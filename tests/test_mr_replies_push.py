"""mr_replies_push: publish postbox reply files to GitLab from the CLI.

Mirrors the plugin's MrReplyPushService: exactly-once markers, thread
resolve flags, anchored discussions with a general-note fallback, the
mr-replies-status report and the postbox ack contract.
"""
import hashlib
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import ops_mr
from lgm_core.client import LgmError
from lgm_core.op_models import Ctx
from lgm_core.ops import op_mr_replies_push, op_mr_replies_status
from lgm_core.render import render


def _marker(iid: int, key: str, body: str) -> str:
    digest = hashlib.sha1(f"{iid}|{key}|{body}".encode("utf-8")).digest()
    return f"<!-- lgm:{digest[:4].hex()} -->"


_MD = "\n".join([
    "# MR !7 — answers",
    "",
    "## thread abc123",
    "resolve: yes",
    "fixed in 1234abc",
    "",
    "## new A.java:12",
    "wrong order of args",
    "",
    "## new B.java:99",
    "not in diff",
    "",
    "## new",
    "general remark",
])


def _note(body, **kw):
    n = {"body": body, "system": False, "resolvable": True, "resolved": True,
         "position": None}
    n.update(kw)
    return n


def _disc(did, notes, resolved=False):
    return {"id": did, "notes": notes}


_MR = {"iid": 7, "source_branch": "feature", "target_branch": "master",
       "diff_refs": {"base_sha": "b0", "start_sha": "s0", "head_sha": "h0"}}

_COMPARE = {"diffs": [{"new_path": "A.java", "diff": "@@ -1,2 +10,3 @@\n+a\n+b"}]}


class FakeClient:
    def __init__(self, discussions=(), mr=None, compare=None):
        self.sync_password = ""
        self.postbox: dict[str, dict] = {}
        self.acked: list[str] = []
        self.uploads: list[tuple] = []
        self.thread_notes: list[tuple] = []
        self.mr_notes: list[tuple] = []
        self.new_discussions: list[tuple] = []
        self.resolved: list[tuple] = []
        self.discussions = list(discussions)
        self.mr = mr if mr is not None else dict(_MR)
        self.compare = compare if compare is not None else dict(_COMPARE)
        self.fail_new_discussion_for: set[str] = set()
        self.fail_thread_note_for: set[str] = set()

    def add_reply_file(self, iid: int, data: str, mtime: int = 5, item_id: str = "r7"):
        self.postbox[item_id] = {"path": f"mr-replies/mr-!{iid}.md",
                                 "mtime": mtime, "data": data.encode("utf-8")}

    def file_sync_list(self, repo):
        return {"items": [{"id": i, "path": v["path"], "mtime": v["mtime"]}
                          for i, v in self.postbox.items()]}

    def file_sync_fetch(self, repo, item_id):
        return self.postbox[item_id]["data"]

    def file_sync_ack(self, repo, item_id):
        self.acked.append(item_id)
        return {"success": True}

    def file_sync_send(self, repo, path, plain_size, data):
        self.uploads.append((path, data))
        return {"id": "up1"}

    def gitlab_list_discussions(self, iid, project=""):
        return self.discussions

    def gitlab_get_mr(self, iid, project=""):
        return self.mr

    def gitlab_compare(self, from_ref, to_ref, project=""):
        return self.compare

    def gitlab_post_thread_note(self, iid, discussion_id, body, project=""):
        if discussion_id in self.fail_thread_note_for:
            raise LgmError(500, "boom")
        self.thread_notes.append((iid, discussion_id, body))
        return {"id": "n1"}

    def gitlab_post_mr_note(self, iid, body, project=""):
        self.mr_notes.append((iid, body))
        return {"id": "n2"}

    def gitlab_post_new_discussion(self, iid, body, diff_refs=None, position=None,
                                   project=""):
        if position and position[0] in self.fail_new_discussion_for:
            raise LgmError(400, "position is invalid")
        self.new_discussions.append((iid, body, diff_refs, position))
        return {"id": "d1"}

    def gitlab_resolve_discussion(self, iid, discussion_id, project=""):
        self.resolved.append((iid, discussion_id))
        return {"success": True}


