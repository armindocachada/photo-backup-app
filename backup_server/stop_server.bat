@echo off
echo ============================================================
echo   Photo Backup Server - Stop
echo ============================================================
echo.

:: Stop the scheduled task if it exists
schtasks /end /tn "PhotoBackupServer" >nul 2>&1

:: Find and kill process using port 9121
echo Looking for server process on port 9121...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":9121" ^| findstr "LISTENING"') do (
    echo Found process with PID: %%a
    echo Stopping process...
    taskkill /PID %%a /F >nul 2>&1
    if %ERRORLEVEL% equ 0 (
        echo Process %%a stopped successfully.
    ) else (
        echo Failed to stop process %%a - may require admin privileges.
    )
)

:: Also try to find any python processes running main.py using tasklist and window title
:: This catches processes that might not have bound to the port yet
for /f "tokens=2" %%i in ('tasklist /fi "WINDOWTITLE eq Photo Backup Server" /fo list 2^>nul ^| findstr "PID:"') do (
    echo Found process by window title with PID: %%i
    taskkill /pid %%i /f >nul 2>&1
)

echo.
echo Server stopped.
echo.
pause
