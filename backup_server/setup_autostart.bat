@echo off
:: Setup Photo Backup Server to start automatically on Windows login
:: Run this script as Administrator

echo ============================================================
echo   Photo Backup Server - Auto-Start Setup
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

:: Create a VBS wrapper to run the server hidden (no console window)
echo Creating hidden launcher...
(
echo Set WshShell = CreateObject("WScript.Shell"^)
echo WshShell.CurrentDirectory = "%SCRIPT_DIR%"
echo WshShell.Run "cmd /c ""%SCRIPT_DIR%\run_server.bat""", 0, False
) > "%SCRIPT_DIR%\run_server_hidden.vbs"

:: Delete existing task if it exists
schtasks /delete /tn "PhotoBackupServer" /f >nul 2>&1

:: Create the scheduled task
echo Creating scheduled task...
schtasks /create /tn "PhotoBackupServer" /tr "wscript.exe \"%SCRIPT_DIR%\run_server_hidden.vbs\"" /sc onlogon /rl highest /f

if %ERRORLEVEL% equ 0 (
    echo.
    echo ============================================================
    echo   SUCCESS! Photo Backup Server will start automatically
    echo   when you log in to Windows.
    echo ============================================================
    echo.
    echo Task Name: PhotoBackupServer
    echo Trigger:   At logon
    echo.
    echo To start the server now, run: run_server.bat
    echo To remove auto-start, run: remove_autostart.bat
    echo.
) else (
    echo.
    echo ERROR: Failed to create scheduled task.
    echo Please try running this script as Administrator.
    echo.
)

pause
