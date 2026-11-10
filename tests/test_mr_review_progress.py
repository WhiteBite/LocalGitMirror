"""Real-store tests for MR review progress: unit marks with evidence
handles, per-row revision CAS, read-neutrality, durability across store
instances and processes, and refresh carryover/invalidation rules.

Store/git/findings helpers are imported from the committed task-2/task-10
test modules; every store root lives under a pytest tmp dir.
"""
import json
import sqlite3
import subprocess
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import mr_review_models as m
from lgm_core.mr_review_models import MrReviewError
from lgm_core.mr_review_progress import (
    LIST_PAGE_MAX,
    mr_review_progress,
    mr_review_refresh,
)
from lgm_core.mr_review_reader import encode_cursor, open_reader
from lgm_core.mr_review_store import MrReviewStore, import_snapshot
from test_mr_review_findings import _finding, _listed, _rev, _save
from test_mr_review_store import _bundle, _git, _manifest, _ordinary_mirror

GITLAB_URL = "https://gitlab.example.com"
REPO_ROOT = Path(__file__).resolve().parent.parent


def _setup(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    home = tmp_path / "reviews"
    import_snapshot(manifest, bundle, home=home)
    return mirror, manifest, home


def _mark(home, path, state, *, snapshot_id="", reason=None, evidence=None,
          expected_revision=0, repo="phonyx", iid=30):
    payload = {"path": path, "state": state}
    if snapshot_id:
        payload["snapshot_id"] = snapshot_id
    if reason is not None:
        payload["reason"] = reason
    if evidence is not None:
        payload["evidence"] = evidence
    return mr_review_progress(repo, iid, action="mark", text=json.dumps(payload),
                              expected_revision=expected_revision, home=home)


def _listed_progress(home, *, snapshot_id="", cursor=0, limit=LIST_PAGE_MAX,
                     repo="phonyx", iid=30):
    query = {"cursor": cursor, "limit": limit}
    if snapshot_id:
        query["snapshot_id"] = snapshot_id
    return mr_review_progress(repo, iid, action="list", text=json.dumps(query),
                              home=home)


def _all_units(home, *, snapshot_id="", repo="phonyx", iid=30):
    units = {}
    cursor = 0
    while True:
        page = _listed_progress(home, snapshot_id=snapshot_id, cursor=cursor)
        for entry in page["units"]:
            units[entry["path"]] = entry
        if page["next_cursor"] is None:
            return units
        cursor = page["next_cursor"]


def _variant(manifest, **changes):
    snap = dict(manifest)
    snap.update(changes)
    snap["code_id"] = m.compute_code_id(snap)
    snap["version_fingerprint"] = m.compute_version_fingerprint(snap)
    snap["snapshot_id"] = m.compute_snapshot_id(snap)
    return snap


def _unit_oid(home, snapshot_id, path, side="new"):
    reader = open_reader(home, "phonyx", snapshot_id)
    page = reader.inventory_page(budget_bytes=24000)
    while True:
        for entry in page["entries"]:
            if entry["path"] == path:
                info = entry[side]
                return info["oid"] if info else None
        if page["next_cursor"] is None:
            return None
        page = reader.inventory_page(cursor=page["next_cursor"], budget_bytes=24000)


def _progress_rows(home, snapshot_id):
    store = MrReviewStore(GITLAB_URL, 42, "phonyx", home=home)
    conn = sqlite3.connect(store.db_path)
    try:
        return conn.execute(
            "SELECT path, revision, payload FROM progress WHERE snapshot_id = ? ORDER BY path",
            (snapshot_id,)).fetchall()
    finally:
        conn.close()


def _discussion(discussion_id, note_id, body):
    return {"discussion_id": discussion_id,
            "notes": [{"note_id": note_id, "body": body, "author": "reviewer",
                       "created_at": "2026-10-30T12:00:00Z", "system": False,
                       "resolvable": True, "resolved": False, "position": None}]}


def _inventory_cursor(snapshot_id):
    return "cursor:" + encode_cursor(
        snapshot_id, {"repo": "phonyx", "iid": 30, "view": "inventory"},
        {"e": 1, "g": 0})


def test_mark_delivered_then_checked_roundtrip(tmp_path):
    _mirror, manifest, home = _setup(tmp_path)
    app_oid = _unit_oid(home, manifest["snapshot_id"], "src/app.py")
    cursor_handle = _inventory_cursor(manifest["snapshot_id"])

    delivered = _mark(home, "src/app.py", "delivered")
    assert delivered == {"status": "marked", "repo": "phonyx", "iid": 30,
                         "snapshot_id": manifest["snapshot_id"],
                         "path": "src/app.py", "state": "delivered",
                         "revision": 1}

    checked = _mark(home, "src/app.py", "checked",
                    evidence=[f"blob:{app_oid}", cursor_handle],
                    expected_revision=1)
    assert checked["revision"] == 2

    units = _all_units(home)
    assert set(units) == {"src/app.py", "src/new.py"}
    app = units["src/app.py"]
    assert app["state"] == "checked"
    assert app["revision"] == 2
    assert app["evidence"] == [f"blob:{app_oid}", cursor_handle]
    assert app["in_inventory"] is True
    assert app["marked_at"] is not None
    assert units["src/new.py"]["state"] == "unchecked"
    assert units["src/new.py"]["revision"] == 0

    page = _listed_progress(home)
    assert page["status"] == "listed"
    assert page["snapshot"]["is_current"] is True
    assert page["totals"] == {"units": 2, "delivered": 0, "checked": 1,
                              "deferred": 0, "not-applicable": 0, "unchecked": 1,
                              "not_in_inventory": 0}

    rows = _progress_rows(home, manifest["snapshot_id"])
    assert len(rows) == 1
    assert rows[0][0] == "src/app.py"
    assert rows[0][1] == 2
    assert json.loads(rows[0][2])["state"] == "checked"


def test_reads_never_mark_checked(tmp_path):
    _mirror, manifest, home = _setup(tmp_path)
    _mark(home, "src/app.py", "delivered")

    for _ in range(3):
        _listed_progress(home)
    reader = open_reader(home, "phonyx", manifest["snapshot_id"])
    diff_page = reader.read_page(view="diff", budget_bytes=24000)
    assert diff_page["complete"] is True
    _listed_progress(home, snapshot_id=manifest["snapshot_id"])

    rows = _progress_rows(home, manifest["snapshot_id"])
    assert len(rows) == 1
    assert rows[0][1] == 1
    assert json.loads(rows[0][2])["state"] == "delivered"
    units = _all_units(home)
    assert units["src/app.py"]["state"] == "delivered"
    assert units["src/new.py"]["state"] == "unchecked"


def test_checked_without_evidence_handles_refused(tmp_path):
    _mirror, manifest, home = _setup(tmp_path)

    for evidence in (None, []):
        with pytest.raises(MrReviewError) as excinfo:
            _mark(home, "src/app.py", "checked", evidence=evidence)
        assert excinfo.value.code == "invalid-schema"
        assert "evidence" in str(excinfo.value)

    for bad in (["I read the diff carefully"], ["blob:" + "z" * 40],
                ["snapshot:" + "z" * 64], ["probe:env-1:not-a-probe-id"],
                ["cursor:not-a-cursor"], [123]):
        with pytest.raises(MrReviewError) as excinfo:
            _mark(home, "src/app.py", "checked", evidence=bad)
        assert excinfo.value.code in ("invalid-schema", "review-object-missing")

    foreign = "cursor:" + encode_cursor(
        "f" * 64, {"repo": "phonyx", "iid": 30, "view": "inventory"},
        {"e": 1, "g": 0})
    with pytest.raises(MrReviewError) as excinfo:
        _mark(home, "src/app.py", "checked", evidence=[foreign])
    assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        _mark(home, "src/app.py", "checked", evidence=["blob:" + "a" * 40])
    assert excinfo.value.code == "review-object-missing"

    assert _progress_rows(home, manifest["snapshot_id"]) == []

    store = MrReviewStore(GITLAB_URL, 42, "phonyx", home=home)
    probe_dir = store.root / "probes" / "env-1"
    probe_dir.mkdir(parents=True)
    probe_id = "a" * 32
    (probe_dir / f"{probe_id}.stdout.log").write_bytes(b"probe output\n")
    _mark(home, "src/app.py", "checked", evidence=[f"probe:env-1:{probe_id}"])
    units = _all_units(home)
    assert units["src/app.py"]["state"] == "checked"
    assert units["src/app.py"]["evidence"] == [f"probe:env-1:{probe_id}"]


def test_mark_input_validation(tmp_path):
    _mirror, _manifest, home = _setup(tmp_path)

    cases = [
        ("bogus", {}),
        ("deferred", {}),
        ("not-applicable", {}),
        ("deferred", {"reason": ""}),
        ("delivered", {"reason": 5}),
    ]
    for state, extra in cases:
        with pytest.raises(MrReviewError) as excinfo:
            _mark(home, "src/app.py", state, **extra)
        assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        _mark(home, "src/ghost.py", "delivered")
    assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        _mark(home, "src/app.py", "delivered", snapshot_id="f" * 64)
    assert excinfo.value.code == "review-object-missing"
    with pytest.raises(MrReviewError) as excinfo:
        _mark(home, "src/app.py", "delivered", snapshot_id="ZZZ")
    assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_progress("phonyx", 30, action="mark",
                           text=json.dumps({"path": "src/app.py", "state": "delivered",
                                            "bogus": 1}), home=home)
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_progress("phonyx", 30, action="bogus", text="{}", home=home)
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_progress("phonyx", 30, action="mark", home=home)
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_progress("", 30, action="list", home=home)
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_progress("phonyx", 0, action="list", home=home)
    assert excinfo.value.code == "invalid-schema"

    for bad in ({"cursor": -1}, {"cursor": "5"}, {"limit": 0}, {"limit": True},
                {"bogus": 1}):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_progress("phonyx", 30, action="list", text=json.dumps(bad),
                               home=home)
        assert excinfo.value.code == "invalid-schema"

    deferred = _mark(home, "src/new.py", "deferred", reason="generated output")
    assert deferred["revision"] == 1
    units = _all_units(home)
    assert units["src/new.py"]["state"] == "deferred"
    assert units["src/new.py"]["reason"] == "generated output"


def test_mark_cas_conflict_is_typed(tmp_path):
    _mirror, manifest, home = _setup(tmp_path)
    app_oid = _unit_oid(home, manifest["snapshot_id"], "src/app.py")
    _mark(home, "src/app.py", "delivered")

    with pytest.raises(MrReviewError) as excinfo:
        _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"],
              expected_revision=0)
    assert excinfo.value.code == "revision-conflict"
    with pytest.raises(MrReviewError) as excinfo:
        _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"],
              expected_revision=5)
    assert excinfo.value.code == "revision-conflict"

    outcomes = []
    barrier = threading.Barrier(2)

    def run():
        barrier.wait()
        try:
            _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"],
                  expected_revision=1)
            outcomes.append("ok")
        except MrReviewError as exc:
            outcomes.append(exc.code)

    threads = [threading.Thread(target=run) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert sorted(outcomes) == ["ok", "revision-conflict"]

    rows = _progress_rows(home, manifest["snapshot_id"])
    assert rows[0][1] == 2
    assert json.loads(rows[0][2])["state"] == "checked"


def test_progress_survives_reopen_by_another_process(tmp_path):
    _mirror, manifest, home = _setup(tmp_path)
    app_oid = _unit_oid(home, manifest["snapshot_id"], "src/app.py")
    _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"])
    _mark(home, "src/new.py", "delivered")

    reopened = MrReviewStore(GITLAB_URL, 42, "phonyx", home=home)
    conn = sqlite3.connect(reopened.db_path)
    try:
        rows = conn.execute(
            "SELECT path, revision, payload FROM progress ORDER BY path").fetchall()
    finally:
        conn.close()
    assert [row[0] for row in rows] == ["src/app.py", "src/new.py"]
    assert [row[1] for row in rows] == [1, 1]
    assert json.loads(rows[0][2])["evidence"] == [f"blob:{app_oid}"]

    code = (
        "import json, sys\n"
        f"sys.path.insert(0, {str(REPO_ROOT)!r})\n"
        "from lgm_core.mr_review_progress import mr_review_progress\n"
        f"result = mr_review_progress('phonyx', 30, action='list', home={str(home)!r})\n"
        "print(json.dumps(result['totals']))\n"
    )
    proc = subprocess.run([sys.executable, "-c", code], capture_output=True,
                          text=True, timeout=120, cwd=str(REPO_ROOT), check=False)
    assert proc.returncode == 0, proc.stderr
    totals = json.loads(proc.stdout)
    assert totals == {"units": 2, "delivered": 1, "checked": 1, "deferred": 0,
                      "not-applicable": 0, "unchecked": 0, "not_in_inventory": 0}


def test_refresh_discussion_only_keeps_coverage_and_findings(tmp_path):
    _mirror, manifest_a, home = _setup(tmp_path)
    app_oid = _unit_oid(home, manifest_a["snapshot_id"], "src/app.py")
    _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"])
    _mark(home, "src/new.py", "deferred", reason="generated output")
    finding = _finding(manifest_a, [_rev(manifest_a, 1, verification="confirmed")])
    _save(home, finding)

    manifest_b = _variant(
        manifest_a,
        target_branch="release-branch",
        title="Review workspace (discussed)",
        mr_updated_at="2026-10-30T12:01:00Z",
        discussions_fetched_at="2026-10-30T12:01:00Z",
        discussions=[_discussion("d1", 11, "looks good")])
    assert manifest_b["code_id"] == manifest_a["code_id"]
    assert manifest_b["version_fingerprint"] != manifest_a["version_fingerprint"]
    assert manifest_b["snapshot_id"] != manifest_a["snapshot_id"]
    import_snapshot(manifest_b, None, home=home)

    result = mr_review_refresh("phonyx", 30, home=home)
    assert result["status"] == "refreshed"
    assert result["code_changed"] is False
    assert result["metadata"]["version_fingerprint_changed"] is True
    assert result["metadata"]["changed_fields"] == ["target_branch"]
    assert result["progress"] == {"carried": 2, "fallback_delivered": 0,
                                  "skipped_existing": 0, "dropped_absent": 0,
                                  "total_old_rows": 2}
    assert result["findings"] == {"invalidated": 0}

    units = _all_units(home)
    assert units["src/app.py"]["state"] == "checked"
    assert units["src/app.py"]["evidence"] == [f"blob:{app_oid}"]
    assert units["src/app.py"]["carryover"]["from_snapshot_id"] == manifest_a["snapshot_id"]
    assert units["src/app.py"]["carryover"]["state"] == "checked"
    assert units["src/new.py"]["state"] == "deferred"
    assert units["src/new.py"]["reason"] == "generated output"

    doc = _listed(home, finding["finding_id"])
    assert len(doc["revisions"]) == 1
    assert doc["revisions"][0]["applicability"] == "current"

    old_units = _all_units(home, snapshot_id=manifest_a["snapshot_id"])
    assert old_units["src/app.py"]["state"] == "checked"
    assert old_units["src/app.py"]["carryover"] is None


def test_refresh_code_change_carries_only_oid_identical_units(tmp_path):
    mirror, manifest_a, home = _setup(tmp_path)
    app_oid = _unit_oid(home, manifest_a["snapshot_id"], "src/app.py")
    new_oid = _unit_oid(home, manifest_a["snapshot_id"], "src/new.py")
    _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"])
    _mark(home, "src/new.py", "checked", evidence=[f"blob:{new_oid}"])
    anchor = {"old_path": None, "new_path": "src/new.py", "side": "new", "line": 1}
    finding = _finding(manifest_a, [_rev(manifest_a, 1, verification="confirmed",
                                         anchor=anchor)])
    _save(home, finding)

    _git(mirror, "checkout", "-q", "feature")
    (mirror / "src" / "new.py").write_text("x = 2\n", encoding="utf-8")
    _git(mirror, "add", "src/new.py")
    _git(mirror, "commit", "-qm", "change new.py")
    _git(mirror, "checkout", "-q", "master")
    manifest_b = _manifest(mirror)
    bundle_b = tmp_path / "code-b.bundle"
    _git(mirror, "bundle", "create", str(bundle_b), "feature", "master")
    import_snapshot(manifest_b, bundle_b, home=home)
    assert manifest_b["code_id"] != manifest_a["code_id"]

    result = mr_review_refresh("phonyx", 30, home=home)
    assert result["code_changed"] is True
    assert result["metadata"]["changed_fields"] == ["diff_refs.head_sha", "source_sha"]
    assert result["progress"] == {"carried": 1, "fallback_delivered": 1,
                                  "skipped_existing": 0, "dropped_absent": 0,
                                  "total_old_rows": 2}
    assert result["findings"] == {"invalidated": 1}

    units = _all_units(home)
    assert units["src/app.py"]["state"] == "checked"
    assert units["src/app.py"]["evidence"] == [f"blob:{app_oid}"]
    assert units["src/app.py"]["carryover"]["from_snapshot_id"] == manifest_a["snapshot_id"]
    new_unit = units["src/new.py"]
    assert new_unit["state"] == "delivered"
    assert "blob OID changed" in new_unit["reason"]
    assert "checked" in new_unit["reason"]
    assert new_unit["carryover"]["state"] == "checked"
    assert new_unit["revision"] == 1

    doc = _listed(home, finding["finding_id"])
    assert doc["finding_id"] == finding["finding_id"]
    rev1, rev2 = doc["revisions"]
    assert rev1 == _rev(manifest_a, 1, verification="confirmed", anchor=anchor)
    assert rev2["revision"] == 2
    assert rev2["applicability"] == "needs-recheck"
    assert rev2["snapshot_id"] == manifest_a["snapshot_id"]
    assert rev2["code_id"] == manifest_a["code_id"]
    assert rev2["version_fingerprint"] == manifest_a["version_fingerprint"]
    assert rev2["trigger"].startswith("snapshot refresh: code_id changed")
    assert rev2["evidence"] == [f"snapshot:{manifest_a['snapshot_id']}",
                                f"snapshot:{manifest_b['snapshot_id']}"]
    assert rev2["comment"] == rev1["comment"]
    assert rev2["verification"] == rev1["verification"]
    assert rev2["delivery"] == rev1["delivery"]
    m.validate_finding(doc)


def test_refresh_drops_units_absent_from_new_inventory(tmp_path):
    mirror, manifest_a, home = _setup(tmp_path)
    new_oid = _unit_oid(home, manifest_a["snapshot_id"], "src/new.py")
    _mark(home, "src/new.py", "checked", evidence=[f"blob:{new_oid}"])

    _git(mirror, "checkout", "-q", "feature")
    (mirror / "src" / "new.py").unlink()
    _git(mirror, "add", "src/new.py")
    _git(mirror, "commit", "-qm", "drop new.py")
    _git(mirror, "checkout", "-q", "master")
    manifest_b = _manifest(mirror)
    bundle_b = tmp_path / "code-b.bundle"
    _git(mirror, "bundle", "create", str(bundle_b), "feature", "master")
    import_snapshot(manifest_b, bundle_b, home=home)

    result = mr_review_refresh("phonyx", 30, home=home)
    assert result["progress"] == {"carried": 0, "fallback_delivered": 0,
                                  "skipped_existing": 0, "dropped_absent": 1,
                                  "total_old_rows": 1}
    units = _all_units(home)
    assert set(units) == {"src/app.py"}
    assert units["src/app.py"]["state"] == "unchecked"
    old_units = _all_units(home, snapshot_id=manifest_a["snapshot_id"])
    assert old_units["src/new.py"]["state"] == "checked"


def test_source_same_target_moved_invalidates_findings(tmp_path):
    mirror, manifest_a, home = _setup(tmp_path)
    app_oid = _unit_oid(home, manifest_a["snapshot_id"], "src/app.py")
    _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"])
    finding = _finding(manifest_a, [_rev(manifest_a, 1, verification="confirmed")])
    _save(home, finding)

    _git(mirror, "checkout", "-q", "master")
    (mirror / "README.md").write_text("target moved on\n", encoding="utf-8")
    _git(mirror, "add", ".")
    _git(mirror, "commit", "-qm", "target advances")
    manifest_b = _manifest(mirror)
    bundle_b = tmp_path / "code-b.bundle"
    _git(mirror, "bundle", "create", str(bundle_b), "feature", "master")
    import_snapshot(manifest_b, bundle_b, home=home)
    assert manifest_b["source_sha"] == manifest_a["source_sha"]
    assert manifest_b["target_tip_sha"] != manifest_a["target_tip_sha"]
    assert manifest_b["code_id"] != manifest_a["code_id"]

    result = mr_review_refresh("phonyx", 30, home=home)
    assert result["code_changed"] is True
    assert result["metadata"]["changed_fields"] == ["diff_refs.start_sha", "target_tip_sha"]
    assert result["findings"] == {"invalidated": 1}
    assert result["progress"]["carried"] == 1
    assert result["progress"]["fallback_delivered"] == 0

    doc = _listed(home, finding["finding_id"])
    assert doc["revisions"][1]["applicability"] == "needs-recheck"
    units = _all_units(home)
    assert units["src/app.py"]["state"] == "checked"


def test_refresh_skips_non_current_and_revalidated_findings(tmp_path):
    mirror, manifest_a, home = _setup(tmp_path)
    stale = _finding(manifest_a, [_rev(manifest_a, 1, applicability="needs-recheck")])
    _save(home, stale)

    _git(mirror, "checkout", "-q", "feature")
    (mirror / "src" / "new.py").write_text("x = 2\n", encoding="utf-8")
    _git(mirror, "add", ".")
    _git(mirror, "commit", "-qm", "change new.py")
    _git(mirror, "checkout", "-q", "master")
    manifest_b = _manifest(mirror)
    bundle_b = tmp_path / "code-b.bundle"
    _git(mirror, "bundle", "create", str(bundle_b), "feature", "master")
    import_snapshot(manifest_b, bundle_b, home=home)

    revalidated = _finding(manifest_b, [_rev(manifest_b, 1, verification="confirmed")])
    _save(home, revalidated)

    result = mr_review_refresh("phonyx", 30, home=home)
    assert result["findings"] == {"invalidated": 0}

    assert len(_listed(home, stale["finding_id"])["revisions"]) == 1
    assert len(_listed(home, revalidated["finding_id"])["revisions"]) == 1


def test_old_snapshot_reads_and_marks_do_not_change_current(tmp_path):
    _mirror, manifest_a, home = _setup(tmp_path)
    app_oid = _unit_oid(home, manifest_a["snapshot_id"], "src/app.py")
    _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"])
    manifest_b = _variant(manifest_a, discussions=[_discussion("d1", 11, "note")])
    import_snapshot(manifest_b, None, home=home)
    mr_review_refresh("phonyx", 30, home=home)

    old_page = _listed_progress(home, snapshot_id=manifest_a["snapshot_id"])
    assert old_page["snapshot_id"] == manifest_a["snapshot_id"]
    assert old_page["snapshot"]["is_current"] is False
    assert old_page["units"][0]["state"] == "checked"

    diff_query = {"repo": "phonyx", "snapshot_id": manifest_a["snapshot_id"],
                  "view": "diff", "paths": [], "group": "", "context_lines": 20}
    old_cursor = encode_cursor(manifest_a["snapshot_id"], diff_query,
                               {"f": 0, "b": 0, "ol": None, "nl": None, "ls": 0})
    reader = open_reader(home, "phonyx", manifest_a["snapshot_id"])
    page = reader.read_page(view="diff", cursor=old_cursor, budget_bytes=24000)
    assert page["snapshot_id"] == manifest_a["snapshot_id"]

    _mark(home, "src/new.py", "delivered", snapshot_id=manifest_a["snapshot_id"])

    store = MrReviewStore(GITLAB_URL, 42, "phonyx", home=home)
    assert store.current_snapshot_id(30) == manifest_b["snapshot_id"]


def test_list_is_bounded(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    _git(mirror, "checkout", "-q", "feature")
    for i in range(55):
        (mirror / "src" / f"f{i:03d}.py").write_text(f"x = {i}\n", encoding="utf-8")
    _git(mirror, "add", "src")
    _git(mirror, "commit", "-qm", "many files")
    _git(mirror, "checkout", "-q", "master")
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    home = tmp_path / "reviews"
    import_snapshot(manifest, bundle, home=home)

    page1 = _listed_progress(home)
    assert len(page1["units"]) == LIST_PAGE_MAX
    assert page1["total"] == 57
    assert page1["next_cursor"] == LIST_PAGE_MAX
    paths1 = [entry["path"] for entry in page1["units"]]
    assert paths1 == sorted(paths1)

    page2 = _listed_progress(home, cursor=LIST_PAGE_MAX)
    assert len(page2["units"]) == 7
    assert page2["next_cursor"] is None
    assert not set(paths1) & {entry["path"] for entry in page2["units"]}

    assert len(_listed_progress(home, limit=10)["units"]) == 10
    assert _listed_progress(home, limit=10)["next_cursor"] == 10
    assert len(_listed_progress(home, limit=999)["units"]) == LIST_PAGE_MAX

    empty = mr_review_progress("phonyx", 30, action="list", home=home)
    assert empty["total"] == 57
    assert len(empty["units"]) == LIST_PAGE_MAX


def test_refresh_without_previous_and_idempotent_rerun(tmp_path):
    _mirror, manifest_a, home = _setup(tmp_path)
    result = mr_review_refresh("phonyx", 30, home=home)
    assert result["status"] == "no-previous"

    app_oid = _unit_oid(home, manifest_a["snapshot_id"], "src/app.py")
    _mark(home, "src/app.py", "checked", evidence=[f"blob:{app_oid}"])
    manifest_b = _variant(manifest_a, discussions=[_discussion("d1", 11, "note")])
    import_snapshot(manifest_b, None, home=home)

    first = mr_review_refresh("phonyx", 30, home=home)
    assert first["progress"]["carried"] == 1
    second = mr_review_refresh("phonyx", 30, home=home,
                               previous_snapshot_id=manifest_a["snapshot_id"])
    assert second["progress"] == {"carried": 0, "fallback_delivered": 0,
                                  "skipped_existing": 1, "dropped_absent": 0,
                                  "total_old_rows": 1}
    rows = _progress_rows(home, manifest_b["snapshot_id"])
    assert len(rows) == 1
    assert rows[0][1] == 1

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_refresh("phonyx", 30, home=home,
                          previous_snapshot_id=manifest_b["snapshot_id"])
    assert excinfo.value.code == "invalid-schema"
