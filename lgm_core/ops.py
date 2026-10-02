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
    _GUIDE, op_guide,
)
from .ops_mr import (
    _MRN_TITLE, _MRN_BRANCH, _parse_mr_notes_head, _mr_list_from_postbox,
    op_mr_list, _resolve_mr_targets, _fetch_mr_branches, _local_tip,
    _mirror_refs_safe, _existing_shas, _new_commit_count, op_mr_send,
    op_mr_notes, op_mr_replies_send, op_mr_replies_status,
)


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
        params=[Param("repo", "str", "", "Repository name")],
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
                  "Pending request id to handle (prefix match, as shown by pending; default: first)"),
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
                  "Response id to apply (prefix match, as shown by debug; default: first)"),
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
        params=[Param("repo", "str", "", "Repository name")],
        run=op_debug,
    ),
    # ── New ops ──────────────────────────────────────────────────────────
    Op(
        name="status",
        summary="Server capabilities + repos + role guess.",
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
        summary="Pull an encrypted git bundle from the mirror and fetch it locally.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("branch", "str", "", "Branch to pull"),
            Param("since", "str", "", "Exclude commits reachable from this SHA"),
            Param("haves", "str", "", "Comma-separated SHAs the client already has"),
            Param("project", "str", "", "Path to git project to fetch into"),
            Param("dry_run", "bool", False, "Download bundle without fetching"),
        ],
        run=op_pull,
    ),
    Op(
        name="deps_request",
        summary="Post a pre-built encrypted manifest file to /api/documents/submit.",
        params=[
            Param("repo", "str", "", "Repository name"),
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
            Param("bases", "str", "master,develop,plan_fix",
                  "Comma-separated base branches (a branch merged into any base is prunable)"),
            Param("older_days", "int", 0,
                  "Also prune branches older than N days by committerdate (0=off)"),
            Param("keep", "str", "",
                  "Comma-separated branches to always keep (default: master,develop,plan_fix)"),
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
            Param("iid", "int", 0, "GitLab MR iid (resolves the source branch)"),
            Param("iids", "str", "", "Comma-separated MR iids, e.g. '41,42,43'"),
            Param("all_open", "bool", False, "Send all open MRs"),
            Param("branch", "str", "", "MR source branch(es), comma-separated (used when no iid given)"),
            Param("project", "str", "", "Local git root to fetch into", required=True),
            Param("repo", "str", "", "Mirror repository name", required=True),
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
            Param("iid", "int", 0, "Only this MR iid (0 = all)"),
        ],
        run=op_mr_notes,
    ),
    Op(
        name="mr_replies_send",
        summary=("Upload agent MR review replies to the mirror postbox; the work machine posts "
                 "them to GitLab. Reply markdown sections: '## thread <id>' — answer an existing "
                 "thread (id from mr_notes), '## new <file>:<line>' — new code-anchored discussion, "
                 "'## new' — general MR note; 'resolve: yes' as the first line of a thread section "
                 "resolves it. The '# MR !N' header is injected when missing. Comments must read "
                 "as a human reviewer's: issues only (bug / bad call / miss), 1-3 sentences, "
                 "anchored at the offending line — no praise, no code restating, no filler."),
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "GitLab MR iid", required=True),
            Param("file", "str", "", "Path to a local replies-!N.md file (same section format)"),
            Param("text", "str", "", "Inline replies markdown: '## thread <id>' / "
                                     "'## new <file>:<line>' / '## new' sections, "
                                     "'resolve: yes' first line per thread"),
        ],
        run=op_mr_replies_send,
    ),
    Op(
        name="mr_replies_status",
        summary="Show the work PC's publish reports for agent MR replies (posted/failed per MR).",
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "Only this MR iid (0 = all)"),
        ],
        run=op_mr_replies_status,
    ),
    Op(
        name="guide",
        summary="How to transfer code and corporate deps between work and home via the Mirror.",
        params=[],
        run=op_guide,
        needs_client=False,
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
