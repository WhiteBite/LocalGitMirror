"""Inspect-before-act contract: preview_pull / has_commits ops wired to the
client methods, mr_send --dry-run moving no refs, the notes precheck failing
closed, prune bases without leaked defaults, mr_projects_scan --root required.
Git and HTTP are faked at the seams; dry-run proofs use real git repos."""
import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lgm
from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.ops import Ctx, REGISTRY, get_op, op_mr_send, op_mr_replies_send, op_prune


def _ctx(sync_password: str = "pw") -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password=sync_password)
    client = MirrorClient(base_url=config.base_url, api_key="",
                          sync_password=sync_password)
    return Ctx(config=config, client=client)


def _ctx_with(client) -> Ctx:
    return Ctx(config=Config(base_url="http://localhost:1", api_key="",
                             sync_password="pw"), client=client)


def _run_cli(monkeypatch, capsys, client, argv, sync_password="pw"):
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password=sync_password)
    monkeypatch.setattr(lgm, "_resolve_config", lambda args: config)
    monkeypatch.setattr(lgm, "_build_ctx", lambda cfg: Ctx(config=cfg, client=client))
    monkeypatch.setattr(sys, "argv", ["lgm", *argv])
    code = lgm.main()
    captured = capsys.readouterr()
    return code, captured.out, captured.err


def _git(repo: Path, *args: str) -> str:
    proc = subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0, proc.stderr
    return proc.stdout.strip()


def _repo_with_branch(tmp_path: Path, branch: str = "b1") -> Path:
    repo = tmp_path / "proj"
    repo.mkdir()
    _git(repo, "init", "-q")
    _git(repo, "-c", "user.email=t@t", "-c", "user.name=t",
         "commit", "--allow-empty", "-qm", "base")
    _git(repo, "branch", branch)
    return repo


# ── inspect ops: registry + client wiring ────────────────────────────────────

def test_preview_pull_registered():
    op = get_op("preview_pull")
    assert op is not None, "preview_pull op missing from REGISTRY"
    params = {p.name: p for p in op.params}
    assert params["repo"].required is True
    assert params["since"].type == "str" and params["since"].default == ""
    assert params["project"].type == "str" and params["project"].default == ""


def test_has_commits_registered():
    op = get_op("has_commits")
    assert op is not None, "has_commits op missing from REGISTRY"
    params = {p.name: p for p in op.params}
    assert params["repo"].required is True
    assert params["commits"].type == "str" and params["commits"].default == ""
    assert params["project"].type == "str" and params["project"].default == ""


def test_preview_pull_calls_client_method():
    from lgm_core.ops_git import op_preview_pull

    calls = {}

    class _Client:
        sync_password = "pw"

        def sync_preview_pull(self, repo, since=""):
            calls["args"] = (repo, since)
            return {"success": True, "repo": repo, "remoteHead": "abc",
                    "hasUpdates": True, "reason": "ahead"}

    ctx = Ctx(config=Config(base_url="http://localhost:1", api_key="",
                            sync_password="pw"), client=_Client())
    res = op_preview_pull(ctx, {"repo": "r", "since": "s1"})
    assert calls["args"] == ("r", "s1")
    assert res["hasUpdates"] is True
    json.dumps(res)


def test_has_commits_calls_client_method():
    from lgm_core.ops_git import op_has_commits

    calls = {}

    class _Client:
        sync_password = "pw"

        def sync_has_commits(self, repo, commits):
            calls["args"] = (repo, commits)
            return {"success": True, "repo": repo, "known": ["a"], "head": "h"}

    ctx = Ctx(config=Config(base_url="http://localhost:1", api_key="",
                            sync_password="pw"), client=_Client())
    res = op_has_commits(ctx, {"repo": "r", "commits": "a,b"})
    assert calls["args"] == ("r", ["a", "b"])
    assert res["known"] == ["a"]
    json.dumps(res)


def test_preview_pull_derives_since_from_project_head(tmp_path):
    from lgm_core.ops_git import op_preview_pull

    repo = _repo_with_branch(tmp_path)
    head = _git(repo, "rev-parse", "HEAD")
    calls = {}

    class _Client:
        sync_password = "pw"

        def sync_preview_pull(self, repo, since=""):
            calls["since"] = since
            return {"success": True, "repo": repo, "remoteHead": head,
                    "hasUpdates": False, "reason": "no-new-commits"}

    ctx = Ctx(config=Config(base_url="http://localhost:1", api_key="",
                            sync_password="pw"), client=_Client())
    op_preview_pull(ctx, {"repo": "r", "project": str(repo)})
    assert calls["since"] == head


