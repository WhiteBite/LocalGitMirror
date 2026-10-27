import pytest

from lgm_core.client import LgmError
from lgm_core.ops_mr import op_mr_send
from lgm_core.ops_git import send_branches
from lgm_core import ops_projects
from test_pull_worktree import git, incoming


@pytest.mark.parametrize("batch", [False, True])
def test_mr_send_updates_ref_when_objects_are_known(incoming, monkeypatch, tmp_path, batch):
    ctx, home, work = incoming
    target = git(work, "rev-parse", "HEAD")
    descendant = git(home, "rev-parse", "HEAD")
    mirror = tmp_path / "mirror.git"
    git(tmp_path, "clone", "--bare", str(home), str(mirror))
    git(home, "branch", "target", target)
    git(work, "fetch", "origin")
    monkeypatch.setattr(ctx.client, "sync_refs", lambda repo: {
        "refs": {"feature": {"sha": descendant}}})
    monkeypatch.setattr(ctx.client, "sync_negotiate", lambda repo, commits: {
        "known": [target], "created": False})
    monkeypatch.setattr(ctx.client, "sync_send", lambda *args: pytest.fail(
        "known objects must not be uploaded"))

    def apply_known(repo, commit, branches=None, local_branches=None):
        for branch, sha in branches.items():
            git(mirror, "fetch", str(work), f"{sha}:refs/heads/{branch}")
        return {"success": True, "branches": list(branches)}

    monkeypatch.setattr(ctx.client, "sync_apply_known", apply_known)
    if batch:
        monkeypatch.setattr(ops_projects, "_REGISTRY_PATH", tmp_path / "registry.json")
        monkeypatch.setattr(ops_projects, "_STATE_PATH", tmp_path / "state.json")
        ops_projects._save_registry({"projects": [
            {"repo": "r", "path": str(work), "gitlab": "group/r"}]})
        monkeypatch.setattr(ctx.client, "gitlab_list_mrs", lambda project: [
            {"iid": 1, "source_branch": "target"}])
        monkeypatch.setattr(ctx.client, "gitlab_list_discussions", lambda *a, **k: [])
        monkeypatch.setattr(ctx.client, "file_sync_send", lambda *a: {"success": True})

    if batch:
        result = ops_projects.op_mr_sync_all(ctx, {})
        assert result["summary"]["branches_sent"] == 1
    else:
        op_mr_send(ctx, {"repo": "r", "project": str(work), "branch": "target"})

    assert git(mirror, "rev-parse", "refs/heads/target") == target
    assert git(work, "status", "--porcelain") == ""


def test_mr_send_reports_rejected_known_ref(incoming, monkeypatch):
    ctx, home, work = incoming
    target = git(work, "rev-parse", "HEAD")
    descendant = git(home, "rev-parse", "HEAD")
    git(home, "branch", "target", target)
    git(work, "fetch", "origin")
    monkeypatch.setattr(ctx.client, "sync_refs", lambda repo: {
        "refs": {"feature": {"sha": descendant}}})
    monkeypatch.setattr(ctx.client, "sync_negotiate", lambda repo, commits: {"known": [target]})
    monkeypatch.setattr(ctx.client, "sync_apply_known", lambda *a, **k: {
        "success": True, "branches": [], "rejected_branches": ["target"]})

    with pytest.raises(LgmError) as failure:
        op_mr_send(ctx, {"repo": "r", "project": str(work), "branch": "target"})

    assert failure.value.machine_code == "diverged"


def test_known_ref_dry_run_does_not_update_mirror(incoming, monkeypatch):
    ctx, home, work = incoming
    git(work, "fetch", "origin")
    descendant = git(home, "rev-parse", "HEAD")
    monkeypatch.setattr(ctx.client, "sync_apply_known", lambda *a, **k: pytest.fail(
        "dry-run must not update refs"))

    result = send_branches(ctx, "r", str(work), ["feature"], [descendant], dry_run=True)

    assert result["dry_run"] is True
    assert result["pointer_only"] is True
