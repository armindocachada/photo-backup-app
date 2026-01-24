@echo off
echo Setting up Photo Backup Server...
echo.

REM Create virtual environment if it doesn't exist
if not exist "venv" (
    echo Creating virtual environment...
    python -m venv venv
)

REM Activate virtual environment and install dependencies
echo Activating virtual environment and installing dependencies...
call venv\Scripts\activate.bat
pip install -r requirements.txt

echo.
if %ERRORLEVEL% EQU 0 (
    echo.
    echo Setup complete!
    echo Use 'run.bat' to start the server.
) else (
    echo.
    echo Installation failed. Make sure Python is installed.
)

pause