def test_has_commits_derives_commits_from_local_tips(tmp_path):
    from lgm_core.ops_git import op_has_commits

    repo = _repo_with_branch(tmp_path, "b1")
    _git(repo, "branch", "b2")
    tips = set(_git(repo, "for-each-ref", "--format=%(objectname)",
                    "refs/heads/").split())
    calls = {}

    class _Client:
        sync_password = "pw"

        def sync_has_commits(self, repo, commits):
            calls["commits"] = commits
            return {"success": True, "repo": repo, "known": [], "head": "h"}

    ctx = Ctx(config=Config(base_url="http://localhost:1", api_key="",
                            sync_password="pw"), client=_Client())
    op_has_commits(ctx, {"repo": "r", "project": str(repo)})
    assert set(calls["commits"]) == tips


def test_has_commits_requires_commits_or_project():
    from lgm_core.ops_git import op_has_commits

    ctx = _ctx()
    with pytest.raises(LgmError) as ei:
        op_has_commits(ctx, {"repo": "r"})
    assert ei.value.code == "config"
    assert "--commits" in ei.value.message


def test_preview_pull_cli_json(monkeypatch, capsys):
    class _Client:
        sync_password = "pw"

        def sync_preview_pull(self, repo, since=""):
            return {"success": True, "repo": repo, "remoteHead": "abc",
                    "hasUpdates": True, "reason": "ahead"}

    code, out, err = _run_cli(monkeypatch, capsys, _Client(),
                              ["preview_pull", "--repo", "r", "--json"])
    assert code == 0, f"exit={code} stdout={out} stderr={err}"
    data = json.loads(out)
    assert data["repo"] == "r"
    assert data["hasUpdates"] is True


def test_has_commits_cli_json(monkeypatch, capsys):
    class _Client:
        sync_password = "pw"

        def sync_has_commits(self, repo, commits):
            return {"success": True, "repo": repo, "known": commits,
                    "head": "h"}

    code, out, err = _run_cli(monkeypatch, capsys, _Client(),
                              ["has_commits", "--repo", "r",
                               "--commits", "a,b", "--json"])
    assert code == 0, f"exit={code} stdout={out} stderr={err}"
    data = json.loads(out)
    assert data["known"] == ["a", "b"]


# ── mr_send --dry-run: plan without touching refs ────────────────────────────

class _MrSendClient:
    def __init__(self, mrs: dict, mirror_refs: dict, created: bool = False):
        self.sync_password = "pw"
        self.mrs = mrs
        self.mirror_refs = mirror_refs
        self.created = created
        self.sent_bundles: list[bytes] = []
        self.negotiate_calls: list[tuple[str, bool]] = []

    def gitlab_get_mr(self, iid, project=""):
        return {"iid": iid, "source_branch": self.mrs[iid]}

    def gitlab_list_mrs(self, project=""):
        return [{"iid": i, "source_branch": b} for i, b in self.mrs.items()]

    def sync_refs(self, repo):
        return {"success": True, "refs": self.mirror_refs}

    def sync_negotiate(self, repo, commits, probe=False):
        self.negotiate_calls.append((repo, probe))
        return {"success": True, "repo": repo, "created": self.created,
                "refs": {}, "head": "", "known": []}

    def sync_send(self, repo, bundle):
        self.sent_bundles.append(bundle)
        return {"success": True, "repo": repo}


def test_mr_send_dry_run_param_registered():
    op = get_op("mr_send")
    params = {p.name: p for p in op.params}
    assert params["dry_run"].type == "bool"
    assert params["dry_run"].default is False


def test_mr_send_dry_run_moves_no_refs_and_prints_plan(tmp_path):
    repo = _repo_with_branch(tmp_path, "b1")
    tip_before = _git(repo, "rev-parse", "refs/heads/b1")
    client = _MrSendClient(mrs={41: "b1"}, mirror_refs={})

    res = op_mr_send(_ctx_with(client), {"repo": "r", "project": str(repo),
                              "iid": 41, "dry_run": True})

    assert _git(repo, "rev-parse", "refs/heads/b1") == tip_before
    assert client.sent_bundles == []
    assert res["dry_run"] is True
    assert [t["branch"] for t in res["sent"]] == ["b1"]
    assert res["skipped"] == []
    assert "dry-run" in res["message"]
    json.dumps(res)


