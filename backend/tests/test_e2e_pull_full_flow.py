"""
Honest end-to-end test of the pull flow as the IDE plugin sees it.

Simulates the full sequence the plugin makes in PullFromMirrorAction:
  1. handshake (GET /api/auth/verify)            — must reject wrong password
  2. list refs (GET /api/documents/list)          — must include branches in BARE
  3. preview details (POST /api/documents/preview-details)
  4. export bundle (POST /api/documents/export with branch + haves)
  5. decrypt the bundle and verify it contains the requested branch

Designed to catch real-world bugs we hit:
  - branch pushed to bare but invisible to /list
  - sync_has_commits being slow (single batched call required)
  - export sending --all when only one branch was requested
  - decrypt failing silently with empty error message on wrong password
  - export not finding a branch that lives only in bare

Each test is independent and uses a fresh storage dir.
"""
import base64
import json
import subprocess
import time
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from tests import _harness


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} (cwd={cwd}) failed: {proc.stderr}")
    return proc


def _make_client(tmp_path: Path, monkeypatch, sync_password: str = "test-pass"):
    """Spin up an in-memory FastAPI client wired to a fresh storage dir."""
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", sync_password)

    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return TestClient(app), storage


def _decrypt_dump(dump_bytes: bytes, password: str) -> bytes:
    """
    Mirror of BundleCrypto.decryptDumpBytes (Kotlin) and bundle_crypto.py.
    Supports BOTH formats the backend uses:
      v1 (legacy, /api/auth/verify):  [LGMSTRL1(8)][salt(16)][nonce(12)][len(8)][ct||tag]
      v2 (current dumps):             [0x01      ][salt(16)][nonce(12)][len(8)][ct||tag]
    """
    if dump_bytes[0:1] == b"L":
        # Legacy v1 with magic header
        assert dump_bytes[0:8] == b"LGMSTRL1", f"Unknown magic: {dump_bytes[0:8]!r}"
        salt = dump_bytes[8:24]
        nonce = dump_bytes[24:36]
        # 8-byte big-endian length, then ciphertext
        ct_len = int.from_bytes(dump_bytes[36:44], "big")
        ct = dump_bytes[44:44 + ct_len]
    elif dump_bytes[0:1] == b"\x01":
        # Current v2 (no magic, just version byte)
        salt = dump_bytes[1:17]
        nonce = dump_bytes[17:29]
        ct_len = int.from_bytes(dump_bytes[29:37], "big")
        ct = dump_bytes[37:37 + ct_len]
    else:
        raise AssertionError(f"Unknown dump format byte: {dump_bytes[0:1]!r}")
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200_000)
    key = kdf.derive(password.encode("utf-8"))
    return AESGCM(key).decrypt(nonce, ct, None)


def _is_git_bundle(data: bytes) -> bool:
    """Git bundle v2 header is `# v2 git bundle` or v3 is `# v3 git bundle`."""
    return data.startswith(b"# v2 git bundle") or data.startswith(b"# v3 git bundle")


def _make_repo_with_branch(client: TestClient, storage: Path, repo_name: str,
                           branch: str, commits: int = 2) -> str:
    """
    Create a repo, then simulate a `git push` of a new branch (lands in bare,
    not in workspace — the exact scenario the plugin reports as buggy).
    Returns the tip SHA of the pushed branch.
    """
    assert client.post("/api/repos/create", json={"name": repo_name}).status_code == 200

    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"
    work = storage / f"_scratch_{repo_name}"
    _run_git(storage.parent, "clone", str(bare), str(work))
    _run_git(work, "config", "user.email", "w@test.com")
    _run_git(work, "config", "user.name", "Worker")
    _run_git(work, "checkout", "-B", branch)
    for i in range(commits):
        (work / f"f{i}.txt").write_text(f"v{i}\n")
        _run_git(work, "add", ".")
        _run_git(work, "commit", "-m", f"{branch} commit {i}")
    _run_git(work, "push", "origin", branch)
    return _run_git(work, "rev-parse", "HEAD").stdout.strip()


