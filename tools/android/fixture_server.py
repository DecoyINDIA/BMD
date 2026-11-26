#!/usr/bin/env python3
"""Serve a deterministic real HTTP body for Android JNI-to-MediaStore acceptance tests."""
from __future__ import annotations
import argparse
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

BODY = bytes((index * 73 + 19) % 256 for index in range(65_536))
SHA256 = hashlib.sha256(BODY).hexdigest()
ETAG = '"' + SHA256 + '"'
PATH = "/bmd-native-fixture.bin"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, _format: str, *args: object) -> None:
        # The fixture has no source credentials. Never echo arbitrary request headers.
        print(json.dumps({"fixture": True, "request": self.command, "status": str(args[1]) if len(args) > 1 else ""}), flush=True)

    def do_HEAD(self) -> None:
        self.respond(False)

    def do_GET(self) -> None:
        self.respond(True)

    def respond(self, send_body: bool) -> None:
        self.connection.settimeout(15)
        if urlsplit(self.path).path != PATH:
            self.send_error(404)
            return
        start, end, status = 0, len(BODY) - 1, 200
        requested = self.headers.get("Range")
        if requested and self.headers.get("If-Range", ETAG) == ETAG:
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", requested)
            if match:
                start = int(match[1])
                end = min(int(match[2]), len(BODY) - 1) if match[2] else len(BODY) - 1
            if match is None or start > end or start >= len(BODY):
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{len(BODY)}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", ETAG)
        self.send_header("Content-Disposition", 'attachment; filename="bmd-native-fixture.bin"')
        self.send_header("Cache-Control", "no-store")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(BODY)}")
        self.end_headers()
        if send_body:
            try:
                self.wfile.write(BODY[start:end + 1])
            except (BrokenPipeError, ConnectionResetError):
                pass  # A bounded metadata probe may intentionally close its body early.


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8088)
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    port = server.server_port
    manifest = {"schemaVersion": 1, "bytes": len(BODY), "sha256": SHA256,
                "etag": ETAG, "hostUrl": f"http://127.0.0.1:{port}{PATH}",
                "fixtureUrl": f"http://10.0.2.2:{port}{PATH}"}
    if args.manifest:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.manifest.with_suffix(args.manifest.suffix + ".tmp")
        temporary.write_text(json.dumps(manifest, indent=2) + "\n")
        temporary.replace(args.manifest)
    print(json.dumps(manifest), flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
