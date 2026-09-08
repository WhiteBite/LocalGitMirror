"""
Тесты npm cache scanner и npm publish endpoint.

Проверяем: сканирование _cacache, фильтрацию по scope, packument generation,
npm publish endpoint.
"""

import io
import json
import os
import tarfile
import zipfile
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.artifact_store import ArtifactStore, sha256_bytes
from app.core.bundle_crypto import encrypt_bundle_bytes
from app.core.npm_cache import (
    DEFAULT_PROTECTED_NPM_SCOPES,
    NpmArtifact,
    is_protected_npm_package,
    scan_npm_cache,
    scan_npm_offline_mirror,
)
from app.routers import mirror as mirror_mod

PASSWORD = "test-sync-password"


# ─────────────────────────────────────────────────────────────────────────────
# Protected scope filtering
# ─────────────────────────────────────────────────────────────────────────────

class TestProtectedScopes:
    def test_scoped_package_matches(self):
        assert is_protected_npm_package("@krypto-ui/components")
        assert is_protected_npm_package("@krypto-sdk/core")

    def test_unscoped_with_prefix_matches(self):
        assert is_protected_npm_package("krypto-cli")
        assert is_protected_npm_package("krypto-utils")

    def test_unrelated_scoped_rejected(self):
        assert not is_protected_npm_package("@evil/components")
        assert not is_protected_npm_package("@krypto-ui-evil/pkg")

    def test_unrelated_unscoped_rejected(self):
        assert not is_protected_npm_package("lodash")
        assert not is_protected_npm_package("react")

    def test_custom_scopes(self):
        custom = ("@myorg/",)
        assert is_protected_npm_package("@myorg/pkg", custom)
        assert not is_protected_npm_package("@krypto-ui/pkg", custom)


# ─────────────────────────────────────────────────────────────────────────────
# npm cache scanning
# ─────────────────────────────────────────────────────────────────────────────

