#!/bin/bash
# Development mode - hot reload for both backend and frontend

echo "========================================"
echo "LocalGitMirror - Development Mode"
echo "========================================"
echo ""

# Check if Python is installed
if ! command -v python3 &> /dev/null; then
    echo "[ERROR] Python not found! Please install Python 3.10+"
    exit 1
fi

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "[ERROR] Node.js not found! Please install Node.js 18+"
    exit 1
fi

echo "[1/4] Starting Backend (hot reload)..."
cd backend
python3 -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000 &
BACKEND_PID=$!
cd ..

sleep 2

echo "[2/4] Starting Frontend (Vite dev server)..."
cd frontend
npm run dev &
FRONTEND_PID=$!
cd ..

sleep 2

echo ""
echo "========================================"
echo "Development servers started!"
echo "========================================"
echo ""
echo "Backend:  http://localhost:8000"
echo "Frontend: http://localhost:5173"
echo ""
echo "PIDs: Backend=$BACKEND_PID Frontend=$FRONTEND_PID"
echo ""
echo "Press Ctrl+C to stop all servers"
echo "========================================"

# Trap Ctrl+C and kill both processes
trap "kill $BACKEND_PID $FRONTEND_PID; exit" INT

# Wait for both processes
wait
