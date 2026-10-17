# 把 toComic 行程安排网页版部署到服务器（systemd 常驻，端口 11451）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File web\deploy.ps1                打包上传 + 重启服务 + 冒烟自检
#   powershell -ExecutionPolicy Bypass -File web\deploy.ps1 -RunE2E         额外在服务器上跑完整端到端自检
#   powershell -ExecutionPolicy Bypass -File web\deploy.ps1 -FetchAccounts  把服务器生成的账号文件拉到 web\data\
#   powershell -ExecutionPolicy Bypass -File web\deploy.ps1 -Logs           只查看服务日志
#   powershell -ExecutionPolicy Bypass -File web\deploy.ps1 -SkipUpload     只重启服务，不重新上传
#
# 首次部署需要手工做一次（本脚本已假定做完）：
#   1) 阿里云安全组放行 11451/TCP 入方向
#   2) 服务器上已有 node >= 22.5（node:sqlite 需要）
# 本脚本只写 /opt/tocomic-web 与 /var/lib/tocomic-web，并操练 tocomic-web 这一个 unit，
# 不会碰 nginx / tocomic-suggestions。

param(
    [switch]$RunE2E,
    [switch]$FetchAccounts,
    [switch]$Logs,
    [switch]$SkipUpload,
    [string]$SshKey = (Join-Path $env:USERPROFILE '.ssh\gptvoice_id_ed25519')
)

$ErrorActionPreference = 'Stop'

$ServerHost = '101.37.21.166'
$ServerUser = 'root'
$RemoteDir  = '/opt/tocomic-web'
$DataDir    = '/var/lib/tocomic-web'
$Service    = 'tocomic-web'
$Port       = 11451
$WebDir     = $PSScriptRoot
$DataLocal  = Join-Path $WebDir 'data'
$Target     = "$ServerUser@$ServerHost"
$SshOpts    = @('-i', $SshKey, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new')

# 注意：PowerShell 不做函数提升，这些定义必须在使用之前。
function Info($message) { Write-Host "[deploy] $message" -ForegroundColor Cyan }
function Ok($message) { Write-Host "[ok] $message" -ForegroundColor Green }
function Warn($message) { Write-Host "[warn] $message" -ForegroundColor Yellow }
function Fail($message) { Write-Host "[FAIL] $message" -ForegroundColor Red; exit 1 }

function Invoke-Remote([string]$Command) {
    ssh @SshOpts $Target $Command
    if ($LASTEXITCODE -ne 0) { Fail "remote command failed: $Command" }
}

function Get-RemoteCode([string]$Path) {
    $code = ssh @SshOpts $Target "curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:$Port$Path"
    if ($LASTEXITCODE -ne 0) { return 'ERR' }
    return $code
}

# ---------- 只读分支 ----------

if ($Logs) {
    Info "journalctl -u $Service"
    ssh @SshOpts $Target "journalctl -u $Service -n 60 --no-pager"
    exit 0
}

if ($FetchAccounts) {
    if (-not (Test-Path $DataLocal)) { New-Item -ItemType Directory -Path $DataLocal -Force | Out-Null }
    $local = Join-Path $DataLocal 'accounts.server.txt'
    Info "scp $DataDir/accounts.txt -> $local"
    scp @SshOpts "${Target}:$DataDir/accounts.txt" $local
    if ($LASTEXITCODE -ne 0) { Fail 'scp accounts.txt failed' }
    Ok "账号文件已保存到 $local （web\data\ 已 gitignore）"
    exit 0
}

# ---------- 1. 本地语法检查 ----------

foreach ($file in @('server.js', 'public\app.js', 'tools\e2e.js', 'tools\accounts.js')) {
    node --check (Join-Path $WebDir $file)
    if ($LASTEXITCODE -ne 0) { Fail "$file syntax check failed" }
}
Ok 'node --check passed'

# ---------- 2. 打包上传 ----------

if (-not $SkipUpload) {
    $tar = Join-Path $env:TEMP 'tocomic-web.tar.gz'
    if (Test-Path $tar) { Remove-Item $tar -Force }
    & tar -czf $tar -C $WebDir server.js public tools README.md
    if ($LASTEXITCODE -ne 0) { Fail 'tar failed' }
    Info "scp -> ${ServerHost}:/tmp/tocomic-web.tar.gz"
    scp @SshOpts $tar "${Target}:/tmp/tocomic-web.tar.gz"
    if ($LASTEXITCODE -ne 0) { Fail 'scp failed' }
    Invoke-Remote "set -e; mkdir -p $RemoteDir $DataDir; tar -xzf /tmp/tocomic-web.tar.gz -C $RemoteDir; rm -f /tmp/tocomic-web.tar.gz; chown -R root:root $RemoteDir; chmod -R a+rX $RemoteDir; chown -R www:www $DataDir"
    Ok ('files deployed to ' + $RemoteDir)
}

# ---------- 3. 重启服务 ----------

Invoke-Remote "systemctl restart $Service; sleep 2; systemctl is-active $Service" | Out-Null
Ok "$Service restarted"

# ---------- 4. 冒烟自检 ----------

$health = ssh @SshOpts $Target "curl -s --max-time 8 http://127.0.0.1:$Port/api/health"
if ($LASTEXITCODE -ne 0 -or $health -notmatch '"ok":true') { Fail "health check failed: $health" }
Ok "health = $health"

$expected = @{ '/' = '200'; '/style.css' = '200'; '/app.js' = '200'; '/favicon.svg' = '200'; '/api/days' = '401' }
$report = @()
foreach ($path in $expected.Keys) {
    $code = Get-RemoteCode $path
    $report += "$path=$code"
    if ($code -ne $expected[$path]) { Fail "$path returned $code, expected $($expected[$path])" }
}
Ok ($report -join '  ')

$public = ssh @SshOpts $Target "curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://$ServerHost`:$Port/api/health"
if ($public -ne '200') {
    Warn "公网 http://${ServerHost}:$Port 返回 $public —— 请检查阿里云安全组的 11451/TCP 入方向规则"
} else {
    Ok "公网可达 http://${ServerHost}:$Port"
}

# ---------- 5. 可选：服务器上跑完整自检 ----------

if ($RunE2E) {
    Info 'running full e2e on the server (temp port + temp db)'
    ssh @SshOpts $Target "cd $RemoteDir && node tools/e2e.js | tail -3"
    if ($LASTEXITCODE -ne 0) { Fail 'remote e2e failed' }
}

Ok "done — http://${ServerHost}:$Port"
