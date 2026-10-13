@echo off
setlocal DisableDelayedExpansion
rem Keep the PowerShell script beside this launcher. It targets Postman/EasyPostman only.
if not exist "%~dp0stop-postman.ps1" (
    echo Missing stop-postman.ps1. Keep both cleanup files in the same folder.
    pause
    exit /b 1
)
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-postman.ps1" %*
set "CLEANUP_EXIT=%ERRORLEVEL%"
pause
exit /b %CLEANUP_EXIT%
