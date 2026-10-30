"""Managed review environment tests: real git worktrees on the real store
(task-2 helper patterns reused by import), malicious global-config fixture
(smudge filter + hooksPath hook), gitlink/LFS incomplete-checkout
declarations, typed release refusals, probe evidence records and
ordinary-mirror immutability."""
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.mr_review_environment import (
    environment_lease,
    mr_review_materialize,
    mr_review_probe,
)
from lgm_core.mr_review_models import MrReviewError, canonical_json, sha256_hex
from lgm_core.mr_review_store import MrReviewStore, import_snapshot
from test_mr_review_store import _bundle, _git, _manifest, _mirror_state, _ordinary_mirror

IS_WINDOWS = os.name == "nt"
_EMPTY_DIGEST = hashlib.sha256(b"").hexdigest()


def _imported(tmp_path: Path, repo: Path, manifest: dict) -> MrReviewStore:
    bundle = _bundle(tmp_path, repo)
    import_snapshot(manifest, bundle, home=tmp_path / "reviews")
    return MrReviewStore(manifest["gitlab_url"], manifest["target_project_id"],
                         manifest["repo"], home=tmp_path / "reviews")


def _gitignore_repo(tmp_path: Path) -> Path:
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-q", "-b", "master")
    _git(repo, "config", "user.email", "t@t")
    _git(repo, "config", "user.name", "t")
    (repo / ".gitignore").write_text("*.log\n", encoding="utf-8")
    (repo / "a.txt").write_text("a\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    (repo / "b.txt").write_text("b\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "feature")
    _git(repo, "checkout", "-q", "master")
    return repo


def test_create_reuse_fresh_lifecycle(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)
    before = _mirror_state(mirror)

    first = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"],
                                  purpose="review")
    assert first["action"] == "create"
    assert first["mode"] == "fresh"
    assert first["status"] == "clean"
    assert first["sha"] == manifest["source_sha"]
    assert first["purpose"] == "review"
    env_path = Path(first["path"])
    assert env_path == store.environment_path(first["environment_id"])
    assert env_path.is_dir()
    assert _git(env_path, "rev-parse", "HEAD").stdout.strip() == manifest["source_sha"]
    assert (env_path / "src" / "app.py").read_text(encoding="utf-8") == "print('v2')\n"
    assert any("file-level only" in lim for lim in first["limitations"])

    second = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    assert second["environment_id"] == first["environment_id"]
    assert second["mode"] == "reused"
    assert second["status"] == "clean"
    assert second["sha"] == manifest["source_sha"]

    third = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"],
                                  fresh=True, purpose="independent")
    assert third["environment_id"] != first["environment_id"]
    assert third["mode"] == "fresh"
    assert third["status"] == "clean"
    assert third["purpose"] == "independent"
    assert Path(third["path"]).is_dir()

    status = mr_review_materialize(store, "status", environment_id=first["environment_id"])
    assert status["action"] == "status"
    assert status["sha"] == manifest["source_sha"]
    assert status["status"] == "clean"
    assert status["tracked_diff_digest"] == _EMPTY_DIGEST
    assert status["untracked"] == {"count": 0, "paths": []}
    assert status["ignored"] == {"count": 0, "paths": []}

    assert _mirror_state(mirror) == before


def test_dirty_reusable_environment_is_never_reset(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)
    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_path = Path(created["path"])
    (env_path / "src" / "app.py").write_text("print('hacked')\n", encoding="utf-8")

    again = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    assert again["environment_id"] == created["environment_id"]
    assert again["mode"] == "reused"
    assert again["status"] == "dirty"
    assert any("fresh=true" in lim for lim in again["limitations"])
    assert (env_path / "src" / "app.py").read_text(encoding="utf-8") == "print('hacked')\n"

    fresh = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"],
                                  fresh=True)
    assert fresh["environment_id"] != created["environment_id"]
    assert fresh["status"] == "clean"
    assert (Path(fresh["path"]) / "src" / "app.py").read_text(encoding="utf-8") \
        == "print('v2')\n"


def test_materialize_validates_parameters(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)

    for bad_action in ("", "destroy", "CREATE", None, 1):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_materialize(store, bad_action)
        assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "create")
    assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"],
                              environment_id="env-x")
    assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"],
                              fresh="yes")
    assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "status")
    assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "status", environment_id="env-x", fresh=True)
    assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "create", snapshot_id="f" * 64)
    assert excinfo.value.code == "review-object-missing"


