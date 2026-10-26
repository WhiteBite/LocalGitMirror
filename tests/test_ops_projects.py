"""Tests for the multi-project MR registry, notes renderer and batch sync."""
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError, MirrorClient
from lgm_core.config import Config
from lgm_core.git_remote import parse_remote as _parse_remote
from lgm_core.mr_notes_render import parse_discussions, render_markdown
from lgm_core.op_models import Ctx
from lgm_core.ops import get_op
from lgm_core.ops_projects import op_mr_projects_scan, op_mr_projects_list, op_mr_sync_all


def _ctx(sync_password: str = "pw") -> Ctx:
    config = Config(base_url="http://localhost:1", api_key="",
                    sync_password=sync_password)
    client = MirrorClient(base_url=config.base_url, api_key="",
                          sync_password=sync_password)
    return Ctx(config=config, client=client)


def _raw_discussions() -> list:
    return [
        {
            "id": "ccd35f",
            "notes": [
                {
                    "author": {"name": "Daniil Minkin"},
                    "created_at": "2026-10-02T13:10:48.092Z",
                    "system": False,
                    "body": "тред открыт",
                    "resolvable": True,
                    "resolved": False,
                    "position": {
                        "new_path": "src/Foo.java", "new_line": 75,
                        "old_path": "src/Foo.java", "old_line": 70,
                    },
                },
                {
                    "author": {"name": "Ivan"},
                    "created_at": "2026-10-02T13:20:00Z",
                    "system": False,
                    "body": "ответ автора",
                    "resolvable": False,
                },
            ],
        },
        {
            "id": "old_thread",
            "notes": [
                {
                    "author": {"name": "Aleksandr"},
                    "created_at": "2026-09-01T10:00:00Z",
                    "system": False,
                    "body": "решённый тред",
                    "resolvable": True,
                    "resolved": True,
                    "position": {"old_path": "src/Bar.java", "old_line": 10},
                },
            ],
        },
        {
            "id": "sys",
            "notes": [
                {"author": {"name": "GitLab"},
                 "created_at": "2026-09-01T09:00:00Z",
                 "system": True, "body": "assigned to @i.spirkov"},
            ],
        },
    ]



def test_parse_discussions_resolved_derivation():
    out = parse_discussions(_raw_discussions())
    assert out[0]["resolved"] is False
    assert out[1]["resolved"] is True
    assert out[2]["resolved"] is False
    assert out[0]["notes"][0]["file_path"] == "src/Foo.java"
    assert out[0]["notes"][0]["line"] == 75
    assert out[1]["notes"][0]["file_path"] == "src/Bar.java"
    assert out[1]["notes"][0]["line"] == 10
    assert out[0]["notes"][1]["file_path"] is None
    assert out[2]["notes"][0]["system"] is True


def test_parse_discussions_position_fallback_to_old():
    raw = [{
        "id": "d1",
        "notes": [{
            "author": {"name": "A"},
            "created_at": "t",
            "system": False,
            "body": "b",
            "resolvable": True,
            "resolved": False,
            "position": {"old_path": "X.java", "old_line": 5},
        }],
    }]
    out = parse_discussions(raw)
    assert out[0]["notes"][0]["file_path"] == "X.java"
    assert out[0]["notes"][0]["line"] == 5


def test_parse_discussions_author_default():
    raw = [{"id": "d", "notes": [{"created_at": "t", "body": "b"}]}]
    out = parse_discussions(raw)
    assert out[0]["notes"][0]["author"] == "?"



