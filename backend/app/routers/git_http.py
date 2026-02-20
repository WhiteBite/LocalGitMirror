"""
Git-over-HTTP Router and WSGI Integration
"""

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

        variants = [repo_name, repo_name.rstrip(".git"), repo_name + ".git"]

        repo_full_path = None
        for variant in variants:
            potential_path = Path(self.root) / variant
            if potential_path.exists():
                repo_full_path = potential_path
                break

        if not repo_full_path:
            # Case-insensitive scan
            try:
                for entry in os.scandir(self.root):
                    if entry.is_dir() and entry.name.lower() == repo_name.lower().replace(".git", ""):
                        repo_full_path = Path(entry.path)
                        break
            except Exception:
                pass

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
    app.mount("/git", WSGIMiddleware(wrapped_app))
