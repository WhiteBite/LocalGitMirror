"""WORK-side MR review snapshot collector: real temp git repos (origin + clone)
with the GitLab HTTP surface faked. Covers the plan's acceptance (canonical
diff, fork MR ref, stable snapshot across local HEADs, no false fresh) and the
fail-closed scenarios (race, mid-paging failure, diff-not-ready,
review-object-missing, review-package-too-large)."""
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import config as config_mod
from lgm_core import mr_review_collect as collect
from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.mr_review_models import MrReviewError, verify_snapshot

PROJECT = "dev/onyx/phonyx"
FIXED_NOW = datetime(2026, 10, 30, 9, 0, 0, tzinfo=timezone.utc)


def _client(monkeypatch) -> MirrorClient:
    env = {"GITLAB_URL": "https://gitlab.corp", "GITLAB_TOKEN": "tok",
           "GITLAB_PROJECT": PROJECT}
    monkeypatch.setattr(config_mod, "_ENV", env)
    for key in ("GITLAB_URL", "GITLAB_TOKEN", "GITLAB_PROJECT"):
        monkeypatch.delenv(key, raising=False)
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    return MirrorClient(base_url=config.base_url, api_key=config.api_key,
                        sync_password=config.sync_password)


def _git(repo: Path, *args: str) -> str:
    proc = subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0, proc.stderr
    return proc.stdout.strip()


