"""End-to-end postbox relay: HOME and WORK machines exchange files through the
real file_sync router using the real lgm_core client crypto.

Each machine talks to the in-process server over its own non-loopback
TestClient address, pins the server pubkey fetched from /api/auth/pubkey, and
seals every call with a fresh RelaySession ephemeral — so the server must
relay between two independent ephemeral sessions (writer's != reader's).
"""
import base64
import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from lgm_core.client import repo_to_rid
from lgm_core.relay_crypto import RELAY_AAD_POSTBOX, RelaySession, decode_pub_b64

from app.core import hybrid_crypto as hc
from app.core import sync_envelope
from app.core.repo_manager import RepoManager
from app.routers import file_sync as file_sync_router_mod
from tests import _harness

REPO = "onyx"
RID = repo_to_rid(REPO)
HOME_ADDR = ("192.168.1.10", 51000)
WORK_ADDR = ("10.20.30.40", 52000)


@pytest.fixture(autouse=True)
def _reset_relay_globals():
    yield
    file_sync_router_mod.server_private_key = None
    file_sync_router_mod.relay_key = None
    sync_envelope.server_private_key = None


def _build_server(tmp_path: Path):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    rm = RepoManager(storage)
    server_priv = hc.load_or_create_server_key(storage / ".lgm" / "server_x25519.key")
    relay_key = hc.load_or_create_relay_key(storage / ".lgm" / "relay.key")
    sync_envelope.server_private_key = server_priv
    file_sync_router_mod.repo_manager = rm
    file_sync_router_mod.system_logger = None
    file_sync_router_mod.server_private_key = server_priv
    file_sync_router_mod.relay_key = relay_key
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    app.include_router(file_sync_router_mod.router)
    return app


def _machine(app, addr) -> TestClient:
    async def scoped(scope, receive, send):
        if scope["type"] in ("http", "websocket"):
            scope = dict(scope, client=addr)
        await app(scope, receive, send)

    return TestClient(scoped)


@pytest.fixture
def postbox(tmp_path: Path):
    app = _build_server(tmp_path)
    home = _machine(app, HOME_ADDR)
    work = _machine(app, WORK_ADDR)
    pub = home.get("/api/auth/pubkey")
    assert pub.status_code == 200, pub.text
    return home, work, decode_pub_b64(pub.json()["pub"])


def _seal_upload(server_pub: bytes, path: str, data: bytes,
                 aad: bytes = RELAY_AAD_POSTBOX):
    session = RelaySession(server_pub)
    meta_plain = json.dumps({"path": path, "plain_size": len(data)},
                            separators=(",", ":")).encode("utf-8")
    meta = base64.b64encode(session.seal(meta_plain, aad)).decode("ascii")
    return session, meta, session.seal(data, aad)


def _upload(client: TestClient, k: str, meta: str, blob: bytes):
    return client.post(
        "/api/documents/attachment-upload",
        data={"rid": RID, "k": k, "meta": meta},
        files={"attachment": ("file.bin", blob, "application/octet-stream")},
    )


def _send(client: TestClient, server_pub: bytes, path: str, data: bytes) -> str:
    session, meta, sealed = _seal_upload(server_pub, path, data)
    resp = _upload(client, session.epk_b64(), meta, sealed)
    assert resp.status_code == 200, resp.text
    return resp.json()["id"]


def _fetch(client: TestClient, server_pub: bytes, item_id: str) -> bytes:
    session = RelaySession(server_pub)
    resp = client.get(
        "/api/documents/attachment-get",
        params={"rid": RID, "id": item_id},
        headers={"X-LGM-Epk": session.epk_b64()},
    )
    assert resp.status_code == 200, resp.text
    return session.open(resp.content, RELAY_AAD_POSTBOX)


def _list(client: TestClient) -> list:
    resp = client.get("/api/documents/attachment-list", params={"rid": RID})
    assert resp.status_code == 200, resp.text
    return resp.json()["items"]


