# BASH 工具修复任务书（唯一任务：修复命令执行工具）

> 本文件只服务于一件事：**修复本会话的命令执行工具（bash）**。修好后，项目收尾动作请转 `RELEASE.md`（0–8 步），项目背景与审计闭环见 `docs/SECURITY-AUDIT-CLOSURE.md`。**不要**在本任务中改动任何项目代码。

---

## 0. 症状（精确描述）

- 故障工具：命令执行（`bash`）。文件编辑/查看工具正常。
- 错误信息（每次逐字相同）：
  ```
  subprocess-local: terminal inspection is unsupported on platform win32
  ```
- 故障起止：会话第 3 轮起出现，至今 12+ 轮持续；**第 1–2 轮同一工具在本机（win32）曾正常执行过** gradle、git、ssh、scp、PowerShell 命令。
- 故障性质：100% 复现、确定性；**任何**命令都在进程真正启动之前失败——包括 `echo`、`exit 0`、`cmd /c echo hi`、`powershell.exe ...`、输出重定向到文件的命令。失败与命令内容、参数、工作目录无关。
- 已排除：不是项目代码问题、不是远程服务器（101.37.21.166）问题、不是磁盘/文件问题（编辑器工具读写正常）。

## 1. 证据表

| 测试 | 命令 | 结果 |
|---|---|---|
| 空操作 | `exit 0` | 同一错误 |
| 最简单输出 | `echo probe` | 同一错误 |
| cmd 直调 | `cmd /c echo hi` | 同一错误 |
| PowerShell 直调 | `powershell.exe -NoProfile -Command "..."` | 同一错误 |
| 重定向输出 | `cmd /c "echo hi > %TEMP%\probe.txt"` | 同一错误 |
| 空字符串命令 | `""` | `command must be a non-empty string`（参数校验先于运行器） |
| 文件编辑工具 | view/create/str_replace | 全部正常 |

## 2. 定位结论

- 错误发生在 **harness 的本地子进程运行器（subprocess-local runner）内部**，具体是它试图对每条命令做「终端检查（terminal inspection）」的步骤，而该步骤的 win32 代码路径不存在或依赖缺失，于是直接抛错，**进程尚未派发**。
- 这解释了为什么命令内容再怎么变都失败，也解释了为什么编辑器工具（不走该运行器）正常。
- 第 1–2 轮可用 → 第 3 轮起不可用，说明运行器/依赖在会话中发生了变化（更新、切换或宿主状态变化），而非机器本身从不支持命令执行。

## 3. 根因假设（按概率排序）

| # | 假设 | 依据 | 修复方向 |
|---|---|---|---|
| H1 | 会话的运行器被更新/切换到新实现，新实现在 win32 上未实现「终端检查」，直接抛 NotImplementedError 风格错误 | 错误文本像自定义 NotImplementedError；同一会话前后行为突变 | 重启会话让运行器重新初始化；或回退/升级运行器版本 |
| H2 | 运行器为每条命令尝试分配 pty（ConPTY / winpty），winpty 依赖缺失或宿主控制台异常 | Windows 上伪终端依赖 winpty/ConPTY，缺组件时常见此类失败 | 修复 pywinpty / winpty；用 Windows Terminal 启动宿主 |
| H3 | 运行器代码 import 了 Unix-only 的 `termios` / `pty` 模块，win32 上 ImportError 后走错误分支抛出该信息 | "terminal inspection" 正是这类探测的典型命名 | 更新运行器；或提供环境变量旁路（见 F2） |
| H4 | 环境变量变化（TERM / CI / NO_COLOR 等）触发了交互式终端探测路径 | 会话环境可能随运行器切换而改变 | 显式设置 `CI=1` / `TERM=dumb` 等（见 F2） |

## 4. 诊断步骤（如果本会话仍无法执行命令，请用户在本机 PowerShell 执行并回贴输出）

