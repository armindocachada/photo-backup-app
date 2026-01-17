@echo off
title Photo Backup Server
echo ============================================================
echo                   PHOTO BACKUP SERVER
echo ============================================================
echo.
echo Starting server...
echo.

cd /d "%~dp0"

REM Check if Python is available
where python >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Python is not installed or not in PATH.
    echo Please install Python from https://python.org
    pause
    exit /b 1
)

REM Check if virtual environment exists
if not exist "venv" (
    echo Creating virtual environment...
    python -m venv venv
)

REM Activate virtual environment
call venv\Scripts\activate.bat

REM Install dependencies if needed
pip show fastapi >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Installing dependencies...
    pip install -r requirements.txt
)

REM Run the server
python main.py

pause
