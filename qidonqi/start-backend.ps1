# ============================================================
# 碳链通 · 启动后端服务（Spring Boot，端口 8080）
#
# 说明：后端必须使用 JDK 17 构建。
#       JDK 23+ 的 javac 默认不再执行 classpath 上的注解处理器，
#       会导致 Lombok 不生成 getter/setter，编译失败。
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
if (-not (Test-Path (Join-Path $backendDir 'mvnw.cmd'))) {
    Write-Host "[错误] 未找到后端目录：$backendDir" -ForegroundColor Red
    Write-Host '       请确认本脚本位于 <项目根>/qidonqi/ 目录内。' -ForegroundColor Red
    exit 1
}

# 优先使用本机 JDK 17；找不到则沿用当前 JAVA_HOME 并给出提示
$jdk17 = 'C:\Program Files\Java\jdk-17'
if (Test-Path (Join-Path $jdk17 'bin\java.exe')) {
    $env:JAVA_HOME = $jdk17
} else {
    Write-Host "[警告] 未找到 $jdk17，将沿用当前 JAVA_HOME：$env:JAVA_HOME" -ForegroundColor Yellow
    Write-Host '        若构建失败，请手动把 JAVA_HOME 指向 JDK 17。' -ForegroundColor Yellow
}

# 链模式：未显式设置时按 stub（离线桩）运行
$chainMode = if ($env:CARBON_CHAIN_MODE) { $env:CARBON_CHAIN_MODE } else { 'stub' }
$modeHint = if ($chainMode -eq 'webase') { '（真实联盟链）' } else { '（离线开发桩，未接入 WeBASE）' }

Set-Location $backendDir

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  启动后端  http://localhost:8080'
Write-Host "  JAVA_HOME = $env:JAVA_HOME"
Write-Host "  链模式     = $chainMode$modeHint"
Write-Host '  停止服务：在本窗口按 Ctrl+C'
Write-Host '============================================' -ForegroundColor Cyan

& .\mvnw.cmd spring-boot:run
