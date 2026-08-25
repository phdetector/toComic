# Expo Planner

A multi-exhibition planner app for Android. The home screen manages exhibition projects; inside each project you get plan/overview tabs, hall selection, search, booth notes, favorites, drag ordering, and JSON import/export. An iOS SwiftUI source project with Liquid Glass styling is included as source-only.

## Version

- Android app: `0.0.18` (`versionCode=19`), package `com.cj2026.expo`, label `Expo Planner`
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

The app submits feedback to `https://269278.xyz/api/suggestions` (nginx 80->443 redirect + Let's Encrypt). See `server/README.md` for deployment and verification steps. The receiver validates JSON, limits title/content length and rate-limits requests per IP.

One-time HTTPS setup on the server (after the DNS A record `269278.xyz -> 101.37.21.166` resolves):

```powershell
ssh -i $env:USERPROFILE\.ssh\gptvoice_id_ed25519 root@101.37.21.166 "apt-get update && apt-get install -y certbot python3-certbot-nginx && certbot certonly --webroot -w /usr/share/nginx/html -d 269278.xyz --non-interactive --agree-tos -m <email>"
powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -DeployNginx
```

Deploy + end-to-end verification (syntax checks, scp, systemd restart, public API smoke, admin tunnel login -> list -> reply -> App-visible reply, optional test-data cleanup):

```powershell
powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -AdminPassword <password> -RunLocalAdminTests -Cleanup
```

Publish a new APK for in-app updates (uploads to `https://269278.xyz/ExpoPlanner.apk`):

```powershell
powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -PublishApk app\build\outputs\apk\release\app-release.apk
```

Then enable the update prompt by writing `/var/lib/expo-suggestions/app-messages.json` on the server:

```json
{ "notices": [], "update": { "versionCode": 19, "versionName": "0.0.18", "title": "新版本 0.0.18", "body": "安全更新", "url": "https://269278.xyz/ExpoPlanner.apk" } }
```

The server re-validates `versionCode` (1..99999) and the update URL host allow-list before serving; the app additionally verifies the APK signature before installing.

## Security status

Done:

- Admin API bound to `127.0.0.1:37795` only; access via SSH tunnel from the local admin server
- `EXPO_ADMIN_SECRET` required at startup (no default secret); admin login rate-limited (5 failures -> 15 min lock)
- Per-client HMAC-signed read auth: first submit issues a random secret; `GET /api/suggestions` requires `sig`
- Storage moved to SQLite (`node:sqlite`) with legacy JSONL migration; no read-modify-write races
- `allowBackup=false`; release builds use R8 minify + resource shrink
- Admin credential file stores only username/hash/salt (no plaintext); all credential files gitignored
- Response reads capped at 1 MB (app) and 64 KB bodies (server); import pre-checks file size (5 MB)
- Import JSON guarded: max nesting depth 64, max 10,000 entries per section; empty import shows "文件内无有效数据"
- Input length caps (name 100 / note 500 / booth code 20 / suggestion 200+5000); invalid time input clamped and inverted ranges rejected
- LruCache (8 MB) for remote logos with bounded downloads; update `versionCode` range-checked and URL must be HTTPS
- Admin session sliding renewal (`X-Expo-Token`) + local admin server 401 -> frontend returns to login
- CORS tightened (no `*` on admin API), nginx body-size/rate-limit/security headers, IP stored as hash only
- `commit()` on identity-critical SharedPreferences writes (client secret, import, booth add/update)
- Deprecated `systemUiVisibility` replaced by `WindowInsetsControllerCompat`; duplicate-booth saves ask before overwriting

Blocked -> unblocked, in progress: full HTTPS rollout. The domain `269278.xyz` is available; code is switched to `https://269278.xyz` endpoints with the cleartext exception removed, update URLs are HTTPS + host allow-listed (`269278.xyz`/`www`), and updates download in-app with APK signature verification against the installed app before install. Remaining steps: add the DNS A record, issue the Let's Encrypt certificate on the server, deploy the new nginx config, then build and release `0.0.18`.

## Test status

- `:app:assembleDebug`, `:app:lintDebug` and `:app:assembleRelease` all pass at `0.0.18` (R8 + resource shrink enabled)
- HTTPS live on `https://269278.xyz`: GET 200, HTTP 301 redirect, HSTS header present
- Full E2E verified: submit -> no-sig 401 -> signed 200 -> admin login/list/reply -> reply visible from App side
- Update chain live: APK published at `https://269278.xyz/ExpoPlanner.apk` with host allow-list + signature verification before install
- No Android device or emulator is attached in the current environment; runtime UI flows still need device testing