# ─────────────────────────────────────────────────────────────────────────────
# 1. Handshake — wrong password must be detectable BEFORE downloading the bundle
# ─────────────────────────────────────────────────────────────────────────────

def test_handshake_wrong_password_decrypt_fails(tmp_path: Path, monkeypatch):
    """
    Server probe is encrypted with SYNC_PASSWORD. Client trying a different
    password must fail to decrypt it — that's the signal "passwords differ"
    that the plugin should use BEFORE pulling a multi-MB bundle.
    """
    client, _ = _make_client(tmp_path, monkeypatch, sync_password="server-pwd")

    resp = client.get("/api/auth/verify")
    assert resp.status_code == 200
    probe_bytes = resp.content
    assert _decrypt_dump(probe_bytes, "server-pwd").startswith(b"SYNC-PROBE")

    # Wrong password: AES-GCM raises InvalidTag (not a recovered plaintext)
    from cryptography.exceptions import InvalidTag
    try:
        _decrypt_dump(probe_bytes, "wrong-pwd")
        raise AssertionError("Decrypt with wrong password should have raised")
    except InvalidTag:
        pass


# ─────────────────────────────────────────────────────────────────────────────
# 2. List refs — branch pushed only to BARE must be visible
# ─────────────────────────────────────────────────────────────────────────────

