# 安全审计整改闭环清单（代码侧）

审计报告全部条目 → 修复位置（本仓库 0.0.18 工作树，versionCode=19）。静态复核已完成；构建/部署/提交待 shell 恢复后按 `RELEASE.md` 执行。

## 高严重度

| 问题 | 修复与证据位置 |
|---|---|
| 全链路明文 HTTP | App 端点改 `https://269278.xyz`：`data/SuggestionApi.kt:14`、`data/AppMessagesApi.kt:13`；`res/xml/network_security_config.xml` 仅剩全局禁明文；`server/nginx-default.conf` 80→443 跳转 + Let's Encrypt 路径 + HSTS/TLSv1.2-1.3 |
| 更新 URL 可指向恶意 APK | App 端 `AppMessagesApi.isAllowedUpdateUrl` 强制 https + 主机白名单（269278.xyz/www）；`UpdateInstaller.kt` 应用内下载（≤300MB）→ 与当前应用签名 SHA-256 比对 → 一致才安装；服务端 `server.js sanitizeUpdate` 权威校验 versionCode 1..99999 + URL 白名单 |
| Admin Web-UI 回复链路断裂 | `admin/admin-server.js remotePost` 用 `pathname.replace('/api/','/admin/')` 映射；E2E 步骤固化于 `deploy.ps1 -RunLocalAdminTests` |
| admin API 0.0.0.0 / 默认 secret / 无限速 | `server.js`：`adminServer.listen(37795,'127.0.0.1')`；启动时 `EXPO_ADMIN_SECRET` 未设即退出（`adminSecret()` + 启动检查）；登录 5 次失败锁 15 分钟（`ADMIN_LOGIN_MAX_FAILURES/LOCK_MS`） |
| allowBackup 导出凭据 | 主 App 与 preview 的 Manifest 均 `allowBackup="false"` |
| GET /api/suggestions 无鉴权 | 首次提交签发随机 secret（`issueClientSecret`），GET 需 `sig=HMAC-SHA256(secret,clientId)`（`validClientSignature` + `timingSafeEqual`）；App 侧 `SuggestionApi.signature` 自动签名 |

## 中严重度

| 问题 | 修复与证据位置 |
|---|---|
| 需求9 开发者板块 | `MainActivity.kt` 设置页底部 `CreditRow` ×3（phdetectar/deepseek/opus5，头像+标题+简介） |
| .admin-secret.json 含明文密码 | 现仅 `{ "username" }`；hash/salt 在服务器 `/var/lib/expo-suggestions/admin-credentials.json`；`.gitignore` 覆盖三个凭据文件；密码已轮换一次 |
| suggestions.jsonl 读改写竞态与增长 | `server.js` 迁 SQLite（`node:sqlite`），启动时 `migrateLegacyJsonl()` 一次性迁移并改名 `.legacy`；`reply.js` 同步改 SQL |
| 导入先读全量再限大小 | `PlannerStore.importFrom` 先 `openFileDescriptor().statSize > 5MB` 拒绝再读 |
| 网络响应无上限 | `SuggestionApi.readLimited` 1MB、`AppMessagesApi` 1MB、`RemoteLogo` 1MB，超限即断 |
| KeepExisting 丢新展台状态 | `applyImported` 先播种 `imported.booths` 为 CustomBooth，再计算 `validStateIds` |
| 时间倒置/非法输入 | `sanitizeHour/Min` 即时钳制（≤23/24、≤59）；`timePartsValid` 校验 24:00 仅结束位；保存拦截 `startTime > endTime` |
| 输入无长度限制 | `sanitizeLength`：名称 100 / 备注 500 / 展台号 20 / 建议 200+5000 等全部入口 |
| RemoteLogo 无限缓存 | `LruCache` 8MB + 下载 1MB 限长 + `inJustDecodeBounds` 降采样 |
| admin 本地端口 CSRF | 所有 `/api/*` 需自定义头 `X-Expo-Admin: 1`（浏览器跨站无法预检通过）；远端 admin OPTIONS 去掉 `Access-Control-Allow-Origin: *`；`readBody` 限 64KB |
| admin 会话过期无感 | `server.js verifyAdmin` 剩余 <5 分钟自动换发并回 `X-Expo-Token`；`admin-server.js adoptRenewedToken` 采纳；`UnauthorizedError` → 本地 401（不再误报 502）；前端 `index.html api()` 收 401 回登录页 |
| 隐私/合规 | 声明弹窗明示收集项（建议内容、版本、设备型号；仅存 IP 哈希）；服务端只落 `ipHash`（sha256 前 32 位） |
| versionCode 无范围 | App 端 `1..99_999` + 服务端 `sanitizeUpdate` 双重校验 |
| Release 未混淆 | `isMinifyEnabled=true` + `isShrinkResources=true`（v0.0.17 起，release 构建通过） |

## 低严重度

| 问题 | 修复与证据位置 |
|---|---|
| 通知弹窗点外=确定 | `MessageDialog` 仅在提供 `dismissLabel` 时点外可关闭；通知类不传 dismissLabel |
| 声明弹窗取消 | `finishAffinity()` + `finish()` 双兜底 |
| 关键写入 apply() | `SuggestionStore`（clientId/secret）、导入（`saveSnapshotCommitted`）、展台新增/更新改用 `commit()`；高频编辑保持 `apply()` |
| 日志泄露 | 网络错误仅保留状态码，不拼响应体 |
| rate-limit Map 无上限 | 1 万条目上限 + 淘汰最旧条目（不再整体 clear） |
| nginx 缺防护 | `client_max_body_size 64k`、`limit_req`、nosniff/DENY/no-referrer/HSTS |
| patch 原型污染 | `updateSuggestion` 白名单字段拷贝，`Object.prototype.hasOwnProperty` 检查 |
| CORS `*` | 公网/远端 admin OPTIONS 均不返回 `Access-Control-Allow-Origin` |
| 展台重名静默覆盖 | 弹「展台已存在…是否更新」确认框，取消不覆盖 |
| 空导入提示 | 「文件内无有效数据」替代「已导入 0 条」 |
| JSON 深度/条数 | 导入前扫描深度 >64 拒绝；各段条目 >10000 拒绝 |
| 弃用 SystemUiFlag | `WindowInsetsControllerCompat` 迁移（`ui/Theme.kt`） |
| WRITE_EXTERNAL_STORAGE | 保留（API≤28 导出仍需；无法确认无 ≤28 用户，条件满足后再删） |
| Admin 清理钩子 | `SIGINT/SIGTERM/uncaughtException/unhandledRejection/exit` 全覆盖（kill -9 内存态随进程回收，风险可忽略） |

## 仍需执行（外部依赖）

1. 域名解析：`269278.xyz` A 记录 → `101.37.21.166`（用户操作）
2. Let's Encrypt 邮箱或免邮箱授权（用户操作）
3. 以下由 `RELEASE.md` 固化，shell 恢复后一条条跑：
   - `node --check server\server.js`、`node --check admin\admin-server.js`
   - certbot 签发 → `deploy.ps1 -DeployNginx` → `deploy.ps1 -RunLocalAdminTests -Cleanup`
   - Gradle `assembleDebug / lintDebug / assembleRelease`（0.0.18）
   - `deploy.ps1 -PublishApk` + 写 `app-messages.json` 更新条目
   - `git commit` + tag `v0.0.18`
