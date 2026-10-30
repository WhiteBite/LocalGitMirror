"""Task 13: detailed receipts recoverable before ack.

WORK side: per-entry publication journal (crash-safe atomic writes), receipt
handoff in strict order (persist journal -> upload receipt -> ack; upload
failure = no ack), restart recovery without a second POST (journaled-posted
entries and uncertain POSTs resolve through markers only), incomplete ids
never fake a complete receipt, all-duplicate retries keep their receipt.
HOME side: receipt import into the store receipts table plus finding delivery
revisions through the committed findings save service, acked only after a
full import; re-imports are idempotent, stored receipts are immutable.
"""
import copy
import json
import sys
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from test_mr_pending import _PendingClient, _ctx  # noqa: F401
from test_mr_review_publish import (  # noqa: F401
    PublishClient, _BATCH, _BATCH_ID, _DIFF_REFS, _FIDS, _LIVE, _WEB_URL,
    _empty_registry, _marker_comment, _note, _push, _v2_report, work_env,
)
from test_mr_review_store import _bundle, _manifest, _ordinary_mirror

from lgm_core.client import LgmError
from lgm_core.mr_review_findings import mr_review_findings
from lgm_core.mr_review_models import MrReviewError, canonical_json, receipt_note_url
from lgm_core.mr_review_receipts import (
    build_receipt_envelope,
    journal_path,
    open_journal,
    receipt_artifact_path,
    run_receipt_handoff,
    run_receipt_import,
    validate_receipt_envelope,
)
from lgm_core.mr_review_store import MrReviewStore, import_snapshot
from lgm_core.op_models import Ctx
from lgm_core.ops import op_mr_pending, op_mr_replies_status, op_mr_review_receipts

GITLAB_URL = "https://gitlab.example.com"
MR_URL = "https://gitlab.example.com/groups/phonyx/-/merge_requests/30"
ANCHOR = {"old_path": "src/app.py", "new_path": "src/app.py", "side": "new", "line": 1}
_ITEM0 = _BATCH["items"][0]
_TRIPLE = (_ITEM0["snapshot_id"], _ITEM0["code_id"], _ITEM0["version_fingerprint"])
_REVISIONS = [i["revision"] for i in _BATCH["items"]]


@pytest.fixture(autouse=True)
def _isolated_reviews_home(tmp_path, monkeypatch):
    monkeypatch.setenv("LGM_REVIEWS_HOME", str(tmp_path / "reviews-home"))


class GrowingClient(PublishClient):
    """General notes become listable discussions right after the POST, the way
    the real GitLab exposes them to the backfill re-scan."""

    def gitlab_post_mr_note(self, iid, body, project=""):
        res = super().gitlab_post_mr_note(iid, body, project=project)
        self.discussions.append(
            {"id": f"disc-gen-{res['id']}",
             "notes": [{"id": res["id"], "body": body}]})
        return res


def _journal_file():
    return journal_path("r1", 30, _BATCH_ID)


def _load_journal():
    return json.loads(_journal_file().read_text(encoding="utf-8"))


def _entry_record(finding_id, revision, kind="anchored", *, state="done",
                  outcome="posted", post_attempted=True, discussion_id="disc-j",
                  note_id=555, url=None, error=None, marker=None):
    return {
        "finding_id": finding_id, "revision": revision, "kind": kind,
        "marker": marker or f"lgm-review:{_BATCH_ID}:{finding_id}:{revision}",
        "state": state, "post_attempted": post_attempted,
        "started_at": "2026-10-30T10:00:00+00:00",
        "finished_at": "2026-10-30T10:00:05+00:00" if state == "done" else None,
        "outcome": outcome, "code": "", "error": error,
        "discussion_id": discussion_id, "note_id": note_id,
        "url": url if url is not None else (
            receipt_note_url(_WEB_URL, note_id) if note_id is not None else None),
        "recovered_from": None,
    }


def _attempt_record(entries, *, attempt=1, completed=True, item_id="b1",
                    web_url=_WEB_URL, uploaded_at=None, receipt_path=None,
                    acked_at=None):
    return {
        "attempt": attempt, "started_at": "2026-10-30T10:00:00+00:00",
        "completed_at": "2026-10-30T10:01:00+00:00" if completed else None,
        "web_url": web_url, "postbox_item_id": item_id,
        "postbox_path": f"mr-replies/mr-!30-{_BATCH_ID}.review.json",
        "receipt_uploaded_at": uploaded_at, "receipt_path": receipt_path,
        "acked_at": acked_at, "entries": entries,
    }


