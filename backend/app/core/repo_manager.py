import subprocess
from pathlib import Path
from datetime import datetime
from typing import List, Dict, Optional
from rich.console import Console
from app.core.logger import get_logger

console = Console()


class RepoManager:
    def __init__(self, storage_path: Path):
        self.storage_path = storage_path
        # In simple mirror, workspaces ARE the storage path
        self.workspaces_path = storage_path
        self.storage_path.mkdir(parents=True, exist_ok=True)
        self._current_repo = "default"

    @property
    def current_repo(self) -> str:
        return self._current_repo

    @current_repo.setter
    def current_repo(self, name: str):
        self._current_repo = name
        # No need to ensure workspace separately, it's the repo folder

    def _get_bare_path(self, repo_name: str) -> Path:
        # In simple mirror, it's just the folder (non-bare)
        return self.storage_path / repo_name

    def _get_workspace_path(self, repo_name: str) -> Path:
        return self.storage_path / repo_name

    def get_repos(self) -> List[str]:
        """Get list of all repositories (folders in storage)"""
        repos = []
        if self.storage_path.exists():
            for item in self.storage_path.iterdir():
                if item.is_dir():
                    # Every folder is a potential project
                    if (item / ".git").exists() or item.name.endswith(".git"):
                        name = (
                            item.name[:-4] if item.name.endswith(".git") else item.name
                        )
                        repos.append(name)
        return sorted(repos) if repos else ["default"]

    def sync_workspace(
        self, repo_name: Optional[str] = None, branch: str = "main"
    ) -> Dict:
        """Manual sync - perform fetch and hard reset to match remote state"""
        logger = get_logger()
        repo_name = repo_name or self._current_repo
        workspace = self._get_workspace_path(repo_name)

        result = {
            "success": False,
            "message": "",
            "repo": repo_name,
        }

        logger.info("Manual sync triggered", {"repo": repo_name})

        if not workspace.exists() or not (workspace / ".git").exists():
            result["message"] = f"Repository '{repo_name}' not found"
            return result

        try:
            # Detect default branch if not main
            import subprocess

            # Fetch from origin (if exists) or just ensure we are clean
            # In updateInstead mode, we are usually already updated, but this ensures a clean slate

            # 1. Fetch
            subprocess.run(
                ["git", "fetch"], cwd=str(workspace), capture_output=True, text=True
            )

            # 2. Get current branch name
            branch_proc = subprocess.run(
                ["git", "rev-parse", "--abbrev-ref", "HEAD"],
                cwd=str(workspace),
                capture_output=True,
                text=True,
            )
            current_branch = branch_proc.stdout.strip() or "main"

            # 3. Hard reset to match the state pushed to us
            # Since this IS the repo that receives pushes, 'HEAD' is what was pushed.
            # But we might want to ensure we are clean of any local (home) edits that weren't committed.
            subprocess.run(
                ["git", "reset", "--hard", "HEAD"], cwd=str(workspace), check=True
            )

            result["success"] = True
            result["message"] = (
                f"Workspace synced and cleaned (branch: {current_branch})"
            )
            logger.info(
                "Sync successful", {"repo": repo_name, "branch": current_branch}
            )
        except Exception as e:
            result["message"] = f"Sync error: {str(e)}"
            logger.error("Sync failed", {"repo": repo_name, "error": str(e)})

        return result

        try:
            # Simple git reset --hard to match latest push if needed
            # Actually with updateInstead, physical files are already updated.
            # We can just return success.
            result["success"] = True
            result["message"] = "Workspace is up to date (Auto-sync active)"
        except Exception as e:
            result["message"] = f"Sync error: {str(e)}"
            logger.error("Sync failed", {"repo": repo_name, "error": str(e)})

        return result

    def get_file_tree(self, repo_name: Optional[str] = None) -> List[Dict]:
        """Get workspace file tree for UI with optimization for heavy folders"""
        repo_name = repo_name or self._current_repo
        workspace = self._get_workspace_path(repo_name)
        tree = []

        if not workspace.exists():
            return tree

        # List of folders to ignore completely for UI tree
        ignore_folders = {
            ".git",
            "node_modules",
            "__pycache__",
            ".venv",
            "venv",
            "dist",
            "build",
            ".next",
            ".vite",
        }

        # Scan recursively, but skip ignored directories
        # We use os.walk for better control over recursion than rglob
        import os

        for root, dirs, files in os.walk(workspace):
            # Modify dirs in-place to skip ignored folders
            dirs[:] = [
                d for d in dirs if d not in ignore_folders and not d.startswith(".")
            ]

            for file in files:
                full_path = Path(root) / file
                rel_path = full_path.relative_to(workspace)
                try:
                    stat = full_path.stat()
                    tree.append(
                        {
                            "path": str(rel_path).replace("\\", "/"),
                            "name": file,
                            "size": stat.st_size,
                            "modified": datetime.fromtimestamp(
                                stat.st_mtime
                            ).isoformat(),
                            "full_path": str(full_path.absolute()),
                        }
                    )
                except (OSError, PermissionError):
                    continue

        return tree

        # Skip .git folder in tree
        for item in sorted(workspace.rglob("*")):
            if ".git" in item.parts:
                continue
            if item.is_file():
                rel_path = item.relative_to(workspace)
                stat = item.stat()
                tree.append(
                    {
                        "path": str(rel_path).replace("\\", "/"),
                        "name": item.name,
                        "size": stat.st_size,
                        "modified": datetime.fromtimestamp(stat.st_mtime).isoformat(),
                        "full_path": str(item.absolute()),
                    }
                )
        return tree

    def get_absolute_path(
        self, relative_path: str, repo_name: Optional[str] = None
    ) -> str:
        """Get absolute path for a file in workspace"""
        repo_name = repo_name or self._current_repo
        workspace = self._get_workspace_path(repo_name)
        return str((workspace / relative_path).absolute())

    def get_recent_commits(
        self, repo_name: Optional[str] = None, limit: int = 10
    ) -> List[Dict]:
        """Get recent commits from repo"""
        repo_name = repo_name or self._current_repo
        repo_path = self._get_workspace_path(repo_name)
        commits = []

        if not repo_path.exists():
            return commits

        try:
            result = subprocess.run(
                ["git", "log", f"-{limit}", "--format=%H|%s|%an|%ai"],
                cwd=str(repo_path),
                capture_output=True,
                text=True,
            )

            if result.returncode == 0:
                for line in result.stdout.strip().split("\n"):
                    if line:
                        parts = line.split("|")
                        if len(parts) >= 4:
                            commits.append(
                                {
                                    "hash": parts[0][:8],
                                    "message": parts[1],
                                    "author": parts[2],
                                    "date": parts[3],
                                }
                            )
        except Exception as e:
            console.print(f"[yellow]Could not get commits: {e}[/yellow]")

        return commits

    def index_codebase(
        self, repo_name: Optional[str] = None, max_file_size: int = 50000
    ) -> str:
        return ""

    def get_context(self, repo_name: Optional[str] = None) -> str:
        return ""
