import json
from pathlib import Path
from typing import Any, Dict


class SettingsManager:
    def __init__(self, storage_path: Path):
        _lgm = storage_path / ".lgm" / "settings.json"
        _old = storage_path / "settings.json"
        self.settings_path = _lgm if _lgm.exists() else (_old if _old.exists() else _lgm)
        self.defaults = {
            "general": {
                "default_repo": "default",
                "default_folder": "",
                "storage_path": str(storage_path),  # Add storage_path to defaults
                "auto_sync": True,
                "refresh_interval": 5,
            },
            "git": {
                "port": 8081,
                "auto_start": True,
                "auto_commit": False,
                "user_name": "",
                "user_email": "",
            },
            "ui": {"theme": "dark", "font_size": 14, "show_system_log": True},
        }
        self.settings = self.load_settings()

    def load_settings(self) -> Dict[str, Any]:
        if self.settings_path.exists():
            try:
                with open(self.settings_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    # Merge deep to ensure all keys exist
                    merged = self.defaults.copy()
                    for key, section in data.items():
                        if key in merged and isinstance(section, dict):
                            merged[key].update(section)
                        else:
                            merged[key] = section
                    return merged
            except Exception:
                return self.defaults.copy()
        return self.defaults.copy()

    def save_settings(self, settings: Dict[str, Any]) -> bool:
        try:
            self.settings_path.parent.mkdir(parents=True, exist_ok=True)
            with open(self.settings_path, "w", encoding="utf-8") as f:
                json.dump(settings, f, indent=4)
            self.settings = settings
            return True
        except Exception:
            return False

    def get_all(self) -> Dict[str, Any]:
        return self.settings
