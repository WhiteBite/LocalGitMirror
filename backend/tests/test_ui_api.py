import requests
import time

BASE_URL = "https://127.0.0.1:8443"
API_KEY = "stealth-bridge-token-2026"
HEADERS = {"X-API-Key": API_KEY}


def test_ui_workflow():
    print("Starting UI Workflow Test...")

    # 1. Get initial status
    try:
        resp = requests.get(f"{BASE_URL}/api/status", headers=HEADERS, verify=False)
        print(f"Initial Status: {resp.status_code}")
        data = resp.json()
        print(f"Current repo: {data.get('current_repo')}")

        # 2. Get list of repos
        resp = requests.get(f"{BASE_URL}/api/repos", headers=HEADERS, verify=False)
        repos = resp.json().get("repos", [])
        print(f"Available repos: {repos}")

        if not repos:
            print("No repos found, creating 'test-ui-repo'")
            requests.post(f"{BASE_URL}/api/repos/create", json={"name": "test-ui-repo"}, headers=HEADERS, verify=False)
            repos = ["test-ui-repo"]

        # 3. Select a repo (simulate UI click)
        target_repo = repos[0]
        print(f"Selecting repo: {target_repo}")
        resp = requests.post(f"{BASE_URL}/api/repos/select", json={"repo": target_repo}, headers=HEADERS, verify=False)
        assert resp.status_code == 200
        assert resp.json()["current"] == target_repo

        # 4. Verify status updated
        resp = requests.get(f"{BASE_URL}/api/status", headers=HEADERS, verify=False)
        assert resp.json()["current_repo"] == target_repo
        print("✅ Status synchronized successfully")

        # 5. Fetch files for this repo
        resp = requests.get(f"{BASE_URL}/api/files", headers=HEADERS, verify=False)
        assert resp.status_code == 200
        files = resp.json().get("files", [])
        print(f"✅ Found {len(files)} files in selected repo")

        print("\nALL UI API TESTS PASSED!")

    except Exception as e:
        print(f"[x] TEST FAILED: {e}")


if __name__ == "__main__":
    # Wait a bit for server if just started
    time.sleep(2)
    test_ui_workflow()
