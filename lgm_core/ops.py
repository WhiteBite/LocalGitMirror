"""Operation registry — the single source of truth for lgm CLI + MCP.

Each ``Op`` has a ``name``, ``summary``, typed ``params``, and a ``run(ctx, args)``
that returns a plain JSON-serializable dict.  The CLI (``lgm.py``) generates
one argparse subcommand per op; the MCP server (``lgm_mcp.py``) generates one
tool per op.  Adding a new capability = adding one ``Op`` here.

All helper functions (gradle/maven/npm scanning, pom fetching, etc.) are
moved from the original ``lgm.py`` so the registry is self-contained.
"""
from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional

from .config import Config, cfg
from .client import MirrorClient, LgmError
from .crypto import encrypt_bundle, decrypt_bundle

# npm helpers — same import the original lgm.py uses.
from _lgm_npm import (  # type: ignore
    npm_cache_dir, npm_offline_mirror,
    npm_find_tarball, _detect_npm_missing, _npm_mirror_rel,
)

from .op_models import Param, Op, Ctx, _client, _repo_arg, _role_guess
from .deps_scanner import (
    gradle_candidate_roots, scan_cache, maven_local_root, _sha1_of,
    scan_maven_local, maven_local_relpath, _DOC_SUFFIXES,
    _classify_artifact, _pick_shipable, _ship_rel_for, _pom_packaging,
    _parent_coord_of_pom, _expand_parent_pom_closure, _GRADLE_INIT_SCRIPT,
    _read_root_project_name, _detect_java_home, _run_gradle_init,
    _ensure_mavenlocal_init_script, _maven_local_jars_without_poms,
    _missing_parent_poms, _fetch_pom_batch, _npmrc_registries,
    _rewrite_lock_to_npmjs, _yarn_offline_mirror, _yarn_tarball_name,
    _read_tgz_name_version, _build_yarn_mirror, _set_global_yarn_mirror,
)
from .ops_deps import (
    op_scan, op_pending, op_debug, op_respond, op_apply, op_request,
    op_fetch_poms,
)
from .ops_vault import (
    scan_maven_local_protected, scan_gradle_cache_protected,
    build_publication_zip, op_publish,
)
from .ops_git import (
    op_status, op_repos, op_branches, send_branch, send_branches, op_send,
    op_pull, op_deps_request, op_vault_status, op_branch_delete, op_prune,
    op_preview_pull, op_has_commits,
    _GUIDE, op_guide, op_update,
)
from .ops_mr import (
    _MRN_TITLE, _MRN_BRANCH, _parse_mr_notes_head, _mr_list_from_postbox,
    op_mr_list, _resolve_mr_targets, _fetch_mr_branches, _local_tip,
    _mirror_refs_safe, _existing_shas, op_mr_send,
    op_mr_notes, op_mr_replies_send, op_mr_replies_status, op_mr_replies_push,
    op_mr_pending, op_mr_review_receipts,
)
from .ops_chat import (
    op_chat_send, op_chat_list,
    op_file_send, op_file_list, op_file_get, op_file_ack,
    op_config_get, op_config_set,
)
from .ops_projects import (
    op_mr_projects_scan, op_mr_projects_list, op_mr_sync_all,
)
from .mr_review_models import (
    BUDGET_BYTES_MAX, BUDGET_BYTES_MIN, MrReviewError, validate_budget,
)
from .mr_review_reader import DEFAULT_BUDGET_BYTES, DEFAULT_CONTEXT_LINES
from .mr_review_store import MrReviewStore
from .mr_review_progress import mr_review_progress, mr_review_refresh
from .mr_review_environment import mr_review_materialize
from .mr_review_findings import mr_review_findings
from .ops_review import (
    mr_review_prepare, mr_review_open, mr_review_read, mr_review_search,
    mr_review_context,
)

_BUDGET_HELP = ("Upper bound in UTF-8 bytes for the fully serialized result "
                f"({BUDGET_BYTES_MIN}..{BUDGET_BYTES_MAX})")
_HOME_HELP = ("Review store root override (default: LGM_REVIEWS_HOME or "
              "~/.lgm/reviews)")
_HEX64 = r"^$|^[0-9a-f]{64}$"
_HEX64_REQUIRED = r"^[0-9a-f]{64}$"


def _review_failure(exc: MrReviewError) -> dict:
    return {"success": False, "code": exc.code, "error": exc.message}


def _review_home(args: dict) -> str | None:
    return str(args.get("home") or "") or None