def _write_journal(attempts):
    item0 = _BATCH["items"][0]
    data = {
        "schema_version": 1, "repo": "r1", "iid": 30, "batch_id": _BATCH_ID,
        "gitlab_url": _BATCH["gitlab_url"],
        "target_project_id": _BATCH["target_project_id"],
        "identity": {"snapshot_id": item0["snapshot_id"],
                     "code_id": item0["code_id"],
                     "version_fingerprint": item0["version_fingerprint"]},
        "refs": {"source_sha": item0["source_sha"],
                 "target_tip_sha": item0["target_tip_sha"],
                 "diff_refs": dict(item0["diff_refs"])},
        "attempts": attempts,
    }
    path = _journal_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")
    return data


def _uploaded_envelopes(client):
    out = []
    for path, data in client.uploads:
        if path.startswith("mr-review/receipts/"):
            out.append((path, json.loads(data.decode("utf-8"))))
    return out


# ── WORK: journal recording during publication ──────────────────────────────

def test_publish_journals_every_entry_with_ids_and_defers_receipt(work_env):
    client = PublishClient()
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["posted"] == 4 and rep["attempt"] == 1
    assert client.uploads == [] and client.acked == []
    data = _load_journal()
    assert data["batch_id"] == _BATCH_ID
    attempt = data["attempts"][0]
    assert attempt["completed_at"] and attempt["postbox_item_id"] == "b1"
    assert attempt["web_url"] == _WEB_URL
    by_key = {(e["finding_id"], e["revision"]): e for e in attempt["entries"]}
    assert len(by_key) == 4
    first = by_key[(_FIDS[0], 1)]
    assert first["state"] == "done" and first["outcome"] == "posted"
    assert first["discussion_id"] == "disc-1" and first["note_id"] == 901
    assert first["url"] == f"{_WEB_URL}#note_901"
    assert all(e["state"] == "done" for e in attempt["entries"])


def test_dry_run_leaves_no_journal(work_env):
    client = PublishClient()
    client.add_batch_file()
    _push(client, {"dry_run": True})
    assert not _journal_file().exists()


def test_corrupt_journal_is_replaced_and_markers_still_protect(work_env):
    client = PublishClient()
    client.add_batch_file()
    _push(client)
    _journal_file().write_text("{not json", encoding="utf-8")
    client.discussions = [
        {"id": f"disc-{k}", "notes": [_note(_marker_comment(fid, rev), id=600 + k)]}
        for k, (fid, rev) in enumerate(zip(_FIDS, _REVISIONS))]
    rep = _v2_report(_push(client))
    assert rep["posted"] == 0 and rep["duplicates"] == 4
    assert client.write_attempts == 4


# ── WORK: handoff order — journal -> receipt -> ack ─────────────────────────

def test_handoff_uploads_detailed_receipt_and_keeps_incomplete_attempt_unacked(work_env):
    client = PublishClient()
    client.add_batch_file()
    _push(client)
    result = run_receipt_handoff(client, "r1")
    path = receipt_artifact_path(30, _BATCH_ID, 1)
    assert result["uploaded"] == [path] and result["acked"] == []
    envelopes = _uploaded_envelopes(client)
    assert [p for p, _ in envelopes] == [path]
    envelope = validate_receipt_envelope(envelopes[0][1])
    assert envelope["attempt"] == 1 and envelope["iid"] == 30
    assert [r["outcome"] for r in envelope["receipts"]] == ["posted"] * 3 + ["blocked"]
    general = envelope["receipts"][3]
    assert general["note_id"] == 801 and general["url"] == f"{_WEB_URL}#note_801"
    assert "receipt incomplete" in general["error"]
    attempt = _load_journal()["attempts"][0]
    assert attempt["receipt_uploaded_at"] and attempt["acked_at"] is None
    assert attempt["receipt_path"] == path


def test_terminal_attempt_acks_only_after_a_successful_upload(work_env):
    client = GrowingClient()
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["replies"][3]["discussion_id"] == "disc-gen-801"
    result = run_receipt_handoff(client, "r1")
    assert result["acked"] == ["b1"]
    envelope = validate_receipt_envelope(_uploaded_envelopes(client)[0][1])
    assert [r["outcome"] for r in envelope["receipts"]] == ["posted"] * 4
    assert envelope["receipts"][3]["discussion_id"] == "disc-gen-801"
    assert _load_journal()["attempts"][0]["acked_at"]


def test_receipt_upload_failure_means_no_ack_and_retries_clean(work_env):
    client = PublishClient()
    client.add_batch_file()
    _push(client)

    def boom(repo, path, size, data):
        raise LgmError("network", "postbox unreachable")

    client.file_sync_send = boom
    result = run_receipt_handoff(client, "r1")
    assert result["uploaded"] == [] and result["acked"] == []
    assert "receipt upload" in result["failed"][0]["error"]
    assert _load_journal()["attempts"][0]["receipt_uploaded_at"] is None

    del client.file_sync_send
    result = run_receipt_handoff(client, "r1")
    assert result["uploaded"] == [receipt_artifact_path(30, _BATCH_ID, 1)]
    assert result["acked"] == []
    assert "blocked" in [r["outcome"] for r in validate_receipt_envelope(
        _uploaded_envelopes(client)[0][1])["receipts"]]


