"""HTTP -> HTTPS redirect app.

This is intended to be run on port 80 to redirect all requests
to the main HTTPS server (typically 443).
"""

from __future__ import annotations

import os
from pathlib import Path
from urllib.parse import urlencode

from dotenv import load_dotenv
from fastapi import FastAPI, Request
from fastapi.responses import RedirectResponse


def _load_env() -> None:
    # Ensure we load the root .env even when started from /backend
    project_root = Path(__file__).resolve().parents[2]
    env_path = project_root / ".env"
    if env_path.exists():
        load_dotenv(dotenv_path=env_path)
    else:
        load_dotenv()


_load_env()


def _target_https_port() -> int:
    # Allow explicit override for the redirect process
    raw = os.getenv("REDIRECT_TO_PORT") or os.getenv("WEB_PORT") or "443"
    try:
        return int(raw)
    except ValueError:
        return 443


app = FastAPI(title="Redirect Service", version="1.0.0")


@app.api_route("/{path:path}", methods=["GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"])
async def redirect_all(request: Request, path: str):
    """Redirect any HTTP request to HTTPS on the configured port.

    Notes:
    - This is a best-effort redirect for browsers and tools.
    - We use 307 to preserve method and body where possible.
    """

    host = request.headers.get("host", "localhost")
    # Strip any :port suffix from the Host header
    hostname = host.rsplit(":", 1)[0] if ":" in host else host

    port = _target_https_port()
    port_part = "" if port == 443 else f":{port}"

    query = request.query_params
    query_string = f"?{urlencode(query, doseq=True)}" if query else ""
    location = f"https://{hostname}{port_part}/{path}{query_string}"

    return RedirectResponse(url=location, status_code=307)
