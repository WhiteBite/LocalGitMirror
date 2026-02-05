#!/usr/bin/env python3
"""
LocalGitMirror Entry Point
Run this file to start the application with SSL support
"""

import sys
import os
from pathlib import Path

# Add backend directory to Python path
backend_dir = Path(__file__).parent
sys.path.insert(0, str(backend_dir))

if __name__ == "__main__":
    from app.main import app, CONFIG
    import uvicorn

    # Resolve SSL certificate paths relative to project root
    project_root = backend_dir.parent
    ssl_cert = project_root / "cert.pem"
    ssl_key = project_root / "key.pem"

    kwargs = {"host": "0.0.0.0", "port": CONFIG["web_port"], "reload": False, "log_level": "info"}

    if ssl_cert.exists() and ssl_key.exists():
        kwargs["ssl_certfile"] = str(ssl_cert)
        kwargs["ssl_keyfile"] = str(ssl_key)
        print(f"🔒 SSL Enabled (Cert: {ssl_cert.name})")
    else:
        print(f"⚠️  SSL Certificates not found in {project_root}. Running in HTTP mode.")

    uvicorn.run("app.main:app", **kwargs)
