"""op_update: unwrap tolerance + verified, atomic install with one rollback copy."""
import io
import zipfile
from pathlib import Path

import pytest

from lgm_core import ops_git
from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.crypto import encrypt_bundle
from lgm_core.op_models import Ctx
from lgm_core.ops_git import _unwrap_tools_blob, op_update

PWD = "pw"
BACKUP = ".lgm_backup"


def _zip(entries: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return buf.getvalue()


def _tools_tree(tmp_path: Path) -> Path:
    core = tmp_path / "lgm_core"
    core.mkdir()
    (core / "__init__.py").write_text("X = 1\n", encoding="utf-8")
    (core / "ops_git.py").write_text("Y = 2\n", encoding="utf-8")
    (tmp_path / "lgm.py").write_text("Z = 3\n", encoding="utf-8")
    (tmp_path / "VERSION").write_text("0.1.0\n", encoding="utf-8")
    return tmp_path


def _snapshot(root: Path) -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    for p in root.rglob("*"):
        if p.is_file() and p.relative_to(root).parts[0] != BACKUP:
            out[str(p.relative_to(root)).replace("\\", "/")] = p.read_bytes()
    return out


class _Response:
    def __init__(self, blob: bytes, version: str):
        self._blob = blob
        self.headers = {"X-LGM-Version": version}

    def read(self) -> bytes:
        return self._blob

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _ctx() -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="", sync_password=PWD)
    return Ctx(config=config, client=MirrorClient(
        base_url=config.base_url, api_key="", sync_password=PWD))


def _fake_download(monkeypatch, ctx: Ctx, blob: bytes, version: str = "0.2.0"):
    monkeypatch.setattr(ctx.client, "_open",
                        lambda req, timeout: _Response(blob, version))


def _aim_at_tools(monkeypatch, tools: Path):
    monkeypatch.setattr(ops_git, "__file__", str(tools / "lgm_core" / "ops_git.py"))


def test_raw_zip_passes_through_undecrypted():
    blob = _zip({"lgm_core/ops_git.py": b"x = 1\n"})
    assert blob[:2] == b"PK"
    assert _unwrap_tools_blob(blob, PWD) == blob


def test_sealed_bundle_is_decrypted_to_zip():
    plain = _zip({"lgm_core/ops_git.py": b"x = 1\n"})
    sealed = encrypt_bundle(plain, PWD)
    assert sealed[:2] != b"PK"
    assert _unwrap_tools_blob(sealed, PWD) == plain


def test_malformed_blob_is_rejected_not_silently_passed():
    with pytest.raises(Exception):
        _unwrap_tools_blob(b"garbage-not-zip-not-bundle", PWD)


def test_corrupt_zip_leaves_previous_tools_intact(monkeypatch, tmp_path):
    tools = _tools_tree(tmp_path)
    _aim_at_tools(monkeypatch, tools)
    blob = bytearray(_zip({"VERSION": b"0.2.0\n", "lgm.py": b"Z = 30\n",
                           "lgm_core/ops_git.py": b"Y = 20\n"}))
    marker = b"Z = 30\n"
    pos = blob.index(marker) + 3
    blob[pos] = blob[pos] ^ 0xFF
    ctx = _ctx()
    _fake_download(monkeypatch, ctx, bytes(blob))
    before = _snapshot(tools)

    with pytest.raises(LgmError) as excinfo:
        op_update(ctx, {})

    assert excinfo.value.code == "network"
    assert _snapshot(tools) == before
    assert not (tools / BACKUP).exists()
    for p in tools.rglob("*.py"):
        compile(p.read_bytes(), str(p), "exec")


def test_truncated_sealed_blob_is_a_typed_refusal(monkeypatch, tmp_path):
    tools = _tools_tree(tmp_path)
    _aim_at_tools(monkeypatch, tools)
    sealed = encrypt_bundle(_zip({"VERSION": b"0.2.0\n"}), PWD)
    ctx = _ctx()
    _fake_download(monkeypatch, ctx, sealed[: len(sealed) // 2])
    before = _snapshot(tools)

    with pytest.raises(LgmError) as excinfo:
        op_update(ctx, {})

    assert excinfo.value.code == "network"
    assert _snapshot(tools) == before


def test_truncated_raw_zip_is_a_typed_refusal(monkeypatch, tmp_path):
    tools = _tools_tree(tmp_path)
    _aim_at_tools(monkeypatch, tools)
    blob = _zip({"VERSION": b"0.2.0\n", "lgm.py": b"Z = 30\n"})
    ctx = _ctx()
    _fake_download(monkeypatch, ctx, blob[: len(blob) // 2])
    before = _snapshot(tools)

    with pytest.raises(LgmError) as excinfo:
        op_update(ctx, {})

    assert excinfo.value.code == "network"
    assert _snapshot(tools) == before


def test_successful_update_installs_and_keeps_one_rollback_copy(monkeypatch, tmp_path):
    tools = _tools_tree(tmp_path)
    _aim_at_tools(monkeypatch, tools)
    ctx = _ctx()
    _fake_download(monkeypatch, ctx, _zip({
        "VERSION": b"0.2.0\n", "lgm.py": b"Z = 30\n",
        "lgm_core/ops_git.py": b"Y = 20\n", "lgm_core/new_mod.py": b"N = 9\n"}))

    res = op_update(ctx, {})

    assert res["success"] is True
    assert res["version"] == "0.2.0"
    assert res["files_updated"] == 4
    assert (tools / "VERSION").read_text() == "0.2.0\n"
    assert (tools / "lgm_core" / "new_mod.py").read_text() == "N = 9\n"
    backup = tools / BACKUP
    assert (backup / "VERSION").read_text() == "0.1.0\n"
    assert (backup / "lgm.py").read_text() == "Z = 3\n"
    assert (backup / "lgm_core" / "ops_git.py").read_text() == "Y = 2\n"
    assert not (backup / "lgm_core" / "new_mod.py").exists()
    assert not list(tools.glob(".lgm_update_*"))

    _fake_download(monkeypatch, ctx, _zip({"VERSION": b"0.3.0\n"}), version="0.3.0")
    op_update(ctx, {})
    assert (backup / "VERSION").read_text() == "0.2.0\n"


def test_midway_write_failure_rolls_back_completely(monkeypatch, tmp_path):
    import os
    tools = _tools_tree(tmp_path)
    _aim_at_tools(monkeypatch, tools)
    ctx = _ctx()
    _fake_download(monkeypatch, ctx, _zip({
        "VERSION": b"0.2.0\n", "lgm.py": b"Z = 30\n",
        "lgm_core/ops_git.py": b"Y = 20\n", "lgm_core/new_mod.py": b"N = 9\n"}))
    before = _snapshot(tools)
    real_replace = os.replace
    calls = {"n": 0}

    def replace_failing_on_second(src, dst):
        calls["n"] += 1
        if calls["n"] == 2:
            raise OSError("simulated disk failure")
        return real_replace(src, dst)

    monkeypatch.setattr(os, "replace", replace_failing_on_second)

    with pytest.raises(LgmError) as excinfo:
        op_update(ctx, {})

    assert excinfo.value.code == "network"
    assert "rolled back" in excinfo.value.message
    assert _snapshot(tools) == before
    assert not list(tools.glob(".lgm_update_*"))
