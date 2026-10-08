"""Render mr-notes/mr-!N.md from GitLab discussions.

Format-compatible with the IntelliJ plugin's MrNotesWriter so the home-side
parsers, the reply precheck and the review agents see one canonical format
regardless of which side produced the notes.
"""
from __future__ import annotations

import subprocess
from pathlib import Path


def parse_discussions(raw: list) -> list[dict]:
    """GitLab /discussions JSON → [{id, resolved, notes: [...]}].

    A thread is resolved only when at least one note is resolvable and every
    resolvable note is resolved (mirrors the plugin's derivation). Position
    prefers new_path/new_line and falls back to old_path/old_line.
    """
    out: list[dict] = []
    for d in raw:
        if not isinstance(d, dict):
            continue
        notes: list[dict] = []
        any_resolvable = False
        resolved = True
        for n in d.get("notes") or []:
            if not isinstance(n, dict):
                continue
            resolvable = bool(n.get("resolvable"))
            is_resolved = bool(n.get("resolved"))
            if resolvable:
                any_resolvable = True
                if not is_resolved:
                    resolved = False
            pos = n.get("position") or {}
            new_line = pos.get("new_line")
            notes.append({
                "author": (n.get("author") or {}).get("name", "?"),
                "created_at": n.get("created_at", ""),
                "system": bool(n.get("system")),
                "body": n.get("body", ""),
                "file_path": pos.get("new_path") or pos.get("old_path"),
                "line": new_line if new_line is not None else pos.get("old_line"),
            })
        out.append({"id": str(d.get("id") or ""),
                    "resolved": any_resolvable and resolved, "notes": notes})
    return out


_HEADER_INSTRUCTIONS = [
    "> Инструкция агенту: прочитай нерешённые треды ниже и внеси правки.",
    "> Строка «Место: файл:строка» показывает, где смотреть. Решённые треды не трогай.",
    "> Ответить ревьюеру: mr_replies_send (repo, iid, text) секциями —",
    "> «## thread <ID треда>» + ответ (первая строка «resolve: yes» закроет тред);",
    "> «## new <файл>:<строка>» — новое замечание к коду; «## new» — общий комментарий.",
    "> Стиль: как у человека — только по делу (ошибка, плохое решение, проёб), 1-3 предложения;",
    "> длинно — только если нужен сценарий. Без похвалы, пересказа кода автору и «стоит отметить»; не уверен — короткий вопрос.",
]


def _anchor_file(d: dict) -> str | None:
    for n in d["notes"]:
        if n["file_path"] is not None:
            return n["file_path"]
    return None


def _anchor_line(d: dict) -> int | None:
    for n in d["notes"]:
        if n["line"] is not None:
            return n["line"]
    return None


def _read_file_lines(project_dir: Path | None, branch: str,
                     rel_path: str) -> list[str] | None:
    if project_dir is None or not project_dir.is_dir():
        return None
    if branch:
        proc = subprocess.run(
            ["git", "-C", str(project_dir), "show", f"refs/heads/{branch}:{rel_path}"],
            capture_output=True, timeout=30,
        )
        if proc.returncode == 0:
            return proc.stdout.decode("utf-8", errors="replace").splitlines()
    try:
        f = project_dir / rel_path
        if f.is_file():
            return f.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None
    return None


def render_markdown(iid: int, title: str, source_branch: str, updated_at: str,
                    unresolved: int, total_threads: int, discussions: list[dict],
                    project_dir: Path | None = None, branch: str = "") -> str:
    lines: list[str] = []
    a = lines.append
    a(f"<!-- Сгенерировано плагином DocCache из обсуждений GitLab MR !{iid}. Не редактируйте вручную. -->")
    a(f"# MR !{iid} — {title}")
    a("")
    lines.extend(_HEADER_INSTRUCTIONS)
    a("")
    a(f"- **Ветка:** `{source_branch}`")
    a(f"- **Статус:** открыт · **{unresolved} нерешённых** из {total_threads} тредов")
    a(f"- **Обновлено:** {updated_at} · **Источник:** GitLab MR !{iid}")
    a("")
    a("---")
    a("")

    def has_user_notes(d: dict) -> bool:
        return any(not n["system"] for n in d["notes"])

    unresolved_threads = [d for d in discussions if not d["resolved"] and has_user_notes(d)]
    resolved_threads = [d for d in discussions if d["resolved"] and has_user_notes(d)]
    system_notes = [n for d in discussions for n in d["notes"] if n["system"]]

    for idx, d in enumerate(unresolved_threads, 1):
        _render_thread(a, idx, d, unresolved=True,
                       project_dir=project_dir, branch=branch)
    for idx, d in enumerate(resolved_threads, 1):
        _render_thread(a, idx, d, unresolved=False,
                       project_dir=project_dir, branch=branch)

    if system_notes:
        a(f"## ✓ системные события (свёрнуто · {len(system_notes)})")
        for n in system_notes:
            a(f"- {n['body']} · {n['created_at']}")
    return "\n".join(lines) + "\n"


def _render_thread(a, n: int, d: dict, unresolved: bool,
                   project_dir: Path | None, branch: str) -> None:
    notes = [x for x in d["notes"] if not x["system"]]
    if not notes:
        return
    first, replies = notes[0], notes[1:]

    if unresolved:
        a(f"## ⚠ НЕ РЕШЕНО · тред {n}")
    else:
        closed_by = notes[-1]["author"]
        suffix = f" · закрыл: {closed_by}" if closed_by else ""
        a(f"## ✓ решено · тред {n}{suffix}")
    if d["id"]:
        a(f"<!-- lgm-thread: {d['id']} -->")
        a(f"**ID треда:** `{d['id']}`")
    a("")

    anchor_file = _anchor_file(d)
    anchor_line = _anchor_line(d)
    if anchor_file is None:
        a("**Место:** общий комментарий (не привязан к коду)")
    else:
        line = anchor_line if anchor_line is not None else "?"
        a(f"**Место:** `{anchor_file}:{line}`")
    a("")

    if anchor_file is not None and project_dir is not None and anchor_line is not None:
        _render_code_block(a, project_dir, branch, anchor_file, anchor_line)
        a("")

    a(f"**{first['author']}** _(reviewer · {first['created_at']})_:")
    a(first["body"])
    a("")

    for reply in replies:
        a(f"&nbsp;&nbsp;↳ **{reply['author']}** _({reply['created_at']})_:")
        a(reply["body"])
        a("")

    a("---")
    a("")


def _render_code_block(a, project_dir: Path, branch: str,
                       anchor_file: str, anchor_line: int) -> None:
    lines = _read_file_lines(project_dir, branch, anchor_file)
    if not lines:
        return
    ext = anchor_file.rsplit(".", 1)[-1] if "." in anchor_file else ""
    start = max(anchor_line - 2, 1)
    end = min(anchor_line + 2, len(lines))
    a(f"```{ext}")
    for i in range(start, end + 1):
        text = lines[i - 1] if i - 1 < len(lines) else ""
        prefix = ">" if i == anchor_line else " "
        a(f"{prefix}{i}: {text}")
    a("```")
