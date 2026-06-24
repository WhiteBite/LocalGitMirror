#!/usr/bin/env python3
"""
lgm — LocalGitMirror CLI diagnostic tool.

Usage:
  python lgm.py scan [--gradle-home PATH]          # scan local gradle cache
  python lgm.py pending [--repo REPO]              # list pending dep requests on Mirror
  python lgm.py respond [--repo REPO] [--dry-run]  # find & ship requested deps
  python lgm.py apply [--repo REPO] [--dry-run]    # download & unpack deps response
  python lgm.py debug [--project PATH]             # full diagnostics: env + cache + mirror

Config is read from .env in the same directory as this script, or from
--base-url / --password flags. SYNC_PASSWORD and API_KEY from .env are used.
"""
import argparse
import json
import os
import sys
import uuid
import zipfile
import struct
import hashlib
from pathlib import Path
from typing import Optional

# ── locate .env ───────────────────────────────────────────────────────────────
_HERE = Path(__file__).parent

def load_env(path: Path = _HERE / ".env") -> dict:
    env = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, _, v = line.partition("=")
            env[k.strip()] = v.strip()
    return env

_ENV = load_env()

def cfg(key: str, default: str = "") -> str:
    """
    Resolve a config key. Project's `.env` takes priority over the process
    environment because the env may carry an UNRELATED variable of the same
    name (e.g. a workstation-wide `API_KEY` belonging to some other tool —
    which is exactly the trap that bit us once with a wrong-length API key
    silently overriding the .env one). To opt OUT for a specific key, set
    `LGM_USE_ENV_<KEY>=1`.
    """
    env_override = os.environ.get(f"LGM_USE_ENV_{key}")
    if env_override:
        return os.environ.get(key) or _ENV.get(key) or default
    return _ENV.get(key) or os.environ.get(key) or default

# ── crypto (mirrors bundle_crypto.py) ────────────────────────────────────────
#
# Format v2 (current):
#   version(1) = 0x01 | salt(16) | nonce(12) | ciphertext_len(8 BE) | ciphertext
# Format v1 (legacy, read-only):
#   magic(8)='LGMSTRL1' | salt(16) | nonce(12) | ciphertext_len(8 BE) | ciphertext
#
# KDF: PBKDF2-HMAC-SHA256(password, salt, 200_000 iters) -> 32 bytes

_MAGIC_V1 = b"LGMSTRL1"
_FORMAT_V2 = 0x01
_PBKDF2_ITERS = 200_000


def _derive_key(password: str, salt: bytes) -> bytes:
    try:
        from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
        from cryptography.hazmat.primitives import hashes
    except ImportError:
        sys.exit("pip install cryptography")
    import hashlib as _hl
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=_PBKDF2_ITERS)
    return kdf.derive(password.encode("utf-8"))


def decrypt_bundle(data: bytes, password: str) -> bytes:
    """AES-256-GCM decrypt — mirrors bundle_crypto.decrypt_dump_to_bundle."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        sys.exit("pip install cryptography")

    min_len = 1 + 16 + 12 + 8 + 16  # version + salt + nonce + len + min_tag
    if len(data) < min_len:
        raise ValueError("Payload too small")

    cursor = 0
    first_byte = data[0]

    if first_byte == ord("L"):
        # v1 legacy
        if data[:8] != _MAGIC_V1:
            raise ValueError("Unsupported format")
        cursor = 8
    elif first_byte == _FORMAT_V2:
        cursor = 1
    else:
        raise ValueError(f"Unsupported format version: 0x{first_byte:02x}")

    salt  = data[cursor:cursor + 16]; cursor += 16
    nonce = data[cursor:cursor + 12]; cursor += 12
    (ct_len,) = struct.unpack(">Q", data[cursor:cursor + 8]); cursor += 8
    ct = data[cursor:cursor + ct_len]

    if len(ct) != ct_len:
        raise ValueError("Corrupted payload")

    key = _derive_key(password, salt)
    return AESGCM(key).decrypt(nonce, ct, None)


def encrypt_bundle(plaintext: bytes, password: str) -> bytes:
    """AES-256-GCM encrypt — mirrors bundle_crypto.encrypt_bundle_to_dump."""
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        sys.exit("pip install cryptography")
    import secrets as _sec
    salt  = _sec.token_bytes(16)
    nonce = _sec.token_bytes(12)
    key   = _derive_key(password, salt)
    ct    = AESGCM(key).encrypt(nonce, plaintext, None)
    return (
        bytes([_FORMAT_V2])
        + salt
        + nonce
        + struct.pack(">Q", len(ct))
        + ct
    )

# ── HTTP helpers ──────────────────────────────────────────────────────────────
import urllib.request
import urllib.error
import ssl

from _lgm_npm import (npm_cache_dir, npm_offline_mirror,
                      npm_find_tarball, _detect_npm_missing, _npm_mirror_rel)

def _ssl_ctx(insecure: bool = True) -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    if insecure:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    return ctx

def _auth_headers(api_key: str) -> dict:
    """Return auth headers matching what the backend expects (X-Session-ID)."""
    return {"X-Session-ID": api_key}


def api_get(base_url: str, path: str, api_key: str, insecure: bool = True) -> dict:
    url = base_url.rstrip("/") + path
    req = urllib.request.Request(url, headers=_auth_headers(api_key))
    try:
        with urllib.request.urlopen(req, context=_ssl_ctx(insecure), timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"_error": e.code, "_msg": e.read().decode(errors="replace")}
    except Exception as e:
        return {"_error": str(e)}


def api_delete(base_url: str, path: str, api_key: str, insecure: bool = True) -> dict:
    """HTTP DELETE request — used for ack."""
    url = base_url.rstrip("/") + path
    req = urllib.request.Request(url, method="DELETE", headers=_auth_headers(api_key))
    try:
        with urllib.request.urlopen(req, context=_ssl_ctx(insecure), timeout=15) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"_error": e.code, "_msg": e.read().decode(errors="replace")}
    except Exception as e:
        return {"_error": str(e)}


def api_post_bytes(base_url: str, path: str, api_key: str,
                   body: bytes, content_type: str = "application/octet-stream",
                   insecure: bool = True) -> dict:
    url = base_url.rstrip("/") + path
    headers = {**_auth_headers(api_key), "Content-Type": content_type}
    req = urllib.request.Request(url, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, context=_ssl_ctx(insecure), timeout=60) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"_error": e.code, "_msg": e.read().decode(errors="replace")}
    except Exception as e:
        return {"_error": str(e)}


def api_post_multipart(base_url: str, path: str, api_key: str,
                       fields: dict, files: dict,
                       insecure: bool = True) -> dict:
    """
    POST multipart/form-data.
    fields: {name: str_value}
    files:  {name: (filename, bytes)}
    """
    import email.generator
    import io

    boundary = uuid.uuid4().hex
    body_parts = []

    for name, value in fields.items():
        body_parts.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
            f"{value}\r\n"
        )
    for name, (filename, data) in files.items():
        header = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n"
        )
        body_parts.append(header.encode() + data + b"\r\n")

    body_parts.append(f"--{boundary}--\r\n")

    # Build final body as bytes
    body = b"".join(
        p.encode() if isinstance(p, str) else p
        for p in body_parts
    )

    url = base_url.rstrip("/") + path
    headers = {
        **_auth_headers(api_key),
        "Content-Type": f"multipart/form-data; boundary={boundary}",
    }
    req = urllib.request.Request(url, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, context=_ssl_ctx(insecure), timeout=120) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"_error": e.code, "_msg": e.read().decode(errors="replace")}
    except Exception as e:
        return {"_error": str(e)}


def api_download(base_url: str, path: str, api_key: str,
                 out: Path, insecure: bool = True) -> bool:
    url = base_url.rstrip("/") + path
    req = urllib.request.Request(url, headers=_auth_headers(api_key))
    try:
        with urllib.request.urlopen(req, context=_ssl_ctx(insecure), timeout=120) as r:
            out.write_bytes(r.read())
        return True
    except Exception as e:
        print(f"  download error: {e}")
        return False

# ── Gradle cache scanner ──────────────────────────────────────────────────────
def gradle_candidate_roots() -> list[Path]:
    """All plausible gradle cache roots, same logic as DepsScanner.kt."""
    sub = Path("caches") / "modules-2" / "files-2.1"
    homes = []
    for env_var in ("GRADLE_USER_HOME",):
        v = os.environ.get(env_var) or _ENV.get(env_var)
        if v:
            homes.append(Path(v))
    user_home = Path.home()
    homes.append(user_home / ".gradle")
    gradle_home = os.environ.get("GRADLE_HOME")
    if gradle_home:
        homes.append(Path(gradle_home) / ".gradle")
    seen, out = set(), []
    for h in homes:
        r = (h / sub).resolve()
        if str(r).lower() not in seen:
            seen.add(str(r).lower())
            out.append(h / sub)
    return out

def scan_cache(root: Path, group_filter: str = "") -> list[dict]:
    """Walk gradle files-2.1 layout, return list of artifact dicts."""
    if not root.is_dir():
        return []
    arts = []
    for g_dir in root.iterdir():
        if not g_dir.is_dir():
            continue
        if group_filter and group_filter.lower() not in g_dir.name.lower():
            continue
        for n_dir in g_dir.iterdir():
            if not n_dir.is_dir():
                continue
            for v_dir in n_dir.iterdir():
                if not v_dir.is_dir():
                    continue
                for sha_dir in v_dir.iterdir():
                    if not sha_dir.is_dir():
                        continue
                    for f in sha_dir.iterdir():
                        if f.is_file() and not f.name.startswith("_") and f.name != ".lock":
                            arts.append({
                                "group": g_dir.name,
                                "name": n_dir.name,
                                "version": v_dir.name,
                                "sha1": sha_dir.name,
                                "file": f.name,
                                "path": str(f),
                                "size": f.stat().st_size,
                            })
    return arts

# ── commands ──────────────────────────────────────────────────────────────────

def cmd_scan(args):
    """Show what's in the local gradle cache."""
    roots = [Path(args.gradle_home)] if args.gradle_home else gradle_candidate_roots()
    group_filter = args.filter or ""
    total = 0
    for root in roots:
        exists = root.is_dir()
        arts = scan_cache(root, group_filter) if exists else []
        groups = len({a["group"] for a in arts})
        print(f"\n{'[OK]' if exists else '[--]'} {root}")
        print(f"     artifacts={len(arts)}  groups={groups}")
        if arts and args.verbose:
            for a in arts[:50]:
                print(f"     {a['group']}:{a['name']}:{a['version']}  {a['file']}  ({a['size']} B)")
            if len(arts) > 50:
                print(f"     ... and {len(arts)-50} more")
        total += len(arts)
    print(f"\nTotal artifacts across all roots: {total}")


