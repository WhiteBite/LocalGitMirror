"""Honest end-to-end stealth sync roundtrip smoke test (re-runnable).

Flow covered:
1) Create/select isolated repo on home server.
2) Simulate work user clone -> commit.
3) Generate stealth dump locally (Python implementation, no 7-Zip).
4) Upload dump to shared folder via API (same backend as UI uses).
5) Apply stealth sync on home server via API.
6) Simulate home edit + commit via API (/file/save + /git/save-and-sync).
7) Work clone pulls back changes from /git HTTP.

This is not a unit test; it's an operational smoke test for a running server.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

import requests
import urllib3


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from stealth_dump_tool import main as stealth_dump_main  # noqa: E402


urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


def run(cmd, cwd=None, check=True):
    result = subprocess.run(
        cmd, cwd=str(cwd) if cwd else None, capture_output=True, text=True
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(cmd)}\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}"
        )
    return result


def api(session, method, url, **kwargs):
    resp = session.request(method, url, timeout=30, **kwargs)
    return resp


def ensure_clean_dir(path: Path):
    if path.exists():
        shutil.rmtree(path, ignore_errors=True)
    path.mkdir(parents=True, exist_ok=True)


def ensure_clean_path(path: Path):
    if path.exists():
        shutil.rmtree(path, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://localhost:443")
    parser.add_argument("--api-key", default="stealth-bridge-token-2026")
    parser.add_argument("--password", default="dandan")
    parser.add_argument("--repo", default="e2estealth")
    parser.add_argument("--shared-folder", default="e2esync")
    parser.add_argument("--work-dir", default=str(ROOT / "tmp" / "e2e_work_roundtrip"))
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    repo = args.repo
    shared_folder = args.shared_folder
    work_dir = Path(args.work_dir)
    work_clone = work_dir / repo

    print("[1/8] Checking server...")
    s = requests.Session()
    s.verify = False
    s.headers.update({"X-Session-ID": args.api_key})
    r = api(s, "GET", f"{base}/api/status")
    if r.status_code != 200:
        raise SystemExit(f"Server not reachable: {r.status_code} {r.text}")
    print("  OK", r.json().get("storage_path"))

    print("[2/8] Preparing isolated repo/shared folder...")
    api(s, "POST", f"{base}/api/repos/delete", json={"repo": repo})  # best effort
    r = api(s, "POST", f"{base}/api/repos/create", json={"name": repo})
    if r.status_code not in (200, 400):
        raise SystemExit(f"Repo create failed: {r.status_code} {r.text}")
    r = api(s, "POST", f"{base}/api/repos/select", json={"repo": repo})
    r.raise_for_status()
    api(
        s, "POST", f"{base}/api/shared/folders", json={"name": shared_folder}
    )  # best effort

    print("[3/8] Simulating work user clone + commit...")
    ensure_clean_dir(work_dir)
    ensure_clean_path(work_clone)
    run(
        [
            "git",
            "-c",
            "http.sslVerify=false",
            "clone",
            f"{base}/git/{repo}",
            str(work_clone),
        ]
    )

    # Detect current branch name (could be master/main depending on git defaults)
    branch = (
        run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=work_clone).stdout.strip()
        or "master"
    )
    test_file = work_clone / "e2e_sync_demo.txt"
    test_file.write_text("work-user commit #1\n", encoding="utf-8")
    run(["git", "add", test_file.name], cwd=work_clone)
    run(
        [
            "git",
            "-c",
            "user.name=E2E Work",
            "-c",
            "user.email=e2e-work@example.local",
            "commit",
            "-m",
            "e2e: work change #1",
        ],
        cwd=work_clone,
    )

    print("[4/8] Generating stealth dump (Python, no 7-Zip)...")
    old_cwd = Path.cwd()
    try:
        # Run stealth_dump_tool as if user launched it from repo directory
        os.chdir(work_clone)
        old_argv = sys.argv[:]
        sys.argv = ["stealth_dump_tool.py", args.password]
        code = stealth_dump_main()
        sys.argv = old_argv
        if code != 0:
            raise RuntimeError(f"stealth_dump_tool exited with code {code}")
    finally:
        os.chdir(old_cwd)

    dumps = sorted(
        work_clone.glob(f"dump_{repo}_*.dmp"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if not dumps:
        raise SystemExit("No generated dump found")
    latest_dump = dumps[0]
    print("  dump:", latest_dump.name)

    print("[5/8] Uploading dump to shared folder (same endpoint as UI)...")
    with latest_dump.open("rb") as fh:
        r = api(
            s,
            "POST",
            f"{base}/api/shared/upload",
            files={"file": (latest_dump.name, fh, "application/octet-stream")},
            data={"folder": shared_folder},
        )
    if r.status_code != 200:
        raise SystemExit(f"Upload failed: {r.status_code} {r.text}")

    print("[6/8] Applying stealth sync on home server...")
    r = api(s, "POST", f"{base}/api/documents/apply")
    print("  apply response:", r.status_code, r.text[:500])
    if r.status_code != 200:
        raise SystemExit(f"Apply failed: {r.status_code} {r.text}")
    payload = r.json()
    if not payload.get("success"):
        raise SystemExit(f"Apply sync failed: {payload}")

    print("[7/8] Simulating home-side edit + save-and-sync...")
    r = api(
        s,
        "POST",
        f"{base}/api/file/save",
        json={"path": test_file.name, "content": "home-user edit #2\n"},
    )
    if r.status_code != 200:
        raise SystemExit(f"Home save failed: {r.status_code} {r.text}")
    r = api(
        s,
        "POST",
        f"{base}/api/git/save-and-sync",
        params={"message": "e2e: home change #2"},
    )
    if r.status_code != 200:
        raise SystemExit(f"Home save-and-sync failed: {r.status_code} {r.text}")

    print("[8/8] Work user pulls back and verifies roundtrip...")
    run(
        ["git", "-c", "http.sslVerify=false", "pull", "origin", branch],
        cwd=work_clone,
    )
    final_text = test_file.read_text(encoding="utf-8")
    if "home-user edit #2" not in final_text:
        raise SystemExit(f"Roundtrip verification failed. File content:\n{final_text}")

    print("\n✅ E2E stealth roundtrip PASSED")
    print(f"Repo: {repo}")
    print(f"Shared folder: {shared_folder}")
    print(f"Work clone: {work_clone}")


if __name__ == "__main__":
    main()
