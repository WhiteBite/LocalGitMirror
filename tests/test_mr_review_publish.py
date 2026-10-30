"""v2 review batch publication (mr_replies_push work side): fresh-fingerprint
gate, full side-aware positions from the batch refs, no general-note fallback,
possible-duplicate block with marker recovery, never acked.

Shared fixture: tests/fixtures/mr_review_publish/publish_batch.json (the live
MR detail + branch tip the gate must see for the batch to be fresh; the
version_fingerprint in the batch is recomputed from exactly those values).
Kotlin mirror: MrReviewPublishTest.kt.
"""
import copy
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from test_mr_replies_push import FakeClient, _empty_registry, work_env  # noqa: F401

from lgm_core.client import LgmError
from lgm_core.mr_review_models import compute_version_fingerprint, review_marker
from lgm_core.op_models import Ctx
from lgm_core.ops import op_mr_replies_push

_FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "mr_review_publish"
                       / "publish_batch.json").read_text(encoding="utf-8"))
_LIVE = _FIXTURE["live"]
_BATCH = _FIXTURE["batch"]
_DETAIL = _LIVE["detail"]
_TIP = _LIVE["target_tip"]
_WEB_URL = _DETAIL["web_url"]
_DIFF_REFS = _DETAIL["diff_refs"]
_BATCH_ID = _BATCH["batch_id"]
_THREAD_ID = _BATCH["items"][2]["thread_id"]
_FIDS = [i["finding_id"] for i in _BATCH["items"]]
_OTHER_BATCH_ID = "9e8d7c6b-5a49-4837-2615-0f4e3d2c1b09"


class PublishClient(FakeClient):
    """FakeClient plus the v2 gate/post surface, with programmable drift."""

    def __init__(self, detail=None, tip=None, base_url="", discussions=None):
        super().__init__(
            discussions=copy.deepcopy(_LIVE["discussions"]) if discussions is None
            else discussions,
            mr=copy.deepcopy(_DETAIL) if detail is None else detail)
        self.tip = _TIP if tip is None else tip
        self.base_url = base_url or _BATCH["gitlab_url"]
        self.positioned: list[tuple] = []
        self.fail_positioned_for: set[str] = set()
        self.positioned_error_code = 400
        self.write_attempts = 0
        self.gate_calls = 0
        self.drift_after_writes: int | None = None
        self.api_down_after_writes: int | None = None
        self.gate_down = False

    def add_batch_file(self, batch=None, item_id="b1", iid=30):
        b = copy.deepcopy(_BATCH) if batch is None else batch
        self.postbox[item_id] = {
            "path": f"mr-replies/mr-!{iid}-{b['batch_id']}.review.json",
            "mtime": 5, "data": json.dumps(b, ensure_ascii=False).encode("utf-8")}

    def _gate_down_or_drift(self):
        if self.gate_down:
            raise LgmError("network", "GitLab unreachable: fixture down")
        if (self.api_down_after_writes is not None
                and self.write_attempts >= self.api_down_after_writes):
            raise LgmError("network", "GitLab unreachable: fixture down")
        if (self.drift_after_writes is not None
                and self.write_attempts >= self.drift_after_writes):
            return {**self.mr, "sha": "e" * 40}
        return None

    def gitlab_get_mr(self, iid, project=""):
        self.projects.add(project)
        self.gate_calls += 1
        mutated = self._gate_down_or_drift()
        return mutated if mutated is not None else self.mr

    def gitlab_branch_tip(self, branch, project=""):
        self._gate_down_or_drift()
        return self.tip

    def gitlab_base_url(self, project=""):
        return self.base_url

    def gitlab_post_new_discussion_positioned(self, iid, body, position, project=""):
        self.projects.add(project)
        self.write_attempts += 1
        if position["new_path"] in self.fail_positioned_for:
            raise LgmError(self.positioned_error_code, "line is not part of the diff")
        self.positioned.append((iid, body, position))
        return {"id": f"disc-{len(self.positioned)}",
                "notes": [{"id": 900 + len(self.positioned)}]}

    def gitlab_post_thread_note(self, iid, discussion_id, body, project=""):
        self.projects.add(project)
        self.write_attempts += 1
        if discussion_id in self.fail_thread_note_for:
            raise LgmError(500, "boom")
        self.thread_notes.append((iid, discussion_id, body))
        return {"id": 700 + len(self.thread_notes)}

    def gitlab_post_mr_note(self, iid, body, project=""):
        self.projects.add(project)
        self.write_attempts += 1
        self.mr_notes.append((iid, body))
        return {"id": 800 + len(self.mr_notes)}


