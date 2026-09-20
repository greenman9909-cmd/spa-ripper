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

    def test_yoru_media_assets_are_discovered(self):
        assets = self.make_scraper().extract_js_assets(
            'const rain = "assets/cozy-rain.mp4"; '
            'const meadow = `assets/moonlit-meadow.mp4`; '
            'const stream = "/video/master.m3u8?token=abc"; '
            'const poster = "./assets/hero.jpg";'
        )
        self.assertIn("assets/cozy-rain.mp4", assets)
        self.assertIn("assets/moonlit-meadow.mp4", assets)
        self.assertIn("/video/master.m3u8?token=abc", assets)
        self.assertIn("./assets/hero.jpg", assets)

    def test_json_method_calls_are_not_treated_as_files(self):
        assets = self.make_scraper().extract_js_assets(
            'const a = await res.json(); '
            'const b = response.json(); '
            'const backup = "yoru-backup.json";'
        )
        self.assertNotIn("res.json", assets)
        self.assertNotIn("response.json", assets)
        self.assertIn("yoru-backup.json", assets)

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
