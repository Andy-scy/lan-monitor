@echo off
rem Copy the built APK next to this script and plug in your phone (USB debugging on).
set APK=%~dp0..ndroidppuild\outputspk\debugpp-debug.apk
if not exist "%APK%" (
  echo APK not found: %APK%
  echo Build it first: cd android ^&^& gradle assembleDebug
  pause
  exit /b 1
)
adb install -r "%APK%"
adb shell monkey -p com.pulse.monitor -c android.intent.category.LAUNCHER 1 >nul
echo Done. Pulse launched.
pause
