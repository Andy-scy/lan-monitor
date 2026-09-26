@echo off
taskkill /F /IM monitor.exe >nul 2>&1
if %errorlevel%==0 (echo [Pulse] Agent stopped.) else (echo [Pulse] Agent is not running.)
timeout /t 2 /nobreak >nul
exit /b 0
