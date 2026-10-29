// SPDX-License-Identifier: GPL-3.0-or-later
import path from "node:path"
import { pathToFileURL } from "node:url"
import { LspClient } from "./client.mjs"
import { configurations, languageId } from "./config.mjs"
import { FilePermissions } from "./access.mjs"

const OPERATIONS = ["status", "diagnostics", "goToDefinition", "findReferences", "hover", "documentSymbol", "workspaceSymbol",
  "goToImplementation", "goToTypeDefinition", "prepareCallHierarchy", "incomingCalls", "outgoingCalls", "completion"]
const METHODS = {
  goToDefinition: ["textDocument/definition", "definitionProvider"], findReferences: ["textDocument/references", "referencesProvider"],
  hover: ["textDocument/hover", "hoverProvider"], documentSymbol: ["textDocument/documentSymbol", "documentSymbolProvider"],
  workspaceSymbol: ["workspace/symbol", "workspaceSymbolProvider"], goToImplementation: ["textDocument/implementation", "implementationProvider"],
  goToTypeDefinition: ["textDocument/typeDefinition", "typeDefinitionProvider"], completion: ["textDocument/completion", "completionProvider"],
  prepareCallHierarchy: ["textDocument/prepareCallHierarchy", "callHierarchyProvider"],
}

export class LspWorkspace {
  constructor(directory, options, worktree = directory) {
    this.directory = directory
    this.options = options
    this.clients = new Map()
    this.files = new FilePermissions(directory, worktree)
    this.disposed = false
    this.idleTimer = setInterval(() => {
      for (const [id, entry] of this.clients) if (Date.now() - entry.client.lastUsed > 120000) {
        this.clients.delete(id); entry.client.close().catch(() => {})
      }
    }, 30000)
    this.idleTimer.unref?.()
  }

  async definitions() {
    const definitions = await configurations(this.directory, this.options)
    for (const [id, entry] of this.clients) {
      const current = definitions.find((definition) => definition.id === id && definition.available)
      if (!current || JSON.stringify(current) !== entry.signature || entry.client.closed) {
        this.clients.delete(id); await entry.client.close()
      }
    }
    return definitions
  }

  async safeFile(input) {
    return (await this.files.resolve(input)).file
  }

  async readPermission(ctx, context, input, file) {
    const token = await this.files.authorize(ctx, context, input)
    if (file && token.file !== file) throw new Error("LSP file target changed before authorization")
    return token
  }

  client(definition) {
    if (this.disposed) throw new Error("LSP workspace has closed")
    let entry = this.clients.get(definition.id)
    if (!entry) {
      if (this.clients.size >= 4) throw new Error("At most four language servers can run in one project")
      entry = { client: new LspClient(definition, this.directory), signature: JSON.stringify(definition) }
      this.clients.set(definition.id, entry)
    }
    entry.client.lastUsed = Date.now()
    return entry.client
  }

  async status() {
    return (await this.definitions()).map((definition) => ({
      id: definition.id, available: definition.available, extensions: definition.extensions,
      running: !this.clients.get(definition.id)?.client.closed && this.clients.has(definition.id),
      pid: this.clients.get(definition.id)?.client.child.pid ?? null,
    }))
  }

  async query(input, signal, authorization) {
    if (!OPERATIONS.includes(input.operation)) throw new Error("Unknown LSP operation")
    if (input.operation === "status") return { servers: await this.status() }
    const file = await this.files.check(authorization)
    const definitions = (await this.definitions()).filter((definition) => definition.available && definition.extensions.includes(path.extname(file).toLowerCase()))
    if (!definitions.length) throw new Error("No installed language server for this file. Install language servers in Runtime Settings or configure lsp in opencode.json.")
    if (signal?.aborted) throw new Error("LSP request cancelled")
    return { operation: input.operation, filePath: file, results: await Promise.all(definitions.map(async (definition) => {
      const client = this.client(definition)
      const document = await client.sync(file, languageId(file), () => this.files.check(authorization))
      if (input.operation === "diagnostics")
        return { server: definition.id, diagnostics: await client.collectDiagnostics(document, signal) }
      const position = { line: (input.line ?? 1) - 1, character: (input.character ?? 1) - 1 }
      if (position.line < 0 || position.character < 0 || !Number.isSafeInteger(position.line) || !Number.isSafeInteger(position.character))
        throw new Error("LSP line and character must be positive integers (1-based UTF-16)")
      const hierarchy = input.operation === "incomingCalls" || input.operation === "outgoingCalls"
      const [method, capability] = METHODS[hierarchy ? "prepareCallHierarchy" : input.operation]
      if (!client.capabilities[capability]) return { server: definition.id, unsupported: input.operation }
      const params = { textDocument: { uri: document.uri }, position }
      if (input.operation === "findReferences") params.context = { includeDeclaration: true }
      if (input.operation === "completion") params.context = { triggerKind: 1 }
      if (input.operation === "workspaceSymbol") {
        if (typeof input.query !== "string" || input.query.length > 1024) throw new Error("workspaceSymbol requires a query up to 1024 characters")
      }
      let result = await client.request(method, input.operation === "workspaceSymbol" ? { query: input.query } : params, { signal })
      if (hierarchy) result = await Promise.all((result ?? []).slice(0, 20).map((item) =>
        client.request(`callHierarchy/${input.operation}`, { item }, { signal })))
      return { server: definition.id, result }
    })) }
  }

