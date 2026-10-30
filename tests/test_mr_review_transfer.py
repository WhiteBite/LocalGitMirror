"""Review snapshot transfer lifecycle: WORK prepare -> postbox -> HOME
open/import. The postbox is faked in memory (send/list/fetch monkeypatched
on a real MirrorClient, the pattern of test_ops_projects.py); the collector
runs against real temp git repos (origin + clone, the pattern of
test_mr_review_collect.py). Covers upload order (code before manifest),
dedup, TTL refresh, idempotent import, the plan's failure scenarios and the
cross-language envelope parity fixture."""
import hashlib
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core import config as config_mod
from lgm_core import mr_review_collect as collect
from lgm_core import ops_review
from lgm_core.client import LgmError, MirrorClient, ResponseTooLargeError
from lgm_core.config import Config
from lgm_core.mr_review_models import (
    CODE_ARTIFACT_MAX_BYTES,
    MrReviewError,
    build_transfer_envelope,
    canonical_json,
    sha256_hex,
    verify_transfer_envelope,
)
from lgm_core.mr_review_store import MrReviewStore
from lgm_core.op_models import Ctx

PROJECT = "dev/onyx/phonyx"
FIXED_NOW = datetime(2026, 10, 30, 9, 0, 0, tzinfo=timezone.utc)
TTL_REFRESH = ops_review.TTL_REFRESH_SECONDS
_FIXTURES = Path(__file__).resolve().parent / "fixtures"


def _client(monkeypatch) -> MirrorClient:
    env = {"GITLAB_URL": "https://gitlab.corp", "GITLAB_TOKEN": "tok",
           "GITLAB_PROJECT": PROJECT}
    monkeypatch.setattr(config_mod, "_ENV", env)
    for key in ("GITLAB_URL", "GITLAB_TOKEN", "GITLAB_PROJECT"):
        monkeypatch.delenv(key, raising=False)
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    return MirrorClient(base_url=config.base_url, api_key=config.api_key,
                        sync_password=config.sync_password)


def _git(repo: Path, *args: str) -> str:
    proc = subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0, proc.stderr
    return proc.stdout.strip()


def _commit(repo: Path, path: str, text: str, message: str) -> str:
    (repo / path).write_text(text, encoding="utf-8")
    _git(repo, "add", ".")
    _git(repo, "commit", "-q", "-m", message)
    return _git(repo, "rev-parse", "HEAD")


def _init_repo(repo: Path) -> None:
    repo.mkdir(parents=True)
    _git(repo, "init", "-q", "-b", "master")
    _git(repo, "config", "user.email", "t@t")
    _git(repo, "config", "user.name", "t")


def _make_origin(tmp_path: Path) -> tuple[Path, dict]:
    origin = tmp_path / "origin"
    _init_repo(origin)
    base = _commit(origin, "F.txt", "base\n", "base")
    _git(origin, "checkout", "-q", "-b", "feature")
    head = _commit(origin, "F.txt", "base\nfeature\n", "feature")
    _git(origin, "checkout", "-q", "master")
    target_only = _commit(origin, "T.txt", "target-only\n", "target only")
    return origin, {"base": base, "head": head, "target_only": target_only,
                    "start": target_only, "tip": target_only}


def _clone(origin: Path, dest: Path) -> Path:
    subprocess.run(["git", "clone", "-q", str(origin), str(dest)],
                   capture_output=True, text=True, timeout=60, check=True)
    _git(dest, "config", "user.email", "t@t")
    _git(dest, "config", "user.name", "t")
    return dest


def _advance_feature(origin: Path) -> str:
    _git(origin, "checkout", "-q", "feature")
    head = _commit(origin, "F.txt", "base\nfeature\nmore\n", "more")
    _git(origin, "checkout", "-q", "master")
    return head


def _mr_detail(shas: dict, **overrides) -> dict:
    detail = {
        "iid": 30,
        "project_id": 42,
        "source_project_id": 42,
        "target_project_id": 42,
        "title": "Add review workspace",
        "description": "First cut",
        "state": "opened",
        "draft": False,
        "source_branch": "feature",
        "target_branch": "master",
        "sha": shas["head"],
        "updated_at": "2026-10-30T08:59:00Z",
        "web_url": "https://gitlab.corp/dev/onyx/phonyx/-/merge_requests/30",
        "diff_refs": {
            "base_sha": shas["base"],
            "start_sha": shas["start"],
            "head_sha": shas["head"],
        },
    }
    detail.update(overrides)
    return detail


