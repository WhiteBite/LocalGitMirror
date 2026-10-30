"""Real-git tests for the HOME MR review store: import atomicity, mirror
isolation, parallel imports, digest gates and SQLite revision CAS.

Every store root lives under tmp_path; the real ~/.lgm is never touched.
"""
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import mr_review_models as m
from lgm_core.mr_review_git import MrReviewGit
from lgm_core.mr_review_models import MrReviewError
from lgm_core.mr_review_store import (
    ImportResult,
    MrReviewStore,
    import_snapshot,
    normalize_gitlab_url,
    repo_key,
    reviews_home,
)

IS_WINDOWS = os.name == "nt"


def _git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=60, check=False)
    if check:
        assert proc.returncode == 0, f"git {' '.join(args)}: {proc.stderr}"
    return proc


def _ordinary_mirror(tmp_path: Path) -> Path:
    """Stand-in for the user's real mirror checkout: branches plus a dirty
    worktree that must survive every store operation untouched."""
    repo = tmp_path / "mirror"
    repo.mkdir()
    _git(repo, "init", "-q", "-b", "master")
    _git(repo, "config", "user.email", "t@t")
    _git(repo, "config", "user.name", "t")
    (repo / "README.md").write_text("base\n", encoding="utf-8")
    (repo / "src").mkdir()
    (repo / "src" / "app.py").write_text("print('v1')\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    (repo / "src" / "app.py").write_text("print('v2')\n", encoding="utf-8")
    (repo / "src" / "new.py").write_text("x = 1\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "feature work")
    _git(repo, "checkout", "-q", "master")
    (repo / "README.md").write_text("base dirty\n", encoding="utf-8")
    return repo


def _manifest(repo: Path, *, iid: int = 30, source_branch: str = "feature",
              gitlab_url: str = "https://gitlab.example.com",
              target_project_id: int = 42, repo_name: str = "phonyx") -> dict:
    source_sha = _git(repo, "rev-parse", source_branch).stdout.strip()
    target_tip = _git(repo, "rev-parse", "master").stdout.strip()
    base = _git(repo, "merge-base", "master", source_branch).stdout.strip()
    snap = {
        "schema_version": 1,
        "repo": repo_name,
        "gitlab_url": gitlab_url,
        "source_project_id": target_project_id,
        "target_project_id": target_project_id,
        "iid": iid,
        "mr_url": f"{gitlab_url}/groups/phonyx/-/merge_requests/{iid}",
        "title": "Review workspace",
        "description": None,
        "state": "opened",
        "draft": False,
        "source_branch": source_branch,
        "target_branch": "master",
        "source_sha": source_sha,
        "target_tip_sha": target_tip,
        "source_fetch_ref": None,
        "gitlab_diff_refs": {
            "base_sha": base,
            "start_sha": target_tip,
            "head_sha": source_sha,
        },
        "computed_merge_bases": [base],
        "comparison_base_sha": base,
        "comparison_kind": "gitlab-diff",
        "observed_started_at": "2026-10-30T09:00:00Z",
        "observed_finished_at": "2026-10-30T09:00:05Z",
        "mr_updated_at": "2026-10-30T08:59:00Z",
        "discussions_fetched_at": "2026-10-30T09:00:04Z",
        "discussions_complete": True,
        "limitations": [],
        "discussions": [],
    }
    snap["code_id"] = m.compute_code_id(snap)
    snap["version_fingerprint"] = m.compute_version_fingerprint(snap)
    snap["snapshot_id"] = m.compute_snapshot_id(snap)
    return snap


def _bundle(tmp_path: Path, repo: Path, name: str = "code.bundle") -> Path:
    bundle = tmp_path / name
    _git(repo, "bundle", "create", str(bundle), "feature", "master")
    return bundle


def _mirror_state(repo: Path) -> dict:
    files = {}
    for path in sorted(repo.rglob("*")):
        if ".git" in path.parts or not path.is_file():
            continue
        files[str(path.relative_to(repo))] = hashlib.sha256(
            path.read_bytes()).hexdigest()
    return {
        "head": _git(repo, "rev-parse", "HEAD").stdout.strip(),
        "refs": _git(repo, "for-each-ref", "--format=%(refname) %(objectname)").stdout,
        "status": _git(repo, "status", "--porcelain").stdout,
        "index": hashlib.sha256(
            _git(repo, "ls-files", "--stage").stdout.encode()).hexdigest(),
        "files": files,
    }


def _store_rows(store: MrReviewStore, sql: str, params: tuple = ()) -> list:
    conn = sqlite3.connect(store.db_path)
    try:
        return conn.execute(sql, params).fetchall()
    finally:
        conn.close()



def test_repo_key_normalizes_url_and_is_deterministic():
    key = repo_key("https://GitLab.Example.com/", 42, "phonyx")
    assert key == repo_key("https://gitlab.example.com", 42, "phonyx")
    assert len(key) == 64 and int(key, 16) >= 0
    assert key != repo_key("https://gitlab.example.com", 43, "phonyx")
    assert key != repo_key("https://gitlab.example.com", 42, "other")
    assert key != repo_key("https://gitlab2.example.com", 42, "phonyx")


def test_repo_key_rejects_bad_identity():
    for args in (("ftp://x", 42, "r"), ("https://x", 0, "r"), ("https://x", True, "r"),
                 ("https://x", 42, ""), ("https://x", 42, None)):
        with pytest.raises(MrReviewError) as excinfo:
            repo_key(*args)
        assert excinfo.value.code == "invalid-schema"


def test_reviews_home_override_and_env(monkeypatch, tmp_path):
    assert reviews_home(tmp_path / "rev") == tmp_path / "rev"
    monkeypatch.setenv("LGM_REVIEWS_HOME", str(tmp_path / "envhome"))
    assert reviews_home() == tmp_path / "envhome"
    monkeypatch.delenv("LGM_REVIEWS_HOME")
    assert reviews_home() == Path.home() / ".lgm" / "reviews"


def test_normalize_gitlab_url_rules():
    assert normalize_gitlab_url("HTTPS://GitLab.Example.com/") == "https://gitlab.example.com"
    assert normalize_gitlab_url("https://gitlab.example.com/sub") == "https://gitlab.example.com/sub"
    for bad in ("", "ftp://x", "gitlab.example.com", None):
        with pytest.raises(MrReviewError) as excinfo:
            normalize_gitlab_url(bad)
        assert excinfo.value.code == "invalid-schema"



def test_import_installs_snapshot_and_leaves_ordinary_mirror_untouched(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    before = _mirror_state(mirror)

    result = import_snapshot(manifest, bundle, home=tmp_path / "reviews")

    assert isinstance(result, ImportResult)
    assert result.status == "imported"
    assert result.snapshot_id == manifest["snapshot_id"]
    store_root = result.store_root
    assert store_root == (tmp_path / "reviews" / repo_key(
        "https://gitlab.example.com", 42, "phonyx")).resolve()
    assert (store_root / "snapshots" / f"{manifest['snapshot_id']}.json").is_file()
    assert (store_root / "objects.git" / "HEAD").is_file()
    assert (store_root / "review.sqlite3").is_file()
    assert (store_root / "environments").is_dir()

    assert _mirror_state(mirror) == before


def test_installed_snapshot_file_is_canonical_json(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    result = import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    raw = (result.store_root / "snapshots" / f"{manifest['snapshot_id']}.json").read_bytes()
    assert raw == m.canonical_json(manifest).encode("utf-8")


def test_import_writes_db_rows_and_retention_refs(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")

    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    rows = _store_rows(store, "SELECT snapshot_id, code_id, iid FROM snapshots")
    assert rows == [(manifest["snapshot_id"], manifest["code_id"], 30)]
    assert store.current_snapshot_id(30) == manifest["snapshot_id"]
    assert store.current_snapshot_id(31) is None

    refs = store.git.list_refs()
    sid = manifest["snapshot_id"]
    assert refs[f"refs/mr-review/{sid}/source"] == manifest["source_sha"]
    assert refs[f"refs/mr-review/{sid}/target_tip"] == manifest["target_tip_sha"]
    assert refs[f"refs/mr-review/{sid}/diff_base"] == manifest["gitlab_diff_refs"]["base_sha"]
    assert refs[f"refs/mr-review/{sid}/diff_start"] == manifest["gitlab_diff_refs"]["start_sha"]
    assert refs[f"refs/mr-review/{sid}/diff_head"] == manifest["gitlab_diff_refs"]["head_sha"]
    assert all(ref.startswith("refs/mr-review/") for ref in refs)


def test_snapshot_stays_readable_after_ordinary_branches_move(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")

    _git(mirror, "branch", "-D", "feature")
    (mirror / "README.md").write_text("moved on\n", encoding="utf-8")
    _git(mirror, "add", ".")
    _git(mirror, "commit", "-qm", "target moves")
    _git(mirror, "gc", "--prune=now", "-q")

    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    snap = store.load_snapshot(manifest["snapshot_id"])
    assert snap["source_sha"] == manifest["source_sha"]
    entry = store.git.ls_tree(snap["source_sha"], "src/app.py")
    assert entry is not None
    _, blob = store.git.read_blob(entry.oid, 0, 100)
    assert blob == b"print('v2')\n"


def test_reimport_same_snapshot_is_idempotent(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    first = import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    second = import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    assert first.status == "imported"
    assert second.status == "already-imported"
    assert second.snapshot_id == first.snapshot_id

    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    assert len(_store_rows(store, "SELECT * FROM snapshots")) == 1
    assert len(_store_rows(store, "SELECT * FROM current_snapshot")) == 1


def test_reimport_without_bundle_file_when_already_installed(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    bundle.unlink()
    result = import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    assert result.status == "already-imported"



def test_digest_mismatch_installs_nothing(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    tampered = dict(manifest)
    tampered["title"] = "tampered after digest computation"

    with pytest.raises(MrReviewError) as excinfo:
        import_snapshot(tampered, bundle, home=tmp_path / "reviews")
    assert excinfo.value.code == "hash-mismatch"

    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    assert _store_rows(store, "SELECT * FROM snapshots") == []
    assert _store_rows(store, "SELECT * FROM current_snapshot") == []
    assert store.git.list_refs() == {}
    assert list((store.root / "snapshots").iterdir()) == []


def test_identity_mismatch_rejected(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror, gitlab_url="https://gitlab2.example.com")
    bundle = _bundle(tmp_path, mirror)
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    with pytest.raises(MrReviewError) as excinfo:
        store.import_snapshot(manifest, bundle)
    assert excinfo.value.code == "invalid-schema"
    assert _store_rows(store, "SELECT * FROM snapshots") == []
    assert store.git.list_refs() == {}


def test_missing_prerequisite_bundle_rejected(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    range_bundle = tmp_path / "range.bundle"
    _git(mirror, "bundle", "create", str(range_bundle), "master..feature")

    with pytest.raises(MrReviewError) as excinfo:
        import_snapshot(manifest, range_bundle, home=tmp_path / "reviews")
    assert excinfo.value.code == "review-object-missing"

    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    assert _store_rows(store, "SELECT * FROM snapshots") == []
    assert store.current_snapshot_id(30) is None
    assert store.git.list_refs() == {}


def test_manifest_tip_missing_from_bundle_rejected(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    other = tmp_path / "other"
    other.mkdir()
    _git(other, "init", "-q", "-b", "master")
    _git(other, "config", "user.email", "t@t")
    _git(other, "config", "user.name", "t")
    (other / "x.txt").write_text("x\n", encoding="utf-8")
    _git(other, "add", ".")
    _git(other, "commit", "-qm", "unrelated")
    unrelated_bundle = tmp_path / "unrelated.bundle"
    _git(other, "bundle", "create", str(unrelated_bundle), "master")

    with pytest.raises(MrReviewError) as excinfo:
        import_snapshot(manifest, unrelated_bundle, home=tmp_path / "reviews")
    assert excinfo.value.code == "review-object-missing"

    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    assert _store_rows(store, "SELECT * FROM snapshots") == []
    assert store.current_snapshot_id(30) is None


def test_invalid_schema_manifest_rejected(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    del manifest["mr_url"]
    bundle = _bundle(tmp_path, mirror)
    with pytest.raises(MrReviewError) as excinfo:
        import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    assert excinfo.value.code == "invalid-schema"


def test_missing_bundle_file_rejected(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    with pytest.raises(MrReviewError) as excinfo:
        import_snapshot(manifest, tmp_path / "nope.bundle", home=tmp_path / "reviews")
    assert excinfo.value.code == "review-object-missing"


def test_tampered_installed_snapshot_detected_on_load(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    result = import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    path = result.store_root / "snapshots" / f"{manifest['snapshot_id']}.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["title"] = "tampered on disk"
    path.write_text(json.dumps(data), encoding="utf-8")

    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    with pytest.raises(MrReviewError) as excinfo:
        store.load_snapshot(manifest["snapshot_id"])
    assert excinfo.value.code == "hash-mismatch"



def _second_snapshot(tmp_path: Path, mirror: Path) -> tuple[dict, Path]:
    _git(mirror, "checkout", "-qb", "feature2", "feature")
    (mirror / "src" / "new.py").write_text("x = 2\n", encoding="utf-8")
    _git(mirror, "add", ".")
    _git(mirror, "commit", "-qm", "second feature")
    _git(mirror, "checkout", "-q", "master")
    manifest = _manifest(mirror, iid=31, source_branch="feature2")
    bundle = tmp_path / "code2.bundle"
    _git(mirror, "bundle", "create", str(bundle), "feature2", "master")
    return manifest, bundle


def test_parallel_imports_of_different_snapshots_both_succeed(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest_a, bundle_a = _manifest(mirror), _bundle(tmp_path, mirror)
    manifest_b, bundle_b = _second_snapshot(tmp_path, mirror)
    home = tmp_path / "reviews"
    results: dict[str, ImportResult] = {}
    errors: list[Exception] = []

    def run(tag: str, manifest: dict, bundle: Path) -> None:
        try:
            results[tag] = import_snapshot(manifest, bundle, home=home)
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [
        threading.Thread(target=run, args=("a", manifest_a, bundle_a)),
        threading.Thread(target=run, args=("b", manifest_b, bundle_b)),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert errors == []
    assert results["a"].status == "imported"
    assert results["b"].status == "imported"

    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx", home=home)
    assert len(_store_rows(store, "SELECT * FROM snapshots")) == 2
    assert store.current_snapshot_id(30) == manifest_a["snapshot_id"]
    assert store.current_snapshot_id(31) == manifest_b["snapshot_id"]
    refs = store.git.list_refs()
    for manifest in (manifest_a, manifest_b):
        sid = manifest["snapshot_id"]
        assert refs[f"refs/mr-review/{sid}/source"] == manifest["source_sha"]


def test_parallel_imports_of_same_snapshot_are_idempotent(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    home = tmp_path / "reviews"
    results: list[ImportResult] = []
    errors: list[Exception] = []

    def run() -> None:
        try:
            results.append(import_snapshot(manifest, bundle, home=home))
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [threading.Thread(target=run) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert errors == []
    assert sorted(r.status for r in results) == ["already-imported", "imported"]
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx", home=home)
    assert len(_store_rows(store, "SELECT * FROM snapshots")) == 1
    assert len(_store_rows(store, "SELECT * FROM current_snapshot")) == 1


def test_import_lock_timeout_is_typed_error(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    home = tmp_path / "reviews"
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx", home=home)
    lock_file = store.root / "import.lock"
    lock_file.write_text("held by another process", encoding="utf-8")

    with pytest.raises(MrReviewError) as excinfo:
        store.import_snapshot(manifest, bundle, lock_timeout=0.3, stale_after=9999.0)
    assert excinfo.value.code == "store-locked"
    assert _store_rows(store, "SELECT * FROM snapshots") == []
    assert store.current_snapshot_id(30) is None

    lock_file.unlink()
    result = store.import_snapshot(manifest, bundle)
    assert result.status == "imported"


def test_stale_import_lock_is_broken(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    home = tmp_path / "reviews"
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx", home=home)
    lock_file = store.root / "import.lock"
    lock_file.write_text("crashed holder", encoding="utf-8")
    os.utime(lock_file, (0, 0))

    result = store.import_snapshot(manifest, bundle, lock_timeout=5.0, stale_after=1.0)
    assert result.status == "imported"
    assert not lock_file.exists()



def test_cas_write_insert_update_conflict(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    key = {"snapshot_id": manifest["snapshot_id"], "path": "src/app.py"}

    assert store.cas_write("progress", key, 0, {"payload": json.dumps({"seen": 1})}) == 1
    assert store.cas_write("progress", key, 1, {"payload": json.dumps({"seen": 2})}) == 2

    with pytest.raises(MrReviewError) as excinfo:
        store.cas_write("progress", key, 0, {"payload": "{}"})
    assert excinfo.value.code == "revision-conflict"
    with pytest.raises(MrReviewError) as excinfo:
        store.cas_write("progress", key, 1, {"payload": "{}"})
    assert excinfo.value.code == "revision-conflict"

    rows = _store_rows(store, "SELECT revision, payload FROM progress")
    assert rows == [(2, json.dumps({"seen": 2}))]


def test_cas_write_concurrent_conflict_is_typed_error(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    key = {"snapshot_id": manifest["snapshot_id"], "path": "src/app.py"}
    store.cas_write("progress", key, 0, {"payload": "{}"})
    outcomes: list[str] = []
    barrier = threading.Barrier(2)

    def run() -> None:
        barrier.wait()
        try:
            store.cas_write("progress", key, 1, {"payload": json.dumps({"w": 1})})
            outcomes.append("ok")
        except MrReviewError as exc:
            outcomes.append(exc.code)

    threads = [threading.Thread(target=run) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert sorted(outcomes) == ["ok", "revision-conflict"]
    rows = _store_rows(store, "SELECT revision FROM progress")
    assert rows == [(2,)]


def test_cas_write_rejects_unknown_table_and_bad_columns(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    with pytest.raises(MrReviewError) as excinfo:
        store.cas_write("snapshots", {"snapshot_id": "x"}, 0, {"code_id": "y"})
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        store.cas_write("progress", {"path; DROP TABLE x": "1"}, 0, {"payload": "{}"})
    assert excinfo.value.code == "invalid-schema"
    with pytest.raises(MrReviewError) as excinfo:
        store.cas_write("progress", {"path": "1"}, -1, {"payload": "{}"})
    assert excinfo.value.code == "invalid-schema"



def test_environment_path_validates_layout(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")

    resolved = store.environment_path("env-1")
    assert resolved == (store.root / "environments" / "env-1").resolve()

    for bad in ("../escape", "..\\escape", "C:/evil", "\\\\srv\\share", "-opt",
                "a/b", "a.b", "", None, "."):
        with pytest.raises(MrReviewError) as excinfo:
            store.environment_path(bad)
        assert excinfo.value.code == "invalid-schema"


@pytest.mark.skipif(not IS_WINDOWS, reason="junctions are a Windows feature")
def test_environment_path_rejects_junction_escape(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    outside = tmp_path / "outside"
    outside.mkdir()
    link = store.root / "environments" / "evil"
    proc = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(outside)],
                          capture_output=True, text=True, timeout=30, check=False)
    if proc.returncode != 0:
        pytest.skip("mklink /J unavailable")
    with pytest.raises(MrReviewError) as excinfo:
        store.environment_path("evil")
    assert excinfo.value.code == "invalid-schema"


def test_environment_path_rejects_dangling_symlink(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    link = store.root / "environments" / "dangle"
    try:
        link.symlink_to(store.root / "environments" / "nowhere",
                        target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("os.symlink requires privileges on this Windows setup")
    with pytest.raises(MrReviewError) as excinfo:
        store.environment_path("dangle")
    assert excinfo.value.code == "review-object-missing"



def test_load_snapshot_rejects_unknown_and_malformed_ids(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    store = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")

    with pytest.raises(MrReviewError) as excinfo:
        store.load_snapshot("f" * 64)
    assert excinfo.value.code == "review-object-missing"
    for bad in ("ZZZ", "../escape", "a" * 63, None, ""):
        with pytest.raises(MrReviewError) as excinfo:
            store.load_snapshot(bad)
        assert excinfo.value.code == "invalid-schema"


def test_store_open_is_idempotent_and_shareable(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    again = MrReviewStore("https://gitlab.example.com", 42, "phonyx",
                          home=tmp_path / "reviews")
    assert again.current_snapshot_id(30) == manifest["snapshot_id"]
    assert again.load_snapshot(manifest["snapshot_id"])["iid"] == 30


def test_git_reader_is_bound_to_store_objects_only(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    bundle = _bundle(tmp_path, mirror)
    result = import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    reader = MrReviewGit(result.store_root / "objects.git")
    entry = reader.ls_tree(manifest["source_sha"], "src/app.py")
    assert entry is not None
    _, blob = reader.read_blob(entry.oid, 0, 100)
    assert blob == b"print('v2')\n"