def cmd_pending(args):
    """List pending dep requests on the Mirror server."""
    base = args.base_url or cfg("BASE_URL", "https://localhost:443")
    key  = args.api_key  or cfg("API_KEY")
    repo = args.repo     or "onyx-platform"
    print(f"Checking pending deps for repo='{repo}' at {base}")
    r = api_get(base, f"/api/deps/pending?repo={repo}", key)
    if "_error" in r:
        print(f"  ERROR: {r}")
        return
    items = r.get("items", [])
    print(f"  {len(items)} pending request(s)")
    for it in items:
        print(f"  - id={it.get('id','?')[:12]}  size={it.get('size',0)}  mtime={it.get('mtime','?')}")


def cmd_debug(args):
    """Full diagnostics: env vars, cache roots, mirror connectivity."""
    print("=== LGM DEBUG ===\n")
    print(f"GRADLE_USER_HOME (env)   : {os.environ.get('GRADLE_USER_HOME', '(not set)')}")
    print(f"GRADLE_USER_HOME (.env)  : {_ENV.get('GRADLE_USER_HOME', '(not set)')}")
    print(f"HOME                     : {Path.home()}")

    print("\n--- Gradle cache candidates ---")
    for root in gradle_candidate_roots():
        exists = root.is_dir()
        arts = scan_cache(root) if exists else []
        groups = len({a["group"] for a in arts})
        marker = "[OK]" if exists and arts else ("[EMPTY]" if exists else "[MISSING]")
        print(f"  {marker} {root}  (artifacts={len(arts)}, groups={groups})")

    base = args.base_url or cfg("BASE_URL", "https://localhost:443")
    key  = args.api_key  or cfg("API_KEY")
    repo = args.repo or "onyx-platform"
    print(f"\n--- Mirror connectivity: {base} ---")
    r = api_get(base, "/api/status", key)
    if "_error" in r:
        print(f"  FAILED: {r}")
    else:
        print(f"  OK: {r}")

    print(f"\n--- Pending deps for repo='{repo}' ---")
    r = api_get(base, f"/api/deps/pending?repo={repo}", key)
    if "_error" in r:
        print(f"  FAILED: {r}")
    else:
        items = r.get("items", [])
        print(f"  {len(items)} pending")
        for it in items:
            print(f"  - {it.get('id','?')[:12]}  mtime={it.get('mtime','?')}")

    print(f"\n--- Available responses for repo='{repo}' ---")
    r = api_get(base, f"/api/deps/responses?repo={repo}", key)
    if "_error" in r:
        print(f"  FAILED: {r}")
    else:
        items = r.get("items", [])
        print(f"  {len(items)} available")
        for it in items:
            print(f"  - {it.get('id','?')[:12]}  size={it.get('size',0)}")

