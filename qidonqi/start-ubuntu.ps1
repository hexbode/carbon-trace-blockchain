# ============================================================
# 碳链通 · Windows 一键启动（拉起 WSL Ubuntu 服务栈并打开系统）
#
# 做什么：
#   1. 启动 WSL 发行版（systemd 模式下由 systemd 托管 MySQL / Redis /
#      nginx / WeBASE / 后端 carbon-trace，enabled 的服务会自动拉起）；
#   2. 显式 start 一遍关键服务，并逐个报告 active 状态；
#   3. 轮询后端接口，就绪后用默认浏览器打开系统首页；
#   4. 确认 Windows 侧到 WSL 的 HTTP / HTTPS 局域网端口映射（其他设备可通过本机 IP 访问）。
#
# 可用环境变量：
#   CARBON_WSL_DISTRO        WSL 发行版名称，默认 Ubuntu-26.04
#   CARBON_APP_URL           系统访问地址，默认 http://localhost
#   CARBON_LAN_PORT          Windows 局域网 HTTP 映射端口，默认 8088
#   CARBON_HTTPS_PORT        Windows 局域网 HTTPS 映射端口，默认 8443
#   CARBON_TLS_BACKEND_PORT  WSL 内 nginx HTTPS 监听端口，默认 4430
#
# 可选参数：
#   -NoOpen                  启动完成后不自动打开浏览器（便于脚本化验证）
#
# 用法：由 启动系统.bat 调用，或在 PowerShell 中直接运行本脚本
# ============================================================
param([switch]$NoOpen)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 参数默认值（环境变量优先，便于不同机器覆盖）：
#   CARBON_WSL_DISTRO 缺省 Ubuntu-26.04，必须与 `wsl -l -v` 的 NAME 完全一致，
#                      名称不匹配会在第 1 步启动时失败并列出已安装发行版；
#   CARBON_APP_URL     缺省 http://localhost（经 nginx 80 端口提供前端）；
#   CARBON_LAN_PORT    缺省 8088，仅用于局域网其他设备访问，不影响本机 localhost。
#   CARBON_HTTPS_PORT  缺省 8443，面向局域网的 HTTPS 地址（扫码/摄像头需要安全上下文）。
#   CARBON_TLS_BACKEND_PORT 缺省 4430，是 WSL 内 nginx SSL 站点的监听端口；
#                      8443 通过 Windows portproxy 转发到 WSL 的 4430，
#                      避免与 Windows 上其他软件（如 Steam++/Watt Toolkit）抢占 443。
$distro = if ($env:CARBON_WSL_DISTRO) { $env:CARBON_WSL_DISTRO } else { 'Ubuntu-26.04' }
$appUrl = if ($env:CARBON_APP_URL) { $env:CARBON_APP_URL } else { 'http://localhost' }
$lanPort = if ($env:CARBON_LAN_PORT) { $env:CARBON_LAN_PORT } else { '8088' }
$httpsPort = if ($env:CARBON_HTTPS_PORT) { $env:CARBON_HTTPS_PORT } else { '8443' }
$tlsBackendPort = if ($env:CARBON_TLS_BACKEND_PORT) { $env:CARBON_TLS_BACKEND_PORT } else { '4430' }
# 就绪探针：选取免登录的公共接口，不依赖 token；只要返回 200 即认为后端可用
$apiUrl = 'http://localhost:8080/api/public/enterprises'

# 服务清单：顺序同时用于 systemctl start 与状态输出
#   数据库/缓存/网关 → WeBASE 节点与管理组件 → 业务后端 carbon-trace
#   若某台机器未安装 WeBASE 相关单元，对应服务会显示为 failed/unknown，仅告警不退出
$services = @('mysql', 'redis-server', 'nginx', 'webase-node', 'webase-node-mgr',
              'webase-front', 'webase-sign', 'webase-web', 'carbon-trace')

# 启动横幅：打印目标发行版与最终访问地址，便于确认当前操作对象
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

