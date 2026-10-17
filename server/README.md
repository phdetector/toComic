# Suggestion Receiver

Receives `POST /api/suggestions` from the Android app over HTTPS (`https://269278.xyz`, nginx 80->443 redirect + Let's Encrypt) and stores entries in SQLite (`node:sqlite`).

## Files

- `server.js`: Node receiver, listens on `127.0.0.1:7978`, validates input, rate-limits POST requests per IP and serves `GET /api/suggestions?clientId=...&sig=...`
- `nginx-default.conf`: nginx server for `269278.xyz` (HTTP redirect + TLS `/api/` proxy)
- `deploy.ps1`: deploy + end-to-end verification script
- `tocomic-suggestions.service`: systemd unit running as the unprivileged `www` user

## Server layout

- `/opt/tocomic-suggestions/server.js`
- `/etc/systemd/system/tocomic-suggestions.service`
- `/etc/nginx/conf.d/default.conf` (nginx site for `269278.xyz`)
- `/var/lib/tocomic-suggestions/suggestions.db` (SQLite, owned by `www:www`)
- `/var/lib/tocomic-suggestions/client-secrets.jsonl` (client HMAC secrets)
- `/var/lib/tocomic-suggestions/admin-credentials.json` (admin scrypt hash)

## Deploy

One-time HTTPS setup (first add the DNS A record `269278.xyz -> 101.37.21.166`):

```powershell
ssh -i C:\Users\yyyak\.ssh\gptvoice_id_ed25519 root@101.37.21.166 "apt-get update && apt-get install -y certbot python3-certbot-nginx && certbot certonly --webroot -w /usr/share/nginx/html -d 269278.xyz --non-interactive --agree-tos -m <email>"
powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -DeployNginx
```

Routine deploy + full E2E verification:

```powershell
powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -AdminPassword <password> -RunLocalAdminTests -Cleanup
```

Manual deploy:

```powershell
scp -i C:\Users\yyyak\.ssh\gptvoice_id_ed25519 server/server.js root@101.37.21.166:/opt/tocomic-suggestions/server.js
scp -i C:\Users\yyyak\.ssh\gptvoice_id_ed25519 server/nginx-default.conf root@101.37.21.166:/etc/nginx/conf.d/default.conf
ssh -i C:\Users\yyyak\.ssh\gptvoice_id_ed25519 root@101.37.21.166 "chown www:www /opt/tocomic-suggestions/server.js && systemctl restart tocomic-suggestions && nginx -t && systemctl reload nginx"
```

## Verify

```powershell
curl.exe -X POST https://269278.xyz/api/suggestions -H "Content-Type: application/json" --data-binary "{\"clientId\":\"test\",\"title\":\"test\",\"content\":\"test\"}"
curl.exe "https://269278.xyz/api/suggestions?clientId=test"
```

Reply to a suggestion by id on the server:

```bash
sudo -u www node /opt/tocomic-suggestions/reply.js <suggestion-id> "developer reply text"
```

## Admin API

The receiver also exposes an authenticated admin API on port `37795` (`POST /admin/login`, `GET /admin/suggestions`, `POST /admin/reply|favorite|archive`). Credentials are stored in `/var/lib/tocomic-suggestions/admin-credentials.json` and are consumed by the local admin UI under `admin/`.