def _make_tarball(tmp_path: Path, name: str, version: str) -> bytes:
    """Create a minimal .tgz with package/package.json inside."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        pkg_json = json.dumps({"name": name, "version": version}).encode()
        info = tarfile.TarInfo(name="package/package.json")
        info.size = len(pkg_json)
        tf.addfile(info, io.BytesIO(pkg_json))
    return buf.getvalue()


def _setup_npm_cache(cache_root: Path, packages: list) -> None:
    """Create a fake npm _cacache structure.
    
    packages: list of (name, version, tarball_bytes)
    scan_npm_cache expects cache_root to BE the _cacache directory.
    """
    index_dir = cache_root / "index-v5"
    content_dir = cache_root / "content-v2" / "sha512"
    index_dir.mkdir(parents=True)
    content_dir.mkdir(parents=True)

    for name, version, tarball_bytes in packages:
        # Compute hashes
        import hashlib, base64
        sha512 = hashlib.sha512(tarball_bytes).digest()
        sha512_hex = sha512.hex()
        integrity = f"sha512-{base64.b64encode(sha512).decode()}"

        # Write content blob
        # npm content-v2 layout: sha512/<first-2-hex>/<full-hex>
        content_prefix = content_dir / sha512_hex[:2]
        content_prefix.mkdir(exist_ok=True)
        (content_prefix / sha512_hex).write_bytes(tarball_bytes)

        # Write index entry
        # npm cache key format: pacote:tarball:<url> where url ends with pkg@version
        key = f"pacote:tarball:https://registry.npmjs.org/{name}@{version}"
        index_entry = {
            "key": key,
            "integrity": integrity,
            "time": 1234567890,
        }
        # Index key hash
        import hashlib as hl
        key_hash = hl.sha1(key.encode()).hexdigest()
        idx_prefix = index_dir / key_hash[:2]
        idx_prefix.mkdir(exist_ok=True)
        (idx_prefix / key_hash[2:]).write_text(json.dumps(index_entry))


class TestScanNpmCache:
    def test_finds_protected_packages(self, tmp_path):
        cache_root = tmp_path / "npm-cache"
        tar = _make_tarball(tmp_path, "@krypto-ui/components", "1.2.3")
        _setup_npm_cache(cache_root, [("@krypto-ui/components", "1.2.3", tar)])

        artifacts = scan_npm_cache(cache_root, DEFAULT_PROTECTED_NPM_SCOPES)
        assert len(artifacts) == 1
        assert artifacts[0].name == "@krypto-ui/components"
        assert artifacts[0].version == "1.2.3"

    def test_filters_non_protected(self, tmp_path):
        cache_root = tmp_path / "npm-cache"
        tar1 = _make_tarball(tmp_path, "@krypto-ui/components", "1.0.0")
        tar2 = _make_tarball(tmp_path, "lodash", "4.17.21")
        _setup_npm_cache(cache_root, [
            ("@krypto-ui/components", "1.0.0", tar1),
            ("lodash", "4.17.21", tar2),
        ])

        artifacts = scan_npm_cache(cache_root, DEFAULT_PROTECTED_NPM_SCOPES)
        assert len(artifacts) == 1
        assert artifacts[0].name == "@krypto-ui/components"

    def test_empty_cache(self, tmp_path):
        cache_root = tmp_path / "npm-cache"
        cache_root.mkdir()
        artifacts = scan_npm_cache(cache_root, DEFAULT_PROTECTED_NPM_SCOPES)
        assert artifacts == []

    def test_missing_cache(self, tmp_path):
        cache_root = tmp_path / "nonexistent"
        artifacts = scan_npm_cache(cache_root, DEFAULT_PROTECTED_NPM_SCOPES)
        assert artifacts == []


# ─────────────────────────────────────────────────────────────────────────────
# Yarn offline mirror scanning
# ─────────────────────────────────────────────────────────────────────────────

class TestScanYarnMirror:
    def test_finds_protected_tarballs(self, tmp_path):
        mirror_root = tmp_path / "yarn-mirror"
        mirror_root.mkdir()
        tar = _make_tarball(tmp_path, "@krypto-ui/components", "1.2.3")
        (mirror_root / "krypto-ui-components-1.2.3.tgz").write_bytes(tar)

        artifacts = scan_npm_offline_mirror(mirror_root, DEFAULT_PROTECTED_NPM_SCOPES)
        assert len(artifacts) == 1
        assert artifacts[0].name == "@krypto-ui/components"
        assert artifacts[0].version == "1.2.3"

    def test_filters_non_protected(self, tmp_path):
        mirror_root = tmp_path / "yarn-mirror"
        mirror_root.mkdir()
        tar1 = _make_tarball(tmp_path, "@krypto-ui/components", "1.0.0")
        tar2 = _make_tarball(tmp_path, "lodash", "4.17.21")
        (mirror_root / "krypto-ui-components-1.0.0.tgz").write_bytes(tar1)
        (mirror_root / "lodash-4.17.21.tgz").write_bytes(tar2)

        artifacts = scan_npm_offline_mirror(mirror_root, DEFAULT_PROTECTED_NPM_SCOPES)
        assert len(artifacts) == 1
        assert artifacts[0].name == "@krypto-ui/components"

    def test_empty_mirror(self, tmp_path):
        mirror_root = tmp_path / "yarn-mirror"
        mirror_root.mkdir()
        artifacts = scan_npm_offline_mirror(mirror_root, DEFAULT_PROTECTED_NPM_SCOPES)
        assert artifacts == []


# ─────────────────────────────────────────────────────────────────────────────
# npm publish endpoint
# ─────────────────────────────────────────────────────────────────────────────

@pytest.fixture()
def vault(tmp_path, monkeypatch):
    root = tmp_path / "vault"
    monkeypatch.setenv("LGM_VAULT_PATH", str(root))
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    monkeypatch.setenv("LGM_M2_ALLOW_REMOTE", "1")  # TestClient uses 'testclient' host
    monkeypatch.delenv("LGM_PROTECTED_NPM_SCOPES", raising=False)
    mirror_mod.repo_manager = None
    mirror_mod.system_logger = None
    return root


@pytest.fixture()
def client(vault):
    app = FastAPI()
    app.include_router(mirror_mod.router)
    return TestClient(app)


def _make_npm_publication(packages: dict) -> bytes:
    """Build a ZIP publication with manifest.json + tarballs.
    
    packages: {name@version: tarball_bytes}
    ZIP structure: npm/tarballs/<filename>.tgz + manifest.json
    """
    buf = io.BytesIO()
    manifest = {"schema": "npm-v1", "npm": []}

    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for spec, tarball_bytes in packages.items():
            name, version = spec.rsplit("@", 1)
            filename = f"{name.replace('/', '-')}-{version}.tgz"
            zf.writestr(f"npm/tarballs/{filename}", tarball_bytes)

            import hashlib, base64
            sha512 = hashlib.sha512(tarball_bytes).digest()
            integrity = f"sha512-{base64.b64encode(sha512).decode()}"
            shasum = hashlib.sha1(tarball_bytes).hexdigest()

            manifest["npm"].append({
                "name": name,
                "version": version,
                "filename": filename,
                "integrity": integrity,
                "shasum": shasum,
            })

        zf.writestr("manifest.json", json.dumps(manifest))

    return buf.getvalue()


class TestNpmPublishEndpoint:
    def test_publish_imports_tarballs(self, client, vault):
        tar = _make_tarball(Path("/tmp"), "@krypto-ui/components", "1.2.3")
        pub = _make_npm_publication({"@krypto-ui/components@1.2.3": tar})
        encrypted = encrypt_bundle_bytes(pub, PASSWORD)

        resp = client.post(
            "/api/cache/publish-npm",
            files={"attachment": ("pub.enc", encrypted, "application/octet-stream")},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["success"] is True
        assert body["added"] == 1

    def test_publish_rejects_wrong_password(self, client, vault):
        tar = _make_tarball(Path("/tmp"), "@krypto-ui/components", "1.2.3")
        pub = _make_npm_publication({"@krypto-ui/components@1.2.3": tar})
        encrypted = encrypt_bundle_bytes(pub, "wrong-password")

        resp = client.post(
            "/api/cache/publish-npm",
            files={"attachment": ("pub.enc", encrypted, "application/octet-stream")},
        )
        assert resp.status_code == 400

    def test_publish_skips_existing(self, client, vault):
        tar = _make_tarball(Path("/tmp"), "@krypto-ui/components", "1.2.3")
        pub = _make_npm_publication({"@krypto-ui/components@1.2.3": tar})
        encrypted = encrypt_bundle_bytes(pub, PASSWORD)

        # First publish
        resp1 = client.post(
            "/api/cache/publish-npm",
            files={"attachment": ("pub.enc", encrypted, "application/octet-stream")},
        )
        assert resp1.json()["added"] == 1

        # Second publish — same tarball
        resp2 = client.post(
            "/api/cache/publish-npm",
            files={"attachment": ("pub.enc", encrypted, "application/octet-stream")},
        )
        assert resp2.json()["existed"] == 1


class TestNpmServeEndpoints:
    def test_serve_tarball(self, client, vault):
        tar = _make_tarball(Path("/tmp"), "@krypto-ui/components", "1.2.3")
        pub = _make_npm_publication({"@krypto-ui/components@1.2.3": tar})
        encrypted = encrypt_bundle_bytes(pub, PASSWORD)
        pub_resp = client.post(
            "/api/cache/publish-npm",
            files={"attachment": ("pub.enc", encrypted, "application/octet-stream")},
        )
        assert pub_resp.status_code == 200, f"Publish failed: {pub_resp.text}"
        assert pub_resp.json()["added"] == 1, f"Nothing added: {pub_resp.json()}"

        # Endpoint serves latest version when no version specified
        resp = client.get("/api/cache/npm/@krypto-ui/components")
        assert resp.status_code == 200
        assert resp.headers["content-type"] == "application/octet-stream"

    def test_serve_packument(self, client, vault):
        tar = _make_tarball(Path("/tmp"), "@krypto-ui/components", "1.2.3")
        pub = _make_npm_publication({"@krypto-ui/components@1.2.3": tar})
        encrypted = encrypt_bundle_bytes(pub, PASSWORD)
        client.post(
            "/api/cache/publish-npm",
            files={"attachment": ("pub.enc", encrypted, "application/octet-stream")},
        )

        resp = client.get("/api/cache/npm/@krypto-ui/components/packument")
        assert resp.status_code == 200
        body = resp.json()
        assert body["name"] == "@krypto-ui/components"
        assert "1.2.3" in body["versions"]

    def test_serve_missing_returns_404(self, client, vault):
        resp = client.get("/api/cache/npm/@krypto-ui/missing/1.0.0")
        assert resp.status_code == 404