_ENV = {"GITLAB_URL": "https://gitlab.corp", "GITLAB_TOKEN": "tok",
        "GITLAB_PROJECT": "grp/proj", "LGM_MACHINE_ROLE": "work"}


@pytest.fixture
def work_env(monkeypatch):
    values = dict(_ENV)
    monkeypatch.setattr(ops_mr, "cfg", lambda key, default="": values.get(key, default))
    return values


def _push(client, args=None):
    full = {"repo": "r1", "iid": 0, "dry_run": False}
    full.update(args or {})
    ctx = Ctx(config=SimpleNamespace(sync_password="", storage_path=""), client=client)
    return op_mr_replies_push(ctx, full)


def _report(res, iid=7):
    return next(r for r in res["reports"] if r.get("iid") == iid)


def test_thread_reply_posts_resolves_acks_and_uploads_status(work_env):
    client = FakeClient(discussions=[
        _disc("abc123", [_note("original remark", resolved=False)], resolved=False),
    ])
    client.add_reply_file(7, _MD)
    res = _push(client)
    assert res["success"] is True
    rep = _report(res)
    assert rep["posted"] == 4 and rep["failed"] == 0
    assert client.thread_notes == [(7, "abc123",
                                    f"{_marker(7, 't:abc123', 'fixed in 1234abc')}\n\nfixed in 1234abc")]
    assert client.resolved == [(7, "abc123")]
    assert client.acked == ["r7"]
    assert [u[0] for u in client.uploads] == ["mr-replies-status/mr-!7.md"]


def test_new_anchored_posts_discussion_with_position(work_env):
    client = FakeClient()
    client.add_reply_file(7, _MD)
    res = _push(client)
    anchored = [d for d in client.new_discussions if d[3] == ("A.java", 12)]
    assert len(anchored) == 1
    assert anchored[0][2] == _MR["diff_refs"]
    assert _marker(7, "a:A.java:12", "wrong order of args") in anchored[0][1]
    assert _report(res)["not_anchored"] == []


def test_new_outside_diff_falls_back_to_general_note_with_reason(work_env):
    client = FakeClient()
    client.fail_new_discussion_for = {"B.java"}
    client.add_reply_file(7, _MD)
    res = _push(client)
    rep = _report(res)
    assert rep["posted"] == 3
    assert rep["not_anchored"] == ["reply 3: B.java:99 — file not in MR diff"]
    fallback = [n for n in client.mr_notes if n[1].startswith("`B.java:99`:")]
    assert len(fallback) == 1
    assert _marker(7, "a:B.java:99", "not in diff") in fallback[0][1]
    assert "reply 3: line not in MR diff, posted as general note" in rep["details"]
    status = client.uploads[0][1].decode("utf-8")
    assert "reply 3: B.java:99 — file not in MR diff" in status


def test_unknown_thread_is_skipped_with_reason(work_env):
    md = "## thread gone42\nnope\n"
    client = FakeClient(discussions=[_disc("abc123", [_note("x")])])
    client.add_reply_file(7, md)
    res = _push(client)
    rep = _report(res)
    assert rep["skipped"] == 1 and rep["posted"] == 0
    assert "reply 1: thread gone42 no longer exists" in rep["details"]
    assert client.acked == ["r7"]


def test_duplicate_marker_with_resolve_flag_resolves_thread(work_env):
    dup_marker = _marker(7, "t:abc123", "fixed in 1234abc")
    client = FakeClient(discussions=[
        _disc("abc123", [_note(f"already answered {dup_marker}", resolved=False)]),
    ])
    client.add_reply_file(7, _MD)
    res = _push(client)
    rep = _report(res)
    assert rep["duplicates"] == 1
    assert "reply 1: duplicate, thread abc123 resolved" in rep["details"]
    assert client.thread_notes == []
    assert client.resolved == [(7, "abc123")]
    assert client.acked == ["r7"]


