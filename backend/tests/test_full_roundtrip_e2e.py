import subprocess
import time
from pathlib import Path
import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.routers import api as api_router


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed\n"
            f"cwd={cwd}\n"
            f"exit={proc.returncode}\n"
            f"stdout={proc.stdout}\n"
            f"stderr={proc.stderr}"
        )
    return proc


def test_full_roundtrip_work_home_work(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)

    # Ensure repo creation has deterministic git identity for initial commit.
    (storage / "settings.json").write_text(
        json.dumps(
            {
                "git": {
                    "user_name": "E2E Bot",
                    "user_email": "e2e@example.com",
                }
            }
        ),
        encoding="utf-8",
    )

    repo_name = f"e2e-roundtrip-{int(time.time())}"

    repo_manager = RepoManager(storage)

    api_router.git_handler = None
    api_router.repo_manager = repo_manager
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}
    api_router.system_logger = None

    app = FastAPI()
    app.include_router(api_router.router)
    client = TestClient(app)

    # 1) Create/select test repo ("home")
    created = client.post("/api/repos/create", json={"name": repo_name})
    assert created.status_code == 200, created.text
    assert created.json().get("success") is True, created.text

    selected = client.post("/api/repos/select", json={"repo": repo_name})
    assert selected.status_code == 200, selected.text
    assert selected.json().get("current") == repo_name, selected.text

    home_workspace = storage / repo_name
    home_file = home_workspace / "README.md"
    assert home_file.exists(), "Home README.md missing after create"

    # Make home repo pushable when checked out (realistic home target behavior).
    _run_git(home_workspace, "config", "receive.denyCurrentBranch", "updateInstead")

    # 2) Simulate "work" machine: clone -> edit -> commit -> push to home workspace
    work_dir = tmp_path / "work_machine"
    remote = str(home_workspace)
    _run_git(tmp_path, "clone", remote, str(work_dir))
    _run_git(work_dir, "config", "user.email", "work@example.com")
    _run_git(work_dir, "config", "user.name", "Work User")

    work_file = work_dir / "README.md"
    assert work_file.exists(), "README.md missing after clone"
    work_marker = f"work-change-{int(time.time())}"
    work_file.write_text(work_file.read_text(encoding="utf-8") + f"\n{work_marker}\n", encoding="utf-8")

    _run_git(work_dir, "add", "README.md")
    _run_git(work_dir, "commit", "-m", "Work sends change")
    branch = _run_git(work_dir, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
    _run_git(work_dir, "push", "origin", branch)

    # 3) Validate home received work change (maximally realistic "send to home")
    assert work_marker in home_file.read_text(encoding="utf-8"), (
        f"Home workspace did not receive pushed work changes. branch={branch}, remote={remote}"
    )

    # 4) Simulate editing on "home" and save via API endpoint
    home_marker = f"home-change-{int(time.time())}"
    home_file.write_text(home_file.read_text(encoding="utf-8") + f"\n{home_marker}\n", encoding="utf-8")

    prepared = client.post("/api/git/save-and-sync", params={"message": "Home edits for work"})
    assert prepared.status_code == 200, prepared.text
    assert prepared.json().get("success") is True, prepared.text

    # 5) Simulate "work" machine pulling back from home
    _run_git(work_dir, "pull", "origin", branch)
    final_text = work_file.read_text(encoding="utf-8")
    assert home_marker in final_text, (
        f"Work did not receive home edits. branch={branch}, remote={remote}, prepared={prepared.json()}"
    )
