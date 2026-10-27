# ============================================================
# 碳链通 · 一键构建并部署到 WSL Ubuntu
#
# 做什么：
#   1. 用 JDK 25 构建后端可执行 jar（跳过测试）；
#   2. 用 Vite 构建前端 dist；
#   3. 停止 carbon-trace 服务，原子替换 jar，rsync 同步前端；
#   4. 重启服务、reload nginx，并轮询接口确认就绪。
#
# 设计原则：
#   - 先完成两个构建，再停止正在运行的服务，构建失败不会影响线上；
#   - jar 先写入 .new 再 mv 覆盖，避免复制中断留下半个 jar；
#   - 只更新 /opt/carbon-trace/app/carbon-trace.jar 与 /opt/carbon-trace/web/，
#     不修改 application-ubuntu.yml、uploads 与 logs；
#   - 任一步失败都会尽量恢复 carbon-trace 服务，并输出排查命令。
#
# 可用环境变量：
#   CARBON_WSL_DISTRO   WSL 发行版名称，默认 Ubuntu-26.04
#   CARBON_APP_URL      部署完成后打开的地址，默认 http://localhost
#   CARBON_LAN_PORT     局域网 HTTP 端口（仅用于结果展示），默认 8088
#   CARBON_HTTPS_PORT   局域网 HTTPS 端口（仅用于结果展示），默认 8443
#
# 可选参数：
#   -NoOpen             部署完成后不自动打开浏览器
#
# 用法：
#   .\deploy.ps1
#   .\deploy.ps1 -NoOpen
#   或由 启动系统.bat deploy 调用
# ============================================================
param([switch]$NoOpen)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ---------- 基础参数 ----------
# WSL 发行版必须与 `wsl -l -v` 中的 NAME 完全一致
$distro = if ($env:CARBON_WSL_DISTRO) { $env:CARBON_WSL_DISTRO } else { 'Ubuntu-26.04' }
# 部署完成后用默认浏览器打开的地址；仅影响脚本末尾的 Start-Process
$appUrl = if ($env:CARBON_APP_URL) { $env:CARBON_APP_URL } else { 'http://localhost' }
# 以下两个端口仅用于结果展示；实际端口映射由 start-ubuntu.ps1 维护
$lanPort = if ($env:CARBON_LAN_PORT) { $env:CARBON_LAN_PORT } else { '8088' }
$httpsPort = if ($env:CARBON_HTTPS_PORT) { $env:CARBON_HTTPS_PORT } else { '8443' }

# 部署目标：与 systemd 单元 carbon-trace.service 中的路径保持一致
$deployRoot = '/opt/carbon-trace'
$serviceName = 'carbon-trace'
# 就绪探针：免登录公共接口，返回 200 即视为后端可用
$apiUrl = 'http://localhost:8080/api/public/enterprises'

# 项目根目录：本脚本位于 <项目根>/qidonqi/，取上一级
$root = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $root 'houduan'
$frontendDir = Join-Path $root 'qianduan'

# 前置检查：缺少后端 Maven Wrapper 或前端 package.json 时，说明脚本被单独拷出项目
if (-not (Test-Path (Join-Path $backendDir 'mvnw.cmd'))) {
    Write-Host "[错误] 未找到后端目录：$backendDir" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path (Join-Path $frontendDir 'package.json'))) {
    Write-Host "[错误] 未找到前端目录：$frontendDir" -ForegroundColor Red
    exit 1
}

# JDK 25 发现逻辑与 start-backend.ps1 保持一致：优先本机 JDK 25，找不到则沿用 JAVA_HOME
$jdk25 = Get-ChildItem 'C:\Program Files\Microsoft\jdk-25*', 'C:\Program Files\Java\jdk-25*' -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if ($jdk25 -and (Test-Path (Join-Path $jdk25.FullName 'bin\java.exe'))) {
    $env:JAVA_HOME = $jdk25.FullName
} else {
    Write-Host "[警告] 未找到 JDK 25，将沿用当前 JAVA_HOME：$env:JAVA_HOME" -ForegroundColor Yellow
}

