@echo off
cd /d "%~dp0"
echo Cleaning and building OVC uber-jar...
echo.
call mvn clean
if %errorlevel% neq 0 (
    echo Clean failed!
    pause
    exit /b %errorlevel%
)
call mvn package -DskipTests "-Dnet.bytebuddy.experimental=true"
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b %errorlevel%
)
echo.
echo Build complete! Uber-jar created at:
echo target\ovc-1.1.1-runner.jar
echo.
echo To run: java -jar target\ovc-1.1.1-runner.jar
pause
