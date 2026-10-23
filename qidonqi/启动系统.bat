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
rem  Content is ASCII only on purpose (avoid cmd codepage issues)
rem ============================================================
setlocal
set "BASE=%~dp0"

if /i "%~1"=="dev" goto DEV

if not exist "%BASE%start-ubuntu.ps1" (
  echo [ERROR] start-ubuntu.ps1 not found next to this launcher.
  echo         Expected: %BASE%start-ubuntu.ps1
  pause
  exit /b 1
)

echo ============================================
echo   Carbon Trace System - Ubuntu / WSL mode
echo   web : http://localhost
echo ============================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%BASE%start-ubuntu.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] launch failed - see messages above.
  pause
)
exit /b 0

:DEV
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
powershell -NoProfile -Command "if(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue){exit 1}else{exit 0}"
if errorlevel 1 (
  echo [WARN] port 8080 is already in use - most likely the Ubuntu carbon-trace service.
  echo        stop it first: wsl -d Ubuntu-26.04 -u root -- systemctl stop carbon-trace
  echo        or just run this launcher without the "dev" argument.
  pause
  exit /b 1
)

echo ============================================
echo   Carbon Trace System - Windows dev mode
echo   backend  : http://localhost:8080  (stub chain)
echo   frontend : http://localhost:5173
echo ============================================
echo.
echo [1/2] launching backend ...
start "carbon-backend" powershell -NoProfile -ExecutionPolicy Bypass -NoExit -File "%BASE%start-backend.ps1"

echo       waiting for backend to listen on 8080 (up to ~120s) ...
powershell -NoProfile -Command "for($i=0;$i -lt 60;$i++){ if(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue){exit 0}; Start-Sleep -Seconds 2 }; exit 1"
if errorlevel 1 (
  echo       [WARN] backend is not listening on 8080 yet - check the backend window.
) else (
  echo       backend is up.
)

echo [2/2] launching frontend ...
start "carbon-frontend" powershell -NoProfile -ExecutionPolicy Bypass -NoExit -File "%BASE%start-frontend.ps1"

echo.
echo Two windows were opened.
echo   backend  window: Ctrl+C stops the backend
echo   frontend window: Ctrl+C stops the frontend
timeout /t 5 /nobreak >nul
exit /b 0