# 把 Windows 绝对路径转换为 WSL 的 /mnt/<drive>/... 形式，供 bash 使用
# 参数：WindowsPath 必须为绝对路径（例如 C:\Users\...\dist）
function ConvertTo-WslPath {
    param([string]$WindowsPath)
    $full = [IO.Path]::GetFullPath($WindowsPath)
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    $rest = $full.Substring(2).Replace('\', '/')
    return "/mnt/$drive$rest"
}

# 把字符串安全地包进 bash 单引号：转义内部单引号，避免路径中的空格/中文/特殊字符被拆分
# 参数：Text 为任意待转义的路径或命令片段
function Quote-Bash {
    param([string]$Text)
    return "'" + ($Text -replace "'", "'\''") + "'"
}

# 读取局域网 IPv4（有默认网关且网卡 Up），仅用于输出访问地址
$lanIp = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq 'Up' } |
          Select-Object -First 1).IPv4Address.IPAddress

# 读取短提交号用于部署横幅；git 不可用或目录不是仓库时留空，不影响部署
$shortCommit = ''
try {
    $shortCommit = (& git -C $root rev-parse --short HEAD 2>$null)
} catch {
    $shortCommit = ''
}

# 部署横幅：打印目标发行版与当前代码版本（有 git 时）
Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  碳链通 · 构建并部署到 Ubuntu'
Write-Host "  发行版 = $distro"
if ($shortCommit) { Write-Host "  代码版本 = $shortCommit" }
Write-Host '============================================' -ForegroundColor Cyan

# ---------- 1/4 构建后端 ----------
# package 会生成可执行 jar；-DskipTests 跳过单元测试（接口回归请单独运行 api-tests.ps1）
Write-Host '[1/4] 构建后端（mvnw package）...' -ForegroundColor Cyan
Push-Location $backendDir
try {
    & .\mvnw.cmd -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw '后端构建失败（mvnw package 返回非 0）'
    }
} finally {
    Pop-Location
}

# 取最新生成的可执行 jar，排除 Spring Boot 生成的 .original 备份
$jar = Get-ChildItem (Join-Path $backendDir 'target') -Filter 'carbon-trace-backend-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    Write-Host '[错误] 未找到构建产物 target\carbon-trace-backend-*.jar' -ForegroundColor Red
    exit 1
}
Write-Host ("  后端产物：{0}（{1:N1} MB）" -f $jar.Name, ($jar.Length / 1MB)) -ForegroundColor Green

# ---------- 2/4 构建前端 ----------
# 直接调用 vite build；若依赖不完整，先 npm install 再构建
Write-Host '[2/4] 构建前端（vite build）...' -ForegroundColor Cyan
Push-Location $frontendDir
try {
    if (-not (Test-Path 'node_modules\.bin\vite.cmd')) {
        Write-Host '  前端依赖不完整，先执行 npm install ...' -ForegroundColor Yellow
        & npm.cmd install
        if ($LASTEXITCODE -ne 0) {
            throw 'npm install 失败'
        }
    }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) {
        throw '前端构建失败（npm run build 返回非 0）'
    }
} finally {
    Pop-Location
}

# Vite 默认输出目录；构建后必须存在 index.html 才继续部署
$distDir = Join-Path $frontendDir 'dist'
if (-not (Test-Path (Join-Path $distDir 'index.html'))) {
    Write-Host '[错误] 前端构建产物缺少 dist\index.html' -ForegroundColor Red
    exit 1
}
Write-Host '  前端产物：dist\index.html 已生成' -ForegroundColor Green

# ---------- 3/4 同步到 Ubuntu ----------
# 先转成 WSL 路径，再通过 bash 单引号包裹，确保中文目录与空格安全
$wslJar = Quote-Bash (ConvertTo-WslPath $jar.FullName)
$wslDist = Quote-Bash ((ConvertTo-WslPath $distDir) + '/')

