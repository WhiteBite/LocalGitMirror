"""SPA serving: loopback-only, API key injected at serve time (not baked into dist)."""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.routers import web as web_mod
from app.routers.web import LoopbackStaticFiles

INDEX_PAGE = "<html><head><title>SPA</title></head><body><div id=app></div></body></html>"


def _scoped_client(app, client_addr):
    async def scoped(scope, receive, send):
        if scope["type"] in ("http", "websocket"):
            scope = dict(scope, client=client_addr)
        await app(scope, receive, send)

    return TestClient(scoped)


@pytest.fixture()
def spa(tmp_path, monkeypatch):
    dist = tmp_path / "dist"
    assets = dist / "assets"
    assets.mkdir(parents=True)
    (dist / "index.html").write_text(INDEX_PAGE, encoding="utf-8")
    (assets / "app.js").write_text("console.log('spa')", encoding="utf-8")
    monkeypatch.setattr(web_mod, "INDEX_HTML", dist / "index.html")
    app = FastAPI()
    app.include_router(web_mod.router)
    app.mount("/assets", LoopbackStaticFiles(directory=str(assets)), name="assets")
    return app


def test_spa_index_404_for_non_loopback(spa):
    client = _scoped_client(spa, ("192.168.1.50", 40000))
    assert client.get("/").status_code == 404


def test_spa_index_200_for_loopback(spa):
    client = _scoped_client(spa, ("127.0.0.1", 50000))
    resp = client.get("/")
    assert resp.status_code == 200
    assert "<div id=app>" in resp.text


def test_spa_index_injects_api_key_for_loopback(spa, monkeypatch):
    monkeypatch.setenv("API_KEY", "loopback-secret-key")
    client = _scoped_client(spa, ("127.0.0.1", 50000))
    resp = client.get("/")
    assert 'window.__LGM_API_KEY__="loopback-secret-key"' in resp.text
    assert resp.text.index('window.__LGM_API_KEY__') < resp.text.index("</head>")


def test_spa_index_injects_empty_key_when_unset(spa, monkeypatch):
    monkeypatch.delenv("API_KEY", raising=False)
    client = _scoped_client(spa, ("127.0.0.1", 50000))
    assert 'window.__LGM_API_KEY__=""' in client.get("/").text


def test_spa_index_escapes_api_key(spa, monkeypatch):
    monkeypatch.setenv("API_KEY", 'a"<b>&c')
    client = _scoped_client(spa, ("127.0.0.1", 50000))
    body = client.get("/").text
    assert 'a&quot;&lt;b&gt;&amp;c' in body
    assert 'a"<b>' not in body


def test_spa_assets_404_for_non_loopback(spa):
    client = _scoped_client(spa, ("192.168.1.50", 40000))
    assert client.get("/assets/app.js").status_code == 404


def test_spa_assets_200_for_loopback(spa):
    client = _scoped_client(spa, ("127.0.0.1", 50000))
    resp = client.get("/assets/app.js")
    assert resp.status_code == 200
    assert resp.text == "console.log('spa')"


def test_spa_index_404_when_frontend_not_built(tmp_path, monkeypatch):
    monkeypatch.setattr(web_mod, "INDEX_HTML", tmp_path / "missing" / "index.html")
    app = FastAPI()
    app.include_router(web_mod.router)
    client = _scoped_client(app, ("127.0.0.1", 50000))
    assert client.get("/").status_code == 404
