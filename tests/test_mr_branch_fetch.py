"""Real-git tests for _fetch_mr_branches: the local ref must be aligned to the
origin tip (created when missing, fast-forwarded when ancestor) and a local
ref with its own commits must stay untouched and be reported as divergent."""
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lgm_core.ops_mr as ops_mr
from lgm_core.client import MirrorClient
from lgm_core.config import Config
from lgm_core.op_models import Ctx
from lgm_core.ops import op_mr_send
from lgm_core.ops_mr import _diverged_warning, _fetch_mr_branches
from lgm_core.render import render


def _git(repo: Path, *args: str) -> str:
    proc = subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, timeout=30)
    assert proc.returncode == 0, f"git {' '.join(args)}: {proc.stderr}"
    return proc.stdout.strip()


def _commit(repo: Path, name: str, message: str) -> str:
    (repo / name).write_text(f"{name}\n", encoding="utf-8")
    _git(repo, "add", name)
    _git(repo, "commit", "-q", "-m", message)
    return _git(repo, "rev-parse", "HEAD")


def _setup(tmp_path: Path):
    # origin holds branch feat at c0; the work clone has only a local master.
    seed = tmp_path / "seed"
    seed.mkdir()
    _git(seed, "init", "-q", "-b", "master")
    _git(seed, "config", "user.email", "t@t")
    _git(seed, "config", "user.name", "t")
    c0 = _commit(seed, "a.txt", "c0")
    _git(seed, "branch", "feat")
    origin = tmp_path / "origin.git"
    subprocess.run(["git", "clone", "-q", "--bare", str(seed), str(origin)],
                   check=True, timeout=30)
    work = tmp_path / "work"
    subprocess.run(["git", "clone", "-q", str(origin), str(work)],
                   check=True, timeout=30)
    _git(work, "config", "user.email", "t@t")
    _git(work, "config", "user.name", "t")
    _git(seed, "remote", "add", "origin", str(origin))
    return seed, work, c0


def _push_origin_feat(seed: Path, name: str) -> str:
    _git(seed, "checkout", "-q", "feat")
    tip = _commit(seed, name, f"origin {name}")
    _git(seed, "push", "-q", "origin", "feat")
    _git(seed, "checkout", "-q", "master")
    return tip


def _local_feat_ahead(work: Path, name: str) -> str:
    _git(work, "checkout", "-q", "-b", "feat", "origin/feat")
    tip = _commit(work, name, f"local {name}")
    _git(work, "checkout", "-q", "master")
    return tip


def test_missing_local_branch_is_created_at_origin_tip(tmp_path):
    _seed, work, c0 = _setup(tmp_path)
    assert _fetch_mr_branches(work, ["feat"]) == []
    assert _git(work, "rev-parse", "refs/heads/feat") == c0


def test_local_behind_origin_is_fast_forwarded(tmp_path):
    seed, work, c0 = _setup(tmp_path)
    _git(work, "branch", "feat", c0)
    o1 = _push_origin_feat(seed, "o1.txt")
    assert _fetch_mr_branches(work, ["feat"]) == []
    assert _git(work, "rev-parse", "refs/heads/feat") == o1


def test_diverged_local_stays_untouched_and_is_reported(tmp_path):
    seed, work, c0 = _setup(tmp_path)
    l1 = _local_feat_ahead(work, "l1.txt")
    o1 = _push_origin_feat(seed, "o1.txt")
    assert l1 != o1
    assert _fetch_mr_branches(work, ["feat"]) == ["feat"]
    assert _git(work, "rev-parse", "refs/heads/feat") == l1


def test_local_ahead_of_origin_stays_untouched_and_is_reported(tmp_path):
    _seed, work, _c0 = _setup(tmp_path)
    l1 = _local_feat_ahead(work, "l1.txt")
    assert _fetch_mr_branches(work, ["feat"]) == ["feat"]
    assert _git(work, "rev-parse", "refs/heads/feat") == l1


def test_in_sync_branch_returns_nothing(tmp_path):
    _seed, work, c0 = _setup(tmp_path)
    _git(work, "branch", "feat", c0)
    assert _fetch_mr_branches(work, ["feat"]) == []
    assert _git(work, "rev-parse", "refs/heads/feat") == c0


def _ctx() -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    client = MirrorClient(base_url=config.base_url, api_key="", sync_password="pw")
    return Ctx(config=config, client=client)


def _run_mr_send(monkeypatch, work: Path, branches: str):
    ctx = _ctx()
    monkeypatch.setattr(ctx.client, "sync_refs",
                        lambda repo: {"success": True, "refs": {}})
    monkeypatch.setattr(ctx.client, "sync_negotiate",
                        lambda repo, commits, probe=False: {"created": False})
    sent_calls = []

    def fake_send_branches(ctx_, repo, project, branches_, excludes, dry_run=False):
        sent_calls.append(list(branches_))
        return {"bundle_size": 1}

    monkeypatch.setattr(ops_mr, "send_branches", fake_send_branches)
    res = op_mr_send(ctx, {"repo": "r", "project": str(work), "branch": branches})
    return res, sent_calls


def test_op_mr_send_excludes_divergent_branch(tmp_path, monkeypatch):
    seed, work, _c0 = _setup(tmp_path)
    l1 = _local_feat_ahead(work, "l1.txt")
    _git(seed, "branch", "ok")
    _git(seed, "push", "-q", "origin", "ok")

    res, sent_calls = _run_mr_send(monkeypatch, work, "feat,ok")

    assert [t["branch"] for t in res["sent"]] == ["ok"]
    assert res["warnings"] == [_diverged_warning("feat")]
    assert res["blocked"] == [{"iid": None, "branch": "feat",
                               "reason": "diverged from origin"}]
    assert sent_calls == [["ok"]]
    assert _git(work, "rev-parse", "refs/heads/feat") == l1


def test_op_mr_send_all_divergent_sends_nothing(tmp_path, monkeypatch):
    _seed, work, _c0 = _setup(tmp_path)
    l1 = _local_feat_ahead(work, "l1.txt")

    res, sent_calls = _run_mr_send(monkeypatch, work, "feat")

    assert res["sent"] == []
    assert res["warnings"] == [_diverged_warning("feat")]
    assert "blocked" in res["message"]
    assert sent_calls == []
    assert _git(work, "rev-parse", "refs/heads/feat") == l1


def test_op_mr_send_blocked_carries_the_mr_iid(tmp_path, monkeypatch):
    _seed, work, _c0 = _setup(tmp_path)
    _local_feat_ahead(work, "l1.txt")

    ctx = _ctx()
    monkeypatch.setattr(ctx.client, "sync_refs",
                        lambda repo: {"success": True, "refs": {}})
    monkeypatch.setattr(ctx.client, "gitlab_get_mr",
                        lambda iid, project="": {"iid": iid,
                                                 "source_branch": "feat"})
    sent_calls = []
    monkeypatch.setattr(ops_mr, "send_branches",
                        lambda *a, **k: sent_calls.append(a) or {"bundle_size": 1})

    res = op_mr_send(ctx, {"repo": "r", "project": str(work), "iid": 41})

    assert res["blocked"] == [{"iid": 41, "branch": "feat",
                               "reason": "diverged from origin"}]
    assert res["sent"] == []
    assert "blocked" in res["message"]
    assert sent_calls == []
    out = render("mr_send", res)
    assert "[blocked] feat !41" in out
    assert "diverged from origin" in out
