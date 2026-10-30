"""Human-readable rendering for op results.

Each ``render_<opname>`` takes the dict returned by ``Op.run`` and produces
a list of lines for terminal output.  The CLI calls ``render(op_name, result)``
which dispatches to the right function.  ``--json`` bypasses this entirely.
"""
from __future__ import annotations

import json
from datetime import datetime
from typing import Any


def render(op_name: str, result: dict) -> str:
    """Dispatch to the per-op renderer; fallback = pretty JSON."""
    fn = _RENDERERS.get(op_name)
    if fn is None:
        return json.dumps(result, ensure_ascii=False, indent=2)
    lines = fn(result)
    return "\n".join(lines) if lines else ""


def _render_scan(r: dict) -> list[str]:
    out = []
    total = r.get("total_artifacts", 0)
    for root in r.get("roots", []):
        marker = "[OK]" if root.get("exists") else "[--]"
        out.append(f"\n{marker} {root['path']}")
        out.append(f"     artifacts={root.get('artifacts', 0)}  groups={root.get('groups', 0)}")
        if root.get("sample"):
            for a in root["sample"][:50]:
                out.append(f"     {a['group']}:{a['name']}:{a['version']}  {a['file']}  ({a['size']} B)")
            if root.get("truncated"):
                out.append(f"     ... and {root.get('remaining', 0)} more")
    out.append(f"\nTotal artifacts across all roots: {total}")
    return out


def _render_pending(r: dict) -> list[str]:
    out = [f"Checking pending deps for repo='{r.get('repo')}'"]
    items = r.get("items", [])
    out.append(f"  {len(items)} pending request(s)")
    for it in items:
        out.append(f"  - id={str(it.get('id', '?'))}  size={it.get('size', 0)}  mtime={it.get('mtime', '?')}")
    return out


def _render_debug(r: dict) -> list[str]:
    out = ["=== LGM DEBUG ===\n"]
    env = r.get("env", {})
    out.append(f"GRADLE_USER_HOME (env)   : {env.get('GRADLE_USER_HOME_env', '(not set)')}")
    out.append(f"GRADLE_USER_HOME (.env)  : {env.get('GRADLE_USER_HOME_dotenv', '(not set)')}")
    out.append(f"HOME                     : {env.get('HOME', '?')}")
    out.append("\n--- Gradle cache candidates ---")
    for root in r.get("cache_roots", []):
        marker = f"[{root.get('status', 'MISSING')}]"
        out.append(f"  {marker} {root['path']}  (artifacts={root.get('artifacts', 0)}, groups={root.get('groups', 0)})")
    mirror = r.get("mirror", {})
    out.append(f"\n--- Mirror connectivity: {mirror.get('base_url', '?')} ---")
    caps = mirror.get("capabilities", {})
    if "error" in caps:
        out.append(f"  FAILED: {caps}")
    else:
        out.append(f"  OK: {caps}")
    pending = mirror.get("pending", {})
    out.append(f"\n--- Pending deps for repo='{r.get('repo')}' ---")
    if "error" in pending:
        out.append(f"  FAILED: {pending}")
    else:
        items = pending.get("items", [])
        out.append(f"  {len(items)} pending")
        for it in items:
            out.append(f"  - {str(it.get('id', '?'))}  mtime={it.get('mtime', '?')}")
    responses = mirror.get("responses", {})
    out.append(f"\n--- Available responses for repo='{r.get('repo')}' ---")
    if "error" in responses:
        out.append(f"  FAILED: {responses}")
    else:
        items = responses.get("items", [])
        out.append(f"  {len(items)} available")
        for it in items:
            out.append(f"  - {str(it.get('id', '?'))}  size={it.get('size', 0)}")
    return out


def _render_respond(r: dict) -> list[str]:
    out = [f"Fetching pending requests for repo='{r.get('repo')}'..."]
    if r.get("message"):
        out.append(f"  {r['message']}")
        return out
    req_id = r.get("request_id", "?")
    out.append(f"  Request: {req_id}")
    if r.get("remaining"):
        out.append(f"  {r['remaining']} more request(s) in queue")
    manifest = r.get("manifest", {})
    out.append(f"  Manifest: v={manifest.get('version')} missing={len(manifest.get('missing', []))}")
    out.append(f"  Found={r.get('found', 0)}  NotFound={r.get('not_found', 0)}")
    for k in r.get("not_found_items", []):
        out.append(f"  [MISS] {k}")
    if r.get("skipped_unshipable"):
        out.append(f"  Filtered out {r['skipped_unshipable']} unshipable files")
    if r.get("added_parents"):
        out.append(f"  Added {r['added_parents']} parent pom(s) to complete the chain")
    if r.get("dry_run"):
        out.append("  --dry-run: would send the above. Stopping.")
        return out
    if r.get("encrypted_size"):
        out.append(f"  Sending {r['encrypted_size'] // 1024} KB...")
    resp = r.get("response", {})
    if "_error" in resp or "error" in resp:
        out.append(f"  FAILED: {resp}")
    else:
        out.append(f"  OK: {resp}")
    return out


