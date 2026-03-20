#!/usr/bin/env python3
import sys
import os
import signal
from pathlib import Path

backend_dir = Path(__file__).parent
sys.path.insert(0, str(backend_dir))


def force_exit(sig=None, frame=None):
    """Force immediate exit on Ctrl+C"""
    sys.stdout.write("\n[INFO] Shutting down...\n")
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    from app.main import app, CONFIG
    import uvicorn

    project_root = backend_dir.parent
    ssl_cert = project_root / "cert.pem"
    ssl_key = project_root / "key.pem"

    # Register signal handlers for immediate shutdown
    signal.signal(signal.SIGINT, force_exit)
    signal.signal(signal.SIGTERM, force_exit)

    kwargs = {
        "host": "0.0.0.0",
        "port": CONFIG["web_port"],
        "reload": False,
        "log_level": "info",
        "timeout_graceful_shutdown": 0,
        "timeout_keep_alive": 0,
    }

    if ssl_cert.exists() and ssl_key.exists():
        kwargs["ssl_certfile"] = str(ssl_cert)
        kwargs["ssl_keyfile"] = str(ssl_key)
        print(f"[SSL] Enabled (Cert: {ssl_cert.name})")

    print(f"[INFO] Server starting on port {CONFIG['web_port']}")
    print("[INFO] Press Ctrl+C to exit.")

    try:
        uvicorn.run(app, **kwargs)
    except KeyboardInterrupt:
        force_exit()
    except Exception:
        os._exit(0)