def test_crash_between_upload_and_journal_mark_reuploads_identical_bytes(work_env):
    client = PublishClient()
    client.add_batch_file()
    _push(client)
    sent = []
    original = client.file_sync_send

    def crashing_send(repo, path, size, data):
        res = original(repo, path, size, data)
        sent.append((path, data))
        raise RuntimeError("crash after upload, before journal mark")

    client.file_sync_send = crashing_send
    with pytest.raises(RuntimeError):
        run_receipt_handoff(client, "r1")
    assert _load_journal()["attempts"][0]["receipt_uploaded_at"] is None
    client.file_sync_send = original
    result = run_receipt_handoff(client, "r1")
    assert result["uploaded"] == [sent[0][0]]
    second = next(data for path, data in client.uploads
                  if path.startswith("mr-review/receipts/"))
    assert second == sent[0][1]


def test_crash_after_receipt_before_ack_acks_without_reupload(work_env):
    client = GrowingClient()
    client.add_batch_file()
    _push(client)
    data = _load_journal()
    data["attempts"][0]["receipt_uploaded_at"] = "2026-10-30T10:05:00+00:00"
    data["attempts"][0]["receipt_path"] = receipt_artifact_path(30, _BATCH_ID, 1)
    _journal_file().write_text(json.dumps(data), encoding="utf-8")
    result = run_receipt_handoff(client, "r1")
    assert result["uploaded"] == [] and _uploaded_envelopes(client) == []
    assert result["acked"] == ["b1"]
    assert _load_journal()["attempts"][0]["acked_at"]


def test_blocked_entries_keep_the_batch_unacked_with_full_receipt(work_env):
    client = PublishClient()
    client.fail_positioned_for = {"src/config.py"}
    client.positioned_error_code = 500
    client.add_batch_file()
    rep = _v2_report(_push(client))
    assert rep["replies"][0]["outcome"] == "blocked" and rep["posted"] == 3
    result = run_receipt_handoff(client, "r1")
    assert result["acked"] == []
    envelope = validate_receipt_envelope(_uploaded_envelopes(client)[0][1])
    outcomes = {r["finding_id"]: r["outcome"] for r in envelope["receipts"]}
    assert outcomes[_FIDS[0]] == "failed"
    assert "HTTP 500" in next(r["error"] for r in envelope["receipts"]
                              if r["finding_id"] == _FIDS[0])
    assert envelope["receipts"][1]["outcome"] == "posted"
    assert envelope["receipts"][1]["submitted_refs"] == {
        "source_sha": _ITEM0["source_sha"], "target_tip_sha": _ITEM0["target_tip_sha"],
        **_DIFF_REFS}


def test_all_duplicate_retry_still_produces_a_detailed_receipt(work_env):
    client = PublishClient()
    client.add_batch_file()
    _push(client)
    client.discussions = [
        {"id": f"disc-{k}", "notes": [_note(_marker_comment(fid, rev), id=600 + k)]}
        for k, (fid, rev) in enumerate(zip(_FIDS, _REVISIONS))]
    rep = _v2_report(_push(client))
    assert rep["posted"] == 0 and rep["duplicates"] == 4
    assert client.write_attempts == 4
    result = run_receipt_handoff(client, "r1")
    envelopes = _uploaded_envelopes(client)
    assert len(envelopes) == 2
    retry = validate_receipt_envelope(envelopes[1][1])
    assert retry["attempt"] == 2
    assert [r["outcome"] for r in retry["receipts"]] == ["recovered"] * 4
    assert [r["note_id"] for r in retry["receipts"]] == [600, 601, 602, 603]
    assert all(r["url"] and r["discussion_id"] for r in retry["receipts"])
    assert result["acked"] == ["b1"]


# ── WORK: restart recovery — never a second POST ────────────────────────────

