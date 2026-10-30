"""Real-git tests for the safe MR review store Git object reader.

Every test builds a real temp repository and a real bundle; nothing touches
the real ~/.lgm or any mirror checkout.
"""
import os
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.mr_review_git import (
    MrReviewGit,
    init_bare,
    resolve_contained,
    validate_ref,
    validate_sha,
    validate_tree_path,
)
from lgm_core.mr_review_models import MrReviewError

IS_WINDOWS = os.name == "nt"


def _git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=60, check=False)
    if check:
        assert proc.returncode == 0, f"git {' '.join(args)}: {proc.stderr}"
    return proc


def _work_repo(tmp_path: Path) -> Path:
    repo = tmp_path / "work"
    repo.mkdir()
    _git(repo, "init", "-q", "-b", "master")
    _git(repo, "config", "user.email", "t@t")
    _git(repo, "config", "user.name", "t")
    (repo / "src").mkdir()
    (repo / "src" / "app.py").write_text("print('v1')\n", encoding="utf-8")
    (repo / "src" / "gone.py").write_text("old\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    (repo / "src" / "app.py").write_text("print('v2')\n", encoding="utf-8")
    (repo / "src" / "new.py").write_text("x = 1\n", encoding="utf-8")
    (repo / "src" / "gone.py").unlink()
    _git(repo, "add", "-A")
    _git(repo, "commit", "-qm", "feature work")
    _git(repo, "checkout", "-q", "master")
    return repo


def _bare_with_bundle(tmp_path: Path) -> tuple[MrReviewGit, Path, Path, str, str]:
    repo = _work_repo(tmp_path)
    bundle = tmp_path / "code.bundle"
    _git(repo, "bundle", "create", str(bundle), "feature", "master")
    bare = tmp_path / "objects.git"
    init_bare(bare)
    reader = MrReviewGit(bare)
    reader.verify_bundle(bundle)
    heads = reader.bundle_heads(bundle)
    reader.fetch_bundle(bundle, "refs/mr-review/sid1", [ref for _, ref in heads])
    source = _git(repo, "rev-parse", "feature").stdout.strip()
    target = _git(repo, "rev-parse", "master").stdout.strip()
    return reader, bare, repo, source, target



def test_bundle_ingest_creates_only_review_refs(tmp_path):
    reader, _, _, source, target = _bare_with_bundle(tmp_path)
    refs = reader.list_refs()
    assert refs == {
        "refs/mr-review/sid1/bundle/refs/heads/feature": source,
        "refs/mr-review/sid1/bundle/refs/heads/master": target,
    }
    assert not [ref for ref in refs if ref.startswith("refs/heads/")]
    assert reader.object_exists(source)
    assert reader.object_exists(target)


def test_bundle_verify_missing_prerequisite_is_typed_error(tmp_path):
    repo = _work_repo(tmp_path)
    bundle = tmp_path / "range.bundle"
    _git(repo, "bundle", "create", str(bundle), "master..feature")
    bare = tmp_path / "objects.git"
    init_bare(bare)
    reader = MrReviewGit(bare)
    with pytest.raises(MrReviewError) as excinfo:
        reader.verify_bundle(bundle)
    assert excinfo.value.code == "review-object-missing"


def test_bundle_verify_missing_file_is_typed_error(tmp_path):
    bare = tmp_path / "objects.git"
    init_bare(bare)
    reader = MrReviewGit(bare)
    with pytest.raises(MrReviewError) as excinfo:
        reader.verify_bundle(tmp_path / "nope.bundle")
    assert excinfo.value.code == "review-object-missing"


def test_fetch_bundle_rejects_escape_refspec(tmp_path):
    repo = _work_repo(tmp_path)
    bundle = tmp_path / "code.bundle"
    _git(repo, "bundle", "create", str(bundle), "feature")
    bare = tmp_path / "objects.git"
    init_bare(bare)
    reader = MrReviewGit(bare)
    with pytest.raises(MrReviewError) as excinfo:
        reader.fetch_bundle(bundle, "refs/heads", ["refs/heads/feature"])
    assert excinfo.value.code == "invalid-schema"
    assert reader.list_refs() == {}



def test_update_ref_and_ref_value_roundtrip(tmp_path):
    reader, _, _, source, _ = _bare_with_bundle(tmp_path)
    reader.update_ref("refs/mr-review/sid1/source", source)
    assert reader.ref_value("refs/mr-review/sid1/source") == source
    assert reader.ref_value("refs/mr-review/sid1/absent") is None


def test_update_ref_rejects_unknown_object(tmp_path):
    reader, _, _, _, _ = _bare_with_bundle(tmp_path)
    with pytest.raises(MrReviewError) as excinfo:
        reader.update_ref("refs/mr-review/sid1/source", "1" * 40)
    assert excinfo.value.code == "review-object-missing"
    with pytest.raises(MrReviewError) as excinfo:
        reader.update_ref("refs/mr-review/sid1/source", "0" * 40)
    assert excinfo.value.code == "invalid-schema"


def test_update_ref_rejects_refs_outside_review_namespace(tmp_path):
    reader, _, _, source, _ = _bare_with_bundle(tmp_path)
    with pytest.raises(MrReviewError) as excinfo:
        reader.update_ref("refs/heads/master", source)
    assert excinfo.value.code == "invalid-schema"


@pytest.mark.parametrize("ref", [
    "-refs/evil", "refs/../../evil", "refs/a..b", "refs/a.lock", "refs/heads/x y",
    "HEAD", "x", "refs//a", "refs/a/", "refs/.hidden",
])
def test_validate_ref_rejects_unsafe_refs(ref):
    with pytest.raises(MrReviewError) as excinfo:
        validate_ref(ref)
    assert excinfo.value.code == "invalid-schema"


@pytest.mark.parametrize("ref", [
    "refs/mr-review/" + "a" * 64 + "/source",
    "refs/heads/master",
    "refs/mr-review/sid/bundle/refs/heads/feature",
])
def test_validate_ref_accepts_safe_refs(ref):
    assert validate_ref(ref) == ref



def test_read_blob_bounded_slices(tmp_path):
    reader, _, _, source, _ = _bare_with_bundle(tmp_path)
    entry = reader.ls_tree(source, "src/app.py")
    assert entry is not None and entry.type == "blob"
    content = b"print('v2')\n"
    info, full = reader.read_blob(entry.oid, 0, 10_000)
    assert full == content
    assert info.size == len(content)
    _, first = reader.read_blob(entry.oid, 0, 7)
    assert first == content[:7]
    _, mid = reader.read_blob(entry.oid, 7, 3)
    assert mid == content[7:10]
    _, past = reader.read_blob(entry.oid, 10_000, 10)
    assert past == b""


def test_read_blob_pages_reassemble_giant_blob(tmp_path):
    repo = _work_repo(tmp_path)
    _git(repo, "checkout", "-q", "feature")
    giant = "z" * (3 * 1024 * 1024)
    (repo / "src" / "giant.txt").write_text(giant, encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "giant")
    bundle = tmp_path / "giant.bundle"
    _git(repo, "bundle", "create", str(bundle), "feature")
    bare = tmp_path / "objects.git"
    init_bare(bare)
    reader = MrReviewGit(bare)
    heads = reader.bundle_heads(bundle)
    reader.fetch_bundle(bundle, "refs/mr-review/sid2", [ref for _, ref in heads])
    source = _git(repo, "rev-parse", "feature").stdout.strip()
    entry = reader.ls_tree(source, "src/giant.txt")
    assert entry is not None
    expected = giant.encode("utf-8")
    info, _ = reader.read_blob(entry.oid, 0, 0)
    assert info.size == len(expected)
    chunks = []
    offset = 0
    while offset < info.size:
        _, chunk = reader.read_blob(entry.oid, offset, 64 * 1024)
        chunks.append(chunk)
        offset += len(chunk)
    assert b"".join(chunks) == expected


def test_read_blob_rejects_non_blob(tmp_path):
    reader, _, _, source, _ = _bare_with_bundle(tmp_path)
    with pytest.raises(MrReviewError) as excinfo:
        reader.read_blob(source, 0, 10)
    assert excinfo.value.code == "review-object-missing"


@pytest.mark.parametrize("sha", ["ZZZ", "0" * 39, "A" * 40, 123, None, ""])
def test_validate_sha_rejects_malformed(sha):
    with pytest.raises(MrReviewError) as excinfo:
        validate_sha(sha)
    assert excinfo.value.code == "invalid-schema"


def test_unknown_sha_is_typed_error_never_disk_fallback(tmp_path):
    reader, _, _, _, _ = _bare_with_bundle(tmp_path)
    absent = "1" * 40
    with pytest.raises(MrReviewError) as excinfo:
        reader.read_blob(absent, 0, 10)
    assert excinfo.value.code == "review-object-missing"
    with pytest.raises(MrReviewError) as excinfo:
        reader.object_info(absent)
    assert excinfo.value.code == "review-object-missing"
    assert reader.object_exists(absent) is False



def test_ls_tree_literal_paths(tmp_path):
    reader, _, _, source, _ = _bare_with_bundle(tmp_path)
    entry = reader.ls_tree(source, "src/app.py")
    assert entry is not None
    assert entry.mode == "100644"
    assert entry.type == "blob"
    assert reader.ls_tree(source, "src/(not-glob)") is None
    assert reader.ls_tree(source, "no/such/file.py") is None


def test_diff_tree_reports_add_modify_delete(tmp_path):
    reader, _, _, source, target = _bare_with_bundle(tmp_path)
    entries = {e.path: e for e in reader.diff_tree(target, source)}
    assert entries["src/app.py"].status == "M"
    assert entries["src/new.py"].status == "A"
    assert entries["src/gone.py"].status == "D"
    assert entries["src/gone.py"].dst_sha == "0" * 40
    assert entries["src/new.py"].src_sha == "0" * 40
    assert entries["src/app.py"].src_mode == "100644"


def test_merge_bases_returns_all(tmp_path):
    reader, _, _, source, target = _bare_with_bundle(tmp_path)
    bases = reader.merge_bases(source, target)
    assert bases == [target]


def test_merge_bases_unrelated_histories_empty(tmp_path):
    repo = _work_repo(tmp_path)
    _git(repo, "checkout", "-q", "--orphan", "orphan-branch")
    (repo / "o.txt").write_text("o\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "orphan")
    bundle = tmp_path / "orphan.bundle"
    _git(repo, "bundle", "create", str(bundle), "master", "orphan-branch")
    bare = tmp_path / "objects.git"
    init_bare(bare)
    reader = MrReviewGit(bare)
    heads = reader.bundle_heads(bundle)
    reader.fetch_bundle(bundle, "refs/mr-review/sid3", [ref for _, ref in heads])
    master = _git(repo, "rev-parse", "master").stdout.strip()
    orphan = _git(repo, "rev-parse", "orphan-branch").stdout.strip()
    assert reader.merge_bases(master, orphan) == []



@pytest.mark.parametrize("path", [
    "../escape", "a/../b", "/abs", "C:/Windows", "C:evil", "\\\\srv\\share",
    "-option", "", ".", "a/./b", "a//b", "a/..",
])
def test_validate_tree_path_rejects_unsafe(path):
    with pytest.raises(MrReviewError) as excinfo:
        validate_tree_path(path)
    assert excinfo.value.code == "invalid-schema"


@pytest.mark.parametrize("path", [
    "src/app.py", "a-b/c_d.e", "ünïcode/päth.txt", "weird\\name", "x",
])
def test_validate_tree_path_accepts_literal(path):
    assert validate_tree_path(path) == path


@pytest.mark.parametrize("relative", [
    "../escape", "a\\..\\b", "/abs", "C:/Windows", "C:x", "\\\\srv\\share",
    "-option", "", "a/../../b",
])
def test_resolve_contained_rejects_unsafe(relative):
    with pytest.raises(MrReviewError) as excinfo:
        resolve_contained(Path.cwd(), relative)
    assert excinfo.value.code == "invalid-schema"


def test_resolve_contained_accepts_nested(tmp_path):
    base = tmp_path / "base"
    base.mkdir()
    resolved = resolve_contained(base, "sub/dir/file.txt")
    assert resolved == (base / "sub" / "dir" / "file.txt").resolve()


@pytest.mark.skipif(not IS_WINDOWS, reason="junctions are a Windows feature")
def test_resolve_contained_rejects_junction_escape(tmp_path):
    base = tmp_path / "base"
    (base / "sub").mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    link = base / "escape"
    proc = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(outside)],
                          capture_output=True, text=True, timeout=30, check=False)
    if proc.returncode != 0:
        pytest.skip("mklink /J unavailable")
    with pytest.raises(MrReviewError) as excinfo:
        resolve_contained(base, "escape")
    assert excinfo.value.code == "invalid-schema"


def test_resolve_contained_rejects_symlink_escape(tmp_path):
    base = tmp_path / "base"
    (base / "sub").mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    link = base / "escape"
    try:
        link.symlink_to(outside, target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("os.symlink requires privileges on this Windows setup")
    with pytest.raises(MrReviewError) as excinfo:
        resolve_contained(base, "escape")
    assert excinfo.value.code == "invalid-schema"


def test_resolve_contained_rejects_dangling_symlink_escape(tmp_path):
    base = tmp_path / "base"
    (base / "sub").mkdir(parents=True)
    link = base / "dangling"
    try:
        link.symlink_to(tmp_path / "nowhere", target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("os.symlink requires privileges on this Windows setup")
    with pytest.raises(MrReviewError) as excinfo:
        resolve_contained(base, "dangling")
    assert excinfo.value.code == "invalid-schema"