def test_mr_send_dry_run_skips_branches_mirror_already_has(tmp_path):
    repo = _repo_with_branch(tmp_path, "b1")
    tip = _git(repo, "rev-parse", "refs/heads/b1")
    client = _MrSendClient(mrs={41: "b1"},
                           mirror_refs={"b1": {"sha": tip}})

    res = op_mr_send(_ctx_with(client), {"repo": "r", "project": str(repo),
                              "iid": 41, "dry_run": True})

    assert res["sent"] == []
    assert [t["branch"] for t in res["skipped"]] == ["b1"]
    assert client.sent_bundles == []


def test_mr_send_dry_run_cli_json(monkeypatch, capsys, tmp_path):
    repo = _repo_with_branch(tmp_path, "b1")
    client = _MrSendClient(mrs={41: "b1"}, mirror_refs={})
    code, out, err = _run_cli(monkeypatch, capsys, client,
                              ["mr_send", "--repo", "r", "--project", str(repo),
                               "--iid", "41", "--dry-run", "--json"])
    assert code == 0, f"exit={code} stdout={out} stderr={err}"
    data = json.loads(out)
    assert data["dry_run"] is True
    assert [t["branch"] for t in data["sent"]] == ["b1"]
    assert client.sent_bundles == []


def test_mr_send_help_lists_dry_run_flag():
    proc = subprocess.run(
        [sys.executable, str(Path(__file__).resolve().parent.parent / "lgm.py"),
         "mr_send", "--help"],
        capture_output=True, text=True, timeout=30,
    )
    assert proc.returncode == 0
    assert "--dry-run" in proc.stdout


# ── notes precheck: fail closed when notes are unavailable ────────────────────

class _PostboxClient:
    def __init__(self, items=None, list_error: LgmError | None = None):
        self.sync_password = ""
        self._items = items or []
        self._list_error = list_error
        self.sent = None

    def file_sync_list(self, repo):
        if self._list_error is not None:
            raise self._list_error
        return {"items": self._items}

    def file_sync_fetch(self, repo, item_id):
        raise AssertionError("notes fetch must not happen when no note exists")

    def file_sync_send(self, repo, path, plain_size, data):
        self.sent = (repo, path, plain_size, data)
        return {"id": "new1"}


def test_precheck_fails_closed_when_notes_expired():
    client = _PostboxClient(items=[])
    res = op_mr_replies_send(_ctx_with(client), {"repo": "r", "iid": 7,
                                       "text": "## thread t1\nfix"})
    assert res["success"] is False
    assert "unavailable" in res["error"]
    assert res["precheck"]["status"] == "rejected"
    assert client.sent is None


def test_precheck_force_overrides_expired_notes():
    client = _PostboxClient(items=[])
    res = op_mr_replies_send(_ctx_with(client), {"repo": "r", "iid": 7,
                                       "text": "## thread t1\nfix",
                                       "force": True})
    assert res["success"] is True
    assert client.sent is not None


def test_precheck_fails_closed_when_postbox_unreachable():
    client = _PostboxClient(list_error=LgmError("network", "mirror unreachable"))
    res = op_mr_replies_send(_ctx_with(client), {"repo": "r", "iid": 7,
                                       "text": "## new Foo.java:75\nfix"})
    assert res["success"] is False
    assert "unavailable" in res["error"]
    assert client.sent is None


def test_precheck_unanchored_new_sections_need_no_notes():
    client = _PostboxClient(items=[])
    res = op_mr_replies_send(_ctx_with(client), {"repo": "r", "iid": 7,
                                       "text": "## new\nanswer"})
    assert res["success"] is True
    assert client.sent is not None


def test_expired_notes_refusal_exits_nonzero_with_json_envelope(monkeypatch, capsys):
    client = _PostboxClient(items=[])
    code, out, err = _run_cli(monkeypatch, capsys, client,
                              ["mr_replies_send", "--repo", "r", "--iid", "7",
                               "--text", "## thread t1\nfix", "--json"])
    assert code != 0, f"exit={code} stdout={out}"
    env = json.loads(out)
    assert env["success"] is False
    assert env["error"]["kind"] == "op_failure"
    assert "unavailable" in env["error"]["message"]


