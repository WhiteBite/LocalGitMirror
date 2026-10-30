"""Real-store tests for the findings service: revisioned saves, independent
state axes, immutable batches, duplicate guards and bounded listing.

Store/git helpers are imported from test_mr_review_store; every store root
lives under a pytest tmp dir, the real ~/.lgm is never touched.
"""
import json
import sqlite3
import sys
import uuid
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from test_mr_review_store import _bundle, _git, _manifest, _ordinary_mirror

from lgm_core import mr_review_models as m
from lgm_core.mr_review_findings import LIST_PAGE_MAX, mr_review_findings
from lgm_core.mr_review_models import MrReviewError
from lgm_core.mr_review_store import MrReviewStore, import_snapshot

ANCHOR = {"old_path": "src/app.py", "new_path": "src/app.py", "side": "new", "line": 1}
GITLAB_URL = "https://gitlab.example.com"
BATCH_FIXTURE = Path(__file__).parent / "fixtures" / "mr_review_protocol" / "batch_v2.json"


@pytest.fixture(scope="session")
def base(tmp_path_factory):
    root = tmp_path_factory.mktemp("findings")
    mirror = _ordinary_mirror(root)
    manifest = _manifest(mirror)
    bundle = _bundle(root, mirror)
    import_snapshot(manifest, bundle, home=root / "reviews")
    return root, manifest


def _rev(snap, revision=1, *, comment="Замечание", verification="hypothesis",
         applicability="current", delivery="local", evidence=(), anchor=ANCHOR,
         thread_id=None, created_at="2026-10-30T10:00:00Z"):
    return {
        "revision": revision,
        "created_at": created_at,
        "snapshot_id": snap["snapshot_id"],
        "code_id": snap["code_id"],
        "version_fingerprint": snap["version_fingerprint"],
        "anchor": anchor,
        "thread_id": thread_id,
        "comment": comment,
        "trigger": "reading the diff",
        "evidence": list(evidence),
        "limitations": [],
        "verification": verification,
        "applicability": applicability,
        "delivery": delivery,
    }


def _finding(snap, revisions, *, finding_id=None, iid=30):
    return {
        "schema_version": 1,
        "finding_id": finding_id or str(uuid.uuid4()),
        "repo": "phonyx",
        "gitlab_url": GITLAB_URL,
        "target_project_id": 42,
        "iid": iid,
        "revisions": revisions,
    }


def _save(home, finding, expected_revision=0):
    return mr_review_findings("save", text=json.dumps(finding),
                              expected_revision=expected_revision, home=home)


def _listed(home, finding_id, iid=30):
    query = {"repo": "phonyx", "gitlab_url": GITLAB_URL,
             "target_project_id": 42, "iid": iid}
    result = mr_review_findings("list", text=json.dumps(query), home=home)
    for doc in result["findings"]:
        if doc["finding_id"] == finding_id:
            return doc
    return None


def _batch(home, items, iid=30):
    selection = {"repo": "phonyx", "gitlab_url": GITLAB_URL,
                 "target_project_id": 42, "iid": iid, "items": items}
    return mr_review_findings("create_batch", text=json.dumps(selection), home=home)


def _store_rows(home, sql, params=()):
    store = MrReviewStore(GITLAB_URL, 42, "phonyx", home=home)
    conn = sqlite3.connect(store.db_path)
    try:
        return conn.execute(sql, params).fetchall()
    finally:
        conn.close()


