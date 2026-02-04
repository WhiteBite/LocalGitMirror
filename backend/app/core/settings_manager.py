import json
from pathlib import Path
from typing import Dict, Any


class SettingsManager:
    def __init__(self, storage_path: Path):
        self.settings_path = storage_path / "settings.json"
        self.defaults = {
            "general": {
                "default_repo": "default",
                "default_folder": "",
                "storage_path": str(storage_path),  # Add storage_path to defaults
                "auto_sync": True,
                "refresh_interval": 5,
            },
            "git": {"port": 8081, "auto_start": True, "auto_commit": False},
            "ui": {"theme": "dark", "font_size": 14, "show_system_log": True},
        }
        self.settings = self.load_settings()

    def load_settings(self) -> Dict[str, Any]:
        if self.settings_path.exists():
            try:
                with open(self.settings_path, "r", encoding="utf-8") as f:
                    return {**self.defaults, **json.load(f)}
            except Exception:
                return self.defaults
        return self.defaults

    def save_settings(self, settings: Dict[str, Any]) -> bool:
        try:
            with open(self.settings_path, "w", encoding="utf-8") as f:
                json.dump(settings, f, indent=4)
            self.settings = settings
            return True
        except Exception:
            return False

    def get_all(self) -> Dict[str, Any]:
        return self.settings
