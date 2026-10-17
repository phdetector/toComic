# toComic 行程安排 · 网页版

行程安排（日期 + 活动）的网页版，零第三方依赖：`node:http` + `node:sqlite` + 原生 HTML/CSS/JS。
没有 npm 包、没有构建步骤，`node server.js` 直接跑。

## 启动（本机）

```powershell
cd web
node server.js
```

默认监听 `0.0.0.0:11451`。浏览器打开：

- 本机：<http://127.0.0.1:11451>
- 局域网内其他设备：`http://<本机局域网IP>:11451`

## 部署（服务器）

已部署在 `101.37.21.166`，直接访问：<http://101.37.21.166:11451>

一键重新部署（打包上传 → 重启服务 → 冒烟自检）：

```powershell
powershell -ExecutionPolicy Bypass -File web\deploy.ps1
powershell -ExecutionPolicy Bypass -File web\deploy.ps1 -RunE2E          # 额外在服务器上跑完整自检
powershell -ExecutionPolicy Bypass -File web\deploy.ps1 -FetchAccounts   # 拉取服务器账号文件到 web\data\
powershell -ExecutionPolicy Bypass -File web\deploy.ps1 -Logs            # 查看服务日志
```

服务器上的布局：

| 用途 | 路径 |
|---|---|
| 程序 | `/opt/tocomic-web/`（`server.js` + `public/` + `tools/`） |
| 数据 | `/var/lib/tocomic-web/`（`trip.db`、`accounts.txt`、`session-secret`），属主 `www:www` |
| systemd | `/etc/systemd/system/tocomic-web.service`（`Restart=always`、开机自启） |

`deploy.ps1` 只写以上两处目录、只操作 `tocomic-web` 这一个 unit，不碰 nginx 与 `expo-suggestions`。

常用运维命令（服务器上执行）：

```bash
systemctl status tocomic-web
systemctl restart tocomic-web
journalctl -u tocomic-web -n 50 --no-pager
node /opt/tocomic-web/tools/accounts.js --reset user03
```

首次部署需要手工做两件事：**阿里云安全组放行 11451/TCP 入方向**；服务器 `node >= 22.5`（`node:sqlite` 要求，实测 v22.23.1）。

## 账号

**没有注册入口。** 首次启动时若账号表为空，会自动生成 10 组账号：

- 用户名：`user01` … `user10`（可用 `WEB_ACCOUNT_PREFIX` 改前缀，仅首次生成时生效）
- 密码：16 位随机串

用户名与密码会**打印到控制台**，同时写入 `data/accounts.txt`。请立即抄走并删除该文件。

管理账号：

```powershell
node tools/accounts.js --list              # 只列出用户名
node tools/accounts.js --reset user03      # 重置单个账号密码并打印
node tools/accounts.js --regen             # 清空重建 10 组（会清掉所有账号）
```

## 数据

- 行程（日期 + 活动）**所有账号共享**：任何登录用户都能新增/编辑/删除。
- 备注、收藏、完成状态**每人独立**，互不影响。
- 存储：`data/trip.db`（SQLite，WAL）。`data/` 已在 `.gitignore` 中，不会入库。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `WEB_PORT` | `11451` | 监听端口 |
| `WEB_HOST` | `0.0.0.0` | 监听地址；设 `127.0.0.1` 则只允许本机访问 |
| `WEB_DB` | `./data/trip.db` | SQLite 路径 |
| `WEB_SESSION_SECRET` | 自动生成到 `data/session-secret` | 会话 Cookie 签名密钥 |
| `WEB_ACCOUNT_PREFIX` | `user` | 账号名前缀，仅首次生成时生效 |
| `WEB_SESSION_DAYS` | `7` | 登录有效期（天） |
| `WEB_LOGIN_MAX_FAILURES` | `5` | 同一 IP 连续失败达到该次数即锁定 |
| `WEB_LOGIN_LOCK_MS` | `900000` | 锁定时长（毫秒，默认 15 分钟） |
| `WEB_LOGIN_HOURLY_MAX` | `10` | **同一 IP 滚动 1 小时内允许的失败尝试上限** |
| `WEB_LOGIN_HOURLY_WINDOW_MS` | `3600000` | 上述滚动窗口长度（毫秒，默认 1 小时） |

## 安全说明（重要）

需求明确「11451 端口、没有域名、接受不安全传输」，因此本服务**默认跑明文 HTTP**：

- 密码在网络上以明文传输，会话 Cookie 同样明文。
- **仅限局域网 / 可信网络使用。** 如果哪天要放到公网，必须前置 HTTPS 反向代理。
- 已做的加固：密码用 `scrypt` 存哈希+盐；会话 Cookie 为 `HttpOnly` + `SameSite=Lax` + HMAC 签名；
  登录失败 5 次锁定 15 分钟；**同一 IP 滚动 1 小时内最多 10 次失败尝试**（只统计失败，成功登录不清零）；
  所有接口不返回任何 CORS 头；变更请求校验 `Origin`；
  响应带 `nosniff` / `X-Frame-Options: DENY` / `Referrer-Policy: no-referrer` / `Cache-Control: no-store`；
  前端一律用 `textContent` 渲染用户文本，杜绝 XSS。

## 自检

```powershell
node tools/e2e.js
```

在临时目录建临时库、临时端口，跑完整链路（未登录 401 → 登录锁定 → 建日期 → 建活动 → 校验 → 双账号状态隔离 → 删除级联 → 登出），
退出码非 0 即失败。