def test_status_reports_digest_and_output_summaries(tmp_path):
    repo = _gitignore_repo(tmp_path)
    manifest = _manifest(repo)
    store = _imported(tmp_path, repo, manifest)
    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_id = created["environment_id"]
    env_path = Path(created["path"])

    clean = mr_review_materialize(store, "status", environment_id=env_id)
    assert clean["status"] == "clean"
    assert clean["tracked_diff_digest"] == _EMPTY_DIGEST

    (env_path / "b.txt").write_text("changed\n", encoding="utf-8")
    (env_path / "extra.txt").write_text("new\n", encoding="utf-8")
    (env_path / "build.log").write_text("log\n", encoding="utf-8")

    dirty = mr_review_materialize(store, "status", environment_id=env_id)
    assert dirty["status"] == "dirty"
    assert dirty["tracked_diff_digest"] != clean["tracked_diff_digest"]
    assert dirty["untracked"]["count"] == 1
    assert dirty["untracked"]["paths"] == ["extra.txt"]
    assert dirty["ignored"]["count"] == 1
    assert dirty["ignored"]["paths"] == ["build.log"]

def test_malicious_global_config_filter_and_hook_do_not_run(tmp_path, monkeypatch):
    mirror = _ordinary_mirror(tmp_path)
    _git(mirror, "checkout", "-q", "feature")
    (mirror / ".gitattributes").write_text("*.bin filter=canary\n", encoding="utf-8")
    (mirror / "data.bin").write_bytes(b"raw blob bytes\n")
    _git(mirror, "add", ".")
    _git(mirror, "commit", "-qm", "add filtered file")
    _git(mirror, "checkout", "-q", "master")
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)
    before = _mirror_state(mirror)

    canary = tmp_path / "canary.txt"
    malicious_home = tmp_path / "malicious-home"
    hooks_dir = malicious_home / "hooks"
    hooks_dir.mkdir(parents=True)
    (malicious_home / ".gitconfig").write_text(
        "[filter \"canary\"]\n"
        f"\tsmudge = echo SMUDGE-RAN >> {canary.as_posix()}\n"
        f"\tclean = echo CLEAN-RAN >> {canary.as_posix()}\n"
        "[core]\n"
        f"\thooksPath = {hooks_dir.as_posix()}\n",
        encoding="utf-8")
    (hooks_dir / "post-checkout").write_text(
        f"#!/bin/sh\necho POST-CHECKOUT-RAN >> {canary.as_posix()}\n", encoding="utf-8")
    monkeypatch.setenv("HOME", str(malicious_home))

    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    assert created["status"] == "clean"
    assert not canary.exists()
    env_path = Path(created["path"])
    assert (env_path / "data.bin").read_bytes() == b"raw blob bytes\n"
    assert _mirror_state(mirror) == before

    control = tmp_path / "control-worktree"
    proc = subprocess.run(
        ["git", "-C", str(store.root / "objects.git"), "worktree", "add",
         "--detach", "--", str(control), manifest["source_sha"]],
        capture_output=True, text=True, timeout=120, check=False)
    assert proc.returncode == 0, proc.stderr
    assert canary.exists() and canary.read_text(encoding="utf-8").strip()


