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
        # Keep-alive must NOT be 0: with 0 uvicorn closes the TCP connection
        # immediately after sending the response, which races slow/large reads
        # on the client and surfaces as "channel was closed" during pull.
        "timeout_graceful_shutdown": 30,
        "timeout_keep_alive": 30,
        # Log format with timestamps and no ANSI colors (clean when piped/redirected).
        "log_config": {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {
                "default": {
                    "format": "%(asctime)s [%(levelname)s] %(message)s",
                    "datefmt": "%Y-%m-%d %H:%M:%S",
                },
                "access": {
                    "format": '%(asctime)s [ACCESS] %(client_addr)s "%(request_line)s" %(status_code)s',
                    "datefmt": "%Y-%m-%d %H:%M:%S",
                    "class": "uvicorn.logging.AccessFormatter",
                    "use_colors": False,
                },
            },
            "handlers": {
                "default": {"class": "logging.StreamHandler", "formatter": "default"},
                "access":  {"class": "logging.StreamHandler", "formatter": "access"},
            },
            "loggers": {
                "uvicorn":        {"handlers": ["default"], "level": "INFO", "propagate": False},
                "uvicorn.error":  {"handlers": ["default"], "level": "INFO", "propagate": False},
                "uvicorn.access": {"handlers": ["access"],  "level": "INFO", "propagate": False},
            },
        },
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