def _render_apply(r: dict) -> list[str]:
    out = [f"Checking responses for repo='{r.get('repo')}'..."]
    if r.get("message"):
        out.append(f"  {r['message']}")
        return out
    resp_id = r.get("response_id", "?")
    out.append(f"  Response: {resp_id}")
    if r.get("remaining"):
        out.append(f"  {r['remaining']} more response(s) waiting")
    out.append(f"  Target cache: {r.get('target', '?')}")
    if r.get("layout"):
        out.append(f"  detected sender layout: {r['layout']}")
    out.append(f"  installed={r.get('installed', 0)}  skipped={r.get('skipped', 0)}  invalid={r.get('invalid', 0)}")
    if r.get("dry_run"):
        out.append(f"  --dry-run: would install {r.get('entry_count', 0)} entries.")
        for n in r.get("sample", [])[:10]:
            out.append(f"    {n}")
        return out
    if r.get("npm"):
        npm = r["npm"]
        out.append(f"  npm: seeding {npm.get('ok', 0) + npm.get('fail', 0)} tarball(s)...")
        out.append(f"  npm: cache add ok={npm.get('ok', 0)} fail={npm.get('fail', 0)}")
    if r.get("npm_lock"):
        out.append(f"  npm: wrote package-lock.json ({r['npm_lock'].get('rewritten', 0)} resolved URL(s) -> npmjs)")
        if "install_exit" in r["npm_lock"]:
            out.append(f"  npm: install exit={r['npm_lock']['install_exit']}")
    if r.get("yarn"):
        y = r["yarn"]
        out.append(f"  yarn: {y.get('count', 0)} corporate tarball(s) -> {y.get('mirror', '?')}")
        if "install_exit" in y:
            out.append(f"  yarn: install --offline exit={y['install_exit']}")
    ack = r.get("ack", {})
    if ack.get("error"):
        out.append(f"  ACK error: {ack['error']}")
    else:
        out.append("  ACK sent.")
    return out


def _render_request(r: dict) -> list[str]:
    out = [f"\n=== Resolving missing deps for {r.get('project', '?')} ==="]
    if r.get("message"):
        out.append(f"  {r['message']}")
        return out
    out.append(f"  init-script + stdout fallback: {r.get('raw_missing', 0)} candidate coord(s)")
    out.append(f"  gradle user home (reported) = {r.get('guh') or '(unknown)'}")
    out.append(f"  scanned cache: {r.get('cached_coords', 0)} fully satisfied coords")
    out.append(f"  after filter: {r.get('missing', 0)} truly missing coord(s)")
    if r.get("npm_missing"):
        out.append(f"  npm: {r['npm_missing']} corporate package(s) to request")
    out.append(f"  present: {r.get('present', 0)} file entries (deduped)")
    out.append(f"  manifest size: {r.get('manifest_size', 0):,} bytes (plaintext json)")
    if r.get("dry_run"):
        out.append("  --dry-run: not posting.")
        return out
    if r.get("posted"):
        resp = r.get("response", {})
        out.append(f"  OK: id={str(resp.get('id', '?'))} size={resp.get('size', 0):,} bytes")
        out.append("\n  On the work machine: `python lgm.py respond`")
    return out


def _render_fetch_poms(r: dict) -> list[str]:
    out = [f"\n=== Maven Central pom fetch ==="]
    out.append(f"  repo: {r.get('repo_url', '?')}")
    out.append(f"\n  TOTAL: fetched={r.get('fetched', 0)}  private/404={r.get('skipped_private', 0)}  failed={r.get('failed', 0)}")
    return out


def _render_publish(r: dict) -> list[str]:
    out = ["=== Публикация корпоративных артефактов ==="]
    if r.get("message"):
        out.append(f"  {r['message']}")
        return out
    out.append(f"  всего уникальных: {r.get('published', r.get('count', 0))} артефактов")
    if r.get("dry_run"):
        for art in r.get("sample", [])[:20]:
            out.append(f"    {art['group']}:{art['artifact']}:{art['version']}"
                       f"{'-' + art['classifier'] if art['classifier'] else ''}.{art['extension']}")
        return out
    resp = r.get("response", {})
    out.append(f"  ✓ публикация принята")
    out.append(f"    добавлено: {resp.get('added', 0)}")
    out.append(f"    уже было: {resp.get('existed', 0)}")
    out.append(f"    конфликты: {len(resp.get('conflicts', []))}")
    return out


def _render_status(r: dict) -> list[str]:
    out = ["=== Mirror Status ==="]
    caps = r.get("capabilities", {})
    if "error" in caps:
        out.append(f"  capabilities: FAILED ({caps.get('code')}: {caps.get('error')})")
    else:
        sync = caps.get("sync", {})
        out.append(f"  server: {caps.get('server', {}).get('name', '?')} v{caps.get('server', {}).get('version', '?')}")
        out.append(f"  protocol: v{sync.get('protocolVersion', '?')}")
        feats = sync.get("features", {})
        out.append(f"  features: v3={feats.get('v3')} uploadAndApply={feats.get('uploadAndApply')} exportDump={feats.get('exportDump')}")
    repos = r.get("repos", {})
    if "error" in repos:
        out.append(f"  repos: FAILED ({repos.get('code')}: {repos.get('error')})")
    else:
        out.append(f"  repos: {repos.get('repos', [])}  current={repos.get('current', '?')}")
    out.append(f"  role guess: {r.get('role_guess', '?')}")
    out.append(f"  base_url: {r.get('base_url', '?')}")
    return out


def _render_repos(r: dict) -> list[str]:
    out = ["Repos:"]
    for repo in r.get("repos", []):
        marker = " *" if repo == r.get("current") else "  "
        out.append(f"{marker} {repo}")
    out.append(f"current: {r.get('current', '?')}")
    return out


def _render_branches(r: dict) -> list[str]:
    out = [f"Branches for repo='{r.get('repo', '?')}'"]
    head = r.get("head", "")
    if head:
        out.append(f"  HEAD: {head[:12]}")
    refs = r.get("refs", {})
    for name, info in sorted(refs.items()):
        sha = info.get("sha", "?")[:12]
        updated = info.get("updated", "")
        is_head = info.get("is_head", False)
        marker = " *" if is_head else "  "
        line = f"{marker} {name:30s} {sha}  {updated}"
        div = info.get("divergence")
        if div == "ahead":
            line += f"  ahead {info.get('ahead', 0)}"
        elif div == "behind":
            line += f"  behind {info.get('behind', 0)}"
        elif div == "diverged":
            line += f"  ahead {info.get('ahead', 0)} / behind {info.get('behind', 0)}"
        elif div:
            line += f"  {div}"
        out.append(line)
    return out


