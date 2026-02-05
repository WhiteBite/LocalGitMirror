@echo off
REM Development mode - hot reload for both backend and frontend

echo ========================================
echo LocalGitMirror - Development Mode
echo ========================================
echo.

REM Check if Python is installed
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found! Please install Python 3.10+
    pause
    exit /b 1
)

REM Check if Node.js is installed
node --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js not found! Please install Node.js 18+
    pause
    exit /b 1
)

echo [0/4] Cleaning up existing processes...
taskkill /F /IM python.exe /T >nul 2>&1
taskkill /F /IM node.exe /T >nul 2>&1

echo [1/4] Starting Backend (hot reload)...
start "Backend Dev" cmd /c "cd backend && python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000"

timeout /t 2 /nobreak >nul

echo [2/4] Starting Frontend (Vite dev server)...
start "Frontend Dev" cmd /c "cd frontend && npm run dev"

timeout /t 2 /nobreak >nul

echo.
echo ========================================
echo Development servers started!
echo ========================================
echo.
echo Backend:  http://localhost:8000
echo Frontend: http://localhost:5173
echo.
echo Press Ctrl+C in each window to stop
echo ========================================
