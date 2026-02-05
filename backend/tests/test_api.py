#!/usr/bin/env python3
"""
Quick API test script
"""

import pytest
import requests

BASE_URL = "http://localhost:80"


@pytest.mark.parametrize(
    "method,path",
    [
        # Status
        ("GET", "/api/status"),
        ("GET", "/api/config"),
        # Git
        ("POST", "/api/git/start"),
        ("GET", "/api/repos"),
        # Files
        ("GET", "/api/files"),
        # Tests for file view are skipped because they require actual files in the storage
        # ("GET", "/api/file/view?file=README.md"),
        # ("GET", "/api/files/content?path=README.md"),
        # Settings
        ("GET", "/api/settings"),
        # Logs
        ("GET", "/api/logs"),
    ],
)
def test_endpoint(method, path):
    """Test an API endpoint"""
    expected_status = 200
    url = f"{BASE_URL}{path}"

    response = None
    if method == "GET":
        response = requests.get(url)
    elif method == "POST":
        response = requests.post(url)

    assert response is not None

    status = "✅" if response.status_code == expected_status else "❌"
    print(f"{status} {method} {path} -> {response.status_code}")

    if response.status_code != expected_status:
        print(f"   Expected: {expected_status}, Got: {response.status_code}")
        print(f"   Response: {response.text[:200]}")

    assert response.status_code == expected_status
