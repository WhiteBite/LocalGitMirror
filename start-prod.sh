#!/bin/bash
# Production mode - serve built frontend from backend

echo "========================================"
echo "LocalGitMirror - Production Mode"
echo "========================================"
echo ""

# Check if Python is installed
if ! command -v python3 &> /dev/null; then
    echo "[ERROR] Python not found! Please install Python 3.10+"
    exit 1
fi

# Check if frontend is built
if [ ! -f "frontend/dist/index.html" ]; then
    echo "[WARNING] Frontend not built! Building now..."
    cd frontend
    npm run build
    cd ..
    echo ""
fi

echo "[1/2] Starting Backend (production mode)..."
echo ""
echo "========================================"
echo "Server running at:"
echo "========================================"
echo ""
echo "http://localhost:8000"
echo ""
echo "Press Ctrl+C to stop"
echo "========================================"
echo ""

cd backend
python3 -m uvicorn app.main:app --host 0.0.0.0 --port 8000
