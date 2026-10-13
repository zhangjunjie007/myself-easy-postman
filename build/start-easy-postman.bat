@echo off
setlocal DisableDelayedExpansion
rem GUI instances allocate independent profiles automatically; no listen port is needed.
if not "%~1"=="" (
    echo This desktop launcher takes no port argument. Run it again to open another window.
    pause
    exit /b 2
)
set "APP_JAR=%~dp0easy-postman-6.2.0.jar"
if not exist "%APP_JAR%" (
    echo Put this script beside easy-postman-6.2.0.jar.
    pause
    exit /b 1
)
set "JAVA_CMD=java"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
echo Starting EasyPostman. Each running window uses an independent configuration.
echo Java 17 or later is required. Close the app window to stop this instance.
"%JAVA_CMD%" -jar "%APP_JAR%"
set "LAUNCH_EXIT=%ERRORLEVEL%"
if not "%LAUNCH_EXIT%"=="0" (
    echo Startup failed. Check the error above and JAVA_HOME or PATH.
    pause
)
exit /b %LAUNCH_EXIT%
