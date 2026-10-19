"""Real-git proof for the mr_send exclusion rule: git bundle create refuses an
empty pack, so a sent branch's own tip must never become a ^exclusion."""
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import ops_git
from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.op_models import Ctx
from lgm_core.ops_mr import send_exclusions


def _git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(repo),
                          capture_output=True, text=True, timeout=30)
    if check:
        assert proc.returncode == 0, f"git {' '.join(args)}: {proc.stderr}"
    return proc


def _repo_two_branches_one_tip(tmp_path: Path) -> tuple[Path, str]:
    proj = tmp_path / "repo"
    proj.mkdir()
    _git(proj, "init", "-q", "-b", "master")
    _git(proj, "config", "user.email", "t@t")
    _git(proj, "config", "user.name", "t")
    (proj / "a.txt").write_text("a\n", encoding="utf-8")
    _git(proj, "add", "a.txt")
    _git(proj, "commit", "-qm", "one")
    tip = _git(proj, "rev-parse", "HEAD").stdout.strip()
    _git(proj, "branch", "feature")
    return proj, tip


def test_bundle_create_refuses_exclusion_equal_to_sent_tip(tmp_path):
    proj, tip = _repo_two_branches_one_tip(tmp_path)
    proc = _git(proj, "bundle", "create", str(tmp_path / "old-rule.bundle"),
                "refs/heads/feature", f"^{tip}", check=False)
    assert proc.returncode != 0
    assert "Refusing to create empty bundle" in proc.stderr


def test_new_rule_exclusions_let_the_ref_ship(tmp_path):
    proj, tip = _repo_two_branches_one_tip(tmp_path)
    excludes = send_exclusions([tip], [tip], {tip})
    assert excludes == []
    bundle = tmp_path / "new-rule.bundle"
    proc = _git(proj, "bundle", "create", str(bundle),
                "refs/heads/feature", *excludes, check=False)
    assert proc.returncode == 0
    heads = _git(proj, "bundle", "list-heads", str(bundle)).stdout
    assert "refs/heads/feature" in heads


def test_send_exclusions_filters_dedups_and_drops_sent_tips():
    mirror_tips = ["m1", "m1", "ghost", "t1", "", "m2"]
    assert send_exclusions(mirror_tips, ["t1"], {"m1", "t1", "m2"}) == ["m1", "m2"]


def _ctx() -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    client = MirrorClient(base_url=config.base_url, api_key="", sync_password="pw")
    return Ctx(config=config, client=client)


def test_send_branch_refuses_when_mirror_already_holds_tip(monkeypatch, tmp_path):
    proj, tip = _repo_two_branches_one_tip(tmp_path)
    monkeypatch.setattr(ops_git, "_negotiate", lambda *a, **kw: ([tip], {}))
    calls = []
    monkeypatch.setattr(ops_git, "send_branches",
                        lambda *a, **kw: calls.append(a))
    with pytest.raises(LgmError) as excinfo:
        ops_git.send_branch(_ctx(), "r", str(proj), "feature")
    assert excinfo.value.code == "config"
    assert "nothing to send" in excinfo.value.message
    assert "Refusing" not in excinfo.value.message
    assert calls == []


def test_send_branches_translates_git_empty_bundle_error(monkeypatch, tmp_path):
    def fake_run(cmd, **kw):
        assert cmd[1:3] == ["bundle", "create"]
        return subprocess.CompletedProcess(
            cmd, 1, stdout="", stderr="fatal: Refusing to create empty bundle.")

    monkeypatch.setattr("lgm_core.ops_git.subprocess.run", fake_run)
    with pytest.raises(LgmError) as excinfo:
        ops_git.send_branches(_ctx(), "r", str(tmp_path), ["feature"], ["t1"])
    assert excinfo.value.code == "config"
    assert "nothing to send" in excinfo.value.message
    assert "Refusing" not in excinfo.value.message
