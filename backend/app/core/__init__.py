"""Core business logic modules.

IMPORTANT:
This package is imported in multiple contexts:
- as part of the backend app (where top-level module is `app`)
- as source files imported directly by helper scripts/tests.

To keep these scripts working, core should not hard-require `app.*` imports at import time.
"""

from app.core.git_handler import GitHandler
from app.core.git_utils import GitWorkspace
from app.core.logger import get_logger
from app.core.repo_manager import RepoManager
from app.core.settings_manager import SettingsManager
from app.core.shared_manager import SharedManager
from app.core.system_monitor import SystemMonitor

__all__ = [
    "GitHandler",
    "RepoManager",
    "GitWorkspace",
    "SystemMonitor",
    "SettingsManager",
    "SharedManager",
    "get_logger",
]
