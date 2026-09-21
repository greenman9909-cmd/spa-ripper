import contextlib
import io
import ipaddress
import os
import shutil
import socket
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlparse

import requests
from flask import Flask, abort, jsonify, render_template, request, send_file, send_from_directory

from spa_ripper.path_utils import query_variant_relpath
from spa_ripper.scraper import DEFAULT_USER_AGENT, SpaScraper


BASE_DIR = Path(__file__).resolve().parent
JOB_ROOT = Path(os.environ.get("SPA_RIPPER_WEB_JOBS", BASE_DIR / "web_jobs")).resolve()
JOB_ROOT.mkdir(parents=True, exist_ok=True)

ALLOW_PRIVATE = os.environ.get("SPA_RIPPER_ALLOW_PRIVATE", "").lower() in {"1", "true", "yes"}
MAX_LOG_CHARS = 120_000

app = Flask(__name__, template_folder="web/templates", static_folder="web/static")
app.config["MAX_CONTENT_LENGTH"] = 64 * 1024

_jobs = {}
_jobs_lock = threading.Lock()


def _validate_public_url(target_url: str) -> None:
    parsed = urlparse(target_url)
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("Only http:// and https:// URLs are supported.")
    if not parsed.hostname:
        raise ValueError("That URL does not contain a valid hostname.")
    if parsed.username or parsed.password:
        raise ValueError("Credentials in URLs are not supported.")

    if ALLOW_PRIVATE:
        return

    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        answers = socket.getaddrinfo(parsed.hostname, port, type=socket.SOCK_STREAM)
    except socket.gaierror as exc:
        raise ValueError(f"Could not resolve hostname: {exc}") from exc

    seen = set()
    for answer in answers:
        ip_text = answer[4][0]
        if ip_text in seen:
            continue
        seen.add(ip_text)
        ip = ipaddress.ip_address(ip_text)
        if (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_multicast
            or ip.is_reserved
            or ip.is_unspecified
        ):
            raise ValueError("Private, loopback, link-local, and reserved network targets are blocked.")


class GuardedSession(requests.Session):
    def send(self, request, **kwargs):
        _validate_public_url(request.url)
        return super().send(request, **kwargs)


class JobLog(io.TextIOBase):
    def __init__(self, job_id):
        self.job_id = job_id

    def write(self, text):
        if not text:
            return 0
        with _jobs_lock:
            job = _jobs.get(self.job_id)
            if not job:
                return len(text)
            job["log"] += text
            if len(job["log"]) > MAX_LOG_CHARS:
                job["log"] = job["log"][-MAX_LOG_CHARS:]
        return len(text)

    def flush(self):
        pass


def normalize_target(raw_url: str) -> str:
    value = (raw_url or "").strip()
    if not value:
        raise ValueError("Enter a website URL.")
    if not value.startswith(("http://", "https://")):
        value = "https://" + value
    _validate_public_url(value)
    return value


def job_public(job):
    return {
        "id": job["id"],
        "url": job["url"],
        "status": job["status"],
        "created_at": job["created_at"],
        "finished_at": job.get("finished_at"),
        "files": job.get("files", 0),
        "bytes": job.get("bytes", 0),
        "failed": job.get("failed", 0),
        "error": job.get("error"),
        "log": job.get("log", ""),
        "preview_url": f"/preview/{job['id']}/" if job["status"] == "done" else None,
        "download_url": f"/api/jobs/{job['id']}/download" if job["status"] == "done" else None,
    }


