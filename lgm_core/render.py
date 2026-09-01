"""Human-readable rendering for op results.

Each ``render_<opname>`` takes the dict returned by ``Op.run`` and produces
a list of lines for terminal output.  The CLI calls ``render(op_name, result)``
which dispatches to the right function.  ``--json`` bypasses this entirely.
"""
from __future__ import annotations

import json
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
        out.append(f"  - id={str(it.get('id', '?'))[:12]}  size={it.get('size', 0)}  mtime={it.get('mtime', '?')}")
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
            out.append(f"  - {str(it.get('id', '?'))[:12]}  mtime={it.get('mtime', '?')}")
    responses = mirror.get("responses", {})
    out.append(f"\n--- Available responses for repo='{r.get('repo')}' ---")
    if "error" in responses:
        out.append(f"  FAILED: {responses}")
    else:
        items = responses.get("items", [])
        out.append(f"  {len(items)} available")
        for it in items:
            out.append(f"  - {str(it.get('id', '?'))[:12]}  size={it.get('size', 0)}")
    return out


def _render_respond(r: dict) -> list[str]:
    out = [f"Fetching pending requests for repo='{r.get('repo')}'..."]
    if r.get("message"):
        out.append(f"  {r['message']}")
        return out
    req_id = r.get("request_id", "?")
    out.append(f"  Request: {str(req_id)[:12]}")
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
    out.append(f"  Response: {str(resp_id)[:12]}")
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
        out.append(f"  OK: id={str(resp.get('id', '?'))[:12]} size={resp.get('size', 0):,} bytes")
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
        out.append(f"{marker} {name:30s} {sha}  {updated}")
    return out


def _render_send(r: dict) -> list[str]:
    out = [f"Sending bundle to repo='{r.get('repo')}'"]
    out.append(f"  branch: {r.get('branch', 'all')}")
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
    out.append(f"  id={str(r.get('id', '?'))[:12]}  size={r.get('size', 0):,} bytes")
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
    return out


def _render_mr_send(r: dict) -> list[str]:
    out = [f"MR send repo='{r.get('repo')}'"]
    if r.get("iid"):
        out.append(f"  MR: !{r.get('iid')}")
    out.append(f"  source branch: {r.get('source_branch', '?')}")
    out.append(f"  project: {r.get('project', '?')}")
    send = r.get("send", {})
    out.append(f"  bundle size: {send.get('bundle_size', 0):,} bytes")
    resp = send.get("response", {})
    if "e" in resp:
        out.append("  OK (envelope response)")
    elif resp:
        out.append(f"  Response: {resp}")
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
    "deps_request": _render_deps_request,
    "vault_status": _render_vault_status,
    "branch_delete": _render_branch_delete,
    "prune": _render_prune,
    "mr_list": _render_mr_list,
    "mr_send": _render_mr_send,
}