def test_crash_after_post_before_journal_recovers_through_the_marker(work_env):
    entries = [_entry_record(_FIDS[0], 1, state="post_start", outcome=None,
                             discussion_id=None, note_id=None, url=None)]
    _write_journal([_attempt_record(entries, completed=False)])
    discussions = copy.deepcopy(_LIVE["discussions"])
    discussions.append({"id": "disc-crash", "notes": [
        _note(_marker_comment(_FIDS[0], 1), id=555)]})
    client = PublishClient(discussions=discussions)
    client.add_batch_file()
    rep = _v2_report(_push(client))
    first = rep["replies"][0]
    assert first["outcome"] == "skipped-duplicate"
    assert first["discussion_id"] == "disc-crash" and first["note_id"] == 555
    assert first["url"] == f"{_WEB_URL}#note_555"
    assert client.write_attempts == 3
    assert all(p[2]["new_path"] != "src/config.py" for p in client.positioned)
    attempt2 = _load_journal()["attempts"][1]
    rec0 = attempt2["entries"][0]
    assert rec0["outcome"] == "skipped-duplicate" and rec0["note_id"] == 555


def test_uncertain_post_without_marker_blocks_and_never_reposts(work_env):
    entries = [_entry_record(_FIDS[0], 1, state="post_start", outcome=None,
                             discussion_id=None, note_id=None, url=None)]
    _write_journal([_attempt_record(entries, completed=False)])
    client = PublishClient()
    client.add_batch_file()
    rep = _v2_report(_push(client))
    first = rep["replies"][0]
    assert first["outcome"] == "blocked"
    assert "uncertain publication outcome" in first["error"]
    assert "refusing a second POST" in first["error"]
    assert client.write_attempts == 3
    assert all(p[2]["new_path"] != "src/config.py" for p in client.positioned)
    result = run_receipt_handoff(client, "r1")
    envelope = validate_receipt_envelope(_uploaded_envelopes(client)[0][1])
    assert envelope["receipts"][0]["outcome"] == "blocked"
    assert result["acked"] == []


def test_journaled_posted_entry_is_never_reposted_even_without_marker(work_env):
    entries = [_entry_record(_FIDS[0], 1, note_id=555, discussion_id="disc-j")]
    _write_journal([_attempt_record(entries, completed=False)])
    client = PublishClient()
    client.add_batch_file()
    rep = _v2_report(_push(client))
    first = rep["replies"][0]
    assert first["outcome"] == "skipped-duplicate"
    assert first["discussion_id"] == "disc-j" and first["note_id"] == 555
    assert first["url"] == f"{_WEB_URL}#note_555"
    assert client.write_attempts == 3
    assert all(p[2]["new_path"] != "src/config.py" for p in client.positioned)


def test_marker_hit_with_missing_ids_is_not_a_complete_receipt(work_env):
    discussions = copy.deepcopy(_LIVE["discussions"])
    discussions.append({"id": "disc-broken", "notes": [
        _note(_marker_comment(_FIDS[0], 1), id=None)]})
    client = PublishClient(discussions=discussions)
    client.add_batch_file()
    rep = _v2_report(_push(client))
    first = rep["replies"][0]
    assert first["outcome"] == "skipped-duplicate" and first["note_id"] is None
    result = run_receipt_handoff(client, "r1")
    envelope = validate_receipt_envelope(_uploaded_envelopes(client)[0][1])
    broken = envelope["receipts"][0]
    assert broken["outcome"] == "blocked"
    assert "receipt incomplete" in broken["error"]
    assert result["acked"] == []


def test_incomplete_attempt_gets_no_receipt_until_republished(work_env):
    entries = [_entry_record(_FIDS[0], 1, note_id=555)]
    _write_journal([_attempt_record(entries, completed=False)])
    client = PublishClient()
    result = run_receipt_handoff(client, "r1")
    assert result["uploaded"] == [] and result["acked"] == [] and client.uploads == []


# ── receipt envelope validation ─────────────────────────────────────────────

def _receipt(batch_id, finding_id, revision=1, outcome="posted", **overrides):
    receipt = {
        "schema_version": 1, "batch_id": batch_id, "finding_id": finding_id,
        "revision": revision, "outcome": outcome, "kind": "anchored",
        "snapshot_id": _TRIPLE[0], "code_id": _TRIPLE[1],
        "version_fingerprint": _TRIPLE[2],
        "discussion_id": "d1", "note_id": 777,
        "url": receipt_note_url(MR_URL, 777), "mr_url": MR_URL,
        "submitted_refs": {"source_sha": "a" * 40, "target_tip_sha": "b" * 40,
                           "base_sha": "c" * 40, "start_sha": "d" * 40,
                           "head_sha": "e" * 40},
        "fallback": False, "attempted_at": "2026-10-30T11:00:00Z",
        "finished_at": "2026-10-30T11:00:05Z", "error": None,
    }
    if outcome in ("failed", "blocked"):
        receipt.update({"discussion_id": None, "note_id": None, "url": None,
                        "submitted_refs": None, "error": "why it failed"})
    receipt.update(overrides)
    return receipt


