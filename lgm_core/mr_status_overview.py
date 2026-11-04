"""mr_status and mr_overview ops: merge readiness and the HOME-side queue."""
from __future__ import annotations

from .client import LgmError
from .op_models import Ctx, _client, _repo_arg

_CI_FAILED = {"failed", "canceled"}
_CI_RUNNING = {"running", "pending", "created", "preparing",
               "waiting_for_resource", "scheduled"}


def _mr_status_item(mr: dict, approvals: dict | None) -> dict:
    """One MR's merge-readiness item; every applicable blocker is listed, not
    just the single reason ``detailed_merge_status`` reports."""
    state = mr.get("state", "")
    draft = bool(mr.get("draft") or mr.get("work_in_progress"))
    merge_status = mr.get("merge_status", "")
    detailed = mr.get("detailed_merge_status", "")
    has_conflicts = mr.get("has_conflicts")
    pipeline = mr.get("head_pipeline") or {}
    pipe_status = pipeline.get("status") or "none"

    blockers: list[str] = []
    if state != "opened":
        blockers.append(f"not_open ({state})")
    if draft:
        blockers.append("draft")
    if detailed and detailed != "mergeable":
        blockers.append(detailed)
    if (has_conflicts is True or merge_status == "cannot_be_merged") \
            and "conflict" not in blockers:
        blockers.append("conflict")
    if pipe_status in _CI_FAILED and "ci_must_pass" not in blockers:
        blockers.append("ci_must_pass")
    if pipe_status in _CI_RUNNING and "ci_still_running" not in blockers:
        blockers.append("ci_still_running")
    if mr.get("blocking_discussions_resolved") is False \
            and "discussions_not_resolved" not in blockers:
        blockers.append("discussions_not_resolved")

    if approvals is None:
        approvals_field: dict = {"unknown": True}
        approved: bool | None = None
    else:
        approved = bool(approvals.get("approved"))
        approvals_field = {
            "approved": approved,
            "required": int(approvals.get("approvals_required") or 0),
            "left": int(approvals.get("approvals_left") or 0),
        }
        if not approved and "not_approved" not in blockers:
            blockers.append("not_approved")

    mergeability_known = bool(detailed) or bool(merge_status) or has_conflicts is not None
    if not mergeability_known:
        blockers.append("mergeability_unknown")
        ready = False
    else:
        mergeable = (
            detailed == "mergeable"
            or (merge_status == "can_be_merged" and has_conflicts is not True
                and (approved is None or approved)
                and pipe_status not in _CI_FAILED and pipe_status not in _CI_RUNNING)
        )
        if not mergeable and not blockers:
            blockers.append(merge_status or "not_mergeable")
        ready = not blockers and mergeable

    return {
        "iid": mr.get("iid"),
        "title": mr.get("title", ""),
        "state": state,
        "draft": draft,
        "web_url": mr.get("web_url", ""),
        "merge_status": merge_status,
        "detailed_merge_status": detailed,
        "has_conflicts": has_conflicts,
        "pipeline": {"status": pipe_status, "web_url": pipeline.get("web_url") or ""},
        "approvals": approvals_field,
        "blocking_discussions_resolved": mr.get("blocking_discussions_resolved"),
        "merge_when_pipeline_succeeds": bool(mr.get("merge_when_pipeline_succeeds")),
        "should_remove_source_branch": bool(mr.get("should_remove_source_branch")),
        "user_notes_count": int(mr.get("user_notes_count") or 0),
        "ready": ready,
        "blockers": blockers,
    }


