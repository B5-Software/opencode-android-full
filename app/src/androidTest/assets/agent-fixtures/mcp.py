"""Synthetic MCP stdio/Streamable HTTP fixture (JSON and SSE responses); no third-party packages."""
import http.server
import json
import os
import pathlib
import sys

MODE, RECEIPT = sys.argv[1:3]


def record(transport, event, **values):
    with open(RECEIPT, "a", encoding="utf-8") as output:
        output.write(json.dumps(dict(transport=transport, event=event, pid=os.getpid(), **values)) + "\n")


def rpc(message, transport):
    method = message.get("method")
    if "id" not in message:
        return None
    if method == "initialize":
        record(transport, "initialize")
        result = dict(protocolVersion="2025-11-25", capabilities=dict(tools={}, resources={}, prompts={}),
                      serverInfo=dict(name="android-acceptance-" + transport, version="0.0.0"))
    elif method == "tools/list":
        result = dict(tools=[dict(name="add", description="Synthetic fixture integer addition",
                      inputSchema=dict(type="object", properties=dict(a=dict(type="integer"), b=dict(type="integer")),
                                       required=["a", "b"], additionalProperties=False))])
    elif method == "tools/call":
        params = message["params"]
        assert params["name"] == "add"
        value = params["arguments"]["a"] + params["arguments"]["b"]
        record(transport, "call", value=value)
        result = dict(content=[dict(type="text", text="MCP_" + transport.upper() + "_" + str(value))])
    elif method in ("resources/list", "resources/templates/list", "prompts/list"):
        key = {"resources/list": "resources", "resources/templates/list": "resourceTemplates", "prompts/list": "prompts"}[method]
        result = {key: []}
    elif method == "ping":
        result = {}
    else:
        return dict(jsonrpc="2.0", id=message["id"], error=dict(code=-32601, message="Unknown fixture method"))
    return dict(jsonrpc="2.0", id=message["id"], result=result)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def authorize(self):
        if self.headers.get("Authorization") == "Bearer " + TOKEN:
            return True
        self.send(401, b"")
        return False

    def send(self, status, body, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # A stateless Streamable HTTP server need not expose a standalone event stream.
        if self.authorize():
            self.send(405, b"")

    def do_POST(self):
        if not self.authorize():
            return
        size = int(self.headers.get("Content-Length", "0"))
        assert 0 < size < 4 * 1024 * 1024
        message = json.loads(self.rfile.read(size))
        if self.path == "/sse":
            result = rpc(message, "sse")
            if result is None:
                self.send(202, b"")
            else:
                self.send(200, ("event: message\ndata: " + json.dumps(result) + "\n\n").encode(), "text/event-stream")
        elif self.path == "/mcp":
            result = rpc(message, "http")
            self.send(202 if result is None else 200, b"" if result is None else json.dumps(result).encode())
        else:
            self.send(404, b"")

    def do_DELETE(self):
        if self.authorize():
            self.send(204, b"")


if MODE == "--stdio":
    for line in sys.stdin:
        response = rpc(json.loads(line), "stdio")
        if response is not None:
            print(json.dumps(response), flush=True)
elif MODE == "--http":
    READY, TOKEN = sys.argv[3:5]
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    pathlib.Path(READY).write_text(json.dumps(dict(port=server.server_port)), encoding="utf-8")
    server.serve_forever()
else:
    raise ValueError("Unknown fixture mode")
