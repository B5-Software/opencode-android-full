import test from "node:test"
import assert from "node:assert/strict"
import { mkdtemp, mkdir, writeFile, realpath, symlink } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { getEventListeners } from "node:events"
import { LspClient } from "../app/src/main/assets/plugins/opencode-android-lsp/client.mjs"
import { LspWorkspace } from "../app/src/main/assets/plugins/opencode-android-lsp/index.mjs"
import { parseJSONC, substituteConfig, configurations } from "../app/src/main/assets/plugins/opencode-android-lsp/config.mjs"
import { FilePermissions } from "../app/src/main/assets/plugins/opencode-android-lsp/access.mjs"

async function temporaryProject() {
  const repository = await realpath(fileURLToPath(new URL("../", import.meta.url)))
  const artifacts = path.join(repository, ".cache", "lsp-unit")
  await mkdir(artifacts, { recursive: true })
  assert.ok((await realpath(artifacts)).startsWith(repository + path.sep))
  // Keep these small, synthetic fixtures inspectable in the workspace; no recursive cleanup.
  return mkdtemp(path.join(artifacts, "run-"))
}

async function fixture(run, pull = false) {
  const root = await temporaryProject()
  const file = path.join(root, "示例.c")
  await writeFile(file, "int x = 0;\n")
  const peer = fileURLToPath(new URL("./fixtures/lsp-server.mjs", import.meta.url))
  const client = new LspClient({ id: "fixture", command: [process.execPath, peer, ...(pull ? ["--pull"] : [])], extensions: [".c"] }, root)
  try { await client.ready; await run(client, file) }
  finally { await client.close(); assert.equal(client.processClosed, true) }
}

test("fragmented UTF-8 push diagnostics reject stale versions and keep no-op versions", async () => fixture(async (client, file) => {
  const document = await client.sync(file, "c")
  const results = await client.collectDiagnostics(document)
  assert.equal(results[0].message, "真实 UTF-8 error 🧪")
  const same = await client.sync(file, "c")
  assert.equal(same.version, document.version)
  assert.deepEqual(await client.collectDiagnostics(same), results)
  await client.request("fixture/register", {})
  assert.ok(client.capabilities.workspaceSymbolProvider)
}))

test("cancellation and timeouts clear pending requests and abort listeners", async () => fixture(async (client) => {
  const controller = new AbortController()
  const request = client.request("fixture/never", {}, { signal: controller.signal })
  controller.abort()
  await assert.rejects(request, /cancelled/)
  assert.equal(client.pending.size, 0)
  assert.equal(getEventListeners(controller.signal, "abort").length, 0)
  await assert.rejects(client.request("fixture/never", {}, { timeout: 20 }), /timed out/)
  assert.equal(client.pending.size, 0)
}))

test("oversized frames terminate their peer and reject outstanding requests", async () => fixture(async (client) => {
  await assert.rejects(client.request("fixture/oversized", {}), /Invalid LSP frame/)
  assert.equal(client.closed, true)
  assert.equal(client.pending.size, 0)
}))

test("pull diagnostics never turn an unversioned unchanged report into a clean result", async () => fixture(async (client, file) => {
  const document = await client.sync(file, "c")
  assert.equal((await client.collectDiagnostics(document))[0].message, "真实 UTF-8 error 🧪")
  await client.request("fixture/unchanged", {})
  client.diagnostics.clear()
  await assert.rejects(client.collectDiagnostics(document), /without a valid document version/)
}, true))

test("JSONC keeps comment-like quoted text, escaped quotes, and Unicode", () => {
  assert.deepEqual(parseJSONC('{/* hello */ "url":"https://example.invalid/a//b", "word":"测\\\"试", "list":[1,],}'),
    { url: "https://example.invalid/a//b", word: '测"试', list: [1] })
  assert.throws(() => parseJSONC('{"secret":"not printed" /* unclosed'))
})

test("external files require directory approval before read approval and queries require the token", async () => {
  const root = await temporaryProject()
  const project = path.join(root, "project")
  await import("node:fs/promises").then((fs) => fs.mkdir(project))
  const external = path.join(root, "private.c")
  await writeFile(external, "PRIVATE")
  const workspace = new LspWorkspace(project, {})
  const requests = []
  try {
    await assert.rejects(workspace.query({ operation: "diagnostics", filePath: external }), /authorization/)
    const token = await workspace.readPermission({ permission: { assert: async (value) => { requests.push(value) } } },
      { sessionID: "test", messageID: "message", id: "tool" }, "../private.c")
    assert.deepEqual(requests.map((request) => request.action), ["external_directory", "read"])
    assert.deepEqual(requests[0].resources, [path.join(root, "*").replaceAll("\\", "/")])
    assert.deepEqual(requests[1].resources, [external.replaceAll("\\", "/")])
    assert.equal(token.file, await realpath(external))
    assert.equal(workspace.clients.size, 0)
  }
  finally { await workspace.close() }
})

test("an external-directory rejection never reaches read authorization or a language server", async () => {
  const root = await temporaryProject(); const project = path.join(root, "project"); await mkdir(project)
  await writeFile(path.join(root, "private.c"), "PRIVATE")
  const workspace = new LspWorkspace(project, {})
  const requests = []
  try {
    await assert.rejects(workspace.readPermission({ permission: { assert: async (value) => { requests.push(value.action); throw new Error("rejected") } } },
      { sessionID: "test", messageID: "message", id: "tool" }, "../private.c"), /rejected/)
    assert.deepEqual(requests, ["external_directory"]); assert.equal(workspace.clients.size, 0)
  } finally { await workspace.close() }
})

