import unittest
from unittest.mock import patch

import web_app


class WebAppTests(unittest.TestCase):
    def setUp(self):
        self.client = web_app.app.test_client()

    def test_health_endpoint(self):
        response = self.client.get("/api/health")
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.get_json()["ok"])

    def test_homepage_renders_console(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"SPA-Ripper", response.data)
        self.assertIn(b"Clone frontend", response.data)

    def test_private_loopback_target_is_blocked(self):
        with self.assertRaises(ValueError):
            web_app.normalize_target("http://127.0.0.1:8080")

    def test_missing_scheme_defaults_to_https(self):
        with patch.object(web_app, "_validate_public_url"):
            self.assertEqual(
                web_app.normalize_target("example.com"),
                "https://example.com",
            )


if __name__ == "__main__":
    unittest.main()
