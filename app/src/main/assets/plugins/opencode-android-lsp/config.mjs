// SPDX-License-Identifier: GPL-3.0-or-later
import path from "node:path"
import os from "node:os"
import { access, readFile } from "node:fs/promises"
import { constants } from "node:fs"

const BUILTINS = [
  { id: "clangd", command: ["clangd", "--background-index=false", "--log=error", "-j=2"], extensions: [".c", ".h", ".cc", ".cpp", ".cxx", ".hpp", ".hh", ".hxx", ".m", ".mm"] },
  { id: "pylsp", command: ["pylsp"], extensions: [".py", ".pyi"] },
  { id: "typescript", command: ["typescript-language-server", "--stdio"], extensions: [".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"] },
  { id: "rust", command: ["rust-analyzer"], extensions: [".rs"] },
  { id: "gopls", command: ["gopls"], extensions: [".go"] },
]

/** JSONC: remove comments/trailing commas while preserving quoted strings and escaped characters. */
export function parseJSONC(text) {
  const stripped = [...text.replace(/^\uFEFF/, "")]
  let quoted = false
  let escaped = false
  for (let index = 0; index < stripped.length; index++) {
    const char = stripped[index]
    if (quoted) {
      if (escaped) escaped = false
      else if (char === "\\") escaped = true
      else if (char === '"') quoted = false
      continue
    }
    if (char === '"') { quoted = true; continue }
    if (char === "/" && stripped[index + 1] === "/") {
      while (index < stripped.length && stripped[index] !== "\n") stripped[index++] = " "
    } else if (char === "/" && stripped[index + 1] === "*") {
      stripped[index++] = " "; stripped[index] = " "
      let closed = false
      while (++index < stripped.length) {
        if (stripped[index] === "*" && stripped[index + 1] === "/") {
          stripped[index++] = " "; stripped[index] = " "; closed = true; break
        }
        if (stripped[index] !== "\n" && stripped[index] !== "\r") stripped[index] = " "
      }
      if (!closed) throw new Error("Unclosed JSONC comment")
    }
  }
  quoted = false; escaped = false
  for (let index = 0; index < stripped.length; index++) {
    const char = stripped[index]
    if (quoted) {
      if (escaped) escaped = false
      else if (char === "\\") escaped = true
      else if (char === '"') quoted = false
    } else if (char === '"') quoted = true
    else if (char === ",") {
      let next = index + 1
      while (/\s/.test(stripped[next] ?? "") && next < stripped.length) next++
      if (stripped[next] === "]" || stripped[next] === "}") stripped[index] = " "
    }
  }
  return JSON.parse(stripped.join(""))
}

async function executable(command, directory, environment) {
  const choices = command.includes("/") || command.includes(path.sep) ? [path.resolve(directory, command)] :
    (environment.PATH ?? "/usr/bin:/bin").split(path.delimiter).map((base) => path.resolve(directory, base || ".", command))
  for (const candidate of choices) {
    try { await access(candidate, constants.X_OK); return true } catch {}
  }
  return false
}

const truthy = (value) => value === "1" || value === "true"

/** Same substitution order and JSON escaping rules as upstream ConfigVariable. Never log secret values. */
export async function substituteConfig(text, directory, environment = process.env) {
  async function files(input) {
    let output = ""; let cursor = 0
    for (const match of input.matchAll(/\{file:[^}]+\}/g)) {
      const token = match[0]; const index = match.index
      output += input.slice(cursor, index)
      const prefix = input.slice(input.lastIndexOf("\n", index - 1) + 1, index).trimStart()
      if (prefix.startsWith("//")) output += token
      else {
        const name = token.slice(6, -1)
        const expanded = name.startsWith("~/") ? path.join(os.homedir(), name.slice(2)) : name
        const file = path.isAbsolute(expanded) ? expanded : path.resolve(directory, expanded)
        // Upstream substitutes an empty value for missing or unreadable references.
        const content = await readFile(file, "utf8").catch(() => "")
        output += JSON.stringify(content.trim()).slice(1, -1)
      }
      cursor = index + token.length
    }
    return output + input.slice(cursor)
  }
  const env = (escape) => text.replace(/\{env:([^}]+)\}/g, (_, name) => {
    const value = environment[name] || ""
    return escape ? JSON.stringify(value).slice(1, -1) : value
  })
  const raw = await files(env(false))
  if (!text.includes("{env:")) return raw
  try { parseJSONC(raw); return raw } catch { return files(env(true)) }
}

