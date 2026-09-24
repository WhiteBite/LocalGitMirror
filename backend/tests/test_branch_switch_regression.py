"""Regression tests: stealth sync must never move the user's checkout and
must never propagate junk branch names (refs/heads/HEAD).

Bug class (forensics on a real repo, 6 observed episodes): upload-and-apply
attached the server workspace to the SENDER's first bundle branch
(`preferred = next(iter(incoming))`), switching the home checkout to an
unrelated branch (PHONYX-16-...) on every send from a different branch.
Separately, a junk ref literally named "HEAD" travelled through bundles and
re-seeded every repo it touched; it also poisons `rev-parse --abbrev-ref HEAD`
(empty stdout), silently degrading every current-branch probe.

Invariants locked here:
  (a) upload-and-apply keeps the workspace on its current branch; the sender's
      branches land as refs only;
  (b) a bundle carrying refs/heads/HEAD is applied without creating that ref
      in workspace or bare;
  (c) apply-known ignores a "HEAD" entry in the branches map;
  (d) the bare repo rejects refs/heads/HEAD (and refs/heads/*/HEAD) at receive
      time, so `git push --all` from a junked machine cannot re-seed it;
  (e) stealth endpoints no longer auto-prune mirror branches from the
      sender's partial local view (manual prune stays).
"""
import json
import subprocess
import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.bundle_crypto import encrypt_bundle_to_dump
from app.core.repo_manager import RepoManager
from tests import _harness
from tests.conftest import envelope_form_post, envelope_post, parse_envelope

PASSWORD = "branch-switch-pw"