def _render_send(r: dict) -> list[str]:
    out = [f"Sending bundle to repo='{r.get('repo')}'"]
    out.append(f"  branch: {r.get('branch', 'all')}")
    if r.get("created"):
        out.append("  mirror repo created (was not registered before this send)")
    out.append(f"  bundle size: {r.get('bundle_size', 0):,} bytes")
    if r.get("dry_run"):
        out.append("  --dry-run: bundle created but not sent.")
        return out
    resp = r.get("response", {})
    if "e" in resp:
        out.append(f"  OK (envelope response)")
    else:
        out.append(f"  Response: {resp}")
    return out


def _render_pull(r: dict) -> list[str]:
    out = [f"Pulling from repo='{r.get('repo')}'"]
    out.append(f"  branch: {r.get('branch', '?')}")
    out.append(f"  status: {r.get('status', '?')}")
    out.append(f"  head: {str(r.get('head', ''))[:12]}")
    if r.get("message"):
        out.append(f"  {r['message']}")
    if r.get("dry_run"):
        out.append(f"  --dry-run: dump_size={r.get('dump_size', 0):,} bytes")
        return out
    if r.get("bundle_size"):
        out.append(f"  bundle size: {r['bundle_size']:,} bytes")
    if r.get("fetch_exit") is not None:
        out.append(f"  git fetch exit={r['fetch_exit']}")
        if r.get("fetch_stderr"):
            out.append(f"  {r['fetch_stderr']}")
    return out


def _render_deps_request(r: dict) -> list[str]:
    out = [f"Posted manifest to repo='{r.get('repo')}'"]
    out.append(f"  id={str(r.get('id', '?'))}  size={r.get('size', 0):,} bytes")
    return out


def _render_vault_status(r: dict) -> list[str]:
    out = ["=== Vault Status ==="]
    out.append(f"  vault: {r.get('vault', '?')}")
    out.append(f"  projection: {r.get('projection', '?')}")
    out.append(f"  protected groups: {r.get('protectedGroups', [])}")
    stats = r.get("stats", {})
    out.append(f"  stats: {stats}")
    conflicts = r.get("conflicts", [])
    if conflicts:
        out.append(f"  conflicts: {len(conflicts)}")
    wanted = r.get("wanted", [])
    if wanted:
        out.append(f"  wanted: {len(wanted)} positions")
    return out


def _render_branch_delete(r: dict) -> list[str]:
    out = [f"Branch delete for repo='{r.get('repo')}'"]
    for b in r.get("deleted", []):
        out.append(f"  [deleted] {b}")
    for f in r.get("failed", []):
        out.append(f"  [FAILED ] {f.get('branch')}: {f.get('error')}")
    out.append(f"  {r.get('message', '')}")
    return out


def _render_prune(r: dict) -> list[str]:
    out = [f"Prune repo='{r.get('repo')}' (apply={r.get('apply')})"]
    out.append(f"  {r.get('message', '')}")
    for b in r.get("candidates", []):
        out.append(f"  [candidate] {b}")
    for b in r.get("pruned", []):
        out.append(f"  [pruned]    {b}")
    for b in r.get("protected", []):
        out.append(f"  [protected] {b}")
    return out


def _render_mr_list(r: dict) -> list[str]:
    out = [f"Open merge requests: {r.get('count', 0)}"]
    for m in r.get("items", []):
        out.append(
            f"  !{m.get('iid')}  {m.get('title', '')}  "
            f"({m.get('source_branch', '')} @ {m.get('updated_at', '')})"
        )
    for e in r.get("errors") or []:
        out.append(f"  [FAILED] repo='{e.get('repo', '?')}': {e.get('error', '?')}")
    return out


def _render_mr_send(r: dict) -> list[str]:
    out = [f"MR send repo='{r.get('repo')}'"]
    for t in r.get("sent") or []:
        iid = f" !{t['iid']}" if t.get("iid") else ""
        out.append(f"  [sent]    {t.get('branch')}{iid} ({(t.get('tip') or '')[:8]})")
    for t in r.get("skipped") or []:
        iid = f" !{t['iid']}" if t.get("iid") else ""
        out.append(f"  [skipped] {t.get('branch')}{iid} (already on mirror)")
    for b in r.get("blocked") or []:
        iid = f" !{b['iid']}" if b.get("iid") else ""
        out.append(f"  [blocked] {b.get('branch')}{iid}: {b.get('reason')}")
    if r.get("created"):
        out.append("  mirror repo created (was not registered before this send)")
    if r.get("message"):
        out.append(f"  {r['message']}")
    send = r.get("send") or {}
    if send:
        out.append(f"  bundle size: {send.get('bundle_size', 0):,} bytes")
        if send.get("excluded_bases"):
            out.append(f"  excluded bases: {send['excluded_bases']} (already on mirror)")
        resp = send.get("response", {})
        if "e" in resp:
            out.append("  OK (envelope response)")
        elif resp:
            out.append(f"  Response: {resp}")
    return out


def _render_mr_replies_send(r: dict) -> list[str]:
    if not r.get("success"):
        return [f"mr_replies_send FAILED: {r.get('error', '?')}"]
    if r.get("check_only"):
        pre = r.get("precheck") or {}
        out = [f"Replies check for MR !{r.get('iid', '?')} (repo={r.get('repo', '?')}): "
               f"precheck={pre.get('status', '?')} — nothing uploaded"]
        for w in r.get("anchor_warnings") or []:
            out.append(f"  [warning] {w}")
        return out
    out = [
        f"Replies for {r.get('path')} uploaded to postbox "
        f"({r.get('size', 0)} bytes, repo={r.get('repo')})",
        "The work machine will post them to GitLab automatically.",
    ]
    for w in r.get("anchor_warnings") or []:
        out.append(f"  [warning] {w}")
    return out


