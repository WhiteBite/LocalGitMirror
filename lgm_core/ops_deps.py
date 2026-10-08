from __future__ import annotations

import io
import json
import os
import subprocess
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .config import cfg
from .client import LgmError
from .crypto import encrypt_bundle, decrypt_bundle
from .op_models import Ctx, _client, _repo_arg
from .deps_scanner import (
    gradle_candidate_roots, scan_cache, maven_local_root, scan_maven_local,
    maven_local_relpath, _classify_artifact, _pick_shipable, _pom_packaging,
    _expand_parent_pom_closure, _read_root_project_name, _detect_java_home,
    _run_gradle_init, _ensure_mavenlocal_init_script,
    _maven_local_jars_without_poms, _missing_parent_poms, _fetch_pom_batch,
    _rewrite_lock_to_npmjs, _yarn_offline_mirror, _build_yarn_mirror,
    _set_global_yarn_mirror,
)

# npm helpers — same import the original lgm.py uses.
from _lgm_npm import (  # type: ignore
    npm_cache_dir, npm_offline_mirror,
    npm_find_tarball, _detect_npm_missing, _npm_mirror_rel,
)


# ── Existing commands (converted to return dicts) ───────────────────────────

def op_scan(ctx: Ctx, args: dict) -> dict:
    """Show what's in the local gradle cache."""
    gradle_home = args.get("gradle_home", "")
    group_filter = args.get("filter", "")
    verbose = args.get("verbose", False)
    roots = [Path(gradle_home)] if gradle_home else gradle_candidate_roots()
    root_results = []
    total = 0
    for root in roots:
        exists = root.is_dir()
        arts = scan_cache(root, group_filter) if exists else []
        groups = len({a["group"] for a in arts})
        entry = {
            "path": str(root),
            "exists": exists,
            "artifacts": len(arts),
            "groups": groups,
        }
        if verbose and arts:
            entry["sample"] = arts[:50]
            entry["truncated"] = len(arts) > 50
            if len(arts) > 50:
                entry["remaining"] = len(arts) - 50
        root_results.append(entry)
        total += len(arts)
    return {"roots": root_results, "total_artifacts": total}


def op_pending(ctx: Ctx, args: dict) -> dict:
    """List pending dep requests on the Mirror server."""
    c = _client(ctx)
    repo = _repo_arg(args)
    r = c.deps_pending(repo)
    items = r.get("items", [])
    return {"repo": repo, "items": items, "count": len(items)}


def op_debug(ctx: Ctx, args: dict) -> dict:
    """Full diagnostics: env vars, cache roots, mirror connectivity."""
    c = _client(ctx)
    repo = _repo_arg(args)
    env_info = {
        "GRADLE_USER_HOME_env": os.environ.get("GRADLE_USER_HOME", "(not set)"),
        "GRADLE_USER_HOME_dotenv": cfg("GRADLE_USER_HOME") or "(not set)",
        "HOME": str(Path.home()),
    }
    cache_roots = []
    for root in gradle_candidate_roots():
        exists = root.is_dir()
        arts = scan_cache(root) if exists else []
        groups = len({a["group"] for a in arts})
        cache_roots.append({
            "path": str(root),
            "exists": exists,
            "artifacts": len(arts),
            "groups": groups,
            "status": "OK" if exists and arts else ("EMPTY" if exists else "MISSING"),
        })
    mirror_caps = {}
    mirror_pending = {}
    mirror_responses = {}
    try:
        mirror_caps = c.capabilities()
    except LgmError as e:
        mirror_caps = {"error": e.message, "code": e.code}
    try:
        mirror_pending = c.deps_pending(repo)
    except LgmError as e:
        mirror_pending = {"error": e.message, "code": e.code}
    try:
        mirror_responses = c.deps_responses(repo)
    except LgmError as e:
        mirror_responses = {"error": e.message, "code": e.code}
    return {
        "env": env_info,
        "cache_roots": cache_roots,
        "mirror": {
            "base_url": ctx.config.base_url,
            "capabilities": mirror_caps,
            "pending": mirror_pending,
            "responses": mirror_responses,
        },
        "repo": repo,
    }


def _select_item(items: list[dict], id_prefix: str, label: str) -> tuple[dict, int]:
    if not id_prefix:
        return items[0], len(items) - 1
    matches = [it for it in items if str(it.get("id", "")).startswith(id_prefix)]
    if not matches:
        raise LgmError("config", f"no {label} matches id '{id_prefix}'")
    if len(matches) > 1:
        raise LgmError("config",
                       f"id '{id_prefix}' matches {len(matches)} {label}s; use a longer prefix")
    return matches[0], len(items) - 1