def test_duplicate_marker_without_resolve_flag_plain_skips(work_env):
    dup_marker = _marker(7, "t:abc123", "plain answer")
    client = FakeClient(discussions=[
        _disc("abc123", [_note(f"already answered {dup_marker}", resolved=False)]),
    ])
    client.add_reply_file(7, "## thread abc123\nplain answer\n")
    res = _push(client)
    rep = _report(res)
    assert rep["duplicates"] == 1
    assert "reply 1: duplicate, already in GitLab" in rep["details"]
    assert client.thread_notes == [] and client.resolved == []
    assert client.acked == ["r7"]


def test_anchor_clash_with_live_thread_is_skipped(work_env):
    clash = _disc("live1", [_note("open remark", resolved=False,
                                  position={"new_path": "A.java", "new_line": 11})])
    client = FakeClient(discussions=[clash])
    client.add_reply_file(7, "## new A.java:13\ndup\n")
    res = _push(client)
    rep = _report(res)
    assert rep["skipped"] == 1 and client.new_discussions == []
    assert "reply 1: unresolved thread live1 already at A.java:11" in rep["details"][0]
    assert "reply into it" in rep["details"][0]


def test_failed_reply_keeps_postbox_item_unacked(work_env):
    client = FakeClient(discussions=[
        _disc("abc123", [_note("original remark", resolved=False)]),
    ])
    client.fail_thread_note_for = {"abc123"}
    client.add_reply_file(7, "## thread abc123\nfix\n")
    res = _push(client)
    rep = _report(res)
    assert rep["failed"] == 1 and rep["posted"] == 0
    assert "reply 1: HTTP 500" in rep["details"][0]
    assert client.acked == []


def test_status_file_header_and_counters(work_env):
    client = FakeClient(discussions=[
        _disc("abc123", [_note("original remark", resolved=False)]),
    ])
    client.add_reply_file(7, _MD)
    _push(client)
    status = client.uploads[0][1].decode("utf-8")
    assert status.startswith("<!-- lgm-replies-status v1 -->\n# MR !7 — status\n")
    assert "- posted: 4" in status
    assert "- duplicates: 0" in status
    assert "- skipped: 0" in status
    assert "- failed: 0" in status
    assert "- not anchored: 0" in status
    assert "- at: " in status


def test_dry_run_touches_nothing(work_env):
    client = FakeClient(discussions=[
        _disc("abc123", [_note("original remark", resolved=False)]),
    ])
    client.add_reply_file(7, _MD)
    res = _push(client, {"dry_run": True})
    assert res["dry_run"] is True
    assert client.thread_notes == [] and client.mr_notes == []
    assert client.new_discussions == [] and client.resolved == []
    assert client.acked == [] and client.uploads == []
    rep = _report(res)
    assert rep["acked"] is False
    plan = "\n".join(rep["details"])
    assert "would post into thread abc123" in plan
    assert "would resolve thread abc123" in plan
    assert "would post as general note (file not in MR diff)" in plan


def test_iid_filter_pushes_only_that_mr(work_env):
    client = FakeClient()
    client.add_reply_file(7, _MD, mtime=5, item_id="r7")
    client.add_reply_file(8, "## new\nx\n", mtime=6, item_id="r8")
    res = _push(client, {"iid": 8})
    assert [r.get("iid") for r in res["reports"]] == [8]
    assert client.acked == ["r8"]


def test_superseded_sibling_reply_files_are_acked_and_reported(work_env):
    client = FakeClient(discussions=[
        _disc("abc123", [_note("original remark", resolved=False)]),
    ])
    client.add_reply_file(7, _MD, mtime=5, item_id="r7-old")
    client.add_reply_file(7, _MD, mtime=9, item_id="r7-new")
    res = _push(client)
    rep = _report(res)
    assert rep["acked"] is True
    assert rep["ack_superseded"] == ["r7-old"]
    assert set(client.acked) == {"r7-old", "r7-new"}


