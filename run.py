#!/usr/bin/env python3
"""
LocalGitMirror — single launcher for both production and development.

Usage:
    python run.py            # production: one server, serves the built frontend
    python run.py prod       # same as above (explicit)
    python run.py dev        # development: backend (--reload) + Vite, one console

What it does (so you never juggle multiple windows again):
  * Self-bootstraps backend/venv on first run (creates it + installs deps) and
    re-executes itself inside that venv — no manual "activate" needed.
  * Ensures a self-signed TLS certificate exists (cert.pem / key.pem at repo root).
  * Optional HTTP->HTTPS redirect runs IN-PROCESS as a daemon thread, only when
    REDIRECT_HTTP_PORT is set in .env — no second window.
  * `dev` streams backend + frontend output into ONE console with [api]/[web]
    prefixes; a single Ctrl+C stops both (whole process trees — nothing else).

All ports come from .env (WEB_PORT, GIT_PORT, REDIRECT_HTTP_PORT).
"""
from __future__ import annotations

import os
import re
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BACKEND = ROOT / "backend"
FRONTEND = ROOT / "frontend"
IDEA_PLUGIN = ROOT / "idea-plugin"
VENV = BACKEND / "venv"
VENV_PY = VENV / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
IS_WIN = os.name == "nt"

# uvicorn log config: timestamps, no ANSI colors (clean when piped/redirected).
_LOG_CONFIG = {
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
        "access": {"class": "logging.StreamHandler", "formatter": "access"},
    },
    "loggers": {
        "uvicorn": {"handlers": ["default"], "level": "INFO", "propagate": False},
        "uvicorn.error": {"handlers": ["default"], "level": "INFO", "propagate": False},
        "uvicorn.access": {"handlers": ["access"], "level": "INFO", "propagate": False},
    },
}


# ── venv bootstrap ───────────────────────────────────────────────────────────
def _in_target_venv() -> bool:
    try:
        return VENV_PY.exists() and Path(sys.executable).resolve() == VENV_PY.resolve()
    except Exception:
        return False


def _bootstrap_and_reexec(argv: list[str]) -> None:
    """Ensure backend/venv exists with deps, then re-run this script inside it."""
    if _in_target_venv():
        return  # already running in the right interpreter

    if not VENV_PY.exists():
        print("[setup] creating virtualenv (backend/venv)...")
        subprocess.run([sys.executable, "-m", "venv", str(VENV)], check=True)
        print("[setup] installing backend dependencies (first run only)...")
        subprocess.run([str(VENV_PY), "-m", "pip", "install", "--upgrade", "pip", "--quiet"], check=False)
        subprocess.run([str(VENV_PY), "-m", "pip", "install", "-r", str(BACKEND / "requirements.txt")], check=True)
        root_reqs = BACKEND / "requirements-root.txt"
        if root_reqs.exists():
            subprocess.run([str(VENV_PY), "-m", "pip", "install", "-r", str(root_reqs)], check=False)

    try:
        rc = subprocess.call([str(VENV_PY), str(ROOT / "run.py"), *argv])
    except KeyboardInterrupt:
        rc = 0
    sys.exit(rc)


# ── shared helpers ───────────────────────────────────────────────────────────
def _load_env() -> None:
    from dotenv import load_dotenv

    load_dotenv(ROOT / ".env")


def _web_port() -> int:
    try:
        return int(os.getenv("WEB_PORT", "443"))
    except ValueError:
        return 443


def _ensure_certs() -> bool:
    cert, key = ROOT / "cert.pem", ROOT / "key.pem"
    if cert.exists() and key.exists():
        return True
    try:
        if str(ROOT) not in sys.path:
            sys.path.insert(0, str(ROOT))
        from generate_cert import generate_self_signed_cert

        generate_self_signed_cert()
        return cert.exists() and key.exists()
    except Exception as exc:
        print(f"[warn] could not generate TLS certificate: {exc}")
        return False


# ── IDEA plugin: build-if-stale ──────────────────────────────────────────────
# Plugin version = 0.<git commit count>.0 (see idea-plugin/build.gradle.kts).
# The backend (app/routers/plugin.py) serves the newest .zip in
# idea-plugin/build/distributions/ to the IDE plugin's self-update check. If
# that .zip is missing or older than the current commit count, we rebuild it
# here so the server always offers an up-to-date plugin without a manual step.
_PLUGIN_ZIP_RE = re.compile(r"localgitmirror-idea-plugin-(\d+)\.(\d+)\.(\d+)\.zip$")


def _git_commit_count() -> int | None:
    try:
        out = subprocess.run(
            ["git", "rev-list", "--count", "HEAD"],
            cwd=str(ROOT), capture_output=True, text=True, check=True,
        ).stdout.strip()
        return int(out) if out.isdigit() else None
    except Exception:
        return None


