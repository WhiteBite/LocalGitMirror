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
    monkeypatch.setattr(opmod, "_fetch_mr_branches", lambda proj, branches: None)
    monkeypatch.setattr(opmod, "_mirror_refs_safe", lambda c, repo: {})
    sent_calls = []

    def fake_send_branches(ctx, repo, project, branches, excludes, dry_run=False):
        sent_calls.append((repo, list(branches)))
        return {"bundle_size": 10}

    monkeypatch.setattr(opmod, "send_branches", fake_send_branches)
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


def test_mr_sync_all_dry_run_touches_nothing(tmp_path, monkeypatch):
    opmod, ctx, proj, sent_calls, uploaded = _prepare_sync(tmp_path, monkeypatch)

    res = opmod.op_mr_sync_all(ctx, {"dry_run": True})
    assert res["dry_run"] is True
    assert uploaded == []
    assert sent_calls == []
    assert res["repos"][0]["mrs"][0]["notes"] == "dry-run"
    assert not (tmp_path / "state.json").exists()


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



def test_gitlab_list_mrs_project_override(monkeypatch):
    c = MirrorClient(base_url="https://localhost:1")
    monkeypatch.setattr(c, "_gitlab_config",
                        lambda: ("https://gitlab.corp", "tok", "default/proj"))
    captured = {}
    monkeypatch.setattr(c, "_gitlab_get",
                        lambda url, token: (captured.__setitem__("url", url), [])[1])
    c.gitlab_list_mrs(project="dev/onyx/doctransformer")
    assert "projects/dev%2Fonyx%2Fdoctransformer/merge_requests" in captured["url"]
    assert "state=opened&order_by=updated_at&sort=desc&per_page=50" in captured["url"]


def test_gitlab_list_discussions_pagination(monkeypatch):
    c = MirrorClient(base_url="https://localhost:1")
    monkeypatch.setattr(c, "_gitlab_config",
                        lambda: ("https://gitlab.corp", "tok", "p"))
    pages = {1: [{"id": "a"}] * 100, 2: [{"id": "b"}]}

    def fake_get(url, token):
        page = int(url.split("&page=")[1])
        return pages.get(page, [])

    monkeypatch.setattr(c, "_gitlab_get", fake_get)
    out = c.gitlab_list_discussions(7)
    assert len(out) == 101