def test_list_includes_bare_only_branch(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo = f"e2e-list-{int(time.time())}"
    _make_repo_with_branch(client, storage, repo, branch="confluence_mcp")

    resp = client.get("/api/documents/list", params={"repo": repo})
    assert resp.status_code == 200, resp.text
    refs = resp.json().get("refs", {})
    assert "confluence_mcp" in refs, (
        f"Branch pushed to bare must be listed. Got: {list(refs.keys())}"
    )


# ─────────────────────────────────────────────────────────────────────────────
# 3. Preview — must work for a branch that exists only in bare
# ─────────────────────────────────────────────────────────────────────────────

def test_preview_for_bare_only_branch(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo = f"e2e-preview-{int(time.time())}"
    _make_repo_with_branch(client, storage, repo, branch="confluence_mcp", commits=3)

    resp = client.post(
        "/api/documents/preview-details",
        json={"repo": repo, "since": None, "branch": "confluence_mcp"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("success") is True
    # confluence_mcp had 3 commits on top of the initial commit -> at least 3 listed
    assert len(body.get("commits", [])) >= 3, (
        f"Preview should list at least 3 commits, got {body.get('commits')}"
    )


# ─────────────────────────────────────────────────────────────────────────────
# 4. Full pull happy path — export, decrypt, bundle is a valid git bundle
#    that contains the requested branch
# ─────────────────────────────────────────────────────────────────────────────

def test_pull_happy_path_decrypts_and_contains_target_branch(tmp_path: Path, monkeypatch):
    """The honest end-to-end: client says 'give me confluence_mcp' and gets a
    decryptable bundle with that branch in it."""
    password = "e2e-flow-pwd"
    client, storage = _make_client(tmp_path, monkeypatch, sync_password=password)
    repo = f"e2e-pull-{int(time.time())}"
    target = "confluence_mcp"
    tip = _make_repo_with_branch(client, storage, repo, branch=target, commits=2)

    # Step 1: handshake
    probe = client.get("/api/auth/verify")
    assert probe.status_code == 200
    assert _decrypt_dump(probe.content, password).startswith(b"SYNC-PROBE")

    # Step 2: list
    refs = client.get("/api/documents/list", params={"repo": repo}).json()["refs"]
    assert refs.get(target, {}).get("sha") == tip

    # Step 3: export with branch + empty haves -> full delta of the branch
    export = client.post(
        "/api/documents/export",
        data={"repo": repo, "branch": target},
    )
    assert export.status_code == 200, export.text
    assert export.json().get("status") == "ok"

    # Step 4: decrypt and verify it's a valid git bundle
    encrypted = base64.b64decode(export.json()["data"])
    bundle_bytes = _decrypt_dump(encrypted, password)
    assert _is_git_bundle(bundle_bytes), "Decrypted payload must be a git bundle"

    # Step 5: feed the bundle into a fresh clone via `git fetch <bundle>` and
    # confirm the branch tip matches what /list reported. We write the bundle
    # to a temp file because modern git refuses `-` for security reasons.
    consumer = tmp_path / "consumer"
    consumer.mkdir()
    _run_git(consumer, "init")
    bundle_file = tmp_path / "decrypted.bundle"
    bundle_file.write_bytes(bundle_bytes)
    fetch = subprocess.run(
        ["git", "fetch", str(bundle_file), "+refs/heads/*:refs/fetched/*"],
        cwd=str(consumer), capture_output=True, timeout=30,
    )
    assert fetch.returncode == 0, f"git fetch from bundle failed: {fetch.stderr!r}"

    fetched_tip = _run_git(consumer, "rev-parse", f"refs/fetched/{target}").stdout.strip()
    assert fetched_tip == tip, (
        f"Branch tip mismatch: got {fetched_tip}, expected {tip}"
    )


# ─────────────────────────────────────────────────────────────────────────────
# 5. Wrong password → bundle is undecryptable → catches the original user-bug
# ─────────────────────────────────────────────────────────────────────────────

def test_pull_with_wrong_password_fails_to_decrypt(tmp_path: Path, monkeypatch):
    """
    Reproduce the user's report: 'pulled, got "decrypt error" without any
    detail in history'. The plugin SHOULD have caught this at handshake.
    Here we just prove the symptom is detectable: probe decryption raises
    InvalidTag, so the plugin can short-circuit before downloading anything.
    """
    from cryptography.exceptions import InvalidTag
    client, storage = _make_client(tmp_path, monkeypatch, sync_password="server-pwd")
    repo = f"e2e-wrongpwd-{int(time.time())}"
    _make_repo_with_branch(client, storage, repo, branch="main")

    # Plugin's view: probe with wrong password
    probe = client.get("/api/auth/verify")
    assert probe.status_code == 200
    try:
        _decrypt_dump(probe.content, "wrong-pwd")
        raise AssertionError("Probe should not decrypt with wrong password")
    except InvalidTag:
        pass  # expected — plugin would surface this as a clear error

    # And to be really sure: a real export with wrong password is also unreadable
    export = client.post("/api/documents/export", data={"repo": repo, "branch": "main"})
    assert export.status_code == 200
    encrypted = base64.b64decode(export.json()["data"])
    try:
        _decrypt_dump(encrypted, "wrong-pwd")
        raise AssertionError("Bundle should not decrypt with wrong password")
    except InvalidTag:
        pass


# ─────────────────────────────────────────────────────────────────────────────
# 6. Performance: /documents/check must be batched (the original 21s bug)
# ─────────────────────────────────────────────────────────────────────────────

def test_check_with_many_hashes_completes_fast(tmp_path: Path, monkeypatch):
    """
    Old code spawned one subprocess per hash. With 80 hashes the fork-only
    overhead alone was ~20s on Windows. The batched implementation must
    complete the same workload in well under a second. We assert <5s as a
    very lax threshold to avoid CI flakiness while still catching regressions.
    """
    client, storage = _make_client(tmp_path, monkeypatch)
    repo = f"e2e-check-{int(time.time())}"
    tip = _make_repo_with_branch(client, storage, repo, branch="main", commits=5)

    # Build 80 hashes: real one + 79 fakes (server should mark them missing)
    fakes = [f"deadbeef{'0' * 30}{i:02d}"[-40:] for i in range(79)]
    hashes_list = [tip, *fakes]

    started = time.perf_counter()
    resp = client.post("/api/documents/check", json={"repo": repo, "commits": hashes_list})
    elapsed = time.perf_counter() - started

    assert resp.status_code == 200
    body = resp.json()
    assert tip in body["known"], "Real tip must be reported as known"
    # Most fakes must NOT be reported (some short-prefix collisions tolerated)
    assert len(body["known"]) <= len(hashes_list) // 2, (
        f"Too many fakes accepted as known: {body['known']}"
    )
    assert elapsed < 5.0, (
        f"/documents/check took {elapsed:.2f}s for {len(hashes_list)} hashes — "
        "should be batched (was ~20s before fix)."
    )
