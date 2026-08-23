# Expo Planner 建议管理 Web-UI

本地管理端。数据来自服务器 `101.37.21.166:37795`，登录后缓存在内存中，关闭程序自动清理。

## 启动

```powershell
node admin/admin-server.js
```

浏览器打开 `http://127.0.0.1:37796`。

- 服务端 admin API 仅监听 `127.0.0.1`，本程序启动时自动通过 SSH 建立本地隧道（默认使用 `~/.ssh/gptvoice_id_ed25519`）。
- 可用环境变量覆盖：`EXPO_ADMIN_SSH_KEY`、`EXPO_ADMIN_TUNNEL_PORT`；`EXPO_ADMIN_NO_TUNNEL=1` 可跳过隧道（服务端本机调试时使用）。

首次使用需要服务器端生成的用户名与密码（见 `.admin-secret.json`，该文件不入库）。

## 功能

- 左侧 3/4 建议卡片：内容、提交时间、回复输入、收藏、归档
- 右侧筛选：全部 / 未处理 / 已处理 / 已归档 / 收藏
- 未处理优先级高于已处理，同优先级按提交时间旧到新排序
