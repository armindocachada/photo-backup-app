@echo off
:: Remove Photo Backup Server auto-start
:: Run this script as Administrator

echo ============================================================
echo   Photo Backup Server - Remove Auto-Start
echo ============================================================
echo.

:: Check for admin rights
net session >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo This script requires Administrator privileges.
    echo Please right-click and select "Run as administrator"
    pause
    exit /b 1
)

:: Get the directory where this script is located
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

:: Delete the scheduled task
echo Removing scheduled task...
schtasks /delete /tn "PhotoBackupServer" /f

:: Delete the VBS launcher
if exist "%SCRIPT_DIR%\run_server_hidden.vbs" (
    del "%SCRIPT_DIR%\run_server_hidden.vbs"
    echo Removed hidden launcher script.
)

if %ERRORLEVEL% equ 0 (
    echo.
    echo ============================================================
    echo   SUCCESS! Auto-start has been removed.
    echo ============================================================
    echo.
) else (
    echo.
    echo Note: Task may not have existed.
    echo.
)

pause