def _render_mr_replies_status(r: dict) -> list[str]:
    statuses = r.get("statuses") or []
    if not statuses:
        return ["No publish reports yet: the work PC has not posted these replies to GitLab."]
    out = []
    for s in statuses:
        if "error" in s:
            out.append(f"{s['path']}: {s['error']}")
            continue
        if not s.get("parsed"):
            out.append(f"{s['path']}: unrecognized status document "
                       f"(no lgm-replies-status v1 marker)")
            continue
        not_anchored = s.get("not_anchored")
        out.append(
            f"{s['path']}: posted={s.get('posted')} "
            f"duplicates={s.get('duplicates')} skipped={s.get('skipped')} "
            f"failed={s.get('failed')} "
            f"not_anchored={'?' if not_anchored is None else not_anchored} "
            f"at={s.get('at') or '?'}"
        )
    return out


def _render_mr_replies_push(r: dict) -> list[str]:
    mode = " (dry-run)" if r.get("dry_run") else ""
    out = [f"MR replies push repo='{r.get('repo', '?')}'{mode}"]
    totals = r.get("totals") or {}
    if totals:
        out.append(
            f"  totals: posted={totals.get('posted', 0)} "
            f"duplicates={totals.get('duplicates', 0)} "
            f"skipped={totals.get('skipped', 0)} "
            f"failed={totals.get('failed', 0)} "
            f"not_anchored={totals.get('not_anchored', 0)}"
        )
    for rep in r.get("reports") or []:
        ack = " [acked]" if rep.get("acked") else ""
        out.append(
            f"  !{rep.get('iid')} {rep.get('path')}: "
            f"posted={rep.get('posted', 0)} duplicates={rep.get('duplicates', 0)} "
            f"skipped={rep.get('skipped', 0)} failed={rep.get('failed', 0)} "
            f"parse_errors={rep.get('parse_errors', 0)}{ack}"
        )
        sup = rep.get("ack_superseded") or []
        if sup:
            out.append(f"    superseded reply file(s) acked: {', '.join(sup)}")
        for rline in rep.get("replies") or []:
            out.append(f"    [{rline.get('outcome', '?')}] {rline.get('label')}: {rline.get('note', '')}")
        for n in rep.get("not_anchored") or []:
            out.append(f"    [not-anchored] {n}")
    if r.get("message"):
        out.append(f"  {r['message']}")
    return out


def _render_preview_pull(r: dict) -> list[str]:
    out = [f"Preview pull repo='{r.get('repo', '?')}'"]
    out.append(f"  since: {str(r.get('since') or '(none)')[:12]}")
    head = str(r.get("remoteHead") or "")
    out.append(f"  mirror head: {head[:12] or '(none)'}")
    verdict = "YES" if r.get("hasUpdates") else "no"
    out.append(f"  incoming commits: {verdict} ({r.get('reason', '?')})")
    return out


def _render_has_commits(r: dict) -> list[str]:
    known = r.get("known") or []
    head = str(r.get("head") or "")
    out = [f"Mirror commit check repo='{r.get('repo', '?')}'"]
    out.append(f"  mirror head: {head[:12] or '(none)'}")
    out.append(f"  known commits: {len(known)}")
    for sha in known:
        out.append(f"    {str(sha)[:12]}")
    return out


def _render_mr_notes(r: dict) -> list[str]:
    out = [f"MR notes repo='{r.get('repo', '?')}'"]
    notes = r.get("notes") or []
    out.append(f"  {r.get('count', len(notes))} note file(s)")
    for n in notes:
        state = " (closed)" if n.get("state") == "closed" else ""
        out.append(f"\n=== !{n.get('iid')}{state} — {n.get('path', '?')} ===")
        if n.get("error"):
            out.append(f"  {n['error']}")
        else:
            out.append((n.get("markdown") or "").rstrip())
    return out


def _render_guide(r: dict) -> list[str]:
    return [r.get("guide", "")]


def _render_update(r: dict) -> list[str]:
    out = [f"CLI tools updated to {r.get('version', '?')}"]
    out.append(f"  files updated: {r.get('files_updated', 0)}")
    out.append(f"  tools root: {r.get('tools_root', '?')}")
    if r.get("message"):
        out.append(f"  {r['message']}")
    return out


def _render_chat_send(r: dict) -> list[str]:
    return [f"Chat message sent (id={r.get('id', '?')})."]


def _render_chat_list(r: dict) -> list[str]:
    messages = r.get("messages") or []
    out = [f"Chat messages: {r.get('count', len(messages))}"]
    for m in messages:
        ts = m.get("ts") or 0
        when = datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S") if ts else "?"
        out.append(f"  [{when}] {m.get('text', '')}")
    return out


def _render_file_send(r: dict) -> list[str]:
    out = [f"File sent: {r.get('file', '?')}"]
    out.append(f"  id={r.get('id', '?')}  size={r.get('size', 0):,} bytes")
    return out


def _render_file_list(r: dict) -> list[str]:
    files = r.get("files") or []
    out = [f"Postbox files for repo='{r.get('repo', '?')}': {r.get('count', len(files))}"]
    for f in files:
        out.append(f"  - id={str(f.get('id', '?'))}  size={f.get('size', 0):,}  {f.get('path', '?')}")
    return out


def _render_file_get(r: dict) -> list[str]:
    out = [f"File downloaded: id={r.get('id', '?')}  size={r.get('size', 0):,} bytes"]
    if r.get("saved"):
        out.append(f"  saved to: {r['saved']}")
    elif r.get("message"):
        out.append(f"  {r['message']}")
    return out


def _render_file_ack(r: dict) -> list[str]:
    return [f"Postbox item deleted: repo='{r.get('repo', '?')}' id={r.get('id', '?')}"]


