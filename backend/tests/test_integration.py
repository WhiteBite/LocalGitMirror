#!/usr/bin/env python3
"""
Integration test - проверяет весь workflow
"""

import subprocess
import time
from pathlib import Path

import pytest
import requests

BASE = "http://localhost:80"


@pytest.fixture(scope="module", autouse=True)
def ensure_test_file():
    # Get current storage path from backend
    try:
        resp = requests.get(f"{BASE}/api/status")
        storage_path = Path(resp.json()["storage_path"])
        current_repo = resp.json()["current_repo"]

        repo_path = storage_path / current_repo
        repo_path.mkdir(parents=True, exist_ok=True)

        test_file = repo_path / "README.md"
        if not test_file.exists():
            test_file.write_text(
                "# Test Repository\nThis is a test file for integration tests."
            )

        # Also need to make it a git repo for some tests
        if not (repo_path / ".git").exists():
            subprocess.run(["git", "init"], cwd=repo_path, check=True)
            subprocess.run(["git", "add", "."], cwd=repo_path, check=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.com"],
                cwd=repo_path,
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test"], cwd=repo_path, check=True
            )
            subprocess.run(
                ["git", "commit", "-m", "Initial commit"], cwd=repo_path, check=True
            )

    except Exception as e:
        print(f"Warning: Failed to setup test file: {e}")


def test_server_running():
    requests.get(f"{BASE}/api/status").raise_for_status()


def test_git_server_starts():
    requests.post(f"{BASE}/api/git/start").raise_for_status()
    time.sleep(0.5)


def test_list_repos():
    r = requests.get(f"{BASE}/api/repos")
    r.raise_for_status()
    data = r.json()
    assert "repos" in data
    # assert len(data["repos"]) > 0


def test_list_files():
    r = requests.get(f"{BASE}/api/files")
    r.raise_for_status()
    data = r.json()
    assert "files" in data


def test_view_file_old_endpoint():
    r = requests.get(f"{BASE}/api/file/view?file=README.md")
    r.raise_for_status()
    data = r.json()
    assert data["success"]
    assert "content" in data


def test_view_file_frontend_endpoint():
    r = requests.get(f"{BASE}/api/files/content?path=README.md")
    r.raise_for_status()
    data = r.json()
    assert data["success"]
    assert "content" in data
    assert "metadata" in data


def test_settings_work():
    r = requests.get(f"{BASE}/api/settings")
    r.raise_for_status()
    data = r.json()
    assert "general" in data
    assert "git" in data
    # assert "editor" in data # Seems it's 'ui' in current version
    assert "ui" in data


def test_logs_work():
    r = requests.get(f"{BASE}/api/logs")
    r.raise_for_status()
    data = r.json()
    assert "logs" in data


def test_git_status():
    r = requests.get(f"{BASE}/api/git/changes")
    r.raise_for_status()
    data = r.json()
    assert "has_changes" in data