@pytest.fixture(autouse=True)
def _isolated_reviews_home(tmp_path, monkeypatch):
    monkeypatch.setenv("LGM_REVIEWS_HOME", str(tmp_path / "reviews-home"))


def _push(client, args=None):
    full = {"repo": "r1", "iid": 0, "dry_run": False}
    full.update(args or {})
    ctx = Ctx(config=SimpleNamespace(sync_password="", storage_path=""),
              client=client)
    return op_mr_replies_push(ctx, full)


def _v2_report(res):
    return next(r for r in res["reports"] if r.get("format") == "v2")


def _assert_zero_writes(client):
    assert client.thread_notes == [] and client.mr_notes == []
    assert client.positioned == [] and client.new_discussions == []
    assert client.resolved == [] and client.write_attempts == 0
    assert client.acked == [] and client.uploads == []


def _marker_comment(finding_id, revision, batch_id=_BATCH_ID):
    return f"<!-- {review_marker(batch_id, finding_id, revision)} -->"


def _note(body, **kw):
    n = {"body": body, "system": False, "resolvable": True, "resolved": False,
         "position": None, "id": 1}
    n.update(kw)
    return n


# ── fixture integrity ────────────────────────────────────────────────────────

def test_fixture_fingerprint_is_recomputable_from_the_live_values():
    item = _BATCH["items"][0]
    live = {
        "gitlab_url": _BATCH["gitlab_url"],
        "iid": _BATCH["iid"],
        "source_project_id": _DETAIL["source_project_id"],
        "target_project_id": _DETAIL["target_project_id"],
        "source_sha": _DETAIL["sha"],
        "target_tip_sha": _TIP,
        "target_branch": _DETAIL["target_branch"],
        "gitlab_diff_refs": _DIFF_REFS,
    }
    assert compute_version_fingerprint(live) == item["version_fingerprint"]
    assert item["source_sha"] == _DETAIL["sha"]
    assert item["target_tip_sha"] == _TIP
    assert item["diff_refs"] == _DIFF_REFS


# ── happy path ───────────────────────────────────────────────────────────────

def test_fresh_batch_publishes_every_kind_with_exact_positions(work_env):  # noqa: F811
    client = PublishClient()
    client.add_batch_file()
    res = _push(client)
    rep = _v2_report(res)
    assert rep["posted"] == 4 and rep["failed"] == 0 and rep["duplicates"] == 0
    assert rep["acked"] is False
    assert client.acked == [] and client.uploads == []

    iid, body, pos = client.positioned[0]
    assert iid == 30
    assert pos == {"base_sha": _DIFF_REFS["base_sha"],
                   "start_sha": _DIFF_REFS["start_sha"],
                   "head_sha": _DIFF_REFS["head_sha"],
                   "position_type": "text",
                   "old_path": "src/config.py", "new_path": "src/config.py",
                   "new_line": 12}
    assert body == f"{_marker_comment(_FIDS[0], 1)}\n\n{_BATCH['items'][0]['body']}"

    _, body2, pos2 = client.positioned[1]
    assert pos2["old_path"] == "src/legacy.py" and pos2["new_path"] == "src/renamed.py"
    assert pos2["old_line"] == 7 and "new_line" not in pos2
    assert body2.startswith(_marker_comment(_FIDS[1], 3))

    assert client.thread_notes == [(30, _THREAD_ID,
                                    f"{_marker_comment(_FIDS[2], 1)}\n\n"
                                    f"{_BATCH['items'][2]['body']}")]
    assert client.resolved == [(30, _THREAD_ID)]
    assert client.mr_notes == [(30, f"{_marker_comment(_FIDS[3], 2)}\n\n"
                                    f"{_BATCH['items'][3]['body']}")]

    entries = rep["replies"]
    assert [e["outcome"] for e in entries] == ["posted"] * 4
    assert entries[0]["discussion_id"] == "disc-1" and entries[0]["note_id"] == 901
    assert entries[0]["url"] == f"{_WEB_URL}#note_901"
    assert entries[0]["response"] == {"id": "disc-1", "notes": [{"id": 901}]}
    assert entries[1]["url"] == f"{_WEB_URL}#note_902"
    assert entries[2]["discussion_id"] == _THREAD_ID and entries[2]["note_id"] == 701
    assert entries[2]["url"] == f"{_WEB_URL}#note_701"
    assert entries[3]["discussion_id"] is None and entries[3]["note_id"] == 801
    assert entries[3]["url"] == f"{_WEB_URL}#note_801"
    assert rep["batch_id"] == _BATCH_ID
    assert rep["identity"]["version_fingerprint"] == _BATCH["items"][0]["version_fingerprint"]