def _commit(repo: Path, path: str, text: str, message: str) -> str:
    (repo / path).write_text(text, encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-q", "-m", message)
    return _git(repo, "rev-parse", "HEAD")


def _init_repo(repo: Path) -> None:
    repo.mkdir(parents=True)
    _git(repo, "init", "-q", "-b", "master")
    _git(repo, "config", "user.email", "t@t")
    _git(repo, "config", "user.name", "t")


def _make_origin(tmp_path: Path) -> tuple[Path, dict]:
    origin = tmp_path / "origin"
    _init_repo(origin)
    base = _commit(origin, "F.txt", "base\n", "base")
    _git(origin, "checkout", "-q", "-b", "feature")
    head = _commit(origin, "F.txt", "base\nfeature\n", "feature")
    _git(origin, "checkout", "-q", "master")
    target_only = _commit(origin, "T.txt", "target-only\n", "target only")
    return origin, {"base": base, "head": head, "target_only": target_only,
                    "start": target_only, "tip": target_only}


def _make_criss_cross_origin(tmp_path: Path) -> tuple[Path, dict]:
    origin = tmp_path / "origin"
    _init_repo(origin)
    _commit(origin, "R.txt", "r\n", "root")
    _git(origin, "checkout", "-q", "-b", "feature")
    f1 = _commit(origin, "F.txt", "f1\n", "f1")
    _git(origin, "checkout", "-q", "master")
    m1 = _commit(origin, "M.txt", "m1\n", "m1")
    _git(origin, "merge", "-q", "--no-edit", "--no-ff", "feature")
    m2 = _git(origin, "rev-parse", "HEAD")
    _git(origin, "checkout", "-q", "feature")
    _git(origin, "merge", "-q", "--no-edit", "--no-ff", m1)
    f2 = _git(origin, "rev-parse", "HEAD")
    _git(origin, "checkout", "-q", "master")
    return origin, {"base": m1, "head": f2, "start": m2, "tip": m2,
                    "m1": m1, "f1": f1}


def _clone(origin: Path, dest: Path) -> Path:
    subprocess.run(["git", "clone", "-q", str(origin), str(dest)],
                   capture_output=True, text=True, timeout=60, check=True)
    _git(dest, "config", "user.email", "t@t")
    _git(dest, "config", "user.name", "t")
    return dest


def _mr_detail(shas: dict, **overrides) -> dict:
    detail = {
        "iid": 30,
        "project_id": 42,
        "source_project_id": 42,
        "target_project_id": 42,
        "title": "Add review workspace",
        "description": "First cut",
        "state": "opened",
        "draft": False,
        "source_branch": "feature",
        "target_branch": "master",
        "sha": shas["head"],
        "updated_at": "2026-10-30T08:59:00Z",
        "web_url": "https://gitlab.corp/dev/onyx/phonyx/-/merge_requests/30",
        "diff_refs": {
            "base_sha": shas["base"],
            "start_sha": shas["start"],
            "head_sha": shas["head"],
        },
    }
    detail.update(overrides)
    return detail


def _note(note_id: int) -> dict:
    return {"id": note_id, "body": f"note {note_id}",
            "author": {"name": "A"}, "created_at": "2026-10-01T00:00:00Z",
            "system": False, "resolvable": False, "resolved": False}


def _raw_discussions(shas: dict) -> list:
    refs = {"base_sha": shas["base"], "start_sha": shas["start"],
            "head_sha": shas["head"]}
    return [
        {"id": "ccd35f", "notes": [
            {"id": 901, "author": {"name": "Daniil Minkin"},
             "created_at": "2026-10-02T13:10:48.092Z", "system": False,
             "body": "тред открыт", "resolvable": True, "resolved": False,
             "position": {"old_path": "F.txt", "new_path": "F.txt",
                          "old_line": 1, "new_line": 2, **refs}},
            {"id": 902, "author": {"name": "Ivan"},
             "created_at": "2026-10-02T13:20:00Z", "system": False,
             "body": "ответ автора", "resolvable": False},
        ]},
        {"id": "sys", "notes": [
            {"id": 903, "author": {"name": "GitLab"},
             "created_at": "2026-09-01T09:00:00Z", "system": True,
             "body": "assigned to @i.spirkov"},
        ]},
        {"id": "del", "notes": [
            {"id": 904, "author": {"name": "Aleksandr"},
             "created_at": "2026-10-01T10:00:00Z", "system": False,
             "body": "удалённая строка", "resolvable": True, "resolved": True,
             "position": {"old_path": "G.txt", "old_line": 5, **refs}},
        ]},
    ]


KEEP_REAL = object()


def _patch_gitlab(monkeypatch, client, details: list, tips: list,
                  discussions: list | object | None = None) -> None:
    state = {"detail": 0, "tip": 0}

    def get_mr(iid, project=""):
        index = min(state["detail"], len(details) - 1)
        state["detail"] += 1
        return details[index]

    def branch_tip(branch, project=""):
        index = min(state["tip"], len(tips) - 1)
        state["tip"] += 1
        return tips[index]

    monkeypatch.setattr(client, "gitlab_get_mr", get_mr)
    monkeypatch.setattr(client, "gitlab_branch_tip", branch_tip)
    if discussions is not KEEP_REAL:
        monkeypatch.setattr(client, "gitlab_list_discussions",
                            lambda iid, project="": discussions or [])


def _repo_state(repo: Path) -> str:
    refs = _git(repo, "for-each-ref", "--format=%(refname) %(objectname)")
    head = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"],
                          capture_output=True, text=True, timeout=60).stdout.strip()
    sym = subprocess.run(["git", "-C", str(repo), "symbolic-ref", "-q", "HEAD"],
                         capture_output=True, text=True, timeout=60).stdout.strip()
    return f"{refs}\nHEAD {head} {sym}"


def _fresh_bare(tmp_path: Path, name: str) -> Path:
    fresh = tmp_path / name
    fresh.mkdir()
    _git(fresh, "init", "-q", "--bare")
    return fresh


def _collect(client, checkout: Path, work_dir: Path) -> dict:
    return collect.collect_mr_snapshot(client, "phonyx", 30, PROJECT, checkout,
                                       work_dir=work_dir)


