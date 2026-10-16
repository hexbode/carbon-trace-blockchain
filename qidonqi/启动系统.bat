@echo off
rem ============================================================
rem  Carbon Trace System - one click launcher
rem  Opens two windows: backend (8080) and frontend (5173)
rem  Place this file in <project root>\qidonqi\ together with
rem  start-backend.ps1 / start-frontend.ps1.
rem  Content is ASCII only on purpose (avoid cmd codepage issues)
rem ============================================================
setlocal
set "BASE=%~dp0"

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

echo ============================================
echo   Carbon Trace System
echo   backend  : http://localhost:8080
echo   frontend : http://localhost:5173
echo ============================================
echo.
echo   chain mode: stub (offline dev stub, no WeBASE needed)
echo   to use the real chain: set CARBON_CHAIN_MODE=webase
echo   (see README section 4) before launching.
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