def cmd_respond(args):
    """Find requested coords in local cache and ship them to Mirror."""
    import tempfile, io
    base     = args.base_url or cfg("BASE_URL", "https://localhost:443")
    key      = args.api_key  or cfg("API_KEY")
    password = args.password or cfg("SYNC_PASSWORD")
    repo     = args.repo     or "onyx-platform"
    if not password:
        sys.exit("SYNC_PASSWORD not set")

    print(f"Fetching pending requests for repo='{repo}'...")
    pending = api_get(base, f"/api/deps/pending?repo={repo}", key)
    if "_error" in pending:
        sys.exit(f"Failed: {pending}")
    items = pending.get("items", [])
    if not items:
        print("No pending requests.")
        return
    req = items[0]
    req_id = req["id"]
    print(f"  Request: {req_id[:12]}  downloading manifest...")

    tmp = Path(tempfile.mktemp(suffix=".bin"))
    ok = api_download(base, f"/api/deps/manifest?repo={repo}&id={req_id}", key, tmp)
    if not ok:
        sys.exit("Manifest download failed")

    raw = decrypt_bundle(tmp.read_bytes(), password)
    tmp.unlink(missing_ok=True)
    manifest = json.loads(raw)
    print(f"  Manifest: v={manifest.get('version')} missing={len(manifest.get('missing',[]))}")

    if manifest.get("version", 0) < 2 or not manifest.get("missing"):
        sys.exit("Empty or legacy manifest")

    # collect from all caches: gradle internal cache + maven-local.
    # Both sources participate in shipability selection, so e.g. a pom that's
    # only in ~/.m2 still gets shipped alongside a jar that lives in the gradle
    # cache. Each artifact is tagged with its source so we know which bundle
    # layout to emit (gradle layout has a sha-dir; maven layout doesn't).
    all_arts = []
    for root in gradle_candidate_roots():
        for a in scan_cache(root):
            a["source"] = "gradle"
            all_arts.append(a)
    for a in scan_maven_local():
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
        # Same shipability rules the plugin's GradleEcosystem.pickShipableArtifacts
        # uses: drop sources/javadoc/tests/test, then pick freshest file per kind
        # (jar/pom/module/aar/klib). Keeps the response bundle small and avoids
        # shipping useless jars.
        shipable = _pick_shipable(arts)
        skipped_unshipable += len(arts) - len(shipable)
        for a in shipable:
            if a["source"] == "gradle":
                rel = f"gradle/{a['group']}/{a['name']}/{a['version']}/{a['sha1']}/{a['file']}"
            else:  # maven-local — emit in maven layout (cmd_apply autodetects)
                rel = f"gradle/{maven_local_relpath(a['group'], a['name'], a['version'], a['file'])}"
            found.append((rel, a["path"]))
    if skipped_unshipable:
        print(f"  Filtered out {skipped_unshipable} unshipable files (sources/javadoc/tests/dupes)")

    # Parent-pom closure: every shipped .pom may declare a <parent> whose own
    # pom must also be present on the dome, or mavenLocal() resolution fails
    # with "Could not find <parent>". The work machine's cache holds the full
    # chain (gradle downloaded it during its successful build), including
    # PRIVATE parents that Maven Central doesn't have. We walk it here so the
    # dome receives a self-contained set.
    added_parents = _expand_parent_pom_closure(found, by_gnv)
    if added_parents:
        print(f"  Added {added_parents} parent pom(s) to complete the chain")

    # Bundle the project's npm lockfile (if present) so the DOME can do a
    # lockfile-driven offline `npm install` later. apply rewrites resolved→npmjs.
    if getattr(args, "project", ""):
        proj = Path(args.project)
        lock = proj / "package-lock.json"
        if lock.is_file():
            found.append(("__meta__/package-lock.json", str(lock)))
            print(f"  + bundling package-lock.json ({lock.stat().st_size // 1024} KB)")
        elif (proj / "yarn.lock").is_file():
            print("  ! project has yarn.lock but no package-lock.json — run "
                  "`npm install --package-lock-only` there first so it can be shipped")

    print(f"  Found={len(found)}  NotFound={len(not_found)}")
    for k in not_found:
        print(f"  [MISS] {k}")

    if not found:
        print("Nothing to send.")
        return

    if args.dry_run:
        print("  --dry-run: would send the above. Stopping.")
        return

    # pack zip
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for rel, path in found:
            zf.write(path, rel)
    zip_bytes = buf.getvalue()
    encrypted = encrypt_bundle(zip_bytes, password)
    print(f"  Sending {len(encrypted)//1024} KB...")

    # /deps/respond expects multipart form: repo (str), request_id (str), attachment (file)
    res = api_post_multipart(
        base, "/api/deps/respond", key,
        fields={"repo": repo, "request_id": req_id},
        files={"attachment": ("response.bin", encrypted)},
    )
    if "_error" in res:
        print(f"  FAILED: {res}")
    else:
        print(f"  OK: {res}")

def _npmrc_registries(project: Path) -> list:
    """Collect registry base URLs from the project's .npmrc (default + scoped)."""
    bases = []
    npmrc = project / ".npmrc"
    if npmrc.is_file():
        for line in npmrc.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            if k.strip().endswith("registry"):
                v = v.strip()
                if v.startswith("http"):
                    bases.append(v.rstrip("/") + "/")
    return bases


def _rewrite_lock_to_npmjs(text: str, project: Path) -> tuple:
    """Rewrite npm-lockfile `resolved` URLs from the corporate registry (read
    from the project's .npmrc) to public npmjs. nexus npm-all proxies npmjs, so
    the tarball path tail is identical and the integrity stays valid. Corporate
    packages 404 on npmjs but resolve from the local npm cache (cache-hit by
    integrity), so their rewritten URL is never actually fetched."""
    npmjs = "https://registry.npmjs.org/"
    count = 0
    for base in _npmrc_registries(project):
        if base == npmjs:
            continue
        count += text.count(base)
        text = text.replace(base, npmjs)
    return text, count


def _yarn_offline_mirror() -> Path:
    return Path.home() / ".lgm-yarn-offline"


def _yarn_tarball_name(name: str, version: str) -> str:
    # yarn v1 offline-mirror filename: scope '/' -> '-', then '-<version>.tgz'
    return name.replace("/", "-") + "-" + version + ".tgz"


def _read_tgz_name_version(tgz: Path):
    """Return (name, version) from package/package.json inside an npm tarball."""
    import tarfile
    try:
        with tarfile.open(tgz, "r:gz") as tf:
            for m in tf.getmembers():
                if m.name.endswith("package.json") and m.name.count("/") <= 1:
                    j = json.loads(tf.extractfile(m).read())
                    return j.get("name"), j.get("version")
    except Exception:
        return None
    return None


def _build_yarn_mirror(tarballs_root: Path, mirror: Path) -> int:
    """Copy corporate .tgz from the npm offline-mirror into a yarn v1
    offline-mirror, renamed to yarn's `<name '/'->'-'>-<version>.tgz`."""
    import shutil
    mirror.mkdir(parents=True, exist_ok=True)
    n = 0
    for tgz in tarballs_root.rglob("*.tgz"):
        nv = _read_tgz_name_version(tgz)
        if not nv or not nv[0] or not nv[1]:
            continue
        shutil.copyfile(tgz, mirror / _yarn_tarball_name(nv[0], nv[1]))
        n += 1
    return n


def _write_yarnrc(project: Path, mirror: Path):
    yarnrc = project / ".yarnrc"
    existing = yarnrc.read_text(encoding="utf-8", errors="replace") if yarnrc.is_file() else ""
    keep = [ln for ln in existing.splitlines()
            if not ln.strip().startswith("yarn-offline-mirror")
            and not ln.strip().startswith("registry ")]
    mp = str(mirror).replace("\\", "/")
    keep += [f'yarn-offline-mirror "{mp}"',
             'yarn-offline-mirror-pruning false',
             'registry "https://registry.npmjs.org"']
    yarnrc.write_text("\n".join(keep) + "\n", encoding="utf-8")


def _rewrite_yarn_lock(project: Path) -> int:
    lock = project / "yarn.lock"
    if not lock.is_file():
        return 0
    text = lock.read_text(encoding="utf-8", errors="replace")
    npmjs = "https://registry.npmjs.org/"
    count = 0
    for base in _npmrc_registries(project):
        if base == npmjs:
            continue
        count += text.count(base)
        text = text.replace(base, npmjs)
    lock.write_text(text, encoding="utf-8")
    return count


