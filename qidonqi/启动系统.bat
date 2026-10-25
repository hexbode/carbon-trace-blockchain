@echo off
rem ============================================================
rem  Carbon Trace System - one click launcher
rem
rem  default : start the WSL Ubuntu stack (MySQL / Redis / nginx /
rem            WeBASE / carbon-trace), wait until the API is ready,
rem            then open http://localhost in the default browser
rem  dev     : start Windows local dev servers only
rem            (stub backend on 8080 + Vite on 5173)
rem
rem  Place this file in <project root>\qidonqi\ together with
rem  start-ubuntu.ps1 / start-backend.ps1 / start-frontend.ps1
rem  Commands are ASCII only on purpose (avoid cmd codepage issues)
rem ============================================================
rem 中文说明：
rem   （中文仅用于注释说明；可执行命令本身仍保持 ASCII，避免 cmd 代码页问题）
rem   不带参数：默认模式，调用 start-ubuntu.ps1 拉起 WSL Ubuntu 服务栈，
rem             等待后端就绪后由脚本自动打开 http://localhost。
rem   带参数 dev：开发模式，仅启动 Windows 本地服务（8080 后端 stub + 5173 前端），
rem             不涉及 WSL，适合本机调试。
rem   参数通过 %1 传入，大小写不敏感（if /i）。
rem ============================================================
setlocal
rem BASE 为启动器所在目录，%~dp0 自带结尾反斜杠，后续用 %BASE%文件名 拼接
set "BASE=%~dp0"

rem 参数为 dev 时跳到 :DEV 标签，否则继续走默认的 WSL 启动流程
if /i "%~1"=="dev" goto DEV

rem 前置检查：找不到同目录的 start-ubuntu.ps1 时无法继续，提示后退出（返回码 1）
if not exist "%BASE%start-ubuntu.ps1" (
  echo [ERROR] start-ubuntu.ps1 not found next to this launcher.
  echo         Expected: %BASE%start-ubuntu.ps1
  pause
  exit /b 1
)

rem 默认模式横幅：仅提示入口地址，具体启动/等待/打开浏览器由 PowerShell 脚本完成
echo ============================================
echo   Carbon Trace System - Ubuntu / WSL mode
echo   web : http://localhost
echo ============================================
echo.
rem 以 -NoProfile 跳过用户配置、-ExecutionPolicy Bypass 规避执行策略拦截，
rem 由 PowerShell 前台执行启动脚本；其退出码通过 errorlevel 回传
powershell -NoProfile -ExecutionPolicy Bypass -File "%BASE%start-ubuntu.ps1"
rem 启动脚本返回非 0（如发行版名称错误、后端 3 分钟未就绪）时暂停，便于查看错误信息
if errorlevel 1 (
  echo.
  echo [ERROR] launch failed - see messages above.
  pause
)
rem 无论成功与否都返回 0：错误详情已由 PowerShell 输出，避免双击运行时控制台一闪而过
exit /b 0

:DEV
rem 开发模式：不启动 WSL，只在本机开两个 PowerShell 窗口跑后端与前端。
rem 前置检查：两个启动脚本必须与启动器同目录，缺任一则提示后退出（返回码 1）
if not exist "%BASE%start-backend.ps1" (
  echo [ERROR] start-backend.ps1 not found next to this launcher.
  echo         Expected: %BASE%start-backend.ps1
  pause
  exit /b 1
)
if not exist "%BASE%start-frontend.ps1" (
  echo [ERROR] start-frontend.ps1 not found next to this launcher.
  echo         Expected: %BASE%start-frontend.ps1
  pause
  exit /b 1
)

rem Dev mode uses port 8080 locally; the Ubuntu stack also listens on 8080,
rem so refuse to start with a clear hint instead of a cryptic bind failure.
rem 端口占用检查（8080）：Ubuntu 模式下的 carbon-trace 也监听 8080，
rem 若已被占用则直接给出停服提示并退出，避免后端启动时报难懂的端口绑定错误。
powershell -NoProfile -Command "if(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue){exit 1}else{exit 0}"
if errorlevel 1 (
  echo [WARN] port 8080 is already in use - most likely the Ubuntu carbon-trace service.
  echo        stop it first: wsl -d Ubuntu-26.04 -u root -- systemctl stop carbon-trace
  echo        or just run this launcher without the "dev" argument.
  pause
  exit /b 1
)

rem 开发模式横幅：标注后端为 stub 链模式、前后端端口
echo ============================================
echo   Carbon Trace System - Windows dev mode
echo   backend  : http://localhost:8080  (stub chain)
echo   frontend : http://localhost:5173
echo ============================================
echo.
rem 第 1 步：在独立窗口启动后端；-NoExit 让窗口在进程结束后仍保留，便于看日志
echo [1/2] launching backend ...
start "carbon-backend" powershell -NoProfile -ExecutionPolicy Bypass -NoExit -File "%BASE%start-backend.ps1"

rem 等待后端开始监听 8080：最多 60 轮 × 2 秒 ≈ 120 秒；
rem 超时只告警不退出，因为首次构建可能更久，后端窗口仍会继续运行
echo       waiting for backend to listen on 8080 (up to ~120s) ...
powershell -NoProfile -Command "for($i=0;$i -lt 60;$i++){ if(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue){exit 0}; Start-Sleep -Seconds 2 }; exit 1"
if errorlevel 1 (
  echo       [WARN] backend is not listening on 8080 yet - check the backend window.
) else (
  echo       backend is up.
)

rem 第 2 步：在独立窗口启动前端（Vite 5173），与后端是否就绪无关
echo [2/2] launching frontend ...
start "carbon-frontend" powershell -NoProfile -ExecutionPolicy Bypass -NoExit -File "%BASE%start-frontend.ps1"

rem 结尾提示两个窗口的停止方式；timeout 5 秒让窗口完成初始化后再收起控制台
echo.
echo Two windows were opened.
echo   backend  window: Ctrl+C stops the backend
echo   frontend window: Ctrl+C stops the frontend
timeout /t 5 /nobreak >nul
exit /b 0
