"""
Shared Folders Manager - Git-based file sharing with version control
"""

import json
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

from app.core.logger import get_logger


class SharedManager:
    """Manages shared folders with Git version control"""

    def __init__(self, storage_path: Path):
        self.storage_path = storage_path
        self.shared_root = storage_path / "shared"
        self.shared_root.mkdir(parents=True, exist_ok=True)
        self.logger = get_logger()

    def _get_folder_path(self, folder_name: str) -> Path:
        """Get path to shared folder"""
        return self.shared_root / folder_name

    def _init_git_repo(self, folder_path: Path) -> bool:
        """Initialize Git repository in folder"""
        try:
            if not (folder_path / ".git").exists():
                subprocess.run(["git", "init"], cwd=str(folder_path), check=True, capture_output=True)
                subprocess.run(
                    ["git", "config", "user.name", "SharedManager"],
                    cwd=str(folder_path),
                    check=True,
                    capture_output=True,
                )
                subprocess.run(
                    ["git", "config", "user.email", "shared@local"],
                    cwd=str(folder_path),
                    check=True,
                    capture_output=True,
                )
                # Initial commit
                readme = folder_path / "README.md"
                readme.write_text(f"# {folder_path.name}\n\nShared folder created at {datetime.now().isoformat()}")
                subprocess.run(["git", "add", "."], cwd=str(folder_path), check=True, capture_output=True)
                subprocess.run(
                    ["git", "commit", "-m", "Initial commit"],
                    cwd=str(folder_path),
                    check=True,
                    capture_output=True,
                )
            return True
        except Exception as e:
            self.logger.error("Failed to init git repo", {"error": str(e)})
            return False

    def get_folders(self) -> List[Dict]:
        """Get list of shared folders with sizes"""
        folders = []
        if not self.shared_root.exists():
            return folders

        for item in self.shared_root.iterdir():
            if item.is_dir() and not item.name.startswith("."):
                try:
                    size = sum(f.stat().st_size for f in item.rglob("*") if f.is_file())
                    file_count = sum(1 for f in item.rglob("*") if f.is_file() and f.name != ".git")

                    folders.append(
                        {
                            "name": item.name,
                            "size": size,
                            "file_count": file_count,
                            "created": datetime.fromtimestamp(item.stat().st_ctime).isoformat(),
                            "modified": datetime.fromtimestamp(item.stat().st_mtime).isoformat(),
                        }
                    )
                except Exception as e:
                    self.logger.error("Failed to get folder info", {"folder": item.name, "error": str(e)})
                    continue

        return sorted(folders, key=lambda x: x["name"])

    def create_folder(self, name: str) -> Dict:
        """Create a new shared folder"""
        if not name or not name.replace("-", "").replace("_", "").isalnum():
            return {"success": False, "message": "Invalid folder name"}

        folder_path = self._get_folder_path(name)
        if folder_path.exists():
            return {"success": False, "message": "Folder already exists"}

        try:
            folder_path.mkdir(parents=True, exist_ok=True)
            if self._init_git_repo(folder_path):
                self.logger.info("Created shared folder", {"name": name})
                return {"success": True, "message": f"Folder '{name}' created"}
            else:
                return {"success": False, "message": "Failed to initialize Git repository"}
        except Exception as e:
            self.logger.error("Failed to create folder", {"name": name, "error": str(e)})
            return {"success": False, "message": f"Failed to create folder: {str(e)}"}

    def delete_folder(self, name: str) -> Dict:
        """Delete a shared folder"""
        folder_path = self._get_folder_path(name)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found"}

        try:
            import shutil

            def on_rm_error(func, path, exc_info):
                import os
                import stat

                os.chmod(path, stat.S_IWRITE)
                func(path)

            shutil.rmtree(folder_path, onerror=on_rm_error)
            self.logger.info("Deleted shared folder", {"name": name})
            return {"success": True, "message": f"Folder '{name}' deleted"}
        except Exception as e:
            self.logger.error("Failed to delete folder", {"name": name, "error": str(e)})
            return {"success": False, "message": f"Failed to delete folder: {str(e)}"}

    def get_files(self, folder: str, subfolder: Optional[str] = None) -> Dict:
        """Get files in folder or subfolder"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found", "files": []}

        target_path = folder_path / subfolder if subfolder else folder_path
        if not target_path.exists():
            return {"success": False, "message": "Subfolder not found", "files": []}

        files = []
        try:
            for item in target_path.iterdir():
                if item.name.startswith("."):
                    continue

                rel_path = item.relative_to(folder_path)
                if item.is_file():
                    # Get metadata from .metadata.json if exists
                    metadata = self._get_file_metadata(folder, str(rel_path))

                    files.append(
                        {
                            "name": item.name,
                            "path": str(rel_path).replace("\\", "/"),
                            "size": item.stat().st_size,
                            "modified": datetime.fromtimestamp(item.stat().st_mtime).isoformat(),
                            "is_dir": False,
                            "tags": metadata.get("tags", []),
                            "description": metadata.get("description", ""),
                        }
                    )
                elif item.is_dir():
                    files.append(
                        {
                            "name": item.name,
                            "path": str(rel_path).replace("\\", "/"),
                            "size": 0,
                            "modified": datetime.fromtimestamp(item.stat().st_mtime).isoformat(),
                            "is_dir": True,
                            "tags": [],
                            "description": "",
                        }
                    )

            return {"success": True, "files": sorted(files, key=lambda x: (not x["is_dir"], x["name"]))}
        except Exception as e:
            self.logger.error("Failed to get files", {"folder": folder, "error": str(e)})
            return {"success": False, "message": f"Failed to get files: {str(e)}", "files": []}

    def save_file(self, folder: str, file_path: str, content: bytes, tags: List[str] = None, description: str = "") -> Dict:
        """Save file to shared folder with Git commit"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found"}

        try:
            target_file = folder_path / file_path
            target_file.parent.mkdir(parents=True, exist_ok=True)

            # Write file
            target_file.write_bytes(content)

            # Save metadata
            if tags or description:
                self._save_file_metadata(folder, file_path, tags or [], description)

            # Git commit
            subprocess.run(["git", "add", "."], cwd=str(folder_path), check=True, capture_output=True)
            commit_msg = f"Add/Update {file_path}"
            if description:
                commit_msg += f"\n\n{description}"
            subprocess.run(
                ["git", "commit", "-m", commit_msg],
                cwd=str(folder_path),
                check=True,
                capture_output=True,
            )

            self.logger.info("File saved", {"folder": folder, "path": file_path})
            return {"success": True, "message": "File saved", "path": file_path}
        except subprocess.CalledProcessError as e:
            # Check if it's "nothing to commit"
            if b"nothing to commit" in e.stderr or b"nothing added to commit" in e.stderr:
                return {"success": True, "message": "File saved (no changes)", "path": file_path}
            self.logger.error("Failed to save file", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Failed to save file: {str(e)}"}
        except Exception as e:
            self.logger.error("Failed to save file", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Failed to save file: {str(e)}"}

    def delete_file(self, folder: str, file_path: str) -> Dict:
        """Delete file from shared folder with Git commit"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found"}

        target_file = folder_path / file_path
        if not target_file.exists():
            return {"success": False, "message": "File not found"}

        try:
            target_file.unlink()

            # Delete metadata if exists
            self._delete_file_metadata(folder, file_path)

            # Git commit
            subprocess.run(["git", "add", "-A"], cwd=str(folder_path), check=True, capture_output=True)
            subprocess.run(
                ["git", "commit", "-m", f"Delete {file_path}"],
                cwd=str(folder_path),
                check=True,
                capture_output=True,
            )

            self.logger.info("File deleted", {"folder": folder, "path": file_path})
            return {"success": True, "message": "File deleted"}
        except Exception as e:
            self.logger.error("Failed to delete file", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Failed to delete file: {str(e)}"}

    def get_file_history(self, folder: str, file_path: str) -> Dict:
        """Get Git history for a file"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found", "history": []}

        try:
            result = subprocess.run(
                ["git", "log", "--follow", "--format=%H|%s|%an|%ai", "--", file_path],
                cwd=str(folder_path),
                capture_output=True,
                text=True,
                check=True,
            )

            history = []
            for line in result.stdout.strip().split("\n"):
                if line:
                    parts = line.split("|")
                    if len(parts) >= 4:
                        history.append(
                            {
                                "hash": parts[0],
                                "short_hash": parts[0][:8],
                                "message": parts[1],
                                "author": parts[2],
                                "date": parts[3],
                            }
                        )

            return {"success": True, "history": history}
        except Exception as e:
            self.logger.error("Failed to get history", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Failed to get history: {str(e)}", "history": []}

    def restore_file(self, folder: str, file_path: str, commit_hash: str) -> Dict:
        """Restore file to specific commit"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found"}

        try:
            # Checkout file from specific commit
            subprocess.run(
                ["git", "checkout", commit_hash, "--", file_path],
                cwd=str(folder_path),
                check=True,
                capture_output=True,
            )

            # Commit the restoration
            subprocess.run(["git", "add", file_path], cwd=str(folder_path), check=True, capture_output=True)
            subprocess.run(
                ["git", "commit", "-m", f"Restore {file_path} from {commit_hash[:8]}"],
                cwd=str(folder_path),
                check=True,
                capture_output=True,
            )

            self.logger.info("File restored", {"folder": folder, "path": file_path, "commit": commit_hash})
            return {"success": True, "message": "File restored"}
        except Exception as e:
            self.logger.error("Failed to restore file", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Failed to restore file: {str(e)}"}

    def create_subfolder(self, folder: str, subfolder_path: str) -> Dict:
        """Create subfolder in shared folder"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found"}

        try:
            target_path = folder_path / subfolder_path
            target_path.mkdir(parents=True, exist_ok=True)

            # Create .gitkeep to track empty folder
            (target_path / ".gitkeep").touch()

            # Git commit
            subprocess.run(["git", "add", "."], cwd=str(folder_path), check=True, capture_output=True)
            subprocess.run(
                ["git", "commit", "-m", f"Create subfolder {subfolder_path}"],
                cwd=str(folder_path),
                check=True,
                capture_output=True,
            )

            self.logger.info("Subfolder created", {"folder": folder, "path": subfolder_path})
            return {"success": True, "message": "Subfolder created"}
        except Exception as e:
            self.logger.error("Failed to create subfolder", {"folder": folder, "path": subfolder_path, "error": str(e)})
            return {"success": False, "message": f"Failed to create subfolder: {str(e)}"}

    def search_files(self, folder: str, query: str) -> Dict:
        """Search files in folder using git grep"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found", "results": []}

        try:
            result = subprocess.run(
                ["git", "grep", "-n", "-I", query],
                cwd=str(folder_path),
                capture_output=True,
                text=True,
            )

            matches = []
            if result.returncode == 0:
                for line in result.stdout.splitlines()[:100]:  # Limit to 100 results
                    parts = line.split(":", 2)
                    if len(parts) >= 3:
                        matches.append(
                            {
                                "file": parts[0],
                                "line": int(parts[1]),
                                "content": parts[2].strip(),
                            }
                        )

            return {"success": True, "results": matches, "count": len(matches)}
        except Exception as e:
            self.logger.error("Failed to search", {"folder": folder, "query": query, "error": str(e)})
            return {"success": False, "message": f"Failed to search: {str(e)}", "results": []}

    def bulk_delete_files(self, folder: str, file_paths: List[str]) -> Dict:
        """Delete multiple files at once"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found"}

        deleted = []
        failed = []

        try:
            for file_path in file_paths:
                target_file = folder_path / file_path
                if target_file.exists():
                    try:
                        target_file.unlink()
                        self._delete_file_metadata(folder, file_path)
                        deleted.append(file_path)
                    except Exception as e:
                        failed.append({"path": file_path, "error": str(e)})
                else:
                    failed.append({"path": file_path, "error": "File not found"})

            # Git commit
            if deleted:
                subprocess.run(["git", "add", "-A"], cwd=str(folder_path), check=True, capture_output=True)
                subprocess.run(
                    ["git", "commit", "-m", f"Bulk delete {len(deleted)} files"],
                    cwd=str(folder_path),
                    check=True,
                    capture_output=True,
                )

            self.logger.info("Bulk delete completed", {"folder": folder, "deleted": len(deleted), "failed": len(failed)})
            return {
                "success": True,
                "deleted": deleted,
                "failed": failed,
                "message": f"Deleted {len(deleted)} files, {len(failed)} failed",
            }
        except Exception as e:
            self.logger.error("Failed to bulk delete", {"folder": folder, "error": str(e)})
            return {"success": False, "message": f"Failed to bulk delete: {str(e)}"}

    def get_file_content(self, folder: str, file_path: str) -> Dict:
        """Get file content for download"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found"}

        target_file = folder_path / file_path
        if not target_file.exists():
            return {"success": False, "message": "File not found"}

        try:
            content = target_file.read_bytes()
            return {
                "success": True,
                "content": content,
                "filename": target_file.name,
                "size": len(content),
            }
        except Exception as e:
            self.logger.error("Failed to get file content", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Failed to get file content: {str(e)}"}

    def update_tags(self, folder: str, file_path: str, tags: List[str]) -> Dict:
        """Update file tags"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Folder not found"}

        target_file = folder_path / file_path
        if not target_file.exists():
            return {"success": False, "message": "File not found"}

        try:
            metadata = self._get_file_metadata(folder, file_path)
            metadata["tags"] = tags
            self._save_file_metadata(folder, file_path, tags, metadata.get("description", ""))

            self.logger.info("Tags updated", {"folder": folder, "path": file_path, "tags": tags})
            return {"success": True, "message": "Tags updated", "tags": tags}
        except Exception as e:
            self.logger.error("Failed to update tags", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Failed to update tags: {str(e)}"}

    def _get_metadata_path(self, folder: str) -> Path:
        """Get path to metadata file"""
        folder_path = self._get_folder_path(folder)
        return folder_path / ".metadata.json"

    def _get_file_metadata(self, folder: str, file_path: str) -> Dict:
        """Get metadata for a file"""
        metadata_path = self._get_metadata_path(folder)
        if not metadata_path.exists():
            return {}

        try:
            all_metadata = json.loads(metadata_path.read_text())
            return all_metadata.get(file_path, {})
        except Exception:
            return {}

    def _save_file_metadata(self, folder: str, file_path: str, tags: List[str], description: str):
        """Save metadata for a file"""
        metadata_path = self._get_metadata_path(folder)

        all_metadata = {}
        if metadata_path.exists():
            try:
                all_metadata = json.loads(metadata_path.read_text())
            except Exception:
                pass

        all_metadata[file_path] = {
            "tags": tags,
            "description": description,
            "updated": datetime.now().isoformat(),
        }

        metadata_path.write_text(json.dumps(all_metadata, indent=2))

    def _delete_file_metadata(self, folder: str, file_path: str):
        """Delete metadata for a file"""
        metadata_path = self._get_metadata_path(folder)
        if not metadata_path.exists():
            return

        try:
            all_metadata = json.loads(metadata_path.read_text())
            if file_path in all_metadata:
                del all_metadata[file_path]
                metadata_path.write_text(json.dumps(all_metadata, indent=2))
        except Exception:
            pass