def op_respond(ctx: Ctx, args: dict) -> dict:
    """Find requested coords in local cache and ship them to Mirror."""
    c = _client(ctx)
    repo = _repo_arg(args)
    project = args.get("project", "")
    dry_run = args.get("dry_run", False)
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")

    pending = c.deps_pending(repo)
    items = pending.get("items", [])
    if not items:
        return {"repo": repo, "message": "No pending requests.", "found": 0, "not_found": 0}
    req, remaining = _select_item(items, args.get("id", ""), "pending request")
    req_id = req["id"]

    raw_manifest = c.deps_manifest(repo, req_id)
    manifest = json.loads(decrypt_bundle(raw_manifest, ctx.config.sync_password))

    if manifest.get("version", 0) < 2 or not manifest.get("missing"):
        return {"repo": repo, "request_id": req_id, "manifest": manifest,
                "remaining": remaining, "message": "Empty or legacy manifest"}

    # collect from all caches
    all_arts = []
    for root in gradle_candidate_roots():
        for a in scan_cache(root):
            a["source"] = "gradle"
            all_arts.append(a)
    for a in scan_maven_local(with_sha1=False):
        a["source"] = "maven"
        all_arts.append(a)
    by_gnv: dict[str, list[dict]] = {}
    for a in all_arts:
        k = f"{a['group']}:{a['name']}:{a['version']}"
        by_gnv.setdefault(k, []).append(a)

    npm_cache_root = npm_cache_dir() / "_cacache" / "content-v2"
    found, not_found = [], []
    skipped_unshipable = 0
    for coord in manifest["missing"]:
        eco = coord.get("ecosystem", "gradle")
        if eco == "npm":
            g = coord.get("group", "")
            n = coord["name"]
            v = coord["version"]
            k = f"{g}/{n}@{v}" if g else f"{n}@{v}"
            integrity = coord.get("classifier", "")
            tb = npm_find_tarball(integrity, npm_cache_root)
            if tb:
                found.append(("npm/" + _npm_mirror_rel(g, n, v), str(tb)))
            else:
                not_found.append(k)
            continue
        if eco != "gradle":
            continue
        k = f"{coord['group']}:{coord['name']}:{coord['version']}"
        arts = by_gnv.get(k)
        if not arts:
            not_found.append(k)
            continue
        shipable = _pick_shipable(arts)
        skipped_unshipable += len(arts) - len(shipable)
        for a in shipable:
            if a["source"] == "gradle":
                rel = f"gradle/{a['group']}/{a['name']}/{a['version']}/{a['sha1']}/{a['file']}"
            else:
                rel = f"gradle/{maven_local_relpath(a['group'], a['name'], a['version'], a['file'])}"
            found.append((rel, a["path"]))

    added_parents = _expand_parent_pom_closure(found, by_gnv)

    if project:
        proj = Path(project)
        lock = proj / "package-lock.json"
        if lock.is_file():
            found.append(("__meta__/package-lock.json", str(lock)))

    if not found:
        return {"repo": repo, "request_id": req_id, "manifest": manifest,
                "found": 0, "not_found": len(not_found), "not_found_items": not_found,
                "skipped_unshipable": skipped_unshipable, "added_parents": added_parents,
                "remaining": remaining, "message": "Nothing to send."}

    if dry_run:
        return {"repo": repo, "request_id": req_id, "manifest": manifest,
                "found": len(found), "not_found": len(not_found), "not_found_items": not_found,
                "skipped_unshipable": skipped_unshipable, "added_parents": added_parents,
                "remaining": remaining, "dry_run": True,
                "items": [{"rel": r, "path": p} for r, p in found]}

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for rel, path in found:
            zf.write(path, rel)
    zip_bytes = buf.getvalue()
    encrypted = encrypt_bundle(zip_bytes, ctx.config.sync_password)
    res = c.deps_respond(repo, req_id, encrypted)
    return {"repo": repo, "request_id": req_id, "manifest": manifest,
            "found": len(found), "not_found": len(not_found), "not_found_items": not_found,
            "skipped_unshipable": skipped_unshipable, "added_parents": added_parents,
            "remaining": remaining, "encrypted_size": len(encrypted), "response": res}


