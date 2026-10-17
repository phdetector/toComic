# Deploy tocomic-suggestions server + run end-to-end verification.
# Usage:
#   powershell -ExecutionPolicy Bypass -File server\deploy.ps1 -AdminUsername <username> -AdminPassword <password> [-SkipDeploy] [-DeployNginx] [-PublishApk <apk-path>] [-RunLocalAdminTests] [-Cleanup]
# One-time HTTPS setup (requires DNS A record 269278.xyz -> 101.37.21.166 already resolving):
#   ssh -i $env:USERPROFILE\.ssh\gptvoice_id_ed25519 root@101.37.21.166 `
#     "apt-get update && apt-get install -y certbot python3-certbot-nginx && certbot certonly --webroot -w /usr/share/nginx/html -d 269278.xyz --non-interactive --agree-tos -m <email>"
# Then run this script with -DeployNginx once.

param(
    [string]$AdminUsername = "",
    [string]$AdminPassword = "",
    [switch]$SkipDeploy,
    [switch]$DeployNginx,
    [string]$PublishApk = "",
    [switch]$RunLocalAdminTests,
    [switch]$Cleanup
)

$ErrorActionPreference = 'Stop'

if ($RunLocalAdminTests -and -not $AdminUsername) {
    Fail '-RunLocalAdminTests requires -AdminUsername (never hardcode admin usernames)'
}

$ServerHost   = '101.37.21.166'
$ServerUser   = 'root'
$SshKey       = Join-Path $env:USERPROFILE '.ssh\gptvoice_id_ed25519'
$RemoteDir    = '/opt/tocomic-suggestions'
$AdminUrl     = 'http://127.0.0.1:37796'
$PublicUrl    = 'https://269278.xyz'

function Info($message) { Write-Host "[deploy] $message" -ForegroundColor Cyan }
function Ok($message)    { Write-Host "[ok] $message" -ForegroundColor Green }
function Fail($message)  { Write-Host "[FAIL] $message" -ForegroundColor Red; exit 1 }

function Invoke-JsonPost($Uri, $Headers, $Json) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($Json)
  return Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 30
}

# ---------- 1. local syntax checks ----------
Info 'node --check server.js'
node --check (Join-Path $PSScriptRoot 'server.js')
if ($LASTEXITCODE -ne 0) { Fail 'server.js syntax check failed' }

Info 'node --check admin-server.js'
node --check (Join-Path (Join-Path $PSScriptRoot '..') 'admin\admin-server.js')
if ($LASTEXITCODE -ne 0) { Fail 'admin-server.js syntax check failed' }

# ---------- 2. deploy ----------
if (-not $SkipDeploy) {
    Info "scp server.js -> $ServerHost`:$RemoteDir/server.js"
    scp -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=accept-new `
        (Join-Path $PSScriptRoot 'server.js') "${ServerUser}@${ServerHost}:$RemoteDir/server.js"
    if ($LASTEXITCODE -ne 0) { Fail 'scp failed' }

    Info 'restart tocomic-suggestions service'
    ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${ServerUser}@${ServerHost} `
        'chown www:www /opt/tocomic-suggestions/server.js && systemctl restart tocomic-suggestions && sleep 1 && systemctl is-active tocomic-suggestions'
    if ($LASTEXITCODE -ne 0) { Fail 'service restart failed' }
    Ok 'deployed and service is active'
}

# ---------- 2b. nginx config deploy (first time / after TLS changes) ----------
# The live web server is the BT panel nginx; our config ships as a vhost extension.
if ($DeployNginx) {
    Info 'deploying nginx-extension.conf -> panel vhost extension'
    scp -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=accept-new `
        (Join-Path $PSScriptRoot 'nginx-extension.conf') "${ServerUser}@${ServerHost}:/www/server/panel/vhost/nginx/extension/269278.xyz/security.conf"
    if ($LASTEXITCODE -ne 0) { Fail 'nginx scp failed' }
    ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${ServerUser}@${ServerHost} `
        '/etc/init.d/nginx reload'
    if ($LASTEXITCODE -ne 0) { Fail 'nginx reload failed' }
    Ok 'nginx reloaded'
}

# ---------- 2c. publish a new APK for in-app updates ----------
if ($PublishApk -ne '') {
    if (-not (Test-Path $PublishApk)) { Fail "APK not found: $PublishApk" }
    Info 'publishing APK -> https://269278.xyz/toComic.apk'
    scp -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=accept-new `
        $PublishApk "${ServerUser}@${ServerHost}:/usr/share/nginx/html/toComic.apk"
    if ($LASTEXITCODE -ne 0) { Fail 'APK publish failed' }
    ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${ServerUser}@${ServerHost} `
        'chmod 644 /usr/share/nginx/html/toComic.apk'
    if ($LASTEXITCODE -ne 0) { Fail 'APK chmod failed' }
    Ok 'APK published; update /var/lib/tocomic-suggestions/app-messages.json with: { update: { versionCode, versionName, title, body, url: "https://269278.xyz/toComic.apk" } }'
}

# ---------- 3. public API E2E ----------
$publicApi = "$PublicUrl/api"
Info "public API smoke: $publicApi"

