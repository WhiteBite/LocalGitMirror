"""
Tests for the binary export mode (params.format == "bin") of /api/documents/export.

The dump format seals with a fresh random salt/nonce per call, so binary and
legacy bodies are never byte-equal; parity is asserted on the decrypted
plaintext bundle instead.
"""
import base64
import json
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.bundle_crypto import decrypt_dump_bytes
from app.core.envelope_crypto import decrypt_envelope
from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_form_post, make_envelope, parse_envelope

PASSWORD = "export-bin-test-pw"


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _make_env(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@example.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    client = TestClient(app)

    repo = f"binexport-{int(time.time() * 1000)}"
    assert client.post("/api/documents/collection", json={"name": repo}).status_code == 200

    ws = storage / repo
    _run_git(ws, "checkout", "-B", "main")
    (ws / "a.txt").write_text("a\n", encoding="utf-8")
    _run_git(ws, "add", ".")
    _run_git(ws, "commit", "-m", "commit A")
    sha_a = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    (ws / "b.txt").write_text("b\n", encoding="utf-8")
    _run_git(ws, "add", ".")
    _run_git(ws, "commit", "-m", "commit B")
    head = _run_git(ws, "rev-parse", "HEAD").stdout.strip()

    return client, repo, sha_a, head


def _export_bin(client: TestClient, payload: dict):
    e = make_envelope(payload, PASSWORD)
    return client.post("/api/documents/export", data={"e": e})


def test_binary_mode_parity_with_legacy_dump(tmp_path, monkeypatch):
    client, repo, _sha_a, head = _make_env(tmp_path, monkeypatch)

    legacy = envelope_form_post(
        client, "/api/documents/export", {"repo": repo, "branch": "main"}, PASSWORD
    )
    assert legacy.status_code == 200
    legacy_body = legacy.json()

    resp = _export_bin(client, {"repo": repo, "branch": "main", "format": "bin"})
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "application/octet-stream"
    assert int(resp.headers["content-length"]) == len(resp.content)

    env = decrypt_envelope(resp.headers["x-lgm-env"], PASSWORD)
    assert env == {"status": "ok", "head": head, "repo": repo}

    bin_bundle = decrypt_dump_bytes(resp.content, PASSWORD)
    legacy_bundle = decrypt_dump_bytes(base64.b64decode(legacy_body["d"]), PASSWORD)
    assert bin_bundle == legacy_bundle
    assert bin_bundle.startswith(b"# v")


def test_legacy_json_unchanged_without_format(tmp_path, monkeypatch):
    client, repo, _sha_a, _head = _make_env(tmp_path, monkeypatch)

    resp = envelope_form_post(
        client, "/api/documents/export", {"repo": repo, "branch": "main"}, PASSWORD
    )
    assert resp.status_code == 200
    assert "application/json" in resp.headers["content-type"]
    assert "x-lgm-env" not in resp.headers
    body = resp.json()
    assert set(body) == {"e", "d"}
    inner = parse_envelope(body, PASSWORD)
    assert inner["status"] == "ok"
    assert base64.b64decode(body["d"])

    other = _export_bin(client, {"repo": repo, "branch": "main", "format": "json"})
    assert other.status_code == 200
    assert "application/json" in other.headers["content-type"]
    assert set(other.json()) == {"e", "d"}


def test_no_content_binary_mode_empty_body_with_header(tmp_path, monkeypatch):
    client, repo, _sha_a, head = _make_env(tmp_path, monkeypatch)

    resp = _export_bin(
        client, {"repo": repo, "branch": "main", "haves": head, "format": "bin"}
    )
    assert resp.status_code == 200
    assert resp.content == b""
    assert int(resp.headers["content-length"]) == 0

    env = decrypt_envelope(resp.headers["x-lgm-env"], PASSWORD)
    assert env == {"status": "no_content", "head": head, "repo": repo}


def test_binary_mode_respects_haves_and_since(tmp_path, monkeypatch):
    client, repo, sha_a, _head = _make_env(tmp_path, monkeypatch)

    full = _export_bin(client, {"repo": repo, "branch": "main", "format": "bin"})
    delta = _export_bin(
        client, {"repo": repo, "branch": "main", "haves": sha_a, "format": "bin"}
    )
    assert full.status_code == 200 and delta.status_code == 200
    full_bundle = decrypt_dump_bytes(full.content, PASSWORD)
    delta_bundle = decrypt_dump_bytes(delta.content, PASSWORD)
    assert delta_bundle.startswith(b"# v")
    assert len(delta_bundle) < len(full_bundle)

    full_all = _export_bin(client, {"repo": repo, "format": "bin"})
    since = _export_bin(client, {"repo": repo, "since": sha_a, "format": "bin"})
    assert since.status_code == 200
    assert decrypt_envelope(since.headers["x-lgm-env"], PASSWORD)["status"] == "ok"
    assert len(decrypt_dump_bytes(since.content, PASSWORD)) < len(
        decrypt_dump_bytes(full_all.content, PASSWORD)
    )
