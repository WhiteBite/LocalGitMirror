"""Shared legacy/v2 review-handoff pipeline: one parse -> normalize -> validate
path for mr_replies_send check/send and mr_replies_push, so preview, upload and
publication reject identical malformed documents and check never writes.

Regression first (plan task 11): check=true used to accept a malformed body
that push would later skip/mis-post; send used to upload it. Shared fixtures
in tests/fixtures/mr_review_handoff/ pin identical outcomes in Kotlin
(MrReviewHandoffTest.kt).
"""
import hashlib
import json
import sqlite3
import sys
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from test_mr_replies_push import FakeClient, _empty_registry, work_env  # noqa: F401
from test_mr_review_publish import PublishClient

from lgm_core.mr_review_handoff import (
    HandoffRejection,
    looks_like_json,
    parse_and_validate,
    parse_legacy,
)
from lgm_core.mr_review_models import canonical_json
from lgm_core.op_models import Ctx
from lgm_core.ops import op_mr_replies_push, op_mr_replies_send

_NOTES_MD = "\n".join([
    "# MR !7 — Title seven",
    "",
    "- **Ветка:** `feature`",
    "- **Target:** `master`",
])

_FIXTURE_DIR = Path(__file__).parent / "fixtures" / "mr_review_handoff"
_PROTO = json.loads((Path(__file__).parent / "fixtures" / "mr_review_protocol"
                     / "batch_v2.json").read_text(encoding="utf-8"))
_BATCH = _PROTO["object"]
_DIGESTS = _PROTO["expected_digests"]
_BATCH_ID = _BATCH["batch_id"]
_FID_A = _BATCH["items"][0]["finding_id"]


@pytest.fixture(autouse=True)
def _isolated_reviews_home(tmp_path, monkeypatch):
    monkeypatch.setenv("LGM_REVIEWS_HOME", str(tmp_path / "reviews-home"))


class _PostboxClient:
    """Minimal postbox fake: notes available, every write recorded."""

    def __init__(self):
        self.sent: list[tuple] = []
        self.acked: list[str] = []

    def file_sync_list(self, repo):
        return {"items": [{"id": "n1", "path": "mr-notes/mr-!7.md", "mtime": 5,
                           "plain_size": len(_NOTES_MD)}]}

    def file_sync_fetch(self, repo, item_id):
        return _NOTES_MD.encode("utf-8")

    def file_sync_send(self, repo, path, plain_size, data):
        self.sent.append((repo, path, plain_size, data))
        return {"id": "up1"}

    def file_sync_ack(self, repo, item_id):
        self.acked.append(item_id)
        return {"success": True}


def _send(client, tmp_path, body=None, *, check=False, force=False, review_file="",
          iid=7, text=None):
    args = {"repo": "r1", "iid": iid, "check": check, "force": force}
    if review_file:
        args["review_file"] = review_file
    if text is not None:
        args["text"] = text
    elif body:
        p = tmp_path / "replies.md"
        p.write_text(body, encoding="utf-8")
        args["file"] = str(p)
    ctx = Ctx(config=SimpleNamespace(sync_password="", storage_path=""),
              client=client)
    return op_mr_replies_send(ctx, args)


def _write_batch(tmp_path, batch, name="batch.review.json"):
    p = tmp_path / name
    p.write_text(json.dumps(batch, ensure_ascii=False), encoding="utf-8")
    return str(p)


def _mutated(**over):
    batch = json.loads(json.dumps(_BATCH))
    batch.update(over)
    return batch


_MALFORMED_SECOND_SECTION = "## new A.java:5\nok\n\n## new B.java:0\nbad\n"


class TestRegressionCheckAcceptedMalformedBody:
    """ops_mr.py:722 — check=true never ran the full parser (plan task 11)."""

    def test_check_true_rejects_malformed_second_section(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, _MALFORMED_SECOND_SECTION, check=True)
        assert res["success"] is False
        assert "bad file:line" in res["error"]
        assert client.sent == [] and client.acked == []

    def test_send_rejects_malformed_second_section_without_upload(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, _MALFORMED_SECOND_SECTION)
        assert res["success"] is False
        assert "bad file:line" in res["error"]
        assert client.sent == [] and client.acked == []

    def test_force_does_not_bypass_malformed_section_rejection(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, _MALFORMED_SECOND_SECTION, force=True)
        assert res["success"] is False
        assert client.sent == []


