# ============================================================
# 碳链通 · Windows 一键启动（拉起 WSL Ubuntu 服务栈并打开系统）
#
# 做什么：
#   1. 启动 WSL 发行版（systemd 模式下由 systemd 托管 MySQL / Redis /
#      nginx / WeBASE / 后端 carbon-trace，enabled 的服务会自动拉起）；
#   2. 显式 start 一遍关键服务，并逐个报告 active 状态；
#   3. 轮询后端接口，就绪后用默认浏览器打开系统首页。
#
# 可用环境变量：
#   CARBON_WSL_DISTRO   WSL 发行版名称，默认 Ubuntu-26.04
#   CARBON_APP_URL      系统访问地址，默认 http://localhost
#
# 用法：由 启动系统.bat 调用，或在 PowerShell 中直接运行本脚本
# ============================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$distro = if ($env:CARBON_WSL_DISTRO) { $env:CARBON_WSL_DISTRO } else { 'Ubuntu-26.04' }
$appUrl = if ($env:CARBON_APP_URL) { $env:CARBON_APP_URL } else { 'http://localhost' }
$apiUrl = 'http://localhost:8080/api/public/enterprises'

# 服务清单：顺序同时用于 systemctl start 与状态输出
$services = @('mysql', 'redis-server', 'nginx', 'webase-node', 'webase-node-mgr',
              'webase-front', 'webase-sign', 'webase-web', 'carbon-trace')

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  碳链通 · 启动 Ubuntu 服务栈'
Write-Host "  发行版 = $distro"
Write-Host "  系统地址 = $appUrl"
Write-Host '============================================' -ForegroundColor Cyan

# 1. 拉起 WSL（systemd 为 PID 1，发行版启动后常驻）
Write-Host '[1/3] 启动 WSL 发行版 ...'
& wsl.exe -d $distro -u root -- bash -lc 'true'
if ($LASTEXITCODE -ne 0) {
    Write-Host "[错误] 无法启动 WSL 发行版 $distro。当前已安装的发行版：" -ForegroundColor Red
    & wsl.exe -l -v
    Write-Host '       可用环境变量 CARBON_WSL_DISTRO 指定发行版名称。' -ForegroundColor Red
    exit 1
}

# 2. 显式启动并逐个确认服务状态（systemd 启动时会自动拉起 enabled 服务，
#    这里再 start 一遍是为了应对手工停过的服务）
Write-Host '[2/3] 启动并检查服务 ...' -ForegroundColor Cyan
& wsl.exe -d $distro -u root -- systemctl start @services 2>$null | Out-Null

$statusLines = & wsl.exe -d $distro -u root -- systemctl is-active @services 2>$null
$failed = @()
for ($i = 0; $i -lt $services.Count; $i++) {
    $state = if ($i -lt $statusLines.Count) { $statusLines[$i] } else { 'unknown' }
    $color = if ($state -eq 'active') { 'Green' } else { 'Red' }
    Write-Host ("  {0,-18} {1}" -f $services[$i], $state) -ForegroundColor $color
    if ($state -ne 'active') { $failed += $services[$i] }
}
if ($failed.Count -gt 0) {
    Write-Host "[警告] 以下服务未处于 active：$($failed -join ', ')" -ForegroundColor Yellow
    Write-Host '       查看原因：wsl -d ' + $distro + ' -u root -- systemctl status <服务名> --no-pager' -ForegroundColor Yellow
}

# 3. 轮询后端，等待应用就绪后打开浏览器
Write-Host '[3/3] 等待应用就绪 ...' -ForegroundColor Cyan
$ready = $false
for ($i = 1; $i -le 90; $i++) {
    try {
        $resp = Invoke-WebRequest -UseBasicParsing -Uri $apiUrl -TimeoutSec 3
        if ($resp.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        # 服务尚未监听：继续等待
    }
    Start-Sleep -Seconds 2
}

if (-not $ready) {
    Write-Host '[错误] 后端 8080 未在约 3 分钟内就绪。排查命令：' -ForegroundColor Red
    Write-Host "       wsl -d $distro -u root -- systemctl status carbon-trace --no-pager" -ForegroundColor Red
    Write-Host "       wsl -d $distro -u root -- tail -n 50 /opt/carbon-trace/logs/app.log" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host '系统已就绪：' -ForegroundColor Green
Write-Host '  系统首页    http://localhost'
Write-Host '  后端 API    http://localhost:8080'
Write-Host '  WeBASE 管理台 http://localhost:5000'
Write-Host '  WeBASE-Front  http://localhost:5002'
Write-Host ''
Start-Process $appUrl

