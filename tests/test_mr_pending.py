"""mr_pending: one pipeline view per MR iid — notes freshness, replies
pending in the postbox, the last publish outcome and the closed state with
evidence. Also pins the file_list postbox items carrying mtime/expires_at."""
import sys
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import ops_mr
from lgm_core.client import LgmError
from lgm_core.op_models import Ctx
from lgm_core.ops import op_file_list, op_mr_pending
from lgm_core.render import render

_NOW = 1_800_000_000

_STATUS_MD = ("<!-- lgm-replies-status v1 -->\n"
              "# MR !24 — status\n"
              "- posted: 19\n- duplicates: 0\n- skipped: 0\n- failed: 0\n"
              "- not anchored: 7\n- at: 2026-10-19 23:45\n"
              "  - reply 3: B.java:99 — file not in MR diff\n")


class _PendingClient:
    """Postbox items ({id, path, mtime, content}); GitLab answers only when
    mr_state is given, otherwise gitlab_get_mr raises the given error."""

    def __init__(self, items=(), mr_state=None, gitlab_error="default"):
        self.sync_password = ""
        self.projects_seen = []
        self._items = list(items)
        self._mr_state = mr_state or {}
        # GitLab answers iff mr_state is populated; otherwise the home-side refusal.
        if gitlab_error == "default":
            self._gitlab_error = (LgmError("config", "GitLab not configured")
                                  if not self._mr_state else None)
        else:
            self._gitlab_error = gitlab_error

    def file_sync_list(self, repo):
        return {"items": [{"id": i["id"], "path": i["path"], "mtime": i["mtime"]}
                          for i in self._items]}

    def file_sync_fetch(self, repo, item_id):
        return next(i["content"].encode("utf-8") for i in self._items
                    if i["id"] == item_id)

    def gitlab_get_mr(self, iid, project=""):
        if self._gitlab_error is not None:
            raise self._gitlab_error
        self.projects_seen.append(project)
        return {"iid": iid, "state": self._mr_state[iid]}


def _ctx(client):
    return Ctx(config=SimpleNamespace(sync_password=""), client=client)


@pytest.fixture
def frozen_now(monkeypatch):
    monkeypatch.setattr(ops_mr, "time", SimpleNamespace(time=lambda: float(_NOW)))


def _note(iid, mtime, content="# MR"):
    return {"id": f"n{iid}", "path": f"mr-notes/mr-!{iid}.md", "mtime": mtime,
            "content": content}


def _marker(iid, mtime):
    return {"id": f"c{iid}", "path": f"mr-notes/mr-!{iid}.closed.md",
            "mtime": mtime, "content": "closed"}


def _reply(iid, mtime, item_id):
    return {"id": item_id, "path": f"mr-replies/mr-!{iid}.md", "mtime": mtime,
            "content": "## new\nx"}


def _status(iid, mtime, content=_STATUS_MD, item_id=None):
    return {"id": item_id or f"s{iid}",
            "path": f"mr-replies-status/mr-!{iid}.md", "mtime": mtime,
            "content": content}


def test_mr_pending_reports_notes_mtime_and_age(frozen_now):
    client = _PendingClient([_note(24, _NOW - 3600)])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = res["items"][0]
    assert it["iid"] == 24
    assert it["notes"] == {"mtime": _NOW - 3600, "age_seconds": 3600,
                           "updated_at": None, "updated_age_seconds": None,
                           "lgm_markers": 0}


def test_mr_pending_counts_distinct_published_markers(frozen_now):
    content = ("# MR !24 - t\n"
               "- **Обновлено:** 2026-10-20T21:39:40.928Z · **Источник:** GitLab MR !24\n"
               "<!-- lgm:19feffae -->\n<!-- lgm:3a9cad9f -->\n<!-- lgm:19feffae -->\n")
    client = _PendingClient([_note(24, _NOW - 10, content=content)])
    notes = op_mr_pending(_ctx(client), {"repo": "r1"})["items"][0]["notes"]
    assert notes["lgm_markers"] == 2


_NOTES_WITH_STAMP = ("# MR !50 - title\n"
                     "- **Ветка:** `agent_mode`\n"
                     "- **Обновлено:** 2026-10-19T16:51:02.072Z · "
                     "**Источник:** GitLab MR !50\n")


