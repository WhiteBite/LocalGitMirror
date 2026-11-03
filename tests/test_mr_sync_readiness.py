import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.op_models import Ctx

MR = {"iid": 24, "title": "Llm-core", "source_branch": "llm-core",
      "updated_at": "2026-10-03T11:38:04.050Z"}


def _ctx() -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    client = MirrorClient(base_url=config.base_url, api_key="",
                          sync_password="pw")
    return Ctx(config=config, client=client)


def _git_repo(proj: Path) -> None:
    proj.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "-C", str(proj), "init", "-q", "-b", "master"], check=True)
    subprocess.run(["git", "-C", str(proj), "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", str(proj), "config", "user.name", "t"], check=True)
    (proj / "F.txt").write_text("x\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(proj), "add", "."], check=True)
    subprocess.run(["git", "-C", str(proj), "commit", "-q", "-m", "i"], check=True)


def _prepared(iid: int) -> dict:
    return {"status": "prepared", "snapshot_id": "a" * 64, "code_id": "b" * 64,
            "bundle": {"action": "uploaded"},
            "manifest_path": f"mr-review/snapshots/{'a' * 64}.json",
            "limitations": []}


def _skipped(iid: int) -> dict:
    return {"status": "skipped", "snapshot_id": "a" * 64, "code_id": "b" * 64,
            "bundle": {"action": "skipped"},
            "limitations": ["code artifact is not available in the postbox"]}


def _env(tmp_path, monkeypatch, mrs, prepare):
    import lgm_core.ops_projects as opmod
    proj = tmp_path / "proj"
    _git_repo(proj)
    for m in mrs:
        branch = m.get("source_branch")
        if branch:
            subprocess.run(["git", "-C", str(proj), "branch", branch], check=True)
    monkeypatch.setattr(opmod, "_REGISTRY_PATH", tmp_path / "reg.json")
    monkeypatch.setattr(opmod, "_STATE_PATH", tmp_path / "state.json")
    monkeypatch.setattr(opmod, "_fetch_mr_branches", lambda proj, branches: [])
    monkeypatch.setattr(opmod, "_mirror_refs_safe", lambda c, repo: {})
    monkeypatch.setattr(opmod, "send_branches", lambda *a, **k: {"bundle_size": 10})
    monkeypatch.setattr(opmod, "mr_review_prepare", prepare)
    ctx = _ctx()
    ctx.client.sync_negotiate = lambda repo, commits, probe=False: {"known": []}
    ctx.client.gitlab_list_mrs = lambda project="": mrs
    ctx.client.gitlab_list_discussions = lambda iid, project="": []
    ctx.client.file_sync_send = lambda repo, path, size, data: {
        "success": True, "id": "x"}
    opmod._save_registry({"projects": [
        {"repo": "r", "path": str(proj), "gitlab": "g/r"}]})
    return opmod, ctx


def test_blocked_branch_still_prepares_review(tmp_path, monkeypatch):
    opmod, ctx = _env(tmp_path, monkeypatch, [MR],
                      lambda *a, **k: _prepared(24))
    monkeypatch.setattr(opmod, "_fetch_mr_branches",
                        lambda proj, branches: ["llm-core"])

    res = opmod.op_mr_sync_all(ctx, {})

    item = res["repos"][0]["mrs"][0]
    assert item["branch_transfer"] == "blocked"
    assert item["review"]["status"] == "prepared"
    assert item["review"]["snapshot_id"] == "a" * 64
    assert item["review"]["code_id"] == "b" * 64
    assert res["summary"]["review_status"] == "ready"
    assert res["summary"]["review_complete"] is True
    assert res["success"] is True


def test_source_branch_string_is_preserved(tmp_path, monkeypatch):
    opmod, ctx = _env(tmp_path, monkeypatch, [MR],
                      lambda *a, **k: _prepared(24))

    res = opmod.op_mr_sync_all(ctx, {})

    item = res["repos"][0]["mrs"][0]
    assert item["branch"] == "llm-core"
    assert isinstance(item["branch"], str)


def test_review_failure_marks_incomplete_but_sync_succeeds(tmp_path, monkeypatch):
    def boom(*a, **k):
        raise LgmError("network", "GitLab unreachable")

    opmod, ctx = _env(tmp_path, monkeypatch, [MR], boom)

    res = opmod.op_mr_sync_all(ctx, {})

    item = res["repos"][0]["mrs"][0]
    assert item["review"]["status"] == "failed"
    assert "GitLab unreachable" in item["review"]["error"]
    assert res["summary"]["review_status"] == "incomplete"
    assert res["summary"]["review_complete"] is False
    assert res["success"] is True
    assert res["repos"][0]["review_snapshots"][0]["status"] == "failed"


def test_notes_only_marks_branch_not_requested_and_review_skipped(tmp_path, monkeypatch):
    opmod, ctx = _env(tmp_path, monkeypatch, [MR],
                      lambda *a, **k: _skipped(24))

    res = opmod.op_mr_sync_all(ctx, {"notes_only": True})

    item = res["repos"][0]["mrs"][0]
    assert item["branch_transfer"] == "not-requested"
    assert item["review"]["status"] == "skipped"
    assert item["review"]["limitations"]
    assert res["summary"]["review_status"] == "incomplete"
    assert res["summary"]["review_complete"] is False


def test_dry_run_reports_not_run(tmp_path, monkeypatch):
    opmod, ctx = _env(tmp_path, monkeypatch, [MR],
                      lambda *a, **k: _prepared(24))

    res = opmod.op_mr_sync_all(ctx, {"dry_run": True})

    item = res["repos"][0]["mrs"][0]
    assert item["branch_transfer"] == "dry-run"
    assert item["review"]["status"] == "not-run"
    assert res["summary"]["review_status"] == "not-run"
    assert res["summary"]["review_complete"] is False


def test_repo_error_never_claims_ready(tmp_path, monkeypatch):
    opmod, ctx = _env(tmp_path, monkeypatch, [MR],
                      lambda *a, **k: _prepared(24))
    ctx.client.gitlab_list_mrs = lambda project="": (_ for _ in ()).throw(
        LgmError("network", "GitLab unreachable"))

    res = opmod.op_mr_sync_all(ctx, {})

    assert res["summary"]["review_status"] == "incomplete"
    assert res["summary"]["review_complete"] is False
    assert res["success"] is False


def test_no_mrs_reports_empty_not_ready(tmp_path, monkeypatch):
    opmod, ctx = _env(tmp_path, monkeypatch, [],
                      lambda *a, **k: _prepared(0))

    res = opmod.op_mr_sync_all(ctx, {})

    assert res["summary"]["review_status"] == "empty"
    assert res["summary"]["review_complete"] is False
    assert res["success"] is True


def test_branch_sent_and_skipped_statuses(tmp_path, monkeypatch):
    mrs = [{"iid": 1, "title": "A", "source_branch": "feat-a",
            "updated_at": "t"},
           {"iid": 2, "title": "B", "source_branch": "feat-b",
            "updated_at": "t"}]
    opmod, ctx = _env(tmp_path, monkeypatch, mrs,
                      lambda *a, **k: _prepared(a[2]))
    monkeypatch.setattr(opmod, "_local_tip",
                        lambda proj, b: {"feat-a": "a" * 40, "feat-b": "b" * 40}[b])
    monkeypatch.setattr(opmod, "_mirror_refs_safe",
                        lambda c, repo: {"feat-b": {"sha": "b" * 40}})
    monkeypatch.setattr(opmod, "_negotiate_mr_bases",
                        lambda c, proj, repo, branches: ([], False))
    monkeypatch.setattr(opmod, "_existing_shas", lambda proj, shas: [])

    res = opmod.op_mr_sync_all(ctx, {})

    by_iid = {m["iid"]: m for m in res["repos"][0]["mrs"]}
    assert by_iid[1]["branch_transfer"] == "sent"
    assert by_iid[2]["branch_transfer"] == "skipped"
    assert res["summary"]["review_status"] == "ready"


def test_branch_send_failure_marks_failed(tmp_path, monkeypatch):
    opmod, ctx = _env(tmp_path, monkeypatch, [MR],
                      lambda *a, **k: _prepared(24))

    def failing(*a, **k):
        raise LgmError("network", "Repository 'r' not found")

    monkeypatch.setattr(opmod, "send_branches", failing)

    res = opmod.op_mr_sync_all(ctx, {})

    item = res["repos"][0]["mrs"][0]
    assert item["branch_transfer"] == "failed"
    assert res["summary"]["review_status"] == "ready"
    assert res["success"] is False


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
