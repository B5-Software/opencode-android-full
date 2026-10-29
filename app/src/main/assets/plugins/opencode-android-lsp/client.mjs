// SPDX-License-Identifier: GPL-3.0-or-later
import { spawn } from "node:child_process"
import { open } from "node:fs/promises"
import { constants } from "node:fs"
import { pathToFileURL } from "node:url"

const MAX_FRAME = 16 * 1024 * 1024
const cancelled = () => new Error("LSP request cancelled")
const CAPABILITY = {
  "textDocument/definition": "definitionProvider", "textDocument/references": "referencesProvider",
  "textDocument/hover": "hoverProvider", "textDocument/documentSymbol": "documentSymbolProvider",
  "workspace/symbol": "workspaceSymbolProvider", "textDocument/implementation": "implementationProvider",
  "textDocument/typeDefinition": "typeDefinitionProvider", "textDocument/completion": "completionProvider",
  "textDocument/prepareCallHierarchy": "callHierarchyProvider", "textDocument/diagnostic": "diagnosticProvider",
}

/** A location-owned LSP stdio connection. Every child is still executed by Debian/PRoot. */
export class LspClient {
  constructor(definition, root) {
    this.definition = definition
    this.root = root
    this.pending = new Map()
    this.documents = new Map()
    this.syncs = new Map()
    this.diagnostics = new Map()
    this.diagnosticWaiters = new Set()
    this.registrations = new Map()
    this.buffer = Buffer.alloc(0)
    this.sequence = 0
    this.lastUsed = Date.now()
    this.closed = false
    this.child = spawn(definition.command[0], definition.command.slice(1), {
      cwd: root, env: { ...process.env, ...definition.env }, stdio: ["pipe", "pipe", "pipe"],
      detached: process.platform !== "win32", windowsHide: true,
    })
    this.child.stderr.on("data", () => {}) // User server logs may contain credentials; do not forward them.
    this.child.on("error", () => this.fail(new Error(`Language server ${definition.id} could not start`)))
    this.child.on("exit", (code, signal) => this.fail(new Error(`Language server ${definition.id} exited (${signal ?? code})`)))
    this.child.on("close", () => { this.processClosed = true })
    this.child.stdin.on("error", () => this.fail(new Error(`Language server ${definition.id} input disconnected`)))
    this.child.stdout.on("data", (data) => {
      try { this.receive(data) } catch { this.fail(new Error(`Invalid LSP frame from ${definition.id}`)); this.child.kill("SIGKILL") }
    })
    this.ready = this.initialize()
    // A failed start is also observed later by query(), not emitted as an unhandled rejection.
    this.ready.catch(() => {})
  }

  async initialize() {
    const response = await this.request("initialize", {
      processId: process.pid, clientInfo: { name: "OpenCode Android LSP", version: "0.1.0" },
      rootUri: pathToFileURL(this.root).href,
      workspaceFolders: [{ uri: pathToFileURL(this.root).href, name: this.root.split("/").at(-1) || "workspace" }],
      initializationOptions: this.definition.initialization ?? {},
      capabilities: {
        general: { positionEncodings: ["utf-16"] },
        workspace: { configuration: true, workspaceFolders: true, symbol: { dynamicRegistration: false } },
        textDocument: {
          synchronization: { didSave: true, dynamicRegistration: false },
          publishDiagnostics: { relatedInformation: true, versionSupport: true },
          hover: { contentFormat: ["markdown", "plaintext"] },
          definition: { linkSupport: true }, implementation: { linkSupport: true }, typeDefinition: { linkSupport: true },
          documentSymbol: { hierarchicalDocumentSymbolSupport: true },
          completion: { completionItem: { snippetSupport: false, documentationFormat: ["markdown", "plaintext"] } },
          callHierarchy: { dynamicRegistration: false },
          diagnostic: { dynamicRegistration: false, relatedDocumentSupport: true },
        },
      },
    })
    this.baseCapabilities = response?.capabilities ?? {}
    this.updateCapabilities()
    if (this.capabilities.positionEncoding && this.capabilities.positionEncoding !== "utf-16")
      throw new Error("Language server did not select the supported UTF-16 position encoding")
    this.notify("initialized", {})
    this.notify("workspace/didChangeConfiguration", { settings: this.definition.initialization ?? {} })
  }

  updateCapabilities() {
    this.capabilities = { ...this.baseCapabilities }
    for (const registration of this.registrations.values()) {
      const field = CAPABILITY[registration.method]
      if (field) this.capabilities[field] = registration.registerOptions ?? true
    }
  }