def test_legacy_file_and_v2_batch_publish_in_one_pass(work_env):  # noqa: F811
    client = PublishClient()
    client.add_reply_file(7, "## new A.java:12\nlegacy remark\n")
    client.add_batch_file()
    res = _push(client)
    legacy = next(r for r in res["reports"] if r.get("iid") == 7)
    assert legacy["posted"] == 1 and legacy["acked"] is True
    assert client.new_discussions and client.acked == ["r7"]
    rep = _v2_report(res)
    assert rep["posted"] == 4 and rep["acked"] is False


# ── stale-snapshot gate: zero POST/resolve ───────────────────────────────────

@pytest.mark.parametrize("mutate,drift", [
    ({"sha": "e" * 40}, ["source_sha", "version_fingerprint"]),
    ({"state": "merged"}, ["state"]),
    ({"state": "closed"}, ["state"]),
    ({"diff_refs": {**_DIFF_REFS, "start_sha": "d" * 40}},
     ["diff_refs", "version_fingerprint"]),
    ({"diff_refs": {**_DIFF_REFS, "base_sha": "d" * 40}},
     ["diff_refs", "version_fingerprint"]),
    ({"target_project_id": 99}, ["target_project_id", "version_fingerprint"]),
])
def test_drift_blocks_the_whole_batch_with_zero_posts(work_env, mutate, drift):  # noqa: F811
    client = PublishClient(detail={**copy.deepcopy(_DETAIL), **mutate})
    client.add_batch_file()
    res = _push(client)
    rep = _v2_report(res)
    assert rep["posted"] == 0 and rep["failed"] == 4 and rep["duplicates"] == 0
    assert rep["acked"] is False
    assert rep["stale"]["drift"] == drift
    assert "stale-snapshot" in rep["details"][0]
    assert all(e["outcome"] == "blocked" and e["code"] == "stale-snapshot"
               for e in rep["replies"])
    _assert_zero_writes(client)


def test_moved_target_tip_blocks_with_zero_posts(work_env):  # noqa: F811
    client = PublishClient(tip="c" * 40)
    client.add_batch_file()
    res = _push(client)
    rep = _v2_report(res)
    assert rep["posted"] == 0 and rep["failed"] == 4
    assert rep["stale"]["drift"] == ["target_tip_sha", "version_fingerprint"]
    assert rep["stale"]["expected"]["target_tip_sha"] == _TIP
    assert rep["stale"]["fresh"]["target_tip_sha"] == "c" * 40
    _assert_zero_writes(client)


def test_foreign_gitlab_url_blocks_with_zero_posts(work_env):  # noqa: F811
    client = PublishClient(base_url="https://other.gitlab.example.com")
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["posted"] == 0 and rep["failed"] == 4
    assert "gitlab_url" in rep["stale"]["drift"]
    _assert_zero_writes(client)


def test_unavailable_gate_blocks_all_entries_without_writes(work_env):  # noqa: F811
    client = PublishClient()
    client.gate_down = True
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["posted"] == 0 and rep["failed"] == 4
    assert "freshness gate" in rep["details"][0]
    assert all(e["outcome"] == "blocked" and e["code"] == "" for e in rep["replies"])
    _assert_zero_writes(client)


def test_discussions_fetch_failure_blocks_everything(work_env, monkeypatch):  # noqa: F811
    client = PublishClient()
    client.add_batch_file()

    def boom(iid, project=""):
        raise LgmError(500, "discussions exploded")

    monkeypatch.setattr(client, "gitlab_list_discussions", boom)
    rep = _v2_report(_push(client))
    assert rep["posted"] == 0 and rep["failed"] == 4
    assert "list discussions" in rep["details"][0]
    _assert_zero_writes(client)