def _own_setup(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    return mirror, manifest, tmp_path / "reviews"


def test_save_and_list_roundtrip(base):
    root, manifest = base
    home = root / "reviews"
    finding = _finding(manifest, [_rev(manifest)])

    result = _save(home, finding)

    assert result == {"status": "saved", "finding_id": finding["finding_id"],
                      "revision": 1}
    doc = _listed(home, finding["finding_id"])
    assert doc == finding
    m.validate_finding(doc)


def test_save_from_file_input_and_input_source_rules(base, tmp_path):
    root, manifest = base
    home = root / "reviews"
    finding = _finding(manifest, [_rev(manifest)])
    path = tmp_path / "finding.json"
    path.write_text(json.dumps(finding), encoding="utf-8")

    result = mr_review_findings("save", file=str(path), home=home)
    assert result["revision"] == 1

    for kwargs in ({"file": str(path), "text": "{}"},
                   {"file": "", "text": ""},
                   {"text": "not json"},
                   {"text": "[1, 2]"}):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_findings("save", home=home, **kwargs)
        assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_findings("bogus", text="{}", home=home)
    assert excinfo.value.code == "invalid-schema"


def test_text_edit_preserves_uuid_and_bumps_revision(base):
    root, manifest = base
    home = root / "reviews"
    rev1 = _rev(manifest, 1, comment="Миграция не описана")
    rev2 = _rev(manifest, 2, comment="Миграция не описана; проверить upgrade-путь",
                verification="confirmed", evidence=["snapshot page 1"],
                created_at="2026-10-30T10:20:00Z")
    finding_id = str(uuid.uuid4())

    assert _save(home, _finding(manifest, [rev1], finding_id=finding_id))["revision"] == 1
    assert _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id),
                 expected_revision=1)["revision"] == 2

    doc = _listed(home, finding_id)
    assert doc["finding_id"] == finding_id
    assert doc["revisions"] == [rev1, rev2]
    m.validate_finding(doc)


def test_axes_move_independently(base):
    root, manifest = base
    home = root / "reviews"
    cases = [
        ("verification", "hypothesis", "confirmed"),
        ("applicability", "current", "needs-recheck"),
        ("delivery", "local", "queued"),
    ]
    for axis, before, after in cases:
        rev1 = _rev(manifest, 1, verification="confirmed")
        rev2 = dict(_rev(manifest, 2, verification="confirmed",
                         created_at="2026-10-30T10:20:00Z"))
        rev2[axis] = before
        rev1[axis] = before
        rev2[axis] = after
        finding_id = str(uuid.uuid4())
        _save(home, _finding(manifest, [rev1], finding_id=finding_id))
        _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id),
              expected_revision=1)

        doc = _listed(home, finding_id)
        stored1, stored2 = doc["revisions"]
        assert stored2[axis] == after
        for other in ("verification", "applicability", "delivery"):
            if other != axis:
                assert stored2[other] == stored1[other]


def test_fixed_without_evidence_refused(base):
    root, manifest = base
    home = root / "reviews"
    rev1 = _rev(manifest, 1, verification="confirmed")
    rev2 = _rev(manifest, 2, verification="confirmed", applicability="fixed",
                evidence=[])
    finding_id = str(uuid.uuid4())
    _save(home, _finding(manifest, [rev1], finding_id=finding_id))

    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id),
              expected_revision=1)
    assert excinfo.value.code == "invalid-schema"
    assert len(_listed(home, finding_id)["revisions"]) == 1


def test_revalidation_binds_new_snapshot_and_evidence(tmp_path):
    mirror, manifest_a, home = _own_setup(tmp_path)
    rev1 = _rev(manifest_a, 1, comment="first look", evidence=[])
    finding_id = str(uuid.uuid4())
    _save(home, _finding(manifest_a, [rev1], finding_id=finding_id))

    _git(mirror, "checkout", "-q", "feature")
    (mirror / "src" / "app.py").write_text("print('v3')\n", encoding="utf-8")
    _git(mirror, "add", ".")
    _git(mirror, "commit", "-qm", "revalidated source")
    _git(mirror, "checkout", "-q", "master")
    manifest_b = _manifest(mirror)
    bundle_b = tmp_path / "code-b.bundle"
    _git(mirror, "bundle", "create", str(bundle_b), "feature", "master")
    import_snapshot(manifest_b, bundle_b, home=home)
    assert manifest_b["snapshot_id"] != manifest_a["snapshot_id"]

    rev2 = _rev(manifest_b, 2, comment="revalidated", verification="confirmed",
                evidence=["probe exit 0", "snapshot page 2"],
                created_at="2026-10-30T11:00:00Z")
    _save(home, _finding(manifest_a, [rev1, rev2], finding_id=finding_id),
          expected_revision=1)

    doc = _listed(home, finding_id)
    assert doc["revisions"][0]["snapshot_id"] == manifest_a["snapshot_id"]
    assert doc["revisions"][1]["snapshot_id"] == manifest_b["snapshot_id"]
    assert doc["revisions"][1]["evidence"] == ["probe exit 0", "snapshot page 2"]

    other = _finding(manifest_a, [_rev(manifest_a, 1, verification="confirmed")])
    _save(home, other)
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [
            {"finding_id": finding_id, "revision": 2, "kind": "anchored"},
            {"finding_id": other["finding_id"], "revision": 1, "kind": "anchored"},
        ])
    assert excinfo.value.code == "invalid-schema"


