"""Home without GitLab: mr_list falls back to transferred notes (v3 postbox)."""
from types import SimpleNamespace

import pytest

from lgm_core.client import LgmError
from lgm_core.ops import (
    Ctx,
    op_mr_list,
    op_mr_notes,
    op_mr_replies_send,
    op_mr_replies_status,
)

MD = "\n".join([
    "<!-- note -->",
    "# MR !7 — Title seven",
    "",
    "- **Ветка:** `feat/x`",
    "",
    "## ⚠ НЕ РЕШЕНО · тред 1",
    "## ⚠ НЕ РЕШЕНО · тред 2",
    "## ✓ решено · тред 3",
    "",
])


class _StubClient:
    def __init__(self, markdown=MD, gitlab_error=None, items=None):
        self._md = markdown
        self._gitlab_error = gitlab_error or LgmError("config", "GitLab not configured")
        self._items = items
        self.sent = None

    def gitlab_list_mrs(self):
        raise self._gitlab_error

    def repos(self):
        return {"repos": [{"name": "r1"}]}

    def file_sync_list(self, repo):
        if self._items is not None:
            return {"items": self._items}
        return {"items": [{"id": "i1", "path": "mr-notes/mr-!7.md", "mtime": 5,
                           "plain_size": len(MD)}]}

    def file_sync_fetch(self, repo, item_id):
        if self._items is not None:
            for i in self._items:
                if i["id"] == item_id:
                    return i["content"].encode("utf-8")
        return self._md.encode("utf-8")

    def file_sync_send(self, repo, path, plain_size, data):
        self.sent = (repo, path, plain_size, data)
        return {"id": "new1"}


def test_mr_list_falls_back_to_postbox_without_gitlab():
    res = op_mr_list(Ctx(config=SimpleNamespace(sync_password=""), client=_StubClient()), {})
    assert res["count"] == 1
    item = res["items"][0]
    assert item["iid"] == 7
    assert item["title"] == "Title seven"
    assert item["source_branch"] == "feat/x"
    assert item["unresolved"] == 2
    assert item["source"] == "cache"
    assert item["repo"] == "r1"
    assert res["errors"] == []


def test_mr_list_reraises_non_config_gitlab_error():
    client = _StubClient(gitlab_error=LgmError(500, "GitLab API error 500: boom"))
    with pytest.raises(LgmError) as ei:
        op_mr_list(Ctx(config=SimpleNamespace(sync_password=""), client=client), {})
    assert ei.value.code == 500


def test_mr_list_collects_postbox_errors():
    class _TwoRepoClient:
        def gitlab_list_mrs(self):
            raise LgmError("config", "GitLab not configured")

        def repos(self):
            return {"repos": [{"name": "good"}, {"name": "bad"}]}

        def file_sync_list(self, repo):
            if repo == "bad":
                raise LgmError("network", "mirror unreachable")
            return {"items": []}

    res = op_mr_list(Ctx(config=SimpleNamespace(sync_password=""),
                         client=_TwoRepoClient()), {})
    assert res["count"] == 0
    assert res["items"] == []
    assert res["errors"] == [{"repo": "bad", "error": "mirror unreachable"}]


def test_mr_replies_send_uploads_plaintext_with_real_path():
    client = _StubClient()
    res = op_mr_replies_send(Ctx(config=SimpleNamespace(sync_password=""), client=client),
                             {"repo": "r1", "iid": 7, "text": "## new\nanswer"})
    assert res["success"] is True
    repo, path, plain_size, data = client.sent
    assert repo == "r1"
    assert path == "mr-replies/mr-!7.md"
    expected = "# MR !7\n\n## new\nanswer"
    assert plain_size == len(expected)
    assert data == expected.encode("utf-8")


def test_mr_replies_send_keeps_existing_header():
    client = _StubClient()
    body = "# MR !7 - Title\n\n## new\nanswer"
    res = op_mr_replies_send(Ctx(config=SimpleNamespace(sync_password=""), client=client),
                             {"repo": "r1", "iid": 7, "text": body})
    assert res["success"] is True
    _, _, plain_size, data = client.sent
    assert plain_size == len(body)
    assert data == body.encode("utf-8")


def test_mr_replies_send_requires_iid():
    res = op_mr_replies_send(Ctx(config=SimpleNamespace(sync_password=""), client=_StubClient()),
                             {"repo": "r1", "text": "## new\nx"})
    assert res["success"] is False


def test_mr_replies_send_rejects_header_iid_mismatch():
    client = _StubClient()
    body = "# MR !8 — answers\n\n## new\nanswer"
    res = op_mr_replies_send(Ctx(config=SimpleNamespace(sync_password=""), client=client),
                             {"repo": "r1", "iid": 7, "text": body})
    assert res["success"] is False
    assert "MR !8" in res["error"]
    assert client.sent is None


def test_mr_replies_send_counts_size_in_bytes():
    client = _StubClient()
    body = "## new\nИсправил, проверь"
    res = op_mr_replies_send(Ctx(config=SimpleNamespace(sync_password=""), client=client),
                             {"repo": "r1", "iid": 7, "text": body})
    assert res["success"] is True
    _, _, plain_size, data = client.sent
    expected = f"# MR !7\n\n{body}".encode("utf-8")
    assert plain_size == len(expected)
    assert data == expected