def cmd_apply(args):
    """Download deps response and unpack into gradle cache."""
    import tempfile
    base     = args.base_url or cfg("BASE_URL", "https://localhost:443")
    key      = args.api_key  or cfg("API_KEY")
    password = args.password or cfg("SYNC_PASSWORD")
    repo     = args.repo     or "onyx-platform"
    if not password:
        sys.exit("SYNC_PASSWORD not set")

    print(f"Checking responses for repo='{repo}'...")
    r = api_get(base, f"/api/deps/responses?repo={repo}", key)
    if "_error" in r:
        sys.exit(f"Failed: {r}")
    items = r.get("items", [])
    if not items:
        print("No responses available.")
        return
    resp = items[0]
    resp_id = resp["id"]
    size = resp.get("size", 0)
    print(f"  Response: {resp_id[:12]}  size={size//1024} KB  downloading...")

    tmp = Path(tempfile.mktemp(suffix=".bin"))
    ok = api_download(base, f"/api/deps/fetch?repo={repo}&id={resp_id}", key, tmp)
    if not ok:
        sys.exit("Download failed")

    raw = decrypt_bundle(tmp.read_bytes(), password)
    tmp.unlink(missing_ok=True)

    # New protocol (v0.65+): receivers go to ~/.m2/repository under maven-local
    # layout. The init-script ~/.gradle/init.d/lgm-mavenlocal-fallback.gradle
    # makes them resolvable from gradle even with --offline.
    target = maven_local_root()
    target.mkdir(parents=True, exist_ok=True)
    print(f"  Target cache: {target}")

    # Auto-install the init-script if it's missing/stale (same logic as plugin).
    _ensure_mavenlocal_init_script()

    if args.dry_run:
        with zipfile.ZipFile(__import__("io").BytesIO(raw)) as zf:
            names = zf.namelist()
        print(f"  --dry-run: would install {len(names)} entries. First 10:")
        for n in names[:10]:
            print(f"    {n}")
        return

    installed = skipped = invalid = 0
    layout_observed = None  # "gradle" or "maven", set on first usable entry
    meta_files = {}  # __meta__/* entries (e.g. package-lock.json), handled post-loop
    with zipfile.ZipFile(__import__("io").BytesIO(raw)) as zf:
        for entry in zf.namelist():
            # Strip ecosystem prefix (gradle/<rest> or npm/<rest>).
            parts = entry.split("/", 1)
            if len(parts) != 2:
                invalid += 1
                continue
            eco, rel = parts
            if ".." in rel or rel.startswith("/"):
                invalid += 1
                continue

            # Meta entries (e.g. the project's package-lock.json) are not cache
            # artifacts — stash them for post-loop handling.
            if eco == "__meta__":
                meta_files[rel] = zf.read(entry)
                continue

            # npm artifacts go to the offline mirror (~/.lgm-npm-offline), not
            # ~/.m2. They're post-installed into the npm cache after the loop.
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

            # Normalise gradle internal layout → maven-local layout for ~/.m2.
            #
            # Gradle (legacy v0.64 senders) ships:
            #   <g-with-dots>/<n>/<v>/<sha1>/<file>   (5 segments; group is one
            #                                          dir whose name contains '.')
            # Maven local expects:
            #   <g/with/slashes>/<n>/<v>/<file>       (depth varies by group's
            #                                          dot-count; no sha1 dir)
            #
            # Two transforms, applied only when the sender used gradle layout:
            #   1. Drop the sha1 directory (40 hex chars).
            #   2. Replace dots in the group segment with slashes.
            #
            # If the sender already uses maven layout (v0.65+) — neither step
            # is needed; we leave [rel] alone.
            if eco == "gradle":
                segs = rel.split("/")
                is_gradle_layout = (
                    len(segs) >= 5
                    and len(segs[-2]) == 40
                    and all(c in "0123456789abcdef" for c in segs[-2].lower())
                )
                if is_gradle_layout:
                    # segs = [group_with_dots, name, version, sha1, file]
                    group_path = segs[0].replace(".", "/")
                    rel = "/".join([group_path] + segs[1:-2] + [segs[-1]])
                    if layout_observed != "gradle":
                        layout_observed = "gradle"
                        print(f"  detected sender layout: gradle internal cache (v0.64) — normalising to maven-local")
                else:
                    if layout_observed != "maven":
                        layout_observed = "maven"
                        print(f"  detected sender layout: maven-local (v0.65+)")

            dest = target / rel
            data = zf.read(entry)
            if dest.exists() and dest.stat().st_size == len(data):
                skipped += 1
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            installed += 1

    print(f"  installed={installed}  skipped={skipped}  invalid={invalid}")

    # npm post-install: seed every received .tgz into the local npm cache so
    # `yarn install` / `npm install` can resolve corporate packages offline.
    npm_mirror = npm_offline_mirror()
    tgzs = sorted(npm_mirror.rglob("*.tgz")) if npm_mirror.is_dir() else []
    if tgzs:
        import subprocess
        print(f"  npm: seeding {len(tgzs)} tarball(s) into npm cache...")
        npm_cmd = ["cmd", "/c", "npm"] if os.name == "nt" else ["npm"]
        npm_ok = npm_fail = 0
        for tb in tgzs:
            try:
                proc = subprocess.run(
                    npm_cmd + ["cache", "add", str(tb)],
                    capture_output=True, text=True, timeout=120,
                )
                if proc.returncode == 0:
                    npm_ok += 1
                else:
                    npm_fail += 1
                    print(f"    [fail] {tb.name}: {(proc.stderr or proc.stdout).strip()[:120]}")
            except Exception as e:
                npm_fail += 1
                print(f"    [err ] {tb.name}: {type(e).__name__}: {e}")
        print(f"  npm: cache add ok={npm_ok} fail={npm_fail}  (mirror: {npm_mirror})")

    # npm lockfile: if the bundle carried the project's package-lock.json,
    # rewrite its resolved URLs (corporate registry -> npmjs) and write it into
    # the project. Then a lockfile-driven `npm install` resolves public packages
    # from npmjs and the corporate ones from the cache we just seeded.
    lock_bytes = meta_files.get("package-lock.json")
    if lock_bytes is not None and getattr(args, "project", ""):
        proj = Path(args.project)
        if proj.is_dir():
            text = lock_bytes.decode("utf-8", errors="replace")
            text, nrw = _rewrite_lock_to_npmjs(text, proj)
            (proj / "package-lock.json").write_bytes(text.encode("utf-8"))
            print(f"  npm: wrote package-lock.json ({nrw} resolved URL(s) -> npmjs)")
            if getattr(args, "npm_install", False):
                import subprocess
                npm_cmd = ["cmd", "/c", "npm"] if os.name == "nt" else ["npm"]
                print("  npm: install (public from npmjs, corporate from cache)...")
                proc = subprocess.run(
                    npm_cmd + ["install", "--prefer-offline",
                               "--registry", "https://registry.npmjs.org",
                               "--no-audit", "--no-fund"],
                    cwd=str(proj),
                )
                print(f"  npm: install exit={proc.returncode}")
            else:
                print("  npm: run `npm install --prefer-offline "
                      "--registry https://registry.npmjs.org` in the project "
                      "(or re-run apply with --npm-install)")
        else:
            print(f"  ! --project is not a directory: {proj}")
    elif lock_bytes is not None:
        print("  npm: bundle carried package-lock.json — pass --project <dir> to apply it")

    # yarn (classic v1) support: build a yarn offline-mirror from the corporate
    # tarballs, wire .yarnrc + bring yarn.lock resolved URLs to npmjs, so
    # `yarn install` resolves public from npmjs and corporate from the mirror.
    if getattr(args, "yarn", False) or getattr(args, "yarn_install", False):
        proj = Path(args.project) if getattr(args, "project", "") else None
        if proj and (proj / "yarn.lock").is_file():
            ymir = _yarn_offline_mirror()
            cnt = _build_yarn_mirror(npm_offline_mirror(), ymir)
            _write_yarnrc(proj, ymir)
            nrw = _rewrite_yarn_lock(proj)
            print(f"  yarn: mirror {cnt} tarball(s) at {ymir}")
            print(f"  yarn: .yarnrc set; yarn.lock {nrw} resolved URL(s) -> npmjs")
            if getattr(args, "yarn_install", False):
                import subprocess
                env = dict(os.environ)
                env["npm_config_registry"] = "https://registry.npmjs.org"
                yarn_bin = os.environ.get("LGM_YARN", "yarn")
                yarn_cmd = ["cmd", "/c", yarn_bin] if os.name == "nt" else [yarn_bin]
                print("  yarn: install (public from npmjs, corporate from mirror)...")
                try:
                    proc = subprocess.run(
                        yarn_cmd + ["install", "--frozen-lockfile", "--non-interactive"],
                        cwd=str(proj), env=env,
                    )
                    print(f"  yarn: install exit={proc.returncode}")
                except FileNotFoundError:
                    print("  yarn: 'yarn' not on PATH — set LGM_YARN or run yarn install manually")
            else:
                print("  yarn: run (set npm_config_registry=https://registry.npmjs.org) "
                      "then `yarn install --frozen-lockfile` in the project")
        elif proj:
            print("  yarn: no yarn.lock in project — skipping yarn setup")
        else:
            print("  yarn: pass --project <dir> for yarn setup")

    # ack — DELETE /api/deps/ack?repo=...&id=...
    api_delete(base, f"/api/deps/ack?repo={repo}&id={resp_id}", key)
    print("  ACK sent.")