def test_stale_or_lost_expected_revision(base):
    root, manifest = base
    home = root / "reviews"
    rev1 = _rev(manifest, 1)
    rev2 = _rev(manifest, 2, verification="confirmed",
                created_at="2026-10-30T10:20:00Z")
    rev3 = _rev(manifest, 3, verification="refuted",
                created_at="2026-10-30T10:30:00Z")
    finding_id = str(uuid.uuid4())
    _save(home, _finding(manifest, [rev1], finding_id=finding_id))

    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [rev1], finding_id=finding_id))
    assert excinfo.value.code == "revision-conflict"
    orphan = _finding(manifest, [rev1, rev2])
    with pytest.raises(MrReviewError) as excinfo:
        _save(home, orphan, expected_revision=1)
    assert excinfo.value.code == "revision-conflict"
    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [rev1, rev2, rev3], finding_id=finding_id),
              expected_revision=2)
    assert excinfo.value.code == "revision-conflict"

    _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id),
          expected_revision=1)
    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id),
              expected_revision=1)
    assert excinfo.value.code == "revision-conflict"

    for bad in (-1, True, "1"):
        with pytest.raises(MrReviewError) as excinfo:
            _save(home, _finding(manifest, [rev1]), expected_revision=bad)
        assert excinfo.value.code == "invalid-schema"


def test_revision_count_must_match_expected(base):
    root, manifest = base
    home = root / "reviews"
    rev1 = _rev(manifest, 1)
    rev2 = _rev(manifest, 2, verification="confirmed",
                created_at="2026-10-30T10:20:00Z")

    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [rev1, rev2]))
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [rev1]), expected_revision=1)
    assert excinfo.value.code == "invalid-schema"


def test_stored_revision_is_immutable(base):
    root, manifest = base
    home = root / "reviews"
    rev1 = _rev(manifest, 1)
    rev2 = _rev(manifest, 2, verification="confirmed",
                created_at="2026-10-30T10:20:00Z")
    finding_id = str(uuid.uuid4())
    _save(home, _finding(manifest, [rev1], finding_id=finding_id))

    tampered = dict(rev1)
    tampered["comment"] = "rewritten history"
    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [tampered, rev2], finding_id=finding_id),
              expected_revision=1)
    assert excinfo.value.code == "invalid-schema"
    assert "immutable" in str(excinfo.value)

    doc = _listed(home, finding_id)
    assert doc["revisions"] == [rev1]


def test_identity_change_on_update_refused(base):
    root, manifest = base
    home = root / "reviews"
    rev1 = _rev(manifest, 1)
    rev2 = _rev(manifest, 2, verification="confirmed",
                created_at="2026-10-30T10:20:00Z")
    finding_id = str(uuid.uuid4())
    _save(home, _finding(manifest, [rev1], finding_id=finding_id))

    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id, iid=31),
              expected_revision=1)
    assert excinfo.value.code == "invalid-schema"

    doc = _listed(home, finding_id)
    assert doc["iid"] == 30
    assert len(doc["revisions"]) == 1


