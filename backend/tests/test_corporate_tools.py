"""
Tests for corporate tools management.

Covers config loading/saving, tool listing, installation with shim creation,
idempotency, and the mirror endpoints.
"""

import io
import json
import tarfile

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core import corporate_tools as ct
from app.core.corporate_tools import (
    get_path_instructions,
    install_tool,
    list_tools,
    load_tools_config,
    save_tools_config,
)
from app.routers import mirror as mirror_mod


# ── helpers ──────────────────────────────────────────────────────────────────

def make_tool_tarball(name: str, version: str, bin_entry: dict | str | None = None) -> bytes:
    """Build a .tgz with a package/ directory containing package.json."""
    if bin_entry is None:
        bin_entry = {name: f"{name}.js"}

    pkg = {"name": name, "version": version, "bin": bin_entry}

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        # package.json
        pj_data = json.dumps(pkg).encode("utf-8")
        pj_info = tarfile.TarInfo(name="package/package.json")
        pj_info.size = len(pj_data)
        tf.addfile(pj_info, io.BytesIO(pj_data))

        # bin scripts — handle both dict and string bin_entry
        scripts = bin_entry.values() if isinstance(bin_entry, dict) else [bin_entry]
        for script_name in scripts:
            script_data = b"#!/usr/bin/env node\nconsole.log('hello');\n"
            si = tarfile.TarInfo(name=f"package/{script_name}")
            si.size = len(script_data)
            tf.addfile(si, io.BytesIO(script_data))

    return buf.getvalue()


# ── fixtures ─────────────────────────────────────────────────────────────────

@pytest.fixture()
def tools_dir(tmp_path, monkeypatch):
    """Override corporate tools config dir to a temp path."""
    monkeypatch.setattr(
        "app.core.corporate_tools.TOOLS_CONFIG_DIR",
        tmp_path / "LocalGitMirror",
    )
    monkeypatch.setattr(
        "app.core.corporate_tools.TOOLS_CONFIG_FILE",
        tmp_path / "LocalGitMirror" / "corporate-tools.json",
    )
    monkeypatch.setattr(
        "app.core.corporate_tools.TOOLS_INSTALL_DIR",
        tmp_path / "LocalGitMirror" / "tools",
    )
    monkeypatch.setattr(
        "app.core.corporate_tools.TOOLS_BIN_DIR",
        tmp_path / "LocalGitMirror" / "tools" / "bin",
    )
    d = tmp_path / "LocalGitMirror"
    d.mkdir(parents=True, exist_ok=True)
    return d


@pytest.fixture()
def client(tools_dir):
    """TestClient wired to the mirror router."""
    app = FastAPI()
    app.include_router(mirror_mod.router)
    return TestClient(app)


# ── load / save config ───────────────────────────────────────────────────────

def test_load_config_empty(tools_dir):
    """No config file → empty dict."""
    assert load_tools_config() == {}


def test_save_and_load_config(tools_dir):
    """Round-trip config through the filesystem."""
    save_tools_config({"krypto-cli": "2.4.1"})
    assert load_tools_config() == {"krypto-cli": "2.4.1"}


def test_load_config_ignores_corrupt(tools_dir):
    """Corrupt JSON returns empty dict without raising."""
    ct.TOOLS_CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
    ct.TOOLS_CONFIG_FILE.write_text("{not json", encoding="utf-8")
    assert load_tools_config() == {}


# ── list_tools ───────────────────────────────────────────────────────────────

def test_list_tools_empty(tools_dir):
    """No config → empty list."""
    assert list_tools() == []


def test_list_tools_not_installed(tools_dir):
    """Configured but not installed → installed=False."""
    save_tools_config({"krypto-cli": "2.4.1"})
    result = list_tools()
    assert len(result) == 1
    assert result[0].name == "krypto-cli"
    assert result[0].version == "2.4.1"
    assert result[0].installed is False
    assert result[0].bin_path is None


def test_list_tools_installed(tools_dir):
    """After install → installed=True, bin_path set."""
    save_tools_config({"krypto-cli": "2.4.1"})
    tarball = make_tool_tarball("krypto-cli", "2.4.1")
    tarball_path = tools_dir / "temp.tgz"
    tarball_path.write_bytes(tarball)
    install_tool("krypto-cli", "2.4.1", tarball_path)

    result = list_tools()
    assert len(result) == 1
    assert result[0].installed is True
    assert result[0].bin_path is not None


# ── install_tool ─────────────────────────────────────────────────────────────

