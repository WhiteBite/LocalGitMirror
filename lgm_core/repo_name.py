"""Mirror repo key derivation from a git remote URL.

The same repository has the same remote URL on every machine, so the remote is
the only identity that stays consistent between the home PC and the work
laptop (project name / directory name differ per checkout). This mirrors the
IntelliJ plugin's RepoResolver so both surfaces derive the same key.
"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

_NON_SLUG = re.compile(r"[^a-z0-9_-]+")
_DASHES = re.compile(r"-+")


def sanitize_slug(raw: str) -> str:
    trimmed = raw.strip()
    if not trimmed:
        return ""
    lowered = trimmed.lower()
    replaced = _NON_SLUG.sub("-", lowered)
    return _DASHES.sub("-", replaced).strip("-")


def repo_slug_from_remote_url(remote_url: str) -> str:
    s = remote_url.strip()
    if not s:
        return ""
    s = s.rstrip("/")

    if "://" not in s and ":" in s and "@" in s:
        path = s.split(":", 1)[1]
    elif "://" in s:
        after_scheme = s.split("://", 1)[1]
        slash = after_scheme.find("/")
        path = after_scheme[slash + 1:] if slash >= 0 else ""
    else:
        path = s.replace("\\", "/")

    normalized = path.replace("\\", "/").rstrip("/")
    last = normalized.rsplit("/", 1)[-1]
    if last.lower().endswith(".git"):
        last = last[:-4]
    return sanitize_slug(last)


def remote_slug(project_dir: Path) -> str:
    """Slug of the checkout's `origin` remote; empty when there is none."""
    try:
        proc = subprocess.run(
            ["git", "-C", str(project_dir), "remote", "get-url", "origin"],
            capture_output=True, text=True, timeout=30,
        )
    except Exception:
        return ""
    if proc.returncode != 0:
        return ""
    return repo_slug_from_remote_url(proc.stdout)