def test_foreign_snapshot_refused(tmp_path):
    mirror, manifest_a, home = _own_setup(tmp_path)
    _git(mirror, "checkout", "-qb", "feature2", "feature")
    (mirror / "src" / "new.py").write_text("x = 2\n", encoding="utf-8")
    _git(mirror, "add", ".")
    _git(mirror, "commit", "-qm", "second feature")
    _git(mirror, "checkout", "-q", "master")
    manifest_b = _manifest(mirror, iid=31)
    bundle_b = tmp_path / "code-b.bundle"
    _git(mirror, "bundle", "create", str(bundle_b), "feature2", "master")
    import_snapshot(manifest_b, bundle_b, home=home)

    missing = _rev(manifest_a, 1)
    missing["snapshot_id"] = "f" * 64
    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest_a, [missing]))
    assert excinfo.value.code == "review-object-missing"

    foreign = _rev(manifest_b, 1)
    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest_a, [foreign]))
    assert excinfo.value.code == "invalid-schema"

    misbound = _rev(manifest_a, 1)
    misbound["code_id"] = "e" * 64
    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest_a, [misbound]))
    assert excinfo.value.code == "invalid-schema"


INVALID_TRANSITIONS = [
    ("verification", "confirmed", "hypothesis"),
    ("verification", "refuted", "hypothesis"),
    ("verification", "refuted", "confirmed"),
    ("applicability", "withdrawn", "current"),
    ("applicability", "withdrawn", "needs-recheck"),
    ("applicability", "withdrawn", "fixed"),
    ("applicability", "fixed", "current"),
    ("applicability", "fixed", "needs-recheck"),
    ("applicability", "fixed", "withdrawn"),
    ("delivery", "local", "received"),
    ("delivery", "local", "posted"),
    ("delivery", "local", "failed"),
    ("delivery", "local", "blocked"),
    ("delivery", "received", "queued"),
    ("delivery", "failed", "received"),
    ("delivery", "failed", "posted"),
    ("delivery", "failed", "blocked"),
    ("delivery", "blocked", "received"),
    ("delivery", "blocked", "posted"),
    ("delivery", "blocked", "failed"),
    ("delivery", "posted", "queued"),
    ("delivery", "posted", "received"),
    ("delivery", "posted", "failed"),
    ("delivery", "posted", "blocked"),
]


@pytest.mark.parametrize("axis,previous,new", INVALID_TRANSITIONS)
def test_invalid_transition_refused(base, axis, previous, new):
    root, manifest = base
    home = root / "reviews"
    evidence = ("probe exit 0",) if "fixed" in (previous, new) else ()
    rev1 = _rev(manifest, 1, verification="confirmed", evidence=evidence)
    rev2 = _rev(manifest, 2, verification="confirmed", evidence=evidence,
                created_at="2026-10-30T10:20:00Z")
    rev1[axis] = previous
    rev2[axis] = new
    finding_id = str(uuid.uuid4())
    _save(home, _finding(manifest, [rev1], finding_id=finding_id))

    with pytest.raises(MrReviewError) as excinfo:
        _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id),
              expected_revision=1)
    assert excinfo.value.code == "invalid-schema"
    assert axis in str(excinfo.value)
    assert len(_listed(home, finding_id)["revisions"]) == 1


VALID_TRANSITIONS = [
    ("verification", "hypothesis", "confirmed"),
    ("verification", "confirmed", "refuted"),
    ("verification", "refuted", "refuted"),
    ("applicability", "current", "needs-recheck"),
    ("applicability", "needs-recheck", "current"),
    ("applicability", "needs-recheck", "fixed"),
    ("applicability", "withdrawn", "withdrawn"),
    ("applicability", "fixed", "fixed"),
    ("delivery", "local", "local"),
    ("delivery", "local", "queued"),
    ("delivery", "queued", "received"),
    ("delivery", "queued", "posted"),
    ("delivery", "queued", "failed"),
    ("delivery", "queued", "blocked"),
    ("delivery", "received", "posted"),
    ("delivery", "failed", "queued"),
    ("delivery", "blocked", "queued"),
    ("delivery", "posted", "local"),
    ("delivery", "received", "local"),
]


