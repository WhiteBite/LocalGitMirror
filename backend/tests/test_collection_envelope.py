"""Collection-repo creation via sealed envelope.

The plugin's ensureRepoExists keeps the plaintext repo name out of the
cleartext request body (work-side stealth): it sends the rid plus the name
sealed in the standard envelope ("e" + optional "k" for v3 clients).
"""

import base64
import json
import subprocess
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from fastapi.testclient import TestClient

from app.core import hybrid_crypto as hc
from app.core import sync_envelope
from app.core.bundle_crypto import encrypt_bundle_to_dump
from app.core.repo_manager import RepoManager
from app.routers._rid import repo_to_rid
from tests import _harness
from tests.conftest import envelope_form_post, envelope_post, make_envelope, parse_envelope


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed\n"
            f"cwd={cwd}\n"
            f"exit={proc.returncode}\n"
            f"stdout={proc.stdout}\n"
            f"stderr={proc.stderr}"
        )
    return proc


def _build_client(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", "e2e-password")
    repo_manager = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=repo_manager,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return repo_manager, TestClient(app), storage


class _V3Client:
    def __init__(self, server_pub: bytes):
        self._eph = X25519PrivateKey.generate()
        self.epk = self._eph.public_key().public_bytes_raw()
        self._shared = self._eph.exchange(X25519PublicKey.from_public_bytes(server_pub))

    @property
    def epk_b64(self) -> str:
        return base64.urlsafe_b64encode(self.epk).decode("ascii")

    def seal_env(self, payload: dict) -> str:
        key = hc._derive(self._shared, self.epk, hc.INFO_ENV_REQ)
        pt = json.dumps(payload, separators=(",", ":")).encode()
        return base64.b64encode(hc._seal(key, pt)).decode("ascii")


def test_envelope_creation_names_repo_after_plaintext(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    repo_name = "env-create"
    rid = repo_to_rid(repo_name)

    resp = client.post(
        "/api/documents/collection",
        json={"rid": rid, "e": make_envelope({"repo": repo_name}, "e2e-password")},
    )

    assert resp.status_code == 200, resp.text
    assert resp.json().get("success") is True
    assert "e" not in resp.json()
    assert repo_name in repo_manager.get_repos()
    assert rid not in repo_manager.get_repos()
    assert (storage / repo_name / ".git").exists()
    assert (storage / ".lgm" / "bare" / f"{repo_name}.git").exists()


def test_unknown_rid_without_envelope_creates_under_rid(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    rid = repo_to_rid("no-such-repo-anywhere")

    resp = client.post("/api/documents/collection", json={"rid": rid})

    assert resp.status_code == 200, resp.text
    assert resp.json().get("success") is True
    assert rid in repo_manager.get_repos()
    assert (storage / rid / ".git").exists()


def test_plain_name_creation_unchanged(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    repo_name = "name-create"

    resp = client.post("/api/documents/collection", json={"name": repo_name})

    assert resp.status_code == 200, resp.text
    assert resp.json().get("success") is True
    assert repo_name in repo_manager.get_repos()
    assert (storage / repo_name / ".git").exists()


def test_envelope_for_existing_repo_returns_already_exists(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    repo_name = "env-exists"
    created = client.post("/api/documents/collection", json={"name": repo_name})
    assert created.status_code == 200, created.text

    rid = repo_to_rid(repo_name)
    resp = client.post(
        "/api/documents/collection",
        json={"rid": rid, "e": make_envelope({"repo": repo_name}, "e2e-password")},
    )

    assert resp.status_code == 400
    assert "уже существует" in resp.json()["detail"]


def test_first_sync_e2e(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    repo_name = "e2e-first-sync"
    rid = repo_to_rid(repo_name)

    created = client.post(
        "/api/documents/collection",
        json={"rid": rid, "e": make_envelope({"repo": repo_name}, "e2e-password")},
    )
    assert created.status_code == 200, created.text

    refs = envelope_post(client, "/api/documents/list", {"repo": repo_name}, "e2e-password")
    assert refs.status_code == 200, refs.text

    ws = storage / repo_name
    _run_git(ws, "checkout", "-B", "main")

    src = tmp_path / "src"
    src.mkdir(parents=True, exist_ok=True)
    _run_git(src, "init")
    _run_git(src, "config", "user.email", "src@example.com")
    _run_git(src, "config", "user.name", "Src")
    _run_git(src, "checkout", "-B", "main")
    (src / "f.txt").write_text("v1\n", encoding="utf-8")
    _run_git(src, "add", "f.txt")
    _run_git(src, "commit", "-m", "first sync commit")
    src_head = _run_git(src, "rev-parse", "HEAD").stdout.strip()

    bundle = tmp_path / "first.bundle"
    _run_git(src, "bundle", "create", str(bundle), "--all")
    dump = tmp_path / f"dump_{repo_name}_20260313_1400.dmp"
    encrypt_bundle_to_dump(bundle, dump, "e2e-password")

    up = envelope_form_post(
        client, "/api/documents/upload", {"repo": repo_name}, "e2e-password",
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert up.status_code == 200, up.text
    upj = parse_envelope(up.json(), "e2e-password")
    assert upj.get("success") is True, upj
    assert upj.get("repo") == repo_name

    head_after = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    assert head_after == src_head


def test_envelope_creation_v3_with_k_epk(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    server_priv = hc.load_or_create_server_key(storage / ".lgm" / "srv.key")
    monkeypatch.setattr(sync_envelope, "server_private_key", server_priv)

    repo_name = "env-create-v3"
    rid = repo_to_rid(repo_name)
    sender = _V3Client(hc.public_bytes(server_priv))

    resp = client.post(
        "/api/documents/collection",
        json={"rid": rid, "e": sender.seal_env({"repo": repo_name}), "k": sender.epk_b64},
    )

    assert resp.status_code == 200, resp.text
    assert resp.json().get("success") is True
    assert repo_name in repo_manager.get_repos()
    assert rid not in repo_manager.get_repos()


def test_envelope_creation_response_carries_no_plaintext_name(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    repo_name = "env-stealth"
    rid = repo_to_rid(repo_name)

    resp = client.post(
        "/api/documents/collection",
        json={"rid": rid, "e": make_envelope({"repo": repo_name}, "e2e-password")},
    )

    assert resp.status_code == 200, resp.text
    assert resp.json().get("success") is True
    assert repo_name not in resp.text
    assert repo_name in repo_manager.get_repos()


def test_resolved_rid_wins_over_envelope_with_different_name(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    repo_name = "env-prio"
    created = client.post("/api/documents/collection", json={"name": repo_name})
    assert created.status_code == 200, created.text

    rid = repo_to_rid(repo_name)
    resp = client.post(
        "/api/documents/collection",
        json={"rid": rid, "e": make_envelope({"repo": "env-other"}, "e2e-password")},
    )

    assert resp.status_code == 400
    assert "уже существует" in resp.json()["detail"]
    assert "env-other" not in repo_manager.get_repos()


def test_envelope_with_non_string_repo_returns_400(tmp_path, monkeypatch):
    repo_manager, client, storage = _build_client(tmp_path, monkeypatch)
    rid = repo_to_rid("env-badtype")

    resp = client.post(
        "/api/documents/collection",
        json={"rid": rid, "e": make_envelope({"repo": 123}, "e2e-password")},
    )

    assert resp.status_code == 400, resp.text
    assert "env-badtype" not in [r for r in repo_manager.get_repos()]
