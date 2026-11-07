@echo off
setlocal
rem Use the verified image staged alongside this workspace's outputs folder.
for %%I in ("%~dp0..\..\..\work") do set "ROAD_WORK=%%~fI"
if not exist "%ROAD_WORK%\tooling\pleos-extracted.ok" goto missing
set "ANDROID_AVD_HOME=%ROAD_WORK%\avd"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%\emulator\emulator.exe" goto missing
"%ANDROID_HOME%\emulator\emulator.exe" -avd RoadAlertPleos -port 5558 -no-snapshot -no-metrics
exit /b %errorlevel%
:missing
echo Use Android Studio Device Manager and the official Pleos setup in README.md.
pause
exit /b 1
