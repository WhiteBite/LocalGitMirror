import os
import sys
import subprocess
import time
import signal
import socket
import argparse
import webbrowser
from pathlib import Path

# === CONFIGURATION ===
PROJECT_ROOT = Path(__file__).parent.absolute()
BACKEND_DIR = PROJECT_ROOT / "backend"
FRONTEND_DIR = PROJECT_ROOT / "frontend"
PID_FILE = PROJECT_ROOT / "server.pid"
LOG_FILE = PROJECT_ROOT / "backend.log"

# Default ports
PROD_PORT = 8443
DEV_BACKEND_PORT = 8000
DEV_FRONTEND_PORT = 5173


def is_port_open(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(0.5)
    result = sock.connect_ex(("127.0.0.1", port))
    sock.close()
    return result == 0


def kill_process_tree(pid):
    """Kill a process and its children (Windows friendly)"""
    try:
        if os.name == "nt":
            subprocess.run(
                f"taskkill /F /T /PID {pid}",
                shell=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        else:
            os.kill(pid, signal.SIGKILL)
    except Exception:
        pass


def stop_all():
    """Stop running servers"""
    print("[!] Stopping LocalGitMirror...")

    # 1. Check PID file
    if PID_FILE.exists():
        try:
            pid = int(PID_FILE.read_text())
            kill_process_tree(pid)
            print(f"   [OK] Stopped PID {pid} from file.")
        except Exception:
            pass
        PID_FILE.unlink()

    # 2. Cleanup orphaned python processes running our app
    if os.name == "nt":
        # Find python processes running 'cli.py' or 'run.py' or 'uvicorn'
        # This is aggressive but necessary for dev mode
        subprocess.run(
            "taskkill /F /IM uvicorn.exe",
            shell=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    print("[OK] All services stopped.")


def start_prod():
    """Start in Production Mode (Background, HTTPS, Optimized)"""
    stop_all()
    print(f"[>] Starting in PRODUCTION mode (Port {PROD_PORT})...")

    # Run backend/run.py as a detached process
    creation_flags = 0
    if os.name == "nt":
        creation_flags = subprocess.CREATE_NEW_CONSOLE

    env = os.environ.copy()
    env["WEB_PORT"] = str(PROD_PORT)
    env["GIT_PORT"] = str(PROD_PORT + 1)  # Internal usage

    with open(LOG_FILE, "a") as log:
        proc = subprocess.Popen(
            [sys.executable, str(BACKEND_DIR / "run.py")],
            cwd=str(PROJECT_ROOT),
            env=env,
            stdout=log,
            stderr=log,
            creationflags=creation_flags,
        )

    PID_FILE.write_text(str(proc.pid))

    print(f"   [INFO] Server PID: {proc.pid}")
    print(f"   [INFO] Logs: {LOG_FILE}")

    # Wait for startup
    print("   ... Waiting for startup...", end="", flush=True)
    for _ in range(15):
        if is_port_open(PROD_PORT):
            print(" Done!")
            url = f"https://localhost:{PROD_PORT}"
            print(f"\n[OK] Server is ready at: {url}")
            print("   (Opening browser...)")
            webbrowser.open(url)
            return
        time.sleep(1)
        print(".", end="", flush=True)

    print("\n[ERR] Server took too long to start. Check logs.")


def start_dev():
    """Start in Developer Mode (Hot Reload, Vite, Console Output)"""
    stop_all()
    print("[>]  Starting in DEVELOPER mode...")

    processes = []

    try:
        # 1. Start Backend (Uvicorn with --reload)
        print(f"   [1/2] Starting Backend (Port {DEV_BACKEND_PORT})...")
        env = os.environ.copy()
        env["WEB_PORT"] = str(DEV_BACKEND_PORT)
        # We run uvicorn directly to enable reload
        backend_cmd = [
            sys.executable,
            "-m",
            "uvicorn",
            "app.main:app",
            "--host",
            "0.0.0.0",
            "--port",
            str(DEV_BACKEND_PORT),
            "--reload",
            "--app-dir",
            str(BACKEND_DIR),
        ]

        # On Windows, we want dev servers in new windows so we can see logs
        creation_flags = 0
        if os.name == "nt":
            creation_flags = subprocess.CREATE_NEW_CONSOLE

        p_back = subprocess.Popen(
            backend_cmd, cwd=str(PROJECT_ROOT), creationflags=creation_flags
        )
        processes.append(p_back)

        # 2. Start Frontend (Vite)
        print(f"   [2/2] Starting Frontend (Port {DEV_FRONTEND_PORT})...")
        npm_cmd = "npm.cmd" if os.name == "nt" else "npm"
        p_front = subprocess.Popen(
            [npm_cmd, "run", "dev"], cwd=str(FRONTEND_DIR), creationflags=creation_flags
        )
        processes.append(p_front)

        print("\n[OK] Dev environment running!")
        print(f"   Backend:  http://localhost:{DEV_BACKEND_PORT}")
        print(f"   Frontend: http://localhost:{DEV_FRONTEND_PORT}")
        print("   (Use Frontend URL for development)")

        # Keep script alive to manage processes
        print("\n[INFO] Press Ctrl+C to stop all dev servers.")
        while True:
            time.sleep(1)

    except KeyboardInterrupt:
        print("\n[!] Stopping dev environment...")
        for p in processes:
            kill_process_tree(p.pid)


def show_status():
    prod_up = is_port_open(PROD_PORT)
    dev_back_up = is_port_open(DEV_BACKEND_PORT)
    dev_front_up = is_port_open(DEV_FRONTEND_PORT)

    print("=== System Status ===")

    if prod_up:
        print(f"[OK] PRODUCTION Server is RUNNING (Port {PROD_PORT})")
        if PID_FILE.exists():
            print(f"   PID: {PID_FILE.read_text()}")
    else:
        print(f"[..] PRODUCTION Server is STOPPED (Port {PROD_PORT})")

    print("-" * 20)

    if dev_back_up:
        print(f"[OK] DEV Backend is RUNNING (Port {DEV_BACKEND_PORT})")
    else:
        print(f"[..] DEV Backend is STOPPED")

    if dev_front_up:
        print(f"[OK] DEV Frontend is RUNNING (Port {DEV_FRONTEND_PORT})")
    else:
        print(f"[..] DEV Frontend is STOPPED")


def tail_logs():
    if not LOG_FILE.exists():
        print("[ERR] No log file found.")
        return

    print(f"--- Showing last 50 lines of {LOG_FILE.name} ---")
    try:
        with open(LOG_FILE, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()
            for line in lines[-50:]:
                print(line.strip())
    except Exception as e:
        print(f"Error reading log: {e}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="LocalGitMirror CLI")
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    subparsers.add_parser("start", help="Start production server (background)")
    subparsers.add_parser("dev", help="Start development mode (hot reload)")
    subparsers.add_parser("stop", help="Stop all servers")
    subparsers.add_parser("status", help="Show system status")
    subparsers.add_parser("logs", help="Show server logs")

    args = parser.parse_args()

    if args.command == "start":
        start_prod()
    elif args.command == "dev":
        start_dev()
    elif args.command == "stop":
        stop_all()
    elif args.command == "status":
        show_status()
    elif args.command == "logs":
        tail_logs()
    else:
        parser.print_help()