def _render_config_get(r: dict) -> list[str]:
    if r.get("section"):
        value = r.get("value")
        body = json.dumps(value, ensure_ascii=False, indent=2) if isinstance(value, (dict, list)) else str(value)
        return [f"Settings section '{r['section']}':", body]
    out = ["Server settings:"]
    for section, value in sorted((r.get("settings") or {}).items()):
        out.append(f"  {section}: {json.dumps(value, ensure_ascii=False)}")
    return out


def _render_config_set(r: dict) -> list[str]:
    return [f"Setting updated: {r.get('section', '?')}.{r.get('key', '?')} = {r.get('value', '?')}"]


def _render_mr_projects_scan(r: dict) -> list[str]:
    out = [f"MR projects scan: {r.get('scanned_dirs', 0)} root(s)"]
    for root in r.get("roots") or []:
        out.append(f"  scanned: {root}")
    out.append(f"  added={len(r.get('added') or [])}  updated={len(r.get('updated') or [])}  total={r.get('total', 0)}")
    for repo in r.get("added") or []:
        out.append(f"  [added]   {repo}")
    for repo in r.get("updated") or []:
        out.append(f"  [updated] {repo}")
    return out


def _render_mr_projects_list(r: dict) -> list[str]:
    projects = r.get("projects") or []
    out = [f"Registered MR-sync projects: {r.get('count', len(projects))}"]
    for p in projects:
        marker = "[OK]" if p.get("exists") else "[--]"
        out.append(f"  {marker} {p.get('repo', '?')}  {p.get('path', '?')}  ({p.get('gitlab', '?')})")
    return out


def _render_mr_pending(r: dict) -> list[str]:
    items = r.get("items") or []
    out = [f"MR pipeline repo='{r.get('repo', '?')}': {r.get('count', len(items))} MR(s)"]
    for it in items:
        notes = it.get("notes")
        if notes:
            age = notes.get("age_seconds", 0)
            stamp = notes.get("updated_at")
            if stamp:
                notes_txt = (f"notes updated_at {stamp} "
                             f"({notes.get('updated_age_seconds')}s ago); "
                             f"file {age}s old (mtime {notes.get('mtime')})")
            elif notes.get("updated_error"):
                notes_txt = (f"notes updated_at unknown "
                             f"({notes.get('updated_error')}); "
                             f"file {age}s old (mtime {notes.get('mtime')})")
            else:
                notes_txt = f"notes {age}s old (mtime {notes.get('mtime')})"
        else:
            notes_txt = "notes absent (expired or never transferred)"
        if it.get("replies_pending"):
            ids = ", ".join(str(i) for i in it.get("reply_ids") or [])
            replies_txt = f"replies pending ({ids})"
        else:
            replies_txt = "no replies pending"
        lp = it.get("last_publish")
        if lp is None:
            publish_txt = "never published"
        elif lp.get("parsed"):
            not_anchored = lp.get("not_anchored")
            publish_txt = (f"last publish posted={lp.get('posted')} "
                           f"duplicates={lp.get('duplicates')} "
                           f"skipped={lp.get('skipped')} "
                           f"failed={lp.get('failed')} "
                           f"not_anchored={'?' if not_anchored is None else not_anchored} "
                           f"at {lp.get('at') or '?'}")
        else:
            publish_txt = (f"last publish unreadable "
                           f"({lp.get('error', 'no v1 marker')})")
        if notes and isinstance(lp, dict) and lp.get("parsed") and lp.get("posted") is not None:
            seen = notes.get("lgm_markers", 0)
            publish_txt += (f"; notes carry {seen} lgm markers "
                            f"(last publish posted={lp.get('posted')})")
            if seen < lp.get("posted"):
                publish_txt += " — notes predate the push, re-sync to verify"
        out.append(f"  !{it.get('iid')} state={it.get('state')} "
                   f"({it.get('state_evidence')})")
        out.append(f"    {notes_txt}; {replies_txt}; {publish_txt}")
    return out


def _render_mr_review_receipts(r: dict) -> list[str]:
    action = r.get("action") or "?"
    mode = " (dry-run)" if r.get("dry_run") else ""
    out = [f"MR review receipts repo='{r.get('repo', '?')}' action={action}{mode}"]
    if action == "import":
        artifacts = r.get("artifacts") or []
        for art in artifacts:
            ack = " [acked]" if art.get("acked") else ""
            out.append(
                f"  !{art.get('iid')} batch {art.get('batch_id')} "
                f"attempt {art.get('attempt')}: imported={art.get('imported', 0)} "
                f"skipped={art.get('skipped', 0)} "
                f"delivery_updates={art.get('delivery_updates', 0)}{ack}")
            out.append(f"    {art.get('path', '?')}")
            for e in art.get("errors") or []:
                out.append(f"    [FAILED] {e}")
        if not artifacts:
            out.append("  no receipt artifacts in the postbox")
        return out
    for p in r.get("uploaded") or []:
        out.append(f"  [uploaded] {p}")
    for a in r.get("acked") or []:
        out.append(f"  [acked] batch item {a}")
    for p in r.get("pending") or []:
        out.append(f"  [pending] would {p.get('would', '?')} batch "
                   f"{p.get('batch_id', '?')} attempt {p.get('attempt', '?')}")
    for f in r.get("failed") or []:
        out.append(f"  [FAILED] batch {f.get('batch_id') or f.get('journal', '?')} "
                   f"attempt {f.get('attempt', '?')}: {f.get('error', '')}")
    if not any(r.get(k) for k in ("uploaded", "acked", "pending", "failed")):
        out.append("  nothing to hand off")
    return out