def test_gitlink_and_lfs_pointer_declared_incomplete(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-q", "-b", "master")
    _git(repo, "config", "user.email", "t@t")
    _git(repo, "config", "user.name", "t")
    (repo / "README.md").write_text("base\n", encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-qm", "base")
    _git(repo, "checkout", "-qb", "feature")
    (repo / "app.py").write_text("print('v2')\n", encoding="utf-8")
    pointer = (b"version https://git-lfs.github.com/spec/v1\n"
               b"oid sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08\n"
               b"size 5\n")
    (repo / "asset.bin").write_bytes(pointer)
    _git(repo, "add", ".")
    master_sha = _git(repo, "rev-parse", "master").stdout.strip()
    _git(repo, "update-index", "--add", "--cacheinfo", f"160000,{master_sha},vendor/lib")
    _git(repo, "commit", "-qm", "feature")
    _git(repo, "checkout", "-q", "master")
    manifest = _manifest(repo)
    store = _imported(tmp_path, repo, manifest)

    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    limitations = " ".join(created["limitations"])
    assert "incomplete-checkout" in limitations
    assert "gitlink" in limitations
    assert "LFS pointer" in limitations
    assert created["status"] == "clean"
    env_path = Path(created["path"])
    assert (env_path / "asset.bin").read_bytes() == pointer
    assert not any((env_path / "vendor" / "lib").iterdir())

    released = mr_review_materialize(store, "release",
                                     environment_id=created["environment_id"])
    assert released["status"] == "released"


def test_dirty_release_refused_and_environment_preserved(tmp_path):
    repo = _gitignore_repo(tmp_path)
    manifest = _manifest(repo)
    store = _imported(tmp_path, repo, manifest)
    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_id = created["environment_id"]
    env_path = Path(created["path"])
    original = (env_path / "b.txt").read_bytes()

    (env_path / "b.txt").write_text("modified\n", encoding="utf-8")
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "release", environment_id=env_id)
    assert excinfo.value.code == "incomplete-checkout"
    assert env_path.is_dir()

    (env_path / "b.txt").write_bytes(original)
    (env_path / "untracked.txt").write_text("u\n", encoding="utf-8")
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "release", environment_id=env_id)
    assert excinfo.value.code == "incomplete-checkout"
    assert env_path.is_dir()

    (env_path / "untracked.txt").unlink()
    (env_path / "build.log").write_text("ignored output\n", encoding="utf-8")
    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "release", environment_id=env_id)
    assert excinfo.value.code == "incomplete-checkout"
    assert (env_path / "build.log").exists()

    (env_path / "build.log").unlink()
    released = mr_review_materialize(store, "release", environment_id=env_id)
    assert released == {"action": "release", "environment_id": env_id,
                        "status": "released"}
    assert not env_path.exists()


def test_busy_release_refused(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)
    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_id = created["environment_id"]

    with environment_lease(store, env_id):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_materialize(store, "release", environment_id=env_id)
        assert excinfo.value.code == "store-locked"
        busy = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
        assert busy["environment_id"] == env_id
        assert busy["mode"] == "reused"
        assert busy["status"] == "busy"

    released = mr_review_materialize(store, "release", environment_id=env_id)
    assert released["status"] == "released"


def test_unknown_and_malicious_environment_ids_refused(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)

    for action in ("status", "release"):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_materialize(store, action, environment_id="env-unknown")
        assert excinfo.value.code == "review-object-missing"

    for bad in ("../escape", "..\\escape", "C:/evil", "\\\\srv\\share", "-opt",
                "a/b", "a.b", "", None, "."):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_materialize(store, "status", environment_id=bad)
        assert excinfo.value.code == "invalid-schema"


@pytest.mark.skipif(not IS_WINDOWS, reason="junctions are a Windows feature")
def test_junction_escape_rejected_and_outside_preserved(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "keep.txt").write_text("precious\n", encoding="utf-8")
    link = store.root / "environments" / "evil"
    proc = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(outside)],
                          capture_output=True, text=True, timeout=30, check=False)
    if proc.returncode != 0:
        pytest.skip("mklink /J unavailable")

    for action in ("status", "release"):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_materialize(store, action, environment_id="evil")
        assert excinfo.value.code == "invalid-schema"
    assert (outside / "keep.txt").read_text(encoding="utf-8") == "precious\n"

def _probe_repo(tmp_path: Path, script: str) -> tuple[Path, dict]:
    mirror = _ordinary_mirror(tmp_path)
    _git(mirror, "checkout", "-q", "feature")
    (mirror / "probe.py").write_text(script, encoding="utf-8")
    _git(mirror, "add", "probe.py")
    _git(mirror, "commit", "-qm", "add probe script")
    _git(mirror, "checkout", "-q", "master")
    return mirror, _manifest(mirror)