def _newest_plugin_zip_version() -> int | None:
    """Highest <minor> from localgitmirror-idea-plugin-0.<minor>.0.zip, or None."""
    dist = IDEA_PLUGIN / "build" / "distributions"
    if not dist.exists():
        return None
    best = None
    for p in dist.iterdir():
        if not p.is_file():
            continue
        m = _PLUGIN_ZIP_RE.match(p.name)
        if m:
            minor = int(m.group(2))
            if best is None or minor > best:
                best = minor
    return best


def _gradle_cmd() -> list[str] | None:
    import shutil

    wrapper = IDEA_PLUGIN / ("gradlew.bat" if IS_WIN else "gradlew")
    if wrapper.exists():
        return [str(wrapper)]
    # On Windows, shutil.which("gradle") can resolve to the extensionless POSIX
    # shell script instead of gradle.bat (subprocess can't exec it — WinError 193).
    # Look for gradle.bat explicitly first.
    if IS_WIN:
        found_bat = shutil.which("gradle.bat")
        if found_bat:
            return [found_bat]
    found = shutil.which("gradle")
    if found:
        return [found]
    return None


def _ensure_plugin_built() -> None:
    """Rebuild the IDEA plugin if it's missing or behind the current commit count."""
    if not IDEA_PLUGIN.exists():
        return

    current = _git_commit_count()
    newest_built = _newest_plugin_zip_version()

    if newest_built is not None and (current is None or newest_built >= current):
        return  # up to date (or we can't tell — don't force a rebuild)

    reason = "no plugin build found" if newest_built is None else f"built v0.{newest_built}.0 < current v0.{current}.0"
    print(f"[info] IDEA plugin is stale ({reason}); rebuilding...")

    gradle = _gradle_cmd()
    if gradle is None:
        print("[warn] gradle not found on PATH and no gradlew wrapper present; skipping plugin build.")
        print("[warn] Install Gradle or add a wrapper, then run: gradle buildPlugin (in idea-plugin/)")
        return

    try:
        result = subprocess.run(
            [*gradle, "buildPlugin", "--no-daemon"],
            cwd=str(IDEA_PLUGIN), capture_output=True, text=True, timeout=900,
        )
        if result.returncode != 0:
            print("[warn] IDEA plugin build failed:")
            tail = "\n".join(result.stdout.splitlines()[-30:])
            print(tail)
            return
        built = _newest_plugin_zip_version()
        print(f"[info] IDEA plugin built: v0.{built}.0" if built is not None else "[info] IDEA plugin build finished.")
    except subprocess.TimeoutExpired:
        print("[warn] IDEA plugin build timed out after 15 minutes; skipping.")
    except Exception as exc:
        print(f"[warn] IDEA plugin build failed to start: {exc}")


# ── production ───────────────────────────────────────────────────────────────
def _build_redirect_app(https_port: int):
    """Tiny HTTP->HTTPS redirect app (replaces the old backend/app/redirect.py)."""
    from urllib.parse import urlencode

    from fastapi import FastAPI, Request
    from fastapi.responses import RedirectResponse

    app = FastAPI(title="Redirect", version="1.0.0")

    @app.api_route("/{path:path}", methods=["GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"])
    async def _redirect(request: Request, path: str):
        host = request.headers.get("host", "localhost")
        hostname = host.rsplit(":", 1)[0] if ":" in host else host
        port_part = "" if https_port == 443 else f":{https_port}"
        qs = request.query_params
        query = f"?{urlencode(qs, doseq=True)}" if qs else ""
        return RedirectResponse(url=f"https://{hostname}{port_part}/{path}{query}", status_code=307)

    return app


def _start_redirect_thread(http_port: int, https_port: int) -> None:
    import uvicorn

    config = uvicorn.Config(_build_redirect_app(https_port), host="0.0.0.0", port=http_port, log_level="warning")
    server = uvicorn.Server(config)
    server.install_signal_handlers = lambda: None  # we are not on the main thread

    def _run():
        try:
            server.run()
        except Exception as exc:
            print(f"[warn] HTTP redirect on :{http_port} stopped: {exc}")

    threading.Thread(target=_run, daemon=True, name="http-redirect").start()
    print(f"[info] HTTP->HTTPS redirect on :{http_port} -> :{https_port}")


