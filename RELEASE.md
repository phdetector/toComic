# Release 0.0.18 (security + HTTPS) — 执行清单

按顺序执行，每步成功后继续。全部命令在仓库根目录 `C:\Users\yyyak\Desktop\应用\AI应用\CJ2026-长期` 的 PowerShell 中运行。

## 0. 前置确认
- 域名解析：`nslookup 269278.xyz` 应返回 `101.37.21.166`
- 本地 shell 可用（若 `bash` 工具仍报 terminal inspection 错误，请在本机 PowerShell 手动执行以下命令）

## 1. JS 语法检查
```powershell
node --check server\server.js
node --check admin\admin-server.js
```

## 2. 服务器签发 Let's Encrypt 证书（一次性，Alibaba Cloud Linux 3 用 dnf 非 apt）
```powershell
ssh -i $env:USERPROFILE\.ssh\gptvoice_id_ed25519 root@101.37.21.166 "dnf install -y certbot && certbot certonly --webroot -w /usr/share/nginx/html -d 269278.xyz --non-interactive --agree-tos -m <email>"
```

## 2b. 证书自动续期确认（每次部署后核验）
```powershell
ssh -i $env:USERPROFILE\.ssh\gptvoice_id_ed25519 root@101.37.21.166 "systemctl enable --now certbot-renew.timer && systemctl list-timers certbot-renew.timer && certbot renew --dry-run --non-interactive"
```
必须看到 timer 有 NEXT 触发时间且 dry-run 输出 `no renewal failures`（2026-08-25 曾发现 timer enabled 但未启动，已用 `enable --now` 修复）。

## 3. 部署 server.js + nginx HTTPS 配置
```powershell
powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -DeployNginx
```

## 4. 全链路 E2E（提交→无签名 401→带签名 200→admin 登录/列表/回复→App 端可见）
```powershell
powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -AdminPassword <admin密码> -RunLocalAdminTests -Cleanup
```

## 5. 构建（Debug + Lint + Release）
```powershell
$env:GRADLE_USER_HOME='C:/Users/yyyak/Desktop/应用/AI应用/CJ2026-长期/.gradle-home'
& 'C:/Users/yyyak/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat' :app:assembleDebug :app:lintDebug :app:assembleRelease --no-daemon --console=plain
```
三项均须通过；lint 无 error。

## 6. 复制 APK 到桌面并发布更新
```powershell
Copy-Item app\build\outputs\apk\release\app-release.apk "$env:USERPROFILE\Desktop\ExpoPlanner-V0.0.18.apk"
powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -PublishApk "$env:USERPROFILE\Desktop\ExpoPlanner-V0.0.18.apk"
```
随后在服务器写 `/var/lib/expo-suggestions/app-messages.json`：
```json
{ "notices": [], "update": { "versionCode": 19, "versionName": "0.0.18", "title": "新版本 0.0.18", "body": "安全更新", "url": "https://269278.xyz/ExpoPlanner.apk" } }
```
并 `chown www:www` 该文件。老版本 App 会自动提示更新。

## 7. 提交与打 tag
```powershell
git add -A
git commit -m "security: 全链路HTTPS(269278.xyz)/更新域名白名单+APK签名校验/服务端更新消息校验/限流Map淘汰/导入防护/commit()/WindowInsets迁移/重名与空导入提示/admin会话续签与401回登录; V0.0.18"
git tag v0.0.18
git --no-pager log --oneline -3
```
确认 `git status` 干净（凭据文件必须仍被 .gitignore 忽略）。

## 8. 完成标准
- [ ] `curl https://269278.xyz/api/app/messages` 返回 200
- [ ] `curl http://269278.xyz/api/app/messages -I` 返回 301 到 https
- [ ] 无签名 GET /api/suggestions 返回 401；带 sig 返回 200
- [ ] admin 回复在 App 端 `GET /api/suggestions` 可见
- [ ] assembleDebug / lintDebug / assembleRelease 全通过
- [ ] git tag v0.0.18 存在，工作区干净

## 9. Release 0.0.19（R8 加固分发，2026-08-25 已执行）
发布 0.0.18 时线上分发的其实是 debug 包（未混淆）。0.0.19 起分发 minified release：
1. `app/build.gradle.kts`：release 增加 `signingConfig = signingConfigs.getByName("debug")`（签名与历史版本一致，老用户可平滑升级，`UpdateInstaller` 签名校验不受影响）；`versionCode = 20` / `versionName = "0.0.19"`
2. 构建：`assembleDebug` + `lintDebug` + `assembleRelease` 全通过
3. 验签：`apksigner verify --print-certs app-release.apk` 证书 SHA-256 `2af69c6e…` 与历史 debug 一致；`aapt dump badging` 无 debuggable；dex ~2MB（debug ~22MB）
4. 发布：`Copy-Item app\build\outputs\apk\release\app-release.apk` → `deploy.ps1 -PublishApk`；服务器 `app-messages.json` 更新为 `versionCode 20 / 0.0.19` 并 `chown www:www`
5. 验证：`https://269278.xyz/ExpoPlanner.apk` 下载后 md5 与本地 `app-release.apk` 一致
6. `git commit` + `git tag v0.0.19`