def test_collect_happy_path_snapshot_bundle_and_discussions(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas)], [shas["tip"]],
                  discussions=_raw_discussions(shas))
    monkeypatch.setattr(collect, "_now", lambda: FIXED_NOW)
    before = (_repo_state(origin), _repo_state(checkout))
    out = tmp_path / "out"

    result = _collect(client, checkout, out)

    manifest = result["manifest"]
    verify_snapshot(manifest)
    assert manifest["source_sha"] == shas["head"]
    assert manifest["target_tip_sha"] == shas["tip"]
    assert manifest["source_fetch_ref"] is None
    assert manifest["computed_merge_bases"] == [shas["base"]]
    assert manifest["comparison_base_sha"] == shas["base"]
    assert manifest["comparison_kind"] == "gitlab-diff"
    assert manifest["gitlab_url"] == "https://gitlab.corp"
    assert manifest["observed_started_at"] == "2026-10-30T09:00:00Z"
    assert manifest["discussions_complete"] is True
    assert manifest["limitations"] == []
    assert manifest["discussions"][0]["notes"][0]["position"]["side"] == "new"
    assert manifest["discussions"][0]["notes"][0]["position"]["new_line"] == 2
    assert manifest["discussions"][2]["notes"][0]["position"]["side"] == "old"
    assert manifest["discussions"][2]["notes"][0]["position"]["new_line"] is None
    assert manifest["discussions"][1]["notes"][0]["system"] is True
    assert result["code_id"] == manifest["code_id"]
    assert result["digests"]["snapshot_id"] == manifest["snapshot_id"]

    bundle = Path(result["bundle_path"])
    assert bundle == out / f"{manifest['code_id']}.bundle"
    assert bundle.stat().st_size == result["bundle_size"]
    assert [p.name for p in out.iterdir()] == [bundle.name]
    assert (_repo_state(origin), _repo_state(checkout)) == before

    fresh = _fresh_bare(tmp_path, "fresh.git")
    verify = subprocess.run(["git", "-C", str(fresh), "bundle", "verify", str(bundle)],
                            capture_output=True, text=True, timeout=60)
    assert verify.returncode == 0, verify.stderr
    assert "complete history" in verify.stdout
    _git(fresh, "fetch", str(bundle), "+refs/review/*:refs/review/*")
    for sha in {shas["head"], shas["tip"], shas["base"]}:
        _git(fresh, "cat-file", "-e", f"{sha}^{{commit}}")


def test_target_only_commits_stay_out_of_canonical_diff(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas)], [shas["tip"]])
    monkeypatch.setattr(collect, "_now", lambda: FIXED_NOW)

    result = _collect(client, checkout, tmp_path / "out")
    manifest = result["manifest"]

    assert manifest["comparison_base_sha"] == shas["base"]
    assert manifest["gitlab_diff_refs"]["head_sha"] == shas["head"]

    fresh = _fresh_bare(tmp_path, "fresh.git")
    _git(fresh, "fetch", result["bundle_path"], "+refs/review/*:refs/review/*")
    heads = _git(fresh, "bundle", "list-heads", result["bundle_path"]).splitlines()
    assert f"{shas['base']} refs/review/diff_base" in heads
    assert f"{shas['head']} refs/review/diff_head" in heads
    assert f"{shas['tip']} refs/review/target_tip" in heads
    revs = _git(fresh, "rev-list", f"{shas['base']}..{shas['head']}").split()
    assert revs == [shas["head"]]
    assert shas["target_only"] not in revs
    assert _git(fresh, "diff", "--name-only",
                f"{shas['base']}..{shas['head']}") == "F.txt"