# ── deps request (CLI equivalent of plugin's RequestDepsAction) ──────────────
#
# Goal: reproduce the plugin's "Request missing deps" flow without IDE.
#   1. Run gradle on the project with the same missing-init-script the plugin
#      uses. Yields a list of (g, n, v) the project needs but cannot resolve
#      offline.
#   2. Filter that list against everything the local cache actually has
#      (g:n:v in cachedCoords) and against the project's own subprojects —
#      same logic GradleEcosystem.resolveMissing applies.
#   3. Build `present` from the local cache, applying the same shipability
#      rules the work side will use (drop sources/javadoc/tests; pick the
#      freshest sha-dir per kind). Same rules on both sides → identical keys.
#   4. JSON-encode the v3 manifest, AES-GCM encrypt it with SYNC_PASSWORD,
#      multipart-POST to /api/deps/request.
#
# After this the work machine will see a new pending request and can run
# `python lgm.py respond` to ship the missing artifacts.

_GRADLE_INIT_SCRIPT = r"""
def lgmWriteMissing(out, group, name, version) {
  if (!group || !name || !version || version == 'null' || version == '') return
  if (name.endsWith('.gradle.plugin')) return
  out.write('{"g":"' + group + '","n":"' + name + '","v":"' + version + '","f":""}\n')
}

def lgmScanResolved(out, conf) {
  if (!conf.canBeResolved) return
  try {
    conf.incoming.resolutionResult.allComponents.each { component ->
      def id = component.moduleVersion
      if (!id) return
      if (id.group == 'unspecified' || id.version == 'unspecified' || id.version == '') return
      if (id.name.endsWith('.gradle.plugin')) return
      try {
        def artifacts = conf.incoming.artifactView { config ->
          config.componentFilter { c -> c.moduleVersion?.module == id.module }
          config.lenient(true)
        }.artifacts
        artifacts.each { ra ->
          if (!ra.file.exists()) {
            lgmWriteMissing(out, id.group, id.name, id.version)
          }
        }
        if (artifacts.isEmpty()) {
          lgmWriteMissing(out, id.group, id.name, id.version)
        }
      } catch (Throwable ignored) { }
    }
  } catch (Throwable ignored) { }
}

def lgmScanUnresolved(out, conf) {
  if (!conf.canBeResolved) return
  try {
    conf.resolvedConfiguration.lenientConfiguration.unresolvedModuleDependencies.each { dep ->
      def sel = dep.selector
      lgmWriteMissing(out, sel.group, sel.name, sel.version)
    }
  } catch (Throwable ignored) { }
}

def lgmScanConf(out, conf) {
  lgmScanResolved(out, conf)
  lgmScanUnresolved(out, conf)
}

settingsEvaluated { settings ->
  def out = new java.io.FileWriter('__OUT__', true)
  try {
    try { out.write('{"g":"__GUH__","n":"","v":"","f":"' + settings.gradle.gradleUserHomeDir.absolutePath.replace('\\', '/') + '"}\n') } catch (Throwable ignored) { }
    try { lgmScanConf(out, settings.buildscript.configurations.classpath) } catch (Throwable ignored) { }
  } finally { out.close() }
}

allprojects { p ->
  p.afterEvaluate {
    def out = new java.io.FileWriter('__OUT__', true)
    try {
      p.configurations.each { conf -> lgmScanConf(out, conf) }
      try { lgmScanConf(out, p.buildscript.configurations.classpath) } catch (Throwable ignored) { }
    } finally { out.close() }
  }
}
"""

# Mirrors GradleEcosystem.classifyArtifact + pickShipableArtifacts.
_DOC_SUFFIXES = ("-sources.jar", "-javadoc.jar", "-tests.jar", "-test.jar")

def _classify_artifact(file_name: str) -> str | None:
    """None = drop. Otherwise returns a kind tag for grouping."""
    lower = file_name.lower()
    if any(lower.endswith(s) for s in _DOC_SUFFIXES):
        return None
    if lower.endswith(".jar"):    return "jar"
    if lower.endswith(".pom"):    return "pom"
    if lower.endswith(".module"): return "module"
    if lower.endswith(".aar"):    return "aar"
    if lower.endswith(".klib"):   return "klib"
    return "other"

def _pick_shipable(arts: list[dict]) -> list[dict]:
    """For one g:n:v, pick freshest file per kind, drop docs/tests/sources."""
    by_kind: dict[str, list[dict]] = {}
    for a in arts:
        kind = _classify_artifact(a["file"])
        if kind is None:
            continue
        by_kind.setdefault(kind, []).append(a)
    out = []
    for group in by_kind.values():
        # mtime descending, then path for stable tie-break
        group.sort(key=lambda a: (-Path(a["path"]).stat().st_mtime, a["path"]))
        out.append(group[0])
    return out

def maven_local_root() -> Path:
    return Path.home() / ".m2" / "repository"