# ── mid-batch changes ────────────────────────────────────────────────────────

def test_mid_batch_head_change_blocks_the_remainder(work_env):  # noqa: F811
    client = PublishClient()
    client.drift_after_writes = 1
    client.add_batch_file()
    rep = _v2_report(_push(client))
    entries = rep["replies"]
    assert entries[0]["outcome"] == "posted"
    assert entries[0]["discussion_id"] == "disc-1" and entries[0]["note_id"] == 901
    assert all(e["outcome"] == "blocked" and e["code"] == "stale-snapshot"
               for e in entries[1:])
    assert "source_sha" in rep["stale"]["drift"]
    assert len(client.positioned) == 1 and client.write_attempts == 1
    assert rep["acked"] is False


def test_api_loss_mid_batch_keeps_posted_ids_and_blocks_remainder(work_env):  # noqa: F811
    client = PublishClient()
    client.api_down_after_writes = 1
    client.add_batch_file()
    rep = _v2_report(_push(client))
    entries = rep["replies"]
    assert entries[0]["outcome"] == "posted" and entries[0]["note_id"] == 901
    assert all(e["outcome"] == "blocked" and "freshness gate" in e["error"]
               for e in entries[1:])
    assert len(client.positioned) == 1


# ── v2 no-general-fallback ───────────────────────────────────────────────────

@pytest.mark.parametrize("code", [400, 422])
def test_rejected_anchor_blocks_without_general_fallback(work_env, code):  # noqa: F811
    client = PublishClient()
    client.fail_positioned_for = {"src/config.py"}
    client.positioned_error_code = code
    client.add_batch_file()
    rep = _v2_report(_push(client))
    entries = rep["replies"]
    assert entries[0]["outcome"] == "blocked"
    assert f"GitLab rejected the anchor: HTTP {code}" in entries[0]["error"]
    assert "no general fallback" in entries[0]["error"]
    assert all(_BATCH["items"][0]["body"] not in n[1] for n in client.mr_notes)
    assert [e["outcome"] for e in entries[1:]] == ["posted"] * 3
    assert rep["posted"] == 3 and rep["failed"] == 1
    assert rep["acked"] is False


def test_old_side_rename_anchor_rejection_never_downgrades(work_env):  # noqa: F811
    client = PublishClient()
    client.fail_positioned_for = {"src/renamed.py"}
    client.positioned_error_code = 422
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["replies"][1]["outcome"] == "blocked"
    assert all(_BATCH["items"][1]["body"] not in n[1] for n in client.mr_notes)
    assert rep["posted"] == 3


def test_server_error_on_anchor_blocks_entry_and_rest_continue(work_env):  # noqa: F811
    client = PublishClient()
    client.fail_positioned_for = {"src/config.py"}
    client.positioned_error_code = 500
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["replies"][0]["outcome"] == "blocked"
    assert "HTTP 500" in rep["replies"][0]["error"]
    assert "rejected the anchor" not in rep["replies"][0]["error"]
    assert [e["outcome"] for e in rep["replies"][1:]] == ["posted"] * 3


def test_file_level_anchor_blocks_invalid_line_without_posting(work_env):  # noqa: F811
    batch = copy.deepcopy(_BATCH)
    batch["items"][0]["anchor"]["line"] = None
    client = PublishClient()
    client.add_batch_file(batch=batch)
    rep = _v2_report(_push(client))
    assert rep["replies"][0]["outcome"] == "blocked"
    assert rep["replies"][0]["code"] == "invalid-line"
    assert all(p[2]["new_path"] != "src/config.py" for p in client.positioned)
    assert [e["outcome"] for e in rep["replies"][1:]] == ["posted"] * 3


def test_missing_thread_blocks_the_entry_without_posting(work_env):  # noqa: F811
    client = PublishClient(discussions=[])
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["replies"][2]["outcome"] == "blocked"
    assert "no longer exists" in rep["replies"][2]["error"]
    assert client.thread_notes == [] and client.resolved == []
    assert [e["outcome"] for e in rep["replies"][:2]] == ["posted"] * 2
    assert rep["replies"][3]["outcome"] == "posted"


# ── possible-duplicate block + marker recovery ───────────────────────────────

