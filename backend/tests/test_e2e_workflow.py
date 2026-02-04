import shutil
import os
import time
from pathlib import Path
import subprocess
import pytest
import requests
import random

BASE_URL = "http://localhost:80"
# Use a unique storage path for each run to avoid file lock issues
RUN_ID = f"{int(time.time())}_{random.randint(1000, 9999)}"
STORAGE_PATH = Path(f"storage_test_e2e_{RUN_ID}")


@pytest.fixture(scope="module", autouse=True)
def setup_teardown():
    # Setup: Create a test storage directory
    if STORAGE_PATH.exists():
        try:

            def on_rm_error(func, path, exc_info):
                import stat

                os.chmod(path, stat.S_IWRITE)
                func(path)

            shutil.rmtree(STORAGE_PATH, onerror=on_rm_error)
        except:
            pass

    STORAGE_PATH.mkdir()

    # Configure backend to use this storage path
    path_str = str(STORAGE_PATH.absolute()).replace("\\", "/")
    response = requests.post(f"{BASE_URL}/api/config/storage", json={"path": path_str})
    assert response.status_code == 200

    yield

    # Teardown: Cleanup is optional and risky on Windows due to locks
    # We leave it for manual cleanup or unique names solve the collision


def test_full_workflow():
    # 1. Start Git Server
    resp = requests.post(f"{BASE_URL}/api/git/start")
    assert resp.status_code == 200
    # If already running, it returns success: False but running: True

    # 2. Create a "Work" repo (client side)
    work_repo_path = STORAGE_PATH / "work_client_repo"
    work_repo_path.mkdir()

    subprocess.run(["git", "init"], cwd=work_repo_path, check=True)
    subprocess.run(
        ["git", "config", "user.email", "test@example.com"],
        cwd=work_repo_path,
        check=True,
    )
    subprocess.run(
        ["git", "config", "user.name", "Test User"], cwd=work_repo_path, check=True
    )

    readme_path = work_repo_path / "README.md"
    readme_path.write_text("# Hello World")

    subprocess.run(["git", "add", "."], cwd=work_repo_path, check=True)
    subprocess.run(
        ["git", "commit", "-m", "Initial commit"], cwd=work_repo_path, check=True
    )

    # 3. Push to Home (Auto-creation test)
    # Use 127.0.0.1 to avoid localhost resolution issues
    home_remote_url = "git://127.0.0.1:8081/test-project"
    subprocess.run(
        ["git", "remote", "add", "home", home_remote_url],
        cwd=work_repo_path,
        check=True,
    )

    # We expect this to either auto-create or we might need to "touch" it via API if broken
    # README says it auto-creates.

    # Try push with a short timeout. If it hangs, we found the bug.
    try:
        # Pushing to 'master' (or whatever current branch is)
        # Some git versions default to 'master', others 'main'
        branch = "master"
        subprocess.run(
            ["git", "push", "home", branch], cwd=work_repo_path, check=True, timeout=15
        )
    except subprocess.TimeoutExpired:
        pytest.fail(
            "Git push HANGED. This is a real bug in the Git server implementation!"
        )
    except subprocess.CalledProcessError as e:
        # If it failed immediately, check why
        pytest.fail(
            f"Git push failed with code {e.returncode}. Is auto-creation broken?"
        )

    # 4. Verify files appeared
    resp = requests.get(f"{BASE_URL}/api/files?repo=test-project")
    assert resp.status_code == 200
    files = resp.json()["files"]
    assert any(f["name"] == "README.md" for f in files)

    # 5. Prepare for work (Commit at home)
    # Select repo first
    requests.post(f"{BASE_URL}/api/repos/select", json={"repo": "test-project"})

    # Modify file on server side directly to simulate editing at home
    server_repo_path = STORAGE_PATH / "test-project"
    (server_repo_path / "README.md").write_text("# Edited at Home")

    resp = requests.post(
        f"{BASE_URL}/api/git/save-and-sync", params={"message": "Home changes"}
    )
    assert resp.status_code == 200
    assert resp.json()["success"] is True

    # 6. Pull back to work
    subprocess.run(["git", "pull", "home", "master"], cwd=work_repo_path, check=True)
    assert readme_path.read_text() == "# Edited at Home"
