@echo off
rem ============================================================
rem  Carbon Trace System - one click launcher
rem  Opens two windows: backend (8080) and frontend (5173)
rem  Content is ASCII only on purpose (avoid cmd codepage issues)
rem ============================================================
setlocal
set "BASE=%~dp0"

echo ============================================
echo   Carbon Trace System
echo   backend  : http://localhost:8080
echo   frontend : http://localhost:5173
echo ============================================
echo.

echo [1/2] launching backend ...
start "carbon-backend" powershell -NoProfile -ExecutionPolicy Bypass -NoExit -File "%BASE%start-backend.ps1"

echo       waiting for backend to be ready (about 15s) ...
timeout /t 15 /nobreak >nul

echo [2/2] launching frontend ...
start "carbon-frontend" powershell -NoProfile -ExecutionPolicy Bypass -NoExit -File "%BASE%start-frontend.ps1"

echo.
echo Two windows were opened. Close them to stop the services.
timeout /t 5 /nobreak >nul
exit /b 0
