"""Bounded reader tests: real temp git repos imported through the real store
(task 2/5 patterns, helpers reused by import), byte-exact page reassembly,
budget enforcement via mcp_result_bytes, typed cursor rejections and the
plan's failure scenarios (rename limit, non-UTF-8, tampered cursors)."""
import base64
import json
import subprocess
import sys
import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import mr_review_models as m
from lgm_core import ops_review
from lgm_core.mr_review_models import MrReviewError, mcp_result_bytes
from lgm_core.mr_review_reader import MrReviewReader
from lgm_core.mr_review_store import MrReviewStore, import_snapshot
from test_mr_review_store import _git, _manifest
from test_mr_review_transfer import _client, _env, _prepare

EVIDENCE_DIR = Path(__file__).resolve().parent.parent / ".omo" / "evidence" / "mr-review-workspace"


def _gitb(repo: Path, *args: str, input: bytes = b"") -> bytes:
    proc = subprocess.run(["git", "-C", str(repo), *args], input=input,
                          capture_output=True, timeout=120)
    assert proc.returncode == 0, proc.stderr
    return proc.stdout


def _init_repo(repo: Path) -> None:
    repo.mkdir(parents=True)
    _git(repo, "init", "-q", "-b", "master")
    _git(repo, "config", "user.email", "t@t")
    _git(repo, "config", "user.name", "t")


def _hash_blob(repo: Path, data: bytes) -> str:
    tmp = repo.parent / f".blob-{uuid.uuid4().hex}.tmp"
    tmp.write_bytes(data)
    sha = _git(repo, "hash-object", "-w", str(tmp)).stdout.strip()
    tmp.unlink()
    return sha


def _fixture(tmp: Path, repo: Path, manifest: dict, *branches: str) -> SimpleNamespace:
    bundle = tmp / f"code-{uuid.uuid4().hex}.bundle"
    _git(repo, "bundle", "create", str(bundle), *branches)
    import_snapshot(manifest, bundle, home=tmp / "reviews")
    store = MrReviewStore(manifest["gitlab_url"], manifest["target_project_id"],
                          manifest["repo"], home=tmp / "reviews")
    return SimpleNamespace(repo=repo, manifest=manifest,
                           reader=MrReviewReader(store.git, manifest),
                           home=tmp / "reviews")


def _with_discussions(manifest: dict) -> dict:
    refs = dict(manifest["gitlab_diff_refs"])
    manifest = dict(manifest)
    manifest["discussions"] = [
        {"discussion_id": "b-thread", "notes": [
            {"note_id": 2, "body": "ответ автора", "author": "Ivan",
             "created_at": "2026-10-02T13:20:00Z", "system": False,
             "resolvable": False, "resolved": False, "position": None},
            {"note_id": 1, "body": "первый комментарий", "author": "Anna",
             "created_at": "2026-10-02T13:10:48Z", "system": False,
             "resolvable": True, "resolved": False,
             "position": {"old_path": "src/app.py", "new_path": "src/app.py",
                          "old_line": 1, "new_line": 1, "side": "new", **refs}},
        ]},
        {"discussion_id": "a-thread", "notes": [
            {"note_id": 3, "body": "огромный комментарий " * 300, "author": "Bob",
             "created_at": "2026-10-03T10:00:00Z", "system": False,
             "resolvable": True, "resolved": True,
             "position": {"old_path": "deleted.txt", "new_path": None,
                          "old_line": 1, "new_line": None, "side": "old", **refs}},
        ]},
    ]
    manifest["snapshot_id"] = m.compute_snapshot_id(manifest)
    return manifest


