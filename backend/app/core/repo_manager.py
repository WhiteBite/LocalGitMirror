import subprocess
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

from rich.console import Console

from app.core.logger import get_logger

console = Console()


class RepoManager:
    def __init__(self, storage_path: Path):
        self.storage_path = storage_path
        self.storage_path.mkdir(parents=True, exist_ok=True)
        self._current_repo = "default"

    @property
    def current_repo(self) -> str:
        return self._current_repo

    @current_repo.setter
    def current_repo(self, name: str):
        self._current_repo = name

    def _get_bare_path(self, repo_name: str) -> Path:
        """The bare repository where git push/pull happens"""
        # For non-bare setup used in this project, bare_path is often ignored
        # or points to .git inside workspace if it's not a separate bare repo.
        return self.storage_path / f"{repo_name}.git"

    def _get_workspace_path(self, repo_name: str) -> Path:
        """The checked out workspace for editing/viewing"""
        return self.storage_path / repo_name

    def get_repos(self) -> List[str]:
        """Get list of all repositories"""
        repos = set()
        if self.storage_path.exists():
            for item in self.storage_path.iterdir():
                if item.is_dir():
                    if item.name.endswith(".git"):
                        repos.add(item.name[:-4])
                    elif (item / ".git").exists():
                        repos.add(item.name)

        if not repos:
            return ["default"]

        return sorted(list(repos))

    def sync_workspace(self, repo_name: Optional[str] = None, branch: str = "main") -> Dict:
        """Sync workspace from bare repo - perform checkout/reset"""
        logger = get_logger()
        repo_name = repo_name or self._current_repo
        bare_path = self._get_bare_path(repo_name)
        workspace = self._get_workspace_path(repo_name)

        result = {
            "success": False,
            "message": "",
            "repo": repo_name,
        }

        logger.info("Sync triggered", {"repo": repo_name})

        # Check if we are dealing with a non-bare repo directly
        is_non_bare = (workspace / ".git").exists()

        if not bare_path.exists() and not is_non_bare:
            result["message"] = f"Repository data for '{repo_name}' not found"
            return result

        try:
            # 1. Ensure workspace directory exists
            workspace.mkdir(parents=True, exist_ok=True)

            if is_non_bare:
                # If we are pushing directly to workspace/.git (non-bare)
                # We just need to reset hard to HEAD to update working tree
                # from the index which was updated by push.

                # Check current branch
                branch_proc = subprocess.run(
                    ["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=str(workspace), capture_output=True, text=True
                )
                current_branch = branch_proc.stdout.strip()

                # Reset hard to update files
                subprocess.run(["git", "reset", "--hard", "HEAD"], cwd=str(workspace), check=True)

                result["success"] = True
                result["message"] = f"Workspace reset to HEAD ({current_branch})"
                logger.info("Non-bare sync successful", {"repo": repo_name})
                return result

            # Fallback for separate bare repo logic (original logic)
            if not (workspace / ".git").exists():
                subprocess.run(["git", "init"], cwd=str(workspace), check=True)
                subprocess.run(
                    ["git", "remote", "add", "origin", str(bare_path.absolute())],
                    cwd=str(workspace),
                    check=True,
                )

            # 3. Fetch from our internal bare repo
            subprocess.run(
                ["git", "fetch", "origin"],
                cwd=str(workspace),
                capture_output=True,
                text=True,
            )

            # 4. Detect default branch if needed
            branch_proc = subprocess.run(
                ["git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD"],
                cwd=str(workspace),
                capture_output=True,
                text=True,
            )
            target_branch = branch_proc.stdout.strip().replace("origin/", "") or branch

            if not target_branch or target_branch == "refs/remotes/origin/HEAD":
                # Fallback to master/main
                target_branch = branch

            # 5. Hard reset to origin's branch
            sync_res = subprocess.run(
                ["git", "reset", "--hard", f"origin/{target_branch}"],
                cwd=str(workspace),
                capture_output=True,
                text=True,
            )

            if sync_res.returncode != 0:
                # Try fallback to 'master' if 'main' failed and vice versa
                alt_branch = "master" if target_branch == "main" else "main"
                subprocess.run(
                    ["git", "reset", "--hard", f"origin/{alt_branch}"],
                    cwd=str(workspace),
                    check=False,
                )

            result["success"] = True
            result["message"] = f"Workspace synced to {repo_name}"
            logger.info("Sync successful", {"repo": repo_name})
        except Exception as e:
            result["message"] = f"Sync error: {str(e)}"
            logger.error("Sync failed", {"repo": repo_name, "error": str(e)})

        return result

    def create_repo(self, repo_name: str) -> Dict:
        """Create a new bare repository and workspace"""
        if not repo_name or not repo_name.replace("-", "").replace("_", "").isalnum():
            return {"success": False, "message": "Invalid repository name"}

        bare_path = self._get_bare_path(repo_name)
        workspace = self._get_workspace_path(repo_name)

        if bare_path.exists() or workspace.exists():
            return {"success": False, "message": "Repository already exists"}

        try:
            # 1. Create bare repo
            bare_path.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "init", "--bare"], cwd=str(bare_path), check=True)

            # 2. Create workspace and initial commit
            workspace.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "init"], cwd=str(workspace), check=True)
            subprocess.run(
                ["git", "remote", "add", "origin", str(bare_path.absolute())],
                cwd=str(workspace),
                check=True,
            )

            (workspace / "README.md").write_text(f"# {repo_name}\nInitial commit via LocalGitMirror")

            subprocess.run(["git", "add", "."], cwd=str(workspace), check=True)
            subprocess.run(
                ["git", "config", "user.email", "mirror@local"],
                cwd=str(workspace),
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "LocalGitMirror"],
                cwd=str(workspace),
                check=True,
            )
            subprocess.run(
                ["git", "commit", "-m", "Initial commit"],
                cwd=str(workspace),
                check=True,
            )

            # 3. Push to bare
            subprocess.run(["git", "push", "origin", "HEAD"], cwd=str(workspace), check=True)

            return {"success": True, "message": f"Repository '{repo_name}' created"}
        except Exception as e:
            return {"success": False, "message": f"Failed to create repo: {str(e)}"}

    def delete_repo(self, repo_name: str) -> Dict:
        """Delete a repository (both bare and workspace)"""
        if repo_name == "default":
            return {"success": False, "message": "Cannot delete default repository"}

        bare_path = self._get_bare_path(repo_name)
        workspace = self._get_workspace_path(repo_name)

        try:
            import shutil

            def on_rm_error(func, path, exc_info):
                import os
                import stat

                os.chmod(path, stat.S_IWRITE)
                func(path)

            if bare_path.exists():
                shutil.rmtree(bare_path, onerror=on_rm_error)
            if workspace.exists():
                shutil.rmtree(workspace, onerror=on_rm_error)

            return {"success": True, "message": f"Repository '{repo_name}' deleted"}
        except Exception as e:
            return {"success": False, "message": f"Failed to delete repo: {str(e)}"}

    def get_file_tree(self, repo_name: Optional[str] = None) -> List[Dict]:
        """Get workspace file tree"""
        repo_name = repo_name or self._current_repo
        workspace = self._get_workspace_path(repo_name)
        tree = []

        if not workspace.exists():
            return tree

        ignore_folders = {
            ".git",
            "node_modules",
            "__pycache__",
            "venv",
            "dist",
            "build",
        }
        import os

        for root, dirs, files in os.walk(workspace):
            dirs[:] = [d for d in dirs if d not in ignore_folders and not d.startswith(".")]

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
                            "modified": datetime.fromtimestamp(stat.st_mtime).isoformat(),
                            "full_path": str(full_path.absolute()),
                        }
                    )
                except (OSError, PermissionError):
                    continue

        return tree

    def get_absolute_path(self, relative_path: str, repo_name: Optional[str] = None) -> str:
        repo_name = repo_name or self._current_repo
        workspace = self._get_workspace_path(repo_name)
        return str((workspace / relative_path).absolute())

    def get_recent_commits(self, repo_name: Optional[str] = None, limit: int = 10) -> List[Dict]:
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
        except Exception:
            pass

        return commits
