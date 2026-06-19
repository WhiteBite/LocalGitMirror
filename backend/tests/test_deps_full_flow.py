"""
End-to-end test of /api/deps/* flow.

Simulates both sides:
  Dome: posts a manifest (encrypted blob) -> waits for response -> applies
  Work: pulls the manifest -> uploads an archive -> request gets cleaned up

The server must NEVER inspect the encrypted payloads. Tests below verify
that, plus the request/response lifecycle.
"""
import json
import time
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.repo_manager import RepoManager
from app.routers import api as api_router
from app.routers import deps as deps_router_mod


def _make_client(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    rm = RepoManager(storage)
    api_router.repo_manager = rm
    api_router.git_handler = None
    api_router.git_workspace = None
    api_router.shared_manager = None
    api_router.system_logger = None
    api_router.config = {"git_port": 0, "web_port": 0, "storage_path": storage}

    deps_router_mod.repo_manager = rm
    deps_router_mod.system_logger = None

    app = FastAPI()
    app.include_router(api_router.router)
    app.include_router(deps_router_mod.router)
    return TestClient(app), storage


# ─────────────────────────────────────────────────────────────────────────────
# Happy path: full request → respond → fetch → ack lifecycle
# ─────────────────────────────────────────────────────────────────────────────

def test_deps_full_lifecycle(tmp_path: Path):
    client, storage = _make_client(tmp_path)
    repo = "onyx-platform"
    # Repo doesn't need to exist for deps endpoints — they only validate name.

    # 1. Dome posts a manifest
    fake_manifest = b"\x01ENCRYPTED-MANIFEST-PAYLOAD-PRETEND-IS-CIPHERTEXT" * 10
    resp = client.post(
        "/api/deps/request",
        data={"repo": repo},
        files={"attachment": ("manifest.bin", fake_manifest, "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    request_id = body["id"]
    assert body["size"] == len(fake_manifest)

    # 2. Work sees it pending
    pending = client.get("/api/deps/pending", params={"repo": repo}).json()
    ids = [it["id"] for it in pending["items"]]
    assert request_id in ids

    # 3. Work downloads the manifest blob — server returns bytes-for-bytes
    got = client.get("/api/deps/manifest", params={"repo": repo, "id": request_id})
    assert got.status_code == 200
    assert got.content == fake_manifest, "Server must NOT mutate encrypted payload"

    # 4. Work uploads a response (zip-like blob)
    fake_archive = b"\x01ENCRYPTED-ARCHIVE-CONTENT" * 1000
    rr = client.post(
        "/api/deps/respond",
        data={"repo": repo, "request_id": request_id},
        files={"attachment": ("archive.bin", fake_archive, "application/octet-stream")},
    )
    assert rr.status_code == 200, rr.text
    response_id = rr.json()["id"]

    # 5. Original request was deleted (one-shot)
    pending2 = client.get("/api/deps/pending", params={"repo": repo}).json()
    assert request_id not in [it["id"] for it in pending2["items"]]

    # 6. Dome sees the response
    resps = client.get("/api/deps/responses", params={"repo": repo}).json()
    assert response_id in [it["id"] for it in resps["items"]]

    # 7. Dome fetches the response — same bytes back
    fetched = client.get("/api/deps/fetch", params={"repo": repo, "id": response_id})
    assert fetched.status_code == 200
    assert fetched.content == fake_archive

    # 8. Dome ACKs — server deletes the response
    ack = client.delete("/api/deps/ack", params={"repo": repo, "id": response_id})
    assert ack.status_code == 200
    final = client.get("/api/deps/responses", params={"repo": repo}).json()
    assert response_id not in [it["id"] for it in final["items"]]


# ─────────────────────────────────────────────────────────────────────────────
# Validation: malicious / invalid input
# ─────────────────────────────────────────────────────────────────────────────

def test_deps_rejects_path_traversal(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    # Attempted path traversal in repo name
    for bad in ["../etc", "foo/bar", "x\\y", "", "."]:
        resp = client.get("/api/deps/pending", params={"repo": bad})
        assert resp.status_code == 400, f"Should reject {bad!r}, got {resp.status_code}"


def test_deps_rejects_bad_id(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    for bad in ["../something", "a/b", "", "x" * 100]:
        resp = client.get("/api/deps/manifest", params={"repo": "onyx", "id": bad})
        assert resp.status_code == 400, f"Should reject id {bad!r}"


def test_deps_rejects_empty_payload(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    resp = client.post(
        "/api/deps/request",
        data={"repo": "onyx"},
        files={"attachment": ("x.bin", b"", "application/octet-stream")},
    )
    assert resp.status_code == 400


def test_deps_fetch_unknown_returns_404(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    resp = client.get("/api/deps/fetch", params={"repo": "onyx", "id": "deadbeef"})
    assert resp.status_code == 404
    resp = client.get("/api/deps/manifest", params={"repo": "onyx", "id": "deadbeef"})
    assert resp.status_code == 404


# ─────────────────────────────────────────────────────────────────────────────
# Multiple parallel requests don't conflict
# ─────────────────────────────────────────────────────────────────────────────

def test_deps_multiple_requests_each_has_unique_id(tmp_path: Path):
    client, _ = _make_client(tmp_path)
    ids = []
    for i in range(5):
        r = client.post(
            "/api/deps/request",
            data={"repo": "onyx"},
            files={"attachment": (f"m{i}.bin", f"payload{i}".encode() * 100, "application/octet-stream")},
        )
        assert r.status_code == 200
        ids.append(r.json()["id"])
    # All unique
    assert len(set(ids)) == len(ids)
    pending = client.get("/api/deps/pending", params={"repo": "onyx"}).json()
    assert len([x for x in pending["items"] if x["id"] in ids]) == 5
