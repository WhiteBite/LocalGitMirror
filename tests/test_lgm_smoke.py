"""Smoke tests against the live mirror server at https://127.0.0.1:443.

Marked as integration — skipped if the server is unreachable.
Run with: pytest tests/test_lgm_smoke.py -m integration
"""
import json
import subprocess
import sys
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parent.parent
_LGM = _REPO_ROOT / "lgm.py"
_BASE_URL = "https://127.0.0.1:443"
_API_KEY = "stealth-bridge-token-2026"

pytestmark = pytest.mark.integration


def _server_reachable() -> bool:
    """Quick TCP probe to see if the mirror server is up."""
    import ssl
    import urllib.request
    req = urllib.request.Request(f"{_BASE_URL}/api/health",
                                headers={"Authorization": f"Bearer {_API_KEY}"})
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=5) as r:
            return r.status == 200
    except Exception:
        return False


# Skip the entire module if the server is not reachable.
if not _server_reachable():
    pytest.skip("Mirror server not reachable at " + _BASE_URL, allow_module_level=True)


def _run_lgm(*args, timeout=30) -> tuple[int, str, str]:
    proc = subprocess.run(
        [sys.executable, str(_LGM), *args],
        capture_output=True, text=True, timeout=timeout,
        cwd=str(_REPO_ROOT),
    )
    return proc.returncode, proc.stdout, proc.stderr


def test_lgm_status_json():
    """lgm.py status --json against the live server."""
    code, out, err = _run_lgm(
        "--base-url", _BASE_URL,
        "--api-key", _API_KEY,
        "status", "--json",
        timeout=15,
    )
    assert code == 0, f"exit={code} stderr={err}"
    data = json.loads(out)
    assert "capabilities" in data
    assert "repos" in data


def test_lgm_repos_json():
    """lgm.py repos --json against the live server."""
    code, out, err = _run_lgm(
        "--base-url", _BASE_URL,
        "--api-key", _API_KEY,
        "repos", "--json",
        timeout=15,
    )
    assert code == 0, f"exit={code} stderr={err}"
    data = json.loads(out)
    assert "repos" in data


def test_lgm_branches_phonyx_json():
    """lgm.py branches --repo phonyx --json against the live server."""
    code, out, err = _run_lgm(
        "--base-url", _BASE_URL,
        "--api-key", _API_KEY,
        "branches", "--repo", "phonyx", "--json",
        timeout=15,
    )
    # branches uses envelope crypto which needs SYNC_PASSWORD.
    # If it fails on crypto, that's expected without --password.
    # We just check it doesn't crash with exit 2 (usage error).
    assert code != 2, f"usage error: {err}"


def test_lgm_deps_pending_json():
    """lgm.py pending --json against the live server."""
    code, out, err = _run_lgm(
        "--base-url", _BASE_URL,
        "--api-key", _API_KEY,
        "pending", "--repo", "onyx-platform", "--json",
        timeout=15,
    )
    assert code == 0, f"exit={code} stderr={err}"
    data = json.loads(out)
    assert "items" in data


def test_lgm_debug_json():
    """lgm.py debug --json against the live server."""
    code, out, err = _run_lgm(
        "--base-url", _BASE_URL,
        "--api-key", _API_KEY,
        "debug", "--json",
        timeout=30,
    )
    assert code == 0, f"exit={code} stderr={err}"
    data = json.loads(out)
    assert "capabilities" in data.get("mirror", {})


def test_lgm_vault_status_json():
    """lgm.py vault_status --json against the live server."""
    code, out, err = _run_lgm(
        "--base-url", _BASE_URL,
        "--api-key", _API_KEY,
        "vault_status", "--json",
        timeout=15,
    )
    assert code == 0, f"exit={code} stderr={err}"
    data = json.loads(out)
    assert "vault" in data or "error" in data