  async changed(file) {
    const uri = pathToFileURL(file).href
    for (const entry of this.clients.values()) {
      try { await entry.client.ready; entry.client.notify("workspace/didChangeWatchedFiles", { changes: [{ uri, type: 2 }] }) } catch {}
    }
  }

  async close() {
    this.disposed = true
    clearInterval(this.idleTimer)
    const clients = [...this.clients.values()]
    this.clients.clear()
    await Promise.all(clients.map(({ client }) => client.close()))
  }
}

export default {
  id: "opencode.android.lsp",
  async setup(ctx) {
    const workspace = new LspWorkspace(ctx.location.directory, ctx.options, ctx.location.project.directory)
    let fallbackActive = false
    await ctx.tool.transform((editor) => {
      // A future upstream dev with its own LSP tool takes precedence.
      if (editor.list().some((tool) => tool.name === "lsp")) { fallbackActive = false; return }
      fallbackActive = true
      editor.add({
        name: "lsp", options: { codemode: false, permission: "lsp" },
        description: "Query installed language servers for fresh diagnostics, definitions, references, hover, symbols, implementations, types, call hierarchy or completion. " +
          "Use status to inspect available servers. External files require directory approval followed by read approval. line and character are 1-based; character counts UTF-16 code units. " +
          "C/C++ clangd and Python pylsp can be installed in Runtime Settings. Other installed/configured LSP servers use opencode.json lsp settings.",
        input: { type: "object", properties: {
          operation: { type: "string", enum: OPERATIONS }, filePath: { type: "string" },
          line: { type: "integer", minimum: 1 }, character: { type: "integer", minimum: 1 }, query: { type: "string", maxLength: 1024 },
        }, required: ["operation"], additionalProperties: false },
        async execute(input, context) {
          let authorization
          if (input.operation !== "status") {
            const file = await workspace.safeFile(input.filePath)
            authorization = await workspace.readPermission(ctx, context, input.filePath, file)
          }
          return { content: JSON.stringify(await workspace.query(input, context.signal, authorization)) }
        },
      })
    })
    await ctx.tool.hook("execute.after", async (event) => {
      if (!fallbackActive || event.status !== "completed" || !["write", "edit"].includes(event.tool) || typeof event.input?.path !== "string") return
      try {
        const file = await workspace.safeFile(event.input.path)
        const servers = await workspace.definitions()
        if (!servers.some((definition) => definition.available && definition.extensions.includes(path.extname(file).toLowerCase()))) return
        const authorization = await workspace.readPermission(ctx, event, event.input.path, file)
        await workspace.changed(file)
        const result = await workspace.query({ operation: "diagnostics", filePath: file }, event.signal, authorization)
        const diagnostics = result.results.flatMap((item) => item.diagnostics.map((diagnostic) => ({ server: item.server, ...diagnostic }))).slice(0, 50)
        const addition = `LSP diagnostics (${diagnostics.length}):\n${JSON.stringify(diagnostics)}`
        const content = event.result.content
        event.result = { ...event.result, content: Array.isArray(content) ? [...content, { type: "text", text: addition }] : `${content ?? ""}\n${addition}`,
          metadata: { ...event.result.metadata, androidLspDiagnostics: diagnostics.length } }
      } catch {
        // A missing/crashed language server must not turn a successfully saved file into a failed write.
        event.result = { ...event.result, metadata: { ...event.result.metadata, androidLspUnavailable: true } }
      }
    })
    await ctx.rpc.register({ id: "opencode.android.lsp", methods: {
      status: { input: { type: "object" }, output: { type: "array", items: { type: "object" } } },
    }, events: {} }, { status: () => workspace.status() })
    return () => workspace.close()
  },
}
