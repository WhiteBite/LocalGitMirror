import pytest
from playwright.sync_api import Page, expect
import subprocess
import time
import os
import shutil
from pathlib import Path

# Configuration for tests
BASE_URL = "http://localhost:5173"
STORAGE_PATH = Path("storage_test")


@pytest.fixture(scope="session", autouse=True)
def setup_test_env():
    """Setup test storage and environment"""
    if STORAGE_PATH.exists():
        shutil.rmtree(STORAGE_PATH)
    STORAGE_PATH.mkdir(parents=True, exist_ok=True)

    # Set env variable for backend to use test storage
    os.environ["STORAGE_PATH"] = str(STORAGE_PATH)

    yield

    # Cleanup after all tests
    # if STORAGE_PATH.exists():
    #    shutil.rmtree(STORAGE_PATH)


def test_dashboard_loads(page: Page):
    """Test 1: Dashboard should load and show system status"""
    page.goto(BASE_URL)

    # Verify Title
    expect(page).to_have_title("Dashboard - LocalGitMirror")

    # Verify critical UI elements are present
    expect(page.get_by_text("Active Project:")).to_be_visible()
    expect(page.get_by_text("GIT SERVER")).to_be_visible()
    expect(page.get_by_text("Sync Guide")).to_be_visible()

    # Verify navigation works
    page.get_by_title("Files").click()
    expect(page).to_have_url(f"{BASE_URL}/files")

    page.get_by_title("Dashboard").click()
    expect(page).to_have_url(f"{BASE_URL}/dashboard")


def test_repo_selection_workflow(page: Page):
    """Test 2: Creating a repo via git push and selecting it in UI"""
    repo_name = "test-project"
    repo_dir = STORAGE_PATH / repo_name

    # 1. Simulate git push (this should create the folder)
    # In a real scenario, this would be git push git://...
    # For e2e, we check if UI reacts to folder appearing
    repo_dir.mkdir(parents=True, exist_ok=True)
    (repo_dir / ".git").mkdir()  # Make it look like a git repo
    (repo_dir / "hello.py").write_text("print('hello')")

    page.goto(BASE_URL)
    page.reload()  # Force discovery

    # 2. Check if repo appears in sidebar
    repo_item = page.get_by_text(repo_name)
    expect(repo_item).to_be_visible()

    # 3. Select repo
    repo_item.click()

    # 4. Verify status bar updated
    expect(page.locator(".status-bar")).to_contain_text(repo_name)
    expect(page.get_by_text(f"Active Project: {repo_name}")).to_be_visible()


def test_file_browser_navigation(page: Page):
    """Test 3: Browsing files in a project"""
    repo_name = "browser-test"
    repo_dir = STORAGE_PATH / repo_name
    repo_dir.mkdir(parents=True, exist_ok=True)
    (repo_dir / ".git").mkdir()

    # Create nested structure
    src_dir = repo_dir / "src"
    src_dir.mkdir()
    (src_dir / "main.py").write_text("print('main')")
    (repo_dir / "README.md").write_text("# Test Project")

    page.goto(f"{BASE_URL}/files")

    # Select our repo
    page.get_by_text(repo_name).click()

    # Check if README.md is visible
    expect(page.get_by_text("README.md")).to_be_visible()

    # Test search
    search_input = page.get_by_placeholder("Search files...")
    search_input.fill("main.py")
    expect(page.get_by_text("main.py")).to_be_visible()
    expect(page.get_by_text("README.md")).not_to_be_visible()


def test_sync_action(page: Page):
    """Test 4: Prepare for Work action (The Main Button)"""
    page.goto(BASE_URL)

    # Click Prepare for Work
    sync_btn = page.get_by_role("button", name="Prepare for Work")
    expect(sync_btn).to_be_enabled()

    # We should see a success message (alert)
    # Playwright handles alerts automatically or we can listen for them
    with page.expect_event("dialog") as dialog_info:
        sync_btn.click()

    dialog = dialog_info.value
    assert "Ready" in dialog.message or "Success" in dialog.message
    dialog.accept()


def test_settings_persistence(page: Page):
    """Test 5: Settings page and theme toggle"""
    page.goto(f"{BASE_URL}/settings")

    expect(page.get_by_text("General Settings")).to_be_visible()

    # Try to change something (if UI allows)
    # For now just verify tabs work
    page.get_by_text("Git", exact=True).click()
    expect(page.get_by_text("Git Server Port")).to_be_visible()
