# ============================================================
# 碳链通 · 启动前端开发服务器（Vite，端口 5173）
#
# 说明：首次运行若没有 node_modules 会自动执行 npm install。
#       已配置代理：/api、/uploads → http://localhost:8080
#
# 用法：右键「使用 PowerShell 运行」，或由 启动系统.bat 调用
# ============================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Set-Location (Join-Path $PSScriptRoot 'qianduan')

if (-not (Test-Path 'node_modules')) {
    Write-Host '首次运行：正在安装前端依赖（npm install）...' -ForegroundColor Cyan
    npm install
}

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  启动前端  http://localhost:5173'
Write-Host '  停止服务：在本窗口按 Ctrl+C'
Write-Host '============================================' -ForegroundColor Cyan

npm run dev