def _review_paths(args: dict) -> list[str]:
    paths = args.get("paths") or []
    if not isinstance(paths, list) \
            or any(not isinstance(p, str) or not p for p in paths):
        raise MrReviewError("invalid-schema",
                            "paths must be an array of non-empty strings")
    return paths


def op_mr_review_prepare(ctx: Ctx, args: dict) -> dict:
    c = _client(ctx)
    repo = _repo_arg(args)
    project = str(args.get("project") or "")
    checkout = str(args.get("checkout") or "")
    if not project or not checkout:
        raise LgmError("config", "project and checkout are required paths")
    try:
        return mr_review_prepare(
            c, repo, int(args.get("iid") or 0), project, checkout,
            allow_code_upload=not bool(args.get("notes_only")),
            work_dir=str(args.get("work_dir") or "") or None)
    except MrReviewError as exc:
        return _review_failure(exc)


def op_mr_review_open(ctx: Ctx, args: dict) -> dict:
    c = _client(ctx)
    repo = _repo_arg(args)
    try:
        budget = validate_budget(args.get("budget_bytes"))
        return mr_review_open(
            c, repo, int(args.get("iid") or 0),
            snapshot_id=str(args.get("snapshot_id") or ""),
            cursor=str(args.get("cursor") or ""),
            budget_bytes=budget, home=_review_home(args))
    except MrReviewError as exc:
        return _review_failure(exc)


def op_mr_review_read(ctx: Ctx, args: dict) -> dict:
    repo = _repo_arg(args)
    try:
        budget = validate_budget(args.get("budget_bytes"))
        paths = _review_paths(args)
        return mr_review_read(
            repo, str(args.get("snapshot_id") or ""),
            str(args.get("view") or ""),
            paths=paths, group=str(args.get("group") or ""),
            side=str(args.get("side") or "new"),
            cursor=str(args.get("cursor") or ""),
            budget_bytes=budget,
            context_lines=int(args.get("context_lines") or 0),
            home=_review_home(args))
    except MrReviewError as exc:
        return _review_failure(exc)


def op_mr_review_search(ctx: Ctx, args: dict) -> dict:
    repo = _repo_arg(args)
    try:
        budget = validate_budget(args.get("budget_bytes"))
        paths = _review_paths(args)
        side = str(args.get("side") or "")
        return mr_review_search(
            repo, str(args.get("snapshot_id") or ""),
            str(args.get("query") or ""),
            side=side or None, scope=str(args.get("scope") or "tree"),
            paths=paths or None, cursor=str(args.get("cursor") or ""),
            budget_bytes=budget, home=_review_home(args))
    except MrReviewError as exc:
        return _review_failure(exc)


def op_mr_review_context(ctx: Ctx, args: dict) -> dict:
    repo = _repo_arg(args)
    try:
        budget = validate_budget(args.get("budget_bytes"))
        return mr_review_context(
            repo, str(args.get("snapshot_id") or ""),
            str(args.get("path") or ""),
            line=int(args.get("line") or 0),
            side=str(args.get("side") or "new"),
            budget_bytes=budget,
            context_lines=int(args.get("context_lines") or 0),
            home=_review_home(args))
    except MrReviewError as exc:
        return _review_failure(exc)


def op_mr_review_progress(ctx: Ctx, args: dict) -> dict:
    repo = _repo_arg(args)
    try:
        return mr_review_progress(
            repo, int(args.get("iid") or 0),
            action=str(args.get("action") or "list"),
            file=str(args.get("file") or ""), text=str(args.get("text") or ""),
            expected_revision=int(args.get("expected_revision") or 0),
            home=_review_home(args))
    except MrReviewError as exc:
        return _review_failure(exc)


def op_mr_review_refresh(ctx: Ctx, args: dict) -> dict:
    repo = _repo_arg(args)
    try:
        return mr_review_refresh(
            repo, int(args.get("iid") or 0),
            previous_snapshot_id=str(args.get("previous_snapshot_id") or ""),
            home=_review_home(args))
    except MrReviewError as exc:
        return _review_failure(exc)


