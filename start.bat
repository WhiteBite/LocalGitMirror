@echo off
title LocalGitMirror Launcher
color 0B

echo ===================================================
echo       LOCAL GIT MIRROR - STEALTH BRIDGE
echo ===================================================
echo.

:: Check for Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Python is not installed or not in PATH.
    pause
    exit /b
)

:: Check dependencies
if not exist "backend\venv" (
    echo [INFO] Creating virtual environment...
    python -m venv backend\venv
    call backend\venv\Scripts\activate
    echo [INFO] Installing dependencies...
    pip install -r backend\requirements.txt
) else (
    call backend\venv\Scripts\activate
)

:: Start Server
echo [INFO] Starting Backend ^& Git Server...
echo [INFO] UI will be available at http://localhost:8000
echo.
echo Press Ctrl+C to stop.
echo.

python backend/run.py

pause
