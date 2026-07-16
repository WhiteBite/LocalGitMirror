"""Regression coverage for serving the current IDEA plugin ZIP."""

import os
from pathlib import Path

from app.routers import plugin


def test_newest_zip_prefers_semantic_version_over_file_mtime(tmp_path, monkeypatch):
    old_version = tmp_path / "localgitmirror-idea-plugin-0.88.0.zip"
    new_version = tmp_path / "localgitmirror-idea-plugin-0.92.0.zip"
    ignored = tmp_path / "notes-999.0.0.zip"
    old_version.write_bytes(b"old")
    new_version.write_bytes(b"new")
    ignored.write_bytes(b"ignored")

    # Simulate an old artifact copied later than the actual current build.
    os.utime(old_version, (2_000_000_000, 2_000_000_000))
    os.utime(new_version, (1_000_000_000, 1_000_000_000))
    monkeypatch.setenv("LGM_PLUGIN_DIST", str(tmp_path))

    assert plugin._newest_zip() == new_version
    assert plugin._parse_version(new_version.name) == "0.92.0"