def _render_mr_sync_all(r: dict) -> list[str]:
    s = r.get("summary") or {}
    out = ["MR sync all:"]
    if r.get("dry_run"):
        out.append("  --dry-run: nothing transferred.")
    if r.get("notes_only"):
        out.append("  --notes-only: branch transfer skipped.")
    out.append(f"  repos={s.get('repos', 0)}  open MRs={s.get('mrs', 0)}")
    out.append(f"  branches: sent={s.get('branches_sent', 0)}  skipped={s.get('branches_skipped', 0)}  warnings={s.get('branch_warnings', 0)}")
    out.append(f"  notes: uploaded={s.get('notes_uploaded', 0)}  unchanged={s.get('notes_unchanged', 0)}  closed marked={s.get('closed_marked', 0)}")
    for repo in r.get("repos") or []:
        out.append(f"  {repo.get('repo', '?')} ({repo.get('gitlab', '?')}): open MRs={repo.get('open_mrs', 0)}")
        for w in repo.get("branch_warnings") or []:
            out.append(f"    [warning] {w}")
        for b in repo.get("blocked") or []:
            out.append(f"    [blocked] !{b.get('iid')} {b.get('branch')}: {b.get('reason')}")
        for m in repo.get("mrs") or []:
            notes = m.get("notes", "")
            if notes:
                out.append(f"    !{m.get('iid')} {m.get('title', '')} — notes: {notes}")
    for e in s.get("errors") or []:
        out.append(f"  [FAILED] {e}")
    return out


def _flat(value: Any) -> str:
    if isinstance(value, dict):
        return " ".join(f"{k}={v}" for k, v in sorted(value.items()))
    return str(value)


def _bounded_page_lines(page: dict, indent: str = "  ") -> list[str]:
    out: list[str] = []
    totals = page.get("totals")
    if isinstance(totals, dict) and totals:
        out.append(f"{indent}totals: " + "  ".join(f"{k}={v}" for k, v in sorted(totals.items())))
    span = page.get("range") or page.get("shown")
    if isinstance(span, dict) and span:
        out.append(f"{indent}range: " + "  ".join(f"{k}={v}" for k, v in sorted(span.items())))
    out.append(f"{indent}complete={bool(page.get('complete'))}")
    if page.get("next_cursor"):
        out.append(f"{indent}next_cursor={page['next_cursor']}")
    for lim in page.get("limitations") or []:
        out.append(f"{indent}[limitation] {lim}")
    return out


def _chunk_lines(chunk: dict, indent: str) -> list[str]:
    keys = sorted(k for k in chunk if k not in ("content", "encoding", "continued"))
    head = " ".join(f"{k}={chunk[k]}" for k in keys if chunk[k] not in (None, ""))
    flags = []
    encoding = chunk.get("encoding") or "utf-8"
    if encoding != "utf-8":
        flags.append(encoding)
    if chunk.get("continued"):
        flags.append("continued")
    flag_txt = f" <{','.join(flags)}>" if flags else ""
    out = [f"{indent}[chunk{' ' + head if head else ''}]{flag_txt}"]
    for line in (chunk.get("content") or "").splitlines():
        out.append(f"{indent}| {line}")
    return out


def _render_mr_review_prepare(r: dict) -> list[str]:
    out = [f"MR review prepare repo='{r.get('repo', '?')}' !{r.get('iid', '?')}: "
           f"{r.get('status', '?')}"]
    if r.get("snapshot_id"):
        out.append(f"  snapshot {r['snapshot_id']}")
    if r.get("code_id"):
        out.append(f"  code {r['code_id']}")
    if r.get("version_fingerprint"):
        out.append(f"  fingerprint {r['version_fingerprint']}")
    bundle = r.get("bundle") or {}
    if bundle:
        out.append(f"  bundle {bundle.get('path', '?')} size={bundle.get('size', 0)} "
                   f"action={bundle.get('action', '?')}")
        out.append(f"    sha256 {bundle.get('sha256', '?')}")
    if r.get("manifest_path"):
        out.append(f"  manifest {r['manifest_path']}")
    for lim in r.get("limitations") or []:
        out.append(f"  [limitation] {lim}")
    return out


def _render_mr_review_open(r: dict) -> list[str]:
    out = [f"MR review open repo='{r.get('repo', '?')}' !{r.get('iid', '?')}: "
           f"{r.get('status', '?')}"]
    if r.get("code"):
        out.append(f"  [{r['code']}] {r.get('message', '')}")
    if r.get("snapshot_id"):
        out.append(f"  snapshot {r['snapshot_id']}")
    if r.get("code_id"):
        out.append(f"  code {r['code_id']}")
    if r.get("version_fingerprint"):
        out.append(f"  fingerprint {r['version_fingerprint']}")
    identity = r.get("identity") or {}
    if identity:
        draft = " draft" if identity.get("draft") else ""
        out.append(f"  {identity.get('title', '?')} [{identity.get('state', '?')}{draft}]")
        out.append(f"    {identity.get('source_branch', '?')} -> {identity.get('target_branch', '?')}")
        out.append(f"    source {identity.get('source_sha', '?')}")
        out.append(f"    target {identity.get('target_tip_sha', '?')}")
        if identity.get("mr_url"):
            out.append(f"    {identity['mr_url']}")
    summary = r.get("summary") or {}
    if summary:
        out.append(f"  discussions={summary.get('discussions', 0)} "
                   f"notes={summary.get('notes', 0)} "
                   f"bundle={summary.get('bundle_size', 0)}B "
                   f"downloaded={summary.get('bundle_downloaded')}")
        if summary.get("previous_snapshot_id"):
            out.append(f"  previous snapshot {summary['previous_snapshot_id']}")
    store = r.get("store") or {}
    if store:
        out.append(f"  store {store.get('repo_key', '?')} @ {store.get('root', '?')}")
    inv = r.get("inventory") or {}
    if inv:
        out.append("  inventory:")
        if inv.get("rename_detection_limited"):
            out.append("    [limitation] rename detection limited")
        for entry in inv.get("entries") or []:
            tags = ",".join(t.get("tag", "?") for t in entry.get("tags") or [])
            rename = entry.get("rename") or {}
            extra = f" rename={rename.get('kind', '?')}/{rename.get('similarity', '?')}%" if rename else ""
            out.append(f"    {entry.get('status', '?')} {entry.get('path', '?')} "
                       f"+{entry.get('lines_added', 0)}/-{entry.get('lines_deleted', 0)}"
                       f"{extra}{' <' + tags + '>' if tags else ''}")
        for frag in inv.get("path_fragments") or []:
            out.append(f"    [path-fragment] entry#{frag.get('entry_index')} "
                       f"{frag.get('field')} part={frag.get('part')}: {frag.get('text')}")
        out.extend(_bounded_page_lines(inv, indent="    "))
    for lim in r.get("limitations") or []:
        out.append(f"  [limitation] {lim}")
    return out


