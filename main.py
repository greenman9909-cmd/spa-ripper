from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
import higgsfield_client

MODEL = "bytedance/seedance-2.5/text-to-video"

# Local-only secret file. It is ignored by Git.
load_dotenv(Path(__file__).with_name(".env.local"))


def _video_url(result: Any) -> str | None:
    if not isinstance(result, dict):
        return None

    status = str(result.get("status") or "").lower()
    if status in {"failed", "cancelled", "canceled", "nsfw", "moderated"}:
        message = result.get("error") or result.get("message") or status
        raise RuntimeError(f"Higgsfield request ended with status '{status}': {message}")

    video = result.get("video")
    if isinstance(video, dict) and isinstance(video.get("url"), str):
        return video["url"]

    videos = result.get("videos")
    if isinstance(videos, list):
        for item in videos:
            if isinstance(item, dict) and isinstance(item.get("url"), str):
                return item["url"]

    # Keep compatibility with SDK response variants that expose generic results.
    results = result.get("results")
    if isinstance(results, dict):
        for key in ("raw", "min"):
            item = results.get(key)
            if isinstance(item, dict) and isinstance(item.get("url"), str):
                return item["url"]

    return None


def main() -> int:
    if not os.getenv("HF_KEY"):
        print(
            "HF_KEY is not configured. Put a rotated Higgsfield credential in "
            ".env.local as HF_KEY=key-id:key-secret.",
            file=sys.stderr,
        )
        return 2

    try:
        result = higgsfield_client.subscribe(
            MODEL,
            arguments={
                "prompt": "A cinematic scene at sunset",
                "duration": 5,
                "resolution": "720p",
                "aspect_ratio": "16:9",
                "output_format": "mp4",
                "generate_audio": False,
            },
        )
    except Exception as exc:
        print(f"Higgsfield generation failed: {exc}", file=sys.stderr)
        return 1

    url = _video_url(result)
    if not url:
        status = result.get("status") if isinstance(result, dict) else None
        print(
            f"Higgsfield returned no completed video URL"
            + (f" (status: {status})" if status else "."),
            file=sys.stderr,
        )
        return 1

    print(url)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