def op_apply(ctx: Ctx, args: dict) -> dict:
    """Download deps response and unpack into gradle cache."""
    c = _client(ctx)
    repo = _repo_arg(args)
    project = args.get("project", "")
    npm_install = args.get("npm_install", False)
    yarn = args.get("yarn", False)
    yarn_install = args.get("yarn_install", False)
    dry_run = args.get("dry_run", False)
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")

    r = c.deps_responses(repo)
    items = r.get("items", [])
    if not items:
        return {"repo": repo, "message": "No responses available."}
    resp, remaining = _select_item(items, args.get("id", ""), "response")
    resp_id = resp["id"]

    raw = c.deps_fetch(repo, resp_id)
    plaintext = decrypt_bundle(raw, ctx.config.sync_password)

    target = maven_local_root()
    target.mkdir(parents=True, exist_ok=True)
    _ensure_mavenlocal_init_script()

    if dry_run:
        with zipfile.ZipFile(io.BytesIO(plaintext)) as zf:
            names = zf.namelist()
        return {"repo": repo, "response_id": resp_id, "dry_run": True,
                "remaining": remaining, "entry_count": len(names), "sample": names[:10]}

    installed = skipped = invalid = 0
    layout_observed = None
    meta_files = {}
    with zipfile.ZipFile(io.BytesIO(plaintext)) as zf:
        for entry in zf.namelist():
            parts = entry.split("/", 1)
            if len(parts) != 2:
                invalid += 1
                continue
            eco, rel = parts
            if ".." in rel or rel.startswith("/"):
                invalid += 1
                continue
            if eco == "__meta__":
                meta_files[rel] = zf.read(entry)
                continue
            if eco == "npm":
                npm_dest = npm_offline_mirror() / rel
                data = zf.read(entry)
                if npm_dest.exists() and npm_dest.stat().st_size == len(data):
                    skipped += 1
                    continue
                npm_dest.parent.mkdir(parents=True, exist_ok=True)
                npm_dest.write_bytes(data)
                installed += 1
                continue
            if eco == "gradle":
                segs = rel.split("/")
                is_gradle_layout = (
                    len(segs) >= 5
                    and len(segs[-2]) == 40
                    and all(c in "0123456789abcdef" for c in segs[-2].lower())
                )
                if is_gradle_layout:
                    group_path = segs[0].replace(".", "/")
                    rel = "/".join([group_path] + segs[1:-2] + [segs[-1]])
                    if layout_observed != "gradle":
                        layout_observed = "gradle"
                else:
                    if layout_observed != "maven":
                        layout_observed = "maven"
            dest = target / rel
            data = zf.read(entry)
            if dest.exists() and dest.stat().st_size == len(data):
                skipped += 1
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            installed += 1

    result: dict = {
        "repo": repo, "response_id": resp_id, "remaining": remaining,
        "installed": installed, "skipped": skipped, "invalid": invalid,
        "layout": layout_observed, "target": str(target),
    }

    # npm post-install
    npm_mirror = npm_offline_mirror()
    tgzs = sorted(npm_mirror.rglob("*.tgz")) if npm_mirror.is_dir() else []
    if tgzs:
        npm_cmd = ["cmd", "/c", "npm"] if os.name == "nt" else ["npm"]

        def _cache_add(tb: Path) -> bool:
            try:
                proc = subprocess.run(
                    npm_cmd + ["cache", "add", str(tb)],
                    capture_output=True, text=True, timeout=120,
                )
                return proc.returncode == 0
            except Exception:
                return False

        with ThreadPoolExecutor(max_workers=4) as ex:
            outcomes = list(ex.map(_cache_add, tgzs))
        npm_ok = sum(outcomes)
        npm_fail = len(outcomes) - npm_ok
        result["npm"] = {"ok": npm_ok, "fail": npm_fail, "mirror": str(npm_mirror)}

    # npm lockfile
    lock_bytes = meta_files.get("package-lock.json")
    if lock_bytes is not None and project:
        proj = Path(project)
        if proj.is_dir():
            text = lock_bytes.decode("utf-8", errors="replace")
            text, nrw = _rewrite_lock_to_npmjs(text, proj)
            (proj / "package-lock.json").write_bytes(text.encode("utf-8"))
            result["npm_lock"] = {"rewritten": nrw}
            if npm_install:
                npm_cmd = ["cmd", "/c", "npm"] if os.name == "nt" else ["npm"]
                proc = subprocess.run(
                    npm_cmd + ["install", "--prefer-offline",
                               "--registry", "https://registry.npmjs.org",
                               "--no-audit", "--no-fund"],
                    cwd=str(proj),
                )
                result["npm_lock"]["install_exit"] = proc.returncode

    # yarn
    if yarn or yarn_install:
        proj = Path(project) if project else None
        if proj and (proj / "yarn.lock").is_file():
            ymir = _yarn_offline_mirror()
            cnt = _build_yarn_mirror(npm_offline_mirror(), ymir)
            _set_global_yarn_mirror(ymir)
            result["yarn"] = {"count": cnt, "mirror": str(ymir)}
            if yarn_install:
                yarn_bin = os.environ.get("LGM_YARN", "yarn")
                yarn_cmd = ["cmd", "/c", yarn_bin] if os.name == "nt" else [yarn_bin]
                try:
                    proc = subprocess.run(
                        yarn_cmd + ["install", "--offline", "--pure-lockfile", "--non-interactive"],
                        cwd=str(proj),
                    )
                    result["yarn"]["install_exit"] = proc.returncode
                except FileNotFoundError:
                    result["yarn"]["error"] = "yarn not on PATH"

    # ack
    try:
        ack = c.deps_ack(repo, resp_id)
        result["ack"] = ack
    except LgmError as e:
        result["ack"] = {"error": e.message, "code": e.code}
    return result