def test_home_upload_reaches_work_reader(postbox):
    home, work, server_pub = postbox
    plaintext = b"# MR !21\n\nhome agent reply\n" * 32

    item_id = _send(home, server_pub, "mr-replies/mr-!21.md", plaintext)

    items = _list(work)
    assert [it["id"] for it in items] == [item_id]
    assert items[0]["path"] == "mr-replies/mr-!21.md"
    assert items[0]["plain_size"] == len(plaintext)
    assert _fetch(work, server_pub, item_id) == plaintext


def test_work_upload_reaches_home_reader(postbox):
    home, work, server_pub = postbox
    plaintext = b"work-side review notes\n" * 64

    item_id = _send(work, server_pub, "mr-notes/mr-!21.md", plaintext)

    items = _list(home)
    assert [it["id"] for it in items] == [item_id]
    assert items[0]["path"] == "mr-notes/mr-!21.md"
    assert items[0]["plain_size"] == len(plaintext)
    assert _fetch(home, server_pub, item_id) == plaintext


def test_upload_with_wrong_aad_rejected(postbox):
    home, work, server_pub = postbox
    session, meta, sealed = _seal_upload(
        server_pub, "mr-notes/mr-!21.md", b"payload", aad=b"lgm/v3/relay/buffer")

    resp = _upload(work, session.epk_b64(), meta, sealed)
    assert resp.status_code == 400
    assert _list(work) == []


def test_upload_with_tampered_body_rejected(postbox):
    home, work, server_pub = postbox
    session, meta, sealed = _seal_upload(server_pub, "mr-notes/mr-!21.md", b"payload")
    sealed = bytearray(sealed)
    sealed[-1] ^= 0xFF

    resp = _upload(work, session.epk_b64(), meta, bytes(sealed))
    assert resp.status_code == 400
    assert _list(work) == []


def test_upload_with_tampered_meta_rejected(postbox):
    home, work, server_pub = postbox
    session, meta, sealed = _seal_upload(server_pub, "mr-notes/mr-!21.md", b"payload")
    tampered = bytearray(base64.b64decode(meta))
    tampered[-1] ^= 0xFF

    resp = _upload(work, session.epk_b64(),
                   base64.b64encode(bytes(tampered)).decode("ascii"), sealed)
    assert resp.status_code == 400
    assert _list(work) == []


def test_upload_missing_k_or_meta_rejected(postbox):
    home, work, server_pub = postbox
    session, meta, sealed = _seal_upload(server_pub, "mr-notes/mr-!21.md", b"payload")

    missing_k = _upload(work, "", meta, sealed)
    assert missing_k.status_code == 400

    missing_meta = _upload(work, session.epk_b64(), "", sealed)
    assert missing_meta.status_code == 400
    assert _list(work) == []


def test_download_without_epk_fails_closed(postbox):
    home, work, server_pub = postbox
    plaintext = b"secret reply payload" * 16
    item_id = _send(home, server_pub, "mr-replies/mr-!21.md", plaintext)

    resp = work.get("/api/documents/attachment-get", params={"rid": RID, "id": item_id})
    assert resp.status_code == 400
    assert plaintext not in resp.content


def test_ack_removes_item(postbox):
    home, work, server_pub = postbox
    plaintext = b"ack me\n" * 8
    item_id = _send(home, server_pub, "mr-replies/mr-!21.md", plaintext)

    ack = work.delete("/api/documents/attachment-ack", params={"rid": RID, "id": item_id})
    assert ack.status_code == 200, ack.text
    assert ack.json()["deleted"] is True

    assert _list(work) == []
    gone = work.get(
        "/api/documents/attachment-get",
        params={"rid": RID, "id": item_id},
        headers={"X-LGM-Epk": RelaySession(server_pub).epk_b64()},
    )
    assert gone.status_code == 404
