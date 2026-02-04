import threading
import socketserver
from pathlib import Path
from typing import Callable, Optional, Dict
from dulwich.repo import Repo
from dulwich.server import TCPGitServer
from rich.console import Console
from app.core.logger import get_logger

console = Console()


class ThreadingTCPGitServer(socketserver.ThreadingMixIn, TCPGitServer):
    daemon_threads = True
    allow_reuse_address = True


class MultiRepoBackend:
    """Backend that supports multiple repositories dynamically"""

    def __init__(self, storage_path: Path, on_receive: Optional[Callable] = None):
        self.storage_path = storage_path
        self.on_receive = on_receive
        self.repos: Dict[bytes, Repo] = {}
        # self._load_existing_repos() # Don't pre-load, lazy load to avoid locks

    def _load_existing_repos(self):
        """Load all existing repos from storage (non-bare)"""
        if not self.storage_path.exists():
            self.storage_path.mkdir(parents=True, exist_ok=True)
            return

        for item in self.storage_path.iterdir():
            if item.is_dir():
                # Check if it's a git repo (bare or non-bare)
                git_dir = item if item.name.endswith(".git") else item / ".git"
                if git_dir.exists():
                    repo_name = (
                        item.name[:-4] if item.name.endswith(".git") else item.name
                    )
                    try:
                        repo = Repo(str(item))
                        self._configure_repo(repo)
                        self.repos[f"/{repo_name}".encode()] = repo
                        # console.print(f"[cyan]Loaded repo: {repo_name}[/cyan]")
                    except Exception:
                        # console.print(
                        #    f"[yellow]Failed to load {repo_name}: {e}[/yellow]"
                        # )
                        pass

    def _configure_repo(self, repo: Repo):
        """Configure repo for stealth sync"""
        config = repo.get_config()
        config.set((b"receive",), b"denyCurrentBranch", b"updateInstead")
        config.write_to_path()

    def open_repository(self, path: bytes):
        """Open or create repository by path"""
        # path is like b'/test-project'
        path_str = path.decode().strip("/")
        if not path_str:
            path_str = "default"

        repo_key = f"/{path_str}".encode()

        if repo_key in self.repos:
            return self.repos[repo_key]

        # Check if exists on disk
        repo_path = self.storage_path / path_str
        if (repo_path / ".git").exists() or repo_path.name.endswith(".git"):
            repo = Repo(str(repo_path))
            self._configure_repo(repo)
            self.repos[repo_key] = repo
            return repo

        # Create new
        repo_path.mkdir(parents=True, exist_ok=True)
        repo = Repo.init(str(repo_path))
        self._configure_repo(repo)
        self.repos[repo_key] = repo

        # Trigger callback for new repo (after handshake usually, but here it's during open)
        if self.on_receive:
            # Important: call this outside of critical git loop if possible,
            # but here we just pass the name
            self.on_receive(path_str)

        return repo

    def get_repo_names(self) -> list:
        """Get list of all repository names"""
        names = []
        for key in self.repos.keys():
            name = key.decode().strip("/")
            if name:
                names.append(name)

        # Also check filesystem
        if self.storage_path.exists():
            for item in self.storage_path.iterdir():
                if item.is_dir() and item.name.endswith(".git"):
                    name = item.name[:-4]
                    if name not in names:
                        names.append(name)

        return sorted(names) if names else ["default"]


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
        self.backend = MultiRepoBackend(storage_path, on_receive)
        self._init_default_repo()

    def _init_default_repo(self):
        """Initialize default repository"""
        default_path = self.storage_path / "default.git"
        if not default_path.exists():
            default_path.mkdir(parents=True, exist_ok=True)
            Repo.init_bare(str(default_path))
            self.backend.repos[b"/default"] = Repo(str(default_path))
            console.print("[green]Initialized default repo[/green]")

    def start(self) -> bool:
        """Start Git TCP server in background thread"""
        logger = get_logger()

        if self._running:
            return False

        try:
            # Bind to 0.0.0.0 for external access
            # Use ThreadingTCPGitServer to prevent hangs
            self._server = ThreadingTCPGitServer(self.backend, "0.0.0.0", self.port)

            def run_server():
                self._running = True
                console.print(
                    f"[green]Git server started on 0.0.0.0:{self.port}[/green]"
                )
                console.print(
                    "[yellow]WARNING: git:// protocol is unencrypted and provides no authentication![/yellow]"
                )
                logger.info(
                    "Git server started", {"port": self.port, "host": "0.0.0.0"}
                )
                try:
                    if self._server:
                        self._server.serve_forever()
                except Exception as e:
                    if self._running:
                        logger.error("Git server error", {"error": str(e)})
                finally:
                    self._running = False

            self._thread = threading.Thread(target=run_server, daemon=True)
            self._thread.start()
            import time

            time.sleep(0.5)
            return self._running
        except Exception as e:
            logger.error(
                "Git server start failed", {"error": str(e), "port": self.port}
            )
            return False

        try:
            # Listen on 0.0.0.0 to accept connections from other computers
            # Use ThreadingTCPGitServer to prevent hangs
            self._server = ThreadingTCPGitServer(self.backend, "0.0.0.0", self.port)

            def run_server():
                self._running = True
                try:
                    if self._server:
                        self._server.serve_forever()
                except Exception:
                    if self._running:
                        pass
                finally:
                    self._running = False

            self._thread = threading.Thread(target=run_server, daemon=True)
            self._thread.start()
            import time

            time.sleep(0.5)
            return self._running
        except Exception:
            return False

    def stop(self):
        """Stop Git server"""
        logger = get_logger()

        if self._server and self._running:
            self._running = False
            try:
                self._server.shutdown()
            except:
                pass
            console.print("[yellow]Git server stopped[/yellow]")
            logger.info("Git server stopped")

    @property
    def is_running(self) -> bool:
        return self._running

    def get_repo_names(self) -> list:
        """Get list of all repository names"""
        return self.backend.get_repo_names()

    def get_repo(self, name: str = "default") -> Optional[Repo]:
        """Get dulwich Repo object by name"""
        repo_path = self.storage_path / f"{name}.git"
        if repo_path.exists():
            return Repo(str(repo_path))
        return None
