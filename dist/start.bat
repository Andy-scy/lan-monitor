@echo off
cd /d "%~dp0"
tasklist /FI "IMAGENAME eq monitor.exe" 2>nul | find /I "monitor.exe" >nul && (
  echo [Pulse] Agent is already running.
  timeout /t 2 /nobreak >nul
  exit /b 0
)
if exist status.json del status.json >nul 2>&1
start "" /min monitor.exe
set /a tries=0
:wait
timeout /t 1 /nobreak >nul
if exist status.json goto show
set /a tries+=1
if %tries% lss 10 goto wait
echo [Pulse] Failed to start. See logs\monitor.log
pause
exit /b 1
:show
echo ==============================================
echo   Pulse LAN Monitor - started
echo ==============================================
type status.json
echo.
echo You can close this window. Agent keeps running.
timeout /t 8 /nobreak >nul
exit /b 0
