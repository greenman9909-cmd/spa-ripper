import os
import re
import sys
from urllib.parse import urljoin, urlparse, unquote
from typing import Set, List, Optional
import requests

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

COMMON_EXTRA_ENDPOINTS = [
    "/favicon.ico",
    "/favicon.svg",
    "/apple-touch-icon.png",
    "/icon.png",
    "/icon-192.png",
    "/icon-512.png",
    "/manifest.webmanifest",
    "/manifest.json",
    "/latest.rss",
    "/sw.js",
    "/robots.txt",
]

# Attribute match for HTML tags
HTML_ATTR_REGEX = re.compile(
    r"""(?:src|href|poster|data-src)\s*=\s*["']([^"'#\s>]+)""",
    re.IGNORECASE
)

# Responsive image srcset regex
SRCSET_REGEX = re.compile(
    r"""(?:srcset)\s*=\s*["']([^"']+)""",
    re.IGNORECASE
)

# CSS url(...) detection
CSS_URL_REGEX = re.compile(
    r"""url\(\s*['"]?([^'")]+)['"]?\s*\)""",
    re.IGNORECASE
)

# Regex for modern bundler chunks (Vite, Webpack, Rollup, Turbopack)
JS_CHUNK_REGEX = re.compile(
    r"""(?:["'`/]|(?:\b))([a-zA-Z0-9_\-\.\/]+\.(?:js|css|woff2?|ttf|eot|png|webp|svg|ico|json))""",
    re.IGNORECASE
)

# JSON schema / manifest icon regex
JSON_SRC_REGEX = re.compile(
    r'''"(?:src|url|href|icon|banner)"\s*:\s*"([^"#\s>]+)"''',
    re.IGNORECASE
)