def test_probe_run_evidence_at_exact_source_sha(tmp_path):
    mirror, manifest = _probe_repo(tmp_path, (
        "import sys\n"
        "sys.stdout.write('probe stdout at sha\\n')\n"
        "sys.stderr.write('probe stderr detail\\n')\n"
        "sys.exit(7)\n"))
    store = _imported(tmp_path, mirror, manifest)
    before = _mirror_state(mirror)

    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_id = created["environment_id"]
    env_path = Path(created["path"])
    assert (env_path / "probe.py").is_file()

    argv = [sys.executable, "probe.py"]
    evidence = mr_review_probe(store, env_id, argv, test_count=3)
    assert evidence["argv"] == argv
    assert evidence["cwd"] == str(env_path)
    assert evidence["source_sha"] == manifest["source_sha"]
    assert evidence["environment_id"] == env_id
    assert evidence["exit_code"] == 7
    assert evidence["test_count"] == 3
    assert evidence["tracked_diff_digest"] == evidence["tracked_diff_digest_before"]
    assert evidence["tracked_diff_digest"] == _EMPTY_DIGEST
    assert evidence["probe_digest"] == sha256_hex(canonical_json({"argv": argv}))
    stdout_log = store.root / evidence["log_paths"]["stdout"]
    stderr_log = store.root / evidence["log_paths"]["stderr"]
    assert stdout_log.parent == store.root / "probes" / env_id
    assert b"probe stdout at sha" in stdout_log.read_bytes()
    assert b"probe stderr detail" in stderr_log.read_bytes()

    uncounted = mr_review_probe(store, env_id, argv)
    assert uncounted["test_count"] is None

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_probe(store, env_id, ["definitely-missing-command-xyz"])
    assert excinfo.value.code == "invalid-schema"

    assert _mirror_state(mirror) == before


def test_probe_timeout_records_partial_evidence(tmp_path):
    mirror, manifest = _probe_repo(tmp_path, (
        "import sys, time\n"
        "sys.stdout.write('started\\n')\n"
        "sys.stdout.flush()\n"
        "time.sleep(30)\n"))
    store = _imported(tmp_path, mirror, manifest)
    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_id = created["environment_id"]

    evidence = mr_review_probe(store, env_id, [sys.executable, "probe.py"], timeout=2.0)
    assert evidence["exit_code"] is None
    assert evidence["test_count"] is None
    stdout_log = store.root / evidence["log_paths"]["stdout"]
    assert b"started" in stdout_log.read_bytes()
    status = mr_review_materialize(store, "status", environment_id=env_id)
    assert status["status"] == "clean"


def test_release_clean_environment_and_recreate(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)
    before = _mirror_state(mirror)
    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_id = created["environment_id"]

    released = mr_review_materialize(store, "release", environment_id=env_id)
    assert released == {"action": "release", "environment_id": env_id,
                        "status": "released"}
    assert not store.environment_path(env_id).exists()

    listing = _git(store.root / "objects.git", "worktree", "list").stdout
    assert str(store.root / "environments") not in listing

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_materialize(store, "status", environment_id=env_id)
    assert excinfo.value.code == "review-object-missing"

    recreated = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    assert recreated["environment_id"] != env_id
    assert recreated["mode"] == "fresh"
    assert recreated["status"] == "clean"
    assert _mirror_state(mirror) == before


def test_missing_environment_status_and_stale_release(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)
    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_id = created["environment_id"]
    shutil.rmtree(Path(created["path"]))

    status = mr_review_materialize(store, "status", environment_id=env_id)
    assert status["status"] == "missing"
    assert status["tracked_diff_digest"] == ""
    assert status["untracked"] == {"count": 0, "paths": []}

    again = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    assert again["environment_id"] == env_id
    assert again["mode"] == "reused"
    assert again["status"] == "missing"
    assert any("fresh=true" in lim for lim in again["limitations"])

    released = mr_review_materialize(store, "release", environment_id=env_id)
    assert released["status"] == "released"


def test_probe_validates_argv_and_environment(tmp_path):
    mirror = _ordinary_mirror(tmp_path)
    manifest = _manifest(mirror)
    store = _imported(tmp_path, mirror, manifest)
    created = mr_review_materialize(store, "create", snapshot_id=manifest["snapshot_id"])
    env_id = created["environment_id"]

    for bad_argv in ([], ["", "x"], "python x", [1], None):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_probe(store, env_id, bad_argv)
        assert excinfo.value.code == "invalid-schema"

    for bad_count in (-1, True, "3"):
        with pytest.raises(MrReviewError) as excinfo:
            mr_review_probe(store, env_id, [sys.executable, "probe.py"],
                            test_count=bad_count)
        assert excinfo.value.code == "invalid-schema"

    with pytest.raises(MrReviewError) as excinfo:
        mr_review_probe(store, "env-unknown", [sys.executable, "probe.py"])
    assert excinfo.value.code == "review-object-missing"