def _sha1_of(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        while True:
            chunk = f.read(8192)
            if not chunk: break
            h.update(chunk)
    return h.hexdigest()

def scan_maven_local(root: Path | None = None) -> list[dict]:
    """
    Scan ~/.m2/repository/. Returns artifacts in the same dict shape as
    scan_cache(). Heuristic for version-dir: any file matching <parent>-<this>.*.
    SHA-1 is computed from file bytes (gradle's content-address scheme — the
    sha-dir name in caches/modules-2/files-2.1 IS this same digest).
    """
    if root is None:
        root = maven_local_root()
    if not root.is_dir(): return []
    out = []
    stack = [root]
    while stack:
        d = stack.pop()
        try:
            entries = list(d.iterdir())
        except OSError:
            continue
        files = [e for e in entries if e.is_file()
                 and not e.name.startswith("_")
                 and not e.name.endswith(".lock")]
        parent_name = d.parent.name if d.parent != d else ""
        prefix = f"{parent_name}-{d.name}"
        is_version_dir = (
            parent_name and
            any(f.stem.startswith(prefix) for f in files)
        )
        if is_version_dir and d.parent.parent != d.parent:
            group_dir = d.parent.parent
            try:
                rel = group_dir.relative_to(root).as_posix().strip("/")
            except ValueError:
                continue
            if not rel:
                continue
            group = rel.replace("/", ".")
            name = parent_name
            version = d.name
            for f in files:
                out.append({
                    "group": group,
                    "name": name,
                    "version": version,
                    "sha1": _sha1_of(f),
                    "file": f.name,
                    "path": str(f),
                    "size": f.stat().st_size,
                })
            # do not descend into version dir
        else:
            for e in entries:
                if e.is_dir():
                    stack.append(e)
    return out

def maven_local_relpath(group: str, name: str, version: str, file_name: str) -> str:
    return f"{group.replace('.', '/')}/{name}/{version}/{file_name}"

def _read_root_project_name(project_dir: Path) -> str:
    for fname in ("settings.gradle.kts", "settings.gradle"):
        f = project_dir / fname
        if not f.is_file():
            continue
        m = __import__("re").search(
            r"""rootProject\.name\s*=\s*["']([^"']+)["']""",
            f.read_text(encoding="utf-8", errors="replace"),
        )
        if m:
            return m.group(1)
    return ""

def _detect_java_home() -> str | None:
    candidates = [
        os.environ.get("JAVA_HOME"),
        cfg("JAVA_HOME"),
        r"C:\Users\Mind\.jdks\openjdk-21",
        r"C:\Users\Mind\.jdks\ms-21.0.10",
        r"D:\SDKs\Java\temurin-23.0.2",
    ]
    for c in candidates:
        if not c:
            continue
        # unwrap accidental \bin suffix
        if Path(c).name.lower() == "bin":
            c = str(Path(c).parent)
        if (Path(c) / "bin" / ("java.exe" if os.name == "nt" else "java")).is_dir() or \
           (Path(c) / "bin" / ("java.exe" if os.name == "nt" else "java")).is_file():
            return c
    return None

def _run_gradle_init(project: Path, java_home: str | None) -> tuple[Path, str, int]:
    """Drop init-script, run gradlew --offline help, return (jsonl_path, stdout, exit)."""
    import tempfile, subprocess
    out_file = Path(tempfile.mktemp(prefix="lgm-missing-", suffix=".jsonl"))
    init_file = Path(tempfile.mktemp(prefix="lgm-init-", suffix=".gradle"))
    init_file.write_text(
        _GRADLE_INIT_SCRIPT.replace("__OUT__", str(out_file).replace("\\", "/")),
        encoding="utf-8",
    )
    is_win = os.name == "nt"
    wrapper = project / ("gradlew.bat" if is_win else "gradlew")
    cmd = [str(wrapper) if wrapper.exists() else ("gradle.bat" if is_win else "gradle"),
           "--init-script", str(init_file),
           "-q", "--no-daemon", "--offline", "help"]
    env = os.environ.copy()
    if java_home:
        env["JAVA_HOME"] = java_home
        env["JDK_HOME"] = java_home
    try:
        proc = subprocess.run(cmd, cwd=str(project), env=env,
                              capture_output=True, text=True, timeout=600)
        return out_file, (proc.stdout or "") + (proc.stderr or ""), proc.returncode
    finally:
        try: init_file.unlink()
        except Exception: pass

def cmd_request(args):
    """Build a minimum-traffic deps request (manifest v3) and post it to Mirror."""
    import re as _re
    base     = args.base_url or cfg("BASE_URL", "https://localhost:443")
    key      = args.api_key  or cfg("API_KEY")
    password = args.password or cfg("SYNC_PASSWORD")
    repo     = args.repo     or "onyx-platform"
    if not password:
        sys.exit("SYNC_PASSWORD not set in env or .env")

    project = Path(args.project).resolve()
    if not project.is_dir():
        sys.exit(f"project not found: {project}")

    # Detect whether this is a gradle project. For npm-only projects (no gradle
    # build files) we skip the gradle init + cache scan entirely — it's
    # irrelevant and the local cache scan can be very slow / hang.
    _gradle_markers = ("build.gradle", "build.gradle.kts", "settings.gradle",
                       "settings.gradle.kts", "gradlew", "gradlew.bat")
    is_gradle = any((project / m).exists() for m in _gradle_markers)

    # ── Step 1: ask gradle what's missing ──────────────────────────────────
    print(f"\n=== Resolving missing deps for {project.name} ===")
    if is_gradle:
        java_home = _detect_java_home()
        print(f"  JAVA_HOME = {java_home or '(not detected)'}")
        out_file, full_stdout, exit_code = _run_gradle_init(project, java_home)
    else:
        print("  no gradle build files — skipping gradle scan (npm-only project)")
        out_file, full_stdout, exit_code = None, "", 0

    raw = []
    guh = None
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
        out_file.unlink(missing_ok=True)

    # Stdout fallback: gradle prints exact coordinates whenever buildscript
    # resolution fails before our afterEvaluate hook runs. Two formats:
    #   "No cached version of <g>:<n>:<v> available for offline mode"
    #   "Could not download <file>.jar (<g>:<n>:<v>): No cached version available"
    # We extract <g>:<n>:<v> from each, skip *.gradle.plugin markers.
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

    # dedup by g:n:v
    seen = set()
    raw_missing = []
    for o in raw:
        k = f"{o.get('g','')}:{o.get('n','')}:{o.get('v','')}"
        if k in seen:
            continue
        seen.add(k)
        raw_missing.append(o)
    print(f"  init-script + stdout fallback: {len(raw_missing)} candidate coord(s)")
    print(f"  gradle user home (reported) = {guh or '(unknown)'}")

    # ── Step 2: scan local cache + filter ──────────────────────────────────
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
    # Also include maven-local — must be in cached_coords so already-applied
    # artifacts don't get re-requested (and so a BOM with only a pom in
    # ~/.m2/repository is correctly recognised as already present).
    maven_arts = scan_maven_local() if is_gradle else []
    for a in maven_arts:
        art_key = f"{a['group']}:{a['name']}:{a['version']}/{a['sha1']}/{a['file']}"
        if art_key in seen_artifact_keys:
            continue
        seen_artifact_keys.add(art_key)
        all_artifacts.append(a)
    # Index every present file by coordinate, tracking which artifact KINDS we
    # hold. A coordinate is "satisfied" (don't re-request) only if we have the
    # real artifact gradle needs — NOT just any file. Having only the .pom of a
    # jar-packaged dependency does NOT satisfy it: gradle still fails with
    # "Could not find ...jar". (BOMs/parents are packaging=pom and ARE satisfied
    # by the pom alone.)
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
        # Real binary artifact present → satisfied.
        if kinds & {"jar", "aar", "klib"}:
            return True
        # Only pom/module/doc present → satisfied ONLY if the pom is itself the
        # artifact (packaging=pom: BOM, parent, platform). A jar-packaged coord
        # with just a pom is still missing its jar.
        pom = coord_pom.get(gnv)
        if pom and _pom_packaging(pom) in ("pom", "bom"):
            return True
        return False

    cached_coords = {gnv for gnv in coord_kinds if _coord_satisfied(gnv)}
    print(f"  scanned {len(cache_roots)} gradle cache root(s) + maven-local, "
          f"{len(all_artifacts)} artifact(s), {len(coord_kinds)} unique g:n:v, "
          f"{len(cached_coords)} fully satisfied")

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
    print(f"  after filter: {len(missing)} truly missing coord(s)")

    # ── npm ecosystem ───────────────────────────────────────────────────────
    # Parse the project's lockfile (yarn.lock / package-lock.json) for corporate
    # npm packages. If --npm-scopes is given, packages are matched by scope
    # (instant, no network). Otherwise each non-public candidate is probed
    # against registry.npmjs.org (slow: one HTTP call per package).
    npm_scopes = [s.strip() for s in (args.npm_scopes or "").split(",") if s.strip()]
    npm_missing = (
        _detect_npm_missing(project, npm_scopes)
        if (project / "package.json").is_file() else []
    )
    if npm_missing:
        for c in npm_missing[:10]:
            full = (c["group"] + "/" + c["name"]) if c["group"] else c["name"]
            print(f"    - {full}@{c['version']}")
        if len(npm_missing) > 10:
            print(f"    ... and {len(npm_missing) - 10} more")

    if not missing and not npm_missing:
        print("\n  Nothing to request — all dependencies resolve locally.")
        return
    for m in missing[:10]:
        print(f"    - {m['g']}:{m['n']}:{m['v']}")
    if len(missing) > 10:
        print(f"    ... and {len(missing) - 10} more")

    # ── Step 3: build present (DOME enumerates what it has) ────────────────
    # Same all_artifacts (gradle cache + maven-local) we built in Step 2.
    by_gnv: dict[str, list[dict]] = {}
    for a in all_artifacts:
        by_gnv.setdefault(f"{a['group']}:{a['name']}:{a['version']}", []).append(a)

    present = []
    seen_present_keys = set()
    for arts in by_gnv.values():
        for a in _pick_shipable(arts):
            present_key = f"{a['group']}:{a['name']}:{a['version']}:{a['sha1']}:{a['file']}"
            if present_key in seen_present_keys: continue
            seen_present_keys.add(present_key)
            present.append({
                "g": a["group"], "n": a["name"], "v": a["version"],
                "sha1": a["sha1"], "fileName": a["file"],
            })
    print(f"  present: {len(present)} file entries (deduped)")

    # ── Step 4: build manifest v3 ──────────────────────────────────────────
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
    print(f"  manifest size: {len(payload_json):,} bytes (plaintext json)")

    if args.dry_run:
        print("  --dry-run: not posting.")
        return

    # ── Step 5: encrypt + post ─────────────────────────────────────────────
    encrypted = encrypt_bundle(payload_json, password)
    print(f"  encrypted: {len(encrypted):,} bytes — posting to {base}…")
    print(f"  api_key present: {bool(key)} ({len(key)} chars)")

    res = api_post_multipart(
        base, "/api/deps/request", key,
        fields={"repo": repo},
        files={"attachment": ("manifest.bin", encrypted)},
    )
    if "_error" in res:
        # Retry once — we've seen sporadic 404s when the backend is busy.
        print(f"  first POST failed ({res}), retrying after 2s…")
        import time as _time
        _time.sleep(2)
        res = api_post_multipart(
            base, "/api/deps/request", key,
            fields={"repo": repo},
            files={"attachment": ("manifest.bin", encrypted)},
        )
    if "_error" in res:
        sys.exit(f"  POST failed: {res}")
    print(f"  OK: id={res.get('id', '?')[:12]} size={res.get('size', 0):,} bytes")
    print("\n  On the work machine: `python lgm.py respond` (or click "
          "'Выдать запрошенные зависимости' in IDEA).")


def _ensure_mavenlocal_init_script() -> bool:
    """Install ~/.gradle/init.d/lgm-mavenlocal-fallback.gradle if absent/outdated."""
    init_dir = Path.home() / ".gradle" / "init.d"
    init_dir.mkdir(parents=True, exist_ok=True)
    target = init_dir / "lgm-mavenlocal-fallback.gradle"
    expected = (
        "// LocalGitMirror v3 — auto-generated. Do not edit by hand: Mirror's apply\n"
        "// step rewrites this file when its content drifts from the plugin's copy.\n"
        "//\n"
        "// Makes artifacts unpacked into ~/.m2/repository (via 'Apply received deps')\n"
        "// resolvable from every gradle build without editing project files.\n"
        "//\n"
        "// We deliberately DO NOT touch settings.pluginManagement.repositories here.\n"
        "// Declaring any pluginManagement repository in an init script suppresses\n"
        "// gradle's implicit default (the plugin portal), which breaks projects that\n"
        "// rely on it (e.g. they declare no pluginManagement block of their own).\n"
        "// Projects that need mavenLocal() for plugin/marker resolution already\n"
        "// declare it in their own settings.gradle, so the init script only has to\n"
        "// cover project + buildscript dependency repositories.\n"
        "//\n"
        "// `beforeProject` runs before each project's build.gradle is evaluated, so a\n"
        "// buildscript {} classpath declared there sees mavenLocal() in its repo list.\n"
        "\n"
        "gradle.beforeProject { project ->\n"
        "    project.buildscript.repositories {\n"
        "        mavenLocal()\n"
        "    }\n"
        "    project.repositories {\n"
        "        mavenLocal()\n"
        "    }\n"
        "}\n"
    )
    if target.is_file() and target.read_text(encoding="utf-8") == expected:
        return False
    target.write_text(expected, encoding="utf-8")
    return True


def _maven_local_jars_without_poms() -> list[tuple[str, str, str, Path]]:
    """
    Walk ~/.m2/repository and find every <g>/<n>/<v>/<n>-<v>.jar where the
    sibling <n>-<v>.pom does NOT exist. Returns (group, name, version, jar_path).
    """
    out = []
    root = maven_local_root()
    if not root.is_dir():
        return out
    for f in root.rglob("*.jar"):
        if not f.is_file():
            continue
        n = f.stem
        if any(n.endswith(s) for s in ("-sources", "-javadoc", "-tests", "-test")):
            continue
        version_dir = f.parent
        name_dir = version_dir.parent
        if not name_dir or not name_dir.parent:
            continue
        try:
            rel = name_dir.parent.relative_to(root).as_posix().strip("/")
        except ValueError:
            continue
        if not rel:
            continue
        group = rel.replace("/", ".")
        name = name_dir.name
        version = version_dir.name
        if not n.startswith(f"{name}-{version}"):
            continue
        pom = version_dir / f"{name}-{version}.pom"
        if pom.is_file():
            continue
        out.append((group, name, version, f))
    return out


def cmd_fetch_poms(args):
    """
    For every <name>-<version>.jar under ~/.m2/repository that lacks a sibling
    .pom, download the pom from a public Maven repository (default Maven Central).
    Then walk every existing pom and recursively fetch any referenced parent
    pom that isn't on disk yet — gradle requires the full parent chain to
    resolve metadata in offline mode.

    Why: Mirror's response bundles ship jar+pom (sometimes only jar) for
    direct deps. But maven-local resolution also pulls each pom's <parent>
    coordinate and refuses if any link in the chain is missing. For PUBLIC
    libraries (jgit, spotless, …) we can fetch the full chain from Maven
    Central and avoid pinging Mirror for every parent. Private artifacts
    (e.g. ru.kryptonite.*) hit 404 and need to come via Mirror in the next
    round-trip.
    """
    repo_url = args.repo_url.rstrip("/")
    print(f"\n=== Maven Central pom fetch ===")
    print(f"  repo: {repo_url}")

    fetched_total = 0
    failed_total = 0
    skipped_private_total = 0

    for pass_no in (1, 2):
        if pass_no == 1:
            print(f"\n  pass {pass_no}: jars without poms")
            targets = _maven_local_jars_without_poms()
        else:
            print(f"\n  pass {pass_no}: missing parent poms (recursive)")
            targets = _missing_parent_poms()
        print(f"  candidates: {len(targets)}")

        # Pass 2 needs to keep going until parent chain converges.
        max_iters = 1 if pass_no == 1 else 8
        iter_no = 0
        while targets and iter_no < max_iters:
            iter_no += 1
            if pass_no == 2 and iter_no > 1:
                print(f"  iteration {iter_no} ({len(targets)} parents to resolve)")
            fetched, skipped_priv, failed = _fetch_pom_batch(repo_url, targets, args.dry_run)
            fetched_total += fetched
            skipped_private_total += skipped_priv
            failed_total += failed
            if pass_no == 1:
                break
            # Re-scan for newly-discovered parents
            targets = _missing_parent_poms()

    print(f"\n  TOTAL: fetched={fetched_total}  private/404={skipped_private_total}  failed={failed_total}")


def _pom_packaging(pom_path: str) -> str:
    """Return a pom's <packaging> (Maven default 'jar'), lowercased."""
    import xml.etree.ElementTree as ET
    try:
        rt = ET.parse(pom_path).getroot()
    except Exception:
        return "jar"
    for c in rt:
        if c.tag.endswith("}packaging") or c.tag == "packaging":
            return (c.text or "jar").strip().lower()
    return "jar"


def _parent_coord_of_pom(pom: "Path") -> tuple[str, str, str] | None:
    """Parse a .pom file's <parent> coordinate. Returns (g, n, v) or None."""
    import xml.etree.ElementTree as ET
    try:
        rt = ET.parse(pom).getroot()
    except Exception:
        return None
    parent = next((c for c in rt if c.tag.endswith("}parent") or c.tag == "parent"), None)
    if parent is None:
        return None
    def _t(name: str) -> str:
        for c in parent:
            if c.tag.endswith("}" + name) or c.tag == name:
                return (c.text or "").strip()
        return ""
    g, n, v = _t("groupId"), _t("artifactId"), _t("version")
    return (g, n, v) if (g and n and v) else None


def _ship_rel_for(a: dict) -> str:
    """Bundle entry path for an artifact dict (gradle sha-dir vs maven layout)."""
    if a.get("source") == "gradle":
        return f"gradle/{a['group']}/{a['name']}/{a['version']}/{a['sha1']}/{a['file']}"
    return f"gradle/{maven_local_relpath(a['group'], a['name'], a['version'], a['file'])}"


def _expand_parent_pom_closure(found: list[tuple[str, str]], by_gnv: dict) -> int:
    """
    Walk every shipped .pom for <parent> coords and add the parent's pom
    (recursively) from the local cache. Mutates `found` in place. Returns the
    number of parent poms added.

    Parent artifacts are pom-only (packaging=pom), so we ship just the .pom.
    Parents not in the local cache are skipped — those are public and the dome
    resolves them from Maven Central itself.
    """
    shipped_rel = {rel for rel, _ in found}
    queue = [path for rel, path in found if path.lower().endswith(".pom")]
    visited = set(queue)
    added = 0
    while queue:
        parent = _parent_coord_of_pom(Path(queue.pop()))
        if not parent:
            continue
        g, n, v = parent
        arts = by_gnv.get(f"{g}:{n}:{v}")
        if not arts:
            continue  # public parent → dome gets it from Maven Central
        for a in _pick_shipable(arts):
            if not a["file"].lower().endswith(".pom"):
                continue
            rel = _ship_rel_for(a)
            if rel not in shipped_rel:
                shipped_rel.add(rel)
                found.append((rel, a["path"]))
                added += 1
            if a["path"] not in visited:
                visited.add(a["path"])
                queue.append(a["path"])
    return added


def _missing_parent_poms() -> list[tuple[str, str, str, "Path"]]:
    """
    Scan every .pom under ~/.m2/repository for <parent> coords that don't have
    their own pom on disk. Returns [(group, name, version, anchor_path)] where
    anchor_path is just a Path used by _fetch_pom_batch to derive jar_path
    (we reuse the same code path; for parent poms there's no jar, so we only
    need the .pom written).
    """
    import xml.etree.ElementTree as ET
    out = []
    seen = set()
    root = maven_local_root()
    if not root.is_dir():
        return out
    for pom in root.rglob("*.pom"):
        if not pom.is_file():
            continue
        try:
            tree = ET.parse(pom)
        except Exception:
            continue
        rt = tree.getroot()
        # XML namespace varies; look for any <parent> child regardless of ns.
        parent = next((c for c in rt if c.tag.endswith("}parent") or c.tag == "parent"), None)
        if parent is None:
            continue
        def _t(name):
            for c in parent:
                if c.tag.endswith("}" + name) or c.tag == name:
                    return (c.text or "").strip()
            return ""
        g = _t("groupId"); n = _t("artifactId"); v = _t("version")
        if not (g and n and v):
            continue
        target_pom = root / g.replace(".", "/") / n / v / f"{n}-{v}.pom"
        if target_pom.is_file():
            continue
        key = f"{g}:{n}:{v}"
        if key in seen:
            continue
        seen.add(key)
        # Anchor a synthetic jar_path under the same parent dir so existing
        # download code writes <name>-<version>.pom alongside it.
        # The anchor doesn't have to exist; it's only used for path derivation.
        anchor = target_pom.parent / f"{n}-{v}.jar"
        out.append((g, n, v, anchor))
    return out


def _fetch_pom_batch(repo_url: str, targets, dry_run: bool) -> tuple[int, int, int]:
    fetched = skipped_private = failed = 0
    for group, name, version, jar_path in targets:
        group_url = group.replace(".", "/")
        url = f"{repo_url}/{group_url}/{name}/{version}/{name}-{version}.pom"
        target_pom = jar_path.parent / f"{name}-{version}.pom"
        if dry_run:
            print(f"  [dry] would fetch {group}:{name}:{version}.pom")
            continue
        try:
            target_pom.parent.mkdir(parents=True, exist_ok=True)
            req = urllib.request.Request(url, headers={"User-Agent": "lgm-cli/1.0"})
            with urllib.request.urlopen(req, timeout=10) as r:
                target_pom.write_bytes(r.read())
            fetched += 1
            print(f"  [ok ] {group}:{name}:{version}")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                skipped_private += 1
                print(f"  [404] {group}:{name}:{version}  (private — request via Mirror)")
            else:
                failed += 1
                print(f"  [HTTP {e.code}] {group}:{name}:{version}")
        except Exception as e:
            failed += 1
            print(f"  [ERR] {group}:{name}:{version}  {type(e).__name__}: {e}")
    return fetched, skipped_private, failed


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser(description="LocalGitMirror CLI")
    p.add_argument("--base-url",   default="", help="Mirror base URL (e.g. https://192.168.0.100:443)")
    p.add_argument("--api-key",    default="", help="API key (default from .env API_KEY)")
    p.add_argument("--password",   default="", help="Sync password (default from .env SYNC_PASSWORD)")
    p.add_argument("--repo",       default="onyx-platform")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("scan",    help="Scan local gradle cache")
    s.add_argument("--gradle-home", default="")
    s.add_argument("--filter",      default="", help="Group substring filter")
    s.add_argument("-v", "--verbose", action="store_true")

    sub.add_parser("pending", help="List pending requests on Mirror")

    rq = sub.add_parser("request", help="Build manifest v3 from a project and POST it to Mirror")
    rq.add_argument("--project", required=True, help="path to gradle project root (e.g. D:\\Sources\\kryptonit\\onyx-platform)")
    rq.add_argument("--npm-scopes", default="",
                    help="comma-separated npm scopes/packages to treat as corporate, "
                         "skipping the slow registry probe "
                         "(e.g. @krypto-ui,@krypto-sdk,krypto-cli)")
    rq.add_argument("--dry-run", action="store_true")

    r = sub.add_parser("respond", help="Find & ship requested deps from local cache")
    r.add_argument("--project", default="", help="project dir; if it has package-lock.json, it's bundled so DOME can npm-install offline")
    r.add_argument("--dry-run", action="store_true")

    a = sub.add_parser("apply", help="Download & unpack deps response")
    a.add_argument("--project", default="", help="project dir to write the received package-lock.json into (resolved rewritten to npmjs)")
    a.add_argument("--npm-install", action="store_true", help="after writing the lockfile, run `npm install --prefer-offline` in the project")
    a.add_argument("--yarn", action="store_true", help="set up a yarn (classic) offline-mirror from the corporate tarballs + wire .yarnrc/yarn.lock")
    a.add_argument("--yarn-install", action="store_true", help="like --yarn, then run `yarn install --frozen-lockfile` (npmjs registry)")
    a.add_argument("--dry-run", action="store_true")

    fp = sub.add_parser("fetch-poms",
        help="For every jar under ~/.m2/repository without a sibling .pom, "
             "fetch the pom from Maven Central. Lets gradle resolve transitive "
             "deps of public libraries that Mirror only shipped as jars.")
    fp.add_argument("--repo-url", default="https://repo.maven.apache.org/maven2",
                    help="public Maven repo to download poms from")
    fp.add_argument("--dry-run", action="store_true")

    d = sub.add_parser("debug", help="Full diagnostics")

    args = p.parse_args()

    if args.cmd == "scan":         cmd_scan(args)
    elif args.cmd == "pending":    cmd_pending(args)
    elif args.cmd == "request":    cmd_request(args)
    elif args.cmd == "respond":    cmd_respond(args)
    elif args.cmd == "apply":      cmd_apply(args)
    elif args.cmd == "fetch-poms": cmd_fetch_poms(args)
    elif args.cmd == "debug":      cmd_debug(args)

if __name__ == "__main__":
    main()
