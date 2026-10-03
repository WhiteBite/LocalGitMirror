$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

Write-Host "Starting DocCache Mirror..." -ForegroundColor Cyan

$env:PYTHONIOENCODING = "utf-8"

try {
    & python run.py @args
} catch {
    # Ctrl+C lands here on some PowerShell hosts
} finally {
    exit 0
}
