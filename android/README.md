# SPA-Ripper Android

Native Android GUI for SPA-Ripper.

## Android v1.1 highlights

- Redesigned dark card-based interface
- Branded SPA-Ripper lightning launcher icon
- **Clone frontend** and **Clone & run localhost** actions
- Clear status card with progress indicator
- Dedicated localhost controls
- Auto-scrolling live activity console
- Same recursive frontend discovery engine
- Query-safe filenames for Next.js-style assets
- Built-in localhost SPA fallback and HTTP Range support

The app stores clones in its external app Downloads area, normally under:

```text
/storage/emulated/0/Android/data/com.sparipper.mobile/files/Download/SPA-Ripper/<domain>_frontend
```

## Build locally

Requires Android SDK 35, JDK 17 and Gradle 8.9.

```bash
gradle -p android assembleDebug
```

APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions also publishes the latest successful APK to the repository root as:

```text
SPA-Ripper.apk
```

Use SPA-Ripper only on sites you own or have permission to archive.