test("a file in the same worktree uses location-relative read rules", async () => {
  const root = await temporaryProject(); const location = path.join(root, "src"); await mkdir(location)
  await writeFile(path.join(root, "header.h"), "int value;")
  const permissions = new FilePermissions(location, root)
  const requests = []
  await permissions.authorize({ permission: { assert: async (value) => { requests.push(value) } } },
    { sessionID: "test", messageID: "message", id: "tool" }, "../header.h")
  assert.deepEqual(requests.map((value) => value.action), ["read"])
  assert.deepEqual(requests[0].resources, ["../header.h"])
})

test("symlink permissions include alias and target; retargeting invalidates authorization", { skip: process.platform === "win32" }, async () => {
  const root = await temporaryProject(); const project = path.join(root, "project"); await mkdir(project)
  const external = path.join(root, "external.c"); const other = path.join(root, "other.c")
  await writeFile(external, "ONE"); await writeFile(other, "TWO")
  const alias = path.join(project, "linked.c"); await symlink(external, alias)
  const permissions = new FilePermissions(project); const requests = []
  const token = await permissions.authorize({ permission: { assert: async (value) => { requests.push(value) } } },
    { sessionID: "test", messageID: "message", id: "tool" }, "linked.c")
  assert.deepEqual(requests[1].resources, ["linked.c", external])
  // Exact owned file replacement only; no recursive deletion or directory cleanup.
  await import("node:fs/promises").then((fs) => fs.unlink(alias))
  await symlink(other, alias)
  await assert.rejects(permissions.check(token), /changed after authorization/)
})

test("file macros use the config directory, trim and escape content, and skip line comments", async () => {
  const root = await temporaryProject()
  await writeFile(path.join(root, "secret.txt"), '  密钥 "quoted"\nnext\\line\n  ')
  const text = '{\n// {file:missing-in-comment}\n"value":"{file:secret.txt}","missing":"{file:no-such-file}"}'
  const result = parseJSONC(await substituteConfig(text, root, {}))
  assert.equal(result.value, '密钥 "quoted"\nnext\\line'); assert.equal(result.missing, "")
  const env = parseJSONC(await substituteConfig('{"path":"{env:PATH_VALUE}","flag":{env:FLAG}}', root,
    { PATH_VALUE: 'C:\\work\\a"b', FLAG: "true" }))
  assert.equal(env.path, 'C:\\work\\a"b'); assert.equal(env.flag, true)
})

test("environment interpolation does not recursively expand content loaded from a file", async () => {
  const root = await temporaryProject(); await writeFile(path.join(root, "literal.txt"), "{env:SHOULD_STAY_LITERAL}")
  const result = parseJSONC(await substituteConfig('{"value":"{file:literal.txt}"}', root, { SHOULD_STAY_LITERAL: "changed" }))
  assert.equal(result.value, "{env:SHOULD_STAY_LITERAL}")
})

test("JSONC overrides JSON, project files override explicit config, and relative executables use the project", async () => {
  const root = await temporaryProject(); const config = path.join(root, "global"); const project = path.join(root, "project")
  await mkdir(config); await mkdir(project)
  const previous = Object.fromEntries(["OPENCODE_CONFIG_DIR", "OPENCODE_CONFIG_PROJECT_DISABLE", "OPENCODE_DISABLE_PROJECT_CONFIG", "OPENCODE_CONFIG", "OPENCODE_CONFIG_CONTENT"].map((name) => [name, process.env[name]]))
  try {
    process.env.OPENCODE_CONFIG_DIR = config
    delete process.env.OPENCODE_CONFIG_PROJECT_DISABLE; delete process.env.OPENCODE_DISABLE_PROJECT_CONFIG
    delete process.env.OPENCODE_CONFIG_CONTENT
    const settings = (value) => JSON.stringify({ lsp: { fixture: { command: [process.execPath], extensions: [".c"], env: { ORDER: value } } } })
    await writeFile(path.join(config, "opencode.json"), settings("global-json"))
    await writeFile(path.join(config, "opencode.jsonc"), settings("global-jsonc"))
    const explicit = path.join(root, "explicit.json"); await writeFile(explicit, settings("explicit")); process.env.OPENCODE_CONFIG = explicit
    await writeFile(path.join(project, "opencode.json"), settings("project-json"))
    await writeFile(path.join(project, "opencode.jsonc"), settings("project-jsonc"))
    const servers = await configurations(project)
    assert.equal(servers.find((value) => value.id === "fixture").env.ORDER, "project-jsonc")
    const relative = path.relative(project, process.execPath)
    const custom = await configurations(project, { servers: { relative: { command: [relative], extensions: [".c"] } } })
    assert.equal(custom.find((value) => value.id === "relative").available, true)
    const empty = path.join(root, "empty-path"); await mkdir(empty)
    const hidden = await configurations(project, { servers: { hidden: { command: [path.basename(process.execPath)], extensions: [".c"], env: { PATH: empty } } } })
    assert.equal(hidden.find((value) => value.id === "hidden").available, false)
  } finally { for (const [name, value] of Object.entries(previous)) { if (value === undefined) delete process.env[name]; else process.env[name] = value } }
})