$messages = Invoke-RestMethod -Uri "$publicApi/app/messages" -TimeoutSec 20
if (-not $messages.ok) { Fail 'GET /api/app/messages not ok' }
Ok "messages: $(@($messages.notices).Count) notices, update=$($messages.update -ne $null)"

$clientId  = [guid]::NewGuid().ToString()
$payload   = @{ clientId = $clientId; title = 'E2E 测试'; content = 'deploy.ps1 验证'; appVersion = '0.0.18' } | ConvertTo-Json
$submit    = Invoke-JsonPost "$publicApi/suggestions" @{} $payload
if (-not $submit.ok) { Fail 'POST /api/suggestions not ok' }
Ok "submit ok, clientId=$clientId"

try {
    Invoke-RestMethod -Uri "$publicApi/suggestions?clientId=$clientId" -TimeoutSec 20 | Out-Null
    Fail 'GET without signature should be 401'
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 401) { Fail "expected 401, got $($_.Exception.Response.StatusCode.value__)" }
    Ok 'GET without signature -> 401 (expected)'
}

$secret = $submit.secret
if (-not $secret) { Fail 'submit did not return a secret' }
function Get-ClientSig($sid) {
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
    $sig = [BitConverter]::ToString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$sid`:$ts"))).Replace('-', '').ToLower()
    return @{ sig = $sig; ts = $ts }
}
$auth = Get-ClientSig $clientId
$list = Invoke-RestMethod -Uri "$publicApi/suggestions?clientId=$clientId&sig=$($auth.sig)&ts=$($auth.ts)" -TimeoutSec 20
if (-not $list.ok -or @($list.suggestions).Count -lt 1) { Fail 'GET with signature failed' }
Ok "GET with signature ok, suggestions=$(@($list.suggestions).Count)"

# ---------- 4. admin tunnel E2E (login -> list -> reply -> App-visible reply) ----------
if ($RunLocalAdminTests) {
    if ([string]::IsNullOrWhiteSpace($AdminPassword)) { Fail '-AdminPassword is required with -RunLocalAdminTests' }

    Info "starting local admin-server on $AdminUrl (auto SSH tunnel)"
    $adminProcess = Start-Process node -ArgumentList (Join-Path (Join-Path $PSScriptRoot '..') 'admin\admin-server.js') `
        -WorkingDirectory (Join-Path (Join-Path $PSScriptRoot '..') 'admin') -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 4
    try {
        $login = Invoke-JsonPost "$AdminUrl/api/login" @{} (@{ username = $AdminUsername; password = $AdminPassword } | ConvertTo-Json)
        if (-not $login.ok) { Fail 'admin login failed' }
        Ok 'admin login ok'

        $headers = @{ 'X-ToComic-Admin' = '1' }
        $adminList = Invoke-RestMethod -Uri "$AdminUrl/api/suggestions" -Headers $headers -TimeoutSec 30
        if (-not $adminList.ok) { Fail 'admin list failed' }
        Ok "admin list ok, suggestions=$(@($adminList.suggestions).Count)"

        $reply = Invoke-JsonPost "$AdminUrl/api/reply" $headers (@{ id = $submit.suggestion.id; reply = 'E2E 回复' } | ConvertTo-Json)
        if (-not $reply.ok) { Fail 'admin reply failed' }
        Ok 'admin reply ok'

        Start-Sleep -Seconds 2
        $auth = Get-ClientSig $clientId
        $after = Invoke-RestMethod -Uri "$publicApi/suggestions?clientId=$clientId&sig=$($auth.sig)&ts=$($auth.ts)" -TimeoutSec 20
        $item = $after.suggestions | Where-Object { $_.id -eq $submit.suggestion.id }
        if ($null -eq $item -or $item.reply -ne 'E2E 回复') { Fail 'reply not visible from App side' }
        Ok 'reply visible from App side (reply E2E passed)'
    } finally {
        Stop-Process -Id $adminProcess.Id -Force -ErrorAction SilentlyContinue
    }
}

# ---------- 4b. optional test-data cleanup ----------
if ($Cleanup) {
    Info 'removing the E2E test suggestion from the server database'
    $escapedId = $submit.suggestion.id -replace '[^a-zA-Z0-9-]', ''
    ssh -i $SshKey -o BatchMode=yes -o StrictHostKeyChecking=accept-new ${ServerUser}@${ServerHost} "node /opt/tocomic-suggestions/cleanup-suggestion.js $escapedId"
    if ($LASTEXITCODE -ne 0) { Fail 'test-data cleanup failed' }
    Ok "cleaned test suggestion $escapedId"
}

# ---------- 5. build + APK copy (run after code changes) ----------
# $env:GRADLE_USER_HOME='C:/Users/yyyak/Desktop/应用/AI应用/CJ2026-长期/.gradle-home'
# & 'C:/Users/yyyak/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat' :app:assembleDebug :app:lintDebug :app:assembleRelease --no-daemon --console=plain
# Copy-Item app\build\outputs\apk\release\toComic-release.apk "$env:USERPROFILE\Desktop\toComic-V0.0.18.apk"

Info 'done'
