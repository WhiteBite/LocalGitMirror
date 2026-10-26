import pytest

from lgm_core.ops_mr import _fetch_mr_branches, op_mr_send
from test_ops_projects import _prepare_sync
from test_pull_worktree import git, incoming


def test_mr_fetch_keeps_current_worktree_aligned(incoming):
    ctx, home, work = incoming

    assert _fetch_mr_branches(work, ["feature"]) == []

    assert (work / "code.txt").read_text() == "fixed\n"
    assert git(work, "status", "--porcelain") == ""


def test_mr_fetch_initializes_unborn_current_branch(incoming, tmp_path):
    ctx, home, work = incoming
    empty = tmp_path / "empty"
    empty.mkdir()
    git(empty, "init", "-b", "feature")
    git(empty, "remote", "add", "origin", str(home))

    _fetch_mr_branches(empty, ["feature"])

    assert (empty / "code.txt").read_text() == "fixed\n"
    assert git(empty, "status", "--porcelain") == ""


def test_unchanged_mr_skips_discussions_request(tmp_path, monkeypatch):
    opmod, ctx, proj, sent, uploaded = _prepare_sync(tmp_path, monkeypatch)
    opmod.op_mr_sync_all(ctx, {"notes_only": True})
    ctx.client.gitlab_list_discussions = lambda *args, **kwargs: pytest.fail(
        "unchanged MR fetched discussions")

    result = opmod.op_mr_sync_all(ctx, {"notes_only": True})

    assert result["summary"]["notes_unchanged"] == 1


def test_mr_send_negotiates_shared_history_not_present_in_mirror_tips(incoming, monkeypatch, tmp_path):
    ctx, home, work = incoming
    base = git(work, "rev-parse", "HEAD")
    uploaded = []
    monkeypatch.setattr(ctx.client, "sync_refs", lambda repo: {"refs": {"unrelated": {"sha": "f" * 40}}})
    def negotiate(repo, commits, probe=False):
        assert base in commits
        return {"known": [base], "created": False}
    monkeypatch.setattr(ctx.client, "sync_negotiate", negotiate)
    monkeypatch.setattr(ctx.client, "sync_send", lambda repo, data: (
        uploaded.append(data), {"success": True})[1])

    result = op_mr_send(ctx, {"repo": "r", "project": str(work), "branch": "feature"})

    assert result["send"]["excluded_bases"] == 1
    bundle = tmp_path / "sent.bundle"
    bundle.write_bytes(uploaded[0])
    assert bundle.read_bytes().split(b"\n")[1].startswith(b"-" + base.encode())
    assert git(work, "status", "--porcelain") == ""
