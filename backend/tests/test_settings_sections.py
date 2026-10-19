"""POST /api/settings must accept per-section partial updates (S16).

The old contract required the whole document (general+git+ui), forcing every
client into read-modify-write: two writers clobbered each other and the SPA
(which only has general+git tabs) got a 422 and persisted nothing. The new
contract: any subset of sections, merged per key, atomically on the server.
"""

import json
import threading
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.settings_manager import SettingsManager
from app.routers import settings as settings_mod


def _build_client(tmp_path: Path) -> TestClient:
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    settings_mod.settings_manager = SettingsManager(storage)
    app = FastAPI()
    app.include_router(settings_mod.router)
    return TestClient(app)


def _on_disk(tmp_path: Path) -> dict:
    path = tmp_path / "storage" / ".lgm" / "settings.json"
    return json.loads(path.read_text(encoding="utf-8"))


def test_partial_post_persists_sent_section_only(tmp_path: Path):
    client = _build_client(tmp_path)
    seeded = client.post("/api/settings", json={
        "general": {"default_repo": "seed-repo"},
        "git": {"user_name": "Seed"},
        "ui": {"theme": "light"},
    })
    assert seeded.status_code == 200, seeded.text

    resp = client.post("/api/settings", json={"general": {"default_repo": "new-repo"}})

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["success"] is True, body
    assert body["settings"]["general"]["default_repo"] == "new-repo"
    assert body["settings"]["git"]["user_name"] == "Seed"
    assert body["settings"]["ui"]["theme"] == "light"
    on_disk = _on_disk(tmp_path)
    assert on_disk["general"]["default_repo"] == "new-repo"
    assert on_disk["git"]["user_name"] == "Seed"
    assert on_disk["ui"]["theme"] == "light"


def test_single_key_update_keeps_other_keys_in_section(tmp_path: Path):
    client = _build_client(tmp_path)
    seeded = client.post("/api/settings", json={
        "general": {"default_repo": "r"},
        "git": {"user_name": "Bot", "user_email": "bot@example.com", "port": 9999},
        "ui": {"theme": "dark"},
    })
    assert seeded.status_code == 200, seeded.text

    resp = client.post("/api/settings", json={"git": {"port": 1234}})

    assert resp.status_code == 200, resp.text
    git = resp.json()["settings"]["git"]
    assert git["port"] == 1234
    assert git["user_name"] == "Bot"
    assert git["user_email"] == "bot@example.com"
    assert _on_disk(tmp_path)["git"] == git


def test_concurrent_writes_to_same_section_do_not_clobber(tmp_path: Path):
    client = _build_client(tmp_path)
    seeded = client.post("/api/settings", json={
        "general": {"default_repo": "r"},
        "git": {"user_name": "A0", "user_email": "B0", "port": 9999},
        "ui": {"theme": "dark"},
    })
    assert seeded.status_code == 200, seeded.text

    rounds = 5
    failures = []
    barrier = threading.Barrier(2)

    def writer(key: str, label: str):
        for i in range(rounds):
            try:
                barrier.wait(timeout=10)
                resp = client.post("/api/settings", json={"git": {key: f"{label}{i}"}})
                if resp.status_code != 200:
                    failures.append(f"{key} round {i}: HTTP {resp.status_code} {resp.text}")
            except Exception as exc:
                failures.append(f"{key} round {i}: {exc!r}")

    threads = [
        threading.Thread(target=writer, args=("user_name", "A")),
        threading.Thread(target=writer, args=("user_email", "B")),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert not failures, failures
    final = client.get("/api/settings").json()
    assert final["git"]["user_name"] == "A4", final["git"]
    assert final["git"]["user_email"] == "B4", final["git"]
    assert final["git"]["port"] == 9999, final["git"]


def test_full_document_payload_still_works(tmp_path: Path):
    client = _build_client(tmp_path)
    full = {
        "general": {"default_repo": "full-repo", "auto_sync": False},
        "git": {"user_name": "Full", "user_email": "full@example.com"},
        "ui": {"theme": "light", "font_size": 16},
    }
    resp = client.post("/api/settings", json=full)

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["success"] is True, body
    settings = body["settings"]
    assert settings["general"]["default_repo"] == "full-repo"
    assert settings["general"]["auto_sync"] is False
    assert settings["git"]["user_name"] == "Full"
    assert settings["ui"]["theme"] == "light"
    assert settings["ui"]["font_size"] == 16
    assert _on_disk(tmp_path) == settings


def test_unknown_top_level_section_is_rejected(tmp_path: Path):
    client = _build_client(tmp_path)
    resp = client.post("/api/settings", json={"generel": {"default_repo": "typo"}})

    assert resp.status_code == 422, resp.text
    assert client.get("/api/settings").json()["general"]["default_repo"] == "default"


def test_empty_payload_is_rejected(tmp_path: Path):
    client = _build_client(tmp_path)
    resp = client.post("/api/settings", json={})

    assert resp.status_code == 422, resp.text
