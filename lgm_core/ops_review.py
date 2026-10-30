"""Review snapshot transfer service: WORK prepare into the encrypted postbox
and HOME open/import from it (plan section A, docs/MR_REVIEW_PROTOCOL.md).

Transport contract: the code bundle goes to mr-review/code/<code_id>.bundle
FIRST, the transfer envelope (validated snapshot + bundle digest/size) to
mr-review/snapshots/<snapshot_id>.json LAST, so HOME never sees a manifest
whose bundle is absent. A transport receipt is not review completion: prepare
reports exactly which artifacts were uploaded, open/import installs into the
persistent MrReviewStore and never depends on the postbox TTL afterwards.
"""
from __future__ import annotations

import hashlib
import json
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

from .client import LgmError, MirrorClient
from .mr_review_collect import collect_mr_snapshot
from .mr_review_models import (
    CODE_ARTIFACT_MAX_BYTES,
    MrReviewError,
    build_transfer_envelope,
    canonical_json,
    code_artifact_path,
    snapshot_artifact_path,
    validate_budget,
    verify_transfer_envelope,
)
from .mr_review_reader import (
    DEFAULT_BUDGET_BYTES,
    DEFAULT_CONTEXT_LINES,
    MrReviewReader,
    open_reader,
    peek_cursor_snapshot,
)
from .mr_review_store import MrReviewStore

# postbox TTL is 7 days (backend/app/routers/file_sync.py); refresh one day before expiry
TTL_REFRESH_SECONDS = 6 * 86400

_READ_CHUNK = 1 << 16


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(_READ_CHUNK)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _postbox_items(client: MirrorClient, repo: str) -> list[dict]:
    return client.file_sync_list(repo).get("items") or []


def _find_item(items: list[dict], path: str) -> dict | None:
    best: dict | None = None
    for item in items:
        if item.get("path") != path:
            continue
        if best is None or float(item.get("mtime") or 0) > float(best.get("mtime") or 0):
            best = item
    return best


def _upload(client: MirrorClient, repo: str, path: str, data: bytes) -> None:
    res = client.file_sync_send(repo, path, len(data), data)
    if res.get("success") is False:
        raise LgmError("server", f"mirror refused {path}: {str(res)[:300]}")


def _check_iid(iid: int) -> None:
    if not isinstance(iid, int) or isinstance(iid, bool) or iid < 1:
        raise MrReviewError("invalid-schema", f"iid must be an integer >= 1, got {iid!r}")


def _bundle_plan(existing: dict | None, bundle_size: int,
                 now: float) -> tuple[str, list[str]]:
    """Decide the bundle transport action: upload (absent or size drift),
    ttl-refresh (aged past the refresh threshold) or deduplicated."""
    if existing is None:
        return "upload", []
    plain = existing.get("plain_size") or existing.get("size") or 0
    if plain and plain != bundle_size:
        return "upload", []
    age = now - float(existing.get("mtime") or 0)
    if age >= TTL_REFRESH_SECONDS:
        return "ttl-refresh", []
    return "deduplicated", []


