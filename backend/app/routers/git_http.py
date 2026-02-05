"""
Git-over-HTTP Router and WSGI Integration
"""

from pathlib import Path
import threading

from dulwich.repo import Repo
from dulwich.server import FileSystemBackend
from dulwich.web import make_wsgi_chain
from starlette.middleware.wsgi import WSGIMiddleware


class WindowsSafeBackend(FileSystemBackend):
    """Stable backend that ensures Smart HTTP works on Windows with Cyrillic paths"""

    def open_repository(self, path):
        if isinstance(path, bytes):
            path = path.decode("utf-8")

        # Extract repo name correctly from WSGI path
        repo_name = path.strip("/").split("/")[0]
        repo_full_path = Path(self.root) / repo_name

        if not repo_full_path.exists():
            repo_full_path.mkdir(parents=True, exist_ok=True)
            # Init as non-bare
            repo = Repo.init(str(repo_full_path))
            config = repo.get_config()
            config.set((b"receive",), b"denyCurrentBranch", b"updateInstead")
            config.write_to_path()
            return repo

        return Repo(str(repo_full_path))


class WSGIFixMiddleware:
    """
    Middleware that patches the WSGI environment to provide a 'write' callable
    returned by start_response, which Dulwich requires but Starlette's
    WSGIMiddleware does not provide.

    Also intercepts POST git-receive-pack to trigger syncing.
    """

    def __init__(self, app, on_receive_callback=None):
        self.app = app
        self.on_receive_callback = on_receive_callback

    def __call__(self, environ, start_response):
        output_buffer = []
        path_info = environ.get("PATH_INFO", "")
        method = environ.get("REQUEST_METHOD", "")

        # Check if this is a push (git-receive-pack)
        is_push = method == "POST" and path_info.endswith("/git-receive-pack")
        repo_name = None

        if is_push:
            # Extract repo name: /repo.git/git-receive-pack -> repo.git
            parts = path_info.strip("/").split("/")
            if len(parts) >= 1:
                repo_name = parts[0]
                if repo_name.endswith(".git"):
                    repo_name = repo_name[:-4]

        def custom_start_response(status, headers, exc_info=None):
            start_response(status, headers, exc_info)

            # If request was successful (200 OK), trigger sync callback
            if is_push and status.startswith("200") and self.on_receive_callback and repo_name:
                # Run in thread to not block response
                threading.Thread(target=self.on_receive_callback, args=(repo_name,), daemon=True).start()

            return output_buffer.append

        app_iter = self.app(environ, custom_start_response)

        # Yield any buffered data first, then yield from app_iter
        try:
            for data in app_iter:
                while output_buffer:
                    yield output_buffer.pop(0)
                yield data
        finally:
            if hasattr(app_iter, "close"):
                app_iter.close()

        # Flush any remaining buffered data
        while output_buffer:
            yield output_buffer.pop(0)


def init_git_http(app, storage_path: Path):
    """Mount Git WSGI app directly into FastAPI"""
    backend = WindowsSafeBackend(str(storage_path.absolute()))
    git_wsgi_app = make_wsgi_chain(backend)

    # Import callback from main to break circular dependency cleanly
    # or better, main calls us with the callback.
    # But main calls init_git_http.

    # We need to access 'on_repo_receive' from app.main but we can't import main here.
    # Solution: We will pass the callback if we refactor main, OR we import inside function.

    on_receive = None
    try:
        from app.main import on_repo_receive

        on_receive = on_repo_receive
    except ImportError:
        pass

    # Wrap with our fix middleware before passing to Starlette
    wrapped_app = WSGIFixMiddleware(git_wsgi_app, on_receive_callback=on_receive)

    # Use WSGIMiddleware to wrap Dulwich and mount at /git
    app.mount("/git", WSGIMiddleware(wrapped_app))
