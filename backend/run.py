#!/usr/bin/env python3
import sys
import os
import signal
from pathlib import Path

backend_dir = Path(__file__).parent
sys.path.insert(0, str(backend_dir))


def fast_exit(sig=None, frame=None):
    sys.stdout.write("\n[INFO] Fast shutdown triggered...\n")
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    from app.main import app, CONFIG
    import uvicorn

    project_root = backend_dir.parent
    ssl_cert = project_root / "cert.pem"
    ssl_key = project_root / "key.pem"

    # PRO-Level Shutdown:
    # timeout_graceful_shutdown=0 -> die instantly on Ctrl+C
    kwargs = {
        "host": "0.0.0.0",
        "port": CONFIG["web_port"],
        "reload": False,
        "log_level": "info",
        "timeout_graceful_shutdown": 0,
    }

    if ssl_cert.exists() and ssl_key.exists():
        kwargs["ssl_certfile"] = str(ssl_cert)
        kwargs["ssl_keyfile"] = str(ssl_key)
        print(f"[SSL] Enabled (Cert: {ssl_cert.name})")

    print(f"[INFO] Server starting on port {CONFIG['web_port']}")
    print("[INFO] Press Ctrl+C for instant exit.")

    try:
        uvicorn.run(app, **kwargs)
    except Exception:
        os._exit(0)
