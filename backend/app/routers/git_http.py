"""
Git-over-HTTP Router and WSGI Integration
"""

import base64
import os
from pathlib import Path
import threading

from dulwich.repo import Repo
from dulwich.server import FileSystemBackend
from dulwich.web import make_wsgi_chain
from starlette.middleware.wsgi import WSGIMiddleware


class WindowsSafeBackend(FileSystemBackend):
    """Stable backend that ensures Smart HTTP works on Windows with Cyrillic paths"""

    def open_repository(self, path):
        # path comes from Dulwich (e.g. "/onyx" or "/onyx.git")
        if isinstance(path, bytes):
            path = path.decode("utf-8", errors="replace")

        clean_path = path.strip("/")
        repo_name = clean_path.split("/")[0]
        name = repo_name[:-4] if repo_name.endswith(".git") else repo_name
        root = Path(self.root)

        # New layout (.lgm/bare) first, then old flat bare, then old workspace.
        candidates = [
            root / ".lgm" / "bare" / f"{name}.git",
            root / f"{name}.git",
            root / name,
        ]
        repo_full_path = None
        for candidate in candidates:
            if candidate.exists():
                repo_full_path = candidate
                break

        if not repo_full_path:
            # Case-insensitive scan in the new bare dir and the storage root.
            for search_dir in (root / ".lgm" / "bare", root):
                if not search_dir.exists():
                    continue
                try:
                    for entry in os.scandir(str(search_dir)):
                        if entry.is_dir() and entry.name.lower().replace(".git", "") == name.lower():
                            repo_full_path = Path(entry.path)
                            break
                except Exception:
                    pass
                if repo_full_path:
                    break

        if not repo_full_path:
            raise KeyError(f"Repository {repo_name} not found")

        try:
            repo = Repo(str(repo_full_path))
            config = repo.get_config()
            try:
                if config.get((b"receive",), b"denyCurrentBranch") != b"updateInstead":
                    config.set((b"receive",), b"denyCurrentBranch", b"updateInstead")
                    config.write_to_path()
            except Exception:
                pass
            return repo
        except Exception as e:
            raise KeyError(f"Failed to open repo {repo_name}: {e}")


class WSGIFixMiddleware:
    def __init__(self, app, on_receive_callback=None):
        self.app = app
        self.on_receive_callback = on_receive_callback

    def __call__(self, environ, start_response):
        output_buffer = []
        path_info = environ.get("PATH_INFO", "")
        method = environ.get("REQUEST_METHOD", "")

        is_push = method == "POST" and path_info.endswith("/git-receive-pack")
        repo_name = None

        if is_push:
            parts = path_info.strip("/").split("/")
            if len(parts) >= 1:
                repo_name = parts[0].replace(".git", "")

        def custom_start_response(status, headers, exc_info=None):
            start_response(status, headers, exc_info)
            if is_push and status.startswith("200") and self.on_receive_callback and repo_name:
                threading.Thread(target=self.on_receive_callback, args=(repo_name,), daemon=True).start()
            return output_buffer.append

        app_iter = self.app(environ, custom_start_response)
        try:
            for data in app_iter:
                while output_buffer:
                    yield output_buffer.pop(0)
                yield data
        finally:
            if hasattr(app_iter, "close"):
                app_iter.close()
        while output_buffer:
            yield output_buffer.pop(0)


class BasicAuthMiddleware:
    """HTTP Basic Auth wrapper for Git WSGI app.

    Only active when GIT_USER and GIT_PASS env vars are set.
    If not configured, passes all requests through (backward-compatible).
    """

    def __init__(self, app):
        self.app = app
        self._user = os.getenv("GIT_USER", "")
        self._pass = os.getenv("GIT_PASS", "")

    @property
    def enabled(self) -> bool:
        return bool(self._user and self._pass)

    def _check_auth(self, environ) -> bool:
        if not self.enabled:
            return True

        auth_header = environ.get("HTTP_AUTHORIZATION", "")
        if not auth_header.startswith("Basic "):
            return False

        try:
            decoded = base64.b64decode(auth_header[6:]).decode("utf-8")
            user, password = decoded.split(":", 1)
            return user == self._user and password == self._pass
        except Exception:
            return False

    def __call__(self, environ, start_response):
        if self._check_auth(environ):
            return self.app(environ, start_response)

        # Return 401 Unauthorized
        start_response(
            "401 Unauthorized",
            [("WWW-Authenticate", 'Basic realm="Git Access"'), ("Content-Type", "text/plain")],
        )
        return [b"Authentication required"]


def init_git_http(app, storage_path: Path):
    """Mount Git WSGI app into FastAPI"""
    root_dir = str(storage_path.absolute()).replace("\\", "/")
    backend = WindowsSafeBackend(root_dir)
    git_wsgi_app = make_wsgi_chain(backend)

    on_receive = None
    try:
        from app.main import on_repo_receive

        on_receive = on_repo_receive
    except ImportError:
        pass

    wrapped_app = WSGIFixMiddleware(git_wsgi_app, on_receive_callback=on_receive)
    authed_app = BasicAuthMiddleware(wrapped_app)
    app.mount("/git", WSGIMiddleware(authed_app))
