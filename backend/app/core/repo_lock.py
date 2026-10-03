"""Per-repo mutation locks for the sync flow."""

import threading
from typing import Dict

_locks: Dict[str, threading.Lock] = {}
_guard = threading.Lock()


def repo_lock(repo_name: str) -> threading.Lock:
    with _guard:
        lock = _locks.get(repo_name)
        if lock is None:
            lock = threading.Lock()
            _locks[repo_name] = lock
        return lock