def test_install_tool_creates_shim(tools_dir):
    """Install from a valid tarball creates a .cmd shim on Windows."""
    tarball = make_tool_tarball("krypto-cli", "2.4.1")
    tarball_path = tools_dir / "temp.tgz"
    tarball_path.write_bytes(tarball)

    assert install_tool("krypto-cli", "2.4.1", tarball_path) is True

    shim = ct.TOOLS_BIN_DIR / "krypto-cli.cmd"
    assert shim.is_file()
    assert "node" in shim.read_text()


def test_install_tool_idempotent(tools_dir):
    """Second install of same tool returns True without error."""
    tarball = make_tool_tarball("krypto-cli", "2.4.1")
    tarball_path = tools_dir / "temp.tgz"
    tarball_path.write_bytes(tarball)

    assert install_tool("krypto-cli", "2.4.1", tarball_path) is True
    assert install_tool("krypto-cli", "2.4.1", tarball_path) is True  # idempotent


def test_install_tool_rejects_invalid_tarball(tools_dir):
    """Non-tarball data returns False."""
    bad_path = tools_dir / "bad.tgz"
    bad_path.write_bytes(b"not a tarball")
    assert install_tool("krypto-cli", "2.4.1", bad_path) is False


def test_install_tool_rejects_missing_package_json(tools_dir):
    """Tarball without package/package.json returns False."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        ti = tarfile.TarInfo(name="package/readme.md")
        ti.size = 0
        tf.addfile(ti, io.BytesIO(b""))
    bad_path = tools_dir / "nopkg.tgz"
    bad_path.write_bytes(buf.getvalue())
    assert install_tool("tool", "1.0", bad_path) is False


def test_install_tool_rejects_no_bin_entry(tools_dir):
    """Tarball with package.json that has no bin field returns False."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tf:
        pj = {"name": "tool", "version": "1.0"}
        data = json.dumps(pj).encode("utf-8")
        ti = tarfile.TarInfo(name="package/package.json")
        ti.size = len(data)
        tf.addfile(ti, io.BytesIO(data))
    bad_path = tools_dir / "nobin.tgz"
    bad_path.write_bytes(buf.getvalue())
    assert install_tool("tool", "1.0", bad_path) is False


def test_install_tool_handles_string_bin(tools_dir):
    """package.json bin: string (not dict) → treated as {name: bin}."""
    tarball = make_tool_tarball("krypto-cli", "2.4.1", bin_entry="krypto-cli.js")
    tarball_path = tools_dir / "temp.tgz"
    tarball_path.write_bytes(tarball)

    assert install_tool("krypto-cli", "2.4.1", tarball_path) is True
    shim = ct.TOOLS_BIN_DIR / "krypto-cli.cmd"
    assert shim.is_file()


# ── get_path_instructions ────────────────────────────────────────────────────

def test_path_instructions(tools_dir):
    assert "Add to PATH" in get_path_instructions()


# ── endpoints ────────────────────────────────────────────────────────────────

def test_get_tools_empty(client, tools_dir):
    """GET /api/cache/tools returns empty list when no tools configured."""
    resp = client.get("/api/cache/tools")
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["tools"] == []
    assert "binDir" in body
    assert "pathInstructions" in body


def test_get_tools_with_installed(client, tools_dir):
    """GET /api/cache/tools returns tool info after config."""
    save_tools_config({"krypto-cli": "2.4.1"})
    resp = client.get("/api/cache/tools")
    body = resp.json()
    assert len(body["tools"]) == 1
    t = body["tools"][0]
    assert t["name"] == "krypto-cli"
    assert t["version"] == "2.4.1"
    assert t["installed"] is False


def test_post_install_tool(client, tools_dir):
    """POST /api/cache/tools/install installs a tool from tarball."""
    tarball = make_tool_tarball("krypto-cli", "2.4.1")
    resp = client.post(
        "/api/cache/tools/install",
        data={"name": "krypto-cli", "version": "2.4.1"},
        files={"tarball": ("krypto-cli.tgz", tarball, "application/gzip")},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["name"] == "krypto-cli"
    assert body["version"] == "2.4.1"

    # Verify shim exists
    shim = ct.TOOLS_BIN_DIR / "krypto-cli.cmd"
    assert shim.is_file()


def test_post_install_bad_tarball(client, tools_dir):
    """POST with invalid tarball returns 400."""
    resp = client.post(
        "/api/cache/tools/install",
        data={"name": "bad", "version": "1.0"},
        files={"tarball": ("bad.tgz", b"not a tarball", "application/gzip")},
    )
    assert resp.status_code == 400