  send(value) {
    if (this.closed || !this.child.stdin.writable) throw new Error(`Language server ${this.definition.id} is disconnected`)
    const body = Buffer.from(JSON.stringify(value))
    if (body.length > MAX_FRAME) throw new Error("LSP request exceeds the frame limit")
    this.child.stdin.write(Buffer.concat([Buffer.from(`Content-Length: ${body.length}\r\n\r\n`), body]))
    this.lastUsed = Date.now()
  }

  notify(method, params) { this.send({ jsonrpc: "2.0", method, params }) }

  request(method, params, { signal, timeout = 15000 } = {}) {
    if (signal?.aborted) return Promise.reject(cancelled())
    return new Promise((resolve, reject) => {
      const id = ++this.sequence
      const finish = (error, result) => {
        if (!this.pending.delete(id)) return
        clearTimeout(timer)
        signal?.removeEventListener("abort", abort)
        error ? reject(error) : resolve(result)
      }
      const abort = () => {
        try { this.notify("$/cancelRequest", { id }) } catch {}
        finish(cancelled())
      }
      const timer = setTimeout(() => {
        try { this.notify("$/cancelRequest", { id }) } catch {}
        finish(new Error(`LSP ${method} timed out`))
      }, timeout)
      this.pending.set(id, finish)
      signal?.addEventListener("abort", abort, { once: true })
      try { this.send({ jsonrpc: "2.0", id, method, params }) } catch (error) { finish(error) }
    })
  }

  receive(data) {
    this.buffer = Buffer.concat([this.buffer, data])
    if (this.buffer.length > MAX_FRAME + 16384) throw new Error("LSP frame too large")
    while (true) {
      const end = this.buffer.indexOf("\r\n\r\n")
      if (end < 0) { if (this.buffer.length > 16384) throw new Error("LSP header too large"); return }
      const match = /^content-length:\s*(\d+)\s*$/im.exec(this.buffer.subarray(0, end).toString())
      const length = match && Number(match[1])
      if (!Number.isSafeInteger(length) || length < 1 || length > MAX_FRAME) throw new Error("Invalid Content-Length")
      if (this.buffer.length < end + 4 + length) return
      const message = JSON.parse(this.buffer.subarray(end + 4, end + 4 + length).toString("utf8"))
      this.buffer = this.buffer.subarray(end + 4 + length)
      this.message(message)
    }
  }

  message(message) {
    if (!message.method && Object.hasOwn(message, "id")) {
      // A custom server may put authentication details in an error; keep those out of shared runtime logs.
      const error = message.error ? new Error(`LSP response error ${message.error.code}`) : null
      this.pending.get(message.id)?.(error, message.result)
      return
    }
    if (message.method === "textDocument/publishDiagnostics") {
      const entry = { ...message.params, received: Date.now() }
      this.diagnostics.set(entry.uri, entry)
      for (const waiter of [...this.diagnosticWaiters]) waiter.check(entry)
    }
    if (Object.hasOwn(message, "id")) {
      let result = null
      switch (message.method) {
        case "workspace/configuration": result = (message.params?.items ?? []).map((item) => {
          let value = this.definition.initialization ?? {}
          for (const key of item.section?.split(".") ?? []) value = value && Object.hasOwn(value, key) ? value[key] : null
          return value ?? null
        }); break
        case "workspace/workspaceFolders": result = [{ uri: pathToFileURL(this.root).href, name: "workspace" }]; break
        case "workspace/applyEdit": result = { applied: false, failureReason: "Read-only LSP queries do not authorize file edits" }; break
        case "client/registerCapability":
          for (const registration of message.params?.registrations ?? []) this.registrations.set(registration.id, registration)
          this.updateCapabilities(); break
        case "client/unregisterCapability":
          for (const registration of message.params?.unregisterations ?? message.params?.unregistrations ?? []) this.registrations.delete(registration.id)
          this.updateCapabilities(); break
        case "window/workDoneProgress/create": break
        case "window/showMessageRequest": break
        default:
          this.send({ jsonrpc: "2.0", id: message.id, error: { code: -32601, message: "Unsupported client method" } })
          return
      }
      this.send({ jsonrpc: "2.0", id: message.id, result })
    }
  }

