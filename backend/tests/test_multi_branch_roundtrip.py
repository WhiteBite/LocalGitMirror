"""
E2E test: Multi-branch sync roundtrip.

Scenario:
  1) Backend creates repo with initial commit on 'master'
  2) "Work" machine has 'master' + 'feature-xyz' branches with different commits
  3) Work machine creates an encrypted bundle with BOTH branches
  4) Work uploads the bundle via upload-and-apply
  5) Backend workspace should have BOTH branches
  6) Backend bare repo should have BOTH branches
  7) Home machine calls export-dump → receives bundle with BOTH branches
  8) Home machine applies the bundle → gets BOTH branches locally
"""
import base64
import json
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core import git_bundle
from app.core.bundle_crypto import MAGIC, decrypt_dump_to_bundle, encrypt_bundle_to_dump
from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_form_post, parse_envelope


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(
        ["git", *args], cwd=str(cwd), capture_output=True, text=True
    )
    if proc.returncode != 0:
        raise AssertionError(
            f"git {' '.join(args)} failed\n"
            f"cwd={cwd}\nexit={proc.returncode}\n"
            f"stdout={proc.stdout}\nstderr={proc.stderr}"
        )
    return proc


def _git_branches(cwd: Path) -> set:
    """Return set of local branch names."""
    proc = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname:short)", "refs/heads"],
        cwd=str(cwd), capture_output=True, text=True
    )
    return {b.strip() for b in proc.stdout.splitlines() if b.strip()}


def _build_client(storage: Path) -> TestClient:
    repo_manager = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=repo_manager,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return TestClient(app)