# ── Legacy grammar parity (frozen shipped semantics) ─────────────────────────

_LEGACY_SAMPLE = "\n".join([
    "<!-- lgm-replies v1 -->",
    "# MR !42 — answers",
    "- branch: refactor/x",
    "",
    "## thread 8f2e4c1d",
    "resolve: yes",
    "Moved reset before fetch, added regression test.",
    "",
    "## new backend/app/routers/sync.py:386",
    "Detach here strands HEAD on early returns.",
    "",
    "## new",
    "Overall looks good, one question below.",
    "",
    "## thread a0b177d9",
])


class TestLegacyGrammarParity:
    def test_parse_legacy_matches_the_shipped_grammar(self):
        parsed = parse_legacy(_LEGACY_SAMPLE)
        assert parsed["iid"] == 42
        assert parsed["branch"] == "refactor/x"
        assert parsed["replies"] == [
            {"kind": "thread", "thread_id": "8f2e4c1d", "file": "", "line": 0,
             "resolve": True,
             "body": "Moved reset before fetch, added regression test."},
            {"kind": "new_anchored", "thread_id": "",
             "file": "backend/app/routers/sync.py", "line": 386,
             "resolve": False,
             "body": "Detach here strands HEAD on early returns."},
            {"kind": "new_general", "thread_id": "", "file": "", "line": 0,
             "resolve": False,
             "body": "Overall looks good, one question below."},
        ]
        assert parsed["errors"] == ["section 4: empty body, skipped"]

    def test_resolve_on_non_thread_section_is_ignored(self):
        parsed = parse_legacy("# MR !7\n## new a.kt:1\nresolve: yes\ntext\n")
        assert parsed["replies"][0]["resolve"] is False
        assert parsed["replies"][0]["body"] == "text"

    def test_resolve_line_after_body_is_body_text(self):
        parsed = parse_legacy("## thread t1\nhello\nresolve: yes\n")
        assert parsed["replies"][0]["body"] == "hello\nresolve: yes"
        assert parsed["replies"][0]["resolve"] is False

    def test_resolve_no_sets_false(self):
        parsed = parse_legacy("## thread t1\nresolve: no\nfixed\n")
        assert parsed["replies"][0]["resolve"] is False

    def test_garbage_without_sections_yields_nothing(self):
        parsed = parse_legacy("just some text\nno structure")
        assert parsed["iid"] == 0
        assert parsed["replies"] == [] and parsed["errors"] == []

    def test_document_shape_for_legacy(self):
        doc = parse_and_validate(_LEGACY_SAMPLE.split("## thread a0b177d9")[0])
        assert doc["format"] == "legacy"
        assert doc["iid"] == 42 and doc["branch"] == "refactor/x"
        assert doc["batch_id"] is None and doc["batch"] is None
        assert doc["identity"] is None and doc["refs"] is None
        assert doc["general_fallback"] is True
        assert len(doc["entries"]) == 3

    def test_header_mismatch_message_is_preserved(self):
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate("# MR !8\n\n## new A.java:5\nok\n", expected_iid=7)
        assert excinfo.value.code == ""
        assert excinfo.value.message == "content header says MR !8 but iid=7"

    def test_headerless_document_takes_the_expected_iid(self):
        doc = parse_and_validate("## new A.java:5\nok\n", expected_iid=7)
        assert doc["iid"] == 7

    def test_want_v2_refuses_legacy_markdown(self):
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate("## new A.java:5\nok\n", want="v2")
        assert excinfo.value.code == "invalid-schema"
        assert "must be a JSON document" in excinfo.value.message

    def test_want_legacy_refuses_json(self):
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate('{"hello": "world"}', want="legacy")
        assert excinfo.value.code == "invalid-schema"
        assert "never parsed as legacy Markdown" in excinfo.value.message

    def test_looks_like_json_routing(self):
        assert looks_like_json('  {"a": 1}')
        assert not looks_like_json("<!-- lgm-replies v1 -->\n{}")
        assert not looks_like_json("## new\n")


# ── V2 pipeline ──────────────────────────────────────────────────────────────

