"""
Tests for yarn v1 offline mirror projection.

Verifies that build_yarn_projection writes only corporate tarballs from CAS
into a flat directory, clears old projection before rebuild, and handles
edge cases (missing sha256, missing CAS file).
"""

import json
from pathlib import Path

import pytest

from app.core.artifact_store import ArtifactStore, sha256_bytes
from app.core.npm_cache import build_yarn_projection


@pytest.fixture()
def vault(tmp_path):
    return tmp_path / "vault"


@pytest.fixture()
def store(vault):
    return ArtifactStore(vault)


@pytest.fixture()
def projection(tmp_path):
    return tmp_path / "yarn-offline"


def _put_tarball(store: ArtifactStore, data: bytes) -> str:
    """Write tarball bytes to CAS, return sha256."""
    digest = sha256_bytes(data)
    path = store.cas_path(digest)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return digest


class TestBuildYarnProjection:
    def test_writes_tarballs(self, vault, store, projection):
        sha = _put_tarball(store, b"fake-tarball-data")
        index = {
            "@krypto-ui/components": {
                "1.2.3": {"tarball_sha256": sha, "integrity": "sha512-xxx", "shasum": "abc"}
            }
        }
        count = build_yarn_projection(vault, projection, index)
        assert count == 1
        assert (projection / "krypto-ui-components-1.2.3.tgz").is_file()

    def test_scope_converted_to_dash(self, vault, store, projection):
        sha = _put_tarball(store, b"data")
        index = {"@krypto-ui/core": {"1.0.0": {"tarball_sha256": sha}}}
        build_yarn_projection(vault, projection, index)
        assert (projection / "krypto-ui-core-1.0.0.tgz").is_file()

    def test_clears_old_projection(self, vault, store, projection):
        projection.mkdir(parents=True)
        (projection / "old-package-1.0.0.tgz").write_bytes(b"old")

        sha = _put_tarball(store, b"new")
        index = {"krypto-new": {"2.0.0": {"tarball_sha256": sha}}}
        build_yarn_projection(vault, projection, index)

        assert not (projection / "old-package-1.0.0.tgz").exists()
        assert (projection / "krypto-new-2.0.0.tgz").is_file()

    def test_skips_missing_sha256(self, vault, store, projection):
        index = {"@krypto-ui/broken": {"1.0.0": {"tarball_sha256": ""}}}
        count = build_yarn_projection(vault, projection, index)
        assert count == 0

    def test_skips_missing_cas_file(self, vault, projection):
        index = {"@krypto-ui/missing": {"1.0.0": {"tarball_sha256": "deadbeef" * 8}}}
        count = build_yarn_projection(vault, projection, index)
        assert count == 0

    def test_empty_index(self, vault, projection):
        count = build_yarn_projection(vault, projection, {})
        assert count == 0
        assert projection.is_dir()

    def test_multiple_versions(self, vault, store, projection):
        sha1 = _put_tarball(store, b"v1")
        sha2 = _put_tarball(store, b"v2")
        index = {
            "@krypto-ui/pkg": {
                "1.0.0": {"tarball_sha256": sha1},
                "2.0.0": {"tarball_sha256": sha2},
            }
        }
        count = build_yarn_projection(vault, projection, index)
        assert count == 2
        assert (projection / "krypto-ui-pkg-1.0.0.tgz").is_file()
        assert (projection / "krypto-ui-pkg-2.0.0.tgz").is_file()
