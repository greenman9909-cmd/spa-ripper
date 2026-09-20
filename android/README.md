# SPA-Ripper Android

Native Android GUI for SPA-Ripper.

## Features

- Website URL input
- Recursive same-origin frontend cloning
- HTML, CSS, JavaScript and JSON asset discovery
- Images, fonts, video, audio, HLS playlists and subtitles
- Query-safe filenames for Next.js-style assets
- Live clone log
- Displays the final device output path
- Copy-output-path button

The app stores clones in its external app Downloads area, normally under:

```text
/storage/emulated/0/Android/data/com.sparipper.mobile/files/Download/SPA-Ripper/<domain>_frontend
```

No broad storage permission is required.

## Build locally

Requires Android SDK 35, JDK 17 and Gradle 8.9.

```bash
gradle -p android assembleDebug
```

APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Build on GitHub

Open the repository's **Actions** tab and run **Build Android APK**. The workflow uploads an artifact named:

```text
spa-ripper-android-debug
```

Use SPA-Ripper only on sites you own or have permission to archive.
