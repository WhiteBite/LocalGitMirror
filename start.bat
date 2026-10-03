@echo off
chcp 65001 >nul
cd /d "%~dp0"
REM Thin wrapper around run.py. No args = production, "dev" = development.
REM   start.bat        -> production
REM   start.bat dev    -> development (backend --reload + Vite, one window)
python run.py %* <NUL 2>&1
exit /B 0
