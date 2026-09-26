@echo off
net session >nul 2>&1 || (
  echo Please right-click this file and "Run as administrator".
  pause
  exit /b 1
)
rem Set network to private (home LAN) and allow Pulse ports on any profile.
powershell -NoProfile -Command "Get-NetConnectionProfile | Set-NetConnectionProfile -NetworkCategory Private"
netsh advfirewall firewall delete rule name="Pulse Monitor" >nul 2>&1
netsh advfirewall firewall delete rule name="Pulse Monitor Discovery" >nul 2>&1
netsh advfirewall firewall add rule name="Pulse Monitor" dir=in action=allow protocol=TCP localport=42710 profile=any >nul
netsh advfirewall firewall add rule name="Pulse Monitor Discovery" dir=in action=allow protocol=UDP localport=42711 profile=any >nul
echo Done. Network set to Private, TCP 42710 + UDP 42711 allowed.
pause
