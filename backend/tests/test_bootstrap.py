"""Bootstrap router: /api/tools/latest, /api/bootstrap, /api/plugin/repo.xml."""
import zipfile
import io
from pathlib import Path

from fastapi.testclient import TestClient

from tests import _harness
from app.core.repo_manager import RepoManager

PASSWORD = "bootstrap-test-pw"


def _build_client(tmp_path, monkeypatch, plugin_zip=True):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        '{"git": {"user_name": "Bot", "user_email": "bot@example.com"}}'
    )
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    monkeypatch.setenv("API_KEY", "bootstrap-test-key")
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    from app.routers import bootstrap
    _harness.inject(repo_manager=rm, system_logger=None, config={})
    return TestClient(app, headers={"Authorization": "Bearer bootstrap-test-key"})


def _make_plugin_zip(tmp_path):
    dist = tmp_path / "idea-plugin" / "build" / "distributions"
    dist.mkdir(parents=True, exist_ok=True)
    zip_path = dist / "localgitmirror-idea-plugin-0.999.0.zip"
    zip_path.write_bytes(b"fake-plugin-zip")
    return zip_path


def test_tools_latest_plain_zip(tmp_path, monkeypatch):
    client = _build_client(tmp_path, monkeypatch)
    resp = client.get("/api/tools/latest")
    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"] == "application/zip"
    zf = zipfile.ZipFile(io.BytesIO(resp.content))
    names = zf.namelist()
    assert "lgm.py" in names
    assert "lgm_mcp.py" in names
    assert "lgm_core/ops.py" in names
    assert "lgm_core/client.py" in names
    assert "lgm_core/crypto.py" in names
    assert "X-LGM-Version" in resp.headers


def test_tools_latest_encrypted(tmp_path, monkeypatch):
    from app.core.bundle_crypto import decrypt_dump_bytes
    client = _build_client(tmp_path, monkeypatch)
    resp = client.get("/api/tools/latest?enc=1")
    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"] == "application/octet-stream"
    plain = decrypt_dump_bytes(resp.content, PASSWORD)
    zf = zipfile.ZipFile(io.BytesIO(plain))
    assert "lgm.py" in zf.namelist()


def test_bootstrap_returns_powershell(tmp_path, monkeypatch):
    client = _build_client(tmp_path, monkeypatch)
    resp = client.get("/api/bootstrap")
    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"].startswith("text/plain")
    text = resp.text
    assert "param(" in text
    assert "Invoke-RestMethod" in text
    assert "AesGcm" in text
    assert "bootstrap-test-key" in text


def test_plugin_repo_xml(tmp_path, monkeypatch):
    dist_dir = tmp_path / "dist"
    dist_dir.mkdir()
    (dist_dir / "localgitmirror-idea-plugin-0.999.0.zip").write_bytes(b"fake")
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(dist_dir))
    client = _build_client(tmp_path, monkeypatch)
    resp = client.get("/api/plugin/repo.xml")
    assert resp.status_code == 200, resp.text
    assert 'id="localgitmirror.idea.orchestrator"' in resp.text
    assert 'version="0.999.0"' in resp.text
    assert "since-build" in resp.text


def test_work_pc_bundle_serves_zip_with_url_injection(tmp_path, monkeypatch):
    dist_dir = tmp_path / "dist"
    dist_dir.mkdir()
    (dist_dir / "localgitmirror-idea-plugin-0.999.0.zip").write_bytes(b"fake-plugin")
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(dist_dir))
    from app.routers import bootstrap as bootstrap_mod
    monkeypatch.setattr(
        "app.routers.plugin._dist_dir",
        lambda: dist_dir,
    )
    monkeypatch.setattr(
        "app.routers.bootstrap._plugin_archive",
        lambda: dist_dir / "localgitmirror-idea-plugin-0.999.0.zip",
    )
    generated = {"path": None}

    def _fake_generate(dest_dir):
        import io as _io
        import zipfile as _zip
        dest_dir.mkdir(parents=True, exist_ok=True)
        buf = _io.BytesIO()
        with _zip.ZipFile(buf, "w", _zip.ZIP_DEFLATED) as zf:
            zf.write(dist_dir / "localgitmirror-idea-plugin-0.999.0.zip",
                     "plugin/localgitmirror-idea-plugin-0.999.0.zip")
            for arcname, path in bootstrap_mod._tools_files():
                zf.write(path, f"tools/{arcname}")
            zf.writestr("README.txt", bootstrap_mod._README_PLACEHOLDER.format(
                version="0.999.0", plugin_filename="localgitmirror-idea-plugin-0.999.0.zip",
            ))
        dest = dest_dir / "doccache-setup-v999.zip"
        dest.write_bytes(buf.getvalue())
        generated["path"] = dest
        return dest

    monkeypatch.setattr(bootstrap_mod, "_generate_bundle_zip", _fake_generate)
    monkeypatch.setattr(bootstrap_mod, "ensure_work_pc_bundle", lambda: generated["path"] or _fake_generate(tmp_path))

    client = _build_client(tmp_path, monkeypatch)
    resp = client.get("/api/work-pc-bundle")
    assert resp.status_code == 200, resp.text
    zf = zipfile.ZipFile(io.BytesIO(resp.content))
    names = zf.namelist()
    assert "plugin/localgitmirror-idea-plugin-0.999.0.zip" in names
    assert "tools/lgm.py" in names
    assert "README.txt" in names
    readme = zf.read("README.txt").decode("utf-8")
    assert "__SERVER_URL__" not in readme
    assert "testserver" in readme


def test_work_pc_bundle_cached_on_disk(tmp_path, monkeypatch):
    dist_dir = tmp_path / "dist"
    dist_dir.mkdir()
    (dist_dir / "localgitmirror-idea-plugin-0.999.0.zip").write_bytes(b"fake-plugin")
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(dist_dir))
    from app.routers import bootstrap as bootstrap_mod
    monkeypatch.setattr(
        "app.routers.plugin._dist_dir",
        lambda: dist_dir,
    )
    monkeypatch.setattr(
        "app.routers.bootstrap._plugin_archive",
        lambda: dist_dir / "localgitmirror-idea-plugin-0.999.0.zip",
    )
    monkeypatch.setattr(bootstrap_mod, "_bundle_dir", lambda: tmp_path / "bc")
    result = bootstrap_mod.ensure_work_pc_bundle()
    assert result.exists()
    assert result.name.startswith("doccache-setup-v")
    all_cached = list((tmp_path / "bc").glob("doccache-setup-v*.zip"))
    assert all_cached == [result]