def test_multi_branch_roundtrip_work_to_home(tmp_path: Path, monkeypatch):
    """
    Full flow:
      Work creates bundle with master + feature-xyz →
      Uploads to backend →
      Backend stores BOTH branches →
      Home pulls export-dump →
      Home decrypts and verifies BOTH branches are in the bundle
    """
    password = "e2e-sync-password"
    monkeypatch.setenv("SYNC_PASSWORD", password)

    # ── Setup backend storage ──────────────────────────────
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )

    repo_name = f"e2e-multibranch-{int(time.time())}"
    client = _build_client(storage)

    # Create repo on backend
    created = client.post("/api/documents/collection", json={"name": repo_name})
    assert created.status_code == 200, created.text
    assert created.json().get("success") is True, created.text

    workspace = storage / repo_name
    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"
    assert workspace.exists(), "Workspace not created"
    assert bare.exists(), "Bare repo not created"

    # ── Step 1: Simulate "work" machine ────────────────────
    work = tmp_path / "work_machine"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "work@example.com")
    _run_git(work, "config", "user.name", "Work User")
    _run_git(work, "checkout", "-B", "master")

    # Initial commit on master
    (work / "readme.txt").write_text("initial\n", encoding="utf-8")
    _run_git(work, "add", "readme.txt")
    _run_git(work, "commit", "-m", "master: initial commit")
    master_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()

    # Create feature branch with additional commit
    _run_git(work, "checkout", "-b", "feature-xyz")
    (work / "feature.txt").write_text("feature work\n", encoding="utf-8")
    _run_git(work, "add", "feature.txt")
    _run_git(work, "commit", "-m", "feature-xyz: add feature work")
    feature_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()
    assert feature_hash != master_hash, "Feature should have different hash from master"

    # Create bundle with BOTH branches (feature-xyz is current, master is additional)
    bundle_path = tmp_path / "work_bundle.bundle"
    _run_git(work, "bundle", "create", str(bundle_path), "feature-xyz", "master")

    # Verify bundle has both branches
    list_proc = _run_git(work, "bundle", "list-heads", str(bundle_path))
    bundle_output = list_proc.stdout
    assert "refs/heads/feature-xyz" in bundle_output, f"Bundle missing feature-xyz: {bundle_output}"
    assert "refs/heads/master" in bundle_output, f"Bundle missing master: {bundle_output}"

    # Encrypt the bundle into a dump file
    dump_path = tmp_path / f"dump_{repo_name}_test.dmp"
    encrypt_bundle_to_dump(bundle_path, dump_path, password)

    # ── Step 2: Upload to backend via upload-and-apply ─────
    # Request metadata (repo) travels in an encrypted envelope ("e" form field),
    # the dump rides as the multipart attachment — same as the IDEA plugin.
    upload_res = envelope_form_post(
        client, "/api/documents/upload", {"repo": repo_name}, password,
        files={"attachment": (dump_path.name, dump_path.read_bytes(), "application/octet-stream")},
    )
    assert upload_res.status_code == 200, upload_res.text
    body = parse_envelope(upload_res.json(), password)
    assert body.get("success") is True, f"Upload failed: {body}"

    # ── Step 3: Verify backend workspace has BOTH branches ──
    ws_branches = _git_branches(workspace)
    assert "master" in ws_branches, f"Backend workspace missing 'master'. Branches: {ws_branches}"
    assert "feature-xyz" in ws_branches, f"Backend workspace missing 'feature-xyz'. Branches: {ws_branches}"

    # Verify hashes match
    ws_master = _run_git(workspace, "rev-parse", "refs/heads/master").stdout.strip()
    ws_feature = _run_git(workspace, "rev-parse", "refs/heads/feature-xyz").stdout.strip()
    assert ws_master == master_hash, f"master hash mismatch: {ws_master} != {master_hash}"
    assert ws_feature == feature_hash, f"feature-xyz hash mismatch: {ws_feature} != {feature_hash}"

    # ── Step 4: Verify backend bare repo has BOTH branches ──
    bare_branches_proc = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname:short)", "refs/heads"],
        cwd=str(bare), capture_output=True, text=True
    )
    bare_branches = {b.strip() for b in bare_branches_proc.stdout.splitlines() if b.strip()}
    assert "master" in bare_branches, f"Bare repo missing 'master'. Branches: {bare_branches}"
    assert "feature-xyz" in bare_branches, f"Bare repo missing 'feature-xyz'. Branches: {bare_branches}"

    # ── Step 5: Home machine calls export-dump ──────────────
    export_res = envelope_form_post(client, "/api/documents/export", {"repo": repo_name}, password)
    assert export_res.status_code == 200, f"Export failed: {export_res.status_code} {export_res.text}"

    export_inner = parse_envelope(export_res.json(), password)
    assert export_inner.get("status") == "ok", f"Export status: {export_inner}"
    exported_dump = tmp_path / "home_exported.dmp"
    exported_dump.write_bytes(base64.b64decode(export_res.json()["d"]))

    # Decrypt the export dump to a bundle
    exported_bundle = tmp_path / "home_exported.bundle"
    decrypt_dump_to_bundle(exported_dump, exported_bundle, password)

    # Verify exported bundle has BOTH branches
    home_list = subprocess.run(
        ["git", "bundle", "list-heads", str(exported_bundle)],
        cwd=str(tmp_path), capture_output=True, text=True
    )
    assert home_list.returncode == 0, f"bundle list-heads failed: {home_list.stderr}"
    export_output = home_list.stdout
    assert "refs/heads/master" in export_output, (
        f"Exported bundle missing 'master'. Bundle refs:\n{export_output}"
    )
    assert "refs/heads/feature-xyz" in export_output, (
        f"Exported bundle missing 'feature-xyz'. Bundle refs:\n{export_output}"
    )

    # ── Step 6: Home machine applies the bundle ─────────────
    home = tmp_path / "home_machine"
    home.mkdir()
    _run_git(home, "init")
    _run_git(home, "config", "user.email", "home@example.com")
    _run_git(home, "config", "user.name", "Home User")

    # Need an initial commit to be able to detach HEAD
    (home / ".gitkeep").write_text("", encoding="utf-8")
    _run_git(home, "add", ".gitkeep")
    _run_git(home, "commit", "-m", "init")

    # Detach HEAD so fetch can update all branch refs (same as backend does)
    _run_git(home, "checkout", "--detach")

    # Fetch all branches from the exported bundle
    _run_git(home, "fetch", str(exported_bundle), "+refs/heads/*:refs/heads/*")

    home_branches = _git_branches(home)
    assert "master" in home_branches, f"Home missing 'master' after apply. Branches: {home_branches}"
    assert "feature-xyz" in home_branches, f"Home missing 'feature-xyz' after apply. Branches: {home_branches}"

    # Verify hashes
    home_master = _run_git(home, "rev-parse", "refs/heads/master").stdout.strip()
    home_feature = _run_git(home, "rev-parse", "refs/heads/feature-xyz").stdout.strip()
    assert home_master == master_hash, f"Home master mismatch: {home_master} != {master_hash}"
    assert home_feature == feature_hash, f"Home feature mismatch: {home_feature} != {feature_hash}"

    # checkout feature and verify files
    _run_git(home, "checkout", "feature-xyz")
    assert (home / "feature.txt").exists(), "feature.txt missing after checkout feature-xyz"
    assert (home / "readme.txt").exists(), "readme.txt missing after checkout feature-xyz"


