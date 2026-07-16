"""
Regression guard for POST /api/git/save-and-sync.

This endpoint is used by the in-browser code editor (CodeEditor.vue): after
writing file content via /api/file/save it calls save-and-sync to auto-commit
the edit. The endpoint has no plugin caller, so a ".py-only" search can wrongly
flag it as dead and remove it — which breaks the web editor silently.

This test pins the contract: the route must exist (not 404/405) and committing
through it must create a real commit in the workspace.
"""
import json
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from tests import _harness


def _git_out(cwd: Path, *args: str) -> str:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    assert proc.returncode == 0, f"git {' '.join(args)} failed: {proc.stderr}"
    return proc.stdout.strip()


def test_save_and_sync_commits_browser_edit(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)

    # Deterministic git identity so the create-time initial commit succeeds.
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Web Editor", "user_email": "web@example.com"}}),
        encoding="utf-8",
    )

    repo_name = f"save-sync-{int(time.time())}"
    repo_manager = RepoManager(storage)

    app = _harness.build_app(
        git_handler=None,
        repo_manager=repo_manager,
        git_workspace=None,
        shared_manager=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
        system_logger=None,
    )
    client = TestClient(app)

    created = client.post("/api/repos/create", json={"name": repo_name})
    assert created.status_code == 200, created.text
    selected = client.post("/api/repos/select", json={"repo": repo_name})
    assert selected.status_code == 200, selected.text

    workspace = storage / repo_name
    commits_before = _git_out(workspace, "rev-list", "--count", "HEAD")

    # 1) Edit a file the way the browser editor does (CodeEditor.vue step 1)
    saved = client.post("/api/file/save", json={"path": "README.md", "content": "# Edited in browser\n"})
    assert saved.status_code == 200, saved.text

    # 2) Auto-commit via save-and-sync (CodeEditor.vue step 2)
    commit_msg = "Edit README.md via Browser"
    res = client.post("/api/git/save-and-sync", params={"message": commit_msg})

    # The route must be registered (guards against accidental removal).
    assert res.status_code == 200, f"save-and-sync must exist and return 200, got {res.status_code}: {res.text}"
    assert res.json().get("success") is True, res.text

    # And it must have actually produced a commit carrying our message.
    commits_after = _git_out(workspace, "rev-list", "--count", "HEAD")
    assert int(commits_after) == int(commits_before) + 1, "save-and-sync did not create a new commit"
    assert _git_out(workspace, "log", "-1", "--format=%s") == commit_msg