def _raw_discussions(shas: dict) -> list:
    refs = {"base_sha": shas["base"], "start_sha": shas["start"],
            "head_sha": shas["head"]}
    return [
        {"id": "ccd35f", "notes": [
            {"id": 901, "author": {"name": "Daniil Minkin"},
             "created_at": "2026-10-02T13:10:48.092Z", "system": False,
             "body": "тред открыт", "resolvable": True, "resolved": False,
             "position": {"old_path": "F.txt", "new_path": "F.txt",
                          "old_line": 1, "new_line": 2, **refs}},
            {"id": 902, "author": {"name": "Ivan"},
             "created_at": "2026-10-02T13:20:00Z", "system": False,
             "body": "ответ автора", "resolvable": False},
        ]},
        {"id": "sys", "notes": [
            {"id": 903, "author": {"name": "GitLab"},
             "created_at": "2026-09-01T09:00:00Z", "system": True,
             "body": "assigned to @i.spirkov"},
        ]},
        {"id": "del", "notes": [
            {"id": 904, "author": {"name": "Aleksandr"},
             "created_at": "2026-10-01T10:00:00Z", "system": False,
             "body": "удалённая строка", "resolvable": True, "resolved": True,
             "position": {"old_path": "G.txt", "old_line": 5, **refs}},
        ]},
    ]


def _raw_discussions_v2(shas: dict) -> list:
    discs = _raw_discussions(shas)
    discs.append({"id": "new", "notes": [
        {"id": 905, "author": {"name": "B"},
         "created_at": "2026-10-30T10:00:00Z", "system": False,
         "body": "новое замечание", "resolvable": False, "resolved": False},
    ]})
    return discs


def _patch_gitlab(monkeypatch, client, detail: dict, tip: str,
                  discussions: list) -> None:
    monkeypatch.setattr(client, "gitlab_get_mr", lambda iid, project="": detail)
    monkeypatch.setattr(client, "gitlab_branch_tip",
                        lambda branch, project="": tip)
    monkeypatch.setattr(client, "gitlab_list_discussions",
                        lambda iid, project="": discussions)


class FakePostbox:
    """Every send appends a new item; duplicate paths are allowed and the
    newest mtime wins, matching the real postbox semantics."""

    def __init__(self):
        self.items: list[dict] = []
        self.data: dict[str, bytes] = {}
        self.uploads: list[tuple[str, bytes]] = []
        self._next = 0

    def attach(self, monkeypatch, client) -> None:
        monkeypatch.setattr(client, "file_sync_send", self.send)
        monkeypatch.setattr(client, "file_sync_list",
                            lambda repo: {"items": [dict(i) for i in self.items]})
        monkeypatch.setattr(client, "file_sync_fetch", self.fetch)

    def send(self, repo, path, size, data):
        self._next += 1
        item_id = f"i{self._next}"
        self.uploads.append((path, bytes(data)))
        self.data[item_id] = bytes(data)
        self.items.append({"id": item_id, "path": path, "size": len(data),
                           "plain_size": len(data),
                           "mtime": time.time() + self._next})
        return {"success": True, "id": item_id, "path": path}

    def fetch(self, repo, item_id, max_bytes=None):
        if item_id not in self.data:
            raise LgmError("server", f"postbox holds no item {item_id}")
        data = self.data[item_id]
        if max_bytes is not None and len(data) > max_bytes:
            raise ResponseTooLargeError(
                "server", f"download exceeds the {max_bytes} byte cap")
        return data

    def paths(self) -> list[str]:
        return [path for path, _ in self.uploads]

    def bundle_items(self, code_id: str) -> list[dict]:
        return [i for i in self.items
                if i["path"] == f"mr-review/code/{code_id}.bundle"]

    def envelope_items(self) -> list[dict]:
        return [i for i in self.items
                if i["path"].startswith("mr-review/snapshots/")]

    def age_items(self, path: str, seconds: float) -> None:
        for item in self.items:
            if item["path"] == path:
                item["mtime"] = time.time() - seconds

    def drop_items(self, pred) -> None:
        kept = []
        for item in self.items:
            if pred(item):
                self.data.pop(item["id"], None)
            else:
                kept.append(item)
        self.items = kept

    def corrupt(self, path: str) -> None:
        for item in self.items:
            if item["path"] == path:
                data = bytearray(self.data[item["id"]])
                data[0] ^= 0xFF
                self.data[item["id"]] = bytes(data)