class _P:
    def __init__(self, rc=0, out="", err=""):
        self.returncode = rc
        self.stdout = out
        self.stderr = err


def _mock_apply_env(monkeypatch, tmp_path: Path, bare_refs: dict, heads: str = None, bare_fetch=None):
    """Mocked apply environment. bare_refs: branch -> hash that rev-parse in
    the bare repo reports (the landing verification source of truth)."""
    repo = "single-fetch-repo"
    workspace = tmp_path / repo
    bare = tmp_path / f"{repo}.git"
    workspace.mkdir()
    bare.mkdir()

    class _RM:
        def get_repos(self):
            return [repo]

        def _get_workspace_path(self, _r):
            return workspace

        def _get_bare_path(self, _r):
            return bare

    monkeypatch.setattr(git_bundle, "repo_manager", _RM(), raising=False)
    monkeypatch.setenv("SYNC_PASSWORD", "pwd")

    dump = tmp_path / "dump_single-fetch-repo_20260101_0000.dmp"
    dump.write_bytes(MAGIC + b"x" * 64)

    def _fake_decrypt(_dump, out, _pwd):
        out.write_bytes(b"bundle")

    monkeypatch.setattr(git_bundle, "decrypt_dump_to_bundle", _fake_decrypt)

    calls = []

    def _fake_git(wd, *args):
        calls.append((wd, args))
        if args[:2] == ("status", "--porcelain"):
            return _P(0, "")
        if args[:2] == ("bundle", "list-heads"):
            return _P(0, heads or "aaa1111 refs/heads/master\nbbb2222 refs/heads/feature\nccc3333 refs/heads/ulw/w2\n")
        if args[:3] == ("symbolic-ref", "--short", "-q"):
            return _P(0, "master\n")
        if args[0] == "fetch" and wd == bare and bare_fetch is not None:
            return bare_fetch(args)
        if args[0] == "fetch":
            return _P(0, "")
        if args[0] == "rev-parse" and wd == bare:
            branch = args[1][len("refs/heads/"):]
            if branch in bare_refs:
                return _P(0, bare_refs[branch] + "\n")
            return _P(128, "", "fatal: ambiguous argument 'refs/heads/...'")
        if args[:2] == ("log", "-1"):
            return _P(0, "aaa1111 head\n")
        return _P(0, "", "")

    monkeypatch.setattr(git_bundle, "_git", _fake_git)
    return repo, dump, calls, workspace, bare


def _apply(repo, dump):
    return git_bundle._apply_dump_to_repo_and_sync_bare(
        dump_path=dump, repo_name=repo, dump_filename=dump.name)