# 1.1 保持 WSL 会话常驻
#     WSL 3.x 在最后一个 wsl.exe 客户端退出约 30 秒后会自动休眠发行版，
#     systemd 托管的 MySQL / nginx / carbon-trace 也会随之中断（本机已复现）。
#     这里启动一个隐藏的 wsl.exe 常驻会话（长时间 sleep）阻止休眠；
#     该进程独立于本脚本窗口，脚本结束后仍保持运行。已存在时不重复创建。
$keepAliveMarker = 'carbon-trace-keepalive'
$keepAlive = Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*$keepAliveMarker*" }
if ($keepAlive) {
    Write-Host ("  常驻会话已存在（PID {0}）" -f $keepAlive.ProcessId)
} else {
    # 优先经 WMI 创建：进程不属于当前终端，窗口关闭后仍然存活；
    # WMI 失败或进程立即退出时退回 Start-Process（双击启动场景同样能独立存活）。
    # 发行版名不加引号：wsl.exe 会把引号当成名称的一部分而立即退出（已实测）。
    $keepAliveCmd = 'wsl.exe -d {0} -u root -- bash -lc "exec sleep 2147483647 # {1}"' -f $distro, $keepAliveMarker
    $keepPid = 0
    try {
        $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $keepAliveCmd }
        if ($created.ReturnValue -eq 0) { $keepPid = [int]$created.ProcessId }
    } catch {
        $keepPid = 0
    }
    Start-Sleep -Seconds 2
    if ($keepPid -eq 0 -or -not (Get-Process -Id $keepPid -ErrorAction SilentlyContinue)) {
        $keep = Start-Process -FilePath 'wsl.exe' -WindowStyle Hidden -PassThru -ArgumentList @(
            '-d', $distro, '-u', 'root', '--', 'bash', '-lc', "`"exec sleep 2147483647 # $keepAliveMarker`"")
        $keepPid = $keep.Id
        Start-Sleep -Seconds 2
    }
    if (Get-Process -Id $keepPid -ErrorAction SilentlyContinue) {
        Write-Host ("  已启动常驻会话（PID {0}），防止发行版空闲休眠" -f $keepPid) -ForegroundColor Green
    } else {
        Write-Host '[警告] 常驻会话启动失败，发行版可能在约 30 秒后休眠导致服务中断。' -ForegroundColor Yellow
        Write-Host "       可手动保持会话：wsl -d $distro -u root -- bash -lc 'exec sleep infinity'" -ForegroundColor Yellow
    }
}

# 2. 显式启动并逐个确认服务状态（systemd 启动时会自动拉起 enabled 服务，
#    这里再 start 一遍是为了应对手工停过的服务）
#    说明：2>$null 抑制 systemctl 的噪声输出（如部分单元不存在）；
#          is-active 的输出顺序与服务清单一一对应，按下标取状态，缺失时记为 unknown。
#    失败分支：仅告警并提示查看单个服务状态，不中断后续就绪探测。
Write-Host '[2/4] 启动并检查服务 ...' -ForegroundColor Cyan
& wsl.exe -d $distro -u root -- systemctl start @services 2>$null | Out-Null

# 读取各单元状态：输出顺序与 $services 一一对应；数量不足时在下面按下标补 unknown
$statusLines = & wsl.exe -d $distro -u root -- systemctl is-active @services 2>$null
# 收集未处于 active 的单元名，仅用于最后的汇总告警，不中断启动
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
# 就绪标记：任意一轮拿到 HTTP 200 后置 true 并跳出循环
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

# 4. 局域网访问：确保 HTTP 与 HTTPS 两条 portproxy 映射都存在且指向正确
#    - HTTP  : Windows 0.0.0.0:<lanPort>    → 127.0.0.1:80   → WSL nginx 80
#    - HTTPS : Windows 0.0.0.0:<httpsPort>  → 127.0.0.1:4430 → WSL nginx SSL 站点
#    WSL 的端口默认只监听在 WSL 虚拟网卡上，Windows 局域网地址无法直达，
#    因此需要 portproxy。HTTPS 的 4430 是 WSL 内 nginx 的新监听端口，
#    避开 Windows 上 Steam++/Watt Toolkit 等软件对 443 的占用。
#    权限分支：添加/修改 portproxy 与防火墙规则需要管理员权限；
#              非管理员时只打印待执行命令，不报错退出（不影响本机使用）。
Write-Host '[4/4] 检查局域网访问 ...' -ForegroundColor Cyan
# 取第一个「有默认网关且网卡 Up」的 IPv4 地址作为局域网访问地址（无则跳过展示）
$lanIp = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq 'Up' } |
          Select-Object -First 1).IPv4Address.IPAddress
# 是否以管理员身份运行，决定能否直接创建端口映射与防火墙规则
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
# 一次性读取现有映射；两个端口互不重叠，后续删除/新增不会影响另一条
$proxyLines = & netsh interface portproxy show v4tov4

