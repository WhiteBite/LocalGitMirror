@echo off
chcp 65001 >nul
REM Режим продакшена - отдача собранного frontend из backend

echo ========================================
echo LocalGitMirror - Режим продакшена
echo ========================================
echo.

REM Проверка Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Python не найден! Установите Python 3.10+
    pause
    exit /b 1
)

REM Проверка сборки frontend
if not exist "frontend\dist\index.html" (
    echo [ПРЕДУПРЕЖДЕНИЕ] Frontend не собран! Сборка сейчас...
    cd frontend
    call npm run build
    cd ..
    echo.
)

echo [1/2] Запуск Backend (режим продакшена)...
echo.

REM Читаем WEB_PORT из .env
for /f "tokens=2 delims==" %%A in ('findstr "^WEB_PORT=" .env') do set WEB_PORT=%%A

REM Читаем REDIRECT_HTTP_PORT (опционально) из .env
for /f "tokens=2 delims==" %%A in ('findstr "^REDIRECT_HTTP_PORT=" .env') do set REDIRECT_HTTP_PORT=%%A

if not defined WEB_PORT (
    set WEB_PORT=443
)

if not defined REDIRECT_HTTP_PORT (
    set REDIRECT_HTTP_PORT=80
)

echo ========================================
echo Сервер запущен по адресу:
echo ========================================
echo.
echo https://localhost:%WEB_PORT%
echo.
echo Нажмите Ctrl+C для остановки
echo ========================================
echo.

REM Запуск HTTP->HTTPS редиректа на 80 (best-effort)
echo [0/2] HTTP->HTTPS redirect (port %REDIRECT_HTTP_PORT% -> %WEB_PORT%)...
start "HTTP Redirect" cmd /c "cd backend && set REDIRECT_TO_PORT=%WEB_PORT% && python -m uvicorn app.redirect:app --host 0.0.0.0 --port %REDIRECT_HTTP_PORT%"

timeout /t 1 /nobreak >nul

cd backend
python -m uvicorn app.main:app --host 0.0.0.0 --port %WEB_PORT% --ssl-keyfile=../key.pem --ssl-certfile=../cert.pem