# ── prune: no leaked branch-convention defaults ───────────────────────────────

def test_prune_bases_default_is_empty():
    op = get_op("prune")
    params = {p.name: p for p in op.params}
    assert params["bases"].default == ""


def test_prune_requires_bases_or_older_days():
    with pytest.raises(LgmError) as ei:
        op_prune(_ctx(), {"repo": "r"})
    assert ei.value.code == "config"
    assert "--bases" in ei.value.message


def test_prune_age_only_without_bases(monkeypatch):
    calls = {}

    def stub(repo, bases=None, older_days=0, keep=None, apply=False):
        calls["args"] = (repo, bases, older_days)
        return {"success": True, "repo": repo}

    ctx = _ctx()
    monkeypatch.setattr(ctx.client, "prune_branches", stub)
    op_prune(ctx, {"repo": "r", "older_days": 30})
    assert calls["args"] == ("r", [], 30)


def test_prune_explicit_bases_passed_through(monkeypatch):
    calls = {}

    def stub(repo, bases=None, older_days=0, keep=None, apply=False):
        calls["args"] = (repo, bases, older_days)
        return {"success": True, "repo": repo}

    ctx = _ctx()
    monkeypatch.setattr(ctx.client, "prune_branches", stub)
    op_prune(ctx, {"repo": "r", "bases": "main,release"})
    assert calls["args"] == ("r", ["main", "release"], 0)


# ── mr_projects_scan: --root is actually required ─────────────────────────────

def test_mr_projects_scan_root_required():
    op = get_op("mr_projects_scan")
    params = {p.name: p for p in op.params}
    assert params["root"].required is True


def test_mr_projects_scan_missing_root_is_usage_error(monkeypatch, capsys):
    class _NoopClient:
        sync_password = "pw"

    code, out, err = _run_cli(monkeypatch, capsys, _NoopClient(),
                              ["mr_projects_scan", "--json"])
    assert code == 2, f"exit={code} stdout={out} stderr={err}"


# ── unregistered repo: typed refusal, not "nothing incoming" ─────────────────

def test_preview_pull_refuses_unregistered_repo():
    from lgm_core.ops_git import op_preview_pull

    class _Client:
        sync_password = "pw"

        def sync_preview_pull(self, repo, since=""):
            return {"success": True, "repo": repo, "remoteHead": None,
                    "hasUpdates": False, "reason": "repo-not-found"}

    with pytest.raises(LgmError) as ei:
        op_preview_pull(_ctx_with(_Client()), {"repo": "ghost"})
    assert ei.value.code == "config"
    assert ei.value.machine_code == "repo-not-found"
    assert "not registered" in ei.value.message


def test_has_commits_refuses_unregistered_repo():
    from lgm_core.ops_git import op_has_commits

    class _Client:
        sync_password = "pw"

        def sync_has_commits(self, repo, commits):
            return {"success": True, "repo": repo, "known": []}

        def sync_refs(self, repo):
            raise LgmError(404, '{"detail":"Repository not found"}')

    with pytest.raises(LgmError) as ei:
        op_has_commits(_ctx_with(_Client()), {"repo": "ghost",
                                              "commits": "a" * 40})
    assert ei.value.code == "config"
    assert ei.value.machine_code == "repo-not-found"
    assert "not registered" in ei.value.message


def test_has_commits_registered_repo_with_empty_known_passes():
    from lgm_core.ops_git import op_has_commits

    class _Client:
        sync_password = "pw"

        def sync_has_commits(self, repo, commits):
            return {"success": True, "repo": repo, "known": []}

        def sync_refs(self, repo):
            return {"success": True, "refs": {}}

    res = op_has_commits(_ctx_with(_Client()), {"repo": "r",
                                                "commits": "a" * 40})
    assert res["known"] == []


def test_has_commits_skips_registration_probe_when_head_present():
    from lgm_core.ops_git import op_has_commits

    class _Client:
        sync_password = "pw"

        def sync_has_commits(self, repo, commits):
            return {"success": True, "repo": repo, "known": [], "head": "h"}

        def sync_refs(self, repo):
            raise AssertionError("sync_refs must not run when head is present")

    res = op_has_commits(_ctx_with(_Client()), {"repo": "r",
                                                "commits": "a" * 40})
    assert res["known"] == []


