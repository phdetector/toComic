# Expo Planner

A multi-exhibition planner app for Android. The home screen manages exhibition projects; inside each project you get plan/overview tabs, hall selection, search, booth notes, favorites, drag ordering, and JSON import/export. An iOS SwiftUI source project with Liquid Glass styling is included as source-only.

## Version

- Android app: `0.0.16` (`versionCode=17`), package `com.cj2026.expo`, label `Expo Planner`
- Android minSdk 26, target/compileSdk 34
- iOS: source project targeting iOS 26, built only on macOS + Xcode 26

## Features

- Multi-project home with add/edit/delete (long-press a project for actions)
- Plan and overview tabs
- Hall selector and fuzzy search across halls, booths, names and notes
- Booth notes, favorites, plan ordering and long-press drag reorder
- JSON import/export through the system file picker
- Custom booths and custom project persistence via SharedPreferences
- Light/dark theme with packed Noto Serif SC and JetBrains Mono NFM fonts
- Suggestion submission and "my suggestions" list with developer replies

## Structure

- `app/` - long-term Android app
- `preview/` - early single-exhibition ChinaJoy Planner snapshot
- `ios/` - SwiftUI source project (not buildable on Windows)
- `server/` - Node receiver + systemd unit + nginx config for `POST /api/suggestions`

## Build Android

Requirements: JDK 17+, Android SDK with platform 34 and build-tools 34.0.0.

On Windows:

```powershell
gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Build the early preview:

```powershell
gradlew.bat -p preview :app:assembleDebug
```

Static checks:

```powershell
gradlew.bat :app:lintDebug
```

## Suggestion server

The app submits feedback to `http://101.37.21.166/api/suggestions`. See `server/README.md` for deployment and verification steps. The receiver validates JSON, limits title/content length and rate-limits requests per IP.

## Test status

- `:app:assembleDebug` and `:app:assembleRelease` compile successfully
- `:app:lintDebug` passes with no errors
- Suggestion endpoint verified with success/error cases over the public network
- No Android device or emulator is attached in the current environment; runtime UI flows still need device testing
