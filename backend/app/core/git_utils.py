import subprocess
from pathlib import Path
from datetime import datetime
from typing import Dict, Optional
from rich.console import Console
from app.core.logger import get_logger

console = Console()


class GitWorkspace:
    """Git operations for workspace directory"""

    def __init__(self, workspace_path: Path, bare_repo_path: Path):
        self.workspace = workspace_path
        self.bare_repo = bare_repo_path

    def _run_git(self, *args, cwd: Optional[Path] = None) -> Dict:
        """Run git command and return result"""
        cwd = cwd or self.workspace
        try:
            result = subprocess.run(
                ["git"] + list(args), cwd=str(cwd), capture_output=True, text=True
            )
            return {
                "success": result.returncode == 0,
                "stdout": result.stdout.strip(),
                "stderr": result.stderr.strip(),
            }
        except Exception as e:
            return {"success": False, "stdout": "", "stderr": str(e)}

    def init_workspace_repo(self) -> bool:
        """Initialize workspace as git repo linked to bare repo"""
        if not self.workspace.exists():
            self.workspace.mkdir(parents=True, exist_ok=True)

        git_dir = self.workspace / ".git"
        if not git_dir.exists():
            # Init repo
            self._run_git("init")
            # Add bare repo as remote
            self._run_git("remote", "add", "origin", str(self.bare_repo))
            console.print("[green]Initialized workspace git repo[/green]")
            return True
        return False

    def has_changes(self) -> bool:
        """Check if workspace has uncommitted changes"""
        result = self._run_git("status", "--porcelain")
        return bool(result["stdout"])

    def get_status(self) -> Dict:
        """Get detailed git status"""
        status_result = self._run_git("status", "--porcelain")

        changes = []
        if status_result["stdout"]:
            for line in status_result["stdout"].split("\n"):
                if line:
                    status = line[:2].strip()
                    filename = line[3:]
                    changes.append({"status": status, "file": filename})

        return {
            "has_changes": len(changes) > 0,
            "changes": changes,
            "change_count": len(changes),
        }

    def commit_all(self, message: Optional[str] = None) -> Dict:
        """Stage all changes and commit"""
        if not message:
            timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            message = f"Sync from Home: {timestamp}"

        # Check if there are changes
        if not self.has_changes():
            return {"success": True, "message": "No changes to commit (already clean)"}

        # Git add all
        add_result = self._run_git("add", "-A")
        if not add_result["success"]:
            return {
                "success": False,
                "message": f"git add failed: {add_result['stderr']}",
            }

        # Git commit
        commit_result = self._run_git("commit", "-m", message)
        if not commit_result["success"]:
            if "nothing to commit" in commit_result["stdout"]:
                return {"success": True, "message": "No changes to commit"}
            return {
                "success": False,
                "message": f"git commit failed: {commit_result['stderr']}",
            }

        console.print(f"[green]Committed: {message}[/green]")
        return {"success": True, "message": f"Changes committed: {message}"}

    def push_to_bare(self, branch: str = "main") -> Dict:
        """Push workspace changes to bare repo"""
        # First ensure we have the remote set correctly
        self._run_git("remote", "set-url", "origin", str(self.bare_repo))

        # Try to push
        push_result = self._run_git("push", "-u", "origin", branch, "--force")

        if push_result["success"]:
            console.print(f"[green]Pushed to bare repo: {branch}[/green]")
            return {"success": True, "message": f"Pushed to {branch}"}

        # Try master if main failed
        if "main" in branch:
            push_result = self._run_git("push", "-u", "origin", "master", "--force")
            if push_result["success"]:
                console.print("[green]Pushed to bare repo: master[/green]")
                return {"success": True, "message": "Pushed to master"}

        return {"success": False, "message": f"Push failed: {push_result['stderr']}"}

    def commit_and_push(self, message: Optional[str] = None) -> Dict:
        """Commit all changes and push to bare repo"""
        logger = get_logger()
        logger.info("Commit and push started")

        # Ensure workspace is a git repo
        self.init_workspace_repo()

        # Commit
        commit_result = self.commit_all(message)
        if not commit_result["success"]:
            logger.warning("Commit failed", {"reason": commit_result["message"]})
            return commit_result

        # Push
        push_result = self.push_to_bare()
        if not push_result["success"]:
            logger.error("Push failed after commit", {"error": push_result["message"]})
            return {
                "success": False,
                "message": f"Commit OK, but push failed: {push_result['message']}",
            }

        logger.info("Commit and push completed successfully")
        return {"success": True, "message": "Changes saved and ready for pull!"}

    def pull_from_bare(self, branch: str = "main") -> Dict:
        """Pull latest from bare repo"""
        self.init_workspace_repo()

        # Fetch
        self._run_git("fetch", "origin")

        # Reset to origin
        reset_result = self._run_git("reset", "--hard", f"origin/{branch}")
        if not reset_result["success"]:
            reset_result = self._run_git("reset", "--hard", "origin/master")

        if reset_result["success"]:
            return {"success": True, "message": "Pulled latest changes"}
        return {"success": False, "message": f"Pull failed: {reset_result['stderr']}"}
