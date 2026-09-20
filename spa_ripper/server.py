import os
import sys
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
import requests


class SpaDevServer:
    """Generic local server for a cloned SPA with optional API reverse proxying."""

    def __init__(
        self,
        directory: str,
        port: int = 8080,
        host: str = "0.0.0.0",
        proxy_target=None,
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
                    return

                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')

            def do_GET(self):
                if proxy_target and self.path.startswith(api_prefix):
                    self.proxy_request("GET")
                    return

                req_path = self.translate_path(self.path)
                if os.path.exists(req_path) and not os.path.isdir(req_path):
                    return super().do_GET()

                if not os.path.splitext(self.path.split("?", 1)[0])[1]:
                    self.path = "/index.html"
                    return super().do_GET()

                return super().do_GET()

            def proxy_request(self, method: str):
                target_url = f"{proxy_target}{self.path}"
                body = None
                if method == "POST":
                    length = int(self.headers.get("Content-Length", 0))
                    if length > 0:
                        body = self.rfile.read(length)

                headers = {
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept": self.headers.get("Accept", "*/*"),
                }
                content_type = self.headers.get("Content-Type")
                if content_type:
                    headers["Content-Type"] = content_type

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
                        if k.lower() not in (
                            "content-length",
                            "content-encoding",
                            "transfer-encoding",
                            "connection",
                        ):
                            self.send_header(k, v)
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Content-Length", str(len(res.content)))
                    self.end_headers()
                    self.wfile.write(res.content)
                except Exception as exc:
                    payload = json.dumps(
                        {"ok": False, "proxy_error": str(exc)}
                    ).encode("utf-8")
                    self.send_response(502)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)

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
        finally:
            server.server_close()


PORT = 8080
DIRECTORY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ani.pm_frontend")
REANIME_ORIGIN = os.environ.get("REANIME_URL", "https://owais-anime-stream-open.onrender.com").rstrip("/")
FALLBACK_ORIGIN = "https://ani.pm"

SELECTION_MAP = {}
LAST_ANILIST_ID = "189046"


class AniPMProxyHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def do_POST(self):
        if self.path.startswith("/api/"):
            self.proxy_api("POST")
        else:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(b'{"ok":true}')

    def do_GET(self):
        # Forward API requests to ReAnime or Fallback
        if self.path.startswith("/api/") or self.path.startswith("/embed/"):
            self.proxy_api("GET")
            return

        # Serve static file if it exists
        req_path = self.translate_path(self.path)
        if os.path.exists(req_path) and not os.path.isdir(req_path):
            return super().do_GET()

        # SPA client routing fallback
        if not os.path.splitext(self.path.split("?")[0])[1]:
            self.path = "/index.html"
            return super().do_GET()

        return super().do_GET()

    def proxy_api(self, method):
        global LAST_ANILIST_ID
        body = None
        if method == "POST":
            length = int(self.headers.get("Content-Length", 0))
            if length > 0:
                body = self.rfile.read(length)

        # Intercept settlar session: serve ReAnime embed player directly
        if self.path.startswith("/api/anime/settlar/session"):
            parsed_qs = parse_qs(urlparse(self.path).query)
            ep = parsed_qs.get("ep", ["1"])[0]
            sel = parsed_qs.get("selection", [""])[0]
            ani_id = SELECTION_MAP.get(sel, LAST_ANILIST_ID)
            embed_url = f"{REANIME_ORIGIN}/embed/ani/{ani_id}/{ep}"

            payload = json.dumps({
                "embedUrl": embed_url,
                "expiresAt": 2147483647,
                "provider": "anipm"
            }).encode("utf-8")

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        # 1. Try ReAnime backend first
        reanime_url = f"{REANIME_ORIGIN}{self.path}"
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Accept": "application/json, */*",
        }

        try:
            resp = requests.request(
                method=method,
                url=reanime_url,
                headers=headers,
                data=body,
                timeout=5,
                allow_redirects=True,
            )
            # If ReAnime answered successfully (200..399), return its response!
            if resp.status_code < 400:
                self.send_response(resp.status_code)
                for k, v in resp.headers.items():
                    if k.lower() not in ("content-length", "content-encoding", "transfer-encoding"):
                        self.send_header(k, v)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Content-Length", str(len(resp.content)))
                self.end_headers()
                self.wfile.write(resp.content)
                return
        except Exception:
            pass

        # 2. Fallback to upstream origin if ReAnime returned 404 or was unreachable
        fallback_url = f"{FALLBACK_ORIGIN}{self.path}"
        fallback_headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer": "https://ani.pm/",
            "Origin": "https://ani.pm",
        }

        try:
            resp = requests.request(
                method=method,
                url=fallback_url,
                headers=fallback_headers,
                data=body,
                timeout=10,
                allow_redirects=True,
            )

            # If playback bootstrap is returned, extract the anilistId and selection token
            if "/api/anime/playback-bootstrap" in self.path and resp.status_code == 200:
                try:
                    data = resp.json()
                    core = data.get("core") or {}
                    ani_id = core.get("anilistId")
                    sel = data.get("settlarSelection")
                    if ani_id:
                        LAST_ANILIST_ID = str(ani_id)
                        if sel:
                            SELECTION_MAP[sel] = str(ani_id)
                except Exception:
                    pass

            self.send_response(resp.status_code)
            for k, v in resp.headers.items():
                if k.lower() not in ("content-length", "content-encoding", "transfer-encoding"):
                    self.send_header(k, v)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(resp.content)))
            self.end_headers()
            self.wfile.write(resp.content)
        except Exception:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(b'{"ok":true}')


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), AniPMProxyHandler)
    print(f"[*] Serving ani.pm frontend at http://localhost:{PORT}")
    print(f"[*] Primary backend: {REANIME_ORIGIN}")
    print(f"[*] Upstream fallback: {FALLBACK_ORIGIN}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server.")
        server.server_close()


if __name__ == "__main__":
    main()