def op_request(ctx: Ctx, args: dict) -> dict:
    """Build a minimum-traffic deps request (manifest v3) and post it to Mirror."""
    import re as _re
    c = _client(ctx)
    repo = _repo_arg(args)
    project_path = args.get("project", "")
    npm_scopes = args.get("npm_scopes", "")
    dry_run = args.get("dry_run", False)
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")
    if not project_path:
        raise LgmError("config", "--project is required")
    project = Path(project_path).resolve()
    if not project.is_dir():
        raise LgmError("config", f"project not found: {project}")

    _gradle_markers = ("build.gradle", "build.gradle.kts", "settings.gradle",
                       "settings.gradle.kts", "gradlew", "gradlew.bat")
    is_gradle = any((project / m).exists() for m in _gradle_markers)

    if is_gradle:
        java_home = _detect_java_home()
        out_file, full_stdout, exit_code = _run_gradle_init(project, java_home)
    else:
        out_file, full_stdout, exit_code = None, "", 0

    raw = []
    guh = None
    try:
        if out_file and out_file.exists():
            for line in out_file.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                if obj.get("g") == "__GUH__":
                    guh = obj.get("f") or None
                    continue
                raw.append(obj)
    finally:
        if out_file:
            try:
                out_file.unlink(missing_ok=True)
            except Exception:
                pass

    fallback_re = _re.compile(
        r"(?:No cached version of|Could not download[^\(]*\()"
        r"\s*([^:\s\(]+):([^:\s]+):([^\s\)]+)"
    )
    fb_seen = set()
    for m in fallback_re.finditer(full_stdout):
        g, n, v = m.group(1), m.group(2), m.group(3)
        if n.endswith(".gradle.plugin"):
            continue
        k = f"{g}:{n}:{v}"
        if k in fb_seen:
            continue
        fb_seen.add(k)
        raw.append({"g": g, "n": n, "v": v, "f": ""})

    seen = set()
    raw_missing = []
    for o in raw:
        k = f"{o.get('g', '')}:{o.get('n', '')}:{o.get('v', '')}"
        if k in seen:
            continue
        seen.add(k)
        raw_missing.append(o)

    extra_root = Path(guh) / "caches/modules-2/files-2.1" if guh else None
    cache_roots = []
    if is_gradle:
        if extra_root and extra_root.is_dir():
            cache_roots.append(extra_root)
        for r in gradle_candidate_roots():
            if r.is_dir() and r not in cache_roots:
                cache_roots.append(r)
    all_artifacts = []
    seen_artifact_keys = set()
    for r in cache_roots:
        for a in scan_cache(r):
            art_key = f"{a['group']}:{a['name']}:{a['version']}/{a['sha1']}/{a['file']}"
            if art_key in seen_artifact_keys:
                continue
            seen_artifact_keys.add(art_key)
            all_artifacts.append(a)
    maven_arts = scan_maven_local() if is_gradle else []
    for a in maven_arts:
        art_key = f"{a['group']}:{a['name']}:{a['version']}/{a['sha1']}/{a['file']}"
        if art_key in seen_artifact_keys:
            continue
        seen_artifact_keys.add(art_key)
        all_artifacts.append(a)

    coord_kinds: dict[str, set] = {}
    coord_pom: dict[str, str] = {}
    for a in all_artifacts:
        gnv = f"{a['group']}:{a['name']}:{a['version']}"
        kind = _classify_artifact(a["file"]) or "doc"
        coord_kinds.setdefault(gnv, set()).add(kind)
        if a["file"].lower().endswith(".pom"):
            coord_pom[gnv] = a["path"]

    def _coord_satisfied(gnv: str) -> bool:
        kinds = coord_kinds.get(gnv)
        if not kinds:
            return False
        has_meta = bool(kinds & {"pom", "module"})
        if kinds & {"jar", "aar", "klib"}:
            # mavenLocal() resolves metadata from the pom; a bare jar without pom/module is unresolvable for Gradle.
            return has_meta
        pom = coord_pom.get(gnv)
        if pom and _pom_packaging(pom) in ("pom", "bom"):
            return True
        return False

    cached_coords = {gnv for gnv in coord_kinds if _coord_satisfied(gnv)}

    root_name = _read_root_project_name(project)

    def _own(g: str) -> bool:
        return bool(root_name) and (g == root_name or g.startswith(f"{root_name}."))

    missing = []
    for o in raw_missing:
        gnv = f"{o['g']}:{o['n']}:{o['v']}"
        if gnv in cached_coords:
            continue
        if _own(o.get("g", "")):
            continue
        missing.append(o)

    npm_scopes_list = [s.strip() for s in (npm_scopes or "").split(",") if s.strip()]
    npm_missing = (
        _detect_npm_missing(project, npm_scopes_list)
        if (project / "package.json").is_file() else []
    )

    if not missing and not npm_missing:
        return {"project": project.name, "message": "Nothing to request — all dependencies resolve locally.",
                "missing": [], "npm_missing": [], "cached_coords": len(cached_coords)}

    by_gnv: dict[str, list[dict]] = {}
    for a in all_artifacts:
        by_gnv.setdefault(f"{a['group']}:{a['name']}:{a['version']}", []).append(a)

    present = []
    seen_present_keys = set()
    for arts in by_gnv.values():
        for a in _pick_shipable(arts):
            present_key = f"{a['group']}:{a['name']}:{a['version']}:{a['sha1']}:{a['file']}"
            if present_key in seen_present_keys:
                continue
            seen_present_keys.add(present_key)
            present.append({
                "g": a["group"], "n": a["name"], "v": a["version"],
                "sha1": a["sha1"], "fileName": a["file"],
            })

    missing_entries = [
        {"ecosystem": "gradle", "group": m["g"], "name": m["n"], "version": m["v"], "classifier": ""}
        for m in missing
    ]
    missing_entries += [
        {"ecosystem": "npm", "group": c["group"], "name": c["name"],
         "version": c["version"], "classifier": c["classifier"]}
        for c in npm_missing
    ]
    ecosystem = "gradle,npm" if npm_missing else "gradle"
    manifest = {
        "version": 3,
        "requester": os.environ.get("USERNAME") or os.environ.get("USER") or "lgm-cli",
        "project": repo,
        "ecosystem": ecosystem,
        "missing": missing_entries,
        "present": present,
    }
    payload_json = json.dumps(manifest, ensure_ascii=False).encode("utf-8")

    result: dict = {
        "project": project.name, "repo": repo,
        "raw_missing": len(raw_missing), "missing": len(missing),
        "npm_missing": len(npm_missing), "present": len(present),
        "cached_coords": len(cached_coords), "guh": guh,
        "manifest_size": len(payload_json),
        "missing_items": missing_entries,
    }

    if dry_run:
        result["dry_run"] = True
        return result

    encrypted = encrypt_bundle(payload_json, ctx.config.sync_password)
    res = c.deps_request(repo, encrypted)
    result["posted"] = True
    result["response"] = res
    return result


def op_fetch_poms(ctx: Ctx, args: dict) -> dict:
    """Fetch missing poms from a public Maven repository."""
    repo_url = args.get("repo_url", "https://repo.maven.apache.org/maven2").rstrip("/")
    dry_run = args.get("dry_run", False)
    fetched_total = 0
    failed_total = 0
    skipped_private_total = 0
    for pass_no in (1, 2):
        if pass_no == 1:
            targets = _maven_local_jars_without_poms()
        else:
            targets = _missing_parent_poms()
        max_iters = 1 if pass_no == 1 else 8
        iter_no = 0
        while targets and iter_no < max_iters:
            iter_no += 1
            fetched, skipped_priv, failed = _fetch_pom_batch(repo_url, targets, dry_run)
            fetched_total += fetched
            skipped_private_total += skipped_priv
            failed_total += failed
            if pass_no == 1:
                break
            targets = _missing_parent_poms()
    return {"repo_url": repo_url, "fetched": fetched_total,
            "skipped_private": skipped_private_total, "failed": failed_total}