def _envelope(receipts, batch_id=None, attempt=1, iid=30, **overrides):
    envelope = {
        "schema_version": 1, "kind": "review-receipts", "repo": "phonyx",
        "gitlab_url": GITLAB_URL, "target_project_id": 42, "iid": iid,
        "batch_id": batch_id or str(uuid.uuid4()), "attempt": attempt,
        "created_at": "2026-10-30T12:00:00Z", "receipts": receipts,
    }
    envelope.update(overrides)
    return envelope


def test_envelope_validation_rejects_structural_violations():
    batch_id = str(uuid.uuid4())
    good = _envelope([_receipt(batch_id, _FIDS[0])], batch_id=batch_id)
    validate_receipt_envelope(good)
    with pytest.raises(MrReviewError):
        validate_receipt_envelope({**good, "extra": 1})
    with pytest.raises(MrReviewError):
        validate_receipt_envelope(_envelope([_receipt(str(uuid.uuid4()), _FIDS[0])],
                                            batch_id=batch_id))
    with pytest.raises(MrReviewError):
        validate_receipt_envelope(_envelope(
            [_receipt(batch_id, _FIDS[0]), _receipt(batch_id, _FIDS[0])],
            batch_id=batch_id))
    with pytest.raises(MrReviewError):
        validate_receipt_envelope(_envelope([], batch_id=batch_id))


# ── HOME: import into the store before ack ──────────────────────────────────

class ReceiptPostbox:
    def __init__(self, artifacts=None):
        self.artifacts = artifacts or {}
        self.acked = []

    def add(self, item_id, envelope):
        self.artifacts[item_id] = (
            receipt_artifact_path(envelope["iid"], envelope["batch_id"],
                                  envelope["attempt"]),
            canonical_json(envelope).encode("utf-8"))

    def file_sync_list(self, repo):
        return {"items": [{"id": k, "path": v[0], "mtime": 1}
                          for k, v in self.artifacts.items()]}

    def file_sync_fetch(self, repo, item_id):
        return self.artifacts[item_id][1]

    def file_sync_ack(self, repo, item_id):
        self.acked.append(item_id)
        return {"success": True}