def op_mr_review_materialize(ctx: Ctx, args: dict) -> dict:
    repo = _repo_arg(args)
    action = str(args.get("action") or "")
    try:
        if action not in ("create", "status", "release"):
            raise MrReviewError(
                "invalid-schema",
                "action must be one of ['create', 'release', 'status'], "
                f"got {action!r}")
        store = MrReviewStore(str(args.get("gitlab_url") or ""),
                              int(args.get("target_project_id") or 0), repo,
                              home=_review_home(args))
        return mr_review_materialize(
            store, action,
            environment_id=str(args.get("environment_id") or ""),
            purpose=str(args.get("purpose") or ""),
            fresh=bool(args.get("fresh")),
            snapshot_id=str(args.get("snapshot_id") or ""))
    except MrReviewError as exc:
        return _review_failure(exc)


def op_mr_review_findings(ctx: Ctx, args: dict) -> dict:
    try:
        return mr_review_findings(
            str(args.get("action") or ""),
            file=str(args.get("file") or ""),
            text=str(args.get("text") or ""),
            expected_revision=int(args.get("expected_revision") or 0),
            home=_review_home(args))
    except MrReviewError as exc:
        return _review_failure(exc)


# ── REGISTRY ─────────────────────────────────────────────────────────────────

REGISTRY: list[Op] = [
    Op(
        name="scan",
        summary="Scan local gradle cache for cached artifacts.",
        params=[
            Param("gradle_home", "str", "", "Override gradle user home"),
            Param("filter", "str", "", "Group substring filter"),
            Param("verbose", "bool", False, "Show individual artifacts"),
        ],
        run=op_scan,
        needs_client=False,
    ),
    Op(
        name="pending",
        summary="List pending dependency requests on the Mirror server.",
        params=[Param("repo", "str", "", "Repository name", required=True)],
        run=op_pending,
    ),
    Op(
        name="request",
            summary="Build a manifest v3 from a project and POST it to Mirror.",
            params=[
                Param("repo", "str", "", "Mirror repository name", required=True),
                Param("project", "str", "", "Path to gradle project root", required=True),
            Param("npm_scopes", "str", "", "Comma-separated npm scopes to treat as corporate"),
            Param("dry_run", "bool", False, "Show what would be requested without posting"),
        ],
        run=op_request,
    ),
    Op(
        name="respond",
        summary="Find requested deps in local cache and ship them to Mirror.",
            params=[
                Param("repo", "str", "", "Mirror repository name", required=True),
                Param("project", "str", "", "Project dir (for package-lock.json bundling)"),
            Param("dry_run", "bool", False, "Show what would be sent without posting"),
            Param("id", "str", "",
                  "Pending request id to handle (prefix match, as shown by pending; default: first)",
                  pattern=r"^$|^[0-9a-f]{1,32}$"),
        ],
        run=op_respond,
    ),
    Op(
        name="apply",
        summary="Download and unpack a deps response into the local cache.",
        params=[
                Param("repo", "str", "", "Mirror repository name", required=True),
                Param("project", "str", "", "Project dir to write package-lock.json into"),
            Param("npm_install", "bool", False, "Run npm install after writing lockfile"),
            Param("yarn", "bool", False, "Set up yarn offline-mirror from corporate tarballs"),
            Param("yarn_install", "bool", False, "Like --yarn, then run yarn install --offline"),
            Param("dry_run", "bool", False, "Show what would be installed without writing"),
            Param("id", "str", "",
                  "Response id to apply (prefix match, as shown by debug; default: first)",
                  pattern=r"^$|^[0-9a-f]{1,32}$"),
        ],
        run=op_apply,
    ),
    Op(
        name="fetch-poms",
        summary="Fetch missing poms from a public Maven repository.",
        params=[
            Param("repo_url", "str", "https://repo.maven.apache.org/maven2", "Public Maven repo URL"),
            Param("dry_run", "bool", False, "Show what would be fetched without downloading"),
        ],
        run=op_fetch_poms,
        needs_client=False,
    ),
    Op(
        name="publish",
        summary="Scan local caches for protected artifacts, encrypt, and publish to Mirror vault.",
        params=[Param("dry_run", "bool", False, "Show what would be published without sending")],
        run=op_publish,
    ),
    Op(
        name="debug",
        summary="Full diagnostics: env vars, cache roots, mirror connectivity.",
        params=[Param("repo", "str", "", "Repository name", required=True)],
        run=op_debug,
    ),
    # ── New ops ──────────────────────────────────────────────────────────
    Op(
        name="status",
        summary="Server capabilities + repos + configured machine role.",
        params=[],
        run=op_status,
    ),
    Op(
        name="repos",
        summary="List repositories on the mirror.",
        params=[],
        run=op_repos,
    ),
    Op(
        name="branches",
        summary="List all branch tips on the mirror for a repo.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("project", "str", "",
                  "Local git project root; annotate each branch with ahead/behind vs the local branch of the same name"),
        ],
        run=op_branches,
    ),
    Op(
        name="send",
        summary="Create a git bundle locally and send it to the mirror.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("branch", "str", "", "Branch to bundle (default: --all)"),
            Param("project", "str", "", "Path to git project root", required=True),
            Param("dry_run", "bool", False, "Create bundle without sending"),
        ],
        run=op_send,
    ),
    Op(
        name="pull",
        summary="Pull an encrypted git bundle and fast-forward local branches and the current working tree.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("branch", "str", "", "Branch to pull"),
            Param("since", "str", "", "Exclude commits reachable from this SHA",
                  pattern=r"^$|^[0-9a-f]{4,40}$"),
            Param("haves", "str", "", "Comma-separated SHAs the client already has",
                  pattern=r"^$|^[0-9a-f]{4,40}(,[0-9a-f]{4,40})*$"),
            Param("project", "str", "", "Path to git project to fetch into"),
            Param("dry_run", "bool", False, "Download bundle without fetching"),
        ],
        run=op_pull,
    ),
    Op(
        name="preview_pull",
        summary="Lightweight incoming-commits check: asks the mirror whether a pull would bring anything (no bundle transfer).",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("since", "str", "", "Local SHA to compare against (default: HEAD of --project)",
                  pattern=r"^$|^[0-9a-f]{4,40}$"),
            Param("project", "str", "", "Local git project root; its HEAD becomes --since when --since is empty"),
        ],
        run=op_preview_pull,
    ),
    Op(
        name="has_commits",
        summary="Ask the mirror which of the given commits it already holds (inspect before send).",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("commits", "str", "", "Comma-separated SHAs to check (default: local branch tips from --project)",
                  pattern=r"^$|^[0-9a-f]{4,40}(,[0-9a-f]{4,40})*$"),
            Param("project", "str", "", "Local git project root; its branch tips become --commits when --commits is empty"),
        ],
        run=op_has_commits,
    ),
    Op(
        name="deps_request",
        summary="Post a pre-built encrypted manifest file to /api/documents/submit.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("manifest", "str", "", "Path to encrypted manifest file", required=True),
        ],
        run=op_deps_request,
    ),
    Op(
        name="vault_status",
        summary="Vault diagnostics: inventory, conflicts, wanted positions.",
        params=[],
        run=op_vault_status,
    ),
    Op(
        name="branch_delete",
        summary="Delete one or more branches on the mirror (comma-separated).",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("branches", "str", "", "Comma-separated branch names to delete", required=True),
        ],
        run=op_branch_delete,
    ),
    Op(
        name="prune",
        summary="List (dry-run) or delete merged/stale branches on the mirror.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("bases", "str", "",
                  "Comma-separated base branches (a branch merged into any base is prunable); required unless --older-days is set"),
            Param("older_days", "int", 0,
                  "Also prune branches older than N days by committerdate (0=off)",
                  minimum=0),
            Param("keep", "str", "",
                  "Comma-separated branches to always keep (HEAD is always protected)"),
            Param("apply", "bool", False,
                  "Actually delete candidates (default: dry-run listing)"),
        ],
        run=op_prune,
    ),
    Op(
        name="mr_list",
        summary="List open GitLab merge requests; without GitLab config falls back to MR notes transferred via the mirror.",
        params=[
            Param("repo", "str", "", "Mirror repository name (cache fallback scans all repos when empty)"),
        ],
        run=op_mr_list,
    ),
    Op(
        name="mr_send",
        summary="Fetch GitLab MR branches and send them to the mirror in one deduplicated bundle (skips tips the mirror already has).",
        params=[
            Param("iid", "int", 0, "GitLab MR iid (resolves the source branch)",
                  minimum=0),
            Param("iids", "str", "", "Comma-separated MR iids, e.g. '41,42,43'",
                  pattern=r"^$|^[0-9]+(,[0-9]+)*$"),
            Param("all_open", "bool", False, "Send all open MRs"),
            Param("branch", "str", "", "MR source branch(es), comma-separated (used when no iid given)"),
            Param("project", "str", "", "Local git root to fetch into", required=True),
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("dry_run", "bool", False,
                  "Print the send plan without fetching, moving refs or uploading"),
        ],
        run=op_mr_send,
    ),
    Op(
        name="mr_notes",
        summary=("Read MR discussion notes from the mirror postbox (mr-notes/mr-!N.md); "
                 "newest per MR. Each thread block carries its 'ID треда' and 'Место: file:line' "
                 "— copy the id into a '## thread <id>' section of mr_replies_send."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "Only this MR iid (0 = all)", minimum=0),
        ],
        run=op_mr_notes,
    ),
    Op(
        name="mr_replies_send",
        summary=("Upload agent MR review replies to the mirror postbox; the work machine posts "
                 "them to GitLab. Reply markdown sections: '## thread <id>' — answer an existing "
                 "thread (id from mr_notes), '## new <file>:<line>' — new code-anchored discussion, "
                 "'## new' — general MR note; 'resolve: yes' as the first line of a thread section "
                 "resolves it. The '# MR !N' header is injected when missing. For a v2 review "
                 "batch (mr_review_findings create_batch JSON) pass review_file instead of "
                 "file/text; it is validated by the same pipeline and uploaded to "
                 "mr-replies/mr-!<iid>-<batch_id>.review.json. Comments must read "
                 "as a human reviewer's: issues only (bug / bad call / miss), 1-3 sentences, "
                 "anchored at the offending line — no praise, no code restating, no filler."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "GitLab MR iid", required=True, minimum=1),
            Param("file", "str", "", "Path to a local replies-!N.md file (same section format)"),
            Param("text", "str", "", "Inline replies markdown: '## thread <id>' / "
                                     "'## new <file>:<line>' / '## new' sections, "
                                     "'resolve: yes' first line per thread"),
            Param("force", "bool", False, "Skip the precheck that rejects anchors colliding "
                                          "with unresolved threads and unknown thread ids"),
            Param("project", "str", "",
                  "Local git root for the anchor warnings (default: the mirror workspace "
                  "for --repo); '## new' paths outside the MR diff are warned, not blocked"),
            Param("check", "bool", False,
                  "Validate the reply grammar and run the precheck + anchor "
                  "warnings without uploading anything"),
            Param("review_file", "str", "",
                  "Path to a v2 review batch JSON (mr_review_findings create_batch "
                  "output); exclusive with file/text — validated through the same "
                  "pipeline and uploaded to mr-replies/mr-!<iid>-<batch_id>.review.json"),
        ],
        run=op_mr_replies_send,
    ),
    Op(
        name="mr_replies_status",
        summary="Show the work PC's publish reports for agent MR replies (posted/failed per MR).",
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "Only this MR iid (0 = all)", minimum=0),
        ],
        run=op_mr_replies_status,
    ),
    Op(
        name="mr_replies_push",
        summary=("Publish pending mr-replies files from the mirror postbox to GitLab "
                 "(work side, headless twin of the plugin's push): thread replies, "
                 "resolve flags, anchored discussions with a general-note fallback when "
                 "the line is outside the MR diff, exactly-once markers, a status report "
                 "to mr-replies-status/, and a postbox ack only when every reply reached "
                 "a terminal outcome. Publishes exactly what the replies file contains."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "Only this MR iid (0 = all)", minimum=0),
            Param("dry_run", "bool", False,
                  "Plan only: list what would be posted/skipped and why; no GitLab "
                  "writes, no postbox acks, no status upload"),
        ],
        run=op_mr_replies_push,
    ),
    Op(
        name="mr_pending",
        summary=("One pipeline view per MR: notes freshness (mtime + age), replies "
                 "pending in the postbox, the last publish outcome and the closed "
                 "state with evidence (gitlab | marker | note-mtime | expired)."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
        ],
        run=op_mr_pending,
    ),
    Op(
        name="mr_review_receipts",
        summary=("Detailed v2 review publication receipts: action=handoff (work) uploads "
                 "the immutable per-(batch, attempt) receipt artifact for every completed "
                 "journal attempt to mr-review/receipts/ and acks the batch item only "
                 "after a successful upload of an all-terminal attempt; action=import "
                 "(home) stores receipt artifacts in the review store, appends finding "
                 "delivery revisions and acks the artifact only after a full import."),
        params=[
            Param("action", "str", "", "handoff (work side) or import (home side)",
                  required=True, pattern=r"^(handoff|import)$"),
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "Only this MR iid (0 = all)", minimum=0),
            Param("dry_run", "bool", False,
                  "Plan only: no uploads, no acks, no store writes"),
        ],
        run=op_mr_review_receipts,
    ),
    Op(
        name="mr_review_prepare",
        summary=("WORK: collect one consistent MR review snapshot (metadata, discussions "
                 "and a self-contained code bundle) and place its artifacts in the "
                 "encrypted postbox: bundle first, transfer envelope last. Deduplicated "
                 "by code_id; an aged bundle is refreshed so HOME can still fetch it."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "GitLab MR iid", required=True, minimum=1),
            Param("project", "str", "",
                  "Local GitLab checkout used to resolve the origin remote",
                  required=True),
            Param("checkout", "str", "",
                  "Checkout path the snapshot is collected from", required=True),
            Param("notes_only", "bool", False,
                  "Skip the code artifact upload (metadata/discussions only); an "
                  "absent or aged bundle is reported as a limitation instead"),
            Param("work_dir", "str", "",
                  "Directory for temporary bundle artifacts (default: system temp)"),
        ],
        run=op_mr_review_prepare,
    ),
    Op(
        name="mr_review_open",
        summary=("HOME: import the requested (or latest) complete snapshot of an MR from "
                 "the postbox into the persistent review store and return its identity, "
                 "limitations and the first bounded inventory page. Call again with the "
                 "page's next_cursor for subsequent inventory pages (no postbox access); "
                 "an already-imported snapshot never depends on the postbox TTL. A "
                 "missing/expired bundle or digest drift returns a typed not-ready "
                 "result and leaves the current snapshot readable."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "GitLab MR iid", required=True, minimum=1),
            Param("snapshot_id", "str", "",
                  "Exact snapshot to import (64-char hex; default: latest in the postbox)",
                  pattern=_HEX64),
            Param("cursor", "str", "",
                  "Inventory page cursor from a previous open (exclusive with snapshot_id)"),
            Param("budget_bytes", "int", DEFAULT_BUDGET_BYTES, _BUDGET_HELP,
                  minimum=BUDGET_BYTES_MIN, maximum=BUDGET_BYTES_MAX),
            Param("home", "str", "", _HOME_HELP),
        ],
        run=op_mr_review_open,
    ),
    Op(
        name="mr_review_read",
        summary=("HOME: read one bounded page of an imported snapshot: view=diff (changed "
                 "files with old/new coordinates and OIDs), view=file (literal file "
                 "content of one side) or view=discussions (review threads). Pages carry "
                 "complete/next_cursor/totals/shown range; reads come only from the "
                 "store's Git objects, never a worktree. This is the bounded snapshot "
                 "reader, not the legacy mr_notes full-text dump."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("snapshot_id", "str", "",
                  "Imported snapshot to read (64-char hex)", required=True,
                  pattern=_HEX64_REQUIRED),
            Param("view", "str", "", "Page view", required=True,
                  enum=["diff", "file", "discussions"]),
            Param("paths", "list", None,
                  "Restrict to these paths (repeatable; exclusive with group)"),
            Param("group", "str", "",
                  "Restrict to one top-level module/group (exclusive with paths)"),
            Param("side", "str", "new", "Which side to read",
                  enum=["old", "new", "target"]),
            Param("cursor", "str", "", "Page cursor from a previous read"),
            Param("budget_bytes", "int", DEFAULT_BUDGET_BYTES, _BUDGET_HELP,
                  minimum=BUDGET_BYTES_MIN, maximum=BUDGET_BYTES_MAX),
            Param("context_lines", "int", DEFAULT_CONTEXT_LINES,
                  "Diff context lines per hunk", minimum=0),
            Param("home", "str", "", _HOME_HELP),
        ],
        run=op_mr_review_read,
        needs_client=False,
    ),
    Op(
        name="mr_review_search",
        summary=("HOME: bounded literal search over an imported snapshot's Git objects "
                 "(never a worktree, no regex): scope=tree searches whole file contents, "
                 "added/removed/changed search the corresponding changed lines. Stable "
                 "hits carry path/side/line/column plus a reader handle; pages carry "
                 "complete/next_cursor/totals."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("snapshot_id", "str", "",
                  "Imported snapshot to search (64-char hex)", required=True,
                  pattern=_HEX64_REQUIRED),
            Param("query", "str", "", "Literal text to find", required=True),
            Param("side", "str", "", "Side to search (empty: derived from scope)",
                  enum=["", "old", "new", "target"]),
            Param("scope", "str", "tree", "What to search",
                  enum=["tree", "added", "removed", "changed"]),
            Param("paths", "list", None, "Restrict to these paths (repeatable)"),
            Param("cursor", "str", "", "Page cursor from a previous search"),
            Param("budget_bytes", "int", DEFAULT_BUDGET_BYTES, _BUDGET_HELP,
                  minimum=BUDGET_BYTES_MIN, maximum=BUDGET_BYTES_MAX),
            Param("home", "str", "", _HOME_HELP),
        ],
        run=op_mr_review_search,
        needs_client=False,
    ),
    Op(
        name="mr_review_context",
        summary=("HOME: bounded textual context around one line of an imported snapshot's "
                 "side (line=0 returns the file head window); huge lines are split with "
                 "byte continuation, never silently truncated."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("snapshot_id", "str", "",
                  "Imported snapshot to read (64-char hex)", required=True,
                  pattern=_HEX64_REQUIRED),
            Param("path", "str", "", "File path inside the snapshot", required=True),
            Param("line", "int", 0, "1-based line to center on (0: file head)",
                  minimum=0),
            Param("side", "str", "new", "Which side to read",
                  enum=["old", "new", "target"]),
            Param("budget_bytes", "int", DEFAULT_BUDGET_BYTES, _BUDGET_HELP,
                  minimum=BUDGET_BYTES_MIN, maximum=BUDGET_BYTES_MAX),
            Param("context_lines", "int", DEFAULT_CONTEXT_LINES,
                  "Lines of context around the target line", minimum=0),
            Param("home", "str", "", _HOME_HELP),
        ],
        run=op_mr_review_context,
        needs_client=False,
    ),
    Op(
        name="mr_review_progress",
        summary=("HOME: list or mark per-unit review progress of one MR in the review "
                 "store. action=list pages the current (or requested) snapshot's units "
                 "with their states (optional JSON query via file/text: {snapshot_id?, "
                 "cursor?, limit?}); action=mark writes one unit state (JSON input "
                 "{snapshot_id?, path, state, reason?, evidence?} in exactly one of "
                 "file/text) with compare-and-swap on expected_revision."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "GitLab MR iid", required=True, minimum=1),
            Param("action", "str", "list", "list or mark", enum=["list", "mark"]),
            Param("file", "str", "", "Path to a JSON input file"),
            Param("text", "str", "", "Inline JSON input"),
            Param("expected_revision", "int", 0,
                  "CAS revision for action=mark (0 inserts a fresh row)", minimum=0),
            Param("home", "str", "", _HOME_HELP),
        ],
        run=op_mr_review_progress,
        needs_client=False,
    ),
    Op(
        name="mr_review_refresh",
        summary=("HOME: compare the previous installed snapshot of one MR with the current "
                 "one and migrate review progress plus finding applicability (unchanged "
                 "units carry their state over; changed units fall back to delivered; "
                 "stale findings are invalidated). Run after mr_review_open imported a "
                 "newer snapshot; the current pointer is never moved."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "GitLab MR iid", required=True, minimum=1),
            Param("previous_snapshot_id", "str", "",
                  "Explicit previous snapshot (64-char hex; default: most recently "
                  "imported other snapshot of the MR)", pattern=_HEX64),
            Param("home", "str", "", _HOME_HELP),
        ],
        run=op_mr_review_refresh,
        needs_client=False,
    ),
    Op(
        name="mr_review_materialize",
        summary=("HOME: manage detached worktree environments at a snapshot's exact "
                 "source_sha for local probes: action=create (one reusable clean "
                 "environment per snapshot; fresh=true creates an independent one), "
                 "action=status (clean/dirty/busy/missing plus tracked-diff digest and "
                 "untracked/ignored summaries), action=release (explicit removal of a "
                 "clean environment). A dirty or busy environment is never reset."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("gitlab_url", "str", "",
                  "GitLab base URL of the MR's project", required=True),
            Param("target_project_id", "int", 0, "GitLab target project id",
                  required=True, minimum=1),
            Param("action", "str", "", "create, status or release", required=True,
                  enum=["create", "status", "release"]),
            Param("environment_id", "str", "",
                  "Environment id (required for status/release; create issues its own)"),
            Param("purpose", "str", "",
                  "Free-form purpose recorded with the environment (create)"),
            Param("fresh", "bool", False,
                  "Create an independent environment even if a reusable clean one exists"),
            Param("snapshot_id", "str", "",
                  "Snapshot to materialize (64-char hex; required for create)",
                  pattern=_HEX64),
            Param("home", "str", "", _HOME_HELP),
        ],
        run=op_mr_review_materialize,
        needs_client=False,
    ),
    Op(
        name="mr_review_findings",
        summary=("HOME: save, list or batch-select revisioned review findings in the "
                 "review store. action=save appends one finding revision (full finding "
                 "document via file/text, CAS on expected_revision); action=list pages "
                 "the findings of one identity; action=create_batch validates the "
                 "selection and writes a v2 review batch artifact for mr_replies_send "
                 "review_file."),
        params=[
            Param("action", "str", "", "list, save or create_batch", required=True,
                  enum=["list", "save", "create_batch"]),
            Param("file", "str", "", "Path to a JSON input file"),
            Param("text", "str", "", "Inline JSON input"),
            Param("expected_revision", "int", 0,
                  "CAS revision count for action=save (0 for a new finding)", minimum=0),
            Param("home", "str", "", _HOME_HELP),
        ],
        run=op_mr_review_findings,
        needs_client=False,
    ),
    Op(
        name="guide",
        summary="How to transfer code and corporate deps between work and home via the Mirror.",
        params=[],
        run=op_guide,
        needs_client=False,
    ),
    Op(
        name="update",
        summary="Download and install the latest CLI tools (lgm.py, lgm_mcp.py, lgm_core/) from the mirror.",
        params=[],
        run=op_update,
    ),
    Op(
        name="chat_send",
        summary="Send a chat message to the other machine via the exchange buffer.",
        params=[
            Param("text", "str", "", "Message text", required=True),
        ],
        run=op_chat_send,
    ),
    Op(
        name="chat_list",
        summary="Read chat messages from the exchange buffer (newest first).",
        params=[
            Param("count", "int", 20, "Max messages to return (1-100)",
                  minimum=1, maximum=100),
        ],
        run=op_chat_list,
    ),
    Op(
        name="file_send",
        summary="Send a file to the other machine via the encrypted postbox.",
        params=[
            Param("path", "str", "", "Local file path to send", required=True),
            Param("repo", "str", "", "Mirror repository name", required=True),
        ],
        run=op_file_send,
    ),
    Op(
        name="file_list",
        summary="List files in the postbox for a repo.",
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
        ],
        run=op_file_list,
    ),
    Op(
        name="file_get",
        summary="Download a file from the postbox.",
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("id", "str", "", "File ID from file_list", required=True,
                  pattern=r"^[0-9a-f]{32}$"),
            Param("save", "str", "", "Local path to save the file to"),
        ],
        run=op_file_get,
    ),
    Op(
        name="file_ack",
        summary="Delete one postbox item on the mirror by id (ack).",
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("id", "str", "", "File ID from file_list", required=True,
                  pattern=r"^[0-9a-f]{32}$"),
        ],
        run=op_file_ack,
    ),
    Op(
        name="config_get",
        summary="Read server settings (all, or a specific section).",
        params=[
            Param("section", "str", "", "Settings section: general, git, ui (empty = all)",
                  enum=["", "general", "git", "ui"]),
        ],
        run=op_config_get,
    ),
    Op(
        name="config_set",
        summary="Update a server setting for debugging/reconfiguration.",
        params=[
            Param("section", "str", "", "Settings section: general, git, ui", required=True,
                  enum=["general", "git", "ui"]),
            Param("key", "str", "", "Setting key within the section", required=True),
            Param("value", "str", "", "New value (auto-parsed: bool/int/float/JSON/string)", required=True),
        ],
        run=op_config_set,
    ),
    Op(
        name="mr_projects_scan",
        summary="Register local GitLab checkouts found under a root directory for mr_sync_all.",
        params=[
            Param("root", "list", None,
                  "One or more directories/checkouts to register (repeatable)", required=True),
        ],
        run=op_mr_projects_scan,
        needs_client=False,
    ),
    Op(
        name="mr_projects_list",
        summary="Show the registered MR-sync projects (repo, path, GitLab project).",
        params=[],
        run=op_mr_projects_list,
        needs_client=False,
    ),
    Op(
        name="mr_sync_all",
        summary="Sync every open MR of every registered project to the mirror: branches (mirror delta) + notes (content-hash delta); a full sync on WORK also prepares review snapshots.",
        params=[
            Param("dry_run", "bool", False, "List the MRs without transferring anything"),
            Param("notes_only", "bool", False,
                  "Skip the branch send/planner entirely; sync notes and closed markers only"),
        ],
        run=op_mr_sync_all,
    ),
]


def get_op(name: str) -> Op | None:
    """Find an op by name (case-insensitive)."""
    for op in REGISTRY:
        if op.name == name:
            return op
    return None


def op_names() -> list[str]:
    return [op.name for op in REGISTRY]
