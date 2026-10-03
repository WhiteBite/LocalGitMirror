"""One-shot negotiation (/api/documents/negotiate): create-if-missing plus
refs and known-commits in a single sealed round-trip from the work PC."""

import base64
import json
import subprocess
import time
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from fastapi.testclient import TestClient

from app.core import hybrid_crypto as hc
from app.core import sync_envelope
from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_post, make_envelope, parse_envelope

PASSWORD = "negotiate-pw"


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _make_client(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
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
    return TestClient(app), storage, rm


def _negotiate(client, payload: dict):
    return envelope_post(client, "/api/documents/negotiate", payload, PASSWORD)


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

    def open_env(self, b64: str) -> dict:
        key = hc._derive(self._shared, self.epk, hc.INFO_ENV_RESP)
        return json.loads(hc._open(key, base64.b64decode(b64)).decode("utf-8"))


def test_negotiate_creates_repo_and_matches_list_refs(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"neg-create-{int(time.time() * 1000)}"

    resp = _negotiate(client, {"repo": repo})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body) == {"e"}
    inner = parse_envelope(body, PASSWORD)
    assert inner["success"] is True
    assert inner["created"] is True
    assert inner["repo"] == repo
    assert repo in rm.get_repos()
    assert (storage / repo / ".git").exists()
    assert (storage / ".lgm" / "bare" / f"{repo}.git").exists()

    list_resp = envelope_post(client, "/api/documents/list", {"repo": repo}, PASSWORD)
    assert list_resp.status_code == 200, list_resp.text
    list_inner = parse_envelope(list_resp.json(), PASSWORD)
    assert inner["refs"] == list_inner["refs"]
    assert inner["head"] == list_inner["head"]


def test_negotiate_second_call_reports_not_created(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"neg-exists-{int(time.time() * 1000)}"

    first = parse_envelope(_negotiate(client, {"repo": repo}).json(), PASSWORD)
    assert first["created"] is True

    second_resp = _negotiate(client, {"repo": repo})
    assert second_resp.status_code == 200, second_resp.text
    second = parse_envelope(second_resp.json(), PASSWORD)
    assert second["created"] is False
    assert second["success"] is True
    assert second["refs"] == first["refs"]


def test_negotiate_known_reports_only_present_commits(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"neg-known-{int(time.time() * 1000)}"
    parse_envelope(_negotiate(client, {"repo": repo}).json(), PASSWORD)

    ws = storage / repo
    initial = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    (ws / "f.txt").write_text("v1\n", encoding="utf-8")
    _run_git(ws, "add", "f.txt")
    _run_git(ws, "commit", "-m", "second")
    second = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    absent = "f" * 40

    resp = _negotiate(client, {"repo": repo, "commits": [initial, absent, second]})
    assert resp.status_code == 200, resp.text
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner["known"] == [initial, second]


def test_negotiate_missing_repo_returns_400(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    resp = client.post(
        "/api/documents/negotiate", json={"e": make_envelope({"commits": []}, PASSWORD)}
    )
    assert resp.status_code == 400
    assert resp.json()["detail"] == "Repository name is required"


def test_negotiate_bad_envelope_returns_400(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    resp = client.post("/api/documents/negotiate", json={"e": "not-an-envelope"})
    assert resp.status_code == 400


def test_negotiate_leaves_legacy_list_and_check_working(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"neg-legacy-{int(time.time() * 1000)}"
    parse_envelope(_negotiate(client, {"repo": repo}).json(), PASSWORD)

    ws = storage / repo
    head = _run_git(ws, "rev-parse", "HEAD").stdout.strip()

    list_resp = envelope_post(client, "/api/documents/list", {"repo": repo}, PASSWORD)
    assert list_resp.status_code == 200, list_resp.text
    assert parse_envelope(list_resp.json(), PASSWORD)["refs"]

    check_resp = envelope_post(
        client, "/api/documents/check", {"repo": repo, "commits": [head]}, PASSWORD
    )
    assert check_resp.status_code == 200, check_resp.text
    assert parse_envelope(check_resp.json(), PASSWORD)["known"] == [head]


def test_negotiate_v3_with_k_epk(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    monkeypatch.delenv("SYNC_PASSWORD", raising=False)
    server_priv = hc.load_or_create_server_key(storage / ".lgm" / "srv.key")
    monkeypatch.setattr(sync_envelope, "server_private_key", server_priv)

    repo = f"neg-v3-{int(time.time() * 1000)}"
    sender = _V3Client(hc.public_bytes(server_priv))

    resp = client.post(
        "/api/documents/negotiate",
        json={"e": sender.seal_env({"repo": repo}), "k": sender.epk_b64},
    )
    assert resp.status_code == 200, resp.text
    inner = sender.open_env(resp.json()["e"])
    assert inner["success"] is True
    assert inner["created"] is True
    assert repo in rm.get_repos()
