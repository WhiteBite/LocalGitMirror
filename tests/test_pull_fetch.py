"""op_pull: a failed local git fetch of the decrypted bundle raises LgmError
instead of returning fetch_exit/fetch_stderr as data."""
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.crypto import encrypt_bundle
from lgm_core.ops import Ctx, op_pull


def _pull_ctx(monkeypatch, returncode: int, stderr: str) -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password="pw")
    ctx = Ctx(config=config,
              client=MirrorClient(base_url=config.base_url, api_key="",
                                  sync_password="pw"))
    dump = encrypt_bundle(b"bundle-bytes", "pw")
    monkeypatch.setattr(
        ctx.client, "sync_pull",
        lambda repo, branch="", since="", haves="":
            {"status": "ok", "head": "abc", "dump": dump},
    )

    def fake_run(cmd, cwd=None, capture_output=None, text=None, timeout=None):
        return subprocess.CompletedProcess(cmd, returncode, stdout="",
                                           stderr=stderr)

    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", fake_run)
    return ctx


def test_pull_fetch_failure_raises(monkeypatch, tmp_path):
    ctx = _pull_ctx(monkeypatch, 128, "fatal: bad bundle\n")
    with pytest.raises(LgmError) as ei:
        op_pull(ctx, {"repo": "r", "project": str(tmp_path)})
    assert ei.value.code == "git"
    assert "fatal: bad bundle" in ei.value.message


def test_pull_fetch_success_keeps_fetch_exit(monkeypatch, tmp_path):
    ctx = _pull_ctx(monkeypatch, 0, "")
    res = op_pull(ctx, {"repo": "r", "project": str(tmp_path)})
    assert res["fetch_exit"] == 0
    assert res["bundle_size"] == len(b"bundle-bytes")


def test_pull_dry_run_skips_local_fetch(monkeypatch, tmp_path):
    ctx = _pull_ctx(monkeypatch, 128, "fatal: unreachable\n")
    res = op_pull(ctx, {"repo": "r", "dry_run": True})
    assert res["dry_run"] is True
    assert res["dump_size"] > 0


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
