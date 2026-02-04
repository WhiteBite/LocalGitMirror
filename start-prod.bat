@echo off
REM Production mode - serve built frontend from backend

echo ========================================
echo LocalGitMirror - Production Mode
echo ========================================
echo.

REM Check if Python is installed
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found! Please install Python 3.10+
    pause
    exit /b 1
)

REM Check if frontend is built
if not exist "frontend\dist\index.html" (
    echo [WARNING] Frontend not built! Building now...
    cd frontend
    call npm run build
    cd ..
    echo.
)

echo [1/2] Starting Backend (production mode)...
echo.
echo ========================================
echo Server running at:
echo ========================================
echo.
echo http://localhost:8000
echo.
echo Press Ctrl+C to stop
echo ========================================
echo.

cd backend
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