def _render_mr_review_read(r: dict) -> list[str]:
    view = r.get("view", "?")
    out = [f"MR review read repo='{r.get('repo', '?')}' "
           f"snapshot={r.get('snapshot_id', '?')} view={view}"]
    if view == "diff":
        out.append(f"  base={r.get('base_sha', '?')} head={r.get('head_sha', '?')} "
                   f"context_lines={r.get('context_lines', '?')}")
        for chunk in r.get("chunks") or []:
            out.extend(_chunk_lines(chunk, "  "))
        for p in r.get("missing_paths") or []:
            out.append(f"  [missing] {p}")
    elif view == "file":
        out.append(f"  side={r.get('side', '?')}")
        for item in r.get("files") or []:
            flags = []
            if item.get("missing"):
                flags.append("missing")
            if item.get("path_truncated"):
                flags.append("path-truncated")
            oid = item.get("oid") or "-"
            size = item.get("size")
            mode = item.get("mode") or "-"
            out.append(f"  {item.get('path', '?')} kind={item.get('kind', '?')} "
                       f"oid={oid} size={'-' if size is None else size} mode={mode}"
                       f"{' <' + ','.join(flags) + '>' if flags else ''}")
            if item.get("limitation"):
                out.append(f"    [limitation] {item['limitation']}")
            for chunk in item.get("chunks") or []:
                out.extend(_chunk_lines(chunk, "    "))
    else:
        for note in r.get("notes") or []:
            pos = note.get("position") or {}
            where = ""
            if pos:
                where = f" @ {pos.get('new_path') or pos.get('old_path') or '?'}"
            out.append(f"  note {note.get('note_id', '?')} "
                       f"(discussion {note.get('discussion_id', '?')}) "
                       f"by {note.get('author', '?')} "
                       f"resolved={note.get('resolved')}{where}")
            for line in (note.get("body") or "").splitlines():
                out.append(f"    | {line}")
        for frag in r.get("note_fragments") or []:
            out.append(f"  [note-fragment] discussion#{frag.get('discussion_index')} "
                       f"note#{frag.get('note_index')} part={frag.get('part')}")
            for line in (frag.get("text") or "").splitlines():
                out.append(f"    | {line}")
    out.extend(_bounded_page_lines(r))
    return out


def _render_mr_review_search(r: dict) -> list[str]:
    sides = r.get("sides") or ([r["side"]] if r.get("side") else [])
    out = [f"MR review search repo='{r.get('repo', '?')}' "
           f"snapshot={r.get('snapshot_id', '?')}: query={r.get('query', '')!r} "
           f"scope={r.get('scope', '?')} sides={','.join(sides) or '?'}"]
    for hit in r.get("hits") or []:
        out.append(f"  {hit.get('path', '?')}:{hit.get('line', '?')}:"
                   f"{hit.get('column', '?')} ({hit.get('side', '?')}) "
                   f"{hit.get('excerpt', '')}"
                   + (" [truncated]" if hit.get("truncated") else ""))
    for cand in r.get("candidates") or []:
        out.append(f"  [candidate] {_flat(cand)}")
    out.extend(_bounded_page_lines(r))
    return out


def _render_mr_review_context(r: dict) -> list[str]:
    out = [f"MR review context {r.get('path', '?')} ({r.get('side', '?')} side) "
           f"blob={r.get('blob', '?')} method={r.get('method', '?')}"]
    for line in r.get("lines") or []:
        cont = " <continued>" if line.get("continued") else ""
        out.append(f"  {line.get('line', '?')} | {line.get('text', '')}{cont}")
    for cand in r.get("candidates") or []:
        out.append(f"  [candidate] {_flat(cand)}")
    if "semantic_complete" in r:
        out.append(f"  semantic_complete={r.get('semantic_complete')} "
                   f"complete={bool(r.get('complete'))}")
    for lim in r.get("limitations") or []:
        out.append(f"  [limitation] {lim}")
    return out


def _render_mr_review_progress(r: dict) -> list[str]:
    status = r.get("status", "?")
    out = [f"MR review progress repo='{r.get('repo', '?')}' !{r.get('iid', '?')}: "
           f"{status}"]
    if status == "marked":
        out.append(f"  {r.get('path', '?')} -> {r.get('state', '?')} "
                   f"(revision {r.get('revision', '?')}, "
                   f"snapshot {r.get('snapshot_id', '?')})")
        return out
    snap = r.get("snapshot") or {}
    if snap:
        out.append(f"  snapshot {r.get('snapshot_id', '?')} "
                   f"code={snap.get('code_id', '?')} current={snap.get('is_current')}")
    elif r.get("snapshot_id"):
        out.append(f"  snapshot {r['snapshot_id']}")
    out.append(f"  total={r.get('total', 0)} offset={r.get('offset', 0)} "
               f"limit={r.get('limit', 0)} next_cursor={r.get('next_cursor')}")
    totals = r.get("totals") or {}
    if totals:
        out.append("  totals: " + "  ".join(f"{k}={v}" for k, v in sorted(totals.items())))
    for unit in r.get("units") or []:
        carry = " [carryover]" if unit.get("carryover") else ""
        absent = "" if unit.get("in_inventory", True) else " [not-in-inventory]"
        out.append(f"  {str(unit.get('state', '?')):<14} {unit.get('path', '?')} "
                   f"rev={unit.get('revision', 0)}{carry}{absent}")
        if unit.get("reason"):
            out.append(f"      reason: {unit['reason']}")
    return out