def run_job(job_id):
    with _jobs_lock:
        job = _jobs[job_id]
        job["status"] = "running"

    output_dir = JOB_ROOT / job_id / "frontend"
    output_dir.mkdir(parents=True, exist_ok=True)
    writer = JobLog(job_id)

    try:
        scraper = SpaScraper(
            base_url=job["url"],
            output_dir=str(output_dir),
            timeout=20,
        )
        safe_session = GuardedSession()
        safe_session.headers.update({"User-Agent": DEFAULT_USER_AGENT})
        scraper.session = safe_session

        with contextlib.redirect_stdout(writer), contextlib.redirect_stderr(writer):
            scraper.run()

        if not (output_dir / "index.html").exists():
            raise RuntimeError("Clone finished without creating index.html.")

        with _jobs_lock:
            job = _jobs[job_id]
            job["status"] = "done"
            job["files"] = scraper.processed_count
            job["bytes"] = scraper.total_bytes
            job["failed"] = len(scraper.failed_urls)
            job["finished_at"] = time.time()

    except Exception as exc:
        writer.write(f"\n[!] {exc}\n")
        with _jobs_lock:
            job = _jobs[job_id]
            job["status"] = "error"
            job["error"] = str(exc)
            job["finished_at"] = time.time()


@app.get("/")
def home():
    return render_template("index.html")


@app.get("/api/health")
def health():
    return jsonify({"ok": True, "service": "SPA-Ripper Web"})


@app.post("/api/clone")
def clone():
    payload = request.get_json(silent=True) or {}
    try:
        target = normalize_target(payload.get("url", ""))
    except ValueError as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400

    job_id = uuid.uuid4().hex[:12]
    job = {
        "id": job_id,
        "url": target,
        "status": "queued",
        "created_at": time.time(),
        "finished_at": None,
        "files": 0,
        "bytes": 0,
        "failed": 0,
        "error": None,
        "log": "",
    }

    with _jobs_lock:
        _jobs[job_id] = job

    threading.Thread(target=run_job, args=(job_id,), daemon=True).start()
    return jsonify({"ok": True, "job": job_public(job)}), 202


@app.get("/api/jobs")
def jobs():
    with _jobs_lock:
        items = sorted(_jobs.values(), key=lambda item: item["created_at"], reverse=True)[:20]
        return jsonify({"jobs": [job_public(item) for item in items]})


@app.get("/api/jobs/<job_id>")
def job_status(job_id):
    with _jobs_lock:
        job = _jobs.get(job_id)
        if not job:
            abort(404)
        return jsonify({"job": job_public(job)})


@app.get("/api/jobs/<job_id>/download")
def job_download(job_id):
    with _jobs_lock:
        job = _jobs.get(job_id)
        if not job or job["status"] != "done":
            abort(404)

    base = JOB_ROOT / job_id
    frontend = base / "frontend"
    archive_base = base / "spa-ripper-clone"
    archive_path = archive_base.with_suffix(".zip")

    if not archive_path.exists():
        shutil.make_archive(str(archive_base), "zip", root_dir=str(frontend))

    host = urlparse(job["url"]).hostname or "frontend"
    safe_host = "".join(ch if ch.isalnum() or ch in ".-_" else "_" for ch in host)
    return send_file(
        archive_path,
        as_attachment=True,
        download_name=f"{safe_host}-frontend.zip",
        mimetype="application/zip",
    )


@app.get("/preview/<job_id>/", defaults={"asset_path": ""})
@app.get("/preview/<job_id>/<path:asset_path>")
def preview(job_id, asset_path):
    with _jobs_lock:
        job = _jobs.get(job_id)
        if not job or job["status"] != "done":
            abort(404)

    root = (JOB_ROOT / job_id / "frontend").resolve()
    query = request.query_string.decode("utf-8", errors="ignore")
    request_rel = asset_path or "index.html"

    if query:
        query_rel = query_variant_relpath("/" + request_rel + "?" + query)
        query_file = (root / query_rel).resolve()
        if (query_file == root or root in query_file.parents) and query_file.is_file():
            return send_from_directory(root, query_rel)

    target = (root / request_rel).resolve()
    if target != root and root not in target.parents:
        abort(404)

    if target.is_file():
        return send_from_directory(root, request_rel)

    if "." not in Path(request_rel).name:
        return send_from_directory(root, "index.html")

    abort(404)


if __name__ == "__main__":
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "7860"))
    app.run(host=host, port=port, threaded=True)
