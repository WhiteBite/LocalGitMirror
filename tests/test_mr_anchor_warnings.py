"""Real-git proof for the mr_replies_send anchor warning: GitLab threads
anchor only to lines inside the MR diff, so a '## new' path outside
merge-base(target)..source would post as a general note."""
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.op_models import Ctx
from lgm_core.ops import op_mr_replies_send
from lgm_core.ops_mr import _notes_precheck
from lgm_core.render import render


def _git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(repo),
                          capture_output=True, text=True, timeout=30)
    if check:
        assert proc.returncode == 0, f"git {' '.join(args)}: {proc.stderr}"
    return proc


def _repo_target_source(proj: Path) -> Path:
    proj.mkdir(parents=True)
    _git(proj, "init", "-q", "-b", "master")
    _git(proj, "config", "user.email", "t@t")
    _git(proj, "config", "user.name", "t")
    (proj / "A.java").write_text("a\n", encoding="utf-8")
    _git(proj, "add", "A.java")
    _git(proj, "commit", "-qm", "base")
    _git(proj, "checkout", "-qb", "feature")
    (proj / "A.java").write_text("a2\n", encoding="utf-8")
    _git(proj, "commit", "-qam", "touch A only")
    _git(proj, "checkout", "-q", "master")
    return proj


_MD_WITH_TARGET = "\n".join([
    "# MR !7 — Title seven",
    "",
    "- **Ветка:** `feature`",
    "- **Target:** `master`",
])

_MD_NO_TARGET = "\n".join([
    "# MR !7 — Title seven",
    "",
    "- **Ветка:** `feature`",
])

_B_WARNING = ("B.java:9 not in MR !7 diff (feature vs master) - "
              "will post as a general note or belongs in an issue")


class _NotesClient:
    def __init__(self, markdown):
        self._md = markdown
        self.sent = None

    def file_sync_list(self, repo):
        return {"items": [{"id": "n1", "path": "mr-notes/mr-!7.md", "mtime": 5,
                           "plain_size": len(self._md)}]}

    def file_sync_fetch(self, repo, item_id):
        return self._md.encode("utf-8")

    def file_sync_send(self, repo, path, plain_size, data):
        self.sent = (repo, path, plain_size, data)
        return {"id": "new1"}


def _replies_file(tmp_path: Path, body: str) -> str:
    p = tmp_path / "replies-7.md"
    p.write_text(body, encoding="utf-8")
    return str(p)


def _send(client, tmp_path, body, project=None, storage=""):
    args = {"repo": "r1", "iid": 7, "file": _replies_file(tmp_path, body)}
    if project is not None:
        args["project"] = project
    ctx = Ctx(config=SimpleNamespace(sync_password="", storage_path=storage),
              client=client)
    return op_mr_replies_send(ctx, args)


def test_anchor_outside_mr_diff_warns_and_still_sends(tmp_path):
    proj = _repo_target_source(tmp_path / "proj")
    client = _NotesClient(_MD_WITH_TARGET)
    res = _send(client, tmp_path,
                "## new A.java:5\nok\n\n## new B.java:9\nbad\n", str(proj))
    assert res["success"] is True
    assert res["anchor_warnings"] == [_B_WARNING]
    assert client.sent is not None


def test_anchor_inside_mr_diff_does_not_warn(tmp_path):
    proj = _repo_target_source(tmp_path / "proj")
    res = _send(_NotesClient(_MD_WITH_TARGET), tmp_path,
                "## new A.java:5\nok\n", str(proj))
    assert res["success"] is True
    assert res["anchor_warnings"] == []


def test_notes_without_target_line_produce_no_warnings(tmp_path):
    proj = _repo_target_source(tmp_path / "proj")
    res = _send(_NotesClient(_MD_NO_TARGET), tmp_path,
                "## new B.java:9\nbad\n", str(proj))
    assert res["success"] is True
    assert res["anchor_warnings"] == []


def test_russian_target_label_is_accepted(tmp_path):
    proj = _repo_target_source(tmp_path / "proj")
    md = _MD_WITH_TARGET.replace("- **Target:** `master`",
                                 "- **Целевая:** `master`")
    res = _send(_NotesClient(md), tmp_path, "## new B.java:9\nbad\n", str(proj))
    assert res["success"] is True
    assert res["anchor_warnings"] == [_B_WARNING]


def test_target_branch_missing_locally_skips_warning(tmp_path):
    proj = _repo_target_source(tmp_path / "proj")
    md = _MD_WITH_TARGET.replace("`master`", "`nope`")
    res = _send(_NotesClient(md), tmp_path, "## new B.java:9\nbad\n", str(proj))
    assert res["success"] is True
    assert res["anchor_warnings"] == []


def test_source_branch_missing_locally_skips_warning(tmp_path):
    proj = _repo_target_source(tmp_path / "proj")
    md = _MD_WITH_TARGET.replace("`feature`", "`ghost`")
    res = _send(_NotesClient(md), tmp_path, "## new B.java:9\nbad\n", str(proj))
    assert res["success"] is True
    assert res["anchor_warnings"] == []


def test_missing_project_dir_skips_warning(tmp_path):
    res = _send(_NotesClient(_MD_WITH_TARGET), tmp_path,
                "## new B.java:9\nbad\n", str(tmp_path / "nope"))
    assert res["success"] is True
    assert res["anchor_warnings"] == []


def test_workspace_used_when_no_project_given(tmp_path):
    _repo_target_source(tmp_path / "storage" / "r1")
    res = _send(_NotesClient(_MD_WITH_TARGET), tmp_path,
                "## new B.java:9\nbad\n", storage=str(tmp_path / "storage"))
    assert res["success"] is True
    assert res["anchor_warnings"] == [_B_WARNING]


def test_no_storage_and_no_project_is_not_a_crash(tmp_path):
    res = _send(_NotesClient(_MD_WITH_TARGET), tmp_path, "## new B.java:9\nbad\n")
    assert res["success"] is True
    assert res["anchor_warnings"] == []


def test_precheck_output_carries_anchor_warnings(tmp_path):
    proj = _repo_target_source(tmp_path / "proj")
    precheck = _notes_precheck(_NotesClient(_MD_WITH_TARGET), "r1", 7,
                               "## new B.java:9\nbad\n", force=False,
                               project=proj)
    assert precheck["status"] == "ok"
    assert precheck["anchor_warnings"] == [_B_WARNING]


def test_render_mr_replies_send_shows_anchor_warnings():
    out = render("mr_replies_send", {
        "success": True, "repo": "r1", "path": "mr-replies/mr-!7.md", "size": 10,
        "anchor_warnings": [_B_WARNING],
        "precheck": {"status": "ok", "collisions": [], "unknown_threads": []},
        "response": {"id": "new1"}})
    assert "B.java:9" in out
    assert "general note" in out


def test_render_mr_replies_send_clean_send_has_no_warning_lines():
    out = render("mr_replies_send", {
        "success": True, "repo": "r1", "path": "mr-replies/mr-!7.md", "size": 10,
        "anchor_warnings": [],
        "precheck": {"status": "ok", "collisions": [], "unknown_threads": []},
        "response": {}})
    assert "warning" not in out.lower()


def test_render_mr_replies_send_check_only_says_nothing_uploaded():
    out = render("mr_replies_send", {
        "success": True, "check_only": True, "repo": "r1", "iid": 7,
        "precheck": {"status": "ok", "collisions": [], "unknown_threads": []},
        "anchor_warnings": [], "message": "check-only: nothing uploaded"})
    assert "nothing uploaded" in out
    assert "uploaded to postbox" not in out


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
