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

# 项目根目录：本脚本位于 <项目根>/qidonqi/ 下，故取上一级
$root = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $root 'qianduan'
if (-not (Test-Path (Join-Path $frontendDir 'package.json'))) {
    Write-Host "[错误] 未找到前端目录：$frontendDir" -ForegroundColor Red
    Write-Host '       请确认本脚本位于 <项目根>/qidonqi/ 目录内。' -ForegroundColor Red
    exit 1
}

Set-Location $frontendDir

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Host '[错误] 未找到 npm，请先安装 Node.js 18+ 并确保其在 PATH 中。' -ForegroundColor Red
    exit 1
}

# 仅判断 node_modules 目录是否存在并不足够：安装被中断时会留下不完整的依赖树
# （缺少 node_modules\.bin\vite.cmd），此时 npm run dev 会报
# 「'vite' is not recognized as an internal or external command」。因此以 vite 可执行文件为准。
$viteBin = 'node_modules\.bin\vite.cmd'
if (-not (Test-Path $viteBin)) {
    if (Test-Path 'node_modules') {
        Write-Host '检测到前端依赖不完整（缺少 vite 可执行文件），正在补全依赖（npm install）...' -ForegroundColor Yellow
    } else {
        Write-Host '首次运行：正在安装前端依赖（npm install）...' -ForegroundColor Cyan
    }
    npm install
    if (-not (Test-Path $viteBin)) {
        Write-Host '[错误] 前端依赖安装未完成。' -ForegroundColor Red
        Write-Host '       请检查网络与 npm 源（本目录下的 .npmrc 已指向 https://registry.npmmirror.com），' -ForegroundColor Red
        Write-Host '       或手动执行：Remove-Item -Recurse -Force node_modules; npm install' -ForegroundColor Red
        exit 1
    }
}

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  启动前端  http://localhost:5173'
Write-Host '  停止服务：在本窗口按 Ctrl+C'
Write-Host '============================================' -ForegroundColor Cyan

npm run dev