def _run_git(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    proc = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if proc.returncode != 0:
        raise AssertionError(f"git {' '.join(args)} failed: {proc.stderr}")
    return proc


def _git_rc(cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)


def _head_branch(cwd: Path) -> str:
    proc = _git_rc(cwd, "symbolic-ref", "--short", "-q", "HEAD")
    return proc.stdout.strip() if proc.returncode == 0 else ""


def _branches(cwd: Path) -> set:
    # %(refname:short) renders a branch named "HEAD" as "heads/HEAD", hiding it from short-name assertions
    proc = _run_git(cwd, "for-each-ref", "--format=%(refname)", "refs/heads")
    return {
        b.strip()[len("refs/heads/"):]
        for b in proc.stdout.splitlines() if b.strip()
    }


def _make_client(tmp_path: Path, monkeypatch):
    storage = tmp_path / "storage"
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "settings.json").write_text(
        json.dumps({"git": {"user_name": "Bot", "user_email": "bot@test.com"}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("SYNC_PASSWORD", PASSWORD)
    rm = RepoManager(storage)
    app = _harness.build_app(
        repo_manager=rm,
        git_handler=None,
        git_workspace=None,
        shared_manager=None,
        system_logger=None,
        config={"git_port": 0, "web_port": 0, "storage_path": storage},
    )
    return TestClient(app), storage, rm


def _make_work(tmp_path: Path, name: str) -> Path:
    work = tmp_path / name
    work.mkdir()
    _run_git(work, "init")
    _run_git(work, "config", "user.email", "work@example.com")
    _run_git(work, "config", "user.name", "Work User")
    return work


def _upload(client, tmp_path: Path, repo: str, bundle: Path):
    dump = tmp_path / f"dump_{repo}_{bundle.stem}.dmp"
    encrypt_bundle_to_dump(bundle, dump, PASSWORD)
    resp = envelope_form_post(
        client, "/api/documents/upload", {"repo": repo}, PASSWORD,
        files={"attachment": (dump.name, dump.read_bytes(), "application/octet-stream")},
    )
    assert resp.status_code == 200, resp.text
    return parse_envelope(resp.json(), PASSWORD)


# (a) workspace stays on its own branch

def test_upload_apply_keeps_workspace_branch(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"sw-a-{int(time.time() * 1000)}"
    assert client.post("/api/documents/collection", json={"name": repo}).status_code == 200
    ws = storage / repo
    bare = rm._get_bare_path(repo)

    _run_git(ws, "checkout", "-B", "feature-home")
    (ws / "home.txt").write_text("h\n", encoding="utf-8")
    _run_git(ws, "add", "home.txt")
    _run_git(ws, "commit", "-m", "home commit")
    _run_git(ws, "push", "--force", str(bare), "refs/heads/feature-home:refs/heads/feature-home")

    work = _make_work(tmp_path, "work-a")
    _run_git(work, "checkout", "-B", "aaa-first")
    (work / "a.txt").write_text("a\n", encoding="utf-8")
    _run_git(work, "add", "a.txt")
    _run_git(work, "commit", "-m", "sender current")
    _run_git(work, "fetch", str(ws), "feature-home:feature-home")
    (work / "h2.txt").write_text("h2\n", encoding="utf-8")
    _run_git(work, "add", "h2.txt")
    _run_git(work, "commit", "-m", "home branch advanced on sender")
    home_tip = _run_git(work, "rev-parse", "feature-home").stdout.strip()

    bundle = tmp_path / "a.bundle"
    _run_git(work, "bundle", "create", str(bundle), "aaa-first", "feature-home")

    inner = _upload(client, tmp_path, repo, bundle)
    assert inner.get("success") is True, inner

    assert _head_branch(ws) == "feature-home", (
        f"workspace checkout moved to {_head_branch(ws)!r}; must stay on feature-home"
    )
    assert _run_git(ws, "rev-parse", "HEAD").stdout.strip() == home_tip
    assert "aaa-first" in _branches(ws), "sender branch must still land as a ref"


# (b) junk ref in bundle is not materialized

def test_upload_apply_skips_junk_head_ref(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"sw-b-{int(time.time() * 1000)}"
    assert client.post("/api/documents/collection", json={"name": repo}).status_code == 200
    ws = storage / repo
    bare = rm._get_bare_path(repo)

    work = _make_work(tmp_path, "work-b")
    _run_git(work, "checkout", "-B", "main")
    (work / "m.txt").write_text("m\n", encoding="utf-8")
    _run_git(work, "add", "m.txt")
    _run_git(work, "commit", "-m", "main")
    tip = _run_git(work, "rev-parse", "HEAD").stdout.strip()
    _run_git(work, "update-ref", "refs/heads/HEAD", tip)

    bundle = tmp_path / "b.bundle"
    _run_git(work, "bundle", "create", str(bundle), "--all")
    heads = _run_git(work, "bundle", "list-heads", str(bundle)).stdout
    assert "refs/heads/HEAD" in heads, "precondition: bundle carries the junk ref"

    inner = _upload(client, tmp_path, repo, bundle)
    assert inner.get("success") is True, inner

    assert "HEAD" not in _branches(ws), f"junk ref materialized in workspace: {_branches(ws)}"
    assert "HEAD" not in _branches(bare), f"junk ref materialized in bare: {_branches(bare)}"
    assert _run_git(ws, "rev-parse", "refs/heads/main").stdout.strip() == tip


# (c) apply-known ignores "HEAD" in branches map

def test_apply_known_ignores_head_branch_entry(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"sw-c-{int(time.time() * 1000)}"
    assert client.post("/api/documents/collection", json={"name": repo}).status_code == 200
    ws = storage / repo
    bare = rm._get_bare_path(repo)

    _run_git(ws, "checkout", "-B", "main")
    (ws / "k.txt").write_text("k\n", encoding="utf-8")
    _run_git(ws, "add", "k.txt")
    _run_git(ws, "commit", "-m", "known")
    known = _run_git(ws, "rev-parse", "HEAD").stdout.strip()
    _run_git(ws, "push", "--force", str(bare), "refs/heads/main:refs/heads/main")

    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": known,
        "branches": {"HEAD": known, "main": known},
    }, PASSWORD)
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner.get("success") is True, inner

    assert "HEAD" not in _branches(ws), f"junk ref created in workspace: {_branches(ws)}"
    assert "HEAD" not in _branches(bare), f"junk ref created in bare: {_branches(bare)}"


# (d) bare rejects junk at receive time

def test_bare_receive_hook_rejects_junk_push(tmp_path, monkeypatch):
    _client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"sw-d-{int(time.time() * 1000)}"
    bare = rm._get_bare_path(repo)
    rm.create_repo(repo)
    assert bare.exists()

    src = _make_work(tmp_path, "work-d")
    _run_git(src, "checkout", "-B", "main")
    (src / "s.txt").write_text("s\n", encoding="utf-8")
    _run_git(src, "add", "s.txt")
    _run_git(src, "commit", "-m", "seed")
    tip = _run_git(src, "rev-parse", "HEAD").stdout.strip()
    _run_git(src, "update-ref", "refs/heads/HEAD", tip)

    push = _git_rc(src, "push", "--force", str(bare), "--all")
    assert push.returncode != 0, f"push --all with junk must be rejected: {push.stderr}"
    assert "HEAD" not in _branches(bare), f"junk landed in bare: {_branches(bare)}"

    good = _git_rc(src, "push", "--force", str(bare), "refs/heads/main:refs/heads/main")
    assert good.returncode == 0, f"legit push must still work: {good.stderr}"


# (e) no auto-prune from partial local views

def test_apply_known_does_not_auto_prune(tmp_path, monkeypatch):
    client, storage, rm = _make_client(tmp_path, monkeypatch)
    repo = f"sw-e-{int(time.time() * 1000)}"
    assert client.post("/api/documents/collection", json={"name": repo}).status_code == 200
    ws = storage / repo
    bare = rm._get_bare_path(repo)

    for br in ("main", "keep-me"):
        _run_git(ws, "checkout", "-B", br)
        (ws / f"{br}.txt").write_text(f"{br}\n", encoding="utf-8")
        _run_git(ws, "add", ".")
        _run_git(ws, "commit", "-m", f"commit on {br}")
        _run_git(ws, "push", "--force", str(bare), f"refs/heads/{br}:refs/heads/{br}")
    _run_git(bare, "symbolic-ref", "HEAD", "refs/heads/main")
    _run_git(ws, "checkout", "main")

    head = _run_git(bare, "rev-parse", "refs/heads/main").stdout.strip()
    resp = envelope_post(client, "/api/documents/link", {
        "repo": repo,
        "commit": head,
        "branches": {"main": head},
        "local_branches": ["main"],
    }, PASSWORD)
    inner = parse_envelope(resp.json(), PASSWORD)
    assert inner.get("success") is True, inner

    assert "keep-me" in _branches(bare), (
        f"mirror branch pruned from a partial local view: {_branches(bare)}"
    )