Write-Host '[3/4] 同步到 Ubuntu 并重启服务 ...' -ForegroundColor Cyan
# 标记服务是否已被本脚本停止：为 true 时 finally 会尝试重新启动，避免失败后停在停机状态
$serviceStopped = $false
try {
    # 停止后端；此阶段前端静态文件仍由 nginx 提供
    & wsl.exe -d $distro -u root -- systemctl stop $serviceName 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "停止 $serviceName 失败"
    }
    $serviceStopped = $true

    # 保留上一版 jar 作为回滚点，再原子替换（.new → mv）
    $replaceCmd = "cd $deployRoot/app && " +
        "if [ -f carbon-trace.jar ]; then cp -a carbon-trace.jar carbon-trace.jar.prev; fi && " +
        "cp $wslJar carbon-trace.jar.new && chown user:user carbon-trace.jar.new && " +
        "chmod 755 carbon-trace.jar.new && mv -f carbon-trace.jar.new carbon-trace.jar"
    & wsl.exe -d $distro -u root -- bash -lc $replaceCmd
    if ($LASTEXITCODE -ne 0) {
        throw '替换后端 jar 失败'
    }
    Write-Host '  后端 jar 已替换（上一版保留为 carbon-trace.jar.prev）' -ForegroundColor Green

    # 前端：dist 全量同步；--delete 清掉旧哈希文件，--chown 保证 nginx 可读
    & wsl.exe -d $distro -u root -- bash -lc "rsync -a --delete --chown=user:user $wslDist $deployRoot/web/"
    if ($LASTEXITCODE -ne 0) {
        throw '同步前端 dist 失败'
    }
    & wsl.exe -d $distro -u root -- bash -lc "test -f $deployRoot/web/index.html"
    if ($LASTEXITCODE -ne 0) {
        throw '前端同步后缺少 web/index.html'
    }
    # Windows 挂载盘上的文件常带 777 权限；目录统一 755、文件统一 644，去掉多余执行/写权限
    & wsl.exe -d $distro -u root -- bash -lc "find $deployRoot/web -type d -exec chmod 755 {} + && find $deployRoot/web -type f -exec chmod 644 {} +"
    if ($LASTEXITCODE -ne 0) {
        throw '修正前端文件权限失败'
    }
    Write-Host '  前端 dist 已同步到 /opt/carbon-trace/web' -ForegroundColor Green

    # 启动后端并 reload nginx（静态文件不需要 reload，但统一刷新配置更稳妥）
    & wsl.exe -d $distro -u root -- systemctl start $serviceName
    if ($LASTEXITCODE -ne 0) {
        throw "启动 $serviceName 失败"
    }
    $serviceStopped = $false
    & wsl.exe -d $distro -u root -- systemctl reload nginx 2>$null | Out-Null
} finally {
    # 若在替换/同步阶段失败，尽量把旧服务拉起来，避免系统停在“已停止”状态
    if ($serviceStopped) {
        Write-Host '[警告] 部署中断，正在尝试恢复后端服务 ...' -ForegroundColor Yellow
        & wsl.exe -d $distro -u root -- systemctl start $serviceName 2>$null | Out-Null
    }
}

# ---------- 4/4 等待就绪 ----------
Write-Host '[4/4] 等待应用就绪 ...' -ForegroundColor Cyan
# 就绪标记：任意一轮拿到 HTTP 200 后置 true 并跳出轮询
$ready = $false
for ($i = 1; $i -le 90; $i++) {
    try {
        $resp = Invoke-WebRequest -UseBasicParsing -Uri $apiUrl -TimeoutSec 3
        if ($resp.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        # 端口尚未监听或服务仍在启动，继续等待
    }
    Start-Sleep -Seconds 2
}

if (-not $ready) {
    Write-Host '[错误] 部署后后端 8080 未在约 3 分钟内就绪。排查命令：' -ForegroundColor Red
    Write-Host "       wsl -d $distro -u root -- systemctl status $serviceName --no-pager" -ForegroundColor Red
    Write-Host "       wsl -d $distro -u root -- tail -n 80 /opt/carbon-trace/logs/app.log" -ForegroundColor Red
    exit 1
}

# 部署完成提示：同时给出本机与局域网入口；HTTPS 使用自签证书
Write-Host ''
Write-Host '部署完成，系统已就绪：' -ForegroundColor Green
Write-Host '  本机 HTTP   http://localhost'
Write-Host "  本机 HTTPS  https://localhost:$httpsPort"
if ($lanIp) {
    Write-Host "  局域网 HTTP  http://${lanIp}:$lanPort" -ForegroundColor Green
    Write-Host "  局域网 HTTPS https://${lanIp}:$httpsPort  （扫码 / 摄像头）" -ForegroundColor Green
}
Write-Host '  后端 API    http://localhost:8080'
Write-Host ''
if (-not $NoOpen) {
    Start-Process $appUrl
}
