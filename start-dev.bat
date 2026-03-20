@echo off
REM Режим разработки - горячая перезагрузка для backend и frontend

echo ========================================
echo LocalGitMirror - Режим разработки
echo ========================================
echo.

REM Проверка Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Python не найден! Установите Python 3.10+
    pause
    exit /b 1
)

REM Проверка Node.js
node --version >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Node.js не найден! Установите Node.js 18+
    pause
    exit /b 1
)

echo [0/4] Остановка существующих процессов...
taskkill /F /IM python.exe /T >nul 2>&1
taskkill /F /IM node.exe /T >nul 2>&1

echo [1/4] Запуск Backend (горячая перезагрузка)...
start "Backend Dev" cmd /c "cd backend && python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 443 --ssl-keyfile=../key.pem --ssl-certfile=../cert.pem"

timeout /t 2 /nobreak >nul

echo [2/4] Запуск Frontend (Vite dev server)...
start "Frontend Dev" cmd /c "cd frontend && npm run dev"
timeout /t 2 /nobreak >nul

echo.
echo ========================================
echo Серверы разработки запущены!
echo ========================================
echo.
echo Backend:  https://localhost:443
echo Frontend: http://localhost:5173
echo.
echo Нажмите Ctrl+C в каждом окне для остановки
echo ========================================