def test_mr_pending_reports_last_activity_from_the_notes_header(frozen_now):
    client = _PendingClient([_note(50, _NOW - 49186, content=_NOTES_WITH_STAMP)])
    notes = op_mr_pending(_ctx(client), {"repo": "r1"})["items"][0]["notes"]
    expected = int(_NOW - datetime.fromisoformat(
        "2026-10-19T16:51:02.072+00:00").timestamp())
    assert notes["updated_at"] == "2026-10-19T16:51:02.072Z"
    assert notes["updated_age_seconds"] == expected
    assert notes["age_seconds"] == 49186


class _BrokenFetchClient(_PendingClient):
    def file_sync_fetch(self, repo, item_id):
        raise LgmError("network", "unreachable")


def test_mr_pending_unreadable_notes_keep_the_file_age(frozen_now):
    client = _BrokenFetchClient([_note(24, _NOW - 60)])
    notes = op_mr_pending(_ctx(client), {"repo": "r1"})["items"][0]["notes"]
    assert notes["updated_at"] is None
    assert notes["updated_error"] == "fetch/decrypt failed (v3 relay)"
    assert notes["age_seconds"] == 60


def test_render_mr_pending_shows_last_activity_before_the_file_age():
    payload = {"success": True, "repo": "r1", "count": 1, "items": [
        {"iid": 50,
         "notes": {"mtime": 1792490363, "age_seconds": 49186,
                   "updated_at": "2026-10-19T16:51:02.072Z",
                   "updated_age_seconds": 123456},
         "replies_pending": False, "reply_ids": [], "last_publish": None,
         "state": "open", "state_evidence": "note-mtime"},
    ]}
    out = render("mr_pending", payload)
    assert "updated_at 2026-10-19T16:51:02.072Z (123456s ago)" in out
    assert "file 49186s old" in out


def test_render_mr_pending_reports_published_reply_visibility():
    def item(iid, posted, markers):
        return {"iid": iid,
                "notes": {"mtime": 100, "age_seconds": 5,
                          "updated_at": None, "updated_age_seconds": None,
                          "lgm_markers": markers},
                "replies_pending": False, "reply_ids": [],
                "last_publish": {"posted": posted, "duplicates": 0,
                                 "skipped": 0, "failed": 0, "not_anchored": 0,
                                 "at": "2026-10-21 02:11", "details": [],
                                 "parsed": True},
                "state": "open", "state_evidence": "note-mtime"}

    out = render("mr_pending", {"success": True, "repo": "r1", "count": 2,
                                "items": [item(24, 19, 19), item(27, 7, 0)]})
    assert "notes carry 19 lgm markers (last publish posted=19)" in out
    assert "notes carry 0 lgm markers (last publish posted=7)" in out
    assert "notes predate the push, re-sync to verify" in out
    assert out.count("notes predate the push") == 1


def test_mr_pending_asks_gitlab_in_the_registry_project(frozen_now, monkeypatch):
    from lgm_core import ops_projects
    monkeypatch.setattr(ops_projects, "_load_registry",
                        lambda: {"projects": [{"repo": "r1", "gitlab": "group/proj"}]})
    client = _PendingClient([_note(24, 100)], mr_state={24: "opened"})

    res = op_mr_pending(_ctx(client), {"repo": "r1"})

    assert res["items"][0]["state_evidence"] == "gitlab"
    assert client.projects_seen == ["group/proj"]


def test_mr_pending_lists_pending_reply_ids(frozen_now):
    client = _PendingClient([
        _note(24, 100),
        _reply(24, 200, "r24a"),
        _reply(24, 300, "r24b"),
    ])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = res["items"][0]
    assert it["replies_pending"] is True
    assert it["reply_ids"] == ["r24a", "r24b"]


def test_mr_pending_no_pending_replies_is_false_with_empty_ids(frozen_now):
    client = _PendingClient([_note(24, 100)])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = res["items"][0]
    assert it["replies_pending"] is False
    assert it["reply_ids"] == []


def test_mr_pending_last_publish_from_newest_status(frozen_now):
    client = _PendingClient([
        _status(24, 5, content="<!-- lgm-replies-status v1 -->\n- posted: 4\n",
                item_id="s-old"),
        _status(24, 9, item_id="s-new"),
    ])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    lp = res["items"][0]["last_publish"]
    assert lp["parsed"] is True
    assert lp["posted"] == 19 and lp["failed"] == 0
    assert lp["not_anchored"] == 7
    assert lp["at"] == "2026-10-19 23:45"
    assert lp["details"] == ["reply 3: B.java:99 — file not in MR diff"]


