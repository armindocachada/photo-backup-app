@echo off
echo ============================================================
echo   Photo Backup Server - Stop
echo ============================================================
echo.

:: Stop the scheduled task
schtasks /end /tn "PhotoBackupServer" >nul 2>&1

:: Also kill any running Python processes for this server
:: Using WMIC to find and kill python.exe running main.py
for /f "tokens=2" %%i in ('wmic process where "commandline like '%%main.py%%' and name='python.exe'" get processid 2^>nul ^| findstr /r "[0-9]"') do (
    echo Stopping process %%i...
    taskkill /pid %%i /f >nul 2>&1
)

echo.
echo Server stopped.
echo.
pause