def op_mr_status(ctx: Ctx, args: dict, cfg, _repo_gitlab_project) -> dict:
    """Read-only WORK-side merge readiness: every blocker for each open MR at once.

    Aggregates ``detailed_merge_status`` (which reports only one reason),
    conflicts, pipeline state and approvals; never calls a merge/approve
    endpoint. A single unreadable MR lands in ``errors`` without aborting
    the batch.
    """
    c = _client(ctx)
    repo = _repo_arg(args)
    project = (str(args.get("project") or "").strip()
               or _repo_gitlab_project(repo) or cfg("GITLAB_PROJECT"))
    missing = [k for k in ("GITLAB_URL", "GITLAB_TOKEN") if not cfg(k)]
    if not project:
        missing.append("GITLAB_PROJECT")
    if missing:
        raise LgmError("config",
                       "GitLab URL/project/token not configured — set " + ", ".join(missing))
    role = cfg("LGM_MACHINE_ROLE").strip().lower()
    if role == "home":
        raise LgmError("config",
                       "mr_status reads GitLab from the work machine; "
                       "this machine is home (LGM_MACHINE_ROLE=home)",
                       machine_code="wrong-machine")
    if role != "work":
        raise LgmError("config",
                       "LGM_MACHINE_ROLE is neither home nor work — reading merge "
                       "readiness needs to know this is the work machine; set "
                       "LGM_MACHINE_ROLE=work",
                       machine_code="role-unset")
    iid = int(args.get("iid") or 0)
    if iid > 0:
        iids = [iid]
    else:
        iids = [m.get("iid") for m in c.gitlab_list_mrs(project) if m.get("iid")]
    items: list[dict] = []
    errors: list[dict] = []
    for n in iids:
        try:
            mr = c.gitlab_get_mr(n, project)
        except LgmError as e:
            errors.append({"iid": n, "error": str(e)})
            continue
        try:
            approvals = c.gitlab_mr_approvals(n, project)
        except LgmError:
            approvals = None
        items.append(_mr_status_item(mr, approvals))
    return {"success": True, "repo": repo, "project": project,
            "count": len(items), "items": items, "errors": errors}


def _compact_publish(last_publish: dict | None) -> dict | None:
    if not last_publish:
        return None
    if not last_publish.get("parsed"):
        return {"parsed": False}
    return {"parsed": True,
            "posted": last_publish.get("posted", 0),
            "failed": last_publish.get("failed", 0),
            "skipped": last_publish.get("skipped", 0)}


def op_mr_overview(ctx: Ctx, args: dict, op_mr_pending, imported_review_index) -> dict:
    """One compact cross-repo MR queue for HOME: notes freshness, pending replies,
    last publish outcome and the imported review snapshot per MR, in a single call.

    Reads only what HOME already has (transferred mr-notes, the review store);
    it makes no GitLab call and never claims a review is done. ``repo`` narrows
    to one repo, empty covers every registered project (falling back to the
    mirror's repo list). A per-repo failure lands in that repo's ``error`` and
    does not abort the rest.
    """
    from .ops_projects import _load_registry

    c = _client(ctx)
    repo = (args.get("repo") or "").strip()
    if repo:
        names = [repo]
    else:
        names = [e.get("repo") for e in _load_registry().get("projects", [])
                 if isinstance(e, dict) and e.get("repo")]
    if not names:
        names = [r.get("name") if isinstance(r, dict) else r
                 for r in (c.repos().get("repos") or [])]
    index = imported_review_index(args.get("home"))
    repos_out: list[dict] = []
    totals = {"repos": 0, "mrs": 0, "replies_pending": 0, "reviews_imported": 0}
    for name in names:
        if not name:
            continue
        try:
            pending = op_mr_pending(ctx, {"repo": name})
        except LgmError as e:
            repos_out.append({"repo": name, "error": e.message})
            continue
        items: list[dict] = []
        for it in pending.get("items") or []:
            iid = it.get("iid")
            imported = index.get((name, int(iid))) if iid is not None else None
            items.append({
                "iid": iid,
                "state": it.get("state"),
                "state_evidence": it.get("state_evidence"),
                "notes_age_seconds": (it.get("notes") or {}).get("age_seconds"),
                "replies_pending": bool(it.get("replies_pending")),
                "last_publish": _compact_publish(it.get("last_publish")),
                "review": {"imported": imported is not None,
                           "snapshot_id": (imported or {}).get("snapshot_id", ""),
                           "marks": (imported or {}).get("marks", 0)},
            })
        repos_out.append({"repo": name, "count": len(items), "items": items})
        totals["repos"] += 1
        totals["mrs"] += len(items)
        totals["replies_pending"] += sum(1 for i in items if i["replies_pending"])
        totals["reviews_imported"] += sum(1 for i in items if i["review"]["imported"])
    return {"success": True, "repo": repo, "totals": totals, "repos": repos_out}