  sync(file, languageId, validate) {
    const previous = this.syncs.get(file) ?? Promise.resolve()
    const next = previous.catch(() => {}).then(async () => {
      await this.ready
      await validate?.()
      const handle = await open(file, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0))
      let bytes
      try {
        const metadata = await handle.stat()
        if (!metadata.isFile() || metadata.size > 2 * 1024 * 1024) throw new Error("LSP supports regular text files up to 2 MiB")
        bytes = await handle.readFile()
      } finally { await handle.close() }
      if (bytes.length > 2 * 1024 * 1024 || bytes.includes(0)) throw new Error("LSP supports text files up to 2 MiB")
      const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes)
      const uri = pathToFileURL(file).href
      const old = this.documents.get(uri)
      // Servers may suppress identical diagnostics for a no-op didChange. Keep the existing document
      // version when its bytes are unchanged so the current version's diagnostics remain valid.
      if (old && old.text === text) return { uri, version: old.version, changed: old.changed }
      const version = (old?.version ?? 0) + 1
      const changed = Date.now()
      this.documents.set(uri, { text, version, changed })
      if (old) this.notify("textDocument/didChange", { textDocument: { uri, version }, contentChanges: [{ text }] })
      else this.notify("textDocument/didOpen", { textDocument: { uri, languageId, version, text } })
      this.notify("textDocument/didSave", { textDocument: { uri }, text })
      return { uri, version, changed }
    })
    this.syncs.set(file, next)
    next.finally(() => { if (this.syncs.get(file) === next) this.syncs.delete(file) }).catch(() => {})
    return next
  }

  waitDiagnostics(document, signal, timeout = 10000) {
    if (signal?.aborted) return Promise.reject(cancelled())
    return new Promise((resolve, reject) => {
      const finish = (error, result) => {
        clearTimeout(timer)
        this.diagnosticWaiters.delete(waiter)
        signal?.removeEventListener("abort", abort)
        error ? reject(error) : resolve(result)
      }
      const abort = () => finish(cancelled())
      const waiter = {
        fail: (error) => finish(error),
        check: (entry) => {
          if (entry?.uri !== document.uri) return
          if (entry.version !== undefined ? entry.version < document.version : entry.received < document.changed) return
          finish(null, entry.diagnostics ?? [])
        },
      }
      const timer = setTimeout(() => finish(new Error(`Language server ${this.definition.id} did not publish fresh diagnostics`)), timeout)
      this.diagnosticWaiters.add(waiter)
      signal?.addEventListener("abort", abort, { once: true })
      waiter.check(this.diagnostics.get(document.uri))
      if (this.closed) finish(new Error("Language server disconnected"))
    })
  }

  async collectDiagnostics(document, signal) {
    if (this.capabilities.diagnosticProvider) {
      const report = await this.request("textDocument/diagnostic", { textDocument: { uri: document.uri },
        identifier: this.capabilities.diagnosticProvider.identifier }, { signal })
      if (report.kind === "full") {
        const entry = { uri: document.uri, version: document.version, received: Date.now(), diagnostics: report.items }
        this.diagnostics.set(document.uri, entry)
        return report.items
      }
      const previous = this.diagnostics.get(document.uri)
      if (report.kind !== "unchanged" || previous?.version !== document.version)
        throw new Error("Language server returned diagnostics without a valid document version")
      return previous.diagnostics
    }
    return this.waitDiagnostics(document, signal)
  }

  fail(error) {
    this.closed = true
    for (const finish of [...this.pending.values()]) finish(error)
    for (const waiter of [...this.diagnosticWaiters]) waiter.fail(error)
  }

  close() { return this.closing ??= this.closeOwned() }

  forceKillOwnedGroup() {
    // A detached Unix child owns PGID=PID, including workers it subsequently spawns.
    if (process.platform !== "win32" && this.child.pid > 0) {
      try { process.kill(-this.child.pid, "SIGKILL") } catch (error) { if (error.code !== "ESRCH") throw error }
    } else if (!this.processClosed) this.child.kill("SIGKILL")
  }

  async closeOwned() {
    const closed = this.processClosed ? Promise.resolve() : new Promise((resolve, reject) => {
      const finished = () => { clearTimeout(timer); resolve() }
      const timer = setTimeout(() => {
        this.child.removeListener("close", finished)
        reject(new Error(`Language server ${this.definition.id} failed to stop`))
      }, 3000)
      this.child.once("close", finished)
    })
    closed.catch(() => {})
    if (!this.closed) {
      try { await this.request("shutdown", null, { timeout: 750 }); this.notify("exit") } catch {}
    }
    this.fail(new Error("LSP connection closed"))
    this.child.stdin.end()
    const kill = setTimeout(() => this.forceKillOwnedGroup(), 500)
    try { await closed } finally {
      clearTimeout(kill)
      this.forceKillOwnedGroup()
      this.child.stdin.destroy()
      this.child.stdout.destroy()
      this.child.stderr.destroy()
    }
  }
}
