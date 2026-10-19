"""Gate for the workspace data-loss class: the mirror workspace IS the home
machine's working checkout, so self-healing and branch attach must never
destroy untracked files there. A colliding incoming file must turn into a
loud apply failure telling the user to park/commit the file and retry."""

import subprocess
from pathlib import Path

from app.core import git_bundle
from app.core.bundle_crypto import MAGIC


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _build_repo(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    _run_git(path, "init")
    _run_git(path, "config", "user.email", "ws@test.com")
    _run_git(path, "config", "user.name", "WS")
    (path / "f.txt").write_text("v1\n", encoding="utf-8")
    _run_git(path, "add", "f.txt")
    _run_git(path, "commit", "-m", "first")
    return path


def _repo_with_incoming(tmp_path: Path) -> tuple[Path, str, str]:
    ws = _build_repo(tmp_path / "ws")
    _run_git(ws, "branch", "-M", "main")
    h1 = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    _run_git(ws, "checkout", "-B", "incoming")
    (ws / "collision.txt").write_text("incoming\n", encoding="utf-8")
    _run_git(ws, "add", "collision.txt")
    _run_git(ws, "commit", "-m", "incoming adds collision.txt")
    h2 = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    _run_git(ws, "checkout", "main")
    return ws, h1, h2


def test_clean_workspace_is_untouched(tmp_path):
    ws = _build_repo(tmp_path / "ws")

    assert git_bundle._ensure_clean_workspace(ws) is None

    assert _run_git(ws, "status", "--porcelain").stdout == ""
    assert (ws / "f.txt").read_text(encoding="utf-8") == "v1\n"


def test_dirty_tracked_file_is_reverted(tmp_path):
    ws = _build_repo(tmp_path / "ws")
    (ws / "f.txt").write_text("dirty\n", encoding="utf-8")

    assert git_bundle._ensure_clean_workspace(ws) is None

    assert (ws / "f.txt").read_text(encoding="utf-8") == "v1\n"


def test_untracked_file_survives_self_healing(tmp_path):
    ws = _build_repo(tmp_path / "ws")
    (ws / "f.txt").write_text("dirty\n", encoding="utf-8")
    (ws / "notes.local").write_text("do not lose me\n", encoding="utf-8")

    git_bundle._ensure_clean_workspace(ws)

    assert (ws / "f.txt").read_text(encoding="utf-8") == "v1\n"
    assert (ws / "notes.local").read_text(encoding="utf-8") == "do not lose me\n"


def test_ignored_file_survives_self_healing(tmp_path):
    ws = _build_repo(tmp_path / "ws")
    (ws / ".gitignore").write_text("ignored.txt\n", encoding="utf-8")
    _run_git(ws, "add", ".gitignore")
    _run_git(ws, "commit", "-m", "gitignore")
    (ws / "f.txt").write_text("dirty\n", encoding="utf-8")
    (ws / "ignored.txt").write_text("local cache\n", encoding="utf-8")

    git_bundle._ensure_clean_workspace(ws)

    assert (ws / "f.txt").read_text(encoding="utf-8") == "v1\n"
    assert (ws / "ignored.txt").read_text(encoding="utf-8") == "local cache\n"


def test_attach_reset_path_refuses_to_overwrite_untracked(tmp_path):
    ws, h1, h2 = _repo_with_incoming(tmp_path)
    (ws / "collision.txt").write_text("local notes\n", encoding="utf-8")

    result = git_bundle._attach_workspace(ws, "main", h2)

    assert result.returncode != 0
    assert (ws / "collision.txt").read_text(encoding="utf-8") == "local notes\n"
    assert _run_git(ws, "rev-parse", "HEAD").stdout.strip() == h1


def test_attach_checkout_path_refuses_to_overwrite_untracked(tmp_path):
    ws, _h1, h2 = _repo_with_incoming(tmp_path)
    (ws / "collision.txt").write_text("local notes\n", encoding="utf-8")

    result = git_bundle._attach_workspace(ws, "incoming", h2)

    assert result.returncode != 0
    assert (ws / "collision.txt").read_text(encoding="utf-8") == "local notes\n"
    assert _run_git(ws, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip() == "main"


def test_attach_allows_untracked_files_not_in_incoming_tree(tmp_path):
    ws, _h1, h2 = _repo_with_incoming(tmp_path)
    (ws / "other.local").write_text("keep me\n", encoding="utf-8")

    result = git_bundle._attach_workspace(ws, "main", h2)

    assert result.returncode == 0
    assert _run_git(ws, "rev-parse", "HEAD").stdout.strip() == h2
    assert (ws / "collision.txt").read_text(encoding="utf-8") == "incoming\n"
    assert (ws / "other.local").read_text(encoding="utf-8") == "keep me\n"


def test_apply_dump_fails_loudly_on_untracked_conflict(tmp_path, monkeypatch):
    repo = "conflict_repo"
    workspace = tmp_path / repo
    bare = tmp_path / f"{repo}.git"
    workspace.mkdir(parents=True)
    bare.mkdir()

    class _RM:
        def _get_workspace_path(self, _repo):
            return workspace

        def _get_bare_path(self, _repo):
            return bare

    monkeypatch.setattr(git_bundle, "repo_manager", _RM(), raising=False)
    monkeypatch.setenv("SYNC_PASSWORD", "pwd")

    dump = tmp_path / f"dump_{repo}_20261019_1200.dmp"
    dump.write_bytes(MAGIC + b"x" * 64)
    monkeypatch.setattr(
        git_bundle, "decrypt_dump_to_bundle",
        lambda _dump, out, _pwd: out.write_bytes(b"bundle"),
    )

    class _P:
        def __init__(self, rc=0, out="", err=""):
            self.returncode = rc
            self.stdout = out
            self.stderr = err

    def _fake_git(_wd, *args):
        if args[:2] == ("status", "--porcelain"):
            return _P(0, "")
        if args[:2] == ("bundle", "list-heads"):
            return _P(0, "aaa111 refs/heads/main\n")
        if args[:3] == ("symbolic-ref", "--short", "-q"):
            return _P(0, "main\n")
        if args[:2] == ("ls-files", "--others"):
            return _P(0, "notes.local\n")
        if args[:2] == ("rev-parse", "--verify"):
            return _P(0, "bbb222\n")
        if args[0] == "diff":
            return _P(0, "notes.local\n")
        if args[0] == "fetch":
            return _P(0, "")
        if args[:2] == ("rev-parse", "refs/heads/main"):
            return _P(0, "aaa111\n")
        return _P(0, "", "")

    monkeypatch.setattr(git_bundle, "_git", _fake_git)

    res = git_bundle._apply_dump_to_repo_and_sync_bare(
        dump_path=dump,
        repo_name=repo,
        dump_filename=dump.name,
    )

    assert res["success"] is False
    assert repo in res["message"]
    assert "untracked" in res["message"].lower()
    assert "retry" in res["message"].lower()