def test_fork_source_fetched_through_mr_ref(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    _git(origin, "update-ref", "refs/merge-requests/30/head", shas["head"])
    _git(origin, "branch", "-D", "feature")
    checkout = _clone(origin, tmp_path / "checkout")
    detail = _mr_detail(shas, source_project_id=77)
    _patch_gitlab(monkeypatch, client, [detail], [shas["tip"]])
    monkeypatch.setattr(collect, "_now", lambda: FIXED_NOW)

    result = _collect(client, checkout, tmp_path / "out")

    manifest = result["manifest"]
    verify_snapshot(manifest)
    assert manifest["source_fetch_ref"] == "refs/merge-requests/30/head"
    assert manifest["source_sha"] == shas["head"]
    assert "source branch fetched through target-project MR ref" in manifest["limitations"]
    fresh = _fresh_bare(tmp_path, "fresh.git")
    _git(fresh, "fetch", result["bundle_path"], "+refs/review/*:refs/review/*")
    _git(fresh, "cat-file", "-e", f"{shas['head']}^{{commit}}")


def test_stable_snapshot_with_different_local_heads(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas)], [shas["tip"]])
    monkeypatch.setattr(collect, "_now", lambda: FIXED_NOW)

    first = _collect(client, checkout, tmp_path / "a")
    _git(checkout, "checkout", "-q", "--detach", shas["base"])
    second = _collect(client, checkout, tmp_path / "b")

    assert first["digests"] == second["digests"]
    assert first["manifest"] == second["manifest"]


def test_no_false_fresh_when_updated_at_equal_but_source_moved(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    detail_b = _mr_detail(shas, sha=shas["tip"])
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas), detail_b], [shas["tip"]])
    out = tmp_path / "out"

    with pytest.raises(MrReviewError) as excinfo:
        _collect(client, checkout, out)

    assert excinfo.value.code == "snapshot-raced"
    assert "sha" in excinfo.value.message
    assert not list(out.glob("*.bundle"))


@pytest.mark.parametrize("mutate", [
    "target_tip", "draft", "state", "updated_at", "diff_refs",
])
def test_detail_mutation_between_a_and_b_races(tmp_path, monkeypatch, mutate):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    detail_b = _mr_detail(shas)
    tip_b = shas["tip"]
    if mutate == "target_tip":
        tip_b = shas["base"]
    elif mutate == "draft":
        detail_b["draft"] = True
    elif mutate == "state":
        detail_b["state"] = "merged"
    elif mutate == "updated_at":
        detail_b["updated_at"] = "2026-10-30T09:30:00Z"
    else:
        detail_b["diff_refs"] = dict(detail_b["diff_refs"], base_sha=shas["tip"])
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas), detail_b],
                  [shas["tip"], tip_b])
    before = (_repo_state(origin), _repo_state(checkout))
    out = tmp_path / "out"

    with pytest.raises(MrReviewError) as excinfo:
        _collect(client, checkout, out)

    assert excinfo.value.code == "snapshot-raced"
    expected = {"target_tip": "target_tip_sha", "draft": "draft", "state": "state",
                "updated_at": "updated_at", "diff_refs": "diff_refs.base_sha"}[mutate]
    assert expected in excinfo.value.message
    assert (_repo_state(origin), _repo_state(checkout)) == before
    assert not list(out.glob("*.bundle"))


def test_discussion_page_failure_mid_paging_fails_closed(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas)], [shas["tip"]],
                  discussions=KEEP_REAL)
    pages = {1: ([{"id": "d1", "notes": [_note(1)]}], "2")}

    def paged(url, token):
        page = int(url.rsplit("page=", 1)[1])
        if page not in pages:
            raise LgmError(500, "GitLab API error 500: boom")
        return pages[page]

    monkeypatch.setattr(client, "_gitlab_get_paged", paged)
    before = (_repo_state(origin), _repo_state(checkout))
    out = tmp_path / "out"

    with pytest.raises(LgmError) as excinfo:
        _collect(client, checkout, out)

    assert "500" in str(excinfo.value)
    assert (_repo_state(origin), _repo_state(checkout)) == before
    assert not list(out.glob("*.bundle"))


def test_multi_page_discussions_collected_completely(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas)], [shas["tip"]],
                  discussions=KEEP_REAL)
    pages = {1: ([{"id": "d1", "notes": [_note(1)]}], "2"),
             2: ([{"id": "d2", "notes": [_note(2)]}], "")}
    monkeypatch.setattr(
        client, "_gitlab_get_paged",
        lambda url, token: pages[int(url.rsplit("page=", 1)[1])])

    result = _collect(client, checkout, tmp_path / "out")

    assert [d["discussion_id"] for d in result["manifest"]["discussions"]] == ["d1", "d2"]
    assert result["manifest"]["discussions_complete"] is True


