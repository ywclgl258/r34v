@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

REM Local preview: start site proxy + static server + open browser.
REM No Android SDK, no emulator needed.
REM
REM Usage:
REM   tool\preview.bat          preview the existing web build
REM   tool\preview.bat build    rebuild first, then serve

if /i "%1"=="build" (
    echo [build] flutter build web ...
    call flutter build web --dart-define=R34V_API_BASE=http://127.0.0.1:8898
    if errorlevel 1 (
        echo.
        echo Build failed. See the errors above.
        pause
        exit /b 1
    )
)

if not exist "build\web\index.html" (
    echo No web build found. Run this once first:
    echo     tool\preview.bat build
    pause
    exit /b 1
)

REM Free the ports if a previous run left them occupied.
for %%P in (8898 8899) do (
    for /f "tokens=5" %%I in ('netstat -ano ^| findstr ":%%P " ^| findstr LISTENING') do (
        echo [cleanup] port %%P held by PID %%I, killing it
        taskkill /F /PID %%I >nul 2>&1
    )
)

echo [1/3] starting site proxy on :8898 ...
start "r34v-proxy" /min cmd /c "python tool\preview_proxy.py 8898"

echo [2/3] starting static server on :8899 ...
start "r34v-web" /min cmd /c "python -m http.server 8899 --bind 127.0.0.1 --directory build\web"

echo [3/3] waiting for the server ...
set READY=0
for /l %%i in (1,1,20) do (
    if !READY!==0 (
        timeout /t 1 >nul
        powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri 'http://127.0.0.1:8899/' -TimeoutSec 2 -UseBasicParsing)|Out-Null;exit 0}catch{exit 1}" >nul 2>&1
        if !errorlevel!==0 set READY=1
    )
)

if !READY!==0 (
    echo.
    echo Server did not come up. Likely causes:
    echo   1. python not on PATH  -- run: python --version
    echo   2. port already taken   -- try: python tool\preview_proxy.py 8908
    pause
    exit /b 1
)

echo.
echo ============================================
echo   preview:  http://127.0.0.1:8899/
echo ============================================
echo.
echo The :8898 proxy works around browser CORS.
echo Close the two minimized windows to stop.
echo.

start "" http://127.0.0.1:8899/
timeout /t 3 >nul