class TestV2Pipeline:
    def test_valid_batch_normalizes_with_markers_and_identity(self):
        doc = parse_and_validate(json.dumps(_BATCH), expected_iid=30)
        assert doc["format"] == "v2"
        assert doc["iid"] == 30 and doc["batch_id"] == _BATCH_ID
        assert doc["general_fallback"] is False
        assert doc["identity"] == _DIGESTS
        assert doc["refs"] == {
            "source_sha": _BATCH["items"][0]["source_sha"],
            "target_tip_sha": _BATCH["items"][0]["target_tip_sha"],
            "diff_refs": _BATCH["items"][0]["diff_refs"],
        }
        anchored, thread = doc["entries"]
        assert anchored["kind"] == "anchored"
        assert anchored["anchor"] == _BATCH["items"][0]["anchor"]
        assert anchored["anchor"]["side"] == "new"
        assert anchored["marker"] == f"lgm-review:{_BATCH_ID}:{_FID_A}:2"
        assert thread["kind"] == "thread" and thread["resolve"] is True
        assert thread["thread_id"] == _BATCH["items"][1]["thread_id"]
        assert doc["batch"] == _BATCH

    def test_version_mismatch_is_typed(self):
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate(json.dumps(_mutated(schema_version=2)))
        assert excinfo.value.code == "unknown-schema-version"

    def test_conflicting_revisions_of_one_finding_are_refused(self):
        batch = json.loads(json.dumps(_BATCH))
        twin = json.loads(json.dumps(batch["items"][0]))
        twin["revision"] = 1
        batch["items"].append(twin)
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate(json.dumps(batch))
        assert excinfo.value.code == "possible-duplicate"
        assert "appears twice" in excinfo.value.message

    def test_unknown_json_is_never_legacy(self):
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate('{"hello": "world"}')
        assert excinfo.value.code == "invalid-schema"

    def test_malformed_json_text_is_refused(self):
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate("{not json")
        assert excinfo.value.code == "invalid-schema"
        assert "not valid JSON" in excinfo.value.message

    def test_iid_and_batch_id_binding(self):
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate(json.dumps(_BATCH), expected_iid=31)
        assert excinfo.value.code == "invalid-schema"
        assert "batch document says MR !30 but iid=31" in excinfo.value.message
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate(json.dumps(_BATCH),
                               expected_batch_id="00000000-0000-4000-8000-000000000000")
        assert "does not match the artifact path" in excinfo.value.message

    def test_non_uniform_refs_across_items_are_refused(self):
        batch = json.loads(json.dumps(_BATCH))
        twin = json.loads(json.dumps(batch["items"][1]))
        twin["finding_id"] = "3c7dd0a1-7e4f-4b6d-8a22-5f9e2b3c4d5e"
        twin["source_sha"] = "a" * 40
        batch["items"].append(twin)
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate(json.dumps(batch))
        assert excinfo.value.code == "invalid-schema"
        assert "one snapshot's refs" in excinfo.value.message


# ── Posted-revision guard against the available HOME state ───────────────────

def _fake_store_db(home, rows):
    from lgm_core.mr_review_store import repo_key, reviews_home
    root = reviews_home(home) / repo_key(
        _BATCH["gitlab_url"], _BATCH["target_project_id"], _BATCH["repo"])
    root.mkdir(parents=True, exist_ok=True)
    db = root / "review.sqlite3"
    conn = sqlite3.connect(db)
    conn.execute("CREATE TABLE finding_revisions (finding_id TEXT NOT NULL, "
                 "revision INTEGER NOT NULL, payload TEXT NOT NULL, "
                 "created_at TEXT NOT NULL, PRIMARY KEY (finding_id, revision))")
    for finding_id, revision, delivery in rows:
        conn.execute("INSERT INTO finding_revisions VALUES (?, ?, ?, ?)",
                     (finding_id, revision, json.dumps({"delivery": delivery}),
                      "2026-10-30T10:00:00Z"))
    conn.commit()
    conn.close()
    return db