```powershell
# D1 基础命令
echo probe

# D2 Python 与相关库
python --version
pip --version
python -c "import pty"          # win32 上预期报错，记录错误文本
python -c "import termios"      # win32 上预期报错，记录错误文本
python -c "import pywinpty"     # 记录是否有 winpty
pip show pywinpty

# D3 常见 shell 与 winpty 位置
where.exe bash
where.exe winpty
where.exe cmd
Get-ChildItem "C:\Program Files\Git\bin" -Filter *.exe | Select-Object Name

# D4 相关环境变量
echo "TERM=$env:TERM"
echo "CI=$env:CI"
echo "NO_COLOR=$env:NO_COLOR"
echo "ConEmuANSI=$env:ConEmuANSI"

# D5 宿主控制台进程
Get-Process conhost -ErrorAction SilentlyContinue | Select-Object Id, SessionId

# D6 错误串定位（在工具/环境安装目录里搜，找到抛出该错误的源码即可定位运行器实现）
findstr /s /m /c:"terminal inspection" C:\Users\yyyak\.gradle 2>$null
findstr /s /m /c:"terminal inspection" $env:APPDATA 2>$null
findstr /s /m /c:"terminal inspection" $env:LOCALAPPDATA 2>$null
```

D6 若能命中文件，直接阅读该文件确认哪条路径在 win32 抛错、是否可用配置/环境变量绕开。

## 5. 修复尝试（严格按顺序，每一步后都回试 `echo probe`）

- **F1 重启会话/应用**（成本最低、概率最高）：运行器随会话重新初始化；本项目所有改动都在磁盘上，重启无损失。重启后先跑 `echo probe` 和 `git status --short`。
- **F2 环境变量旁路**：在用户 PowerShell 设置后重试——
  ```powershell
  $env:CI='1'; $env:TERM='dumb'; $env:NO_COLOR='1'
  ```
  （若运行器支持这些约定，可跳过终端探测。）
- **F3 修复 winpty/ConPTY 依赖**：
  ```powershell
  python -m pip install --force-reinstall pywinpty
  python -m pip install --upgrade pywin32
  ```
  确保 Git for Windows 自带 `winpty.exe` 存在；启动宿主建议使用 Windows Terminal（提供 ConPTY）。
- **F4 Python 运行环境**：确认 Python ≥3.8（推荐 3.11+），`python --version` 异常则重装。
- **F5 运行器版本**：若会话工具带版本号/更新入口，检查是否自动更新导致；尝试回退上一版本或等待新版修复。
- **F6 关闭 pty**：若运行器存在 `no-tty` / `plain` / `disable-terminal-inspection` 类配置，启用之。
- **F7 换壳测试**：若运行器允许指定 shell，依次尝试 `cmd.exe`、`C:\Program Files\Git\bin\bash.exe`、`powershell.exe`，确认哪种能跑通（通一个即可继续任务）。

## 6. 修复成功标准

1. `echo probe` 输出 `probe`（不再抛 terminal inspection 错误）；
2. `git status --short` 能列出未提交改动（见 `docs/SECURITY-AUDIT-CLOSURE.md` 的改动清单）；
3. 随后立即转 `RELEASE.md` 按 0–8 步完成项目收尾（JS 语法检查 → DNS/certbot → 部署 nginx → 全链路 E2E → Gradle 三构建 → 发布 APK → git 提交 + tag `v0.0.18`）。

## 7. 修复期间的替代路径（不必等修复）

- 用户在**自己的** PowerShell 里按 `RELEASE.md` 手动执行（命令全部现成可复制），把每步输出粘贴给会话，由会话静态核对并确认完成。
- 会话侧继续用文件编辑工具记录进度，不阻塞。

## 8. 边界

- 本任务**不修改**项目代码、不发布、不部署（除非 bash 已修复且按 RELEASE.md 执行）。
- 修复过程中产生的临时文件不要提交进 git；凭据文件（`admin/.admin-secret.json`、`admin/admin-credentials.json`、`admin/admin-secret.env`）永远不入库。