def run_prod() -> None:
    os.chdir(ROOT)  # cert.pem / storage / frontend dist resolve relative to repo root
    _load_env()
    _ensure_plugin_built()
    if str(BACKEND) not in sys.path:
        sys.path.insert(0, str(BACKEND))

    import uvicorn
    from app.main import CONFIG, app

    web_port = CONFIG["web_port"]
    cert, key = ROOT / "cert.pem", ROOT / "key.pem"
    have_ssl = _ensure_certs()

    redirect_raw = (os.getenv("REDIRECT_HTTP_PORT") or "").strip()
    if redirect_raw and redirect_raw != "0":
        try:
            _start_redirect_thread(int(redirect_raw), web_port)
        except Exception as exc:
            print(f"[warn] redirect not started: {exc}")

    kwargs = dict(
        host="0.0.0.0",
        port=web_port,
        log_level="info",
        # Cap graceful shutdown: a single Ctrl+C must not hang waiting for the
        # long-lived log WebSocket to close. uvicorn's own (Windows-aware) signal
        # handling then exits within ~2s; press Ctrl+C twice for instant.
        timeout_graceful_shutdown=2,
        # Keep-alive must NOT be 0: uvicorn would close the TCP connection right
        # after the response and race slow client reads during large pulls.
        timeout_keep_alive=30,
        log_config=_LOG_CONFIG,
    )
    if have_ssl and cert.exists() and key.exists():
        kwargs["ssl_certfile"] = str(cert)
        kwargs["ssl_keyfile"] = str(key)

    scheme = "https" if "ssl_certfile" in kwargs else "http"
    print(f"[info] LocalGitMirror (production) -> {scheme}://localhost:{web_port}")
    print("[info] Press Ctrl+C to stop.")
    try:
        uvicorn.run(app, **kwargs)
    except KeyboardInterrupt:
        pass


# ── development ──────────────────────────────────────────────────────────────
def _npm() -> str:
    import shutil

    found = shutil.which("npm")
    if found:
        return found
    return "npm.cmd" if IS_WIN else "npm"


def _spawn(cmd: list[str], cwd: Path) -> subprocess.Popen:
    kwargs: dict = dict(
        cwd=str(cwd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=1,
        universal_newlines=True,
    )
    if IS_WIN:
        # New process group so the console Ctrl+C doesn't hit children directly —
        # we tear them down explicitly and cleanly below.
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen(cmd, **kwargs)


def _pump(proc: subprocess.Popen, prefix: str, lock: threading.Lock) -> None:
    for line in iter(proc.stdout.readline, ""):
        with lock:
            sys.stdout.write(f"{prefix} {line.rstrip()}\n")
            sys.stdout.flush()


def _terminate(proc: subprocess.Popen | None) -> None:
    if proc is None or proc.poll() is not None:
        return
    try:
        if IS_WIN:
            # Targeted kill of THIS pid tree only (never a global taskkill /IM).
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
        else:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def run_dev() -> None:
    os.chdir(ROOT)
    _load_env()
    _ensure_plugin_built()
    web_port = _web_port()
    _ensure_certs()
    cert, key = ROOT / "cert.pem", ROOT / "key.pem"

    if web_port != 443:
        print(f"[warn] WEB_PORT={web_port}, but the Vite dev proxy targets :443.")
        print("[warn] Update frontend/vite.config.js proxy if you keep this port.")

    backend_cmd = [
        sys.executable, "-m", "uvicorn", "app.main:app", "--reload",
        "--app-dir", str(BACKEND), "--host", "0.0.0.0", "--port", str(web_port),
    ]
    if cert.exists() and key.exists():
        backend_cmd += ["--ssl-certfile", str(cert), "--ssl-keyfile", str(key)]

    npm = _npm()
    if not (FRONTEND / "node_modules").exists():
        print("[setup] installing frontend dependencies (npm install)...")
        subprocess.run([npm, "install"], cwd=str(FRONTEND), check=False)

    print("[info] LocalGitMirror (development)")
    print(f"[info]   backend  -> https://localhost:{web_port}   (uvicorn --reload)")
    print("[info]   frontend -> http://localhost:5173    (vite, use THIS url)")
    print("[info] Press Ctrl+C to stop both.\n")

    lock = threading.Lock()
    api = _spawn(backend_cmd, ROOT)            # cwd=ROOT so cert.pem/storage resolve correctly
    web = _spawn([npm, "run", "dev"], FRONTEND)
    procs = [(api, "[api]"), (web, "[web]")]

    for proc, prefix in procs:
        threading.Thread(target=_pump, args=(proc, prefix, lock), daemon=True).start()

    try:
        while True:
            for proc, prefix in procs:
                if proc.poll() is not None:
                    with lock:
                        print(f"\n[info] {prefix} exited (code {proc.returncode}); stopping the other...")
                    raise KeyboardInterrupt
            time.sleep(0.3)
    except KeyboardInterrupt:
        pass
    finally:
        with lock:
            print("\n[info] shutting down dev servers...")
        for proc, _ in procs:
            _terminate(proc)


# ── entry point ──────────────────────────────────────────────────────────────
def main() -> None:
    argv = sys.argv[1:]
    mode = (argv[0].lower() if argv else "prod")
    if mode in ("-h", "--help", "help"):
        print(__doc__)
        return
    if mode not in ("prod", "dev"):
        print(f"[error] unknown mode '{mode}'. Use: python run.py [prod|dev]")
        sys.exit(2)

    _bootstrap_and_reexec(argv)  # returns only once we're inside backend/venv

    if mode == "dev":
        run_dev()
    else:
        run_prod()


if __name__ == "__main__":
    main()
