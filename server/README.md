# Suggestion Receiver

Receives `POST /api/suggestions` from the Android app and appends each entry to a JSONL file.

## Files

- `server.js`: Node receiver, listens on `127.0.0.1:7978`, validates input and rate-limits requests per IP
- `expo-suggestions.service`: systemd unit running as the unprivileged `www` user
- `nginx-default.conf`: nginx default server with the `/api/suggestions` proxy location

## Server layout

- `/opt/expo-suggestions/server.js`
- `/etc/systemd/system/expo-suggestions.service`
- `/var/lib/expo-suggestions/suggestions.jsonl` (owned by `www:www`)

## Deploy

```powershell
scp -i C:\Users\yyyak\.ssh\gptvoice_id_ed25519 server/server.js root@101.37.21.166:/opt/expo-suggestions/server.js
scp -i C:\Users\yyyak\.ssh\gptvoice_id_ed25519 server/expo-suggestions.service root@101.37.21.166:/etc/systemd/system/expo-suggestions.service
scp -i C:\Users\yyyak\.ssh\gptvoice_id_ed25519 server/nginx-default.conf root@101.37.21.166:/etc/nginx/conf.d/default.conf
ssh -i C:\Users\yyyak\.ssh\gptvoice_id_ed25519 root@101.37.21.166 "mkdir -p /var/lib/expo-suggestions && chown www:www /var/lib/expo-suggestions && systemctl daemon-reload && systemctl enable --now expo-suggestions && nginx -t && systemctl reload nginx"
```

## Verify

```powershell
curl.exe -X POST http://101.37.21.166/api/suggestions -H "Content-Type: application/json" --data-binary "{\"title\":\"test\",\"content\":\"test\"}"
```