def _env(tmp_path, monkeypatch):
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _patch_gitlab(monkeypatch, client, _mr_detail(shas), shas["tip"],
                  _raw_discussions(shas))
    monkeypatch.setattr(collect, "_now", lambda: FIXED_NOW)
    postbox = FakePostbox()
    postbox.attach(monkeypatch, client)
    return client, origin, shas, checkout, postbox


def _prepare(client, checkout, work_dir, iid=30, allow_code_upload=True):
    return ops_review.mr_review_prepare(
        client, "phonyx", iid, PROJECT, checkout,
        allow_code_upload=allow_code_upload, work_dir=work_dir)


def _open(client, home, iid=30, snapshot_id=""):
    return ops_review.mr_review_open(client, "phonyx", iid,
                                     snapshot_id=snapshot_id, home=home)


def _store(home) -> MrReviewStore:
    return MrReviewStore("https://gitlab.corp", 42, "phonyx", home=home)


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# ── prepare: upload order, result shape ──────────────────────────────────────

def test_prepare_uploads_bundle_before_manifest(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    work = tmp_path / "work"

    result = _prepare(client, checkout, work)

    assert result["status"] == "prepared"
    sid, code_id = result["snapshot_id"], result["code_id"]
    code_path = f"mr-review/code/{code_id}.bundle"
    manifest_path = f"mr-review/snapshots/{sid}.json"
    assert result["bundle"]["path"] == code_path
    assert result["bundle"]["action"] == "uploaded"
    assert result["manifest_path"] == manifest_path
    assert result["bundle"]["size"] == (work / f"{code_id}.bundle").stat().st_size
    assert result["bundle"]["sha256"] == _file_sha256(work / f"{code_id}.bundle")
    assert postbox.paths() == [code_path, manifest_path]
    env = verify_transfer_envelope(
        json.loads(postbox.data[postbox.envelope_items()[0]["id"]].decode("utf-8")))
    assert env["snapshot"]["snapshot_id"] == sid
    assert env["code"]["bundle_sha256"] == result["bundle"]["sha256"]
    assert env["code"]["bundle_size"] == result["bundle"]["size"]


def test_prepare_result_never_claims_import(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)

    result = _prepare(client, checkout, tmp_path / "work")

    assert "imported" not in json.dumps(result)
    assert set(result) == {"status", "repo", "iid", "snapshot_id", "code_id",
                           "version_fingerprint", "bundle", "manifest_path",
                           "limitations"}


# ── HOME open/import ─────────────────────────────────────────────────────────

def test_home_open_imports_snapshot_and_reads_it_back(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared = _prepare(client, checkout, tmp_path / "work")

    result = _open(client, home)

    assert result["status"] == "imported"
    assert result["snapshot_id"] == prepared["snapshot_id"]
    assert result["identity"]["source_sha"] == shas["head"]
    assert result["identity"]["target_tip_sha"] == shas["tip"]
    assert result["identity"]["iid"] == 30
    assert result["identity"]["repo"] == "phonyx"
    assert result["summary"]["discussions"] == 3
    assert result["summary"]["notes"] == 4
    assert result["summary"]["bundle_downloaded"] is True
    assert result["summary"]["previous_snapshot_id"] is None
    assert result["current_snapshot_id"] == prepared["snapshot_id"]
    store = _store(home)
    assert result["store"]["repo_key"] == store.repo_key
    snap = store.load_snapshot(prepared["snapshot_id"])
    assert snap["source_sha"] == shas["head"]


def test_reimport_of_same_snapshot_is_idempotent(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared = _prepare(client, checkout, tmp_path / "work")
    assert _open(client, home)["status"] == "imported"

    second = _open(client, home)

    assert second["status"] == "already-imported"
    assert second["snapshot_id"] == prepared["snapshot_id"]
    assert second["summary"]["bundle_downloaded"] is False
    store = _store(home)
    assert store.current_snapshot_id(30) == prepared["snapshot_id"]
    rows = store._connect()
    try:
        count = rows.execute(
            "SELECT COUNT(*) FROM snapshots WHERE snapshot_id = ?",
            (prepared["snapshot_id"],)).fetchone()[0]
    finally:
        rows.close()
    assert count == 1


def test_imported_snapshot_survives_postbox_expiry(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared = _prepare(client, checkout, tmp_path / "work")
    _open(client, home)

    postbox.drop_items(lambda item: True)

    result = _open(client, home)
    assert result["status"] == "not-ready"
    assert result["code"] == "review-object-missing"
    assert "no snapshot envelope" in result["message"]
    store = _store(home)
    snap = store.load_snapshot(prepared["snapshot_id"])
    assert snap["source_sha"] == shas["head"]
    assert store.current_snapshot_id(30) == prepared["snapshot_id"]
    refs = snap["gitlab_diff_refs"]
    for sha in (snap["source_sha"], snap["target_tip_sha"], refs["head_sha"]):
        assert store.git.object_info(sha).type == "commit"


def test_open_with_explicit_snapshot_id(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared_a = _prepare(client, checkout, tmp_path / "work")
    _open(client, home)
    new_head = _advance_feature(origin)
    _patch_gitlab(monkeypatch, client,
                  _mr_detail(shas, sha=new_head,
                             diff_refs={"base_sha": shas["base"],
                                        "start_sha": shas["start"],
                                        "head_sha": new_head}),
                  shas["tip"], _raw_discussions(shas))
    prepared_b = _prepare(client, checkout, tmp_path / "work")

    again = _open(client, home, snapshot_id=prepared_a["snapshot_id"])
    assert again["status"] == "already-imported"
    assert again["snapshot_id"] == prepared_a["snapshot_id"]

    fresh = _open(client, home, snapshot_id=prepared_b["snapshot_id"])
    assert fresh["status"] == "imported"
    assert fresh["snapshot_id"] == prepared_b["snapshot_id"]

    missing = _open(client, home, snapshot_id="f" * 64)
    assert missing["status"] == "not-ready"
    assert missing["code"] == "review-object-missing"


def test_open_imports_newer_snapshot_and_reports_previous(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared_a = _prepare(client, checkout, tmp_path / "work")
    _open(client, home)
    new_head = _advance_feature(origin)
    _patch_gitlab(monkeypatch, client,
                  _mr_detail(shas, sha=new_head,
                             diff_refs={"base_sha": shas["base"],
                                        "start_sha": shas["start"],
                                        "head_sha": new_head}),
                  shas["tip"], _raw_discussions(shas))
    prepared_b = _prepare(client, checkout, tmp_path / "work")

    result = _open(client, home)

    assert result["status"] == "imported"
    assert result["snapshot_id"] == prepared_b["snapshot_id"]
    assert result["summary"]["previous_snapshot_id"] == prepared_a["snapshot_id"]
    store = _store(home)
    assert store.current_snapshot_id(30) == prepared_b["snapshot_id"]
    assert store.load_snapshot(prepared_a["snapshot_id"])["source_sha"] == shas["head"]


def test_discussion_only_refresh_reuses_imported_code(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared_a = _prepare(client, checkout, tmp_path / "work")
    assert _open(client, home)["summary"]["bundle_downloaded"] is True
    _patch_gitlab(monkeypatch, client, _mr_detail(shas), shas["tip"],
                  _raw_discussions_v2(shas))
    prepared_b = _prepare(client, checkout, tmp_path / "work")

    assert prepared_b["code_id"] == prepared_a["code_id"]
    assert prepared_b["snapshot_id"] != prepared_a["snapshot_id"]
    assert prepared_b["bundle"]["action"] == "deduplicated"

    result = _open(client, home)

    assert result["status"] == "imported"
    assert result["snapshot_id"] == prepared_b["snapshot_id"]
    assert result["summary"]["bundle_downloaded"] is False
    assert result["summary"]["notes"] == 5
    assert result["summary"]["previous_snapshot_id"] == prepared_a["snapshot_id"]


def test_open_scans_envelopes_for_the_requested_iid(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    _prepare(client, checkout, tmp_path / "work", iid=30)
    _prepare(client, checkout, tmp_path / "work", iid=31)

    r30 = _open(client, home, iid=30)
    r31 = _open(client, home, iid=31)

    assert r30["identity"]["iid"] == 30
    assert r31["identity"]["iid"] == 31
    assert r30["snapshot_id"] != r31["snapshot_id"]
    assert r30["code_id"] == r31["code_id"]
    assert r30["summary"]["bundle_downloaded"] is True
    assert r31["summary"]["bundle_downloaded"] is False


def test_open_rejects_envelope_from_another_repo(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    _prepare(client, checkout, tmp_path / "work")

    with pytest.raises(MrReviewError) as excinfo:
        ops_review.mr_review_open(client, "other-repo", 30, home=tmp_path / "home")
    assert excinfo.value.code == "invalid-schema"
    assert "other-repo" in excinfo.value.message


# ── dedup and TTL refresh ────────────────────────────────────────────────────

def test_second_prepare_dedups_bundle_uploads_metadata_only(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    first = _prepare(client, checkout, tmp_path / "work")
    uploads_after_first = len(postbox.uploads)

    second = _prepare(client, checkout, tmp_path / "work")

    assert second["snapshot_id"] == first["snapshot_id"]
    assert second["code_id"] == first["code_id"]
    assert second["bundle"]["action"] == "deduplicated"
    assert len(postbox.uploads) == uploads_after_first + 1
    assert postbox.uploads[-1][0] == second["manifest_path"]
    assert len(postbox.bundle_items(first["code_id"])) == 1


def test_ttl_refresh_reuploads_aged_bundle(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    first = _prepare(client, checkout, tmp_path / "work")
    code_path = first["bundle"]["path"]
    postbox.age_items(code_path, TTL_REFRESH + 3600)
    uploads_after_first = len(postbox.uploads)

    second = _prepare(client, checkout, tmp_path / "work")

    assert second["bundle"]["action"] == "ttl-refreshed"
    assert len(postbox.bundle_items(second["code_id"])) == 2
    second_run = postbox.uploads[uploads_after_first:]
    assert [path for path, _ in second_run] == [code_path, second["manifest_path"]]


def test_size_drift_between_transport_and_bundle_reuploads(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    first = _prepare(client, checkout, tmp_path / "work")
    for item in postbox.items:
        if item["path"] == first["bundle"]["path"]:
            item["plain_size"] = first["bundle"]["size"] + 1

    second = _prepare(client, checkout, tmp_path / "work")

    assert second["bundle"]["action"] == "uploaded"
    assert len(postbox.bundle_items(second["code_id"])) == 2


# ── notes-only preparation ───────────────────────────────────────────────────

def test_notes_only_prepare_skips_without_code_artifact(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)

    result = _prepare(client, checkout, tmp_path / "work",
                      allow_code_upload=False)

    assert result["status"] == "skipped"
    assert result["bundle"]["action"] == "skipped"
    assert result["limitations"]
    assert "not available" in result["limitations"][0]
    assert postbox.uploads == []


def test_notes_only_prepare_refreshes_metadata_when_code_available(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    _prepare(client, checkout, tmp_path / "work")
    uploads_after_full = len(postbox.uploads)

    result = _prepare(client, checkout, tmp_path / "work",
                      allow_code_upload=False)

    assert result["status"] == "prepared"
    assert result["bundle"]["action"] == "deduplicated"
    assert [path for path, _ in postbox.uploads[uploads_after_full:]] \
        == [result["manifest_path"]]


def test_notes_only_prepare_reports_aged_bundle_limitation(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    first = _prepare(client, checkout, tmp_path / "work")
    postbox.age_items(first["bundle"]["path"], TTL_REFRESH + 3600)
    uploads_after_full = len(postbox.uploads)

    result = _prepare(client, checkout, tmp_path / "work",
                      allow_code_upload=False)

    assert result["status"] == "prepared"
    assert result["bundle"]["action"] == "deduplicated"
    assert any("expiry" in limitation for limitation in result["limitations"])
    assert [path for path, _ in postbox.uploads[uploads_after_full:]] \
        == [result["manifest_path"]]


# ── failure scenarios (plan verbatim) ────────────────────────────────────────

def _prepare_second_snapshot(monkeypatch, client, origin, shas, checkout,
                             postbox, work_dir):
    new_head = _advance_feature(origin)
    _patch_gitlab(monkeypatch, client,
                  _mr_detail(shas, sha=new_head,
                             diff_refs={"base_sha": shas["base"],
                                        "start_sha": shas["start"],
                                        "head_sha": new_head}),
                  shas["tip"], _raw_discussions(shas))
    return _prepare(client, checkout, work_dir)


def test_envelope_without_bundle_not_ready_old_current_readable(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared_a = _prepare(client, checkout, tmp_path / "work")
    _open(client, home)
    prepared_b = _prepare_second_snapshot(
        monkeypatch, client, origin, shas, checkout, postbox, tmp_path / "work")
    postbox.drop_items(lambda item: item["path"] == prepared_b["bundle"]["path"])

    result = _open(client, home)

    assert result["status"] == "not-ready"
    assert result["code"] == "review-object-missing"
    assert "code bundle" in result["message"]
    assert result["current_snapshot_id"] == prepared_a["snapshot_id"]
    store = _store(home)
    assert store.load_snapshot(prepared_a["snapshot_id"])["source_sha"] == shas["head"]
    assert not store.has_snapshot(prepared_b["snapshot_id"])


def test_envelope_without_bundle_and_no_current_is_not_ready(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared = _prepare(client, checkout, tmp_path / "work")
    postbox.drop_items(lambda item: item["path"] == prepared["bundle"]["path"])

    result = _open(client, home)

    assert result["status"] == "not-ready"
    assert result["code"] == "review-object-missing"
    assert result["current_snapshot_id"] is None
    assert not _store(home).has_snapshot(prepared["snapshot_id"])


def test_bundle_digest_mismatch_not_ready_nothing_imported(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared_a = _prepare(client, checkout, tmp_path / "work")
    _open(client, home)
    prepared_b = _prepare_second_snapshot(
        monkeypatch, client, origin, shas, checkout, postbox, tmp_path / "work")
    postbox.corrupt(prepared_b["bundle"]["path"])

    result = _open(client, home)

    assert result["status"] == "not-ready"
    assert result["code"] == "hash-mismatch"
    assert result["current_snapshot_id"] == prepared_a["snapshot_id"]
    store = _store(home)
    assert not store.has_snapshot(prepared_b["snapshot_id"])
    assert store.current_snapshot_id(30) == prepared_a["snapshot_id"]


def test_transport_bundle_size_mismatch_not_ready(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared = _prepare(client, checkout, tmp_path / "work")
    for item in postbox.items:
        if item["path"] == prepared["bundle"]["path"]:
            item["plain_size"] = prepared["bundle"]["size"] + 5

    result = _open(client, home)

    assert result["status"] == "not-ready"
    assert result["code"] == "hash-mismatch"
    assert "does not match" in result["message"]
    assert not _store(home).has_snapshot(prepared["snapshot_id"])


def test_oversized_transport_bundle_rejected_before_download(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared = _prepare(client, checkout, tmp_path / "work")
    bundle_ids = {item["id"] for item in postbox.items
                  if item["path"] == prepared["bundle"]["path"]}
    for item in postbox.items:
        if item["path"] == prepared["bundle"]["path"]:
            item["plain_size"] = CODE_ARTIFACT_MAX_BYTES + 1
    fetched = []
    real_fetch = postbox.fetch

    def guarded_fetch(repo, item_id):
        fetched.append(item_id)
        return real_fetch(repo, item_id)

    monkeypatch.setattr(client, "file_sync_fetch", guarded_fetch)

    with pytest.raises(MrReviewError) as excinfo:
        _open(client, home)
    assert excinfo.value.code == "review-package-too-large"
    assert bundle_ids.isdisjoint(fetched)


def test_missing_metadata_oversized_stream_rejected(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared = _prepare(client, checkout, tmp_path / "work")
    declared = prepared["bundle"]["size"]
    for item in postbox.bundle_items(prepared["code_id"]):
        item.pop("plain_size", None)
        item.pop("size", None)
        postbox.data[item["id"]] = b"x" * (declared + 1024)

    with pytest.raises(MrReviewError) as excinfo:
        _open(client, home)
    assert excinfo.value.code == "review-package-too-large"
    assert str(declared) in excinfo.value.message
    assert not _store(home).has_snapshot(prepared["snapshot_id"])


def test_missing_metadata_short_stream_still_hash_mismatch(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    prepared = _prepare(client, checkout, tmp_path / "work")
    declared = prepared["bundle"]["size"]
    for item in postbox.bundle_items(prepared["code_id"]):
        item.pop("plain_size", None)
        item.pop("size", None)
        postbox.data[item["id"]] = postbox.data[item["id"]][:declared - 10]

    result = _open(client, home)
    assert result["status"] == "not-ready"
    assert result["code"] == "hash-mismatch"
    assert "downloaded bundle is" in result["message"]
    assert not _store(home).has_snapshot(prepared["snapshot_id"])


def test_corrupt_envelope_rejected(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    _prepare(client, checkout, tmp_path / "work")
    envelope_id = postbox.envelope_items()[0]["id"]
    postbox.data[envelope_id] = b"{not json"

    with pytest.raises(MrReviewError) as excinfo:
        _open(client, home)
    assert excinfo.value.code == "invalid-schema"


def test_tampered_envelope_snapshot_rejected_as_hash_mismatch(tmp_path, monkeypatch):
    client, origin, shas, checkout, postbox = _env(tmp_path, monkeypatch)
    home = tmp_path / "home"
    _prepare(client, checkout, tmp_path / "work")
    envelope_id = postbox.envelope_items()[0]["id"]
    env = json.loads(postbox.data[envelope_id].decode("utf-8"))
    env["snapshot"]["title"] = "tampered"
    postbox.data[envelope_id] = json.dumps(env).encode("utf-8")

    with pytest.raises(MrReviewError) as excinfo:
        _open(client, home)
    assert excinfo.value.code == "hash-mismatch"


# ── envelope contract and cross-language parity ──────────────────────────────

@pytest.mark.parametrize("mutate, code", [
    (lambda e: e.update({"extra": 1}), "invalid-schema"),
    (lambda e: e.update({"snapshot_id": "f" * 64}), "invalid-schema"),
    (lambda e: e.update({"code_id": "f" * 64}), "invalid-schema"),
    (lambda e: e["code"].update({"bundle_size": 0}), "invalid-schema"),
    (lambda e: e["code"].update({"bundle_sha256": "x"}), "invalid-schema"),
    (lambda e: e.update({"prepared_at": "not-a-time"}), "invalid-schema"),
    (lambda e: e.update({"schema_version": 2}), "unknown-schema-version"),
    (lambda e: e["snapshot"].update({"title": "tampered"}), "hash-mismatch"),
])
def test_verify_transfer_envelope_rejects_invalid(mutate, code):
    snap = json.loads((_FIXTURES / "mr_review_protocol" / "snapshot_fork_mr.json")
                      .read_text(encoding="utf-8"))["object"]
    env = build_transfer_envelope(snap, "a" * 64, 4096,
                                  "2026-10-30T12:00:00Z", ["l"])
    mutate(env)
    with pytest.raises(MrReviewError) as excinfo:
        verify_transfer_envelope(env)
    assert excinfo.value.code == code


def test_envelope_parity_fixture():
    fixture = json.loads(
        (_FIXTURES / "mr_review_transfer" / "envelope_parity.json")
        .read_text(encoding="utf-8"))
    snap = json.loads((_FIXTURES / "mr_review_protocol" / "snapshot_fork_mr.json")
                      .read_text(encoding="utf-8"))["object"]
    params = fixture["params"]
    env = build_transfer_envelope(
        snap, params["bundle_sha256"], params["bundle_size"],
        params["prepared_at"], params["limitations"])
    assert sha256_hex(canonical_json(env)) == fixture["expected_canonical_sha256"]


# ── registered WORK sync wiring (ops_projects) ───────────────────────────────

def _sync_env(tmp_path, monkeypatch):
    import lgm_core.ops_projects as opmod
    client = _client(monkeypatch)
    origin, shas = _make_origin(tmp_path)
    checkout = _clone(origin, tmp_path / "checkout")
    _git(checkout, "branch", "feature", "origin/feature")
    _patch_gitlab(monkeypatch, client, _mr_detail(shas), shas["tip"],
                  _raw_discussions(shas))
    monkeypatch.setattr(collect, "_now", lambda: FIXED_NOW)
    postbox = FakePostbox()
    postbox.attach(monkeypatch, client)
    monkeypatch.setattr(opmod, "_REGISTRY_PATH", tmp_path / "reg.json")
    monkeypatch.setattr(opmod, "_STATE_PATH", tmp_path / "state.json")
    monkeypatch.setattr(opmod, "_fetch_mr_branches", lambda proj, branches: [])
    monkeypatch.setattr(opmod, "_mirror_refs_safe", lambda c, repo: {})
    monkeypatch.setattr(opmod, "send_branches",
                        lambda ctx, repo, project, branches, excludes,
                        dry_run=False: {"bundle_size": 10})
    config = Config(base_url="http://localhost:1", api_key="", sync_password="pw")
    ctx = Ctx(config=config, client=client)
    ctx.client.sync_negotiate = lambda repo, commits, probe=False: {"known": []}
    ctx.client.gitlab_list_mrs = lambda project="": [
        {"iid": 30, "title": "Add review workspace", "source_branch": "feature",
         "target_branch": "master", "updated_at": "2026-10-30T08:59:00Z"}]
    opmod._save_registry({"projects": [
        {"repo": "phonyx", "path": str(checkout), "gitlab": PROJECT}]})
    return opmod, ctx, postbox


def test_mr_sync_all_prepares_review_snapshots(tmp_path, monkeypatch):
    opmod, ctx, postbox = _sync_env(tmp_path, monkeypatch)

    res = opmod.op_mr_sync_all(ctx, {})

    assert res["success"] is True
    reviews = res["repos"][0]["review_snapshots"]
    assert len(reviews) == 1
    assert reviews[0]["status"] == "prepared"
    assert res["summary"]["review_prepared"] == 1
    review_uploads = [path for path, _ in postbox.uploads
                      if path.startswith("mr-review/")]
    assert len(review_uploads) == 2
    assert review_uploads[0].startswith("mr-review/code/")
    assert review_uploads[1].startswith("mr-review/snapshots/")


def test_mr_sync_all_notes_only_skips_snapshot_without_code(tmp_path, monkeypatch):
    opmod, ctx, postbox = _sync_env(tmp_path, monkeypatch)

    res = opmod.op_mr_sync_all(ctx, {"notes_only": True})

    assert res["success"] is True
    reviews = res["repos"][0]["review_snapshots"]
    assert reviews[0]["status"] == "skipped"
    assert reviews[0]["limitations"]
    assert res["summary"]["review_skipped"] == 1
    assert [path for path, _ in postbox.uploads
            if path.startswith("mr-review/")] == []


def test_mr_sync_all_notes_only_refreshes_metadata_after_full_sync(tmp_path, monkeypatch):
    opmod, ctx, postbox = _sync_env(tmp_path, monkeypatch)
    opmod.op_mr_sync_all(ctx, {})
    uploads_after_full = len(postbox.uploads)

    res = opmod.op_mr_sync_all(ctx, {"notes_only": True})

    reviews = res["repos"][0]["review_snapshots"]
    assert reviews[0]["status"] == "prepared"
    assert reviews[0]["bundle_action"] == "deduplicated"
    new_review_uploads = [path for path, _ in postbox.uploads[uploads_after_full:]
                          if path.startswith("mr-review/")]
    assert new_review_uploads == [reviews[0]["manifest_path"]]


def test_mr_sync_all_dry_run_prepares_nothing(tmp_path, monkeypatch):
    opmod, ctx, postbox = _sync_env(tmp_path, monkeypatch)

    res = opmod.op_mr_sync_all(ctx, {"dry_run": True})

    assert res["dry_run"] is True
    assert "review_snapshots" not in res["repos"][0]
    assert postbox.uploads == []


def test_mr_sync_all_records_review_failure_per_mr(tmp_path, monkeypatch):
    opmod, ctx, postbox = _sync_env(tmp_path, monkeypatch)

    def broken_get_mr(iid, project=""):
        raise LgmError("network", "GitLab unreachable")

    monkeypatch.setattr(ctx.client, "gitlab_get_mr", broken_get_mr)

    res = opmod.op_mr_sync_all(ctx, {})

    assert res["success"] is True
    reviews = res["repos"][0]["review_snapshots"]
    assert reviews[0]["status"] == "failed"
    assert "GitLab unreachable" in reviews[0]["error"]
    assert res["summary"]["review_failed"] == 1
    assert res["summary"]["failures"] == []
