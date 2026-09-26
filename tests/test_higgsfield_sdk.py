import os
import unittest
from unittest.mock import patch

import main as seedance_example


class HiggsfieldSeedanceSetupTests(unittest.TestCase):
    def test_seedance_model_id(self):
        self.assertEqual(
            seedance_example.MODEL,
            "bytedance/seedance-2.5/text-to-video",
        )

    def test_extracts_video_url(self):
        self.assertEqual(
            seedance_example._video_url(
                {"status": "completed", "video": {"url": "https://example.test/video.mp4"}}
            ),
            "https://example.test/video.mp4",
        )

    def test_failed_status_is_not_success(self):
        with self.assertRaises(RuntimeError):
            seedance_example._video_url(
                {"status": "failed", "message": "generation failed"}
            )

    def test_missing_key_stops_before_generation(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(seedance_example.main(), 2)


if __name__ == "__main__":
    unittest.main()