def test_single_reply_file_reports_empty_ack_superseded(work_env):
    client = FakeClient(discussions=[
        _disc("abc123", [_note("original remark", resolved=False)]),
    ])
    client.add_reply_file(7, _MD)
    res = _push(client)
    rep = _report(res)
    assert rep["ack_superseded"] == []
    assert client.acked == ["r7"]


def test_failed_reply_leaves_ack_superseded_empty(work_env):
    client = FakeClient(discussions=[
        _disc("abc123", [_note("original remark", resolved=False)]),
    ])
    client.fail_thread_note_for = {"abc123"}
    client.add_reply_file(7, "## thread abc123\nfix\n", mtime=5, item_id="r7-old")
    client.add_reply_file(7, "## thread abc123\nfix\n", mtime=9, item_id="r7-new")
    res = _push(client)
    rep = _report(res)
    assert rep["acked"] is False
    assert rep["ack_superseded"] == []
    assert client.acked == []


def test_home_role_refuses(work_env, monkeypatch):
    monkeypatch.setitem(work_env, "LGM_MACHINE_ROLE", "home")
    client = FakeClient()
    client.add_reply_file(7, _MD)
    with pytest.raises(LgmError) as excinfo:
        _push(client)
    assert excinfo.value.code == "config"
    assert "work" in excinfo.value.message
    assert client.acked == [] and client.thread_notes == []


def test_missing_gitlab_config_refuses(work_env, monkeypatch):
    monkeypatch.delitem(work_env, "GITLAB_TOKEN")
    client = FakeClient()
    client.add_reply_file(7, _MD)
    with pytest.raises(LgmError) as excinfo:
        _push(client)
    assert excinfo.value.code == "config"
    assert "GitLab URL/project/token not configured" in excinfo.value.message


def test_non_reply_file_under_mr_replies_is_reported_skipped(work_env):
    client = FakeClient()
    client.postbox["junk"] = {"path": "mr-replies/notes.txt", "mtime": 1,
                              "data": b"x"}
    res = _push(client)
    rep = res["reports"][0]
    assert rep["skipped"] == 1
    assert "not an mr-!N.md reply file" in rep["details"][0]
    assert client.acked == []


def test_render_shows_counts_per_reply_and_not_anchored():
    payload = {
        "success": True, "repo": "r1", "dry_run": False, "count": 1,
        "totals": {"posted": 2, "duplicates": 1, "skipped": 0, "failed": 0,
                   "not_anchored": 1},
        "reports": [{
            "iid": 7, "path": "mr-replies/mr-!7.md", "posted": 2,
            "duplicates": 1, "skipped": 0, "failed": 0, "parse_errors": 0,
            "acked": True,
            "not_anchored": ["reply 3: B.java:99 — file not in MR diff"],
            "replies": [
                {"label": "reply 1", "outcome": "posted",
                 "note": "posted into thread abc123"},
                {"label": "reply 2", "outcome": "dup",
                 "note": "duplicate, already in GitLab"},
            ],
            "details": [],
        }],
        "skipped_items": [],
    }
    out = render("mr_replies_push", payload)
    assert "posted=2" in out and "not_anchored=1" in out
    assert "reply 1: posted into thread abc123" in out
    assert "reply 3: B.java:99 — file not in MR diff" in out
    assert "[acked]" in out


def test_render_lists_superseded_acked_ids():
    payload = {
        "success": True, "repo": "r1", "dry_run": False, "count": 1,
        "totals": {"posted": 1, "duplicates": 0, "skipped": 0, "failed": 0,
                   "not_anchored": 0},
        "reports": [{
            "iid": 7, "path": "mr-replies/mr-!7.md", "posted": 1,
            "duplicates": 0, "skipped": 0, "failed": 0, "parse_errors": 0,
            "acked": True, "ack_superseded": ["r7-old"],
            "not_anchored": [], "replies": [], "details": [],
        }],
    }
    out = render("mr_replies_push", payload)
    assert "r7-old" in out
    assert "superseded" in out


# ── mr_replies_status: parse the v1 document once, machine fields ────────────