def test_apply_fetches_all_branches_into_bare_in_one_fetch(tmp_path: Path, monkeypatch):
    repo, dump, calls, ws, bare = _mock_apply_env(
        monkeypatch, tmp_path,
        bare_refs={"master": "aaa1111", "feature": "bbb2222", "ulw/w2": "ccc3333"},
    )

    res = _apply(repo, dump)

    assert res["success"] is True, res
    assert set(res["branches"]) == {"master", "feature", "ulw/w2"}

    bare_fetches = [c for wd, c in calls if c[0] == "fetch" and wd == bare]
    assert len(bare_fetches) == 1, f"expected one bare fetch, got {bare_fetches}"
    assert bare_fetches[0][1] == "--force"
    assert set(bare_fetches[0][3:]) == {
        "+refs/heads/master:refs/heads/master",
        "+refs/heads/feature:refs/heads/feature",
        "+refs/heads/ulw/w2:refs/heads/ulw/w2",
    }

    ws_fetches = [c for wd, c in calls if c[0] == "fetch" and wd == ws]
    assert len(ws_fetches) == 1, f"expected one workspace sync fetch, got {ws_fetches}"
    assert ws_fetches[0][1] == "--force"
    assert set(ws_fetches[0][3:]) == {
        "+refs/heads/master:refs/lgm/incoming/master",
        "+refs/heads/feature:refs/lgm/incoming/feature",
        "+refs/heads/ulw/w2:refs/lgm/incoming/ulw/w2",
    }

    assert not [c for wd, c in calls if c[0] == "push"], "apply must not push; bare is fed by fetch"

    ws_sync_idx = calls.index((ws, ws_fetches[0]))
    head_updates = [i for i, (wd, c) in enumerate(calls)
                    if c[0] == "update-ref" and str(c[1]).startswith("refs/heads/")]
    assert {calls[i][1][1] for i in head_updates} == {"refs/heads/feature", "refs/heads/ulw/w2"}
    assert all(i > ws_sync_idx for i in head_updates), "workspace refs move only after the sync fetch"


def test_apply_partial_landing_keeps_successful_branches(tmp_path: Path, monkeypatch):
    repo, dump, calls, ws, bare = _mock_apply_env(
        monkeypatch, tmp_path,
        bare_refs={"master": "aaa1111"},
    )

    res = _apply(repo, dump)

    assert res["success"] is True, res
    assert res["branches"] == ["master"]

    ws_fetches = [c for wd, c in calls if c[0] == "fetch" and wd == ws]
    assert len(ws_fetches) == 1
    assert ws_fetches[0][3:] == ("+refs/heads/master:refs/lgm/incoming/master",)

    failed_ref_updates = [c for wd, c in calls
                          if c[0] == "update-ref" and str(c[1]).startswith("refs/heads/feature")]
    assert failed_ref_updates == [], "branch that failed landing must not get a workspace ref"


def test_apply_landing_failure_for_all_branches_fails(tmp_path: Path, monkeypatch):
    repo, dump, calls, ws, bare = _mock_apply_env(
        monkeypatch, tmp_path,
        bare_refs={},
    )

    res = _apply(repo, dump)

    assert res["success"] is False, res
    assert "Failed to land any branch" in res["message"]
    assert not [c for wd, c in calls if c[0] == "fetch" and wd == ws], (
        "workspace must not be synced when nothing landed in bare"
    )


def test_apply_wildcard_fallback_sweeps_junk_refs_from_bare(tmp_path: Path, monkeypatch):
    def _bare_fetch(args):
        if "+refs/heads/*:refs/heads/*" in args:
            return _P(0, "")
        return _P(1, "", "fatal: multi-refspec bundle fetch quirk")

    repo, dump, calls, ws, bare = _mock_apply_env(
        monkeypatch, tmp_path,
        bare_refs={"master": "aaa1111"},
        heads="aaa1111 refs/heads/master\naaa1111 refs/heads/HEAD\n",
        bare_fetch=_bare_fetch,
    )

    res = _apply(repo, dump)

    assert res["success"] is True, res
    assert res["branches"] == ["master"]
    assert (bare, ("update-ref", "-d", "refs/heads/HEAD")) in calls, (
        "wildcard fallback must sweep junk refs out of bare"
    )


