import os
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.request import urlopen

from spa_ripper.scraper import SpaScraper
from spa_ripper.server import SpaDevServer


class SpaScraperRegressionTests(unittest.TestCase):
    def make_scraper(self):
        return SpaScraper(
            "https://example.com/",
            tempfile.mkdtemp(),
            extra_endpoints=[],
        )

    def test_json_is_not_truncated_to_js(self):
        assets = self.make_scraper().extract_js_assets(
            'const cfg = "config.json";'
        )
        self.assertIn("config.json", assets)
        self.assertNotIn("config.js", assets)

    def test_js_paths_keep_relative_and_root_semantics(self):
        assets = self.make_scraper().extract_js_assets(
            'import("./chunks/Home-ABC.js"); const app = "/assets/app.js";'
        )
        self.assertIn("./chunks/Home-ABC.js", assets)
        self.assertIn("/assets/app.js", assets)

    def test_relative_chunk_resolves_against_current_bundle(self):
        scraper = self.make_scraper()
        url = scraper.normalize_url(
            "./chunk.js",
            "https://example.com/assets/app.js",
        )
        self.assertEqual(
            url,
            "https://example.com/assets/chunk.js",
        )

    def test_query_is_preserved_and_fragment_removed(self):
        scraper = self.make_scraper()
        url = scraper.normalize_url(
            "/app.js?v=42#section",
            scraper.base_url,
        )
        self.assertEqual(
            url,
            "https://example.com/app.js?v=42",
        )

    def test_enqueue_rejects_cross_origin_assets(self):
        scraper = self.make_scraper()
        scraper.enqueue(
            "https://cdn.example.net/app.js",
            scraper.base_url,
        )
        self.assertEqual(scraper.queue, [])


class SpaDevServerRegressionTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()

        with open(
            os.path.join(self.tempdir.name, "index.html"),
            "w",
            encoding="utf-8",
        ) as handle:
            handle.write("SPA INDEX")

        with open(
            os.path.join(self.tempdir.name, "asset.txt"),
            "w",
            encoding="utf-8",
        ) as handle:
            handle.write("STATIC ASSET")

        app = SpaDevServer(
            self.tempdir.name,
            host="127.0.0.1",
            port=0,
        )
        self.httpd = ThreadingHTTPServer(
            ("127.0.0.1", 0),
            app.make_handler(),
        )
        self.thread = threading.Thread(
            target=self.httpd.serve_forever,
            daemon=True,
        )
        self.thread.start()
        self.base = (
            f"http://127.0.0.1:{self.httpd.server_address[1]}"
        )

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        self.tempdir.cleanup()

    def test_static_asset_is_served(self):
        with urlopen(
            self.base + "/asset.txt",
            timeout=2,
        ) as response:
            self.assertEqual(
                response.read().decode(),
                "STATIC ASSET",
            )

    def test_client_route_falls_back_to_index(self):
        with urlopen(
            self.base + "/anime/naruto",
            timeout=2,
        ) as response:
            self.assertEqual(
                response.read().decode(),
                "SPA INDEX",
            )


if __name__ == "__main__":
    unittest.main()
