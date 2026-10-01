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

    # SPA default locale is ru - UI text assertions are in Russian
    expect(page).to_have_title("Dashboard - LocalGitMirror")

    expect(page.get_by_text("Последняя активность")).to_be_visible()
    expect(page.get_by_text("Сервер", exact=True)).to_be_visible()
    expect(page.get_by_text("Плагин IDEA")).to_be_visible()

    page.get_by_title("Файлы").click()
    expect(page).to_have_url(f"{BASE_URL}/files")

    page.get_by_title("Панель управления").click()
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
    repo_item = page.locator(".project-item", has_text=repo_name)
    expect(repo_item).to_be_visible()

    # 3. Select repo
    repo_item.click()

    # 4. Verify status bar updated
    expect(page.locator(".status-bar")).to_contain_text(repo_name)
    expect(page.locator(".active-project-card h2")).to_have_text(repo_name)


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
    page.locator(".project-item", has_text=repo_name).click()

    # Check if README.md is visible
    expect(page.get_by_text("README.md")).to_be_visible()

    # Test search
    search_input = page.get_by_placeholder("Поиск файлов...")
    search_input.fill("main.py")
    expect(page.get_by_text("main.py")).to_be_visible()
    expect(page.get_by_text("README.md")).not_to_be_visible()


def test_panic_action(page: Page):
    """Test 4: PANIC button asks for confirmation before stopping the server"""
    page.goto(BASE_URL)

    panic_btn = page.get_by_role("button", name="ПАНИКА")
    expect(panic_btn).to_be_enabled()

    with page.expect_event("dialog") as dialog_info:
        panic_btn.click()

    dialog = dialog_info.value
    assert "Остановить сервер" in dialog.message
    # dismissing on purpose: accepting would stop the server under test
    dialog.dismiss()


def test_settings_persistence(page: Page):
    """Test 5: Settings page and theme toggle"""
    page.goto(f"{BASE_URL}/settings")

    expect(page.get_by_role("heading", name="Общие")).to_be_visible()

    # Try to change something (if UI allows)
    # For now just verify tabs work
    page.get_by_role("button", name="Git").click()
    expect(page.get_by_text("Порт HTTPS сервера")).to_be_visible()