def test_mr_pending_last_publish_none_without_status(frozen_now):
    client = _PendingClient([_note(24, 100)])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    assert res["items"][0]["last_publish"] is None


def test_mr_pending_state_closed_when_marker_wins(frozen_now):
    client = _PendingClient([_note(9, 100), _marker(9, 200)])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = res["items"][0]
    assert it["state"] == "closed"
    assert it["state_evidence"] == "marker"


def test_mr_pending_state_open_when_note_newer_than_marker(frozen_now):
    client = _PendingClient([_note(9, 200), _marker(9, 100)])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = res["items"][0]
    assert it["state"] == "open"
    assert it["state_evidence"] == "note-mtime"


def test_mr_pending_state_open_with_note_and_no_marker(frozen_now):
    client = _PendingClient([_note(9, 100)])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = res["items"][0]
    assert it["state"] == "open"
    assert it["state_evidence"] == "note-mtime"


def test_mr_pending_state_unknown_expired_without_notes_or_marker(frozen_now):
    client = _PendingClient([_reply(27, 100, "r27")])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = res["items"][0]
    assert it["state"] == "unknown"
    assert it["state_evidence"] == "expired"
    assert it["notes"] is None


def test_mr_pending_state_from_gitlab_when_reachable(frozen_now):
    client = _PendingClient(
        [_note(24, 100), _note(25, 100)],
        mr_state={24: "opened", 25: "merged"})
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    by_iid = {it["iid"]: it for it in res["items"]}
    assert by_iid[24]["state"] == "open"
    assert by_iid[24]["state_evidence"] == "gitlab"
    assert by_iid[25]["state"] == "closed"
    assert by_iid[25]["state_evidence"] == "gitlab"


def test_mr_pending_gitlab_network_error_falls_back_to_postbox(frozen_now):
    client = _PendingClient([_note(9, 100), _marker(9, 200)],
                            gitlab_error=LgmError("network", "unreachable"))
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    it = res["items"][0]
    assert it["state"] == "closed"
    assert it["state_evidence"] == "marker"


def test_mr_pending_covers_union_of_postbox_iids_sorted(frozen_now):
    client = _PendingClient([
        _note(3, 100), _reply(7, 100, "r7"), _status(5, 100),
    ])
    res = op_mr_pending(_ctx(client), {"repo": "r1"})
    assert [it["iid"] for it in res["items"]] == [3, 5, 7]
    assert res["count"] == 3
    assert res["success"] is True
    assert res["repo"] == "r1"


def test_mr_pending_requires_repo():
    with pytest.raises(LgmError) as ei:
        op_mr_pending(_ctx(_PendingClient()), {})
    assert "--repo" in ei.value.message


def test_render_mr_pending_pipeline_lines():
    payload = {"success": True, "repo": "r1", "count": 2, "items": [
        {"iid": 24, "notes": {"mtime": 1799996400, "age_seconds": 3600},
         "replies_pending": False, "reply_ids": [],
         "last_publish": {"posted": 19, "duplicates": 0, "skipped": 0,
                          "failed": 0, "not_anchored": 7,
                          "at": "2026-10-19 23:45", "details": [],
                          "parsed": True},
         "state": "open", "state_evidence": "note-mtime"},
        {"iid": 27, "notes": None, "replies_pending": True,
         "reply_ids": ["r27"], "last_publish": None,
         "state": "unknown", "state_evidence": "expired"},
    ]}
    out = render("mr_pending", payload)
    assert "state=open (note-mtime)" in out
    assert "state=unknown (expired)" in out
    assert "3600s old" in out
    assert "replies pending (r27)" in out
    assert "posted=19" in out
    assert "never published" in out


# ── file_list: postbox items carry mtime and expires_at ─────────────────────

def test_file_list_items_carry_mtime_and_expires_at():
    ctx = Ctx(config=SimpleNamespace(sync_password=""),
              client=SimpleNamespace(file_sync_list=lambda repo: {"items": [
                  {"id": "a1", "path": "mr-notes/mr-!24.md", "plain_size": 42,
                   "mtime": 1000},
                  {"id": "a2", "path": "logs/other.bin", "size": 7,
                   "mtime": 2000},
              ]}))
    res = op_file_list(ctx, {"repo": "r1"})
    assert res["files"][0]["mtime"] == 1000
    assert res["files"][0]["expires_at"] == 1000 + 7 * 24 * 3600
    assert res["files"][1]["mtime"] == 2000
    assert res["files"][1]["expires_at"] == 2000 + 7 * 24 * 3600


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