class TestPostedStateGuard:
    def test_posted_revision_is_refused_as_possible_duplicate(self, tmp_path):
        home = tmp_path / "reviews"
        _fake_store_db(home, [(_FID_A, 2, "posted")])
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate(json.dumps(_BATCH), expected_iid=30, home=home)
        assert excinfo.value.code == "possible-duplicate"
        assert "already posted" in excinfo.value.message

    def test_unposted_state_and_missing_store_are_accepted(self, tmp_path):
        home = tmp_path / "reviews"
        _fake_store_db(home, [(_FID_A, 2, "queued")])
        doc = parse_and_validate(json.dumps(_BATCH), expected_iid=30, home=home)
        assert doc["format"] == "v2"
        doc = parse_and_validate(json.dumps(_BATCH), expected_iid=30,
                                 home=tmp_path / "empty")
        assert doc["format"] == "v2"

    def test_state_read_leaves_db_bytes_untouched(self, tmp_path):
        home = tmp_path / "reviews"
        db = _fake_store_db(home, [(_FID_A, 2, "posted")])
        before = hashlib.sha256(db.read_bytes()).hexdigest()
        with pytest.raises(HandoffRejection):
            parse_and_validate(json.dumps(_BATCH), expected_iid=30, home=home)
        assert hashlib.sha256(db.read_bytes()).hexdigest() == before
        assert [p.name for p in db.parent.iterdir()] == ["review.sqlite3"]


# ── mr_replies_send: review_file plumbing and zero-write check ───────────────

class TestSendReviewFile:
    def test_valid_batch_uploads_canonical_bytes_to_the_artifact_path(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, "", iid=30,
                    review_file=_write_batch(tmp_path, _BATCH))
        assert res["success"] is True
        assert res["format"] == "v2" and res["batch_id"] == _BATCH_ID
        assert res["path"] == f"mr-replies/mr-!30-{_BATCH_ID}.review.json"
        _repo, path, size, data = client.sent[0]
        assert path == res["path"] and size == len(data)
        assert data == canonical_json(_BATCH).encode("utf-8")
        assert client.acked == []

    def test_check_true_validates_with_zero_writes(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, "", iid=30, check=True,
                    review_file=_write_batch(tmp_path, _BATCH))
        assert res["success"] is True and res["check_only"] is True
        assert res["format"] == "v2" and res["sections"] == 2
        assert res["identity"] == _DIGESTS
        assert client.sent == [] and client.acked == []

    def test_review_file_is_exclusive_with_legacy_inputs(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, "## new\nx\n", iid=30,
                    review_file=_write_batch(tmp_path, _BATCH))
        assert res["success"] is False
        assert "exclusive" in res["error"]
        assert client.sent == []

    def test_legacy_markdown_in_review_file_is_refused(self, tmp_path):
        client = _PostboxClient()
        p = tmp_path / "legacy.md"
        p.write_text("## new A.java:5\nok\n", encoding="utf-8")
        res = _send(client, tmp_path, "", iid=30, review_file=str(p))
        assert res["success"] is False
        assert res["code"] == "invalid-schema"
        assert "must be a JSON document" in res["error"]
        assert client.sent == []

    def test_missing_review_file_is_reported(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, "", iid=30,
                    review_file=str(tmp_path / "nope.json"))
        assert res["success"] is False
        assert "file not found" in res["error"]

    def test_version_mismatch_refuses_check_send_and_force_alike(self, tmp_path):
        bad = _write_batch(tmp_path, _mutated(schema_version=2))
        for kwargs in ({"check": True}, {}, {"force": True}):
            client = _PostboxClient()
            res = _send(client, tmp_path, "", iid=30, review_file=bad, **kwargs)
            assert res["success"] is False
            assert res["code"] == "unknown-schema-version"
            assert client.sent == [] and client.acked == []

    def test_conflicting_revisions_refuse_with_zero_uploads(self, tmp_path):
        batch = json.loads(json.dumps(_BATCH))
        twin = json.loads(json.dumps(batch["items"][0]))
        twin["revision"] = 1
        batch["items"].append(twin)
        client = _PostboxClient()
        res = _send(client, tmp_path, "", iid=30,
                    review_file=_write_batch(tmp_path, batch))
        assert res["success"] is False
        assert res["code"] == "possible-duplicate"
        assert client.sent == [] and client.acked == []

    def test_json_through_legacy_text_is_redirected_to_review_file(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, "", iid=30, text='{"hello": "world"}')
        assert res["success"] is False
        assert "review_file" in res["error"]
        assert client.sent == []

    def test_two_batches_for_one_iid_coexist(self, tmp_path):
        client = _PostboxClient()
        second = _mutated(batch_id=str(uuid.uuid4()))
        _send(client, tmp_path, "", iid=30,
              review_file=_write_batch(tmp_path, _BATCH, "b1.json"))
        res = _send(client, tmp_path, "", iid=30,
                    review_file=_write_batch(tmp_path, second, "b2.json"))
        assert res["success"] is True
        paths = [s[1] for s in client.sent]
        assert paths == [f"mr-replies/mr-!30-{_BATCH_ID}.review.json",
                         f"mr-replies/mr-!30-{second['batch_id']}.review.json"]

    def test_legacy_send_still_uploads_the_injected_header_bytes(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, "## new A.java:5\nok\n")
        assert res["success"] is True and res["format"] == "legacy"
        assert res["path"] == "mr-replies/mr-!7.md"
        assert client.sent[0][3] == b"# MR !7\n\n## new A.java:5\nok\n"

    def test_legacy_check_true_has_zero_writes(self, tmp_path):
        client = _PostboxClient()
        res = _send(client, tmp_path, "## new A.java:5\nok\n", check=True)
        assert res["success"] is True and res["check_only"] is True
        assert res["format"] == "legacy" and res["sections"] == 1
        assert client.sent == [] and client.acked == []