@pytest.mark.parametrize("diff_refs", [
    None,
    {"base_sha": "", "start_sha": "x", "head_sha": "y"},
])
def test_empty_diff_refs_fail_as_diff_not_ready(tmp_path, monkeypatch, diff_refs):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas, diff_refs=diff_refs)],
                  [shas["tip"]])

    with pytest.raises(MrReviewError) as excinfo:
        _collect(client, checkout, tmp_path / "out")

    assert excinfo.value.code == "diff-not-ready"


def test_missing_commit_after_fetch_fails_closed(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    ghost = "0" * 40
    detail = _mr_detail(shas)
    detail["diff_refs"] = dict(detail["diff_refs"], start_sha=ghost)
    _patch_gitlab(monkeypatch, client, [detail], [shas["tip"]])
    before = (_repo_state(origin), _repo_state(checkout))
    out = tmp_path / "out"

    with pytest.raises(MrReviewError) as excinfo:
        _collect(client, checkout, out)

    assert excinfo.value.code == "review-object-missing"
    assert ghost in excinfo.value.message
    assert (_repo_state(origin), _repo_state(checkout)) == before
    assert not list(out.glob("*.bundle"))


def test_oversized_bundle_fails_closed_with_actual_size(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas)], [shas["tip"]])
    monkeypatch.setattr(collect, "CODE_BUNDLE_MAX_BYTES", 16)
    out = tmp_path / "out"

    with pytest.raises(MrReviewError) as excinfo:
        _collect(client, checkout, out)

    assert excinfo.value.code == "review-package-too-large"
    assert "bytes" in excinfo.value.message
    assert "16" in excinfo.value.message
    assert not list(out.glob("*.bundle"))


def test_merge_base_mismatch_recorded_as_limitation(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    detail = _mr_detail(shas)
    detail["diff_refs"] = dict(detail["diff_refs"], base_sha=shas["target_only"])
    _patch_gitlab(monkeypatch, client, [detail], [shas["tip"]])
    monkeypatch.setattr(collect, "_now", lambda: FIXED_NOW)

    result = _collect(client, checkout, tmp_path / "out")

    manifest = result["manifest"]
    verify_snapshot(manifest)
    assert manifest["computed_merge_bases"] == [shas["base"]]
    assert any("not among computed merge bases" in limitation
               for limitation in manifest["limitations"])
    assert manifest["comparison_base_sha"] == shas["target_only"]


def test_criss_cross_merge_bases_all_preserved(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_criss_cross_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, [_mr_detail(shas)], [shas["tip"]])
    monkeypatch.setattr(collect, "_now", lambda: FIXED_NOW)

    result = _collect(client, checkout, tmp_path / "out")

    manifest = result["manifest"]
    verify_snapshot(manifest)
    assert set(manifest["computed_merge_bases"]) == {shas["m1"], shas["f1"]}
    assert len(manifest["computed_merge_bases"]) == 2
    assert manifest["limitations"] == []


def test_fork_mr_ref_moved_fails_as_race(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    _git(origin, "update-ref", "refs/merge-requests/30/head", shas["base"])
    _git(origin, "branch", "-D", "feature")
    checkout = _clone(origin, tmp_path / "checkout")
    detail = _mr_detail(shas, source_project_id=77)
    _patch_gitlab(monkeypatch, client, [detail], [shas["tip"]])
    before = (_repo_state(origin), _repo_state(checkout))
    out = tmp_path / "out"

    with pytest.raises(MrReviewError) as excinfo:
        _collect(client, checkout, out)

    assert excinfo.value.code == "snapshot-raced"
    assert (_repo_state(origin), _repo_state(checkout)) == before
    assert not list(out.glob("*.bundle"))
