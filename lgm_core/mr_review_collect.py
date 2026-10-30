"""WORK-side MR review snapshot collector (docs/MR_REVIEW_PROTOCOL.md section B).

Sequence: MR detail A -> exact object fetch into a throwaway staging bare
(never the user checkout, never the mirror bare) -> complete discussions
paging -> detail B + target tip, with a fail-closed race gate. Returns the
verified snapshot manifest plus a self-contained code bundle path; transport
is wired separately.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from .client import LgmError, MirrorClient
from .mr_review_models import (
    CODE_ARTIFACT_MAX_BYTES,
    SCHEMA_VERSION,
    MrReviewError,
    compute_code_id,
    compute_snapshot_id,
    compute_version_fingerprint,
    validate_diff_refs,
    verify_snapshot,
)

CODE_BUNDLE_MAX_BYTES = CODE_ARTIFACT_MAX_BYTES

_GIT_TIMEOUT = 300

_RACE_DETAIL_FIELDS = (
    "state", "draft", "updated_at", "sha", "source_project_id",
    "target_project_id", "target_branch",
)


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(moment: datetime) -> str:
    return moment.isoformat(timespec="seconds").replace("+00:00", "Z")


def _git(cwd: Path, args: list[str],
         timeout: int = 60) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(cwd), *args], capture_output=True, text=True,
        timeout=timeout, check=False)


def _discard_tree(path: Path) -> None:
    # git marks object/pack files read-only on Windows; plain rmtree fails on them
    def _reset(func, target, _exc):
        os.chmod(target, 0o666)
        func(target)

    shutil.rmtree(path, onexc=_reset)


def _origin_url(checkout: Path) -> str:
    proc = _git(checkout, ["remote", "get-url", "origin"], timeout=30)
    if proc.returncode != 0:
        raise LgmError("config", f"checkout {checkout} has no origin remote")
    url = proc.stdout.strip()
    if not url:
        raise LgmError("config", f"checkout {checkout} has an empty origin URL")
    return url


def _fetch_refs(stage: Path, origin_url: str, refspecs: list[str]) -> None:
    proc = _git(stage, ["fetch", "--no-tags", origin_url, *refspecs],
                timeout=_GIT_TIMEOUT)
    if proc.returncode != 0:
        raise MrReviewError(
            "review-object-missing",
            f"git fetch {refspecs} from origin failed: "
            f"{proc.stderr.strip()[:300] or 'no stderr'}")


def _ref_sha(stage: Path, ref: str) -> str:
    proc = _git(stage, ["rev-parse", "--verify", ref])
    return proc.stdout.strip() if proc.returncode == 0 else ""


def _commit_exists(stage: Path, sha: str) -> bool:
    return _git(stage, ["cat-file", "-e", f"{sha}^{{commit}}"]).returncode == 0


def _merge_bases(stage: Path, source_sha: str, target_tip: str) -> list[str]:
    proc = _git(stage, ["merge-base", "--all", source_sha, target_tip])
    if proc.returncode == 1:
        return []
    if proc.returncode != 0:
        raise LgmError("git", proc.stderr.strip() or "git merge-base failed")
    return proc.stdout.split()


def _convert_position(position: dict) -> dict | None:
    new_line = position.get("new_line")
    old_line = position.get("old_line")
    if new_line is None and old_line is None:
        return None
    return {
        "old_path": position.get("old_path"),
        "new_path": position.get("new_path"),
        "old_line": old_line,
        "new_line": new_line,
        "side": "new" if new_line is not None else "old",
        "base_sha": position.get("base_sha"),
        "start_sha": position.get("start_sha"),
        "head_sha": position.get("head_sha"),
    }


def _convert_note(note: dict, limitations: list[str]) -> dict:
    if not isinstance(note, dict):
        raise MrReviewError("invalid-schema", "GitLab returned a non-object note")
    position = note.get("position")
    converted = _convert_position(position) if isinstance(position, dict) else None
    if isinstance(position, dict) and converted is None:
        limitations.append(
            f"note {note.get('id')} position is not line-anchored; position omitted")
    return {
        "note_id": note.get("id"),
        "body": note.get("body") or "",
        "author": (note.get("author") or {}).get("name") or "?",
        "created_at": note.get("created_at") or "",
        "system": bool(note.get("system")),
        "resolvable": bool(note.get("resolvable")),
        "resolved": bool(note.get("resolved")),
        "position": converted,
    }


def _convert_discussions(raw: list, limitations: list[str]) -> list[dict]:
    out: list[dict] = []
    for discussion in raw:
        if not isinstance(discussion, dict):
            raise MrReviewError(
                "invalid-schema", "GitLab returned a non-object discussion")
        out.append({
            "discussion_id": str(discussion.get("id") or ""),
            "notes": [_convert_note(note, limitations)
                      for note in discussion.get("notes") or []],
        })
    return out


def _race_changes(detail_a: dict, detail_b: dict,
                  tip_a: str, tip_b: str) -> list[str]:
    changed = [key for key in _RACE_DETAIL_FIELDS
               if detail_a.get(key) != detail_b.get(key)]
    refs_a = detail_a.get("diff_refs") or {}
    refs_b = detail_b.get("diff_refs") or {}
    changed += [
        f"diff_refs.{key}" for key in ("base_sha", "start_sha", "head_sha")
        if refs_a.get(key) != refs_b.get(key)]
    if tip_a != tip_b:
        changed.append("target_tip_sha")
    return changed


def _require_mr_fields(detail: dict, iid: int) -> None:
    for key in ("source_project_id", "target_project_id"):
        value = detail.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value < 1:
            raise MrReviewError(
                "invalid-schema", f"MR !{iid}: {key} must be a positive integer")
    for key in ("source_branch", "target_branch", "sha"):
        if not detail.get(key):
            raise MrReviewError(
                "invalid-schema", f"MR !{iid}: {key} is missing or empty")


def _create_bundle(stage: Path, tips: dict[str, str], out_path: Path) -> int:
    refs: list[str] = []
    for label, sha in tips.items():
        ref = f"refs/review/{label}"
        proc = _git(stage, ["update-ref", ref, sha])
        if proc.returncode != 0:
            raise LgmError(
                "git", proc.stderr.strip() or f"git update-ref {ref} failed")
        refs.append(ref)
    proc = _git(stage, ["bundle", "create", str(out_path), *refs],
                timeout=_GIT_TIMEOUT)
    if proc.returncode != 0:
        raise LgmError("git", proc.stderr.strip() or "git bundle create failed")
    return os.stat(out_path).st_size


def collect_mr_snapshot(
    client: MirrorClient,
    repo: str,
    iid: int,
    project: str,
    checkout: Path | str,
    work_dir: Path | str | None = None,
) -> dict:
    """Collect one consistent review snapshot for MR ``iid`` on WORK.

    One attempt per call: any drift between detail A and detail B raises
    ``snapshot-raced`` and no snapshot is emitted. Returns the verified
    manifest, the self-contained bundle path, code_id and digests; the bundle
    stays at ``<work_dir>/<code_id>.bundle`` for the caller to transfer.
    """
    checkout = Path(checkout)
    artifacts = Path(work_dir) if work_dir is not None else Path(tempfile.gettempdir())
    artifacts.mkdir(parents=True, exist_ok=True)
    started = _now()

    detail_a = client.gitlab_get_mr(iid, project=project)
    refs = validate_diff_refs(detail_a.get("diff_refs"), f"MR !{iid} diff_refs")
    _require_mr_fields(detail_a, iid)
    tip_a = client.gitlab_branch_tip(
        detail_a["target_branch"], project=str(detail_a["target_project_id"]))
    fork = detail_a["source_project_id"] != detail_a["target_project_id"]
    origin_url = _origin_url(checkout)

    tmp = Path(tempfile.mkdtemp(prefix="lgm-review-", dir=artifacts))
    try:
        stage = tmp / "stage.git"
        init = _git(tmp, ["init", "--bare", "stage.git"])
        if init.returncode != 0:
            raise LgmError("git", init.stderr.strip() or "git init --bare failed")
        target_branch = detail_a["target_branch"]
        _fetch_refs(stage, origin_url,
                    [f"+refs/heads/{target_branch}:refs/heads/{target_branch}"])
        if fork:
            mr_ref = f"refs/merge-requests/{iid}/head"
            _fetch_refs(stage, origin_url, [f"+{mr_ref}:{mr_ref}"])
            fetched = _ref_sha(stage, mr_ref)
            if fetched != detail_a["sha"]:
                raise MrReviewError(
                    "snapshot-raced",
                    f"MR !{iid} ref {mr_ref} points at {fetched or 'nothing'}, "
                    f"expected head {detail_a['sha']}")
        else:
            source_branch = detail_a["source_branch"]
            _fetch_refs(stage, origin_url,
                        [f"+refs/heads/{source_branch}:refs/heads/{source_branch}"])

        raw_discussions = client.gitlab_list_discussions(iid, project=project)
        discussions_at = _now()

        detail_b = client.gitlab_get_mr(iid, project=project)
        tip_b = client.gitlab_branch_tip(
            detail_b["target_branch"], project=str(detail_b["target_project_id"]))
        changes = _race_changes(detail_a, detail_b, tip_a, tip_b)
        if changes:
            raise MrReviewError(
                "snapshot-raced",
                f"MR !{iid} changed during collection: {', '.join(changes)}")

        tips = {
            "source": detail_b["sha"],
            "target_tip": tip_b,
            "diff_start": refs["start_sha"],
            "diff_base": refs["base_sha"],
            "diff_head": refs["head_sha"],
        }
        for label, sha in tips.items():
            if not _commit_exists(stage, sha):
                raise MrReviewError(
                    "review-object-missing",
                    f"MR !{iid} {label} commit {sha} is not available after fetch")

        bases = _merge_bases(stage, detail_b["sha"], tip_b)
        limitations: list[str] = []
        if fork:
            limitations.append("source branch fetched through target-project MR ref")
        if refs["base_sha"] not in bases:
            limitations.append(
                f"gitlab base_sha {refs['base_sha']} not among computed merge bases")
        discussions = _convert_discussions(raw_discussions, limitations)

        snapshot = {
            "schema_version": SCHEMA_VERSION,
            "repo": repo,
            "gitlab_url": client.gitlab_base_url(project=project),
            "source_project_id": detail_b["source_project_id"],
            "target_project_id": detail_b["target_project_id"],
            "iid": iid,
            "mr_url": detail_b.get("web_url"),
            "title": detail_b.get("title"),
            "description": detail_b.get("description"),
            "state": detail_b.get("state"),
            "draft": bool(detail_b.get("draft", False)),
            "source_branch": detail_b["source_branch"],
            "target_branch": detail_b["target_branch"],
            "source_sha": detail_b["sha"],
            "target_tip_sha": tip_b,
            "source_fetch_ref": f"refs/merge-requests/{iid}/head" if fork else None,
            "gitlab_diff_refs": dict(refs),
            "computed_merge_bases": bases,
            "comparison_base_sha": refs["base_sha"],
            "comparison_kind": "gitlab-diff",
            "observed_started_at": _iso(started),
            "observed_finished_at": _iso(_now()),
            "mr_updated_at": detail_b.get("updated_at"),
            "discussions_fetched_at": _iso(discussions_at),
            "discussions_complete": True,
            "limitations": limitations,
            "discussions": discussions,
        }
        snapshot["code_id"] = compute_code_id(snapshot)
        snapshot["version_fingerprint"] = compute_version_fingerprint(snapshot)
        snapshot["snapshot_id"] = compute_snapshot_id(snapshot)
        verify_snapshot(snapshot)

        bundle_tmp = tmp / "code.bundle"
        size = _create_bundle(stage, tips, bundle_tmp)
        if size > CODE_BUNDLE_MAX_BYTES:
            raise MrReviewError(
                "review-package-too-large",
                f"MR !{iid} code bundle is {size} bytes, above the "
                f"{CODE_BUNDLE_MAX_BYTES} byte cap")
        bundle_path = artifacts / f"{snapshot['code_id']}.bundle"
        os.replace(bundle_tmp, bundle_path)
    finally:
        _discard_tree(tmp)

    return {
        "manifest": snapshot,
        "bundle_path": str(bundle_path),
        "bundle_size": size,
        "code_id": snapshot["code_id"],
        "digests": {
            "snapshot_id": snapshot["snapshot_id"],
            "code_id": snapshot["code_id"],
            "version_fingerprint": snapshot["version_fingerprint"],
        },
    }
