from pathlib import Path
import subprocess

from .client import LgmError


def _git(project: Path, *args: str) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["git", *args], cwd=str(project), capture_output=True, text=True, timeout=300,
    )
    if result.returncode != 0:
        raise LgmError("git", result.stderr.strip() or f"git {args[0]} failed")
    return result


def fetch_bundle(project: Path, bundle: Path) -> subprocess.CompletedProcess[str]:
    fetched = _git(project, "fetch", "--atomic", str(bundle),
                   "+refs/heads/*:refs/remotes/lgm/*")
    heads = _git(project, "bundle", "list-heads", str(bundle)).stdout.splitlines()
    branches = [ref.removeprefix("refs/heads/") for line in heads
                for _, ref in [line.split(maxsplit=1)] if ref.startswith("refs/heads/")]
    current = _git(project, "branch", "--show-current").stdout.strip()
    if current in branches:
        dirty = _git(project, "status", "--porcelain", "--untracked-files=no").stdout.strip()
        if dirty:
            raise LgmError("git", f"working tree has uncommitted changes:\n{dirty}",
                           machine_code="dirty-worktree")
        local_refs = _git(project, "for-each-ref", "--format=%(refname)", "refs/heads/").stdout.splitlines()
        if f"refs/heads/{current}" in local_refs:
            counts = _git(project, "rev-list", "--left-right", "--count",
                          f"HEAD...refs/remotes/lgm/{current}").stdout.split()
            ahead, behind = map(int, counts)
            if ahead and behind:
                raise LgmError("git", f"branch '{current}' diverged from the mirror",
                               machine_code="diverged")
    others = [f"refs/heads/{branch}:refs/heads/{branch}"
              for branch in branches if branch != current]
    if others:
        _git(project, "fetch", "--atomic", str(bundle), *others)
    if current in branches:
        _git(project, "merge", "--ff-only", "--no-edit", f"refs/remotes/lgm/{current}")
    return fetched
