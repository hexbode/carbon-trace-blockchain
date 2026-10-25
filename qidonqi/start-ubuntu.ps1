# ============================================================
# 碳链通 · Windows 一键启动（拉起 WSL Ubuntu 服务栈并打开系统）
#
# 做什么：
#   1. 启动 WSL 发行版（systemd 模式下由 systemd 托管 MySQL / Redis /
#      nginx / WeBASE / 后端 carbon-trace，enabled 的服务会自动拉起）；
#   2. 显式 start 一遍关键服务，并逐个报告 active 状态；
#   3. 轮询后端接口，就绪后用默认浏览器打开系统首页；
#   4. 确认 Windows 侧到 WSL 的局域网端口映射（其他设备可通过本机 IP 访问）。
#
# 可用环境变量：
#   CARBON_WSL_DISTRO   WSL 发行版名称，默认 Ubuntu-26.04
#   CARBON_APP_URL      系统访问地址，默认 http://localhost
#   CARBON_LAN_PORT     Windows 局域网映射端口，默认 8088
#
# 用法：由 启动系统.bat 调用，或在 PowerShell 中直接运行本脚本
# ============================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 参数默认值（环境变量优先，便于不同机器覆盖）：
#   CARBON_WSL_DISTRO 缺省 Ubuntu-26.04，必须与 `wsl -l -v` 的 NAME 完全一致，
#                      名称不匹配会在第 1 步启动时失败并列出已安装发行版；
#   CARBON_APP_URL     缺省 http://localhost（经 nginx 80 端口提供前端）；
#   CARBON_LAN_PORT    缺省 8088，仅用于局域网其他设备访问，不影响本机 localhost。
$distro = if ($env:CARBON_WSL_DISTRO) { $env:CARBON_WSL_DISTRO } else { 'Ubuntu-26.04' }
$appUrl = if ($env:CARBON_APP_URL) { $env:CARBON_APP_URL } else { 'http://localhost' }
$lanPort = if ($env:CARBON_LAN_PORT) { $env:CARBON_LAN_PORT } else { '8088' }
# 就绪探针：选取免登录的公共接口，不依赖 token；只要返回 200 即认为后端可用
$apiUrl = 'http://localhost:8080/api/public/enterprises'

# 服务清单：顺序同时用于 systemctl start 与状态输出
#   数据库/缓存/网关 → WeBASE 节点与管理组件 → 业务后端 carbon-trace
#   若某台机器未安装 WeBASE 相关单元，对应服务会显示为 failed/unknown，仅告警不退出
$services = @('mysql', 'redis-server', 'nginx', 'webase-node', 'webase-node-mgr',
              'webase-front', 'webase-sign', 'webase-web', 'carbon-trace')

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  碳链通 · 启动 Ubuntu 服务栈'
Write-Host "  发行版 = $distro"
Write-Host "  系统地址 = $appUrl"
Write-Host '============================================' -ForegroundColor Cyan

# 1. 拉起 WSL（systemd 为 PID 1，发行版启动后常驻）
#    执行 `bash -lc 'true'` 只为触发发行版启动，不运行任何实际业务命令；
#    失败分支：$LASTEXITCODE 非 0 表示发行版名称不存在/WSL 未安装，打印已安装列表后退出（exit 1）。
Write-Host '[1/4] 启动 WSL 发行版 ...'
& wsl.exe -d $distro -u root -- bash -lc 'true'
if ($LASTEXITCODE -ne 0) {
    Write-Host "[错误] 无法启动 WSL 发行版 $distro。当前已安装的发行版：" -ForegroundColor Red
    & wsl.exe -l -v
    Write-Host '       可用环境变量 CARBON_WSL_DISTRO 指定发行版名称。' -ForegroundColor Red
    exit 1
}

# 2. 显式启动并逐个确认服务状态（systemd 启动时会自动拉起 enabled 服务，
#    这里再 start 一遍是为了应对手工停过的服务）
#    说明：2>$null 抑制 systemctl 的噪声输出（如部分单元不存在）；
#          is-active 的输出顺序与服务清单一一对应，按下标取状态，缺失时记为 unknown。
#    失败分支：仅告警并提示查看单个服务状态，不中断后续就绪探测。
Write-Host '[2/4] 启动并检查服务 ...' -ForegroundColor Cyan
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
#    最多 90 轮、每轮等待 2 秒（单次请求超时 3 秒），即最长约 3 分钟；
#    请求异常（连接被拒/尚未监听）在 catch 中忽略并继续重试，直到拿到 200 才跳出。
#    失败分支：超时后打印 carbon-trace 状态与日志查看命令，退出（exit 1），不再打开浏览器。
Write-Host '[3/4] 等待应用就绪 ...' -ForegroundColor Cyan
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

