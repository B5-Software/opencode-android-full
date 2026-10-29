// Test-only protocol peer. No project analysis; real analysis is validated on K30 separately.
let buffer = Buffer.alloc(0)
let unchanged = false
const pull = process.argv.includes("--pull")
const diagnostic = { range: { start: { line: 0, character: 0 }, end: { line: 0, character: 1 } }, severity: 1, message: "真实 UTF-8 error 🧪" }
function send(message) {
  const body = Buffer.from(JSON.stringify(message))
  const frame = Buffer.concat([Buffer.from(`Content-Length: ${body.length}\r\n\r\n`), body])
  // Deliberately split headers and multi-byte UTF-8 sequences.
  for (let index = 0; index < frame.length; index += 7) process.stdout.write(frame.subarray(index, index + 7))
}
function message(value) {
  if (!value.method) return
  const result = (body) => send({ jsonrpc: "2.0", id: value.id, result: body })
  switch (value.method) {
    case "initialize": return result({ capabilities: { hoverProvider: true, ...(pull ? { diagnosticProvider: { interFileDependencies: true, workspaceDiagnostics: false } } : {}) } })
    case "shutdown": return result(null)
    case "exit": process.exit(0)
    case "textDocument/didOpen": case "textDocument/didChange": {
      const doc = value.params.textDocument
      if (!pull) {
        send({ jsonrpc: "2.0", method: "textDocument/publishDiagnostics", params: { uri: doc.uri, version: doc.version - 1, diagnostics: [{ ...diagnostic, message: "STALE" }] } })
        send({ jsonrpc: "2.0", method: "textDocument/publishDiagnostics", params: { uri: doc.uri, version: doc.version, diagnostics: [diagnostic] } })
      }
      return
    }
    case "textDocument/diagnostic": return result(unchanged ? { kind: "unchanged", resultId: "old" } : { kind: "full", resultId: "new", items: [diagnostic] })
    case "fixture/unchanged": unchanged = true; return result(null)
    case "fixture/never": return
    case "fixture/oversized": process.stdout.write("Content-Length: 16777217\r\n\r\n"); return
    case "fixture/register":
      send({ jsonrpc: "2.0", id: "register", method: "client/registerCapability", params: { registrations: [{ id: "symbols", method: "workspace/symbol", registerOptions: {} }] } })
      return result(null)
    default: if (value.id !== undefined) return result(null)
  }
}
process.stdin.on("data", (data) => {
  buffer = Buffer.concat([buffer, data])
  while (true) {
    const end = buffer.indexOf("\r\n\r\n")
    if (end < 0) return
    const length = Number(/Content-Length:\s*(\d+)/i.exec(buffer.subarray(0, end).toString())[1])
    if (buffer.length < end + 4 + length) return
    const value = JSON.parse(buffer.subarray(end + 4, end + 4 + length).toString())
    buffer = buffer.subarray(end + 4 + length)
    message(value)
  }
})
