@echo off
setlocal

REM One-command happy-path E2E test runner.
REM Starts backend on 443 (HTTPS) if needed and runs the stealth roundtrip test.

echo ========================================
echo LocalGitMirror - E2E Happy Test
echo ========================================
echo.

REM API key and sync password are read from .env
for /f "tokens=2 delims==" %%A in ('findstr "^API_KEY=" .env') do set API_KEY=%%A
if not defined API_KEY (
  echo [error] API_KEY not set in .env
  exit /b 1
)

for /f "tokens=2 delims==" %%A in ('findstr "^SYNC_PASSWORD=" .env') do set SYNC_PASSWORD=%%A
if not defined SYNC_PASSWORD (
  echo [error] SYNC_PASSWORD not set in .env
  exit /b 1
)

REM Try to start backend on 443 (dev/reload not needed for test). If already running, it will fail but test can still proceed.
echo [1/2] Starting backend (best-effort) on https://localhost:443 ...
start "LGM Backend (E2E)" cmd /c "cd backend && set API_KEY=%API_KEY% && set SYNC_PASSWORD=%SYNC_PASSWORD% && python -m uvicorn app.main:app --host 127.0.0.1 --port 443 --ssl-keyfile=../key.pem --ssl-certfile=../cert.pem"

timeout /t 2 /nobreak >nul

echo [2/2] Running E2E stealth roundtrip...
python scripts\e2e_stealth_roundtrip.py --base-url https://localhost:443 --api-key %API_KEY% --password %SYNC_PASSWORD%

echo.
echo Done.
echo.
endlocal