def _home_setup(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    home = tmp_path / "reviews"
    import_snapshot(manifest, bundle, home=home)
    return manifest, home


def _save_finding(home, manifest, delivery="queued", finding_id=None):
    finding_id = finding_id or str(uuid.uuid4())
    rev = {
        "revision": 1, "created_at": "2026-10-30T10:00:00Z",
        "snapshot_id": manifest["snapshot_id"], "code_id": manifest["code_id"],
        "version_fingerprint": manifest["version_fingerprint"],
        "anchor": ANCHOR, "thread_id": None, "comment": "Замечание",
        "trigger": "reading the diff", "evidence": [], "limitations": [],
        "verification": "confirmed", "applicability": "current",
        "delivery": delivery,
    }
    doc = {"schema_version": 1, "finding_id": finding_id, "repo": "phonyx",
           "gitlab_url": GITLAB_URL, "target_project_id": 42, "iid": 30,
           "revisions": [rev]}
    mr_review_findings("save", text=json.dumps(doc), home=home)
    return finding_id


def _listed(home, finding_id):
    query = {"repo": "phonyx", "gitlab_url": GITLAB_URL,
             "target_project_id": 42, "iid": 30}
    result = mr_review_findings("list", text=json.dumps(query), home=home)
    return next(d for d in result["findings"] if d["finding_id"] == finding_id)


def _receipt_rows(home):
    store = MrReviewStore(GITLAB_URL, 42, "phonyx", home=home)
    with store.transaction() as conn:
        return conn.execute(
            "SELECT batch_id, finding_id, revision, attempt, payload "
            "FROM receipts ORDER BY attempt").fetchall()


def test_home_import_stores_rows_updates_delivery_then_acks(tmp_path):
    manifest, home = _home_setup(tmp_path)
    finding_id = _save_finding(home, manifest, delivery="queued")
    batch_id = str(uuid.uuid4())
    envelope = _envelope(
        [_receipt(batch_id, finding_id, note_id=777,
                  url=receipt_note_url(MR_URL, 777))],
        batch_id=batch_id, attempt=1)
    client = ReceiptPostbox()
    client.add("rc1", envelope)

    result = run_receipt_import(client, "phonyx", home=home)

    art = result["artifacts"][0]
    assert art["imported"] == 1 and art["acked"] is True and art["errors"] == []
    assert client.acked == ["rc1"]
    rows = _receipt_rows(home)
    assert len(rows) == 1
    assert (rows[0][0], rows[0][1], rows[0][2], rows[0][3]) == (batch_id, finding_id, 1, 1)
    doc = _listed(home, finding_id)
    assert len(doc["revisions"]) == 2
    assert doc["revisions"][0]["delivery"] == "queued"
    posted = doc["revisions"][1]
    assert posted["delivery"] == "posted"
    assert any("note 777" in ref and "discussion d1" in ref and MR_URL in ref
               for ref in posted["evidence"])


def test_home_import_from_local_walks_queued_then_posted(tmp_path):
    manifest, home = _home_setup(tmp_path)
    finding_id = _save_finding(home, manifest, delivery="local")
    batch_id = str(uuid.uuid4())
    client = ReceiptPostbox()
    client.add("rc1", _envelope([_receipt(batch_id, finding_id)],
                                batch_id=batch_id, attempt=1))

    run_receipt_import(client, "phonyx", home=home)

    deliveries = [r["delivery"] for r in _listed(home, finding_id)["revisions"]]
    assert deliveries == ["local", "queued", "posted"]


def test_blocked_then_posted_attempts_walk_legal_transitions(tmp_path):
    manifest, home = _home_setup(tmp_path)
    finding_id = _save_finding(home, manifest, delivery="queued")
    batch_id = str(uuid.uuid4())
    client = ReceiptPostbox()
    client.add("a1", _envelope([_receipt(batch_id, finding_id, outcome="blocked")],
                               batch_id=batch_id, attempt=1))
    client.add("a2", _envelope([_receipt(batch_id, finding_id)],
                               batch_id=batch_id, attempt=2))

    result = run_receipt_import(client, "phonyx", home=home)

    assert [a["acked"] for a in result["artifacts"]] == [True, True]
    deliveries = [r["delivery"] for r in _listed(home, finding_id)["revisions"]]
    assert deliveries == ["queued", "blocked", "queued", "posted"]


def test_reimport_is_idempotent_and_never_mutates_stored_revisions(tmp_path):
    manifest, home = _home_setup(tmp_path)
    finding_id = _save_finding(home, manifest, delivery="queued")
    batch_id = str(uuid.uuid4())
    client = ReceiptPostbox()
    client.add("rc1", _envelope([_receipt(batch_id, finding_id)],
                                batch_id=batch_id, attempt=1))
    run_receipt_import(client, "phonyx", home=home)
    before = _listed(home, finding_id)["revisions"]

    result = run_receipt_import(client, "phonyx", home=home)

    art = result["artifacts"][0]
    assert art["imported"] == 0 and art["skipped"] == 1
    assert art["delivery_updates"] == 0 and art["acked"] is True
    assert _listed(home, finding_id)["revisions"] == before
    assert len(_receipt_rows(home)) == 1


def test_tampered_receipt_for_the_same_key_is_hash_mismatch_and_no_ack(tmp_path):
    manifest, home = _home_setup(tmp_path)
    finding_id = _save_finding(home, manifest, delivery="queued")
    batch_id = str(uuid.uuid4())
    client = ReceiptPostbox()
    client.add("rc1", _envelope([_receipt(batch_id, finding_id)],
                                batch_id=batch_id, attempt=1))
    run_receipt_import(client, "phonyx", home=home)
    client.acked.clear()
    client.add("rc1", _envelope([_receipt(batch_id, finding_id, note_id=999,
                                          url=receipt_note_url(MR_URL, 999))],
                                batch_id=batch_id, attempt=1))

    result = run_receipt_import(client, "phonyx", home=home)

    art = result["artifacts"][0]
    assert art["acked"] is False and client.acked == []
    assert any("hash-mismatch" in e for e in art["errors"])


def test_unknown_finding_keeps_the_artifact_unacked(tmp_path):
    manifest, home = _home_setup(tmp_path)
    batch_id = str(uuid.uuid4())
    client = ReceiptPostbox()
    client.add("rc1", _envelope([_receipt(batch_id, str(uuid.uuid4()))],
                                batch_id=batch_id, attempt=1))

    result = run_receipt_import(client, "phonyx", home=home)

    art = result["artifacts"][0]
    assert art["acked"] is False and client.acked == []
    assert any("not stored" in e for e in art["errors"])


def test_invalid_envelope_is_refused_without_store_writes(tmp_path):
    manifest, home = _home_setup(tmp_path)
    client = ReceiptPostbox()
    client.artifacts["bad"] = (receipt_artifact_path(30, str(uuid.uuid4()), 1),
                               b'{"schema_version": 1}')

    result = run_receipt_import(client, "phonyx", home=home)

    art = result["artifacts"][0]
    assert art["acked"] is False and art["errors"]
    assert _receipt_rows(home) == []


def test_envelope_path_identity_mismatch_is_refused(tmp_path):
    manifest, home = _home_setup(tmp_path)
    finding_id = _save_finding(home, manifest)
    batch_id = str(uuid.uuid4())
    envelope = _envelope([_receipt(batch_id, finding_id)], batch_id=batch_id,
                         attempt=1)
    client = ReceiptPostbox()
    client.artifacts["rc1"] = (
        receipt_artifact_path(30, batch_id, 2), canonical_json(envelope).encode("utf-8"))

    result = run_receipt_import(client, "phonyx", home=home)

    assert result["artifacts"][0]["acked"] is False
    assert any("does not match the artifact path" in e
               for e in result["artifacts"][0]["errors"])


def test_dry_run_import_writes_nothing(tmp_path):
    manifest, home = _home_setup(tmp_path)
    finding_id = _save_finding(home, manifest)
    batch_id = str(uuid.uuid4())
    client = ReceiptPostbox()
    client.add("rc1", _envelope([_receipt(batch_id, finding_id)],
                                batch_id=batch_id, attempt=1))

    result = run_receipt_import(client, "phonyx", home=home, dry_run=True)

    assert result["artifacts"][0]["imported"] == 1
    assert client.acked == [] and _receipt_rows(home) == []
    assert len(_listed(home, finding_id)["revisions"]) == 1


# ── op wiring, roles and the additive status surfaces ───────────────────────

def test_op_roles_are_enforced(work_env, monkeypatch):
    client = PublishClient()
    ctx = Ctx(config=SimpleNamespace(sync_password="", storage_path=""),
              client=client)
    monkeypatch.setitem(work_env, "LGM_MACHINE_ROLE", "home")
    with pytest.raises(LgmError) as excinfo:
        op_mr_review_receipts(ctx, {"action": "handoff", "repo": "r1"})
    assert excinfo.value.machine_code == "wrong-machine"
    monkeypatch.setitem(work_env, "LGM_MACHINE_ROLE", "work")
    with pytest.raises(LgmError) as excinfo:
        op_mr_review_receipts(ctx, {"action": "import", "repo": "r1"})
    assert excinfo.value.machine_code == "wrong-machine"
    monkeypatch.setitem(work_env, "LGM_MACHINE_ROLE", "")
    with pytest.raises(LgmError) as excinfo:
        op_mr_review_receipts(ctx, {"action": "handoff", "repo": "r1"})
    assert excinfo.value.machine_code == "role-unset"
    with pytest.raises(LgmError) as excinfo:
        op_mr_review_receipts(ctx, {"action": "bogus", "repo": "r1"})
    assert excinfo.value.code == "config"


def test_op_import_runs_on_home(work_env, monkeypatch, tmp_path):
    manifest, home = _home_setup(tmp_path)
    finding_id = _save_finding(home, manifest)
    batch_id = str(uuid.uuid4())
    client = ReceiptPostbox()
    client.add("rc1", _envelope([_receipt(batch_id, finding_id)],
                                batch_id=batch_id, attempt=1))
    monkeypatch.setitem(work_env, "LGM_MACHINE_ROLE", "home")
    monkeypatch.setenv("LGM_REVIEWS_HOME", str(home))
    ctx = Ctx(config=SimpleNamespace(sync_password="", storage_path=""),
              client=client)

    result = op_mr_review_receipts(ctx, {"action": "import", "repo": "phonyx"})

    assert result["action"] == "import" and result["success"] is True
    assert result["artifacts"][0]["acked"] is True
    assert client.acked == ["rc1"]


def test_push_result_carries_the_handoff_report(work_env):
    client = PublishClient()
    client.add_batch_file()
    res = _push(client)
    assert res["receipt_handoff"] == {"uploaded": [], "acked": [],
                                      "failed": [], "pending": []}
    res2 = _push(client)
    assert res2["receipt_handoff"]["uploaded"] == [
        receipt_artifact_path(30, _BATCH_ID, 1)]
    assert res2["receipt_handoff"]["acked"] == []


def test_legacy_all_duplicate_retry_still_uploads_the_status_receipt(work_env):
    from test_mr_replies_push import FakeClient, _marker
    dup_marker = _marker(7, "t:abc123", "plain answer")
    client = FakeClient(discussions=[
        {"id": "abc123", "notes": [
            {"body": f"already answered {dup_marker}", "system": False,
             "resolvable": True, "resolved": False, "position": None}]}])
    client.add_reply_file(7, "## thread abc123\nplain answer\n")
    res = _push(client)
    legacy = next(r for r in res["reports"] if r.get("iid") == 7)
    assert legacy["duplicates"] == 1 and legacy["posted"] == 0
    assert [u[0] for u in client.uploads] == ["mr-replies-status/mr-!7.md"]
    status = client.uploads[0][1].decode("utf-8")
    assert status.startswith("<!-- lgm-replies-status v1 -->")
    assert "- posted: 0" in status and "- duplicates: 1" in status


def test_mr_pending_adds_review_batch_states_and_keeps_numeric_fields():
    batch_id = str(uuid.uuid4())
    envelope = _envelope(
        [_receipt(batch_id, _FIDS[0], outcome="posted"),
         _receipt(batch_id, _FIDS[1], revision=2, outcome="blocked")],
        batch_id=batch_id, attempt=3)
    client = _PendingClient([
        {"id": "n30", "path": "mr-notes/mr-!30.md", "mtime": 100, "content": "# MR"},
        {"id": "rc1", "path": receipt_artifact_path(30, batch_id, 3),
         "mtime": 200, "content": canonical_json(envelope)},
    ])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = next(i for i in res["items"] if i["iid"] == 30)
    assert it["replies_pending"] is False and it["last_publish"] is None
    batches = it["review_batches"]
    assert len(batches) == 1
    assert batches[0]["batch_id"] == batch_id and batches[0]["attempt"] == 3
    assert batches[0]["outcomes"] == {"posted": 1, "blocked": 1}
    states = {f["finding_id"]: f for f in batches[0]["findings"]}
    assert states[_FIDS[0]]["outcome"] == "posted"
    assert states[_FIDS[0]]["note_id"] == 777
    assert states[_FIDS[1]]["outcome"] == "blocked"


def test_mr_pending_receipt_only_iid_joins_the_view():
    batch_id = str(uuid.uuid4())
    envelope = _envelope([_receipt(batch_id, _FIDS[0])], batch_id=batch_id,
                         iid=31)
    client = _PendingClient([
        {"id": "rc1", "path": receipt_artifact_path(31, batch_id, 1),
         "mtime": 5, "content": canonical_json(envelope)},
    ])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    assert [it["iid"] for it in res["items"]] == [31]


def test_mr_replies_status_keeps_v1_numbers_and_adds_receipt_states():
    from test_mr_replies_push import _STATUS_MD
    batch_id = str(uuid.uuid4())
    envelope = _envelope([_receipt(batch_id, _FIDS[0])], batch_id=batch_id)
    client = PublishClient()
    client.postbox["s7"] = {"path": "mr-replies-status/mr-!7.md", "mtime": 5,
                            "data": _STATUS_MD.encode("utf-8")}
    client.postbox["rc1"] = {"path": receipt_artifact_path(30, batch_id, 1),
                             "mtime": 6,
                             "data": canonical_json(envelope).encode("utf-8")}
    res = op_mr_replies_status(_ctx(client), {"repo": "r1"})
    s = res["statuses"][0]
    assert s["posted"] == 4 and s["duplicates"] == 1 and s["skipped"] == 2
    assert s["failed"] == 3 and s["not_anchored"] == 7 and s["parsed"] is True
    assert res["review_receipts"][0]["batch_id"] == batch_id
    assert res["review_receipts"][0]["outcomes"] == {"posted": 1}


# ── journal module invariants ───────────────────────────────────────────────

def test_journal_path_sanitizes_the_repo_and_rejects_dot_names():
    with pytest.raises(MrReviewError):
        journal_path(".", 30, _BATCH_ID)
    with pytest.raises(MrReviewError):
        journal_path("..", 30, _BATCH_ID)
    with pytest.raises(MrReviewError):
        journal_path("r1", 30, "not-a-uuid")
    with pytest.raises(MrReviewError):
        receipt_artifact_path(30, _BATCH_ID, 0)
    assert journal_path("../etc", 30, _BATCH_ID).parent.name == ".._etc"


def test_receipt_envelope_rebuild_is_byte_identical(work_env):
    client = PublishClient()
    client.add_batch_file()
    _push(client)
    data = _load_journal()
    attempt = data["attempts"][0]
    first = canonical_json(build_receipt_envelope(data, attempt))
    second = canonical_json(build_receipt_envelope(json.loads(json.dumps(data)),
                                                   json.loads(json.dumps(attempt))))
    assert first == second


def test_open_journal_keeps_existing_attempts(work_env):
    client = PublishClient()
    client.add_batch_file()
    _push(client)
    doc = {"batch_id": _BATCH_ID, "batch": _BATCH,
           "identity": {"snapshot_id": _TRIPLE[0], "code_id": _TRIPLE[1],
                        "version_fingerprint": _TRIPLE[2]},
           "refs": {"source_sha": _ITEM0["source_sha"],
                    "target_tip_sha": _ITEM0["target_tip_sha"],
                    "diff_refs": _DIFF_REFS}}
    journal = open_journal("r1", 30, doc, "b1", "mr-replies/x")
    assert len(journal.data["attempts"]) == 1
    attempt = journal.begin_attempt("b1", "mr-replies/x")
    assert attempt["attempt"] == 2


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
