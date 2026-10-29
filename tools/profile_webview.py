#!/usr/bin/env python3
"""Read-only, numeric WebView sample for this app's debug process; never exports cookies or page URLs.

Requires websocket-client. Creates and removes its own ephemeral ADB forward.
"""
import argparse
import json
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path

import websocket


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seconds", type=float, default=6)
    parser.add_argument("--check-connection", action="store_true", help="also read the browser's cookie-authenticated /api/info status")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 30:
        parser.error("sample duration must be 1–30 seconds")

    def adb(*command):
        return subprocess.check_output([args.adb, "-s", args.serial, *command], text=True, encoding="utf-8", timeout=20).strip()

    process = adb("shell", "pidof", "io.github.opencodeandroid")
    if not process.isdecimal():
        raise RuntimeError("A single running main app process is required")
    port = adb("forward", "tcp:0", "localabstract:webview_devtools_remote_" + process)
    if not port.isdecimal():
        raise RuntimeError("ADB did not return an ephemeral port")
    connection = None
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=5) as response:
            pages = json.load(response)
        pages = [p for p in pages if p.get("type") == "page" and urllib.parse.urlsplit(p.get("url", "")).netloc == "127.0.0.1:49374"]
        if len(pages) != 1:
            raise RuntimeError("Select the official OpenCode page; expected exactly one main page")
        path = urllib.parse.urlsplit(pages[0]["webSocketDebuggerUrl"]).path
        connection = websocket.create_connection(f"ws://127.0.0.1:{port}{path}", timeout=10, suppress_origin=True)
        identifier = 0

        def cdp(method, params=None):
            nonlocal identifier
            identifier += 1
            connection.send(json.dumps({"id": identifier, "method": method, "params": params or {}}))
            while True:
                message = json.loads(connection.recv())
                if message.get("id") == identifier:
                    if "error" in message:
                        raise RuntimeError(message["error"])
                    return message.get("result", {})

        def metrics():
            return {item["name"]: item["value"] for item in cdp("Performance.getMetrics")["metrics"]}

        # Only reads numeric layout/media state; no text, credentials, URLs or DOM mutations.
        expression = """JSON.stringify({visibility:document.visibilityState,width:innerWidth,height:innerHeight,dpr:devicePixelRatio,nodes:document.querySelectorAll('*').length,scrollHeight:document.documentElement.scrollHeight,dark:matchMedia('(prefers-color-scheme: dark)').matches,runningAnimations:document.getAnimations().filter(a=>a.playState==='running').length})"""
        page = json.loads(cdp("Runtime.evaluate", {"expression": expression, "returnByValue": True})["result"]["value"])
        if page["visibility"] != "visible":
            raise RuntimeError("The page must be visible for comparable sampling")
        api_status = None
        if args.check_connection:
            api_status = cdp("Runtime.evaluate", {"expression": "fetch('/api/info',{credentials:'include',cache:'no-store',headers:{Accept:'application/json'}}).then(r=>r.status).catch(()=>0)", "awaitPromise": True, "returnByValue": True})["result"].get("value")
        cdp("Performance.enable")
        adb("shell", "dumpsys", "gfxinfo", "io.github.opencodeandroid", "reset")
        before = metrics()
        time.sleep(args.seconds)
        after = metrics()
        fields = ("Timestamp", "LayoutCount", "RecalcStyleCount", "ScriptDuration", "LayoutDuration", "RecalcStyleDuration", "TaskDuration")
        sample = {"page": page, "sample_seconds": args.seconds,
                  "deltas": {key: round(after.get(key, 0) - before.get(key, 0), 4) for key in fields},
                  "heap_mb": round(after.get("JSHeapUsedSize", 0) / 1048576, 2),
                  "documents": after.get("Documents"), "nodes": after.get("Nodes")}
        if args.check_connection:
            sample["browser_api_status"] = api_status
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(sample, indent=2) + "\n", encoding="utf-8")
        args.output.with_suffix(".gfx.txt").write_text(adb("shell", "dumpsys", "gfxinfo", "io.github.opencodeandroid", "framestats"), encoding="utf-8")
        print(json.dumps(sample, ensure_ascii=False, indent=2))
    finally:
        if connection is not None:
            connection.close()
        adb("forward", "--remove", "tcp:" + port)


if __name__ == "__main__":
    main()