class SpaScraper:
    def __init__(
        self,
        base_url: str,
        output_dir: str,
        user_agent: str = DEFAULT_USER_AGENT,
        extra_endpoints: Optional[List[str]] = None,
        timeout: int = 15,
    ):
        self.base_url = base_url if base_url.endswith("/") else base_url + "/"
        parsed_base = urlparse(self.base_url)
        self.base_netloc = parsed_base.netloc
        self.output_dir = output_dir
        self.timeout = timeout
        self.extra_endpoints = extra_endpoints or COMMON_EXTRA_ENDPOINTS

        self.session = requests.Session()
        self.session.headers.update({"User-Agent": user_agent})

        self.queue: List[str] = []
        self.queued_set: Set[str] = set()
        self.processed_count = 0
        self.total_bytes = 0
        self.failed_urls: List[tuple] = []

    def normalize_url(self, raw_url: str, context_url: str) -> str:
        """Resolve relative URLs, remove hash fragments and query strings for asset matching."""
        joined = urljoin(context_url, raw_url.strip())
        parsed = urlparse(joined)
        clean = parsed._replace(query="", fragment="").geturl()
        return clean

    def url_to_local_path(self, target_url: str) -> str:
        """Translate a target URL to a safe relative path in output_dir."""
        parsed = urlparse(target_url)
        decoded = unquote(parsed.path).lstrip("/")
        if not decoded:
            decoded = "index.html"

        # Prevent directory traversal
        norm = os.path.normpath(decoded)
        if norm.startswith("..") or os.path.isabs(norm):
            norm = norm.replace("..", "").lstrip(os.sep)

        return os.path.join(self.output_dir, norm)

    def enqueue(self, url: str, context_url: str):
        clean_url = self.normalize_url(url, context_url)
        parsed = urlparse(clean_url)

        # Stay on the same host to prevent mirroring the entire internet
        if parsed.netloc == self.base_netloc and clean_url not in self.queued_set:
            self.queued_set.add(clean_url)
            self.queue.append(clean_url)

    def extract_html_assets(self, html: str, context_url: str) -> Set[str]:
        found = set()
        for attr in HTML_ATTR_REGEX.findall(html):
            if not attr.startswith(("data:", "javascript:", "mailto:", "tel:")):
                found.add(attr)

        # Extract items from srcset="img1.jpg 1x, img2.jpg 2x"
        for srcset in SRCSET_REGEX.findall(html):
            parts = [p.strip().split()[0] for p in srcset.split(",") if p.strip()]
            found.update(parts)

        return found

    def extract_css_assets(self, css: str) -> Set[str]:
        found = set()
        for match in CSS_URL_REGEX.findall(css):
            clean = match.strip()
            if not clean.startswith("data:"):
                found.add(clean)
        return found

    def extract_js_assets(self, js: str) -> Set[str]:
        found = set()
        for match in JS_CHUNK_REGEX.findall(js):
            clean = match.strip().lstrip("/")
            # Filter common false positives
            if any(clean.endswith(ext) for ext in (".js", ".css", ".woff2", ".woff", ".webp", ".png", ".svg")):
                found.add("/" + clean)
        return found

    def extract_json_assets(self, json_text: str) -> Set[str]:
        found = set()
        for match in JSON_SRC_REGEX.findall(json_text):
            clean = match.strip()
            if not clean.startswith(("data:", "javascript:")):
                found.add(clean)
        return found

    def run(self):
        os.makedirs(self.output_dir, exist_ok=True)
        print(f"[*] Target URL:  {self.base_url}")
        print(f"[*] Output Dir:  {os.path.abspath(self.output_dir)}")
        print("=" * 60)

        # 1. Fetch Root Index
        print(f"[+] Fetching root shell: {self.base_url}")
        try:
            resp = self.session.get(self.base_url, timeout=self.timeout)
            resp.raise_for_status()
            root_html = resp.text

            index_path = os.path.join(self.output_dir, "index.html")
            with open(index_path, "w", encoding="utf-8") as f:
                f.write(root_html)

            html_size = os.path.getsize(index_path)
            self.total_bytes += html_size
            self.processed_count += 1
            self.queued_set.add(self.normalize_url(self.base_url, self.base_url))
            print(f"[OK] Saved index.html ({html_size:,} bytes)")

            # Enqueue initial root assets
            for asset in self.extract_html_assets(root_html, self.base_url):
                self.enqueue(asset, self.base_url)

        except Exception as exc:
            print(f"[!] Critical error fetching root shell: {exc}", file=sys.stderr)
            return

        # 2. Enqueue common well-known files (PWA manifest, icons, etc.)
        for endpoint in self.extra_endpoints:
            self.enqueue(endpoint, self.base_url)

        # 3. Process the recursive download queue
        queue_idx = 0
        while queue_idx < len(self.queue):
            current_url = self.queue[queue_idx]
            queue_idx += 1

            local_path = self.url_to_local_path(current_url)
            os.makedirs(os.path.dirname(local_path), exist_ok=True)

            try:
                res = self.session.get(current_url, timeout=self.timeout)
                if res.status_code != 200:
                    print(f"[-] [SKIP {res.status_code}] {current_url}")
                    self.failed_urls.append((current_url, f"HTTP {res.status_code}"))
                    continue

                content = res.content
                with open(local_path, "wb") as f:
                    f.write(content)

                size = len(content)
                self.total_bytes += size
                self.processed_count += 1
                rel_dest = os.path.relpath(local_path, self.output_dir)
                print(f"[+] [{self.processed_count}] {rel_dest} ({size:,} bytes)")

                content_type = res.headers.get("Content-Type", "").lower()

                # Deep scan CSS files for fonts and background images
                if local_path.lower().endswith(".css") or "text/css" in content_type:
                    css_text = content.decode("utf-8", errors="ignore")
                    for css_asset in self.extract_css_assets(css_text):
                        self.enqueue(css_asset, current_url)

                # Deep scan JS bundles for dynamically imported chunks and assets
                elif local_path.lower().endswith(".js") or "javascript" in content_type:
                    js_text = content.decode("utf-8", errors="ignore")
                    for js_asset in self.extract_js_assets(js_text):
                        # Chunk paths in JS are typically root-relative
                        self.enqueue(js_asset, self.base_url)

                # Deep scan Web Manifest and JSON configs
                elif local_path.lower().endswith((".webmanifest", ".json")) or "json" in content_type:
                    json_text = content.decode("utf-8", errors="ignore")
                    for json_asset in self.extract_json_assets(json_text):
                        self.enqueue(json_asset, self.base_url)

            except Exception as exc:
                print(f"[!] [FAIL] {current_url}: {exc}")
                self.failed_urls.append((current_url, str(exc)))

        # Summary
        print("\n" + "=" * 60)
        print("CLONE SUMMARY")
        print(f"Total files saved:   {self.processed_count}")
        print(f"Total downloaded:    {self.total_bytes / (1024 * 1024):.2f} MB ({self.total_bytes:,} bytes)")
        print(f"Failed / unreachable:{len(self.failed_urls)}")
        print("=" * 60)
