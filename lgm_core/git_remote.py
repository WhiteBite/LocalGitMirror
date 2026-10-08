"""Origin-remote parsing shared by the multi-project MR ops.

One implementation for the scan registry (ops_projects) and the per-project
GitLab derivation in mr_send — the CLI counterpart of the plugin's
GitLabConfig.parseRemoteUrl auto-detection.
"""
from __future__ import annotations

import subprocess
from pathlib import Path

from .config import cfg


def origin_remote_url(proj: Path) -> str:
    proc = subprocess.run(
        ["git", "-C", str(proj), "remote", "get-url", "origin"],
        capture_output=True, timeout=30,
    )
    return proc.stdout.decode(errors="replace").strip() if proc.returncode == 0 else ""


def parse_remote(url: str) -> tuple[str, str]:
    """(host, project_path) from https://host/group/proj.git or git@host:group/proj.git."""
    if not url:
        return "", ""
    if url.startswith("git@"):
        host, _, path = url[4:].partition(":")
    else:
        body = url.split("://", 1)[-1]
        host, _, path = body.partition("/")
    path = path.strip().strip("/")
    if path.endswith(".git"):
        path = path[:-4]
    return host.lower(), path


def gitlab_project_for(proj: Path) -> str:
    """GitLab project path from the origin remote when its host matches GITLAB_URL.

    Empty when GITLAB_URL is unset, the project has no origin remote, or the
    remote lives on another host — callers then fall back to GITLAB_PROJECT.
    """
    gitlab_url = cfg("GITLAB_URL").rstrip("/")
    if not gitlab_url:
        return ""
    host = gitlab_url.split("://", 1)[-1].partition("/")[0].lower()
    remote_host, project = parse_remote(origin_remote_url(proj))
    if project and remote_host == host:
        return project
    return ""
