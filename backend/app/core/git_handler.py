"""
Standard Dulwich backend for multiple repositories with callback support
"""

import socketserver
import threading
from pathlib import Path
from typing import Callable, Optional

from dulwich.repo import Repo
from dulwich.server import TCPGitServer, ReceivePackHandler, UploadPackHandler
from rich.console import Console

from app.core.logger import get_logger

console = Console()


class CustomReceivePackHandler(ReceivePackHandler):
    """
    Custom handler to trigger callbacks after push.
    """

    def __init__(self, *args, **kwargs):
        # Extract handlers map if present (passed via server)
        # Dulwich instantiates this with (backend, request, client_address, server)
        # We can access the server instance via args or kwargs depending on signature
        # Standard signature: (backend, request, client_address, server)
        self._server_instance = None
        if len(args) >= 4:
            self._server_instance = args[3]

        super().__init__(*args, **kwargs)

    def handle(self):
        # Run standard handle logic
        super().handle()

        # After handle finishes successfully, trigger callback if repo was updated
        # We can try to extract repo path from self.repo if accessible
        if hasattr(self, "repo"):
            try:
                # self.repo might be a Repo object
                repo_path = Path(self.repo.path)
                # Repo name is the folder name (or folder name - .git)
                repo_name = repo_path.name
                if repo_name.endswith(".git"):
                    repo_name = repo_name[:-4]
                elif (repo_path.parent / ".git").exists():  # Non-bare
                    repo_name = repo_path.name

                # Call the server's callback if it exists
                if (
                    self._server_instance
                    and hasattr(self._server_instance, "on_receive")
                    and self._server_instance.on_receive
                ):
                    self._server_instance.on_receive(repo_name)
            except Exception as e:
                console.print(f"[red]Error in receive callback: {e}[/red]")


class ThreadingTCPGitServer(socketserver.ThreadingMixIn, TCPGitServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, backend, listen_addr, port, on_receive=None):
        super().__init__(backend, listen_addr, port)
        self.on_receive = on_receive
        # Override the handler class to use our custom one
        self.receive_pack_handler_cls = CustomReceivePackHandler

    def handle_error(self, request, client_address):
        """Handle an error gracefully."""
        pass


class MultiRepoBackend:
    """Standard Dulwich backend for multiple repositories"""

    def __init__(self, storage_path: Path):
        self.storage_path = storage_path

    def open_repository(self, path: bytes):
        path_str = path.decode("utf-8", errors="ignore").strip("/")
        if not path_str:
            path_str = "default"

        repo_path = self.storage_path / path_str

        # If it doesn't exist, create it as a simple bare repo (fallback)
        if not repo_path.exists():
            repo_path.mkdir(parents=True, exist_ok=True)
            return Repo.init(str(repo_path))

        return Repo(str(repo_path))


class GitHandler:
    def __init__(
        self,
        storage_path: Path,
        port: int = 8081,
        on_receive: Optional[Callable] = None,
    ):
        self.storage_path = storage_path
        self.port = port
        self.on_receive = on_receive
        self._server: Optional[TCPGitServer] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self.backend = MultiRepoBackend(storage_path)
        self._init_default_repo()

    def _init_default_repo(self):
        default_path = self.storage_path / "default.git"
        if not default_path.exists():
            default_path.mkdir(parents=True, exist_ok=True)
            Repo.init_bare(str(default_path))
            console.print("[green]Initialized default repo[/green]")

    def start(self) -> bool:
        logger = get_logger()
        if self._running:
            return False

        try:
            self._server = ThreadingTCPGitServer(self.backend, "0.0.0.0", self.port, on_receive=self.on_receive)

            def run_server():
                self._running = True
                console.print(f"[green]Git server started on 0.0.0.0:{self.port}[/green]")
                try:
                    if self._server:
                        self._server.serve_forever()
                except Exception as e:
                    if logger:
                        logger.error(f"Git server loop error: {e}")
                finally:
                    self._running = False

            self._thread = threading.Thread(target=run_server, daemon=True)
            self._thread.start()
            return True
        except Exception as e:
            logger.error("Git server start failed", {"error": str(e)})
            return False

    def stop(self):
        if self._server and self._running:
            self._running = False
            try:
                self._server.shutdown()
                self._server.server_close()
            except Exception:
                pass

    @property
    def is_running(self) -> bool:
        return self._running

    def get_repo_names(self) -> list:
        names = []
        if self.storage_path.exists():
            for item in self.storage_path.iterdir():
                if item.is_dir() and item.name.endswith(".git"):
                    names.append(item.name[:-4])
        return sorted(names) if names else ["default"]