@pytest.mark.parametrize("axis,previous,new", VALID_TRANSITIONS)
def test_valid_transition_accepted(base, axis, previous, new):
    root, manifest = base
    home = root / "reviews"
    evidence = ("probe exit 0",) if "fixed" in (previous, new) else ()
    rev1 = _rev(manifest, 1, verification="confirmed", evidence=evidence)
    rev2 = _rev(manifest, 2, verification="confirmed", evidence=evidence,
                created_at="2026-10-30T10:20:00Z")
    rev1[axis] = previous
    rev2[axis] = new
    finding_id = str(uuid.uuid4())
    _save(home, _finding(manifest, [rev1], finding_id=finding_id))

    result = _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id),
                   expected_revision=1)
    assert result["revision"] == 2
    assert _listed(home, finding_id)["revisions"][1][axis] == new


def _confirmed_finding(manifest, **rev_kwargs):
    rev_kwargs.setdefault("verification", "confirmed")
    rev = _rev(manifest, 1, **rev_kwargs)
    return _finding(manifest, [rev])


def test_batch_document_is_protocol_shaped(base):
    root, manifest = base
    home = root / "reviews"
    finding = _confirmed_finding(manifest)
    _save(home, finding)

    result = _batch(home, [
        {"finding_id": finding["finding_id"], "revision": 1, "kind": "anchored"}])

    assert result["status"] == "created"
    batch = result["batch"]
    m.validate_batch(batch)
    assert batch["repo"] == "phonyx"
    assert batch["gitlab_url"] == GITLAB_URL
    assert batch["target_project_id"] == 42
    assert batch["iid"] == 30

    item = batch["items"][0]
    assert item["finding_id"] == finding["finding_id"]
    assert item["revision"] == 1
    assert item["kind"] == "anchored"
    assert item["anchor"] == ANCHOR
    assert item["thread_id"] is None
    assert item["resolve"] is False
    assert item["body"] == finding["revisions"][0]["comment"]
    assert item["snapshot_id"] == manifest["snapshot_id"]
    assert item["code_id"] == manifest["code_id"]
    assert item["version_fingerprint"] == manifest["version_fingerprint"]
    assert item["source_sha"] == manifest["source_sha"]
    assert item["target_tip_sha"] == manifest["target_tip_sha"]
    assert item["diff_refs"] == manifest["gitlab_diff_refs"]

    fixture = json.loads(BATCH_FIXTURE.read_text(encoding="utf-8"))["object"]
    assert set(batch) == set(fixture)
    assert set(item) == set(fixture["items"][0])

    marker = m.review_marker(batch["batch_id"], item["finding_id"], item["revision"])
    assert m.parse_review_markers(f"<!-- {marker} -->") == [
        (batch["batch_id"], item["finding_id"], 1)]

    assert Path(result["path"]).read_bytes() == m.canonical_json(batch).encode("utf-8")
    assert result["artifact_path"] == m.batch_artifact_path(30, batch["batch_id"])
    rows = _store_rows(home, "SELECT payload FROM batches WHERE batch_id = ?",
                       (batch["batch_id"],))
    assert rows == [(m.canonical_json(batch),)]