export async function configurations(directory, options = {}) {
  const definitions = new Map(BUILTINS.map((server) => [server.id, { ...server }]))
  let enabled = true
  const apply = (lsp) => {
    if (typeof lsp === "boolean") { enabled = lsp; return }
    if (lsp === undefined) return
    if (!lsp || typeof lsp !== "object" || Array.isArray(lsp)) throw new Error("Invalid LSP configuration")
    enabled = true
    for (const [id, value] of Object.entries(lsp)) {
      if (!/^[a-zA-Z0-9._-]+$/.test(id) || !value || typeof value !== "object" || Array.isArray(value)) throw new Error("Invalid LSP server definition")
      definitions.set(id, { ...definitions.get(id), ...value, id })
    }
  }
  const config = process.env.OPENCODE_CONFIG_DIR ?? path.join(process.env.XDG_CONFIG_HOME ?? path.join(os.homedir(), ".config"), "opencode")
  const names = ["opencode.json", "opencode.jsonc"]
  const files = names.map((name) => path.join(config, name))
  if (process.env.OPENCODE_CONFIG) files.push(process.env.OPENCODE_CONFIG)
  if (!truthy(process.env.OPENCODE_CONFIG_PROJECT_DISABLE ?? process.env.OPENCODE_DISABLE_PROJECT_CONFIG)) {
    const roots = []
    let current = directory
    while (true) { roots.unshift(current); const parent = path.dirname(current); if (parent === current) break; current = parent }
    for (const root of roots) for (const name of names) files.push(path.join(root, name))
    for (const root of roots) for (const name of names) files.push(path.join(root, ".opencode", name))
  }
  for (const file of files) {
    let text
    try { text = await readFile(file, "utf8") } catch (error) { if (error.code === "ENOENT") continue; throw new Error("Cannot read LSP configuration file") }
    try { apply(parseJSONC(await substituteConfig(text, path.dirname(file))).lsp) } catch { throw new Error(`Invalid LSP configuration in ${path.basename(file)}`) }
  }
  if (process.env.OPENCODE_CONFIG_CONTENT) {
    try { apply(parseJSONC(await substituteConfig(process.env.OPENCODE_CONFIG_CONTENT, directory)).lsp) } catch { throw new Error("Invalid inline LSP configuration") }
  }
  // Linux managed settings have the final file-based authority, as in upstream Config.load.
  if (process.platform === "linux") for (const name of names) {
    let text
    try { text = await readFile(path.join("/etc/opencode", name), "utf8") } catch (error) { if (error.code === "ENOENT") continue; throw new Error("Cannot read managed LSP configuration") }
    try { apply(parseJSONC(await substituteConfig(text, "/etc/opencode")).lsp) } catch { throw new Error("Invalid managed LSP configuration") }
  }
  if (options.servers !== undefined) apply(options.servers)
  if (options.disabled === true) enabled = false
  if (!enabled) return []
  const servers = []
  for (const definition of definitions.values()) {
    if (definition.disabled) continue
    if (!Array.isArray(definition.command) || !definition.command.length || !definition.command[0] || definition.command.length > 64 ||
        definition.command.some((item) => typeof item !== "string" || item.includes("\0")) ||
        !Array.isArray(definition.extensions) || !definition.extensions.length ||
        definition.extensions.some((item) => typeof item !== "string" || !/^\.[\w+-]+$/.test(item)))
      throw new Error(`Invalid command or extensions for LSP server ${definition.id}`)
    if (definition.env !== undefined) {
      if (!definition.env || typeof definition.env !== "object" || Array.isArray(definition.env) ||
          Object.entries(definition.env).some(([key, value]) => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(key) || typeof value !== "string" || value.includes("\0")))
        throw new Error(`Invalid environment for LSP server ${definition.id}`)
    }
    servers.push({ ...definition, available: await executable(definition.command[0], directory, { ...process.env, ...definition.env }) })
  }
  return servers
}

export function languageId(file) {
  const extension = path.extname(file).toLowerCase()
  return ({ ".c": "c", ".h": "c", ".m": "objective-c", ".mm": "objective-cpp", ".py": "python", ".pyi": "python",
    ".rs": "rust", ".go": "go", ".ts": "typescript", ".tsx": "typescriptreact", ".js": "javascript",
    ".mjs": "javascript", ".cjs": "javascript", ".jsx": "javascriptreact" })[extension] ?? "cpp"
}
