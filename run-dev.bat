@echo off
cd /d "%~dp0"
echo Cleaning and starting Open Video Clipper in dev mode...
echo.
call mvn clean
if %errorlevel% neq 0 (
    echo Clean failed!
    pause
    exit /b %errorlevel%
)
call mvn quarkus:dev "-Dnet.bytebuddy.experimental=true"
if %errorlevel% neq 0 (
    echo Dev mode exited with error!
    pause
)