def test_batch_thread_and_general_kinds(base):
    root, manifest = base
    home = root / "reviews"
    thread_finding = _confirmed_finding(manifest, anchor=None, thread_id="04ee218e8d3197c60a4ff9c175a5f35c7aeec8f9")
    anchored_finding = _confirmed_finding(manifest)
    _save(home, thread_finding)
    _save(home, anchored_finding)

    result = _batch(home, [
        {"finding_id": thread_finding["finding_id"], "revision": 1,
         "kind": "thread", "resolve": True}])
    item = result["batch"]["items"][0]
    assert item["kind"] == "thread"
    assert item["thread_id"] == "04ee218e8d3197c60a4ff9c175a5f35c7aeec8f9"
    assert item["resolve"] is True
    assert item["anchor"] is None

    result = _batch(home, [
        {"finding_id": anchored_finding["finding_id"], "revision": 1,
         "kind": "general", "body": "общий комментарий"}])
    item = result["batch"]["items"][0]
    assert item["kind"] == "general"
    assert item["anchor"] is None
    assert item["thread_id"] is None
    assert item["body"] == "общий комментарий"

    no_thread = _confirmed_finding(manifest)
    _save(home, no_thread)
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": no_thread["finding_id"], "revision": 1,
                       "kind": "thread"}])
    assert excinfo.value.code == "invalid-schema"
    thread_only = _confirmed_finding(manifest, anchor=None, thread_id="tid-1")
    _save(home, thread_only)
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": thread_only["finding_id"], "revision": 1,
                       "kind": "anchored"}])
    assert excinfo.value.code == "invalid-schema"
    resolve_anchor = _confirmed_finding(manifest)
    _save(home, resolve_anchor)
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": resolve_anchor["finding_id"], "revision": 1,
                       "kind": "anchored", "resolve": True}])
    assert excinfo.value.code == "invalid-schema"
    empty_body = _confirmed_finding(manifest)
    _save(home, empty_body)
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": empty_body["finding_id"], "revision": 1,
                       "kind": "general", "body": ""}])
    assert excinfo.value.code == "invalid-schema"


def test_unconfirmed_or_stale_revisions_cannot_queue(base):
    root, manifest = base
    home = root / "reviews"
    cases = [
        _confirmed_finding(manifest, verification="hypothesis"),
        _confirmed_finding(manifest, applicability="needs-recheck"),
        _confirmed_finding(manifest, applicability="withdrawn"),
        _confirmed_finding(manifest, applicability="fixed",
                           evidence=("probe exit 0",)),
    ]
    for finding in cases:
        _save(home, finding)
        with pytest.raises(MrReviewError) as excinfo:
            _batch(home, [{"finding_id": finding["finding_id"], "revision": 1,
                           "kind": "anchored"}])
        assert excinfo.value.code == "invalid-schema"


def test_posted_revision_never_reenters_batch(base):
    root, manifest = base
    home = root / "reviews"
    finding_id = str(uuid.uuid4())
    revisions = []
    for revision, delivery in enumerate(["local", "queued", "received", "posted"], start=1):
        revisions.append(_rev(manifest, revision, verification="confirmed",
                              delivery=delivery,
                              created_at=f"2026-10-30T10:{revision:02d}:00Z"))
        _save(home, _finding(manifest, revisions, finding_id=finding_id),
              expected_revision=revision - 1)

    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": finding_id, "revision": 4, "kind": "anchored"}])
    assert excinfo.value.code == "possible-duplicate"


def test_batched_revision_refused_in_second_batch(base):
    root, manifest = base
    home = root / "reviews"
    finding = _confirmed_finding(manifest)
    _save(home, finding)
    item = {"finding_id": finding["finding_id"], "revision": 1, "kind": "anchored"}

    first = _batch(home, [item])
    assert first["status"] == "created"

    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [item])
    assert excinfo.value.code == "possible-duplicate"


def test_batch_selection_identity_errors(base):
    root, manifest = base
    home = root / "reviews"
    finding = _confirmed_finding(manifest)
    _save(home, finding)
    fid = finding["finding_id"]

    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": str(uuid.uuid4()), "revision": 1,
                       "kind": "anchored"}])
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": fid, "revision": 99, "kind": "anchored"}])
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": fid, "revision": 1, "kind": "anchored"}], iid=31)
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": fid, "revision": 1}])
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": fid, "revision": 1, "kind": "anchored",
                       "bogus": 1}])
    assert excinfo.value.code == "invalid-schema"

    selection = {"repo": "phonyx", "gitlab_url": GITLAB_URL,
                 "target_project_id": 42, "iid": 30}
    for items in (None, []):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_findings("create_batch",
                               text=json.dumps({**selection, "items": items}), home=home)
        assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_findings("create_batch", text=json.dumps(selection), home=home)
    assert excinfo.value.code == "invalid-schema"


