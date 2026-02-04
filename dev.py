import os
import subprocess
import time
import sys


def start_dev():
    # Get base directory
    base_dir = os.path.dirname(os.path.abspath(__file__))

    print("🚀 Starting LocalGitMirror Dev Mode...")

    # 1. Start Backend with reload
    # We use sys.executable to ensure we use the same python environment
    backend_cmd = [
        sys.executable,
        "-m",
        "uvicorn",
        "app.main:app",
        "--host",
        "0.0.0.0",
        "--port",
        "8000",
        "--reload",
        "--reload-dir",
        "backend",
    ]

    print(f"📡 Starting Backend on http://localhost:8000 (with reload)...")
    backend_proc = subprocess.Popen(backend_cmd, cwd=os.path.join(base_dir, "backend"))

    # 2. Start Frontend with Vite HMR
    print(f"💻 Starting Frontend on http://localhost:5173 (Vite HMR)...")
    # Using node directly to bypass shell issues found earlier
    vite_path = os.path.join(
        base_dir, "frontend", "node_modules", "vite", "bin", "vite.js"
    )
    frontend_cmd = ["node", vite_path]

    frontend_proc = subprocess.Popen(
        frontend_cmd, cwd=os.path.join(base_dir, "frontend")
    )

    print("\n✅ Both servers are running.")
    print("- Frontend (HMR): http://localhost:5173")
    print("- Backend (API): http://localhost:8000")
    print("Press Ctrl+C to stop both.\n")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n🛑 Stopping servers...")
        backend_proc.terminate()
        frontend_proc.terminate()
        print("Done.")


if __name__ == "__main__":
    start_dev()
