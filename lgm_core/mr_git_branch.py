"""Local-git helpers for MR branch transfer: classification, tips, negotiation."""
from __future__ import annotations

import subprocess
from pathlib import Path

from .client import LgmError, MirrorClient


def _classify_branch(proj: Path, branch: str) -> dict:
    """Compare a local branch to origin by commit counts (local-only, origin-only)."""
    proc = subprocess.run(
        ["git", "-C", str(proj), "rev-list", "--left-right", "--count",
         f"refs/heads/{branch}...refs/remotes/origin/{branch}"],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        raise LgmError("git", proc.stderr.strip() or f"cannot classify branch '{branch}'")
    counts = proc.stdout.split()
    if len(counts) != 2:
        raise LgmError("git", f"unexpected rev-list output for branch '{branch}'")
    local_only, origin_only = int(counts[0]), int(counts[1])
    if local_only == 0 and origin_only == 0:
        kind = "synced"
    elif local_only == 0:
        kind = "behind"
    elif origin_only == 0:
        kind = "ahead"
    else:
        kind = "diverged"
    return {"kind": kind, "ahead": local_only, "behind": origin_only}


def _branch_block_warning(entry: dict) -> str:
    branch = entry["branch"]
    if entry["kind"] == "ahead":
        return (f"local branch '{branch}' is ahead of origin by {entry['ahead']} "
                f"commit(s) that origin does not have — the MR transfer ships the "
                f"origin tip only; push those commits to origin (or confirm the "
                f"intended tip) before transferring")
    return (f"local '{branch}' and origin/{branch} have diverged "
            f"({entry['ahead']} local-only, {entry['behind']} origin-only "
            f"commit(s)) — reconcile them by hand; a blind pull or rebase can "
            f"lose the local commits")


def _local_tip(proj: Path, branch: str, missing_ok: bool = False) -> str:
    proc = subprocess.run(
        ["git", "-C", str(proj), "rev-parse", f"refs/heads/{branch}"],
        capture_output=True, text=True, timeout=30,
    )
    if proc.returncode != 0:
        if missing_ok:
            return ""
        raise LgmError("git", f"cannot resolve refs/heads/{branch}")
    return proc.stdout.strip()


def _mirror_refs_safe(c: MirrorClient, repo: str) -> dict:
    """Mirror branch tips; {} when the repo is not on the mirror yet."""
    try:
        return c.sync_refs(repo).get("refs") or {}
    except LgmError as e:
        if e.code != 404:
            raise
        return {}


def _existing_shas(proj: Path, shas: list[str]) -> list[str]:
    """Keep only SHAs present locally (a ^sha exclusion of an unknown commit
    would make git bundle fail)."""
    deduped = [s for s in dict.fromkeys(shas) if s]
    if not deduped:
        return []
    proc = subprocess.run(
        ["git", "-C", str(proj), "cat-file", "--batch-check"],
        input="\n".join(deduped) + "\n",
        capture_output=True, text=True, timeout=30,
    )
    lines = (proc.stdout or "").splitlines()
    out = []
    for i, sha in enumerate(deduped):
        if i >= len(lines):
            break
        parts = lines[i].split(" ", 2)
        if len(parts) >= 2 and parts[1] == "commit":
            out.append(sha)
    return out


def _dirty_tracked_paths(proj: Path) -> list[str]:
    """Tracked files with uncommitted changes; untracked files are user data
    and never block a send."""
    proc = subprocess.run(
        ["git", "-C", str(proj), "status", "--porcelain", "-uno"],
        capture_output=True, text=True, timeout=60,
    )
    if proc.returncode != 0:
        return []
    paths: list[str] = []
    for line in proc.stdout.splitlines():
        entry = line[3:].strip() if len(line) > 3 else ""
        if " -> " in entry:
            entry = entry.split(" -> ", 1)[1]
        if entry.startswith('"') and entry.endswith('"'):
            entry = entry[1:-1]
        if entry:
            paths.append(entry)
    return paths


def _negotiate_mr_bases(c: MirrorClient, proj: Path, repo: str,
                        branches: list[str]) -> tuple[list[str], bool]:
    history = subprocess.run(
        ["git", "-C", str(proj), "rev-list", "--max-count=300", "--date-order", *branches, "--"],
        capture_output=True, text=True, timeout=60,
    )
    if history.returncode:
        raise LgmError("git", history.stderr.strip())
    candidates = history.stdout.splitlines()
    result = c.sync_negotiate(repo, candidates)
    offered = set(candidates)
    return ([sha for sha in result.get("known", []) if sha in offered],
            bool(result.get("created")))
