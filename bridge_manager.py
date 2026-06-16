import os
import sys
import subprocess
import time
import signal
import socket
import argparse
from pathlib import Path

# Config
PID_FILE = Path("server.pid")
LOG_FILE = Path("backend.log")
SCRIPT_PATH = Path("backend/run.py")
PORT = 8443


def is_port_open(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(1)
    result = sock.connect_ex(("127.0.0.1", port))
    sock.close()
    return result == 0


def start_server():
    if PID_FILE.exists():
        print(f"[WARN] PID file {PID_FILE} exists. Server might be running.")
        try:
            pid = int(PID_FILE.read_text())
            # Check if process exists using tasklist on Windows
            result = subprocess.run(
                f'tasklist /FI "PID eq {pid}"',
                capture_output=True,
                text=True,
                shell=True,
            )
            if str(pid) in result.stdout:
                print(f"[INFO] Server is already running (PID: {pid}).")
                return
            else:
                print("[INFO] PID file is stale. Removing.")
                PID_FILE.unlink()
        except Exception:
            PID_FILE.unlink()

    print("[INFO] Starting server...")

    # Windows specific flags to run in background (no visible console)
    creation_flags = 0
    if os.name == "nt":
        creation_flags = subprocess.CREATE_NO_WINDOW | subprocess.DETACHED_PROCESS

    with open(LOG_FILE, "a") as log:
        # Use sys.executable to ensure we use the same python env
        process = subprocess.Popen(
            [sys.executable, str(SCRIPT_PATH)],
            stdout=log,
            stderr=log,
            creationflags=creation_flags,
            cwd=os.getcwd(),
        )

    PID_FILE.write_text(str(process.pid))
    print(f"[SUCCESS] Server started! PID: {process.pid}")
    print(f"[INFO] Logs redirected to {LOG_FILE}")

    # Wait a bit to ensure startup
    for _ in range(10):
        time.sleep(1)
        if is_port_open(PORT):
            print(f"[SUCCESS] Port {PORT} is open. Server is ready.")
            return
        print(".", end="", flush=True)
    print("\n[WARN] Server process started, but port is not yet open. Check logs.")


def stop_server():
    if not PID_FILE.exists():
        print("[INFO] No PID file found.")
        # Fallback: Kill by port/name? No, too risky.
        return

    try:
        pid = int(PID_FILE.read_text())
        print(f"[INFO] Stopping server (PID: {pid})...")

        if os.name == "nt":
            # Force kill on Windows is often more reliable for detached processes
            subprocess.run(
                f"taskkill /F /PID {pid}",
                shell=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        else:
            os.kill(pid, signal.SIGTERM)

        print("[SUCCESS] Server stopped.")
    except Exception as e:
        print(f"[ERROR] Failed to stop server: {e}")
    finally:
        if PID_FILE.exists():
            PID_FILE.unlink()


def status_server():
    running = False
    pid = None

    if PID_FILE.exists():
        pid = PID_FILE.read_text()
        # Check if actually running
        if os.name == "nt":
            res = subprocess.run(
                f'tasklist /FI "PID eq {pid}"',
                capture_output=True,
                text=True,
                shell=True,
            )
            if str(pid) in res.stdout:
                running = True

    port_open = is_port_open(PORT)

    print("=== Server Status ===")
    print(f"PID File: {'Present' if PID_FILE.exists() else 'Missing'} ({pid})")
    print(f"Process : {'RUNNING' if running else 'STOPPED'}")
    print(f"Port {PORT}: {'OPEN' if port_open else 'CLOSED'}")

    if running and port_open:
        print("\n[OK] System Operational")
    else:
        print("\n[!] System Issues Detected")


def tail_logs(lines=20):
    if not LOG_FILE.exists():
        print("[INFO] No log file found.")
        return

    print(f"=== Last {lines} lines of {LOG_FILE} ===")
    try:
        with open(LOG_FILE, "r", encoding="utf-8", errors="ignore") as f:
            content = f.readlines()
            for line in content[-lines:]:
                print(line.strip())
    except Exception as e:
        print(f"[ERROR] Reading logs: {e}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Server Manager")
    parser.add_argument(
        "action",
        choices=["start", "stop", "restart", "status", "logs"],
        help="Action to perform",
    )
    args = parser.parse_args()

    if args.action == "start":
        start_server()
    elif args.action == "stop":
        stop_server()
    elif args.action == "restart":
        stop_server()
        time.sleep(2)
        start_server()
    elif args.action == "status":
        status_server()
    elif args.action == "logs":
        tail_logs()