def test_list_is_bounded(tmp_path):
    _, manifest, home = _own_setup(tmp_path)
    count = LIST_PAGE_MAX + 5
    for _ in range(count):
        _save(home, _finding(manifest, [_rev(manifest)]))

    def listed(**extra):
        query = {"repo": "phonyx", "gitlab_url": GITLAB_URL,
                 "target_project_id": 42, "iid": 30, **extra}
        return mr_review_findings("list", text=json.dumps(query), home=home)

    page1 = listed()
    assert len(page1["findings"]) == LIST_PAGE_MAX
    assert page1["total"] == count
    assert page1["next_cursor"] == LIST_PAGE_MAX
    ids1 = [doc["finding_id"] for doc in page1["findings"]]
    assert ids1 == sorted(ids1)

    page2 = listed(cursor=LIST_PAGE_MAX)
    assert len(page2["findings"]) == 5
    assert page2["total"] == count
    assert page2["next_cursor"] is None
    ids2 = [doc["finding_id"] for doc in page2["findings"]]
    assert not set(ids1) & set(ids2)
    assert len(set(ids1) | set(ids2)) == count

    assert len(listed(limit=10)["findings"]) == 10
    assert listed(limit=10)["next_cursor"] == 10
    assert len(listed(limit=999)["findings"]) == LIST_PAGE_MAX

    for bad in ({"limit": 0}, {"limit": True}, {"cursor": -1}, {"cursor": "5"}):
        with pytest.raises(MrReviewError) as excinfo:
            listed(**bad)
        assert excinfo.value.code == "invalid-schema"


def test_list_query_validation(base):
    root, _manifest_snap = base
    home = root / "reviews"
    base_query = {"repo": "phonyx", "gitlab_url": GITLAB_URL,
                  "target_project_id": 42, "iid": 30}

    for mutate in (lambda q: q.update({"bogus": 1}),
                   lambda q: q.pop("iid"),
                   lambda q: q.update({"repo": ""}),
                   lambda q: q.update({"gitlab_url": "ftp://x"}),
                   lambda q: q.update({"target_project_id": 0})):
        query = dict(base_query)
        mutate(query)
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_findings("list", text=json.dumps(query), home=home)
        assert excinfo.value.code == "invalid-schema"


def test_durability_across_store_instances(base):
    root, manifest = base
    home = root / "reviews"
    rev1 = _rev(manifest, 1)
    rev2 = _rev(manifest, 2, verification="confirmed",
                created_at="2026-10-30T10:20:00Z")
    finding_id = str(uuid.uuid4())
    _save(home, _finding(manifest, [rev1], finding_id=finding_id))
    _save(home, _finding(manifest, [rev1, rev2], finding_id=finding_id),
          expected_revision=1)
    batch = _batch(home, [{"finding_id": finding_id, "revision": 2,
                           "kind": "anchored"}])

    reopened = MrReviewStore(GITLAB_URL, 42, "phonyx", home=home)
    conn = sqlite3.connect(reopened.db_path)
    try:
        row = conn.execute(
            "SELECT revision, payload FROM findings WHERE finding_id = ?",
            (finding_id,)).fetchone()
        assert row[0] == 2
        assert row[1] == m.canonical_json(rev2)
        assert conn.execute(
            "SELECT COUNT(*) FROM finding_revisions WHERE finding_id = ?",
            (finding_id,)).fetchone()[0] == 2
        assert conn.execute(
            "SELECT COUNT(*) FROM batches WHERE batch_id = ?",
            (batch["batch_id"],)).fetchone()[0] == 1
    finally:
        conn.close()

    doc = _listed(home, finding_id)
    assert doc["revisions"] == [rev1, rev2]

    with pytest.raises(MrReviewError) as excinfo:
        _batch(home, [{"finding_id": finding_id, "revision": 2, "kind": "anchored"}])
    assert excinfo.value.code == "possible-duplicate"
