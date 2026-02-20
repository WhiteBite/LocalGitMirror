import subprocess
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

from rich.console import Console

from app.core.logger import get_logger
from app.core.settings_manager import SettingsManager

console = Console()


class RepoManager:
    def __init__(self, storage_path: Path):
        self.storage_path = storage_path
        self.storage_path.mkdir(parents=True, exist_ok=True)
        self._current_repo = "default"
        # We need access to settings to get user preference
        # SettingsManager typically reads from storage_path/.sisyphus/settings.json
        self.settings_manager = SettingsManager(storage_path)

    @property
    def current_repo(self) -> str:
        return self._current_repo

    @current_repo.setter
    def current_repo(self, name: str):
        self._current_repo = name

    def _get_bare_path(self, repo_name: str) -> Path:
        """The bare repository where git push/pull happens"""
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

    def _configure_user_from_last_commit(self, workspace: Path):
        """
        Configure user.name and user.email based on settings OR detection.
        Priority:
        1. Global Settings (if set) -> Force apply
        2. Existing Repo Config -> Respect it
        3. Auto-detect from last commit -> Apply
        """
        try:
            # 0. Load global settings
            settings = self.settings_manager.get_all()
            global_name = settings.get("git", {}).get("user_name")
            global_email = settings.get("git", {}).get("user_email")

            if global_name and global_email:
                # Force apply global settings if they exist
                subprocess.run(["git", "config", "user.name", global_name], cwd=str(workspace))
                subprocess.run(["git", "config", "user.email", global_email], cwd=str(workspace))
                console.print(f"[cyan][i] Enforced Git Identity: {global_name} <{global_email}>[/cyan]")
                return

            # 1. Check if user.name is already configured locally
            existing_name = subprocess.run(
                ["git", "config", "user.name"], cwd=str(workspace), capture_output=True, text=True
            ).stdout.strip()

            if existing_name:
                return  # Respect existing config if no global override

            # 2. Auto-detect from last commit
            proc_name = subprocess.run(
                ["git", "log", "-1", "--format=%an"], cwd=str(workspace), capture_output=True, text=True
            )
            author_name = proc_name.stdout.strip()

            proc_email = subprocess.run(
                ["git", "log", "-1", "--format=%ae"], cwd=str(workspace), capture_output=True, text=True
            )
            author_email = proc_email.stdout.strip()

            if author_name and author_email:
                subprocess.run(["git", "config", "user.name", author_name], cwd=str(workspace))
                subprocess.run(["git", "config", "user.email", author_email], cwd=str(workspace))
                console.print(f"[cyan][i] Auto-detected Git Identity: {author_name} <{author_email}>[/cyan]")

        except Exception as e:
            console.print(f"[red][!] Failed to configure git user: {e}[/red]")

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

        is_non_bare = (workspace / ".git").exists()

        if not bare_path.exists() and not is_non_bare:
            result["message"] = f"Repository data for '{repo_name}' not found"
            return result

        try:
            workspace.mkdir(parents=True, exist_ok=True)

            if is_non_bare:
                branch_proc = subprocess.run(
                    ["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=str(workspace), capture_output=True, text=True
                )
                current_branch = branch_proc.stdout.strip()

                subprocess.run(["git", "reset", "--hard", "HEAD"], cwd=str(workspace), check=True)

                # APPLY IDENTITY CONFIG
                self._configure_user_from_last_commit(workspace)

                result["success"] = True
                result["message"] = f"Workspace reset to HEAD ({current_branch})"
                logger.info("Non-bare sync successful", {"repo": repo_name})
                return result

            if not (workspace / ".git").exists():
                subprocess.run(["git", "init"], cwd=str(workspace), check=True)
                subprocess.run(
                    ["git", "remote", "add", "origin", str(bare_path.absolute())],
                    cwd=str(workspace),
                    check=True,
                )

            subprocess.run(
                ["git", "fetch", "origin"],
                cwd=str(workspace),
                capture_output=True,
                text=True,
            )

            branch_proc = subprocess.run(
                ["git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD"],
                cwd=str(workspace),
                capture_output=True,
                text=True,
            )
            target_branch = branch_proc.stdout.strip().replace("origin/", "") or branch

            if not target_branch or target_branch == "refs/remotes/origin/HEAD":
                target_branch = branch

            sync_res = subprocess.run(
                ["git", "reset", "--hard", f"origin/{target_branch}"],
                cwd=str(workspace),
                capture_output=True,
                text=True,
            )

            if sync_res.returncode != 0:
                alt_branch = "master" if target_branch == "main" else "main"
                subprocess.run(
                    ["git", "reset", "--hard", f"origin/{alt_branch}"],
                    cwd=str(workspace),
                    check=False,
                )

            # APPLY IDENTITY CONFIG
            self._configure_user_from_last_commit(workspace)

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
            bare_path.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "init", "--bare"], cwd=str(bare_path), check=True)

            workspace.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "init"], cwd=str(workspace), check=True)
            subprocess.run(
                ["git", "remote", "add", "origin", str(bare_path.absolute())],
                cwd=str(workspace),
                check=True,
            )

            (workspace / "README.md").write_text(f"# {repo_name}\nInitial commit via LocalGitMirror")

            subprocess.run(["git", "add", "."], cwd=str(workspace), check=True)

            # Use settings if available immediately for creation
            settings = self.settings_manager.get_all()
            name = settings.get("git", {}).get("user_name", "LocalGitMirror")
            email = settings.get("git", {}).get("user_email", "mirror@local")

            subprocess.run(["git", "config", "user.email", email], cwd=str(workspace), check=True)
            subprocess.run(["git", "config", "user.name", name], cwd=str(workspace), check=True)

            subprocess.run(["git", "commit", "-m", "Initial commit"], cwd=str(workspace), check=True)
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

    def get_latest_pushed_branch(self, repo_name: str) -> Optional[str]:
        """Detect which branch was just updated in the bare repo"""
        bare_path = self._get_bare_path(repo_name)
        if not bare_path.exists():
            return None

        try:
            proc = subprocess.run(
                [
                    "git",
                    "for-each-ref",
                    "--sort=-committerdate",
                    "--format=%(refname:short)",
                    "--count=1",
                    "refs/heads/",
                ],
                cwd=str(bare_path),
                capture_output=True,
                text=True,
            )
            branch = proc.stdout.strip()
            return branch if branch else None
        except Exception:
            return None

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