def test_repeated_identical_revision_is_blocked_as_possible_duplicate(work_env):  # noqa: F811
    discussions = copy.deepcopy(_LIVE["discussions"])
    discussions.append({"id": "disc-prev", "notes": [
        _note(f"{_marker_comment(_FIDS[0], 1)}\n\n{_BATCH['items'][0]['body']}",
              id=555)]})
    client = PublishClient(discussions=discussions)
    client.add_batch_file()
    rep = _v2_report(_push(client))
    first = rep["replies"][0]
    assert first["outcome"] == "skipped-duplicate"
    assert first["code"] == "possible-duplicate"
    assert first["discussion_id"] == "disc-prev" and first["note_id"] == 555
    assert first["url"] == f"{_WEB_URL}#note_555"
    assert all(p[2]["new_path"] != "src/config.py" for p in client.positioned)
    assert rep["posted"] == 3 and rep["duplicates"] == 1 and rep["failed"] == 0
    assert rep["acked"] is False


def test_marker_from_another_batch_still_deduplicates(work_env):  # noqa: F811
    discussions = copy.deepcopy(_LIVE["discussions"])
    discussions.append({"id": "disc-other", "notes": [
        _note(_marker_comment(_FIDS[0], 1, _OTHER_BATCH_ID), id=556)]})
    client = PublishClient(discussions=discussions)
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["replies"][0]["outcome"] == "skipped-duplicate"
    assert rep["replies"][0]["note_id"] == 556
    assert all(p[2]["new_path"] != "src/config.py" for p in client.positioned)


def test_second_push_recovers_every_marker_and_posts_nothing(work_env):  # noqa: F811
    client = PublishClient()
    client.add_batch_file()
    _push(client)
    first_writes = client.write_attempts
    assert first_writes == 4
    revisions = [i["revision"] for i in _BATCH["items"]]
    client.discussions = [
        {"id": f"disc-{k}", "notes": [_note(_marker_comment(fid, rev), id=600 + k)]}
        for k, (fid, rev) in enumerate(zip(_FIDS, revisions))]
    rep = _v2_report(_push(client))
    assert rep["posted"] == 0 and rep["duplicates"] == 4 and rep["failed"] == 0
    assert client.write_attempts == first_writes
    assert all(e["outcome"] == "skipped-duplicate" for e in rep["replies"])
    assert [e["note_id"] for e in rep["replies"]] == [600, 601, 602, 603]
    assert all(e["url"] for e in rep["replies"])
    assert rep["acked"] is False


def test_duplicate_entries_survive_a_stale_halt_with_recovered_ids(work_env):  # noqa: F811
    discussions = copy.deepcopy(_LIVE["discussions"])
    discussions.append({"id": "disc-prev", "notes": [
        _note(_marker_comment(_FIDS[0], 1), id=555)]})
    client = PublishClient(detail={**copy.deepcopy(_DETAIL), "state": "merged"},
                           discussions=discussions)
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["replies"][0]["outcome"] == "skipped-duplicate"
    assert rep["replies"][0]["note_id"] == 555
    assert all(e["outcome"] == "blocked" and e["code"] == "stale-snapshot"
               for e in rep["replies"][1:])
    _assert_zero_writes(client)


# ── dry run ──────────────────────────────────────────────────────────────────

def test_dry_run_plans_without_writes(work_env):  # noqa: F811
    client = PublishClient()
    client.add_batch_file()
    res = _push(client, {"dry_run": True})
    rep = _v2_report(res)
    assert rep["posted"] == 4 and rep["acked"] is False
    assert rep["replies"][0]["note"] == ("would post anchored discussion at "
                                         "src/config.py:12 (new side)")
    assert rep["replies"][2]["note"] == f"would reply into thread at {_THREAD_ID}"
    assert rep["replies"][3]["note"] == "would post general note"
    _assert_zero_writes(client)


def test_dry_run_of_a_stale_batch_plans_blocked_entries(work_env):  # noqa: F811
    client = PublishClient(tip="c" * 40)
    client.add_batch_file()
    rep = _v2_report(_push(client, {"dry_run": True}))
    assert rep["posted"] == 0 and rep["failed"] == 4
    assert "stale-snapshot" in rep["details"][0]
    _assert_zero_writes(client)


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