def test_render_markdown_full_format(tmp_path):
    proj = tmp_path / "proj"
    (proj / "src").mkdir(parents=True)
    (proj / "src" / "Foo.java").write_text(
        "\n".join(f"line {i}" for i in range(1, 81)), encoding="utf-8")

    md = render_markdown(
        24, "Llm-core", "llm-core", "2026-10-03T11:38:04.050Z",
        1, 2, parse_discussions(_raw_discussions()),
        project_dir=proj, branch="",
    )
    lines = md.splitlines()
    assert lines[0].startswith("<!-- Сгенерировано плагином DocCache")
    assert lines[1] == "# MR !24 — Llm-core"
    assert "> Инструкция агенту: прочитай нерешённые треды ниже и внеси правки." in lines
    assert "> Стиль: как у человека — только по делу (ошибка, плохое решение, проёб), 1-3 предложения;" in lines
    assert "- **Ветка:** `llm-core`" in lines
    assert "- **Статус:** открыт · **1 нерешённых** из 2 тредов" in lines
    assert "- **Обновлено:** 2026-10-03T11:38:04.050Z · **Источник:** GitLab MR !24" in lines

    assert "## ⚠ НЕ РЕШЕНО · тред 1" in lines
    assert "<!-- lgm-thread: ccd35f -->" in lines
    assert "**ID треда:** `ccd35f`" in lines
    assert "**Место:** `src/Foo.java:75`" in lines
    assert "```java" in lines
    assert ">75: line 75" in lines
    assert " 74: line 74" in lines
    assert "**Daniil Minkin** _(reviewer · 2026-10-02T13:10:48.092Z)_:" in lines
    assert "&nbsp;&nbsp;↳ **Ivan** _(2026-10-02T13:20:00Z)_:" in lines
    assert "ответ автора" in lines

    assert "## ✓ решено · тред 1 · закрыл: Aleksandr" in lines
    assert "**Место:** `src/Bar.java:10`" in lines
    bar_idx = lines.index("**Место:** `src/Bar.java:10`")
    assert "```java" not in lines[bar_idx:bar_idx + 3]

    assert "## ✓ системные события (свёрнуто · 1)" in lines
    assert "- assigned to @i.spirkov · 2026-09-01T09:00:00Z" in lines


def test_render_markdown_general_comment_without_anchor():
    discussions = [{"id": "g1", "resolved": False, "notes": [
        {"author": "A", "created_at": "t", "system": False,
         "body": "b", "file_path": None, "line": None},
    ]}]
    md = render_markdown(7, "T", "b", "u", 1, 1, discussions)
    assert "**Место:** общий комментарий (не привязан к коду)" in md


