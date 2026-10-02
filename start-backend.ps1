# ============================================================
# 碳链通 · 启动后端服务（Spring Boot，端口 8080）
#
# 说明：后端必须使用 JDK 17 构建。
#       JDK 23+ 的 javac 默认不再执行 classpath 上的注解处理器，
#       会导致 Lombok 不生成 getter/setter，编译失败。
#
# 用法：右键「使用 PowerShell 运行」，或由 启动系统.bat 调用
# ============================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 优先使用本机 JDK 17；找不到则沿用当前 JAVA_HOME 并给出提示
$jdk17 = 'C:\Program Files\Java\jdk-17'
if (Test-Path (Join-Path $jdk17 'bin\java.exe')) {
    $env:JAVA_HOME = $jdk17
} else {
    Write-Host "[警告] 未找到 $jdk17，将沿用当前 JAVA_HOME：$env:JAVA_HOME" -ForegroundColor Yellow
    Write-Host '        若构建失败，请手动把 JAVA_HOME 指向 JDK 17。' -ForegroundColor Yellow
}

Set-Location (Join-Path $PSScriptRoot 'houduan')

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  启动后端  http://localhost:8080'
Write-Host "  JAVA_HOME = $env:JAVA_HOME"
Write-Host '  停止服务：在本窗口按 Ctrl+C'
Write-Host '============================================' -ForegroundColor Cyan

& .\mvnw.cmd spring-boot:run
