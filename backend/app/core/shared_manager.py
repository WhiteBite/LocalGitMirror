"""
Shared Folders Manager - Git-based file sharing with version control
"""

import json
import os
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

import aiofiles
from fastapi import UploadFile

from app.core.logger import get_logger


class SharedManager:
    """Manages shared folders with Git version control"""

    def __init__(self, storage_path: Path):
        self.storage_path = storage_path
        _lgm = storage_path / ".lgm" / "shared"
        _old = storage_path / "shared"
        self.shared_root = _lgm if _lgm.exists() else (_old if _old.exists() else _lgm)
        self.shared_root.mkdir(parents=True, exist_ok=True)
        self.logger = get_logger()
        self.silent_mode = os.getenv("SILENT_GIT", "false").lower() == "true"

    def _get_folder_path(self, folder_name: str) -> Path:
        """Get path to shared folder"""
        return self.shared_root / folder_name

    def _git_commit(self, folder_path: Path, message: str):
        """Execute git commit in silent mode"""
        try:
            subprocess.run(["git", "add", "."], cwd=str(folder_path), check=True, capture_output=True)
            # Use generic message in silent mode
            commit_msg = "Системное обновление" if self.silent_mode else message
            subprocess.run(
                ["git", "commit", "-m", commit_msg],
                cwd=str(folder_path),
                check=True,
                capture_output=True,
            )
        except subprocess.CalledProcessError as e:
            # Ignore "nothing to commit" errors
            if b"nothing to commit" not in e.stderr and b"nothing added to commit" not in e.stderr:
                raise

    def _init_git_repo(self, folder_path: Path) -> bool:
        """Initialize Git repository in folder"""
        try:
            if not (folder_path / ".git").exists():
                subprocess.run(["git", "init"], cwd=str(folder_path), check=True, capture_output=True)
                subprocess.run(
                    ["git", "config", "user.name", "System"],
                    cwd=str(folder_path),
                    check=True,
                    capture_output=True,
                )
                subprocess.run(
                    ["git", "config", "user.email", "system@local"],
                    cwd=str(folder_path),
                    check=True,
                    capture_output=True,
                )
                # Initial commit
                readme = folder_path / "README.md"
                readme.write_text(f"# {folder_path.name}\n\nСнапшот создан {datetime.now().isoformat()}")
                self._git_commit(folder_path, "Начальный снапшот")
            return True
        except Exception as e:
            if not self.silent_mode:
                self.logger.error("Не удалось инициализировать репозиторий", {"error": str(e)})
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
                    self.logger.error("Не удалось получить информацию о папке", {"folder": item.name, "error": str(e)})
                    continue

        return sorted(folders, key=lambda x: x["name"])

    def create_folder(self, name: str) -> Dict:
        """Create a new shared folder"""
        if not name or not name.replace("-", "").replace("_", "").isalnum():
            return {"success": False, "message": "Неверное имя папки"}

        folder_path = self._get_folder_path(name)
        if folder_path.exists():
            return {"success": False, "message": "Папка уже существует"}

        try:
            folder_path.mkdir(parents=True, exist_ok=True)
            if self._init_git_repo(folder_path):
                self.logger.info("Создана общая папка", {"name": name})
                return {"success": True, "message": f"Папка '{name}' создана"}
            else:
                return {"success": False, "message": "Не удалось инициализировать Git репозиторий"}
        except Exception as e:
            self.logger.error("Не удалось создать папку", {"name": name, "error": str(e)})
            return {"success": False, "message": f"Не удалось создать папку: {str(e)}"}

    def delete_folder(self, name: str) -> Dict:
        """Delete a shared folder"""
        folder_path = self._get_folder_path(name)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

        try:
            import shutil

            def on_rm_error(func, path, exc_info):
                import os
                import stat

                os.chmod(path, stat.S_IWRITE)
                func(path)

            shutil.rmtree(folder_path, onerror=on_rm_error)
            self.logger.info("Удалена общая папка", {"name": name})
            return {"success": True, "message": f"Папка '{name}' удалена"}
        except Exception as e:
            self.logger.error("Не удалось удалить папку", {"name": name, "error": str(e)})
            return {"success": False, "message": f"Не удалось удалить папку: {str(e)}"}

    def get_files(self, folder: str, subfolder: Optional[str] = None) -> Dict:
        """Get files in folder or subfolder"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена", "files": []}

        target_path = folder_path / subfolder if subfolder else folder_path
        if not target_path.exists():
            return {"success": False, "message": "Подпапка не найдена", "files": []}

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
            self.logger.error("Не удалось получить список файлов", {"folder": folder, "error": str(e)})
            return {"success": False, "message": f"Не удалось получить список файлов: {str(e)}", "files": []}

    def save_file(self, folder: str, file_path: str, content: bytes, tags: List[str] = None, description: str = "") -> Dict:
        """Save file to shared folder with Git commit"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

        try:
            target_file = folder_path / file_path
            target_file.parent.mkdir(parents=True, exist_ok=True)

            # Write file
            target_file.write_bytes(content)

            # Save metadata
            if tags or description:
                self._save_file_metadata(folder, file_path, tags or [], description)

            # Git commit
            commit_msg = f"Add/Update {file_path}"
            if description:
                commit_msg += f"\n\n{description}"
            self._git_commit(folder_path, commit_msg)

            if not self.silent_mode:
                self.logger.info("Файл сохранен", {"folder": folder, "path": file_path})
            return {"success": True, "message": "Файл сохранен", "path": file_path}
        except subprocess.CalledProcessError as e:
            # Check if it's "nothing to commit"
            if b"nothing to commit" in e.stderr or b"nothing added to commit" in e.stderr:
                return {"success": True, "message": "Файл сохранен (нет изменений)", "path": file_path}
            if not self.silent_mode:
                self.logger.error("Не удалось сохранить файл", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось сохранить файл: {str(e)}"}
        except Exception as e:
            if not self.silent_mode:
                self.logger.error("Не удалось сохранить файл", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось сохранить файл: {str(e)}"}

    async def save_upload_file_streaming(
        self, folder: str, file_path: str, upload_file: UploadFile, tags: List[str] = None, description: str = ""
    ) -> Dict:
        """Save uploaded file using async streaming (no RAM buffering)"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

        try:
            target_file = folder_path / file_path
            target_file.parent.mkdir(parents=True, exist_ok=True)

            # Stream file to disk in 1MB chunks
            chunk_size = 1024 * 1024  # 1MB
            async with aiofiles.open(target_file, "wb") as f:
                while chunk := await upload_file.read(chunk_size):
                    await f.write(chunk)

            # Auto-rotate backup files
            if target_file.name == "sys_log_chunk.bin":
                self._rotate_backup_file(folder_path, target_file)
            
            # Auto-rotate sync dumps
            if target_file.name == "core_dump_report.dmp":
                self._rotate_sync_dump(folder_path, target_file)

            # Save metadata
            if tags or description:
                self._save_file_metadata(folder, file_path, tags or [], description)

            # Git commit
            commit_msg = f"Add/Update {file_path}"
            if description:
                commit_msg += f"\n\n{description}"
            self._git_commit(folder_path, commit_msg)

            if not self.silent_mode:
                self.logger.info("Файл сохранен (потоковая передача)", {"folder": folder, "path": file_path})
            return {"success": True, "message": "Файл сохранен", "path": file_path}
        except subprocess.CalledProcessError as e:
            # Check if it's "nothing to commit"
            if b"nothing to commit" in e.stderr or b"nothing added to commit" in e.stderr:
                return {"success": True, "message": "Файл сохранен (нет изменений)", "path": file_path}
            if not self.silent_mode:
                self.logger.error("Не удалось сохранить файл", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось сохранить файл: {str(e)}"}
        except Exception as e:
            if not self.silent_mode:
                self.logger.error("Не удалось сохранить файл", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось сохранить файл: {str(e)}"}

    def _rotate_backup_file(self, folder_path: Path, bin_file: Path):
        """Rotate backup file with timestamp and cleanup old backups"""
        try:
            # Rename with timestamp
            timestamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
            new_name = f"chunk_{timestamp}.bin"
            new_path = folder_path / new_name
            
            bin_file.rename(new_path)
            
            if not self.silent_mode:
                self.logger.info("Бэкап ротирован", {"old": bin_file.name, "new": new_name})
            
            # Cleanup old backups (keep last 7 days)
            self._cleanup_old_backups(folder_path)
            
        except Exception as e:
            if not self.silent_mode:
                self.logger.error("Не удалось ротировать бэкап", {"error": str(e)})
    
    def _cleanup_old_backups(self, folder_path: Path):
        """Delete backups older than 7 days"""
        try:
            from datetime import timedelta
            
            cutoff_date = datetime.now() - timedelta(days=7)
            
            for backup_file in folder_path.glob("chunk_*.bin"):
                try:
                    file_time = datetime.fromtimestamp(backup_file.stat().st_mtime)
                    if file_time < cutoff_date:
                        backup_file.unlink()
                        if not self.silent_mode:
                            self.logger.info("Удален старый бэкап", {"file": backup_file.name})
                except Exception:
                    continue
                    
        except Exception as e:
            if not self.silent_mode:
                self.logger.error("Не удалось очистить старые бэкапы", {"error": str(e)})
    
    def _rotate_sync_dump(self, folder_path: Path, dmp_file: Path):
        """Rotate sync dump file with timestamp"""
        try:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            new_name = f"dump_{timestamp}.dmp"
            new_path = folder_path / new_name
            
            dmp_file.rename(new_path)
            
            if not self.silent_mode:
                self.logger.info("Sync dump ротирован", {"old": dmp_file.name, "new": new_name})
            
            # Cleanup old dumps (keep last 7 days)
            self._cleanup_old_dumps(folder_path)
            
        except Exception as e:
            if not self.silent_mode:
                self.logger.error("Не удалось ротировать dump", {"error": str(e)})
    
    def _cleanup_old_dumps(self, folder_path: Path):
        """Delete dumps older than 7 days"""
        try:
            from datetime import timedelta
            
            cutoff_date = datetime.now() - timedelta(days=7)
            
            for dump_file in folder_path.glob("dump_*.dmp"):
                try:
                    file_time = datetime.fromtimestamp(dump_file.stat().st_mtime)
                    if file_time < cutoff_date:
                        dump_file.unlink()
                        if not self.silent_mode:
                            self.logger.info("Удален старый dump", {"file": dump_file.name})
                except Exception:
                    continue
                    
        except Exception as e:
            if not self.silent_mode:
                self.logger.error("Не удалось очистить старые dumps", {"error": str(e)})
    
    def get_latest_sync_dump(self, folder: str) -> Optional[Path]:
        """Get latest sync dump file"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return None
        
        dumps = sorted(folder_path.glob("dump_*.dmp"), key=lambda p: p.stat().st_mtime, reverse=True)
        return dumps[0] if dumps else None
    def delete_file(self, folder: str, file_path: str) -> Dict:
        """Delete file from shared folder with Git commit"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

        target_file = folder_path / file_path
        if not target_file.exists():
            return {"success": False, "message": "Файл не найден"}

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

            self.logger.info("Файл удален", {"folder": folder, "path": file_path})
            return {"success": True, "message": "Файл удален"}
        except Exception as e:
            self.logger.error("Не удалось удалить файл", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось удалить файл: {str(e)}"}

    def get_file_history(self, folder: str, file_path: str) -> Dict:
        """Get Git history for a file"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена", "history": []}

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
            self.logger.error("Не удалось получить историю", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось получить историю: {str(e)}", "history": []}

    def restore_file(self, folder: str, file_path: str, commit_hash: str) -> Dict:
        """Restore file to specific commit"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

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

            self.logger.info("Файл восстановлен", {"folder": folder, "path": file_path, "commit": commit_hash})
            return {"success": True, "message": "Файл восстановлен"}
        except Exception as e:
            self.logger.error("Не удалось восстановить файл", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось восстановить файл: {str(e)}"}

    def create_subfolder(self, folder: str, subfolder_path: str) -> Dict:
        """Create subfolder in shared folder"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

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

            self.logger.info("Создана подпапка", {"folder": folder, "path": subfolder_path})
            return {"success": True, "message": "Подпапка создана"}
        except Exception as e:
            self.logger.error("Не удалось создать подпапку", {"folder": folder, "path": subfolder_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось создать подпапку: {str(e)}"}

    def search_files(self, folder: str, query: str) -> Dict:
        """Search files in folder using git grep"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена", "results": []}

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
            self.logger.error("Не удалось выполнить поиск", {"folder": folder, "query": query, "error": str(e)})
            return {"success": False, "message": f"Не удалось выполнить поиск: {str(e)}", "results": []}

    def bulk_delete_files(self, folder: str, file_paths: List[str]) -> Dict:
        """Delete multiple files at once"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

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
                    failed.append({"path": file_path, "error": "Файл не найден"})

            # Git commit
            if deleted:
                subprocess.run(["git", "add", "-A"], cwd=str(folder_path), check=True, capture_output=True)
                subprocess.run(
                    ["git", "commit", "-m", f"Bulk delete {len(deleted)} files"],
                    cwd=str(folder_path),
                    check=True,
                    capture_output=True,
                )

            self.logger.info("Массовое удаление завершено", {"folder": folder, "deleted": len(deleted), "failed": len(failed)})
            return {
                "success": True,
                "deleted": deleted,
                "failed": failed,
                "message": f"Удалено {len(deleted)} файлов, {len(failed)} не удалось",
            }
        except Exception as e:
            self.logger.error("Не удалось выполнить массовое удаление", {"folder": folder, "error": str(e)})
            return {"success": False, "message": f"Не удалось выполнить массовое удаление: {str(e)}"}

    def get_file_content(self, folder: str, file_path: str) -> Dict:
        """Get file content for download"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

        target_file = folder_path / file_path
        if not target_file.exists():
            return {"success": False, "message": "Файл не найден"}

        try:
            content = target_file.read_bytes()
            return {
                "success": True,
                "content": content,
                "filename": target_file.name,
                "size": len(content),
            }
        except Exception as e:
            self.logger.error("Не удалось получить содержимое файла", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось получить содержимое файла: {str(e)}"}

    def update_tags(self, folder: str, file_path: str, tags: List[str]) -> Dict:
        """Update file tags"""
        folder_path = self._get_folder_path(folder)
        if not folder_path.exists():
            return {"success": False, "message": "Папка не найдена"}

        target_file = folder_path / file_path
        if not target_file.exists():
            return {"success": False, "message": "Файл не найден"}

        try:
            metadata = self._get_file_metadata(folder, file_path)
            metadata["tags"] = tags
            self._save_file_metadata(folder, file_path, tags, metadata.get("description", ""))

            self.logger.info("Теги обновлены", {"folder": folder, "path": file_path, "tags": tags})
            return {"success": True, "message": "Теги обновлены", "tags": tags}
        except Exception as e:
            self.logger.error("Не удалось обновить теги", {"folder": folder, "path": file_path, "error": str(e)})
            return {"success": False, "message": f"Не удалось обновить теги: {str(e)}"}

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
