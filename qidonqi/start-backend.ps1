# ============================================================
# 碳链通 · 启动后端服务（Spring Boot，端口 8080）
#
# 说明：后端使用 JDK 25 构建与运行（Spring Boot 3.5 支持 Java 17–25）。
#       JDK 23+ 的 javac 默认不再执行 classpath 上的注解处理器，
#       pom.xml 已显式声明 Lombok 注解处理器，无需额外参数。
#
# 链模式：默认 stub（离线开发桩，不需要 WeBASE，链上无真实存证）。
#       接入真实联盟链请先设置环境变量再运行：
#         $env:CARBON_CHAIN_MODE = 'webase'
#         $env:CARBON_CHAIN_SIGN_USER_ID = '<signUserId>'   # 或 CARBON_CHAIN_USER_ADDRESS
#         $env:CARBON_CHAIN_REGISTRY_ADDRESS = '0x...'
#         $env:CARBON_CHAIN_COMPLIANCE_ADDRESS = '0x...'
#       合约部署与配置详见 README「4. 接入联盟链」。
#
# 用法：右键「使用 PowerShell 运行」，或由 启动系统.bat 调用
# ============================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 项目根目录：本脚本位于 <项目根>/qidonqi/ 下，故取上一级
$root = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $root 'houduan'
# 错误分支：后端目录不存在或缺少 Maven Wrapper，说明脚本被单独拷出项目，
# 直接提示并以 exit 1 结束，避免在错误目录里执行构建
if (-not (Test-Path (Join-Path $backendDir 'mvnw.cmd'))) {
    Write-Host "[错误] 未找到后端目录：$backendDir" -ForegroundColor Red
    Write-Host '       请确认本脚本位于 <项目根>/qidonqi/ 目录内。' -ForegroundColor Red
    exit 1
}

# 优先使用本机 JDK 25；找不到则沿用当前 JAVA_HOME 并给出提示
# 搜索顺序：Microsoft OpenJDK 与 Oracle/通用 Java 安装目录，按目录名倒序取最新版本；
# 仅当 bin\java.exe 存在才认定为可用 JDK。找不到属警告而非错误（可能已手动配置 JAVA_HOME）。
$jdk25 = Get-ChildItem 'C:\Program Files\Microsoft\jdk-25*', 'C:\Program Files\Java\jdk-25*' -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if ($jdk25 -and (Test-Path (Join-Path $jdk25.FullName 'bin\java.exe'))) {
    $env:JAVA_HOME = $jdk25.FullName
} else {
    Write-Host "[警告] 未找到 JDK 25，将沿用当前 JAVA_HOME：$env:JAVA_HOME" -ForegroundColor Yellow
    Write-Host '        若构建失败，请安装 JDK 25 或手动把 JAVA_HOME 指向 JDK 25。' -ForegroundColor Yellow
}

# 链模式：未显式设置时按 stub（离线桩）运行
#   CARBON_CHAIN_MODE=webase 时需同时提供 signUserId/registry/compliance 等变量，否则启动会失败
$chainMode = if ($env:CARBON_CHAIN_MODE) { $env:CARBON_CHAIN_MODE } else { 'stub' }
$modeHint = if ($chainMode -eq 'webase') { '（真实联盟链）' } else { '（离线开发桩，未接入 WeBASE）' }

# 必须在后端目录执行，mvnw.cmd 才能找到 pom.xml
Set-Location $backendDir

# 启动信息：端口 8080 固定；JAVA_HOME 与链模式便于排查环境问题
Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  启动后端  http://localhost:8080'
Write-Host "  JAVA_HOME = $env:JAVA_HOME"
Write-Host "  链模式     = $chainMode$modeHint"
Write-Host '  停止服务：在本窗口按 Ctrl+C'
Write-Host '============================================' -ForegroundColor Cyan

# 以前台方式运行 Spring Boot；Ctrl+C 即可停止（进程随窗口结束）
& .\mvnw.cmd spring-boot:run
