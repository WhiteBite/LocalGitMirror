"""Obfuscated repo identifier (rid) resolution.

Shared by deps.py and file_sync.py so the document-cache alias routes can
accept a rid instead of a plain repo name. A rid is the first 16 hex chars
of SHA-256("lgm-repo-id:" + repoName). The server resolves a rid by
enumerating known repo names from storage and hashing each; a value that
does not match any known rid is passed through unchanged so the caller's
normal plain-name validation and storage lookup apply (yielding empty/404
for unknown repos, matching the old routes' semantics).
"""
import hashlib
from typing import Optional

_RID_PREFIX = "lgm-repo-id:"
_RID_LENGTH = 16


def repo_to_rid(repo_name: str) -> str:
    return hashlib.sha256((_RID_PREFIX + repo_name).encode("utf-8")).hexdigest()[:_RID_LENGTH]


def resolve_repo_identifier(value: Optional[str], repo_manager) -> str:
    value = (value or "").strip()
    if not value:
        return ""
    if repo_manager is not None:
        try:
            for name in repo_manager.get_repos():
                if repo_to_rid(name) == value:
                    return name
        except Exception:
            pass
    return value
