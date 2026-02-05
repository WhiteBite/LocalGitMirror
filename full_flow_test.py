import subprocess
import shutil
import os
import time
from pathlib import Path

# Test Configuration
BASE_URL = "http://localhost:8000"
REPO_NAME = f"test-project-{int(time.time())}"
TEMP_DIR = Path("temp_git_test")


def on_rm_error(func, path, exc_info):
    import stat

    os.chmod(path, stat.S_IWRITE)
    func(path)


def run_git_test():
    print(f"Starting Git-over-HTTP Flow Test: {REPO_NAME}")

    # 1. Prepare local test repo
    if TEMP_DIR.exists():
        shutil.rmtree(TEMP_DIR, onerror=on_rm_error)
    TEMP_DIR.mkdir()

    try:
        # git init
        subprocess.run(["git", "init"], cwd=TEMP_DIR, check=True)
        subprocess.run(
            ["git", "config", "user.email", "test@example.com"],
            cwd=TEMP_DIR,
            check=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "Test User"], cwd=TEMP_DIR, check=True
        )

        # create file
        (TEMP_DIR / "README.md").write_text("# Test Project")
        subprocess.run(["git", "add", "."], cwd=TEMP_DIR, check=True)
        subprocess.run(
            ["git", "commit", "-m", "Initial commit"], cwd=TEMP_DIR, check=True
        )

        # 2. Add remote (HTTP)
        remote_url = f"{BASE_URL}/git/{REPO_NAME}"
        subprocess.run(
            ["git", "remote", "add", "home", remote_url], cwd=TEMP_DIR, check=True
        )

        # 3. PUSH (The critical part)
        print(f"Attempting git push to {remote_url}...")
        result = subprocess.run(
            ["git", "push", "--force", "home", "master"],
            cwd=TEMP_DIR,
            capture_output=True,
            text=True,
            timeout=30,
        )

        if result.returncode == 0:
            print("SUCCESS: Git push completed!")
        else:
            print(f"FAILED: Git push failed with code {result.returncode}")
            print(f"Error output: {result.stderr}")
            print(f"Out output: {result.stdout}")

    except Exception as e:
        print(f"ERROR: {e}")
    finally:
        pass


if __name__ == "__main__":
    run_git_test()