def _rich_repo(tmp: Path) -> Path:
    repo = tmp / "rich"
    _init_repo(repo)
    (repo / "src").mkdir()
    (repo / "unicode").mkdir()
    (repo / "src" / "app.py").write_text("print('v1')\nline2\n", encoding="utf-8")
    (repo / "src" / "util.py").write_text("def util():\n    return 1\n", encoding="utf-8")
    (repo / "old_name.txt").write_text("alpha\nbeta\ngamma\n", encoding="utf-8")
    (repo / "deleted.txt").write_text("to be deleted\n", encoding="utf-8")
    (repo / "unicode" / "данные.txt").write_text("старые данные\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    (repo / "src" / "app.py").write_text("print('v2')\nline2\nline3\n", encoding="utf-8")
    (repo / "src" / "util.py").unlink()
    (repo / "src" / "util_renamed.py").write_text("def util():\n    return 1\n", encoding="utf-8")
    (repo / "old_name.txt").unlink()
    (repo / "new_name.txt").write_text("alpha\nbeta\ngamma\ndelta\n", encoding="utf-8")
    (repo / "deleted.txt").unlink()
    (repo / "empty.txt").write_text("", encoding="utf-8")
    (repo / "binary.bin").write_bytes(b"bin\x00ary\x00data\n")
    (repo / "cp1251.txt").write_bytes("текст на cp1251\n".encode("cp1251"))
    (repo / "unicode" / "данные.txt").write_text("новые данные\nещё строка\n", encoding="utf-8")
    (repo / "unicode" / "файл.txt").write_text("содержимое файла\n", encoding="utf-8")
    (repo / "test_data.json").write_text('{"key": "value"}\n', encoding="utf-8")
    (repo / "generated").mkdir()
    (repo / "generated" / "x.min.js").write_text("console.log(1)\n", encoding="utf-8")
    _git(repo, "add", "-A")
    link_sha = _hash_blob(repo, b"target.txt\n")
    _git(repo, "update-index", "--add", "--cacheinfo", f"120000,{link_sha},link.txt")
    master_sha = _git(repo, "rev-parse", "master").stdout.strip()
    _git(repo, "update-index", "--add", "--cacheinfo", f"160000,{master_sha},submodule")
    _git(repo, "commit", "-qm", "feature")
    _git(repo, "checkout", "-q", "master")
    return repo


def _big_repo(tmp: Path) -> Path:
    repo = tmp / "big"
    _init_repo(repo)
    (repo / "seed.txt").write_text("seed\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    files = repo / "files"
    files.mkdir()
    for i in range(1000):
        (files / f"f{i:04d}.py").write_text(f"# file {i}\nvalue = {i}\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "1000 files")
    _git(repo, "checkout", "-q", "master")
    return repo


def _huge_repo(tmp: Path) -> Path:
    repo = tmp / "huge"
    _init_repo(repo)
    (repo / "seed.txt").write_text("seed\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    line = " ".join(f"word{i}" for i in range(200000))
    (repo / "vocab.txt").write_text(line, encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "huge line")
    _git(repo, "checkout", "-q", "master")
    return repo


def _limit_repo(tmp: Path) -> Path:
    repo = tmp / "limit"
    _init_repo(repo)
    for i in range(1050):
        (repo / f"d{i:04d}.txt").write_text(f"content {i}\n" * 3, encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    for i in range(1050):
        (repo / f"d{i:04d}.txt").unlink()
    for i in range(1050):
        (repo / f"a{i:04d}.txt").write_text(f"content {i}\n" * 3 + "tail\n", encoding="utf-8")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-qm", "swap")
    _git(repo, "checkout", "-q", "master")
    return repo


def _plumbing_repo(tmp: Path) -> Path:
    repo = tmp / "plumb"
    _init_repo(repo)
    (repo / "keep.txt").write_text("base\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    keep2 = _hash_blob(repo, b"changed\n")
    nonutf_content = _hash_blob(repo, b"content\n")
    huge_content = _hash_blob(repo, b"hi\n")
    nonutf_path = "файл".encode("cp1251")
    huge_path = b"x" * 4000 + b".txt"
    tree_in = (b"100644 blob " + keep2.encode() + b"\tkeep.txt\n"
               + b"100644 blob " + nonutf_content.encode() + b"\t" + nonutf_path + b"\n"
               + b"100644 blob " + huge_content.encode() + b"\t" + huge_path + b"\n")
    tree = _gitb(repo, "mktree", input=tree_in).decode().strip()
    master = _git(repo, "rev-parse", "master").stdout.strip()
    commit = _gitb(repo, "commit-tree", tree, "-p", master, "-m", "plumbing").decode().strip()
    _git(repo, "update-ref", "refs/heads/plumbing", commit)
    return repo


@pytest.fixture(scope="module")
def rich(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("rich")
    repo = _rich_repo(tmp)
    manifest = _with_discussions(_manifest(repo))
    return _fixture(tmp, repo, manifest, "feature", "master")


@pytest.fixture(scope="module")
def big(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("big")
    repo = _big_repo(tmp)
    manifest = _manifest(repo)
    return _fixture(tmp, repo, manifest, "feature", "master")


@pytest.fixture(scope="module")
def huge(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("huge")
    repo = _huge_repo(tmp)
    manifest = _manifest(repo)
    return _fixture(tmp, repo, manifest, "feature", "master")


@pytest.fixture(scope="module")
def limit(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("limit")
    repo = _limit_repo(tmp)
    manifest = _manifest(repo)
    return _fixture(tmp, repo, manifest, "feature", "master")


@pytest.fixture(scope="module")
def plumb(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("plumb")
    repo = _plumbing_repo(tmp)
    manifest = _manifest(repo, source_branch="plumbing")
    return _fixture(tmp, repo, manifest, "plumbing", "master")


def _walk(reader: MrReviewReader, *, view: str, budget: int = 24000,
          max_pages: int = 5000, **kwargs) -> list[dict]:
    pages = []
    cursor = ""
    for _ in range(max_pages):
        page = reader.read_page(view=view, cursor=cursor, budget_bytes=budget, **kwargs)
        assert mcp_result_bytes(page) <= budget, "page exceeds the requested budget"
        pages.append(page)
        cursor = page["next_cursor"]
        if page["complete"]:
            assert cursor is None
            return pages
        assert cursor
    pytest.fail(f"reader did not terminate within {max_pages} pages")


def _walk_inventory(reader: MrReviewReader, budget: int = 24000,
                    max_pages: int = 5000) -> list[dict]:
    pages = []
    cursor = ""
    for _ in range(max_pages):
        page = reader.inventory_page(cursor=cursor, budget_bytes=budget)
        assert mcp_result_bytes(page) <= budget
        pages.append(page)
        cursor = page["next_cursor"]
        if page["complete"]:
            return pages
        assert cursor
    pytest.fail(f"inventory did not terminate within {max_pages} pages")


def _chunk_bytes(chunk: dict) -> bytes:
    if chunk["encoding"] == "utf-8":
        return chunk["content"].encode("utf-8")
    return base64.b64decode(chunk["content"])


def _diff_bytes(pages: list[dict]) -> bytes:
    return b"".join(_chunk_bytes(chunk)
                    for page in pages for chunk in page["chunks"])


def _expected_diff(objects_git: Path, base: str, head: str,
                   context_lines: int = 20) -> bytes:
    proc = subprocess.run(
        ["git", "-C", str(objects_git), "-c", "core.quotepath=true", "diff",
         "--no-color", "--no-ext-diff", "--no-textconv", "--full-index",
         "--diff-algorithm=myers", "-M", "-l1000", f"-U{context_lines}", base, head],
        capture_output=True, timeout=120)
    assert proc.returncode == 0, proc.stderr
    return proc.stdout


def _entry(pages: list[dict], path: str) -> dict:
    for page in pages:
        for item in page["entries"]:
            if item["path"] == path:
                return item
    raise AssertionError(f"inventory entry {path!r} not found")


def _file_entry(pages: list[dict], path: str) -> dict:
    for page in pages:
        for item in page["files"]:
            if item["path"] == path:
                return item
    raise AssertionError(f"file entry {path!r} not found")


def _file_content(pages: list[dict], path: str) -> bytes:
    out = b""
    for page in pages:
        for item in page["files"]:
            if item["path"] == path:
                out += b"".join(_chunk_bytes(c) for c in item["chunks"])
    return out


def test_inventory_entries_statuses_renames_tags(rich):
    pages = _walk_inventory(rich.reader, budget=24000)
    assert pages[-1]["complete"] is True
    totals = pages[-1]["totals"]
    assert totals == {"files": 13, "added": 8, "modified": 2,
                      "deleted": 1, "renamed": 2}
    paths = [e["path"] for p in pages for e in p["entries"]]
    assert len(paths) == len(set(paths)) == 13

    exact = _entry(pages, "src/util_renamed.py")
    assert exact["status"] == "renamed"
    assert exact["rename"] == {"kind": "exact", "similarity": 100}
    assert exact["old"]["oid"] == exact["new"]["oid"]
    assert exact["old_path"] == "src/util.py"
    assert (exact["lines_added"], exact["lines_deleted"]) == (0, 0)

    changed = _entry(pages, "new_name.txt")
    assert changed["status"] == "renamed"
    assert changed["rename"]["kind"] == "changed"
    assert changed["rename"]["similarity"] < 100
    assert changed["old_path"] == "old_name.txt"
    assert (changed["lines_added"], changed["lines_deleted"]) == (1, 0)

    modified = _entry(pages, "src/app.py")
    assert modified["status"] == "modified"
    assert modified["module"] == "src"
    assert (modified["lines_added"], modified["lines_deleted"]) == (2, 1)
    assert modified["old"]["oid"] != modified["new"]["oid"]

    deleted = _entry(pages, "deleted.txt")
    assert deleted["status"] == "deleted"
    assert deleted["new"] is None
    assert deleted["old"] is not None

    empty = _entry(pages, "empty.txt")
    assert empty["status"] == "added"
    assert empty["new"]["size"] == 0
    assert empty["old"] is None

    binary = _entry(pages, "binary.bin")
    assert {"tag": "binary", "reason": "NUL byte within the first 8000 bytes"} \
        in binary["tags"]

    cp1251 = _entry(pages, "cp1251.txt")
    assert any(t["tag"] == "non-utf8-content" for t in cp1251["tags"])

    link = _entry(pages, "link.txt")
    assert any(t["tag"] == "symlink" for t in link["tags"])
    assert link["new"]["mode"] == "120000"

    sub = _entry(pages, "submodule")
    assert any(t["tag"] == "gitlink" for t in sub["tags"])
    assert sub["new"]["mode"] == "160000"
    assert sub["new"]["size"] is None

    test_data = _entry(pages, "test_data.json")
    tags = {t["tag"] for t in test_data["tags"]}
    assert {"test", "data"} <= tags

    gen = _entry(pages, "generated/x.min.js")
    assert any(t["tag"] == "generated" for t in gen["tags"])
    assert gen["module"] == "generated"

    assert _entry(pages, "new_name.txt")["module"] == ""


def test_inventory_budget_boundaries(rich):
    for budget in (4096, 48000):
        pages = _walk_inventory(rich.reader, budget=budget)
        for page in pages:
            assert mcp_result_bytes(page) <= budget
        assert pages[-1]["complete"] is True
        assert pages[-1]["totals"]["files"] == 13


def test_inventory_deterministic(rich):
    a = rich.reader.inventory_page(budget_bytes=4096)
    b = rich.reader.inventory_page(budget_bytes=4096)
    assert a == b


def test_diff_view_reassembly_byte_exact(rich):
    expected = _expected_diff(
        rich.reader._git.git_dir, rich.manifest["comparison_base_sha"],
        rich.manifest["gitlab_diff_refs"]["head_sha"], 20)
    for budget in (4096, 24000, 48000):
        pages = _walk(rich.reader, view="diff", budget=budget)
        assert _diff_bytes(pages) == expected
        identities = [(c["path"], c["byte_offset"])
                      for p in pages for c in p["chunks"]]
        assert len(identities) == len(set(identities))
        assert pages[-1]["totals"]["bytes"] == len(expected)
        assert pages[-1]["totals"]["files"] == 13
        assert pages[-1]["complete"] is True
        assert all(not p["complete"] for p in pages[:-1]) or len(pages) == 1


def test_diff_view_small_mr_single_call(rich):
    page = rich.reader.read_page(view="diff", budget_bytes=48000)
    assert page["complete"] is True
    assert page["next_cursor"] is None
    assert page["totals"]["files"] == 13


def test_diff_chunks_carry_oid_and_coords(rich):
    pages = _walk(rich.reader, view="diff", budget=24000)
    inventory = _walk_inventory(rich.reader, budget=24000)
    for page in pages:
        for chunk in page["chunks"]:
            for key in ("path", "old_path", "new_path", "old_blob", "new_blob",
                        "old_line", "new_line", "line_byte_offset",
                        "byte_offset", "content", "encoding", "continued"):
                assert key in chunk
    app = _entry(inventory, "src/app.py")
    app_chunks = [c for p in pages for c in p["chunks"] if c["path"] == "src/app.py"]
    assert app_chunks
    assert app_chunks[0]["old_blob"] == app["old"]["oid"]
    assert app_chunks[0]["new_blob"] == app["new"]["oid"]
    assert app_chunks[0]["old_line"] is None
    assert app_chunks[0]["new_line"] is None
    assert app_chunks[0]["line_byte_offset"] == 0


def test_diff_view_non_utf8_content_is_base64_not_mojibake(rich):
    pages = _walk(rich.reader, view="diff", budget=24000)
    cp_chunks = [c for p in pages for c in p["chunks"]
                 if c["path"] == "cp1251.txt"]
    assert cp_chunks
    assert all(c["encoding"] == "base64" for c in cp_chunks)
    assert any("non-UTF-8" in lim for lim in pages[0]["limitations"])


def test_diff_view_path_selection_and_missing(rich):
    pages = _walk(rich.reader, view="diff", paths=["src/app.py", "nope.txt"],
                  budget=24000)
    assert pages[-1]["complete"] is True
    assert pages[0]["missing_paths"] == ["nope.txt"]
    paths = {c["path"] for p in pages for c in p["chunks"]}
    assert paths == {"src/app.py"}


def test_diff_view_group_selection(rich):
    pages = _walk(rich.reader, view="diff", group="src", budget=24000)
    paths = {c["path"] for p in pages for c in p["chunks"]}
    assert paths == {"src/app.py", "src/util_renamed.py"}


def test_file_view_sides_and_missing_vs_empty(rich):
    pages = _walk(rich.reader, view="file", paths=["src/app.py"], side="new")
    assert _file_content(pages, "src/app.py") == b"print('v2')\nline2\nline3\n"

    pages = _walk(rich.reader, view="file", paths=["src/app.py"], side="old")
    assert _file_content(pages, "src/app.py") == b"print('v1')\nline2\n"

    pages = _walk(rich.reader, view="file", paths=["deleted.txt"], side="new")
    item = _file_entry(pages, "deleted.txt")
    assert item["missing"] is True
    assert item["chunks"] == []

    pages = _walk(rich.reader, view="file", paths=["deleted.txt"], side="old")
    assert _file_content(pages, "deleted.txt") == b"to be deleted\n"

    pages = _walk(rich.reader, view="file", paths=["empty.txt"], side="new")
    item = _file_entry(pages, "empty.txt")
    assert item["missing"] is False
    assert item["size"] == 0
    assert item["chunks"] == []
    assert item["oid"] == "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"

    pages = _walk(rich.reader, view="file", paths=["deleted.txt"], side="target")
    assert _file_content(pages, "deleted.txt") == b"to be deleted\n"

    pages = _walk(rich.reader, view="file", paths=["empty.txt"], side="target")
    assert _file_entry(pages, "empty.txt")["missing"] is True


def test_file_view_symlink_gitlink_binary_nonutf8_unicode(rich):
    pages = _walk(rich.reader, view="file", paths=["link.txt"], side="new")
    item = _file_entry(pages, "link.txt")
    assert item["kind"] == "symlink"
    assert item["mode"] == "120000"
    assert _file_content(pages, "link.txt") == b"target.txt\n"

    pages = _walk(rich.reader, view="file", paths=["submodule"], side="new")
    item = _file_entry(pages, "submodule")
    assert item["kind"] == "gitlink"
    assert item["chunks"] == []
    assert item["size"] is None
    assert "gitlink" in item["limitation"]

    pages = _walk(rich.reader, view="file", paths=["binary.bin"], side="new")
    item = _file_entry(pages, "binary.bin")
    assert item["chunks"] == []
    assert "binary" in item["limitation"]

    pages = _walk(rich.reader, view="file", paths=["cp1251.txt"], side="new")
    item = _file_entry(pages, "cp1251.txt")
    assert item["chunks"] == []
    assert "UTF-8" in item["limitation"]

    pages = _walk(rich.reader, view="file", paths=["unicode/файл.txt"], side="new")
    assert _file_content(pages, "unicode/файл.txt") == "содержимое файла\n".encode()


def test_file_view_huge_line_reassembly(huge):
    inventory = _walk_inventory(huge.reader, budget=24000)
    oid = _entry(inventory, "vocab.txt")["new"]["oid"]
    expected = _gitb(huge.reader._git.git_dir, "cat-file", "blob", oid)
    pages = _walk(huge.reader, view="file", paths=["vocab.txt"], side="new",
                  budget=48000)
    chunks = [c for p in pages for i in p["files"] for c in i["chunks"]]
    assert chunks
    offsets = [c["byte_offset"] for c in chunks]
    assert offsets == sorted(offsets)
    assert offsets[0] == 0
    for prev, cur in zip(chunks, chunks[1:]):
        assert prev["byte_offset"] + len(_chunk_bytes(prev)) == cur["byte_offset"]
    assert chunks[-1]["byte_offset"] + len(_chunk_bytes(chunks[-1])) == len(expected)
    assert b"".join(_chunk_bytes(c) for c in chunks) == expected
    assert all(c["line"] == 1 for c in chunks)
    assert chunks[0]["line_byte_offset"] == 0
    assert any(c["line_byte_offset"] > 0 for c in chunks[1:])
    assert pages[-1]["totals"]["bytes"] == len(expected)


def test_discussions_view_sorted_positions_body_split(rich):
    pages = _walk(rich.reader, view="discussions", budget=4096)
    notes = [n for p in pages for n in p["notes"]]
    frags = [f for p in pages for f in p["note_fragments"]]
    assert [n["note_id"] for n in notes] == [3, 1, 2]
    assert [n["discussion_id"] for n in notes] == ["a-thread", "b-thread", "b-thread"]
    assert notes[0]["position"]["old_path"] == "deleted.txt"
    assert notes[0]["position"]["side"] == "old"
    assert notes[0]["position"]["old_line"] == 1
    assert notes[1]["position"]["new_line"] == 1
    assert notes[1]["position"]["new_path"] == "src/app.py"
    assert notes[2]["position"] is None
    assert pages[-1]["totals"] == {"discussions": 2, "notes": 3}
    assert pages[-1]["complete"] is True
    body = notes[0]["body"] + "".join(
        f["text"] for f in sorted(frags, key=lambda f: f["part"]))
    assert body == "огромный комментарий " * 300
    assert len(frags) >= 2


def test_rename_limit_flag_and_full_inventory(limit):
    page = limit.reader.inventory_page(budget_bytes=24000)
    assert page["rename_detection_limited"] is True
    assert page["totals"] == {"files": 2100, "added": 1050, "modified": 0,
                              "deleted": 1050, "renamed": 0}
    assert page["complete"] is False
    assert page["next_cursor"]
    second = limit.reader.inventory_page(cursor=page["next_cursor"],
                                          budget_bytes=24000)
    assert second["range"]["from_entry"] == page["range"]["to_entry"]
    assert second["entries"]


def test_non_utf8_path_percent_encoded_and_addressable(plumb):
    pages = _walk_inventory(plumb.reader, budget=24000)
    nonutf = _entry(pages, "%F4%E0%E9%EB")
    assert nonutf["status"] == "added"
    assert any(t["tag"] == "non-utf8-path" for t in nonutf["tags"])
    assert nonutf["new"]["size"] == len(b"content\n")
    file_pages = _walk(plumb.reader, view="file", paths=["%F4%E0%E9%EB"],
                       side="new", budget=24000)
    assert _file_content(file_pages, "%F4%E0%E9%EB") == b"content\n"


def test_huge_path_inventory_continuation(plumb):
    full_path = "x" * 4000 + ".txt"
    pages = _walk_inventory(plumb.reader, budget=4096)
    entries = [e for p in pages for e in p["entries"]]
    huge = [e for e in entries if e["path"].startswith("x" * 100)][0]
    frags = [f for p in pages for f in p["path_fragments"]
             if f["field"] == "path" and f["entry_index"] >= entries.index(huge)]
    path_frags = sorted([f for f in frags if f["field"] == "path"],
                        key=lambda f: f["part"])
    reassembled = huge["path"] + "".join(f["text"] for f in path_frags)
    assert reassembled == full_path
    assert [f["part"] for f in path_frags] == list(range(1, len(path_frags) + 1))
    assert pages[-1]["complete"] is True
    assert pages[-1]["totals"]["files"] == 3


def test_plumbing_diff_reassembly_with_split_header_line(plumb):
    expected = _expected_diff(
        plumb.reader._git.git_dir, plumb.manifest["comparison_base_sha"],
        plumb.manifest["gitlab_diff_refs"]["head_sha"], 20)
    pages = _walk(plumb.reader, view="diff", budget=4096)
    assert _diff_bytes(pages) == expected
    assert len(pages) >= 2
    huge_chunks = [c for p in pages for c in p["chunks"]
                   if c["path"].startswith("x" * 100)]
    assert len(huge_chunks) >= 2
    assert any(c["line_byte_offset"] > 0 for c in huge_chunks[1:])


def test_tampered_cursor_rejected(plumb):
    page = plumb.reader.read_page(view="diff", budget_bytes=4096)
    cursor = page["next_cursor"]
    assert cursor
    flipped = ("A" if cursor[0] != "A" else "B") + cursor[1:]
    with pytest.raises(MrReviewError) as excinfo:
        plumb.reader.read_page(view="diff", cursor=flipped, budget_bytes=4096)
    assert excinfo.value.code in ("invalid-schema", "hash-mismatch")
    payload = json.loads(base64.urlsafe_b64decode(cursor))
    payload["s"]["f"] = 99
    forged = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode()
    with pytest.raises(MrReviewError) as excinfo:
        plumb.reader.read_page(view="diff", cursor=forged, budget_bytes=4096)
    assert excinfo.value.code == "hash-mismatch"


def test_cursor_from_other_snapshot_rejected(rich, plumb):
    page = plumb.reader.read_page(view="diff", budget_bytes=4096)
    cursor = page["next_cursor"]
    assert cursor
    with pytest.raises(MrReviewError) as excinfo:
        rich.reader.read_page(view="diff", cursor=cursor, budget_bytes=4096)
    assert excinfo.value.code == "invalid-schema"


def test_cursor_from_other_query_rejected(plumb):
    page = plumb.reader.read_page(view="diff", budget_bytes=4096)
    cursor = page["next_cursor"]
    assert cursor
    with pytest.raises(MrReviewError) as excinfo:
        plumb.reader.read_page(view="diff", paths=["keep.txt"], cursor=cursor,
                               budget_bytes=4096)
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        plumb.reader.read_page(view="file", cursor=cursor, budget_bytes=4096)
    assert excinfo.value.code == "invalid-schema"


def test_budget_validation(rich):
    for bad in (4095, 48001, 0, -1, "x", None, 24000.5):
        with pytest.raises(MrReviewError) as excinfo:
            rich.reader.read_page(view="diff", budget_bytes=bad)
        assert excinfo.value.code == "invalid-budget"
    for good in (4096, 48000):
        page = rich.reader.read_page(view="diff", budget_bytes=good)
        assert mcp_result_bytes(page) <= good


def test_read_page_param_validation(rich):
    with pytest.raises(MrReviewError) as excinfo:
        rich.reader.read_page(view="search")
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        rich.reader.read_page(view="file", side="both")
    assert excinfo.value.code == "invalid-side"
    with pytest.raises(MrReviewError) as excinfo:
        rich.reader.read_page(view="file", paths=["a", 1])
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        rich.reader.read_page(view="file", paths=["a"], group="src")
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        rich.reader.read_page(view="diff", context_lines=-1)
    assert excinfo.value.code == "invalid-schema"


def test_reads_leave_repo_untouched(rich):
    def state():
        return (_git(rich.repo, "rev-parse", "HEAD").stdout,
                _git(rich.repo, "status", "--porcelain").stdout,
                _git(rich.repo, "for-each-ref").stdout)
    before = state()
    _walk(rich.reader, view="diff", budget=4096)
    _walk_inventory(rich.reader, budget=4096)
    _walk(rich.reader, view="file", budget=4096)
    _walk(rich.reader, view="discussions", budget=4096)
    assert state() == before


def test_open_returns_first_inventory_page(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    _prepare(client, checkout, tmp_path / "work")
    home = tmp_path / "home"
    result = ops_review.mr_review_open(client, "phonyx", 30, home=home)
    assert result["status"] == "imported"
    inv = result["inventory"]
    assert inv["totals"]["files"] == 1
    assert inv["complete"] is True
    assert inv["entries"][0]["path"] == "F.txt"
    assert inv["entries"][0]["status"] == "modified"
    assert mcp_result_bytes(result) <= 24000


def test_open_cursor_pages_inventory(big, monkeypatch):
    client = _client(monkeypatch)
    page1 = big.reader.inventory_page(budget_bytes=4096)
    assert page1["complete"] is False
    result = ops_review.mr_review_open(client, "phonyx", 30,
                                        cursor=page1["next_cursor"],
                                        budget_bytes=4096,
                                        home=big.home)
    assert result["status"] == "paged"
    assert result["snapshot_id"] == big.manifest["snapshot_id"]
    assert result["inventory"]["entries"]
    assert result["inventory"]["range"]["from_entry"] == page1["range"]["to_entry"]
    assert mcp_result_bytes(result) <= 4096
    with pytest.raises(MrReviewError) as excinfo:
        ops_review.mr_review_open(client, "phonyx", 30,
                                  cursor=page1["next_cursor"],
                                  snapshot_id=big.manifest["snapshot_id"],
                                  home=big.home)
    assert excinfo.value.code == "invalid-schema"


def test_mr_review_read_service(rich):
    page = ops_review.mr_review_read("phonyx", rich.manifest["snapshot_id"],
                                     "diff", home=rich.home)
    assert page["view"] == "diff"
    assert page["snapshot_id"] == rich.manifest["snapshot_id"]
    assert page["repo"] == "phonyx"
    with pytest.raises(MrReviewError) as excinfo:
        ops_review.mr_review_read("phonyx", "f" * 64, "diff", home=rich.home)
    assert excinfo.value.code == "review-object-missing"
    with pytest.raises(MrReviewError) as excinfo:
        ops_review.mr_review_read("other", rich.manifest["snapshot_id"], "diff",
                                  home=rich.home)
    assert excinfo.value.code == "invalid-schema"


def test_big_mr_diff_reassembly_and_evidence(big):
    expected = _expected_diff(
        big.reader._git.git_dir, big.manifest["comparison_base_sha"],
        big.manifest["gitlab_diff_refs"]["head_sha"], 20)
    pages = _walk(big.reader, view="diff", budget=24000)
    assert _diff_bytes(pages) == expected
    identities = [(c["path"], c["byte_offset"]) for p in pages for c in p["chunks"]]
    assert len(identities) == len(set(identities))
    assert pages[-1]["complete"] is True
    assert all(not p["complete"] for p in pages[:-1])
    inv_pages = _walk_inventory(big.reader, budget=24000)
    inv_total = inv_pages[-1]["totals"]["files"]
    assert inv_total == 1000
    assert inv_pages[-1]["totals"]["added"] == 1000
    assert pages[-1]["totals"]["files"] == inv_total
    assert pages[-1]["totals"]["bytes"] == len(expected)
    inv_paths = [e["path"] for p in inv_pages for e in p["entries"]]
    assert len(inv_paths) == len(set(inv_paths)) == 1000

    failure_cases = []
    cursor = pages[0]["next_cursor"]
    flipped = ("A" if cursor[0] != "A" else "B") + cursor[1:]
    with pytest.raises(MrReviewError) as excinfo:
        big.reader.read_page(view="diff", cursor=flipped, budget_bytes=24000)
    failure_cases.append({"case": "tampered-cursor",
                          "code": excinfo.value.code})
    with pytest.raises(MrReviewError) as excinfo:
        big.reader.read_page(view="diff", cursor=cursor, paths=["files/f0001.py"],
                             budget_bytes=24000)
    failure_cases.append({"case": "cursor-other-query",
                          "code": excinfo.value.code})
    with pytest.raises(MrReviewError) as excinfo:
        big.reader.read_page(view="diff", budget_bytes=48001)
    failure_cases.append({"case": "budget-out-of-range",
                          "code": excinfo.value.code})

    evidence = {
        "pages_walked": {"diff": len(pages), "inventory": len(inv_pages)},
        "files_in_inventory": inv_total,
        "reassembly_equal": _diff_bytes(pages) == expected,
        "budget_checks": [{"page": i, "bytes": mcp_result_bytes(p),
                            "budget": 24000}
                           for i, p in enumerate(pages)],
        "failure_cases": failure_cases,
    }
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    (EVIDENCE_DIR / "task-6.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
