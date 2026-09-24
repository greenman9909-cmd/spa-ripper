from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
from collections import deque
from dataclasses import asdict, dataclass
from html.parser import HTMLParser
from pathlib import Path
from typing import Deque, List, Optional, Set, Tuple
from urllib.parse import urldefrag, urljoin, urlparse
from urllib.robotparser import RobotFileParser

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry


USER_AGENT = "SPA-Ripper-PublicMirror/2.0"


@dataclass(frozen=True)
class QueueItem:
    url: str
    depth: int


@dataclass
class SavedFile:
    url: str
    path: str
    status: int
    content_type: str
    size: int
    sha256: str


class DiscoveryParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links: Set[str] = set()
        self.assets: Set[str] = set()

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]):
        values = {k.lower(): v for k, v in attrs if v}
        tag = tag.lower()

        if tag == "a" and values.get("href"):
            self.links.add(values["href"])

        candidates = {
            "script": ("src",),
            "img": ("src", "data-src"),
            "source": ("src",),
            "video": ("src", "poster"),
            "audio": ("src",),
            "track": ("src",),
            "link": ("href",),
            "iframe": ("src",),
        }

        for attr in candidates.get(tag, ()):
            value = values.get(attr)
            if value:
                self.assets.add(value)

        for attr in ("srcset",):
            value = values.get(attr)
            if value:
                for part in value.split(","):
                    part = part.strip()
                    if part:
                        self.assets.add(part.split()[0])


class PublicMirror:
    def __init__(
        self,
        base_url: str,
        output: str,
        max_depth: int = 2,
        max_files: int = 30,
        delay: float = 0.8,
        timeout: int = 15,
        respect_robots: bool = True,
    ):
        parsed = urlparse(base_url if "://" in base_url else "https://" + base_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("Invalid HTTP(S) URL")

        self.base_url = parsed._replace(fragment="").geturl()
        self.origin = (parsed.scheme.lower(), parsed.netloc.lower())
        self.output = Path(output).resolve()
        self.max_depth = max_depth
        self.max_files = max_files
        self.delay = max(delay, 0.5)
        self.timeout = timeout

        self.queue: Deque[QueueItem] = deque()
        self.seen: Set[str] = set()
        self.saved: List[SavedFile] = []
        self.failed = []

        self.session = requests.Session()
        self.session.headers.update({"User-Agent": USER_AGENT})
        retry = Retry(
            total=2,
            backoff_factor=0.5,
            status_forcelist=(429, 500, 502, 503, 504),
            allowed_methods=frozenset({"GET", "HEAD"}),
            respect_retry_after_header=True,
        )
        self.session.mount("https://", HTTPAdapter(max_retries=retry))
        self.session.mount("http://", HTTPAdapter(max_retries=retry))

        self.robots = None
        if respect_robots:
            parser = RobotFileParser()
            parser.set_url(urljoin(self.base_url, "/robots.txt"))
            try:
                parser.read()
                self.robots = parser
            except Exception:
                self.robots = None

    def normalize(self, raw: str, context: str) -> Optional[str]:
        raw = (raw or "").strip()
        if not raw or raw.startswith(("#", "data:", "javascript:", "mailto:", "tel:", "blob:")):
            return None

        absolute, _ = urldefrag(urljoin(context, raw))
        parsed = urlparse(absolute)
        if parsed.scheme not in ("http", "https"):
            return None
        if (parsed.scheme.lower(), parsed.netloc.lower()) != self.origin:
            return None
        return parsed.geturl()

    def allowed(self, url: str) -> bool:
        if self.robots is None:
            return True
        return self.robots.can_fetch(USER_AGENT, url)

    def enqueue(self, raw: str, context: str, depth: int):
        if depth > self.max_depth:
            return
        url = self.normalize(raw, context)
        if not url or url in self.seen or not self.allowed(url):
            return
        self.seen.add(url)
        self.queue.append(QueueItem(url=url, depth=depth))

    def local_path(self, url: str, content_type: str) -> Path:
        parsed = urlparse(url)
        rel = parsed.path.lstrip("/")

        if not rel:
            rel = "index.html"
        elif rel.endswith("/"):
            rel += "index.html"
        elif "text/html" in content_type and not os.path.splitext(rel)[1]:
            rel = os.path.join(rel, "index.html")

        if parsed.query:
            base, ext = os.path.splitext(rel)
            digest = hashlib.sha256(parsed.query.encode()).hexdigest()[:10]
            rel = base + "__q_" + digest + ext

        target = (self.output / rel).resolve()
        if target != self.output and self.output not in target.parents:
            raise ValueError("Unsafe output path")
        return target

    def discover(self, body: bytes, content_type: str, url: str, depth: int):
        if "text/html" not in content_type:
            return

        parser = DiscoveryParser()
        try:
            parser.feed(body.decode("utf-8", errors="ignore"))
        except Exception:
            return

        for asset in parser.assets:
            self.enqueue(asset, url, depth + 1)

        for link in parser.links:
            self.enqueue(link, url, depth + 1)

    def run(self):
        self.output.mkdir(parents=True, exist_ok=True)
        self.enqueue(self.base_url, self.base_url, 0)

        while self.queue and len(self.saved) < self.max_files:
            item = self.queue.popleft()
            try:
                response = self.session.get(
                    item.url,
                    timeout=self.timeout,
                    allow_redirects=True,
                )

                final = self.normalize(response.url, item.url)
                if not final:
                    raise RuntimeError("Cross-origin redirect blocked")

                response.raise_for_status()

                content_type = response.headers.get("content-type", "").lower()
                body = response.content

                path = self.local_path(final, content_type)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(body)

                record = SavedFile(
                    url=final,
                    path=str(path.relative_to(self.output)),
                    status=response.status_code,
                    content_type=content_type,
                    size=len(body),
                    sha256=hashlib.sha256(body).hexdigest(),
                )
                self.saved.append(record)

                print(f"[+] {record.status} {record.url} -> {record.path}")
                self.discover(body, content_type, final, item.depth)

            except Exception as exc:
                self.failed.append({"url": item.url, "error": str(exc)})
                print(f"[!] {item.url}: {exc}")

            time.sleep(self.delay)

        manifest = {
            "target": self.base_url,
            "limits": {
                "max_depth": self.max_depth,
                "max_files": self.max_files,
                "delay": self.delay,
            },
            "saved": [asdict(item) for item in self.saved],
            "failed": self.failed,
        }
        (self.output / "manifest.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )

        print(f"Saved {len(self.saved)} files; {len(self.failed)} failed.")
        return manifest


def main():
    parser = argparse.ArgumentParser(
        description="Low-impact same-origin public website mirror."
    )
    parser.add_argument("url")
    parser.add_argument("-o", "--output", default="public-mirror")
    parser.add_argument("--max-depth", type=int, default=2)
    parser.add_argument("--max-files", type=int, default=30)
    parser.add_argument("--delay", type=float, default=0.8)
    parser.add_argument("--timeout", type=int, default=15)
    parser.add_argument("--ignore-robots", action="store_true")
    args = parser.parse_args()

    PublicMirror(
        args.url,
        args.output,
        max_depth=args.max_depth,
        max_files=args.max_files,
        delay=args.delay,
        timeout=args.timeout,
        respect_robots=not args.ignore_robots,
    ).run()


if __name__ == "__main__":
    main()