# 同步一条 portproxy：目标不存在则创建；存在但目标端口不对（例如旧版 8443→443）
# 则先删除再重建；入站防火墙规则按规则名幂等创建。
# 参数：
#   ListenPort   Windows 侧监听端口（绑定 0.0.0.0）
#   ConnectPort  WSL 内 nginx 目标端口（80 / 4430）
#   RuleName     入站防火墙规则显示名（CarbonTrace-LAN-<port> / CarbonTrace-HTTPS-<port>）
function Sync-CarbonPortProxy {
    param(
        [string]$ListenPort,
        [string]$ConnectPort,
        [string]$RuleName
    )
    # 按监听端口找出已有的 portproxy 行；没有匹配时数组为空
    $match = @($proxyLines | Select-String -Pattern ("^\s*0\.0\.0\.0\s+" + [regex]::Escape($ListenPort) + "\s+"))
    if ($match.Count -gt 0) {
        # 解析「监听地址 监听端口 目标地址 目标端口」四列，确认目标是否已经指向期望端口
        $parsed = [regex]::Match($match[0].Line, '^\s*0\.0\.0\.0\s+(\d+)\s+([\d\.]+)\s+(\d+)\s*$')
        if ($parsed.Success -and $parsed.Groups[2].Value -eq '127.0.0.1' -and $parsed.Groups[3].Value -eq $ConnectPort) {
            Write-Host ("  端口映射已存在 0.0.0.0:{0} -> 127.0.0.1:{1}" -f $ListenPort, $ConnectPort) -ForegroundColor Green
            return
        }
        if (-not $isAdmin) {
            Write-Host ("[提示] 0.0.0.0:{0} 存在旧映射，需管理员执行：" -f $ListenPort) -ForegroundColor Yellow
            Write-Host ("       netsh interface portproxy delete v4tov4 listenport={0} listenaddress=0.0.0.0" -f $ListenPort)
            return
        }
        & netsh interface portproxy delete v4tov4 listenport=$ListenPort listenaddress=0.0.0.0 | Out-Null
        Write-Host ("  已移除旧映射 0.0.0.0:{0}" -f $ListenPort) -ForegroundColor Yellow
    }

    if ($isAdmin) {
        # 添加 TCP 端口转发；Out-Null 抑制 netsh 成功提示
        & netsh interface portproxy add v4tov4 listenport=$ListenPort listenaddress=0.0.0.0 connectport=$ConnectPort connectaddress=127.0.0.1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host ("[警告] 创建端口映射失败：0.0.0.0:{0} -> 127.0.0.1:{1}" -f $ListenPort, $ConnectPort) -ForegroundColor Yellow
            return
        }
        # 入站防火墙规则按规则名幂等创建
        if (-not (Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue)) {
            New-NetFirewallRule -DisplayName $RuleName -Direction Inbound -Protocol TCP `
                -LocalPort $ListenPort -Action Allow -Profile Any | Out-Null
        }
        Write-Host ("  已创建端口映射 0.0.0.0:{0} -> 127.0.0.1:{1}" -f $ListenPort, $ConnectPort) -ForegroundColor Green
    } else {
        # 非管理员分支：给出等价命令，由用户自行以管理员 PowerShell 执行一次
        Write-Host ("[提示] 尚未配置 0.0.0.0:{0} -> 127.0.0.1:{1}，请用管理员 PowerShell 执行：" -f $ListenPort, $ConnectPort) -ForegroundColor Yellow
        Write-Host ("       netsh interface portproxy add v4tov4 listenport={0} listenaddress=0.0.0.0 connectport={1} connectaddress=127.0.0.1" -f $ListenPort, $ConnectPort)
        Write-Host ("       New-NetFirewallRule -DisplayName {0} -Direction Inbound -Protocol TCP -LocalPort {1} -Action Allow -Profile Any" -f $RuleName, $ListenPort)
    }
}

Sync-CarbonPortProxy -ListenPort $lanPort -ConnectPort '80' -RuleName "CarbonTrace-LAN-$lanPort"
Sync-CarbonPortProxy -ListenPort $httpsPort -ConnectPort $tlsBackendPort -RuleName "CarbonTrace-HTTPS-$httpsPort"

# 检查 WSL 内 nginx 是否真的监听 4430：监听缺失时 HTTPS 映射会连接失败，
# 这里只告警并给出排查命令，不中断启动（HTTP 访问仍然可用）。
& wsl.exe -d $distro -u root -- bash -lc "ss -lnt | grep -q ':$tlsBackendPort'" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host ("[警告] WSL 内未检测到 nginx 监听 {0}，HTTPS 可能不可用。" -f $tlsBackendPort) -ForegroundColor Yellow
    Write-Host "       排查：wsl -d $distro -u root -- nginx -t; grep -n 'listen' /etc/nginx/sites-enabled/carbon-trace-ssl" -ForegroundColor Yellow
}

# 启动完成提示：本机入口固定为 localhost，局域网入口仅在检测到 IPv4 时展示。
# HTTPS 使用自签证书，首次访问浏览器会提示风险；手机扫码/调用摄像头需要 HTTPS 安全上下文。
Write-Host ''
Write-Host '系统已就绪：' -ForegroundColor Green
Write-Host '  本机 HTTP   http://localhost'
Write-Host "  本机 HTTPS  https://localhost:$httpsPort  （自签证书，首次需信任）"
if ($lanIp) {
    Write-Host "  局域网 HTTP  http://${lanIp}:$lanPort  （同一局域网内的其他设备）" -ForegroundColor Green
    Write-Host "  局域网 HTTPS https://${lanIp}:$httpsPort  （扫码 / 摄像头）" -ForegroundColor Green
    Write-Host "  证书下载     http://${lanIp}:$lanPort/carbon.crt" -ForegroundColor Green
} else {
    Write-Host '  [提示] 未检测到局域网 IPv4 地址，其他设备暂时无法访问' -ForegroundColor Yellow
}
# 其余固定端口：后端 API、WeBASE 管理台、WeBASE-Front
Write-Host '  后端 API    http://localhost:8080'
Write-Host '  WeBASE 管理台 http://localhost:5000'
Write-Host '  WeBASE-Front  http://localhost:5002'
Write-Host '  停止服务     wsl --shutdown（同时结束常驻会话）'
Write-Host ''
# 用系统默认浏览器打开前端首页（地址可用 CARBON_APP_URL 覆盖；-NoOpen 时不打开）
if (-not $NoOpen) {
    Start-Process $appUrl
}

