import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.render import render

_SNAP = "a" * 64
_CODE = "b" * 64


def _review(status, *, limitations=None, error=""):
    return {"status": status, "snapshot_id": _SNAP, "code_id": _CODE,
            "limitations": list(limitations or []), "error": error}


def _snapshot(iid, status, *, limitations=None, error=""):
    entry = {"iid": iid, "status": status, "snapshot_id": _SNAP,
             "code_id": _CODE, "bundle_action": "uploaded",
             "manifest_path": f"mr-review/snapshots/{_SNAP}.json",
             "limitations": list(limitations or [])}
    if error:
        entry["error"] = error
    return entry


def _payload(*, mrs, review_snapshots, summary, dry_run=False, notes_only=False,
             blocked=None):
    return {
        "success": True, "dry_run": dry_run, "notes_only": notes_only,
        "repos": [{
            "repo": "phonyx", "gitlab": "kryptonit/phonyx",
            "open_mrs": len(mrs), "mrs": mrs,
            "branches_sent": [], "branches_skipped": [],
            "branch_warnings": [], "blocked": list(blocked or []),
            "closed": [], "review_snapshots": review_snapshots,
        }],
        "summary": summary,
    }


def _summary(status, *, complete, **over):
    base = {"repos": 1, "mrs": 1, "branches_sent": 0, "branches_skipped": 0,
            "branch_warnings": 0, "notes_uploaded": 0, "notes_unchanged": 0,
            "closed_marked": 0, "review_prepared": 0, "review_skipped": 0,
            "review_failed": 0, "review_complete": complete,
            "review_status": status, "failures": [], "errors": []}
    base.update(over)
    return base


def test_ready_summary_names_snapshot_preparation_not_review_completed():
    mrs = [{"iid": 24, "title": "Llm-core", "branch": "llm-core",
            "branch_transfer": "sent", "review": _review("prepared"),
            "notes": "uploaded"}]
    out = render("mr_sync_all", _payload(
        mrs=mrs, review_snapshots=[_snapshot(24, "prepared")],
        summary=_summary("ready", complete=True, review_prepared=1)))
    assert "snapshot preparation: ready" in out
    assert "review completed" not in out.lower()
    assert "branch_transfer=sent" in out
    assert "snapshot=prepared" in out
    assert "llm-core" in out


def test_blocked_branch_and_prepared_snapshot_both_surface():
    mrs = [{"iid": 24, "title": "Llm-core", "branch": "llm-core",
            "branch_transfer": "blocked", "review": _review("prepared"),
            "notes": "uploaded"}]
    out = render("mr_sync_all", _payload(
        mrs=mrs, review_snapshots=[_snapshot(24, "prepared")],
        blocked=[{"iid": 24, "branch": "llm-core",
                  "reason": "diverged from origin"}],
        summary=_summary("ready", complete=True, review_prepared=1)))
    assert "branch_transfer=blocked" in out
    assert "snapshot=prepared" in out
    assert "[blocked] !24 llm-core" in out
    assert "snapshot preparation: ready" in out


def test_dry_run_reports_not_run():
    mrs = [{"iid": 24, "title": "Llm-core", "branch": "llm-core",
            "branch_transfer": "dry-run", "review": _review("not-run"),
            "notes": "dry-run"}]
    out = render("mr_sync_all", _payload(
        mrs=mrs, review_snapshots=[], dry_run=True,
        summary=_summary("not-run", complete=False)))
    assert "snapshot preparation: not-run" in out
    assert "branch_transfer=dry-run" in out
    assert "snapshot=not-run" in out


def test_failed_snapshot_surfaces_error_and_incomplete():
    mrs = [{"iid": 24, "title": "Llm-core", "branch": "llm-core",
            "branch_transfer": "sent",
            "review": _review("failed", error="GitLab unreachable"),
            "notes": "uploaded"}]
    out = render("mr_sync_all", _payload(
        mrs=mrs,
        review_snapshots=[_snapshot(24, "failed", error="GitLab unreachable")],
        summary=_summary("incomplete", complete=False, review_failed=1)))
    assert "snapshot=failed" in out
    assert "GitLab unreachable" in out
    assert "snapshot preparation: incomplete" in out


def test_empty_reports_empty():
    out = render("mr_sync_all", _payload(
        mrs=[], review_snapshots=[],
        summary=_summary("empty", complete=False, mrs=0)))
    assert "snapshot preparation: empty" in out


def test_ready_with_limitations_surfaces_limitations():
    lim = "code artifact approaches postbox expiry and cannot be refreshed in a notes-only run"
    mrs = [{"iid": 24, "title": "Llm-core", "branch": "llm-core",
            "branch_transfer": "sent",
            "review": _review("prepared", limitations=[lim]),
            "notes": "uploaded"}]
    out = render("mr_sync_all", _payload(
        mrs=mrs, review_snapshots=[_snapshot(24, "prepared", limitations=[lim])],
        summary=_summary("ready", complete=True, review_prepared=1)))
    assert "snapshot=prepared" in out
    assert "[limitation] code artifact" in out
    assert "snapshot preparation: ready" in out


def test_legacy_payload_without_readiness_does_not_invent_ready():
    mrs = [{"iid": 41, "title": "Refactor sync", "branch": "feature/sync",
            "notes": "uploaded"}]
    payload = _payload(
        mrs=mrs, review_snapshots=[],
        summary={"repos": 1, "mrs": 1, "branches_sent": 1,
                 "branches_skipped": 0, "branch_warnings": 0,
                 "notes_uploaded": 1, "notes_unchanged": 0,
                 "closed_marked": 0, "errors": []})
    out = render("mr_sync_all", payload)
    assert "snapshot preparation" not in out
    assert "ready" not in out.lower()


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
