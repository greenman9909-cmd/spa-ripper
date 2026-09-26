import os
from functools import wraps
from urllib.parse import urlparse

import requests
from flask import jsonify, request, session

SUPABASE_URL = os.environ.get("SUPABASE_URL", "").rstrip("/")
SUPABASE_ANON_KEY = os.environ.get("SUPABASE_ANON_KEY", "")
SUPABASE_SERVICE_ROLE_KEY = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "")
OWNER_EMAIL = os.environ.get("WEBLOOM_OWNER_EMAIL", "").strip().lower()

def supabase_ready():
    return bool(SUPABASE_URL and SUPABASE_ANON_KEY and SUPABASE_SERVICE_ROLE_KEY)

def _sb_headers(service=False, token=None):
    key = SUPABASE_SERVICE_ROLE_KEY if service else SUPABASE_ANON_KEY
    headers = {"apikey": key, "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    elif service:
        headers["Authorization"] = f"Bearer {SUPABASE_SERVICE_ROLE_KEY}"
    return headers

def auth_signup(email, password):
    if not supabase_ready():
        raise RuntimeError("Supabase is not configured.")
    r = requests.post(
        f"{SUPABASE_URL}/auth/v1/signup",
        headers=_sb_headers(),
        json={"email": email, "password": password},
        timeout=20,
    )
    data = r.json() if r.content else {}
    if not r.ok:
        raise ValueError(data.get("msg") or data.get("error_description") or "Sign up failed.")
    return data

def auth_signin(email, password):
    if not supabase_ready():
        raise RuntimeError("Supabase is not configured.")
    r = requests.post(
        f"{SUPABASE_URL}/auth/v1/token?grant_type=password",
        headers=_sb_headers(),
        json={"email": email, "password": password},
        timeout=20,
    )
    data = r.json() if r.content else {}
    if not r.ok:
        raise ValueError(data.get("msg") or data.get("error_description") or "Invalid email or password.")
    return data

def auth_user_from_token(token):
    r = requests.get(
        f"{SUPABASE_URL}/auth/v1/user",
        headers=_sb_headers(token=token),
        timeout=20,
    )
    if not r.ok:
        return None
    return r.json()

def auth_refresh(refresh_token):
    if not refresh_token:
        return None
    r = requests.post(
        f"{SUPABASE_URL}/auth/v1/token?grant_type=refresh_token",
        headers=_sb_headers(),
        json={"refresh_token": refresh_token},
        timeout=20,
    )
    if not r.ok:
        return None
    return r.json()

def auth_recover(email, redirect_to=None):
    if not supabase_ready():
        raise RuntimeError("Supabase is not configured.")
    url = f"{SUPABASE_URL}/auth/v1/recover"
    if redirect_to:
        url += "?redirect_to=" + requests.utils.quote(redirect_to, safe="")
    r = requests.post(
        url,
        headers=_sb_headers(),
        json={"email": email},
        timeout=20,
    )
    if not r.ok:
        data = r.json() if r.content else {}
        raise ValueError(data.get("msg") or data.get("error_description") or "Could not send recovery email.")
    return True

def profile_for(user_id):
    r = requests.get(
        f"{SUPABASE_URL}/rest/v1/profiles",
        headers={**_sb_headers(service=True), "Accept": "application/vnd.pgrst.object+json"},
        params={"id": f"eq.{user_id}", "select": "*"},
        timeout=20,
    )
    if not r.ok:
        return None
    return r.json()

def set_owner_if_matching(user_id, email):
    if not OWNER_EMAIL or (email or "").strip().lower() != OWNER_EMAIL:
        return
    requests.patch(
        f"{SUPABASE_URL}/rest/v1/profiles",
        headers={**_sb_headers(service=True), "Prefer": "return=minimal"},
        params={"id": f"eq.{user_id}"},
        json={"role": "owner", "plan": "pro", "subscription_status": "active"},
        timeout=20,
    )

def set_login_session(auth_data):
    user = auth_data.get("user") or {}
    token = auth_data.get("access_token")
    if not user.get("id") or not token:
        raise ValueError("Authentication did not return a user session.")
    email = (user.get("email") or "").lower()
    set_owner_if_matching(user["id"], email)
    session.clear()
    session["user_id"] = user["id"]
    session["email"] = email
    session["access_token"] = token
    if auth_data.get("refresh_token"):
        session["refresh_token"] = auth_data["refresh_token"]
    session.permanent = True
    return profile_for(user["id"]) or {"id": user["id"], "email": email, "role": "user", "plan": "free"}

def clear_login_session():
    session.clear()

def current_identity():
    user_id = session.get("user_id")
    token = session.get("access_token")
    if not user_id or not token:
        return None
    user = auth_user_from_token(token)
    if not user or user.get("id") != user_id:
        refreshed = auth_refresh(session.get("refresh_token"))
        if not refreshed:
            session.clear()
            return None
        token = refreshed.get("access_token")
        user = refreshed.get("user") or auth_user_from_token(token)
        if not token or not user or user.get("id") != user_id:
            session.clear()
            return None
        session["access_token"] = token
        if refreshed.get("refresh_token"):
            session["refresh_token"] = refreshed["refresh_token"]
    profile = profile_for(user_id) or {}
    return {
        "id": user_id,
        "email": user.get("email") or session.get("email"),
        "role": profile.get("role", "user"),
        "plan": profile.get("plan", "free"),
        "subscription_status": profile.get("subscription_status"),
        "free_capture_used": bool(profile.get("free_capture_used")),
        "stripe_customer_id": profile.get("stripe_customer_id"),
    }

def require_user(fn):
    @wraps(fn)
    def wrapped(*args, **kwargs):
        ident = current_identity()
        if not ident:
            return jsonify({"ok": False, "error": "Authentication required."}), 401
        request.webloom_user = ident
        return fn(*args, **kwargs)
    return wrapped

def require_owner(fn):
    @wraps(fn)
    def wrapped(*args, **kwargs):
        ident = current_identity()
        if not ident:
            return jsonify({"ok": False, "error": "Authentication required."}), 401
        if ident.get("role") != "owner":
            return jsonify({"ok": False, "error": "Owner access required."}), 403
        request.webloom_user = ident
        return fn(*args, **kwargs)
    return wrapped

def consume_capture_entitlement(user_id, trial_key, network_key=None):
    r = requests.post(
        f"{SUPABASE_URL}/rest/v1/rpc/consume_capture_entitlement",
        headers=_sb_headers(service=True),
        json={"p_user": user_id, "p_trial_key": trial_key, "p_network_key": network_key},
        timeout=20,
    )
    if not r.ok:
        raise RuntimeError("Could not verify capture entitlement.")
    return r.json()

def restore_free_capture(user_id, trial_key):
    r = requests.post(
        f"{SUPABASE_URL}/rest/v1/rpc/restore_free_capture",
        headers=_sb_headers(service=True),
        json={"p_user": user_id, "p_trial_key": trial_key},
        timeout=20,
    )
    return r.ok

def create_project(user_id, project_id, source_url, engine="webloom"):
    host = urlparse(source_url).hostname or ""
    r = requests.post(
        f"{SUPABASE_URL}/rest/v1/projects",
        headers={**_sb_headers(service=True), "Prefer": "return=representation"},
        json={
            "id": project_id,
            "user_id": user_id,
            "source_url": source_url,
            "hostname": host,
            "status": "queued",
            "engine": engine,
        },
        timeout=20,
    )
    if not r.ok:
        raise RuntimeError("Could not create project record.")
    rows = r.json()
    return rows[0] if isinstance(rows, list) and rows else rows

def update_project(project_id, **fields):
    r = requests.patch(
        f"{SUPABASE_URL}/rest/v1/projects",
        headers={**_sb_headers(service=True), "Prefer": "return=minimal"},
        params={"id": f"eq.{project_id}"},
        json=fields,
        timeout=20,
    )
    return r.ok

def list_projects(user_id, limit=50):
    r = requests.get(
        f"{SUPABASE_URL}/rest/v1/projects",
        headers=_sb_headers(service=True),
        params={
            "user_id": f"eq.{user_id}",
            "select": "*",
            "order": "created_at.desc",
            "limit": str(limit),
        },
        timeout=20,
    )
    return r.json() if r.ok else []

def get_project(user_id, project_id):
    r = requests.get(
        f"{SUPABASE_URL}/rest/v1/projects",
        headers={**_sb_headers(service=True), "Accept": "application/vnd.pgrst.object+json"},
        params={"user_id": f"eq.{user_id}", "id": f"eq.{project_id}", "select": "*"},
        timeout=20,
    )
    return r.json() if r.ok else None

def set_subscription_state(user_id, customer_id, subscription_id, status):
    r = requests.post(
        f"{SUPABASE_URL}/rest/v1/rpc/set_subscription_state",
        headers=_sb_headers(service=True),
        json={
            "p_user": user_id,
            "p_customer": customer_id,
            "p_subscription": subscription_id,
            "p_status": status,
        },
        timeout=20,
    )
    return r.ok


STORAGE_BUCKET = os.environ.get("WEBLOOM_STORAGE_BUCKET", "webloom-projects")

def storage_upload_bytes(object_path, data, content_type="application/octet-stream"):
    if not supabase_ready():
        raise RuntimeError("Supabase is not configured.")
    clean = object_path.lstrip("/")
    r = requests.post(
        f"{SUPABASE_URL}/storage/v1/object/{STORAGE_BUCKET}/{clean}",
        headers={
            **_sb_headers(service=True),
            "Content-Type": content_type,
            "x-upsert": "true",
        },
        data=data,
        timeout=60,
    )
    if not r.ok:
        raise RuntimeError(f"Storage upload failed for {clean}: {r.text[:200]}")
    return clean

def storage_upload_file(object_path, file_path, content_type="application/octet-stream"):
    with open(file_path, "rb") as fh:
        return storage_upload_bytes(object_path, fh.read(), content_type)

def storage_download(object_path):
    if not supabase_ready():
        raise RuntimeError("Supabase is not configured.")
    clean = object_path.lstrip("/")
    r = requests.get(
        f"{SUPABASE_URL}/storage/v1/object/authenticated/{STORAGE_BUCKET}/{clean}",
        headers=_sb_headers(service=True),
        timeout=60,
    )
    if not r.ok:
        return None
    return r.content, r.headers.get("content-type") or "application/octet-stream"

def storage_delete_prefix(paths):
    if not paths:
        return True
    r = requests.delete(
        f"{SUPABASE_URL}/storage/v1/object/{STORAGE_BUCKET}",
        headers=_sb_headers(service=True),
        json={"prefixes": [p.lstrip("/") for p in paths]},
        timeout=60,
    )
    return r.ok
