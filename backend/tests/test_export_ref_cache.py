from tests.test_export_cache import (
    PASSWORD, _make_repo, _export, _bundle_refs, _push_commit, _run_git,
)


def test_export_cache_tracks_requested_branch_not_head(tmp_path, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    client, repo, bare, work, rm, storage = _make_repo(tmp_path)
    _run_git(bare, "branch", "stationary", "trunk")
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/stationary")
    _export(client, repo, branch="trunk")
    tip = _push_commit(work, bare, "new trunk commit")

    result = _export(client, repo, branch="trunk")

    refs, _ = _bundle_refs(result["data"], tmp_path, "updated")
    assert refs["refs/heads/trunk"] == tip


def test_export_all_cache_tracks_non_head_refs(tmp_path, monkeypatch):
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    client, repo, bare, work, rm, storage = _make_repo(tmp_path)
    workspace = rm._get_workspace_path(repo)
    _run_git(workspace, "fetch", str(bare), "trunk:refs/heads/trunk")
    _export(client, repo)
    tip = _push_commit(work, bare, "new trunk commit")
    _run_git(workspace, "fetch", str(bare), "trunk:refs/heads/trunk")

    result = _export(client, repo)

    refs, _ = _bundle_refs(result["data"], tmp_path, "updated-all")
    assert refs["refs/heads/trunk"] == tip
