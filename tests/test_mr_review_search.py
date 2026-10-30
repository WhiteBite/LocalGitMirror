"""Acceptance and failure tests for mr_review_search / mr_review_context:
snapshot objects only (dirty worktree invisible), side/scope matrix, budget
paging with lossless multi-page walks, typed cursor rejections, binary and
gitlink limitations, heuristic candidates and the ops_review wiring."""
from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.client import LgmError
from lgm_core.mr_review_models import MrReviewError, mcp_result_bytes
from lgm_core.mr_review_reader import _cursor_digest, open_reader
from lgm_core.mr_review_search import mr_review_context, mr_review_search
from lgm_core import ops_review
from test_mr_review_reader import _fixture, _init_repo, big, huge, rich
from test_mr_review_store import _git, _manifest


@pytest.fixture(scope="module")
def cand(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("cand")
    repo = tmp / "cand"
    _init_repo(repo)
    (repo / "src").mkdir()
    (repo / "tests").mkdir()
    (repo / "cfg").mkdir()
    (repo / "src" / "price.py").write_text("PRICE = 1\n", encoding="utf-8")
    (repo / "tests" / "test_price.py").write_text(
        "def test_price():\n    assert PRICE\n", encoding="utf-8")
    (repo / "cfg" / "app.yml").write_text("v: 1\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    (repo / "src" / "price.py").write_text("PRICE = 2\n", encoding="utf-8")
    (repo / "cfg" / "app.yml").write_text("v: 2\n", encoding="utf-8")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-qm", "feature")
    _git(repo, "checkout", "-q", "master")
    return _fixture(tmp, repo, _manifest(repo), "feature", "master")


def _ids(ns):
    return ns.manifest["repo"], ns.manifest["snapshot_id"]


def _walk(ns, query, budget, **kw):
    repo, sid = _ids(ns)
    pages, cursor, hits = [], "", []
    while True:
        page = mr_review_search(repo, sid, query, cursor=cursor,
                                budget_bytes=budget, home=ns.home, **kw)
        assert mcp_result_bytes(page) <= budget
        pages.append(page)
        hits += page["hits"]
        if page["complete"]:
            assert page["next_cursor"] is None
            return pages, hits
        cursor = page["next_cursor"]
        assert cursor


def test_dirty_checkout_marker_not_found(rich):
    repo, sid = _ids(rich)
    target = rich.repo / "src" / "app.py"
    original = target.read_text(encoding="utf-8")
    target.write_text(original + "UNIQUE_DIRTY_TOKEN_42\n", encoding="utf-8")
    (rich.repo / "untracked_marker.txt").write_text(
        "UNIQUE_DIRTY_TOKEN_42\n", encoding="utf-8")
    try:
        page = mr_review_search(repo, sid, "UNIQUE_DIRTY_TOKEN_42", home=rich.home)
        assert page["hits"] == [] and page["complete"] is True
    finally:
        target.write_text(original, encoding="utf-8")
        (rich.repo / "untracked_marker.txt").unlink()


def test_deleted_line_old_side_only(rich):
    repo, sid = _ids(rich)
    removed = mr_review_search(repo, sid, "to be deleted", scope="removed",
                               home=rich.home)
    assert [h["side"] for h in removed["hits"]] == ["old"]
    assert removed["hits"][0]["path"] == "deleted.txt"
    tree_old = mr_review_search(repo, sid, "to be deleted", scope="tree",
                                side="old", home=rich.home)
    assert len(tree_old["hits"]) == 1
    tree_new = mr_review_search(repo, sid, "to be deleted", scope="tree",
                                side="new", home=rich.home)
    assert tree_new["hits"] == []
    added = mr_review_search(repo, sid, "to be deleted", scope="added",
                             home=rich.home)
    assert added["hits"] == []


def test_changed_scope_searches_both_sides(rich):
    repo, sid = _ids(rich)
    both = mr_review_search(repo, sid, "print('v", scope="changed", home=rich.home)
    sides = sorted(h["side"] for h in both["hits"])
    assert sides == ["new", "old"]
    assert all(h["path"] == "src/app.py" for h in both["hits"])
    new_only = mr_review_search(repo, sid, "print('v", scope="changed",
                                side="new", home=rich.home)
    assert [h["side"] for h in new_only["hits"]] == ["new"]


def test_hits_open_via_reader_file_view(rich):
    repo, sid = _ids(rich)
    page = mr_review_search(repo, sid, "line3", scope="tree", side="new",
                            home=rich.home)
    assert page["hits"]
    reader = open_reader(rich.home, repo, sid)
    for hit in page["hits"]:
        viewed = reader.read_page(view="file", paths=[hit["path"]], side=hit["side"])
        entry = viewed["files"][0]
        assert entry["oid"] == hit["blob"]
        text = "".join(c["content"] for c in entry["chunks"])
        lines = text.split("\n")
        assert lines[hit["line"] - 1][hit["column"] - 1:
                                      hit["column"] - 1 + hit["match_length"]] \
            == "line3"


def test_context_textual_semantic_false_and_window(rich):
    repo, sid = _ids(rich)
    ctx = mr_review_context(repo, sid, "src/app.py", line=2, side="new",
                            context_lines=1, home=rich.home)
    assert ctx["method"] == "textual" and ctx["semantic_complete"] is False
    assert [ln["line"] for ln in ctx["lines"]] == [1, 2, 3]
    assert ctx["lines"][1]["text"] == "line2"
    assert ctx["blob"]


def test_context_missing_and_empty(rich):
    repo, sid = _ids(rich)
    with pytest.raises(MrReviewError) as exc:
        mr_review_context(repo, sid, "nope.txt", home=rich.home)
    assert exc.value.code == "review-object-missing"
    empty = mr_review_context(repo, sid, "empty.txt", side="new", home=rich.home)
    assert empty["lines"] == [] and empty["complete"] is True


def test_invalid_side_and_scope_combos(rich):
    repo, sid = _ids(rich)
    with pytest.raises(MrReviewError) as exc:
        mr_review_search(repo, sid, "x", side="bogus", home=rich.home)
    assert exc.value.code == "invalid-side"
    with pytest.raises(MrReviewError) as exc:
        mr_review_search(repo, sid, "x", scope="bogus", home=rich.home)
    assert exc.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as exc:
        mr_review_search(repo, sid, "x", scope="removed", side="new", home=rich.home)
    assert exc.value.code == "invalid-side"
    with pytest.raises(MrReviewError) as exc:
        mr_review_search(repo, sid, "x", scope="added", side="old", home=rich.home)
    assert exc.value.code == "invalid-side"


def test_binary_nonutf8_gitlink_limitations_and_symlink_text(rich):
    repo, sid = _ids(rich)
    page = mr_review_search(repo, sid, "a", scope="tree", side="new",
                            home=rich.home)
    joined = " ".join(page["limitations"])
    assert "binary.bin" in joined and "cp1251.txt" in joined and "submodule" in joined
    assert all(h["path"] not in ("binary.bin", "cp1251.txt", "submodule")
               for h in page["hits"])
    link = mr_review_search(repo, sid, "target.txt", scope="added", home=rich.home)
    assert [h["path"] for h in link["hits"]] == ["link.txt"]


def test_huge_line_excerpt_capped(huge):
    repo, sid = _ids(huge)
    page = mr_review_search(repo, sid, "word12345", scope="tree", side="new",
                            home=huge.home)
    assert page["hits"]
    hit = page["hits"][0]
    assert hit["truncated"] is True
    assert len(hit["excerpt"].encode("utf-8")) <= 256
    assert hit["excerpt"].encode("utf-8").decode("utf-8") == hit["excerpt"]
    assert hit["match_length"] == len("word12345")


def test_unicode_column_is_char_index(rich):
    repo, sid = _ids(rich)
    page = mr_review_search(repo, sid, "данные", scope="tree", side="new",
                            paths=["unicode/данные.txt"], home=rich.home)
    assert page["hits"]
    assert page["hits"][0]["column"] == 7


def test_budget_walk_lossless_and_bounded(big):
    pages_lo, hits_lo = _walk(big, "value = ", 4096, scope="tree", side="new")
    pages_hi, hits_hi = _walk(big, "value = ", 48000, scope="tree", side="new")
    assert len(pages_lo) > 1
    assert hits_lo == hits_hi
    keys = [(h["path"], h["line"], h["column"]) for h in hits_lo]
    assert len(keys) == len(set(keys)) == 1000
    assert sum(p["totals"]["files_scanned"] for p in pages_lo) == 1001
    assert all(p["complete"] is False for p in pages_lo[:-1])
    assert pages_lo[-1]["complete"] is True


def test_budget_validation(rich):
    repo, sid = _ids(rich)
    for bad in (100, 99999):
        with pytest.raises(MrReviewError) as exc:
            mr_review_search(repo, sid, "x", budget_bytes=bad, home=rich.home)
        assert exc.value.code == "invalid-budget"


def test_cursor_tampered_and_foreign(big, rich):
    repo, sid = _ids(big)
    first = mr_review_search(repo, sid, "value = ", budget_bytes=4096,
                             scope="tree", side="new", home=big.home)
    token = first["next_cursor"]
    payload = json.loads(base64.urlsafe_b64decode(token.encode("ascii")))
    payload["s"]["hs"] = payload["s"]["hs"] + 5
    tampered = base64.urlsafe_b64encode(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).decode()
    with pytest.raises(MrReviewError) as exc:
        mr_review_search(repo, sid, "value = ", cursor=tampered,
                         budget_bytes=4096, scope="tree", side="new", home=big.home)
    assert exc.value.code == "hash-mismatch"
    with pytest.raises(MrReviewError) as exc:
        mr_review_search(repo, sid, "value = ", cursor=token, budget_bytes=4096,
                         scope="tree", side="old", home=big.home)
    assert exc.value.code == "invalid-schema"
    rrepo, rsid = _ids(rich)
    with pytest.raises(MrReviewError) as exc:
        mr_review_search(rrepo, rsid, "value = ", cursor=token,
                         budget_bytes=4096, scope="tree", side="new", home=rich.home)
    assert exc.value.code == "invalid-schema"


def test_cursor_malformed_state_is_typed(big):
    repo, sid = _ids(big)
    first = mr_review_search(repo, sid, "value = ", budget_bytes=4096,
                             scope="tree", side="new", home=big.home)
    payload = json.loads(
        base64.urlsafe_b64decode(first["next_cursor"].encode("ascii")))
    payload["s"] = {"unexpected": 1}
    payload["d"] = _cursor_digest({k: payload[k] for k in ("v", "sid", "q", "s")})
    forged = base64.urlsafe_b64encode(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).decode()
    with pytest.raises(MrReviewError) as exc:
        mr_review_search(repo, sid, "value = ", cursor=forged,
                         budget_bytes=4096, scope="tree", side="new", home=big.home)
    assert exc.value.code == "invalid-schema"


def test_determinism_and_zero_matches(rich):
    repo, sid = _ids(rich)
    a = mr_review_search(repo, sid, "line2", scope="tree", side="new", home=rich.home)
    b = mr_review_search(repo, sid, "line2", scope="tree", side="new", home=rich.home)
    assert a == b
    none = mr_review_search(repo, sid, "ZZZ_NOT_PRESENT", home=rich.home)
    assert none["complete"] is True and none["hits"] == [] \
        and none["next_cursor"] is None


def test_candidates_heuristic(cand):
    repo, sid = _ids(cand)
    page = mr_review_search(repo, sid, "PRICE", scope="changed", home=cand.home)
    kinds = {c["path"]: c for c in page["candidates"]}
    assert kinds["tests/test_price.py"]["kind"] == "test"
    assert kinds["cfg/app.yml"]["kind"] == "config"
    for cand_item in page["candidates"]:
        assert cand_item["method"] == "heuristic" and cand_item["reason"]
    assert len(page["candidates"]) <= 20


def test_context_huge_line_continuation(huge):
    repo, sid = _ids(huge)
    ctx = mr_review_context(repo, sid, "vocab.txt", line=1, context_lines=0,
                            budget_bytes=4096, home=huge.home)
    assert ctx["lines"]
    assert ctx["lines"][0].get("continued") is True
    assert all(len(ln["text"].encode("utf-8")) <= 4096 for ln in ctx["lines"])
    offsets = [ln.get("byte_offset", 0) for ln in ctx["lines"]]
    assert offsets == sorted(offsets)
    assert ctx["semantic_complete"] is False


def test_ops_review_wiring(rich):
    repo, sid = _ids(rich)
    page = ops_review.mr_review_search(repo, sid, "line3", home=rich.home)
    assert page["hits"]
    ctx = ops_review.mr_review_context(repo, sid, "src/app.py", home=rich.home)
    assert ctx["lines"]
    with pytest.raises(LgmError):
        ops_review.mr_review_search("", sid, "line3", home=rich.home)