def _render_mr_review_refresh(r: dict) -> list[str]:
    out = [f"MR review refresh repo='{r.get('repo', '?')}' !{r.get('iid', '?')}: "
           f"{r.get('status', '?')}"]
    out.append(f"  previous {r.get('previous_snapshot_id') or '-'} -> "
               f"current {r.get('current_snapshot_id') or '-'}")
    if r.get("previous_code_id") or r.get("current_code_id"):
        out.append(f"  code {r.get('previous_code_id') or '-'} -> "
                   f"{r.get('current_code_id') or '-'} "
                   f"(changed={bool(r.get('code_changed'))})")
    meta = r.get("metadata") or {}
    if meta.get("changed_fields"):
        out.append(f"  changed fields: {', '.join(meta['changed_fields'])}")
    prog = r.get("progress") or {}
    if prog:
        out.append("  progress: " + "  ".join(f"{k}={v}" for k, v in sorted(prog.items())))
    findings = r.get("findings") or {}
    if findings:
        out.append("  findings: " + "  ".join(f"{k}={v}" for k, v in sorted(findings.items())))
    return out


def _render_mr_review_materialize(r: dict) -> list[str]:
    out = [f"MR review environment action={r.get('action', '?')}: "
           f"{r.get('status', '?')}"]
    if r.get("environment_id"):
        out.append(f"  environment {r['environment_id']} ({r.get('mode', '?')})")
    if r.get("path"):
        out.append(f"  path {r['path']}")
    if r.get("sha"):
        out.append(f"  sha {r['sha']}")
    if r.get("purpose"):
        out.append(f"  purpose {r['purpose']}")
    if r.get("tracked_diff_digest"):
        out.append(f"  tracked-diff digest {r['tracked_diff_digest']}")
    for key in ("untracked", "ignored"):
        summary = r.get(key) or {}
        if summary:
            paths = summary.get("paths") or []
            shown = ", ".join(str(p) for p in paths[:10])
            if len(paths) > 10:
                shown += ", ..."
            out.append(f"  {key}: {summary.get('count', len(paths))}"
                       + (f" ({shown})" if shown else ""))
    for lim in r.get("limitations") or []:
        out.append(f"  [limitation] {lim}")
    return out


def _render_mr_review_findings(r: dict) -> list[str]:
    status = r.get("status", "?")
    out = [f"MR review findings: {status}"]
    if status == "saved":
        out.append(f"  finding {r.get('finding_id', '?')} "
                   f"revision {r.get('revision', '?')}")
        return out
    if status == "created":
        batch = r.get("batch") or {}
        out.append(f"  batch {r.get('batch_id', '?')} "
                   f"items={len(batch.get('items') or [])}")
        out.append(f"  artifact {r.get('artifact_path', '?')}")
        out.append(f"  file {r.get('path', '?')}")
        return out
    if r.get("repo") or r.get("iid") is not None:
        out.append(f"  identity repo='{r.get('repo', '?')}' iid={r.get('iid', '?')} "
                   f"project={r.get('target_project_id', '?')}")
    out.append(f"  total={r.get('total', 0)} offset={r.get('offset', 0)} "
               f"limit={r.get('limit', 0)} next_cursor={r.get('next_cursor')}")
    for finding in r.get("findings") or []:
        out.append(f"  {finding.get('finding_id', '?')} "
                   f"revisions={len(finding.get('revisions') or [])}")
    return out


_RENDERERS = {
    "scan": _render_scan,
    "pending": _render_pending,
    "debug": _render_debug,
    "respond": _render_respond,
    "apply": _render_apply,
    "request": _render_request,
    "fetch-poms": _render_fetch_poms,
    "publish": _render_publish,
    "status": _render_status,
    "repos": _render_repos,
    "branches": _render_branches,
    "send": _render_send,
    "pull": _render_pull,
    "preview_pull": _render_preview_pull,
    "has_commits": _render_has_commits,
    "deps_request": _render_deps_request,
    "vault_status": _render_vault_status,
    "branch_delete": _render_branch_delete,
    "prune": _render_prune,
    "mr_list": _render_mr_list,
    "mr_send": _render_mr_send,
    "mr_notes": _render_mr_notes,
    "mr_replies_send": _render_mr_replies_send,
    "mr_replies_status": _render_mr_replies_status,
    "mr_replies_push": _render_mr_replies_push,
    "mr_pending": _render_mr_pending,
    "mr_review_receipts": _render_mr_review_receipts,
    "mr_review_prepare": _render_mr_review_prepare,
    "mr_review_open": _render_mr_review_open,
    "mr_review_read": _render_mr_review_read,
    "mr_review_search": _render_mr_review_search,
    "mr_review_context": _render_mr_review_context,
    "mr_review_progress": _render_mr_review_progress,
    "mr_review_refresh": _render_mr_review_refresh,
    "mr_review_materialize": _render_mr_review_materialize,
    "mr_review_findings": _render_mr_review_findings,
    "guide": _render_guide,
    "update": _render_update,
    "chat_send": _render_chat_send,
    "chat_list": _render_chat_list,
    "file_send": _render_file_send,
    "file_list": _render_file_list,
    "file_get": _render_file_get,
    "file_ack": _render_file_ack,
    "config_get": _render_config_get,
    "config_set": _render_config_set,
    "mr_projects_scan": _render_mr_projects_scan,
    "mr_projects_list": _render_mr_projects_list,
    "mr_sync_all": _render_mr_sync_all,
}