def test_mr_notes_returns_newest_per_iid():
    items = [
        {"id": "old7", "path": "mr-notes/mr-!7.md", "mtime": 5, "plain_size": 10,
         "content": "# MR !7 — old"},
        {"id": "new7", "path": "mr-notes/mr-!7.md", "mtime": 9, "plain_size": 10,
         "content": "# MR !7 — fresh"},
        {"id": "s3", "path": "mr-notes/mr-!3.md", "mtime": 7, "plain_size": 10,
         "content": "# MR !3 — x"},
        {"id": "r", "path": "mr-replies/mr-!7.md", "mtime": 99, "plain_size": 1,
         "content": "## new\nreply"},
    ]
    res = op_mr_notes(Ctx(config=SimpleNamespace(sync_password=""),
                          client=_StubClient(items=items)), {"repo": "r1"})
    assert res["count"] == 2
    assert [n["iid"] for n in res["notes"]] == [3, 7]
    assert res["notes"][1]["markdown"] == "# MR !7 — fresh"


def test_mr_notes_filters_by_iid():
    items = [
        {"id": "n7", "path": "mr-notes/mr-!7.md", "mtime": 5, "content": "# MR !7"},
        {"id": "n8", "path": "mr-notes/mr-!8.md", "mtime": 6, "content": "# MR !8"},
    ]
    res = op_mr_notes(Ctx(config=SimpleNamespace(sync_password=""),
                          client=_StubClient(items=items)), {"repo": "r1", "iid": 8})
    assert res["count"] == 1
    assert res["notes"][0]["iid"] == 8


def test_mr_replies_status_returns_newest_per_iid():
    items = [
        {"id": "s-old", "path": "mr-replies-status/mr-!7.md", "mtime": 5,
         "content": "- posted: 1"},
        {"id": "s-new", "path": "mr-replies-status/mr-!7.md", "mtime": 9,
         "content": "- posted: 3"},
        {"id": "n", "path": "mr-notes/mr-!7.md", "mtime": 99, "content": "# MR !7"},
    ]
    res = op_mr_replies_status(Ctx(config=SimpleNamespace(sync_password=""),
                                   client=_StubClient(items=items)), {"repo": "r1"})
    assert res["count"] == 1
    assert res["statuses"][0]["markdown"] == "- posted: 3"


def _notes_items(iid=7, thread_id="t1", file="Foo.java", line=75, resolved=False):
    kind = "## ✓ решено" if resolved else "## ⚠ НЕ РЕШЕНО"
    md = "\n".join([
        f"# MR !{iid} — T",
        "",
        "- **Ветка:** `b`",
        "",
        kind,
        f"**ID треда:** `{thread_id}`",
        "",
        f"**Место:** `{file}:{line}`",
        "",
        "**Ivan** _(reviewer · 2026-10-02T10:00:00Z)_:",
        "body",
    ])
    return [{"id": "n1", "path": f"mr-notes/mr-!{iid}.md", "mtime": 9, "content": md}]


def _send(client, text, **kw):
    return op_mr_replies_send(Ctx(config=SimpleNamespace(sync_password=""), client=client),
                              {"repo": "r1", "iid": 7, "text": text, **kw})


def test_mr_replies_send_rejects_anchor_colliding_with_unresolved_thread():
    res = _send(_StubClient(items=_notes_items()), "## new Foo.java:75\nfix")
    assert res["success"] is False
    assert "## thread t1" in res["error"]
    assert res["precheck"]["collisions"][0]["thread"] == "t1"


def test_mr_replies_send_rejects_anchor_within_drift_window():
    res = _send(_StubClient(items=_notes_items(line=106)), "## new Foo.java:104\nfix")
    assert res["success"] is False
    assert "Foo.java:106" in res["error"]


def test_mr_replies_send_allows_distant_anchor_same_file():
    res = _send(_StubClient(items=_notes_items(line=106)), "## new Foo.java:217\nfix")
    assert res["success"] is True


def test_mr_replies_send_resolved_thread_anchor_does_not_clash():
    res = _send(_StubClient(items=_notes_items(resolved=True)), "## new Foo.java:75\nfix")
    assert res["success"] is True


def test_mr_replies_send_rejects_unknown_thread_id():
    res = _send(_StubClient(items=_notes_items()), "## thread nope\nfix")
    assert res["success"] is False
    assert "'## thread nope' not found" in res["error"]
    assert "t1" in res["error"]


def test_mr_replies_send_accepts_known_thread_id():
    res = _send(_StubClient(items=_notes_items()), "## thread t1\nfix")
    assert res["success"] is True


def test_mr_replies_send_force_overrides_precheck():
    client = _StubClient(items=_notes_items())
    res = _send(client, "## new Foo.java:75\nfix", force=True)
    assert res["success"] is True
    assert res["precheck"]["status"] == "forced"
    assert client.sent is not None


def test_mr_replies_send_precheck_failopen_without_notes():
    res = _send(_StubClient(items=[]), "## new Foo.java:75\nfix")
    assert res["success"] is True
    assert res["precheck"]["status"] == "notes-unavailable"


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