def mr_review_prepare(client: MirrorClient, repo: str, iid: int, project: str,
                      checkout: Path | str, *,
                      allow_code_upload: bool = True,
                      work_dir: Path | str | None = None) -> dict:
    """WORK: collect one snapshot and place its artifacts in the postbox.

    Bundle first, envelope last. Dedup: an existing bundle for the same
    code_id (path + size match) is not re-uploaded; one aged past
    TTL_REFRESH_SECONDS is re-uploaded so new HOME consumers can fetch it.
    ``allow_code_upload=False`` (notes-only sync) never uploads code: when the
    bundle is absent the whole preparation is skipped with a limitation, and
    an aged bundle is reported as a limitation instead of being refreshed.
    """
    _check_iid(iid)
    if not isinstance(repo, str) or not repo:
        raise LgmError("config", "repo must be a non-empty mirror repository name")
    collected = collect_mr_snapshot(client, repo, iid, project, checkout, work_dir)
    manifest = collected["manifest"]
    bundle_path = Path(collected["bundle_path"])
    bundle_size = collected["bundle_size"]
    code_id = collected["code_id"]
    if bundle_size > CODE_ARTIFACT_MAX_BYTES:
        raise MrReviewError(
            "review-package-too-large",
            f"code bundle is {bundle_size} bytes, above the "
            f"{CODE_ARTIFACT_MAX_BYTES} byte cap")
    bundle_sha256 = _file_sha256(bundle_path)
    code_path = code_artifact_path(code_id)
    items = _postbox_items(client, repo)
    desired, limitations = _bundle_plan(_find_item(items, code_path), bundle_size,
                                        time.time())

    if desired == "upload" and not allow_code_upload:
        return {
            "status": "skipped",
            "repo": repo, "iid": iid,
            "snapshot_id": manifest["snapshot_id"],
            "code_id": code_id,
            "version_fingerprint": manifest["version_fingerprint"],
            "bundle": {"path": code_path, "size": bundle_size,
                       "sha256": bundle_sha256, "action": "skipped"},
            "limitations": [
                f"code artifact {code_path} is not available in the postbox; "
                "run a full sync (not notes-only) to transfer it"],
        }

    action = "deduplicated"
    if desired in ("upload", "ttl-refresh"):
        if allow_code_upload:
            _upload(client, repo, code_path, bundle_path.read_bytes())
            action = "uploaded" if desired == "upload" else "ttl-refreshed"
        else:
            limitations.append(
                f"code artifact {code_path} approaches postbox expiry and cannot "
                "be refreshed in a notes-only run")
    envelope = build_transfer_envelope(
        manifest, bundle_sha256, bundle_size, _now_iso(), limitations)
    manifest_path = snapshot_artifact_path(manifest["snapshot_id"])
    _upload(client, repo, manifest_path, canonical_json(envelope).encode("utf-8"))
    return {
        "status": "prepared",
        "repo": repo, "iid": iid,
        "snapshot_id": manifest["snapshot_id"],
        "code_id": code_id,
        "version_fingerprint": manifest["version_fingerprint"],
        "bundle": {"path": code_path, "size": bundle_size,
                   "sha256": bundle_sha256, "action": action},
        "manifest_path": manifest_path,
        "limitations": limitations,
    }


def _code_objects_present(store: MrReviewStore, snap: dict) -> bool:
    refs = snap["gitlab_diff_refs"]
    shas = [snap["source_sha"], snap["target_tip_sha"],
            refs["base_sha"], refs["start_sha"], refs["head_sha"],
            *snap["computed_merge_bases"]]
    for sha in shas:
        try:
            if store.git.object_info(sha).type != "commit":
                return False
        except MrReviewError:
            return False
    return True


def _identity(snap: dict) -> dict:
    return {key: snap[key] for key in (
        "repo", "gitlab_url", "source_project_id", "target_project_id", "iid",
        "mr_url", "title", "state", "draft", "source_branch", "target_branch",
        "source_sha", "target_tip_sha")}


def _summary(snap: dict, env: dict, bundle_downloaded: bool,
             previous_snapshot_id: str | None) -> dict:
    return {
        "discussions": len(snap["discussions"]),
        "notes": sum(len(disc["notes"]) for disc in snap["discussions"]),
        "bundle_size": env["code"]["bundle_size"],
        "bundle_sha256": env["code"]["bundle_sha256"],
        "bundle_downloaded": bundle_downloaded,
        "previous_snapshot_id": previous_snapshot_id,
    }


def _not_ready(repo: str, iid: int, code: str, message: str,
               current_snapshot_id: str | None = None) -> dict:
    return {"status": "not-ready", "repo": repo, "iid": iid, "code": code,
            "message": message, "current_snapshot_id": current_snapshot_id}