def test_render_markdown_reads_code_from_branch(tmp_path):
    proj = tmp_path / "proj"
    proj.mkdir()
    subprocess.run(["git", "-C", str(proj), "init", "-q", "-b", "master"], check=True)
    (proj / "F.txt").write_text("a\nb\nc\nd\ne\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(proj), "add", "."], check=True)
    subprocess.run(["git", "-C", str(proj), "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", str(proj), "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", str(proj), "commit", "-q", "-m", "i"], check=True)
    (proj / "F.txt").write_text("CHANGED\n", encoding="utf-8")

    discussions = [{"id": "d", "resolved": False, "notes": [
        {"author": "A", "created_at": "t", "system": False,
         "body": "b", "file_path": "F.txt", "line": 3},
    ]}]
    md = render_markdown(7, "T", "b", "u", 1, 1, discussions,
                         project_dir=proj, branch="master")
    assert ">3: c" in md
    assert "CHANGED" not in md



@pytest.mark.parametrize("remote,host,path", [
    ("https://gitlab.corp/dev/onyx/doctransformer.git",
     "gitlab.corp", "dev/onyx/doctransformer"),
    ("git@gitlab.corp:dev/onyx/frontend.git",
     "gitlab.corp", "dev/onyx/frontend"),
    ("https://gitlab.corp/group/proj", "gitlab.corp", "group/proj"),
    ("", "", ""),
])
def test_parse_remote(remote, host, path):
    assert _parse_remote(remote) == (host, path)



def _git_init(d: Path, remote: str) -> None:
    d.mkdir(parents=True)
    subprocess.run(["git", "-C", str(d), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(d), "remote", "add", "origin", remote], check=True)


def test_mr_projects_scan_registers_gitlab_repos(tmp_path, monkeypatch):
    import lgm_core.git_remote as gitrem
    import lgm_core.ops_projects as opmod
    monkeypatch.setattr(opmod, "_REGISTRY_PATH", tmp_path / "mr-projects.json")
    fake_cfg = lambda key, default="": "https://gitlab.corp" if key == "GITLAB_URL" else default
    monkeypatch.setattr(opmod, "cfg", fake_cfg)
    monkeypatch.setattr(gitrem, "cfg", fake_cfg)

    root = tmp_path / "src"
    _git_init(root / "doctransformer", "https://gitlab.corp/dev/onyx/doctransformer.git")
    _git_init(root / "frontend", "git@gitlab.corp:dev/onyx/frontend.git")
    _git_init(root / "tools", "https://github.com/some/tools.git")
    (root / "not-a-repo").mkdir()

    res = opmod.op_mr_projects_scan(_ctx(), {"root": str(root)})
    assert res["success"] is True
    assert sorted(res["added"]) == ["doctransformer", "frontend"]
    repos = {e["repo"]: e for e in res["projects"]}
    assert repos["doctransformer"]["gitlab"] == "dev/onyx/doctransformer"
    assert repos["frontend"]["gitlab"] == "dev/onyx/frontend"
    assert "tools" not in repos

    res2 = opmod.op_mr_projects_scan(_ctx(), {"root": str(root)})
    assert res2["added"] == []
    assert res2["total"] == 2

    listing = opmod.op_mr_projects_list(_ctx(), {})
    assert listing["count"] == 2
    assert listing["projects"][0]["exists"] is True


def test_mr_projects_scan_requires_gitlab_url(tmp_path, monkeypatch):
    import lgm_core.ops_projects as opmod
    monkeypatch.setattr(opmod, "_REGISTRY_PATH", tmp_path / "mr-projects.json")
    monkeypatch.setattr(opmod, "cfg", lambda key, default="": default)
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_projects_scan(_ctx(), {"root": str(tmp_path)})
    assert "GITLAB_URL" in ei.value.message



def _make_project(tmp_path: Path) -> Path:
    proj = tmp_path / "doctransformer"
    proj.mkdir()
    subprocess.run(["git", "-C", str(proj), "init", "-q", "-b", "master"], check=True)
    subprocess.run(["git", "-C", str(proj), "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", str(proj), "config", "user.name", "t"], check=True)
    (proj / "src").mkdir()
    (proj / "src" / "Foo.java").write_text(
        "\n".join(f"line {i}" for i in range(1, 80)), encoding="utf-8")
    subprocess.run(["git", "-C", str(proj), "add", "."], check=True)
    subprocess.run(["git", "-C", str(proj), "commit", "-q", "-m", "init"], check=True)
    subprocess.run(["git", "-C", str(proj), "branch", "llm-core"], check=True)
    return proj


def _prepare_sync(tmp_path, monkeypatch):
    import lgm_core.ops_projects as opmod
    proj = _make_project(tmp_path)
    monkeypatch.setattr(opmod, "_REGISTRY_PATH", tmp_path / "reg.json")
    monkeypatch.setattr(opmod, "_STATE_PATH", tmp_path / "state.json")
    monkeypatch.setattr(opmod, "_fetch_mr_branches", lambda proj, branches: [])
    monkeypatch.setattr(opmod, "_mirror_refs_safe", lambda c, repo: {})
    sent_calls = []

    def fake_send_branches(ctx, repo, project, branches, excludes, dry_run=False):
        sent_calls.append((repo, list(branches)))
        return {"bundle_size": 10}

    monkeypatch.setattr(opmod, "send_branches", fake_send_branches)
    ctx = _ctx()
    ctx.client.sync_negotiate = lambda repo, commits, probe=False: {"known": []}
    opmod._save_registry({"projects": [
        {"repo": "doctransformer", "path": str(proj),
         "gitlab": "dev/onyx/doctransformer"}]})

    ctx = _ctx()
    uploaded = []
    ctx.client.gitlab_list_mrs = lambda project="": [
        {"iid": 24, "title": "Llm-core", "source_branch": "llm-core",
         "updated_at": "2026-10-03T11:38:04.050Z"},
    ]
    ctx.client.gitlab_list_discussions = lambda iid, project="": _raw_discussions()
    ctx.client.file_sync_send = lambda repo, path, size, data: (
        uploaded.append((repo, path, bytes(data))), {"success": True, "id": "x"})[1]
    return opmod, ctx, proj, sent_calls, uploaded


def test_mr_sync_all_uploads_notes_and_branches(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)

    res = opmod.op_mr_sync_all(ctx, {})
    assert res["success"] is True
    assert res["summary"]["notes_uploaded"] == 1
    assert res["summary"]["branches_sent"] == 1
    assert sent_calls == [("doctransformer", ["llm-core"])]
    repo, path, data = uploaded[0]
    assert path == "mr-notes/mr-!24.md"
    md = data.decode("utf-8")
    assert "# MR !24 — Llm-core" in md
    assert "**ID треда:** `ccd35f`" in md
    assert ">75: line 75" in md


def test_mr_sync_all_second_run_is_full_delta(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    opmod.op_mr_sync_all(ctx, {})

    tip = opmod._local_tip(proj, "llm-core")
    monkeypatch.setattr(opmod, "_mirror_refs_safe",
                        lambda c, repo: {"llm-core": {"sha": tip}})

    res2 = opmod.op_mr_sync_all(ctx, {})
    assert res2["summary"]["notes_uploaded"] == 0
    assert res2["summary"]["notes_unchanged"] == 1
    assert res2["summary"]["branches_sent"] == 0
    assert res2["summary"]["branches_skipped"] == 1
    assert len(uploaded) == 1
    assert len(sent_calls) == 1


@pytest.mark.parametrize("raw", [[], [{"id": "system", "notes": [
    {"system": True, "body": "opened", "created_at": "2026-10-03"}]}]])
def test_mr_sync_all_transfers_mrs_without_reviewer_threads(tmp_path, monkeypatch, raw):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    ctx.client.gitlab_list_discussions = lambda iid, project="": raw

    res = opmod.op_mr_sync_all(ctx, {"notes_only": True})

    assert res["summary"]["notes_uploaded"] == 1
    assert [path for _, path, _ in uploaded] == ["mr-notes/mr-!24.md"]
    from lgm_core.ops_mr import _parse_mr_notes_head
    head = _parse_mr_notes_head(uploaded[0][2].decode("utf-8"))
    assert head["source_branch"] == "llm-core"
    assert head["unresolved"] == 0


def test_mr_sync_all_transfers_target_branch_in_notes(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    ctx.client.gitlab_list_mrs = lambda project="": [
        {"iid": 24, "title": "T", "source_branch": "llm-core",
         "target_branch": "release/next", "updated_at": "2026-10-03"}]

    opmod.op_mr_sync_all(ctx, {"notes_only": True})

    from lgm_core.ops_mr import _parse_mr_notes_head
    head = _parse_mr_notes_head(uploaded[0][2].decode("utf-8"))
    assert head["target_branch"] == "release/next"


def test_mcp_transfers_unreviewed_mr_to_home_and_returns_review(tmp_path, monkeypatch):
    import json
    import lgm_mcp

    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    ctx.client.gitlab_list_discussions = lambda iid, project="": []
    monkeypatch.setattr(lgm_mcp.Config, "from_env", lambda: ctx.config)
    monkeypatch.setattr(lgm_mcp, "MirrorClient", lambda **kwargs: ctx.client)
    ctx.client.file_sync_list = lambda repo: {"items": [
        {"id": str(i), "path": path, "mtime": 100 + i}
        for i, (_, path, _) in enumerate(uploaded)]}
    ctx.client.file_sync_fetch = lambda repo, item_id: uploaded[int(item_id)][2]
    monkeypatch.setenv("LGM_MACHINE_ROLE", "work")

    sent = lgm_mcp._call_tool("mr_sync_all", {"notes_only": True})
    assert not sent.get("isError")
    assert json.loads(sent["content"][0]["text"])["summary"]["notes_uploaded"] == 1

    def no_gitlab(project=""):
        raise LgmError("config", "GitLab not configured on HOME")

    monkeypatch.setenv("LGM_MACHINE_ROLE", "home")
    ctx.client.gitlab_list_mrs = no_gitlab
    listed = lgm_mcp._call_tool("mr_list", {"repo": "doctransformer"})
    assert not listed.get("isError")
    items = json.loads(listed["content"][0]["text"])["items"]
    assert [(item["iid"], item["source"]) for item in items] == [(24, "cache")]

    replied = lgm_mcp._call_tool("mr_replies_send", {
        "repo": "doctransformer", "iid": 24, "text": "## new\nReview finding"})
    assert not replied.get("isError")
    assert uploaded[-1][1] == "mr-replies/mr-!24.md"


def test_mr_sync_all_dry_run_touches_nothing(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)

    res = opmod.op_mr_sync_all(ctx, {"dry_run": True})
    assert res["dry_run"] is True
    assert uploaded == []
    assert sent_calls == []
    assert res["repos"][0]["mrs"][0]["notes"] == "dry-run"
    assert not (tmp_path / "state.json").exists()


def test_mr_sync_all_notes_only_param_registered():
    op = get_op("mr_sync_all")
    params = {p.name: p for p in op.params}
    assert params["notes_only"].type == "bool"
    assert params["notes_only"].default is False


def test_mr_sync_all_notes_only_skips_branches_but_keeps_notes(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    _seed_known_mr(opmod, tmp_path, 99, "Old MR", "old-branch", 1.0)

    res = opmod.op_mr_sync_all(ctx, {"notes_only": True})

    assert res["notes_only"] is True
    assert res["summary"]["notes_uploaded"] == 1
    assert res["summary"]["branches_sent"] == 0
    assert sent_calls == []
    marker = [p for (_, p, _) in uploaded if p == "mr-notes/mr-!99.closed.md"]
    assert marker, "closure detection must stay on with --notes-only"


def test_mr_sync_all_notes_only_still_uploads_notes(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)

    res = opmod.op_mr_sync_all(ctx, {"notes_only": True})

    assert res["success"] is True
    assert [p for (_, p, _) in uploaded] == ["mr-notes/mr-!24.md"]
    assert sent_calls == []


def test_mr_sync_all_requires_registry(tmp_path, monkeypatch):
    import lgm_core.ops_projects as opmod
    monkeypatch.setattr(opmod, "_REGISTRY_PATH", tmp_path / "reg.json")
    monkeypatch.setattr(opmod, "_STATE_PATH", tmp_path / "state.json")
    with pytest.raises(LgmError) as ei:
        opmod.op_mr_sync_all(_ctx(), {})
    assert "mr_projects_scan" in ei.value.message


def test_mr_sync_all_reports_gitlab_failure_per_repo(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    ctx.client.gitlab_list_mrs = lambda project="": (_ for _ in ()).throw(
        LgmError("network", "GitLab unreachable"))

    res = opmod.op_mr_sync_all(ctx, {})
    assert res["success"] is False
    assert "GitLab unreachable" in res["repos"][0]["error"]


def test_send_branches_raises_on_rejected_upload(tmp_path, monkeypatch):
    from lgm_core.ops_git import send_branches
    proj = _make_project(tmp_path)
    ctx = _ctx()

    def fake_sync_send(repo, bundle_bytes, local_branches=None):
        return {"success": False, "message": f"Repository '{repo}' not found", "repo": repo}

    ctx.client.sync_send = fake_sync_send
    with pytest.raises(LgmError) as ei:
        send_branches(ctx, "onyx-search", str(proj), ["master"])
    assert "not found" in ei.value.message


def test_mr_sync_all_negotiates_repo_before_send(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    negotiated = []
    ctx.client.sync_negotiate = lambda repo, commits, probe=False: (
        negotiated.append((repo, probe)), {"known": [], "created": True})[1]

    res = opmod.op_mr_sync_all(ctx, {})
    assert res["success"] is True
    assert negotiated == [("doctransformer", False)]
    assert sent_calls == [("doctransformer", ["llm-core"])]


def test_mr_sync_all_surfaces_branch_failure_in_summary(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)

    def failing(ctx, repo, project, branches, excludes, dry_run=False):
        raise LgmError("network", "Repository 'doctransformer' not found")

    monkeypatch.setattr(opmod, "send_branches", failing)
    res = opmod.op_mr_sync_all(ctx, {})
    assert res["success"] is False
    assert res["repos"][0].get("branches_error") == "Repository 'doctransformer' not found"
    assert any("doctransformer" in e for e in res["summary"]["errors"])


def test_mr_sync_all_collects_per_mr_note_failures(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    ctx.client.gitlab_list_discussions = lambda iid, project="": (
        _ for _ in ()).throw(LgmError(500, "boom"))

    res = opmod.op_mr_sync_all(ctx, {})
    assert res["success"] is False
    assert res["summary"]["failures"] == [
        {"repo": "doctransformer", "iid": 24, "stage": "notes", "error": "boom"}]
    assert any("MR !24" in e and "boom" in e
               for e in res["summary"]["errors"])


def test_mr_sync_all_blocked_carries_iids_for_diverged_branches(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    monkeypatch.setattr(opmod, "_fetch_mr_branches",
                        lambda proj, branches: ["llm-core"])

    res = opmod.op_mr_sync_all(ctx, {})
    assert res["repos"][0]["blocked"] == [
        {"iid": 24, "branch": "llm-core", "reason": "diverged from origin"}]
    assert res["blocked"] == res["repos"][0]["blocked"]
    assert "git pull origin llm-core" in res["repos"][0]["branch_warnings"][0]
    assert sent_calls == []


def _seed_known_mr(opmod, tmp_path, iid: int, title: str, branch: str, sent_at: float):
    opmod._save_state({"doctransformer": {
        str(iid): {"hash": "x", "sent_at": sent_at, "title": title, "branch": branch}}})


def test_mr_sync_all_marks_closed_mr_with_marker(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    _seed_known_mr(opmod, tmp_path, 99, "Old MR", "old-branch", 1.0)

    res = opmod.op_mr_sync_all(ctx, {})
    assert res["success"] is True
    assert res["summary"]["closed_marked"] == 1
    marker = [p for (r, p, _) in uploaded if p == "mr-notes/mr-!99.closed.md"]
    assert marker, f"marker not uploaded, uploaded={[p for (_, p, _) in uploaded]}"
    body = next(d for (r, p, d) in uploaded if p == "mr-notes/mr-!99.closed.md").decode("utf-8")
    assert body.startswith("<!-- lgm-mr-closed v1 -->")
    assert "# MR !99 — Old MR" in body
    assert "**Ветка:** `old-branch`" in body
    st = opmod._load_state()["doctransformer"]["99"]
    assert st.get("closed") is True and st.get("closed_sent_at")


def test_mr_sync_all_skips_closed_marker_within_ttl(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    import time as _t
    opmod._save_state({"doctransformer": {
        "99": {"closed": True, "closed_sent_at": _t.time(), "title": "Old MR", "branch": "old-branch"}}})

    res = opmod.op_mr_sync_all(ctx, {})
    assert [p for (_, p, _) in uploaded if p.endswith(".closed.md")] == []
    assert res["summary"]["closed_marked"] == 0


def test_mr_sync_all_clears_closed_state_on_reopen(tmp_path, monkeypatch):
    import time as _t
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    opmod._save_state({"doctransformer": {
        "24": {"closed": True, "closed_sent_at": _t.time(),
               "title": "Llm-core", "branch": "llm-core"}}})

    res = opmod.op_mr_sync_all(ctx, {})
    assert res["success"] is True
    st = opmod._load_state()["doctransformer"]["24"]
    assert "closed" not in st
    assert "closed_sent_at" not in st


def test_mr_sync_all_evicts_stale_closed_marker(tmp_path, monkeypatch):
    import time as _t
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    opmod._save_state({"doctransformer": {
        "99": {"closed": True, "closed_sent_at": _t.time() - 10 * 86400,
               "title": "Old MR", "branch": "old-branch"}}})

    res = opmod.op_mr_sync_all(ctx, {})
    assert [p for (_, p, _) in uploaded if p.endswith(".closed.md")] == []
    assert "99" not in opmod._load_state()["doctransformer"]
    closed = res["repos"][0]["closed"]
    assert {"iid": 99, "marker": "evicted"} in closed


def test_mr_list_from_postbox_marks_closed_entries(tmp_path, monkeypatch):
    import lgm_core.ops_mr as mrmod
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    notes_md = _raw_notes_markdown(24, "Llm-core", "llm-core")
    closed_md = ("<!-- lgm-mr-closed v1 -->\n# MR !99 — Old MR\n"
                 "- **Ветка:** `old-branch`\n- **Обнаружено:** 2026-10-15T10:00:00+03:00\n")
    store = {
        "a": ("mr-notes/mr-!24.md", notes_md.encode("utf-8")),
        "b": ("mr-notes/mr-!99.closed.md", closed_md.encode("utf-8")),
    }
    ctx.client.file_sync_list = lambda repo: {"items": [
        {"id": k, "path": p, "mtime": 10.0 + i} for i, (k, (p, _)) in enumerate(store.items())]}
    ctx.client.file_sync_fetch = lambda repo, item_id, *a, **k: store[item_id][1]
    ctx.client.gitlab_list_mrs = lambda project="": (_ for _ in ()).throw(LgmError("config", "no gitlab"))

    res = mrmod.op_mr_list(ctx, {"repo": "doctransformer"})
    by_iid = {i["iid"]: i for i in res["items"]}
    assert by_iid[24]["state"] == "open"
    assert by_iid[99]["state"] == "closed"
    assert by_iid[99]["title"] == "Old MR"
    assert by_iid[99]["source_branch"] == "old-branch"


def test_mr_notes_exposes_closed_state(tmp_path, monkeypatch):
    import lgm_core.ops_mr as mrmod
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)
    notes_md = _raw_notes_markdown(24, "Llm-core", "llm-core")
    closed_md = "<!-- lgm-mr-closed v1 -->\n# MR !24 — Llm-core\n- **Ветка:** `llm-core`\n"
    store = {
        "n": ("mr-notes/mr-!24.md", notes_md.encode("utf-8")),
        "c": ("mr-notes/mr-!24.closed.md", closed_md.encode("utf-8")),
    }
    ctx.client.file_sync_list = lambda repo: {"items": [
        {"id": k, "path": p, "mtime": 1.0} for k, (p, _) in store.items()]}
    ctx.client.file_sync_fetch = lambda repo, item_id, *a, **k: store[item_id][1]

    res = mrmod.op_mr_notes(ctx, {"repo": "doctransformer", "iid": 24})
    assert res["notes"][0]["state"] == "closed"


def _raw_notes_markdown(iid: int, title: str, branch: str) -> str:
    return (f"# MR !{iid} — {title}\n\n- **Ветка:** `{branch}`\n"
            f"- **Статус:** открыт · 0 нерешённых из 0 тредов\n"
            f"- **Обновлено:** 2026-10-13T13:57:34.729Z · **Источник:** GitLab MR !{iid}\n")



def _mock_gitlab_env(monkeypatch, **overrides):
    from lgm_core import config as config_mod
    env = {"GITLAB_URL": "https://gitlab.corp", "GITLAB_TOKEN": "tok"}
    env.update(overrides)
    monkeypatch.setattr(config_mod, "_ENV", env)
    for k in ("GITLAB_URL", "GITLAB_TOKEN", "GITLAB_PROJECT"):
        monkeypatch.delenv(k, raising=False)


def _capture_url(monkeypatch, c) -> dict:
    captured = {}
    monkeypatch.setattr(c, "_gitlab_get_paged",
                        lambda url, token: (captured.__setitem__("url", url), ([], ""))[1])
    return captured


def test_gitlab_list_mrs_project_override(monkeypatch):
    _mock_gitlab_env(monkeypatch, GITLAB_PROJECT="default/proj")
    c = MirrorClient(base_url="https://localhost:1")
    captured = _capture_url(monkeypatch, c)
    c.gitlab_list_mrs(project="dev/onyx/doctransformer")
    assert "projects/dev%2Fonyx%2Fdoctransformer/merge_requests" in captured["url"]
    assert "state=opened&order_by=updated_at&sort=desc&per_page=50" in captured["url"]


def test_gitlab_explicit_project_needs_no_global_default(monkeypatch):
    """Registry-driven sync passes project explicitly; no global GITLAB_PROJECT required."""
    _mock_gitlab_env(monkeypatch)
    c = MirrorClient(base_url="https://localhost:1")
    captured = _capture_url(monkeypatch, c)
    c.gitlab_list_mrs(project="dev/onyx/phonyx")
    assert "projects/dev%2Fonyx%2Fphonyx/merge_requests" in captured["url"]
    with pytest.raises(LgmError) as ei:
        c.gitlab_list_mrs()
    assert ei.value.code == "config"
    assert "GITLAB_PROJECT" in ei.value.message


def test_gitlab_list_discussions_pagination(monkeypatch):
    _mock_gitlab_env(monkeypatch, GITLAB_PROJECT="p")
    c = MirrorClient(base_url="https://localhost:1")
    pages = {1: [{"id": "a"}] * 100, 2: [{"id": "b"}]}

    def fake_get(url, token):
        page = int(url.split("&page=")[1])
        return pages.get(page, [])

    monkeypatch.setattr(c, "_gitlab_get", fake_get)
    out = c.gitlab_list_discussions(7)
    assert len(out) == 101