def test_branches_refuses_unregistered_repo():
    from lgm_core.ops_git import op_branches

    class _Client:
        sync_password = "pw"

        def sync_refs(self, repo):
            raise LgmError(404, '{"detail":"Repository not found"}')

    with pytest.raises(LgmError) as ei:
        op_branches(_ctx_with(_Client()), {"repo": "ghost"})
    assert ei.value.code == "config"
    assert ei.value.machine_code == "repo-not-found"
    assert "not registered" in ei.value.message


def test_branches_data_not_found_404_is_not_repo_not_found():
    from lgm_core.ops_git import op_branches

    class _Client:
        sync_password = "pw"

        def sync_refs(self, repo):
            raise LgmError(404, '{"detail":"Repository data not found"}')

    with pytest.raises(LgmError) as ei:
        op_branches(_ctx_with(_Client()), {"repo": "r"})
    assert ei.value.machine_code != "repo-not-found"


# ── send / mr_send: the negotiate repo creation is surfaced ──────────────────

def test_send_surfaces_created_flag(tmp_path):
    from lgm_core.ops_git import op_send

    repo = _repo_with_branch(tmp_path, "b1")
    client = _MrSendClient(mrs={}, mirror_refs={}, created=True)

    res = op_send(_ctx_with(client), {"repo": "r", "project": str(repo),
                                      "branch": "b1"})
    assert res["created"] is True
    assert client.negotiate_calls == [("r", False)]
    assert len(client.sent_bundles) == 1


def test_send_created_false_for_existing_repo(tmp_path):
    from lgm_core.ops_git import op_send

    repo = _repo_with_branch(tmp_path, "b1")
    client = _MrSendClient(mrs={}, mirror_refs={})

    res = op_send(_ctx_with(client), {"repo": "r", "project": str(repo),
                                      "branch": "b1"})
    assert res["created"] is False


def test_mr_send_surfaces_created_flag(tmp_path, monkeypatch):
    import lgm_core.ops_mr as ops_mr

    repo = _repo_with_branch(tmp_path, "b1")
    monkeypatch.setattr(ops_mr, "_fetch_mr_branches", lambda proj, branches: [])
    client = _MrSendClient(mrs={41: "b1"}, mirror_refs={}, created=True)

    res = op_mr_send(_ctx_with(client), {"repo": "r", "project": str(repo),
                                         "iid": 41})
    assert res["created"] is True
    assert client.negotiate_calls == [("r", False)]
    assert len(client.sent_bundles) == 1


# ── mr_send: dirty worktree refusal (plugin parity) ──────────────────────────

def _repo_with_tracked_file(tmp_path: Path, branch: str = "b1") -> Path:
    repo = _repo_with_branch(tmp_path, branch)
    (repo / "tracked.txt").write_text("base\n", encoding="utf-8")
    _git(repo, "add", "tracked.txt")
    _git(repo, "-c", "user.email=t@t", "-c", "user.name=t",
         "commit", "-qm", "file")
    return repo


def test_mr_send_refuses_dirty_worktree(tmp_path):
    repo = _repo_with_tracked_file(tmp_path)
    (repo / "tracked.txt").write_text("dirty\n", encoding="utf-8")
    client = _MrSendClient(mrs={41: "b1"}, mirror_refs={})

    with pytest.raises(LgmError) as ei:
        op_mr_send(_ctx_with(client), {"repo": "r", "project": str(repo),
                                       "iid": 41})
    assert ei.value.code == "config"
    assert ei.value.machine_code == "dirty-worktree"
    assert "tracked.txt" in ei.value.message
    assert "stash" in ei.value.message
    assert client.sent_bundles == []
    assert client.negotiate_calls == []


def test_mr_send_untracked_files_do_not_block(tmp_path, monkeypatch):
    import lgm_core.ops_mr as ops_mr

    repo = _repo_with_tracked_file(tmp_path)
    (repo / "untracked.txt").write_text("user data\n", encoding="utf-8")
    monkeypatch.setattr(ops_mr, "_fetch_mr_branches", lambda proj, branches: [])
    client = _MrSendClient(mrs={41: "b1"}, mirror_refs={})

    res = op_mr_send(_ctx_with(client), {"repo": "r", "project": str(repo),
                                         "iid": 41})
    assert res["created"] is False
    assert len(client.sent_bundles) == 1