def _open_result(status: str, repo: str, env: dict, store: MrReviewStore,
                 repo_key: str, store_root: Path, bundle_downloaded: bool,
                 previous_snapshot_id: str | None) -> dict:
    snap = env["snapshot"]
    return {
        "status": status,
        "repo": repo, "iid": snap["iid"],
        "snapshot_id": snap["snapshot_id"],
        "code_id": snap["code_id"],
        "version_fingerprint": snap["version_fingerprint"],
        "identity": _identity(snap),
        "limitations": [*snap["limitations"], *env["limitations"]],
        "summary": _summary(snap, env, bundle_downloaded, previous_snapshot_id),
        "store": {"repo_key": repo_key, "root": str(store_root)},
        "current_snapshot_id": store.current_snapshot_id(snap["iid"]),
    }


def _with_inventory(result: dict, store: MrReviewStore, snap: dict,
                    budget_bytes: int) -> dict:
    reader = MrReviewReader(store.git, snap)
    result["inventory"] = reader.inventory_page(
        budget_bytes=budget_bytes, enclosing=result)
    return result


def mr_review_open(client: MirrorClient, repo: str, iid: int, *,
                   snapshot_id: str = "",
                   cursor: str = "",
                   budget_bytes: int = DEFAULT_BUDGET_BYTES,
                   home: Path | str | None = None) -> dict:
    """HOME: import the requested (or latest) complete snapshot for an MR and
    return its identity plus the first inventory page. Subsequent inventory
    pages come from calling again with the page's next_cursor: the snapshot
    is then read straight from the store, without the postbox.

    The envelope digest chain (snapshot digests, identity binding, bundle
    sha256/size) is verified before store.import_snapshot. Idempotent: an
    already-imported snapshot returns ``already-imported`` without any
    bundle download, so an imported snapshot never depends on the postbox
    TTL. A missing/expired bundle or a digest mismatch returns a typed
    ``not-ready`` result and leaves the previously imported current
    snapshot untouched and readable.
    """
    _check_iid(iid)
    if not isinstance(repo, str) or not repo:
        raise LgmError("config", "repo must be a non-empty mirror repository name")
    validate_budget(budget_bytes)
    if cursor:
        if snapshot_id:
            raise MrReviewError(
                "invalid-schema", "cursor and snapshot_id are mutually exclusive")
        sid = peek_cursor_snapshot(cursor)
        reader = open_reader(home, repo, sid)
        base = {"status": "paged", "repo": repo, "iid": iid, "snapshot_id": sid}
        inventory = reader.inventory_page(cursor=cursor, budget_bytes=budget_bytes,
                                          enclosing=base)
        return {**base, "inventory": inventory}
    items = _postbox_items(client, repo)
    envelopes = [item for item in items
                 if (item.get("path") or "").startswith("mr-review/snapshots/")
                 and (item.get("path") or "").endswith(".json")]
    if snapshot_id:
        target = snapshot_artifact_path(snapshot_id)
        candidates = [item for item in envelopes if item.get("path") == target]
        if not candidates:
            return _not_ready(repo, iid, "review-object-missing",
                              f"snapshot envelope {target} is not in the postbox")
    else:
        candidates = sorted(envelopes, key=lambda i: float(i.get("mtime") or 0),
                            reverse=True)

    env: dict | None = None
    for item in candidates:
        data = client.file_sync_fetch(repo, item["id"])
        try:
            candidate = verify_transfer_envelope(json.loads(data.decode("utf-8")))
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise MrReviewError(
                "invalid-schema",
                f"envelope {item.get('path')} is not valid JSON") from None
        snap = candidate["snapshot"]
        if snap["repo"] != repo:
            raise MrReviewError(
                "invalid-schema",
                f"envelope {item.get('path')} belongs to repo {snap['repo']!r}, "
                f"requested {repo!r}")
        if snap["iid"] != iid:
            if snapshot_id:
                raise MrReviewError(
                    "invalid-schema",
                    f"envelope {target} is for MR !{snap['iid']}, requested !{iid}")
            continue
        env = candidate
        break
    if env is None:
        return _not_ready(repo, iid, "review-object-missing",
                          f"no snapshot envelope for MR !{iid} in the postbox")

    snap = env["snapshot"]
    store = MrReviewStore(snap["gitlab_url"], snap["target_project_id"],
                          snap["repo"], home=home)
    if store.has_snapshot(snap["snapshot_id"]):
        return _with_inventory(
            _open_result("already-imported", repo, env, store,
                         store.repo_key, store.root, False,
                         snap["snapshot_id"]),
            store, snap, budget_bytes)
    previous = store.current_snapshot_id(iid)

    if store.has_code_id(snap["code_id"]) and _code_objects_present(store, snap):
        result = store.import_snapshot(snap, None)
        return _with_inventory(
            _open_result(result.status, repo, env, store, result.repo_key,
                         result.store_root, False, previous),
            store, snap, budget_bytes)

    code_path = code_artifact_path(snap["code_id"])
    bundle_item = _find_item(items, code_path)
    if bundle_item is None:
        return _not_ready(
            repo, iid, "review-object-missing",
            f"code bundle {code_path} is not in the postbox (missing or expired); "
            "the previously imported current snapshot stays readable",
            previous)
    plain = bundle_item.get("plain_size") or bundle_item.get("size") or 0
    if plain > CODE_ARTIFACT_MAX_BYTES:
        raise MrReviewError(
            "review-package-too-large",
            f"code bundle in transport is {plain} bytes, above the "
            f"{CODE_ARTIFACT_MAX_BYTES} byte cap")
    if plain and plain != env["code"]["bundle_size"]:
        return _not_ready(
            repo, iid, "hash-mismatch",
            f"bundle size in transport ({plain}) does not match the envelope "
            f"({env['code']['bundle_size']})", previous)
    data = client.file_sync_fetch(repo, bundle_item["id"])
    if len(data) != env["code"]["bundle_size"]:
        return _not_ready(
            repo, iid, "hash-mismatch",
            f"downloaded bundle is {len(data)} bytes, envelope declares "
            f"{env['code']['bundle_size']}", previous)
    if hashlib.sha256(data).hexdigest() != env["code"]["bundle_sha256"]:
        return _not_ready(
            repo, iid, "hash-mismatch",
            "downloaded bundle digest does not match the envelope", previous)

    fd, tmp_name = tempfile.mkstemp(prefix="lgm-review-import-", suffix=".bundle")
    tmp_path = Path(tmp_name)
    try:
        with open(fd, "wb") as handle:
            handle.write(data)
        result = store.import_snapshot(snap, tmp_path)
    finally:
        try:
            tmp_path.unlink()
        except OSError:
            pass
    return _with_inventory(
        _open_result(result.status, repo, env, store, result.repo_key,
                     result.store_root, True, previous),
        store, snap, budget_bytes)


def mr_review_read(repo: str, snapshot_id: str, view: str, *,
                   paths: list[str] | tuple[str, ...] = (),
                   group: str = "",
                   side: str = "new",
                   cursor: str = "",
                   budget_bytes: int = DEFAULT_BUDGET_BYTES,
                   context_lines: int = DEFAULT_CONTEXT_LINES,
                   home: Path | str | None = None) -> dict:
    """HOME: read one bounded page of an imported snapshot (diff, file or
    discussions view). Reads only the store's objects.git; a worktree is
    never touched and missing objects never fall back to disk."""
    if not isinstance(repo, str) or not repo:
        raise LgmError("config", "repo must be a non-empty mirror repository name")
    reader = open_reader(home, repo, snapshot_id)
    return reader.read_page(view=view, paths=paths, group=group, side=side,
                            cursor=cursor, budget_bytes=budget_bytes,
                            context_lines=context_lines)