def test_apply_objects_resolve_in_bare_and_workspace(tmp_path: Path, monkeypatch):
    password = "e2e-objects-pw"
    monkeypatch.setenv("SYNC_PASSWORD", password)

    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )

    repo_name = f"e2e-objects-{int(time.time())}"
    client = _build_client(storage)
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200

    workspace = storage / repo_name
    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"

    work = tmp_path / "work_objects"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "work@example.com")
    _run_git(work, "config", "user.name", "Work User")
    _run_git(work, "checkout", "-B", "master")
    (work / "a.txt").write_text("a\n", encoding="utf-8")
    _run_git(work, "add", "a.txt")
    _run_git(work, "commit", "-m", "master commit")
    master_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()
    _run_git(work, "checkout", "-b", "feature")
    (work / "f.txt").write_text("f\n", encoding="utf-8")
    _run_git(work, "add", "f.txt")
    _run_git(work, "commit", "-m", "feature commit")
    feature_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()

    bundle = tmp_path / "objects.bundle"
    _run_git(work, "bundle", "create", str(bundle), "feature", "master")
    dump = tmp_path / f"dump_{repo_name}_objects.dmp"
    encrypt_bundle_to_dump(bundle, dump, password)

    upload = envelope_form_post(
        client, "/api/documents/upload", {"repo": repo_name}, password,
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert upload.status_code == 200, upload.text
    body = parse_envelope(upload.json(), password)
    assert body.get("success") is True, body
    assert set(body.get("branches", [])) == {"master", "feature"}

    for branch, expected in (("master", master_hash), ("feature", feature_hash)):
        for git_dir in (bare, workspace):
            rp = subprocess.run(
                ["git", "rev-parse", f"refs/heads/{branch}"], cwd=str(git_dir),
                capture_output=True, text=True,
            )
            assert rp.returncode == 0, f"{git_dir}: {rp.stderr}"
            assert rp.stdout.strip() == expected, f"{git_dir} {branch}: {rp.stdout.strip()} != {expected}"
            cat = subprocess.run(
                ["git", "cat-file", "-e", f"{expected}^{{commit}}"], cwd=str(git_dir),
                capture_output=True, text=True,
            )
            assert cat.returncode == 0, f"commit {expected} missing in {git_dir}"


def test_apply_junk_head_branch_never_lands_in_bare(tmp_path: Path, monkeypatch):
    password = "e2e-junk-pw"
    monkeypatch.setenv("SYNC_PASSWORD", password)

    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )

    repo_name = f"e2e-junk-{int(time.time())}"
    client = _build_client(storage)
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200

    workspace = storage / repo_name
    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"

    work = tmp_path / "work_junk"
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "work@example.com")
    _run_git(work, "config", "user.name", "Work User")
    _run_git(work, "checkout", "-B", "main")
    (work / "m.txt").write_text("m\n", encoding="utf-8")
    _run_git(work, "add", "m.txt")
    _run_git(work, "commit", "-m", "main commit")
    tip = _run_git(work, "rev-parse", "HEAD").stdout.strip()
    _run_git(work, "update-ref", "refs/heads/HEAD", tip)

    bundle = tmp_path / "junk.bundle"
    _run_git(work, "bundle", "create", str(bundle), "--all")
    heads = _run_git(work, "bundle", "list-heads", str(bundle)).stdout
    assert "refs/heads/HEAD" in heads, "precondition: bundle carries the junk ref"

    dump = tmp_path / f"dump_{repo_name}_junk.dmp"
    encrypt_bundle_to_dump(bundle, dump, password)

    upload = envelope_form_post(
        client, "/api/documents/upload", {"repo": repo_name}, password,
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert upload.status_code == 200, upload.text
    body = parse_envelope(upload.json(), password)
    assert body.get("success") is True, body

    for git_dir in (bare, workspace):
        proc = subprocess.run(
            ["git", "for-each-ref", "--format=%(refname)", "refs/heads"],
            cwd=str(git_dir), capture_output=True, text=True,
        )
        refnames = [r.strip() for r in proc.stdout.splitlines() if r.strip()]
        assert "refs/heads/HEAD" not in refnames, f"junk ref landed in {git_dir}: {refnames}"

    bare_main = subprocess.run(
        ["git", "rev-parse", "refs/heads/main"], cwd=str(bare),
        capture_output=True, text=True,
    )
    assert bare_main.stdout.strip() == tip


def test_apply_incremental_bundle_with_workspace_only_base(tmp_path: Path, monkeypatch):
    """Incremental bundle whose prerequisite commit exists only in the workspace
    (bare lags): the bare-first fetch fails on prerequisites and must relay the
    bundle through the workspace instead of erroring out."""
    password = "e2e-relay-pw"
    monkeypatch.setenv("SYNC_PASSWORD", password)

    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "E2E Bot", "user_email": "e2e@example.com"}}),
        encoding="utf-8",
    )

    repo_name = f"e2e-relay-{int(time.time())}"
    client = _build_client(storage)
    assert client.post("/api/documents/collection", json={"name": repo_name}).status_code == 200

    workspace = storage / repo_name
    bare = storage / ".lgm" / "bare" / f"{repo_name}.git"

    _run_git(workspace, "config", "user.email", "home@example.com")
    _run_git(workspace, "config", "user.name", "Home User")
    (workspace / "base.txt").write_text("base\n", encoding="utf-8")
    _run_git(workspace, "add", "base.txt")
    _run_git(workspace, "commit", "-m", "workspace-only base")
    base_hash = _run_git(workspace, "rev-parse", "HEAD").stdout.strip()
    branch = _run_git(workspace, "symbolic-ref", "--short", "HEAD").stdout.strip()

    bare_check = subprocess.run(
        ["git", "cat-file", "-e", f"{base_hash}^{{commit}}"], cwd=str(bare),
        capture_output=True, text=True,
    )
    assert bare_check.returncode != 0, "precondition: base commit must be workspace-only"

    work = tmp_path / "work_relay"
    _run_git(tmp_path, "clone", str(workspace), str(work))
    _run_git(work, "config", "user.email", "work@example.com")
    _run_git(work, "config", "user.name", "Work User")
    (work / "tip.txt").write_text("tip\n", encoding="utf-8")
    _run_git(work, "add", "tip.txt")
    _run_git(work, "commit", "-m", "sender tip")
    tip_hash = _run_git(work, "rev-parse", "HEAD").stdout.strip()

    bundle = tmp_path / "relay.bundle"
    _run_git(work, "bundle", "create", str(bundle), f"^{base_hash}", branch)
    dump = tmp_path / f"dump_{repo_name}_relay.dmp"
    encrypt_bundle_to_dump(bundle, dump, password)

    upload = envelope_form_post(
        client, "/api/documents/upload", {"repo": repo_name}, password,
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert upload.status_code == 200, upload.text
    body = parse_envelope(upload.json(), password)
    assert body.get("success") is True, body
    assert body.get("branches") == [branch]

    assert _run_git(bare, "rev-parse", f"refs/heads/{branch}").stdout.strip() == tip_hash
    assert _run_git(workspace, "rev-parse", f"refs/heads/{branch}").stdout.strip() == tip_hash
    assert _run_git(workspace, "symbolic-ref", "--short", "HEAD").stdout.strip() == branch