# ── mr_replies_push: identical rejections, zero side effects ─────────────────

def _push(client, args=None):
    full = {"repo": "r1", "iid": 0, "dry_run": False}
    full.update(args or {})
    ctx = Ctx(config=SimpleNamespace(sync_password="", storage_path=""),
              client=client)
    return op_mr_replies_push(ctx, full)


def _add_v2_file(client, item_id, batch_id, data, iid=30):
    client.postbox[item_id] = {
        "path": f"mr-replies/mr-!{iid}-{batch_id}.review.json",
        "mtime": 5, "data": data}


def _assert_no_gitlab_no_writes(client):
    assert client.thread_notes == [] and client.mr_notes == []
    assert client.new_discussions == [] and client.resolved == []
    assert client.acked == [] and client.uploads == []


class TestPushRejections:
    def test_malformed_legacy_file_is_rejected_whole(self, work_env):  # noqa: F811
        client = FakeClient()
        client.add_reply_file(7, "# MR !7\n\n" + _MALFORMED_SECOND_SECTION)
        res = _push(client)
        rep = next(r for r in res["reports"] if r.get("iid") == 7)
        assert rep["posted"] == 0 and rep["skipped"] == 1 and rep["acked"] is False
        assert rep["parse_errors"] == 1
        assert rep["details"] == ["rejected: section 2: bad file:line, skipped"]
        _assert_no_gitlab_no_writes(client)

    def test_json_inside_legacy_md_file_is_rejected(self, work_env):  # noqa: F811
        client = FakeClient()
        client.add_reply_file(7, json.dumps(_BATCH))
        res = _push(client)
        rep = next(r for r in res["reports"] if r.get("iid") == 7)
        assert rep["posted"] == 0 and rep["acked"] is False
        assert "never parsed as legacy Markdown" in rep["details"][0]
        _assert_no_gitlab_no_writes(client)

    def test_legacy_header_still_wins_over_path_iid(self, work_env):  # noqa: F811
        client = FakeClient()
        client.add_reply_file(7, "# MR !8\n\n## new\nx\n")
        res = _push(client)
        rep = next(r for r in res["reports"] if r.get("iid") == 8)
        assert rep["posted"] == 1
        assert client.mr_notes[0][0] == 8

    def test_valid_v2_batch_enters_the_publication_gate(self, work_env):  # noqa: F811
        client = PublishClient()
        _add_v2_file(client, "b1", _BATCH_ID, json.dumps(_BATCH).encode("utf-8"))
        res = _push(client)
        rep = next(r for r in res["reports"] if r.get("format") == "v2")
        assert rep["iid"] == 30 and rep["batch_id"] == _BATCH_ID
        assert rep["posted"] == 0 and rep["failed"] == 2
        assert rep["acked"] is False and rep["parse_errors"] == 0
        assert "stale-snapshot" in rep["details"][0]
        assert client.positioned == []
        _assert_no_gitlab_no_writes(client)

    def test_malformed_v2_batch_is_rejected_with_its_code(self, work_env):  # noqa: F811
        client = FakeClient()
        _add_v2_file(client, "b1", _BATCH_ID,
                     json.dumps(_mutated(schema_version=2)).encode("utf-8"))
        res = _push(client)
        rep = next(r for r in res["reports"] if r.get("format") == "v2")
        assert rep["posted"] == 0 and rep["parse_errors"] == 1
        assert "unknown-schema-version" in rep["details"][0]
        assert rep["acked"] is False
        _assert_no_gitlab_no_writes(client)

    def test_v2_path_batch_id_mismatch_is_rejected(self, work_env):  # noqa: F811
        client = FakeClient()
        _add_v2_file(client, "b1", str(uuid.uuid4()),
                     json.dumps(_BATCH).encode("utf-8"))
        res = _push(client)
        rep = next(r for r in res["reports"] if r.get("format") == "v2")
        assert rep["parse_errors"] == 1
        assert "does not match the artifact path" in rep["details"][0]
        _assert_no_gitlab_no_writes(client)

    def test_two_v2_batches_for_one_iid_are_both_reported(self, work_env):  # noqa: F811
        client = PublishClient()
        second_id = str(uuid.uuid4())
        _add_v2_file(client, "b1", _BATCH_ID, json.dumps(_BATCH).encode("utf-8"))
        _add_v2_file(client, "b2", second_id,
                     json.dumps(_mutated(batch_id=second_id)).encode("utf-8"))
        res = _push(client)
        v2 = [r for r in res["reports"] if r.get("format") == "v2"]
        assert sorted(r["batch_id"] for r in v2) == sorted([_BATCH_ID, second_id])
        assert all(r["acked"] is False for r in v2)
        _assert_no_gitlab_no_writes(client)

    def test_iid_filter_applies_to_v2_batches(self, work_env):  # noqa: F811
        client = PublishClient()
        _add_v2_file(client, "b1", _BATCH_ID, json.dumps(_BATCH).encode("utf-8"))
        res = _push(client, {"iid": 31})
        assert res["reports"] == []
        res = _push(client, {"iid": 30})
        assert len(res["reports"]) == 1


