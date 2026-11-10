"""
End-to-end test of /api/documents/* deps flow.

Simulates both sides:
  Dome: posts a manifest (encrypted blob) -> waits for response -> applies
  Work: pulls the manifest -> uploads an archive -> request gets cleaned up

The server terminates the crypto: it decrypts on write, stores at rest under
the relay key and re-seals on read, so the contract under test is that the
PLAINTEXT round-trips (not the raw wire bytes), plus the request/response
lifecycle.
"""
import json
import os
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.bundle_crypto import decrypt_dump_bytes, encrypt_bundle_bytes
from app.core.repo_manager import RepoManager
from app.routers import deps as deps_router_mod
from tests import _harness

PASSWORD = "deps-flow-test-password"


def _make_client(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )

    deps_router_mod.repo_manager = rm
    deps_router_mod.system_logger = None
    app.include_router(deps_router_mod.router)
    return TestClient(app), storage


# ─────────────────────────────────────────────────────────────────────────────
# Happy path: full request → respond → fetch → ack lifecycle
# ─────────────────────────────────────────────────────────────────────────────

def test_deps_full_lifecycle(tmp_path: Path, monkeypatch):
    client, storage = _make_client(tmp_path, monkeypatch)
    repo = "onyx-platform"
    # Repo doesn't need to exist for deps endpoints — they only validate name.

    # 1. Dome posts a manifest
    manifest_plain = b"ENCRYPTED_MANIFEST_PAYLOAD" * 10
    fake_manifest = encrypt_bundle_bytes(manifest_plain, PASSWORD)
    resp = client.post(
        "/api/documents/submit",
        data={"rid": repo},
        files={"attachment": ("manifest.bin", fake_manifest, "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    request_id = body["id"]
    assert body["size"] == len(fake_manifest)

    # 2. Work sees it pending
    pending = client.get("/api/documents/queue", params={"rid": repo}).json()
    ids = [it["id"] for it in pending["items"]]
    assert request_id in ids

    # 3. Work downloads the manifest blob — the plaintext must round-trip
    got = client.get("/api/documents/queue-item", params={"rid": repo, "id": request_id})
    assert got.status_code == 200
    assert decrypt_dump_bytes(got.content, PASSWORD) == manifest_plain

    # 4. Work uploads a response (zip-like blob)
    archive_plain = b"ENCRYPTED_ARCHIVE_PAYLOAD" * 100
    fake_archive = encrypt_bundle_bytes(archive_plain, PASSWORD)
    rr = client.post(
        "/api/documents/fulfill",
        data={"rid": repo, "request_id": request_id},
        files={"attachment": ("archive.bin", fake_archive, "application/octet-stream")},
    )
    assert rr.status_code == 200, rr.text
    response_id = rr.json()["id"]

    # the request survives the fulfill — only the Dome ack completes the handshake
    pending2 = client.get("/api/documents/queue", params={"rid": repo}).json()
    assert request_id in [it["id"] for it in pending2["items"]]

    # 6. Dome sees the response
    resps = client.get("/api/documents/ready", params={"rid": repo}).json()
    assert response_id in [it["id"] for it in resps["items"]]

    # 7. Dome fetches the response — the plaintext must round-trip
    fetched = client.get("/api/documents/ready-item", params={"rid": repo, "id": response_id})
    assert fetched.status_code == 200
    assert decrypt_dump_bytes(fetched.content, PASSWORD) == archive_plain

    # Dome ACKs — server deletes the response and the answered request
    ack = client.delete("/api/documents/ack", params={"rid": repo, "id": response_id})
    assert ack.status_code == 200
    final = client.get("/api/documents/ready", params={"rid": repo}).json()
    assert response_id not in [it["id"] for it in final["items"]]
    finalq = client.get("/api/documents/queue", params={"rid": repo}).json()
    assert request_id not in [it["id"] for it in finalq["items"]]


# ─────────────────────────────────────────────────────────────────────────────
# Validation: malicious / invalid input
# ─────────────────────────────────────────────────────────────────────────────

def test_deps_rejects_path_traversal(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    # Attempted path traversal in repo name
    for bad in ["../etc", "foo/bar", "x\\y", "", "."]:
        resp = client.get("/api/documents/queue", params={"rid": bad})
        assert resp.status_code == 400, f"Should reject {bad!r}, got {resp.status_code}"


def test_deps_rejects_bad_id(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    for bad in ["../something", "a/b", "", "x" * 100]:
        resp = client.get("/api/documents/queue-item", params={"rid": "onyx", "id": bad})
        assert resp.status_code == 400, f"Should reject id {bad!r}"


def test_deps_rejects_empty_payload(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    resp = client.post(
        "/api/documents/submit",
        data={"rid": "onyx"},
        files={"attachment": ("x.bin", b"", "application/octet-stream")},
    )
    assert resp.status_code == 400


def test_deps_fetch_unknown_returns_404(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    resp = client.get("/api/documents/ready-item", params={"rid": "onyx", "id": "deadbeef"})
    assert resp.status_code == 404
    resp = client.get("/api/documents/queue-item", params={"rid": "onyx", "id": "deadbeef"})
    assert resp.status_code == 404


# ─────────────────────────────────────────────────────────────────────────────
# Multiple parallel requests don't conflict
# ─────────────────────────────────────────────────────────────────────────────

def test_deps_multiple_requests_each_has_unique_id(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    ids = []
    for i in range(5):
        blob = encrypt_bundle_bytes(f"payload{i}".encode() * 100, PASSWORD)
        r = client.post(
            "/api/documents/submit",
            data={"rid": "onyx"},
            files={"attachment": (f"m{i}.bin", blob, "application/octet-stream")},
        )
        assert r.status_code == 200
        ids.append(r.json()["id"])
    # All unique
    assert len(set(ids)) == len(ids)
    pending = client.get("/api/documents/queue", params={"rid": "onyx"}).json()
    assert len([x for x in pending["items"] if x["id"] in ids]) == 5


# ─────────────────────────────────────────────────────────────────────────────
# X-Doc-Ref header: alternative to ?rid= query param
# ─────────────────────────────────────────────────────────────────────────────

def test_deps_queue_via_x_doc_ref_header(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    repo = "onyx-platform"
    fake_manifest = encrypt_bundle_bytes(b"ENCRYPTED-MANIFEST-PAYLOAD" * 10, PASSWORD)
    client.post(
        "/api/documents/submit",
        data={"rid": repo},
        files={"attachment": ("manifest.bin", fake_manifest, "application/octet-stream")},
    )

    resp = client.get("/api/documents/queue", headers={"X-Doc-Ref": repo})
    assert resp.status_code == 200, resp.text
    assert len(resp.json()["items"]) >= 1

    resp = client.get("/api/documents/queue")
    assert resp.status_code == 400


# S4: lossless handshake — partial fulfillment, request_id validation, TTL race

def _submit_request(client: TestClient, repo: str) -> str:
    fake_manifest = encrypt_bundle_bytes(b"ENCRYPTED_MANIFEST_PAYLOAD" * 10, PASSWORD)
    r = client.post(
        "/api/documents/submit",
        data={"rid": repo},
        files={"attachment": ("manifest.bin", fake_manifest, "application/octet-stream")},
    )
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _fulfill(client: TestClient, repo: str, request_id: str, residual: str = ""):
    fake_archive = encrypt_bundle_bytes(b"ENCRYPTED_ARCHIVE_PAYLOAD" * 100, PASSWORD)
    data = {"rid": repo, "request_id": request_id}
    if residual:
        data["residual"] = residual
    return client.post(
        "/api/documents/fulfill",
        data=data,
        files={"attachment": ("archive.bin", fake_archive, "application/octet-stream")},
    )


def _age_blob(path: Path, age_seconds: float) -> None:
    old = time.time() - age_seconds
    os.utime(path, (old, old))


def test_deps_partial_fulfillment_keeps_request_and_carries_residual(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    repo = "onyx-platform"
    request_id = _submit_request(client, repo)

    residual = [
        {"ecosystem": "gradle", "group": "com.corp", "name": "secret-lib",
         "version": "1.2", "classifier": ""},
        "npm/@scope/pkg@3.1.0",
    ]
    rr = _fulfill(client, repo, request_id, residual=json.dumps(residual))
    assert rr.status_code == 200, rr.text
    response_id = rr.json()["id"]

    # the request blob survives a partial shipment
    pending = client.get("/api/documents/queue", params={"rid": repo}).json()
    assert request_id in [it["id"] for it in pending["items"]]

    # the response meta carries the residual missing coordinates
    assert rr.json()["residual"] == residual
    resps = client.get("/api/documents/ready", params={"rid": repo}).json()
    item = next(it for it in resps["items"] if it["id"] == response_id)
    assert item["request_id"] == request_id
    assert item["residual"] == residual

    # acking a partial response removes the response but keeps the request outstanding
    ack = client.delete("/api/documents/ack", params={"rid": repo, "id": response_id})
    assert ack.status_code == 200
    resps2 = client.get("/api/documents/ready", params={"rid": repo}).json()
    assert response_id not in [it["id"] for it in resps2["items"]]
    pending2 = client.get("/api/documents/queue", params={"rid": repo}).json()
    assert request_id in [it["id"] for it in pending2["items"]]


def test_deps_complete_fulfillment_deletes_request_only_after_ack(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    repo = "onyx-platform"
    request_id = _submit_request(client, repo)

    rr = _fulfill(client, repo, request_id)
    assert rr.status_code == 200, rr.text
    response_id = rr.json()["id"]

    # the request outlives the fulfill itself — only the Dome ack completes the handshake
    pending = client.get("/api/documents/queue", params={"rid": repo}).json()
    assert request_id in [it["id"] for it in pending["items"]]

    ack = client.delete("/api/documents/ack", params={"rid": repo, "id": response_id})
    assert ack.status_code == 200
    pending2 = client.get("/api/documents/queue", params={"rid": repo}).json()
    assert request_id not in [it["id"] for it in pending2["items"]]


def test_deps_fulfill_unknown_request_id_is_typed_404_and_stores_nothing(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    rr = _fulfill(client, "onyx", "deadbeef")
    assert rr.status_code == 404, rr.text
    assert rr.json()["detail"]["error"] == "request_not_found"
    resps = client.get("/api/documents/ready", params={"rid": "onyx"}).json()
    assert resps["items"] == []


def test_deps_fulfill_request_id_mismatched_repo_is_rejected(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    request_id = _submit_request(client, "repo-a")

    rr = _fulfill(client, "repo-b", request_id)
    assert rr.status_code == 404, rr.text
    assert rr.json()["detail"]["error"] == "request_not_found"

    resps = client.get("/api/documents/ready", params={"rid": "repo-b"}).json()
    assert resps["items"] == []
    pending = client.get("/api/documents/queue", params={"rid": "repo-a"}).json()
    assert request_id in [it["id"] for it in pending["items"]]


def test_deps_fulfill_rejects_malformed_residual(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    repo = "onyx-platform"
    request_id = _submit_request(client, repo)

    for bad in ["not-json", json.dumps({"not": "a list"}), json.dumps([1, 2])]:
        rr = _fulfill(client, repo, request_id, residual=bad)
        assert rr.status_code == 400, f"Should reject residual {bad!r}, got {rr.status_code}"

    resps = client.get("/api/documents/ready", params={"rid": repo}).json()
    assert resps["items"] == []


def test_deps_queue_item_reaped_by_ttl_is_typed_gone(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    repo = "onyx-platform"
    request_id = _submit_request(client, repo)

    got = client.get("/api/documents/queue-item", params={"rid": repo, "id": request_id})
    assert got.status_code == 200

    # the blob ages past the TTL and the lazy cleanup reaps it after the listing
    _age_blob(deps_router_mod._requests_dir(repo) / f"{request_id}.bin", 8 * 24 * 3600)
    listing = client.get("/api/documents/queue", params={"rid": repo}).json()
    assert request_id not in [it["id"] for it in listing["items"]]

    gone = client.get("/api/documents/queue-item", params={"rid": repo, "id": request_id})
    assert gone.status_code == 410
    assert gone.json()["detail"]["error"] == "expired"

    missing = client.get("/api/documents/queue-item", params={"rid": repo, "id": "deadbeef"})
    assert missing.status_code == 404
    assert missing.json()["detail"]["error"] == "request_not_found"


def test_deps_ready_item_reaped_by_ttl_is_typed_gone(tmp_path: Path, monkeypatch):
    client, _ = _make_client(tmp_path, monkeypatch)
    repo = "onyx-platform"
    request_id = _submit_request(client, repo)
    response_id = _fulfill(client, repo, request_id).json()["id"]

    _age_blob(deps_router_mod._responses_dir(repo) / f"{response_id}.bin", 8 * 24 * 3600)
    listing = client.get("/api/documents/ready", params={"rid": repo}).json()
    assert response_id not in [it["id"] for it in listing["items"]]

    gone = client.get("/api/documents/ready-item", params={"rid": repo, "id": response_id})
    assert gone.status_code == 410
    assert gone.json()["detail"]["error"] == "expired"