_STATUS_MD = "\n".join([
    "<!-- lgm-replies-status v1 -->",
    "# MR !7 — status",
    "- posted: 4",
    "- duplicates: 1",
    "- skipped: 2",
    "- failed: 3",
    "- not anchored: 7",
    "- at: 2026-10-19 23:45",
    "  - reply 1: posted into thread abc123",
    "  - reply 3: B.java:99 — file not in MR diff",
]) + "\n"


def _status_ctx(client):
    return Ctx(config=SimpleNamespace(sync_password="", storage_path=""),
               client=client)


def _add_status_file(client, iid=7, mtime=5, data=_STATUS_MD, item_id="s7"):
    client.postbox[item_id] = {"path": f"mr-replies-status/mr-!{iid}.md",
                               "mtime": mtime, "data": data.encode("utf-8")}


def test_mr_replies_status_parses_v1_document_into_fields():
    client = FakeClient()
    _add_status_file(client)
    res = op_mr_replies_status(_status_ctx(client), {"repo": "r1"})
    s = res["statuses"][0]
    assert s["parsed"] is True
    assert s["posted"] == 4 and s["duplicates"] == 1
    assert s["skipped"] == 2 and s["failed"] == 3
    assert s["not_anchored"] == 7
    assert s["at"] == "2026-10-19 23:45"
    assert s["details"] == ["reply 1: posted into thread abc123",
                            "reply 3: B.java:99 — file not in MR diff"]
    assert s["markdown"] == _STATUS_MD


def test_mr_replies_status_marks_foreign_documents_unparsed():
    client = FakeClient()
    _add_status_file(client, data="- posted: 3\n")
    res = op_mr_replies_status(_status_ctx(client), {"repo": "r1"})
    s = res["statuses"][0]
    assert s["parsed"] is False
    assert s["posted"] is None and s["failed"] is None
    assert s["markdown"] == "- posted: 3\n"


def test_render_mr_replies_status_reads_parsed_fields():
    payload = {"success": True, "repo": "r1", "count": 1, "statuses": [{
        "iid": 7, "path": "mr-replies-status/mr-!7.md", "markdown": _STATUS_MD,
        "posted": 4, "duplicates": 1, "skipped": 2, "failed": 3,
        "not_anchored": 7, "at": "2026-10-19 23:45", "details": [],
        "parsed": True,
    }]}
    out = render("mr_replies_status", payload)
    assert "posted=4" in out and "failed=3" in out
    assert "not_anchored=7" in out
    assert "2026-10-19 23:45" in out


def test_render_mr_replies_status_flags_unparsed_documents():
    payload = {"success": True, "repo": "r1", "count": 1, "statuses": [{
        "iid": 7, "path": "mr-replies-status/mr-!7.md", "markdown": "- posted: 3",
        "posted": None, "duplicates": None, "skipped": None, "failed": None,
        "not_anchored": None, "at": "", "details": [], "parsed": False,
    }]}
    out = render("mr_replies_status", payload)
    assert "unrecognized" in out


def test_render_mr_replies_status_owns_no_posted_regex():
    src = (Path(__file__).resolve().parent.parent / "lgm_core" / "render.py"
           ).read_text(encoding="utf-8")
    assert "re.search" not in src
    assert 'r"^- posted' not in src


# ── machine codes on the push refusals ───────────────────────────────────────

def test_push_wrong_machine_carries_machine_code(work_env, monkeypatch):
    monkeypatch.setitem(work_env, "LGM_MACHINE_ROLE", "home")
    client = FakeClient()
    client.add_reply_file(7, _MD)
    with pytest.raises(LgmError) as excinfo:
        _push(client)
    assert excinfo.value.machine_code == "wrong-machine"


def test_push_role_unset_carries_machine_code(work_env, monkeypatch):
    monkeypatch.setitem(work_env, "LGM_MACHINE_ROLE", "")
    client = FakeClient()
    client.add_reply_file(7, _MD)
    with pytest.raises(LgmError) as excinfo:
        _push(client)
    assert excinfo.value.machine_code == "role-unset"


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
