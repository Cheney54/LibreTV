# LibreTV Android APK

This folder contains a native Android WebView shell for LibreTV.

It packages the web files from the repository root into APK assets and serves them from:

```text
https://libretv.local/
```

The Android layer intercepts:

```text
https://libretv.local/proxy/<encoded-url>
```

and proxies remote API, m3u8, key, map, and media segment requests inside the APK. This means the app does not need a domain, Vercel, Netlify, Render, or a local Node server at runtime.

## Build

Requirements:

- Android Studio
- JDK bundled with Android Studio
- Android SDK / Build Tools

Open the `android` folder in Android Studio, let Gradle sync, then build:

```bash
./gradlew assembleDebug
```

On Windows, use:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK will be generated under:

```text
android/app/build/outputs/apk/debug/
```

If the Gradle wrapper files are missing, open the project with Android Studio once or install Gradle locally and run:

```bash
gradle wrapper
```

## Asset Sync

Before every Android build, Gradle runs `syncLibreTvAssets` and copies these root files/folders into `app/src/main/assets/site`:

- `index.html`
- `live.html`
- `player.html`
- `watch.html`
- `about.html`
- `admin-logs.html`
- `manifest.json`
- `css/**`
- `data/**`
- `js/**`
- `libs/**`
- `image/**`

The source web files remain the canonical files. Do not edit generated files under `app/src/main/assets/site` directly.

## TV Box Notes

The app declares Leanback launcher support, uses landscape orientation, keeps the screen awake, supports fullscreen WebView video, and maps Android back to WebView history/fullscreen exit.

HarmonyOS devices that support Android APK compatibility may install this APK. HarmonyOS NEXT requires a separate native Harmony app package.
