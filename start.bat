@echo off
chcp 65001 >nul
title LocalGitMirror Launcher
color 0B

echo ===================================================
echo       LOCAL GIT MIRROR - STEALTH BRIDGE
echo ===================================================
echo.

:: Проверка Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ОШИБКА] Python не установлен или отсутствует в PATH.
    pause
    exit /b
)

:: Проверка зависимостей
if not exist "backend\venv" (
    echo [ИНФО] Создание виртуального окружения...
    python -m venv backend\venv
    call backend\venv\Scripts\activate
    echo [ИНФО] Установка зависимостей...
    pip install -r backend\requirements.txt
) else (
    call backend\venv\Scripts\activate
)

:: Запуск сервера
echo [ИНФО] Запуск Backend и Git сервера...
echo [ИНФО] UI будет доступен по адресу http://localhost:8000
echo.
echo Нажмите Ctrl+C для остановки.
echo.

python backend/run.py

pause