# 4. 局域网访问：确认 Windows 侧存在到 WSL 内 nginx(80) 的端口映射
#    WSL 的 80 端口默认只监听在 WSL 虚拟网卡上，Windows 局域网地址无法直达，
#    因此需要在 Windows 侧做 portproxy：0.0.0.0:<lanPort> → 127.0.0.1:80
#    （Windows 会把 127.0.0.1:80 转发进 WSL 的 nginx）。
#    检查方式：解析 `netsh interface portproxy show v4tov4` 的输出，按四列匹配
#    「监听地址 0.0.0.0 + 监听端口 lanPort + 目标 127.0.0.1 + 目标端口 80」，
#    已存在则跳过，避免重复添加。
#    权限分支：添加 portproxy 与防火墙规则需要管理员权限；
#              非管理员时只打印两条待执行命令，不报错退出（不影响本机使用）。
Write-Host '[4/4] 检查局域网访问 ...' -ForegroundColor Cyan
# 取第一个「有默认网关且网卡 Up」的 IPv4 地址作为局域网访问地址（无则跳过展示）
$lanIp = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq 'Up' } |
          Select-Object -First 1).IPv4Address.IPAddress
# 是否以管理员身份运行，决定能否直接创建端口映射与防火墙规则
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
$proxyLines = & netsh interface portproxy show v4tov4
$hasMapping = $proxyLines | Select-String -Pattern ("^\s*0\.0\.0\.0\s+" + $lanPort + "\s+127\.0\.0\.1\s+80\s*$")
if (-not $hasMapping) {
    if ($isAdmin) {
        # 添加 TCP 端口转发；Out-Null 抑制 netsh 成功提示
        & netsh interface portproxy add v4tov4 listenport=$lanPort listenaddress=0.0.0.0 connectport=80 connectaddress=127.0.0.1 | Out-Null
        # 入站防火墙规则按端口命名（CarbonTrace-LAN-<port>），已存在则不重复创建
        if (-not (Get-NetFirewallRule -DisplayName "CarbonTrace-LAN-$lanPort" -ErrorAction SilentlyContinue)) {
            New-NetFirewallRule -DisplayName "CarbonTrace-LAN-$lanPort" -Direction Inbound -Protocol TCP `
                -LocalPort $lanPort -Action Allow -Profile Any | Out-Null
        }
        Write-Host "  已创建端口映射 0.0.0.0:$lanPort -> 127.0.0.1:80" -ForegroundColor Green
    } else {
        # 非管理员分支：给出等价的两条命令，由用户自行以管理员 PowerShell 执行一次
        Write-Host '[提示] 尚未配置局域网映射，请用管理员 PowerShell 执行一次：' -ForegroundColor Yellow
        Write-Host "       netsh interface portproxy add v4tov4 listenport=$lanPort listenaddress=0.0.0.0 connectport=80 connectaddress=127.0.0.1"
        Write-Host "       New-NetFirewallRule -DisplayName CarbonTrace-LAN-$lanPort -Direction Inbound -Protocol TCP -LocalPort $lanPort -Action Allow -Profile Any"
    }
}

# 启动完成提示：本机入口固定为 localhost，局域网入口仅在检测到 IPv4 时展示
Write-Host ''
Write-Host '系统已就绪：' -ForegroundColor Green
Write-Host '  系统首页    http://localhost'
if ($lanIp) {
    Write-Host "  局域网访问  http://${lanIp}:$lanPort  （同一局域网内的其他设备）" -ForegroundColor Green
} else {
    Write-Host '  [提示] 未检测到局域网 IPv4 地址，其他设备暂时无法访问' -ForegroundColor Yellow
}
# 其余固定端口：后端 API、WeBASE 管理台、WeBASE-Front
Write-Host '  后端 API    http://localhost:8080'
Write-Host '  WeBASE 管理台 http://localhost:5000'
Write-Host '  WeBASE-Front  http://localhost:5002'
Write-Host ''
# 用系统默认浏览器打开前端首页（地址可用 CARBON_APP_URL 覆盖）
Start-Process $appUrl