# ── Shared cross-language fixtures ───────────────────────────────────────────

@pytest.mark.parametrize("path", sorted(_FIXTURE_DIR.glob("*.json")),
                         ids=lambda p: p.stem)
def test_shared_fixture_parity(path):
    fx = json.loads(path.read_text(encoding="utf-8"))
    content = (fx["input"] if "input" in fx
               else json.dumps(fx["input_object"], ensure_ascii=False))
    kwargs = {"expected_iid": fx.get("expected_iid", 0),
              "expected_batch_id": fx.get("expected_batch_id", ""),
              "want": fx.get("want", "")}
    if fx["expect"] == "accepted":
        doc = parse_and_validate(content, **kwargs)
        assert doc["format"] == fx["expected_format"]
        assert len(doc["entries"]) == fx["expected_entry_count"]
        if "expected_kinds" in fx:
            assert [e["kind"] for e in doc["entries"]] == fx["expected_kinds"]
        if "expected_markers" in fx:
            assert [e["marker"] for e in doc["entries"]] == fx["expected_markers"]
        if "expected_identity" in fx:
            assert doc["identity"] == fx["expected_identity"]
        if "expected_anchors" in fx:
            assert [e["anchor"] for e in doc["entries"]] == fx["expected_anchors"]
        if "expected_general_fallback" in fx:
            assert doc["general_fallback"] is fx["expected_general_fallback"]
        if fx.get("expected_iid"):
            assert doc["iid"] == fx["expected_iid"]
    else:
        with pytest.raises(HandoffRejection) as excinfo:
            parse_and_validate(content, **kwargs)
        rejection = excinfo.value
        assert rejection.code == fx.get("expected_error", "")
        if "expected_error_contains" in fx:
            assert fx["expected_error_contains"] in rejection.message
        if "expected_errors" in fx:
            assert rejection.errors == fx["expected_errors"]


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
