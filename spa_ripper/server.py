import os
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urljoin
from typing import Optional
import requests


class SpaDevServer:
    def __init__(
        self,
        directory: str,
        port: int = 8080,
        host: str = "0.0.0.0",
        proxy_target: Optional[str] = None,
        api_prefix: str = "/api/",
    ):
        self.directory = os.path.abspath(directory)
        self.port = port
        self.host = host
        self.proxy_target = proxy_target.rstrip("/") if proxy_target else None
        self.api_prefix = api_prefix

    def make_handler(self):
        root_dir = self.directory
        proxy_target = self.proxy_target
        api_prefix = self.api_prefix

        class SpaHandler(SimpleHTTPRequestHandler):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, directory=root_dir, **kwargs)

            def do_POST(self):
                if proxy_target and self.path.startswith(api_prefix):
                    self.proxy_request("POST")
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    self.wfile.write(b'{"ok":true}')

            def do_GET(self):
                # Forward to proxy target if requested path is an API endpoint
                if proxy_target and self.path.startswith(api_prefix):
                    self.proxy_request("GET")
                    return

                # If static file exists, serve it directly
                req_path = self.translate_path(self.path)
                if os.path.exists(req_path) and not os.path.isdir(req_path):
                    return super().do_GET()

                # Client-side SPA routing fallback: rewrite non-asset paths to /index.html
                if not os.path.splitext(self.path.split("?")[0])[1]:
                    self.path = "/index.html"
                    return super().do_GET()

                return super().do_GET()

            def proxy_request(self, method: str):
                target_url = f"{proxy_target}{self.path}"
                headers = {
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Origin": proxy_target,
                    "Referer": f"{proxy_target}/",
                }

                body = None
                if method == "POST":
                    length = int(self.headers.get("Content-Length", 0))
                    if length > 0:
                        body = self.rfile.read(length)

                try:
                    res = requests.request(
                        method=method,
                        url=target_url,
                        headers=headers,
                        data=body,
                        timeout=10,
                        allow_redirects=True,
                    )
                    self.send_response(res.status_code)
                    for k, v in res.headers.items():
                        if k.lower() not in ("content-length", "content-encoding", "transfer-encoding"):
                            self.send_header(k, v)
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Content-Length", str(len(res.content)))
                    self.end_headers()
                    self.wfile.write(res.content)
                except Exception as exc:
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    self.wfile.write(b'{"ok":false,"proxy_error":true}')

        return SpaHandler

    def start(self):
        handler_class = self.make_handler()
        server = ThreadingHTTPServer((self.host, self.port), handler_class)
        print(f"[*] SPA Server listening on http://localhost:{self.port}")
        print(f"[*] Serving Directory:     {self.directory}")
        if self.proxy_target:
            print(f"[*] Proxying {self.api_prefix}* -> {self.proxy_target}")
        else:
            print("[*] Running in offline mode (no API proxy)")
        print("[*] Press Ctrl+C to stop.")

        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server.")
            server.server_close()
