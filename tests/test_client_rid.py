"""MirrorClient sends the hashed repo identifier (rid), never the plain repo name."""
import base64
import hashlib

from lgm_core.client import MirrorClient, repo_to_rid

REPO = "onyx"
RID = hashlib.sha256(b"lgm-repo-id:onyx").hexdigest()[:16]


class _FakeSession:
    """Crypto-free relay session stand-in: rid tests do not exercise ECIES."""

    def epk_b64(self) -> str:
        return "fake-epk"

    def seal(self, plaintext: bytes, aad: bytes) -> bytes:
        return b"sealed:" + plaintext

    def open(self, blob: bytes, aad: bytes) -> bytes:
        return blob


def test_repo_to_rid_matches_shared_scheme():
    assert repo_to_rid(REPO) == RID
    assert len(RID) == 16


def test_file_sync_send_puts_hashed_rid_in_form_fields(monkeypatch):
    captured = {}

    def fake_post_multipart(path, fields, files, timeout=None):
        captured["path"] = path
        captured["fields"] = fields
        captured["files"] = files
        return {"success": True, "id": "i1"}

    client = MirrorClient(base_url="http://mirror", api_key="k")
    monkeypatch.setattr(client, "_post_multipart", fake_post_multipart)
    monkeypatch.setattr(client, "_relay_session", lambda: _FakeSession())
    client.file_sync_send(REPO, "mr-replies/mr-!1.md", 10, b"data")
    assert captured["path"] == "/api/documents/attachment-upload"
    assert captured["fields"]["rid"] == RID
    assert set(captured["fields"]) == {"rid", "k", "meta"}
    meta_plain = base64.b64decode(captured["fields"]["meta"])
    assert b'"path":"mr-replies/mr-!1.md"' in meta_plain
    assert b'"plain_size":10' in meta_plain
    assert captured["files"]["attachment"][1] == b"sealed:data"


def test_deps_request_and_respond_put_hashed_rid_in_form_fields(monkeypatch):
    captured = []

    def fake_post_multipart(path, fields, files, timeout=None):
        captured.append((path, fields))
        return {"success": True, "id": "i1"}

    client = MirrorClient(base_url="http://mirror", api_key="k")
    monkeypatch.setattr(client, "_post_multipart", fake_post_multipart)
    client.deps_request(REPO, b"manifest")
    client.deps_respond(REPO, "req1", b"archive")
    assert [c[0] for c in captured] == ["/api/documents/submit", "/api/documents/fulfill"]
    assert all(c[1]["rid"] == RID for c in captured)


def test_get_and_delete_requests_carry_hashed_rid_in_query(monkeypatch):
    captured = {}

    def fake_get_json(path, timeout=None):
        captured.setdefault("get", []).append(path)
        return {"items": []}

    def fake_download_bytes(path, headers=None):
        captured.setdefault("download", []).append(path)
        return b"blob"

    def fake_delete_json(path):
        captured.setdefault("delete", []).append(path)
        return {"success": True}

    client = MirrorClient(base_url="http://mirror", api_key="k")
    monkeypatch.setattr(client, "_get_json", fake_get_json)
    monkeypatch.setattr(client, "_download_bytes", fake_download_bytes)
    monkeypatch.setattr(client, "_delete_json", fake_delete_json)
    monkeypatch.setattr(client, "_relay_session", lambda: _FakeSession())

    client.deps_pending(REPO)
    client.deps_responses(REPO)
    client.file_sync_list(REPO)
    client.deps_manifest(REPO, "i1")
    client.deps_fetch(REPO, "i1")
    client.file_sync_fetch(REPO, "i1")
    client.deps_ack(REPO, "i1")
    client.file_sync_ack(REPO, "i1")

    for path in captured["get"] + captured["download"] + captured["delete"]:
        assert f"rid={RID}" in path
        assert f"rid={REPO}" not in path
