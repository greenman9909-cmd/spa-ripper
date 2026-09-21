# ⚡ SPA-Ripper

A lightweight, zero-parser-dependency toolkit to clone modern Single Page Application (SPA) frontends (React, Vue, Svelte, Angular, Vite, Webpack) and serve them locally with full client-side routing and optional API proxying.

---

## 🎯 Why SPA-Ripper?

Traditional tools like `wget -m` or standard HTML scrapers fail on modern Single Page Applications because:
1. **Dynamic Code-Splitting**: Vite and Webpack don't put all JavaScript in `index.html`. They load lazy route components, video players, and vendor modules on demand (`import("./assets/Home-*.js")`). Missing those causes instant runtime errors (`ChunkLoadError` / `Something went wrong`).
2. **Relative Fonts & Styles**: CSS stylesheets declare fonts and background SVGs inside `url(...)` which must be recursively resolved.
3. **PWA Manifest & Web Workers**: Web manifests and service workers contain references not found in simple anchor tags.
4. **SPA Routing**: Modern SPAs use browser history routing. Refreshing `/anime/naruto` on a naive static server throws a 404 unless fallback to `index.html` is handled.
5. **Backend API Decoupling**: Frontend apps often expect `/api/*` endpoints. SPA-Ripper includes a built-in proxy server that can forward API calls to the real origin or your own local backend.

---

## 🚀 Features

- 🔍 **Recursive Dynamic Asset Discovery**: Scans JavaScript string literals to extract lazy-loaded bundles, images, fonts, JSON, video/audio media, HLS playlists, and subtitle files while avoiding method-call false positives such as `res.json()`.
- 🎨 **Deep CSS Asset Extraction**: Parses `url(...)` declarations to capture `@font-face` woff2/woff fonts and images.
- 📱 **PWA Manifest & Worker Support**: Automatically extracts icons, manifests, and enqueues service workers.
- ⚡ **Zero Heavy Dependencies**: Pure Python using only `requests`. No BeautifulSoup, no Selenium, no Playwright required.
- 🌐 **Built-in SPA Dev Server**:
  - Serves static assets cleanly.
  - Automatically rewrites client-side routes to `/index.html` (SPA fallback).
  - Reverse-proxies `/api/*` to the original website or your custom backend (e.g. FastAPI / Flask / Express).
  - Stubs POST telemetry/analytics endpoints so apps don't crash.

---

## 📦 Installation

```bash
git clone https://github.com/greenman9909-cmd/spa-ripper.git
cd spa-ripper

pip install -r requirements.txt
```

---

## 🌐 Web App

SPA-Ripper now includes a full browser-based console with a responsive dark UI.

Run it locally:

```bash
pip install -r requirements.txt
python web_app.py
```

Then open:

```text
http://127.0.0.1:7860
```

The web console includes:

- Website URL input with server-side validation
- Live clone status and logs
- File / byte / failure counters
- Recent clone history for the current server session
- Built-in SPA preview
- One-click ZIP download
- SSRF protection that blocks private, loopback, link-local, multicast, reserved, and unspecified targets by default
- Query-safe preview handling for Next.js-style assets

For trusted local development only, private-network targets can be enabled with:

```bash
SPA_RIPPER_ALLOW_PRIVATE=1 python web_app.py
```

### Docker

```bash
docker build -t spa-ripper-web .
docker run --rm -p 7860:7860 spa-ripper-web
```

### Render

A `render.yaml` blueprint and `Dockerfile` are included. Import the repository into Render and deploy the `spa-ripper-web` service.

> Public deployments should keep `SPA_RIPPER_ALLOW_PRIVATE=0`.

---

## 💻 Usage

### 1. Clone a Target Frontend

```bash
# Basic usage (clones into ./frontend by default)
python run.py clone https://ani.pm/

# Specify custom output directory
python run.py clone https://ani.pm/ -o ./ani_frontend
```

### 2. Serve Locally

#### Offline / Standalone Mode
```bash
python run.py serve ./ani_frontend -p 8080
```

#### Connected to Real Origin API
Proxy all `/api/*` calls back to the original website so live data loads:
```bash
python run.py serve ./ani_frontend -p 8080 --proxy https://ani.pm
```

#### Connected to Your Custom Backend
Proxy all `/api/*` calls to your own local API server:
```bash
python run.py serve ./ani_frontend -p 8080 --proxy http://127.0.0.1:8000
```

---

## 🖥️ Desktop GUI

Run SPA-Ripper with a graphical interface:

```bash
python run.py
```

or explicitly:

```bash
python run.py --gui
```

The GUI asks for the website URL and output folder, streams the clone log live, prints the final download path, and can open the output folder when finished.

---

## 📱 Android App / APK

A native Android version is included under `android/`. It provides a URL field, **Clone Frontend** button, live progress log, query-safe asset saving, and the final on-device output path.

GitHub automatically builds a debug APK using **Actions → Build Android APK**. Download the `spa-ripper-android-debug` artifact and install `app-debug.apk`.

Android source:

```text
android/
├── app/
│   └── src/main/java/com/sparipper/mobile/
│       ├── MainActivity.java
│       └── SpaRipperEngine.java
└── README.md
```

The Android app stores cloned frontends in its app-specific Downloads area and does not require broad storage access.

---

## 📂 Project Structure

```
spa-ripper/
├── spa_ripper/
│   ├── __init__.py
│   ├── scraper.py     # Recursive SPA & dynamic chunk extraction engine
│   ├── server.py      # Threaded SPA server with fallback & API reverse proxy
│   └── cli.py         # Command-line interface
├── web_app.py         # Flask web console + clone API
├── web/               # Responsive web UI assets/templates
├── gui.py             # Tkinter desktop GUI
├── android/            # Native Android app + APK build project
├── Dockerfile         # Containerized web deployment
├── render.yaml        # Render blueprint
├── run.py             # GUI launcher / CLI entry point
├── requirements.txt   # Python + web dependencies
├── pyproject.toml     # Packaging metadata
└── README.md          # Documentation
```

---

## 🛠️ CLI Reference

### `clone`
```
usage: spa-ripper clone [-h] [-o OUTPUT] [-t TIMEOUT] url

positional arguments:
  url                   Target website URL (e.g. https://ani.pm/)

options:
  -o, --output OUTPUT   Output directory (default: frontend)
  -t, --timeout TIMEOUT Request timeout in seconds (default: 15)
```

### `serve`
```
usage: spa-ripper serve [-h] [-p PORT] [--host HOST] [--proxy PROXY] [--api-prefix API_PREFIX] [directory]

positional arguments:
  directory             Directory containing the cloned frontend (default: frontend)

options:
  -p, --port PORT       Port to listen on (default: 8080)
  --host HOST           Host interface (default: 0.0.0.0)
  --proxy PROXY         Backend origin to proxy /api/* requests to
  --api-prefix PREFIX   API route prefix for proxying (default: /api/)
```

---

## 📄 License
MIT License
