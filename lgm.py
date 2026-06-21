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

    # collect from all caches
    all_arts = []
    roots = gradle_candidate_roots()
    for root in roots:
        all_arts.extend(scan_cache(root))
    by_gnv = {}
    for a in all_arts:
        k = f"{a['group']}:{a['name']}:{a['version']}"
        by_gnv.setdefault(k, []).append(a)

    found, not_found = [], []
    for coord in manifest["missing"]:
        eco = coord.get("ecosystem", "gradle")
        if eco != "gradle":
            continue
        k = f"{coord['group']}:{coord['name']}:{coord['version']}"
        arts = by_gnv.get(k)
        if arts:
            for a in arts:
                rel = f"gradle/{a['group']}/{a['name']}/{a['version']}/{a['sha1']}/{a['file']}"
                found.append((rel, a["path"]))
        else:
            not_found.append(k)

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
    with zipfile.ZipFile(__import__("io").BytesIO(raw)) as zf:
        for entry in zf.namelist():
            # strip ecosystem prefix (gradle/group/name/...)
            parts = entry.split("/", 1)
            rel = parts[1] if len(parts) == 2 else entry
            if ".." in rel or rel.startswith("/"):
                invalid += 1
                continue
            dest = target / rel
            if dest.exists() and dest.stat().st_size == zf.getinfo(entry).file_size:
                skipped += 1
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(zf.read(entry))
            installed += 1

    print(f"  installed={installed}  skipped={skipped}  invalid={invalid}")

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

    # ── Step 1: ask gradle what's missing ──────────────────────────────────
    print(f"\n=== Resolving missing deps for {project.name} ===")
    java_home = _detect_java_home()
    print(f"  JAVA_HOME = {java_home or '(not detected)'}")
    out_file, full_stdout, exit_code = _run_gradle_init(project, java_home)

    raw = []
    guh = None
    if out_file.exists():
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
    maven_arts = scan_maven_local()
    for a in maven_arts:
        art_key = f"{a['group']}:{a['name']}:{a['version']}/{a['sha1']}/{a['file']}"
        if art_key in seen_artifact_keys:
            continue
        seen_artifact_keys.add(art_key)
        all_artifacts.append(a)
    cached_coords = {f"{a['group']}:{a['name']}:{a['version']}" for a in all_artifacts}
    print(f"  scanned {len(cache_roots)} gradle cache root(s) + maven-local, "
          f"{len(all_artifacts)} artifact(s), {len(cached_coords)} unique g:n:v")

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
    if not missing:
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
    manifest = {
        "version": 3,
        "requester": os.environ.get("USERNAME") or os.environ.get("USER") or "lgm-cli",
        "project": repo,
        "ecosystem": "gradle",
        "missing": [
            {"ecosystem": "gradle", "group": m["g"], "name": m["n"], "version": m["v"], "classifier": ""}
            for m in missing
        ],
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
        "// LocalGitMirror v1 — auto-generated. Do not edit by hand: Mirror's apply\n"
        "// step rewrites this file when its content drifts from the plugin's copy.\n"
        "//\n"
        "// Adds mavenLocal() to every gradle build's repositories so artifacts\n"
        "// unpacked into ~/.m2/repository (e.g. via 'Apply received deps') are\n"
        "// resolvable in offline mode without modifying the project's build files.\n"
        "\n"
        "allprojects {\n"
        "    buildscript {\n"
        "        repositories {\n"
        "            mavenLocal()\n"
        "        }\n"
        "    }\n"
        "    repositories {\n"
        "        mavenLocal()\n"
        "    }\n"
        "}\n"
        "\n"
        "beforeSettings { settings ->\n"
        "    settings.pluginManagement.repositories {\n"
        "        mavenLocal()\n"
        "    }\n"
        "}\n"
    )
    if target.is_file() and target.read_text(encoding="utf-8") == expected:
        return False
    target.write_text(expected, encoding="utf-8")
    return True


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
    rq.add_argument("--dry-run", action="store_true")

    r = sub.add_parser("respond", help="Find & ship requested deps from local cache")
    r.add_argument("--dry-run", action="store_true")

    a = sub.add_parser("apply", help="Download & unpack deps response")
    a.add_argument("--dry-run", action="store_true")

    d = sub.add_parser("debug", help="Full diagnostics")

    args = p.parse_args()

    if args.cmd == "scan":       cmd_scan(args)
    elif args.cmd == "pending":  cmd_pending(args)
    elif args.cmd == "request":  cmd_request(args)
    elif args.cmd == "respond":  cmd_respond(args)
    elif args.cmd == "apply":    cmd_apply(args)
    elif args.cmd == "debug":    cmd_debug(args)

if __name__ == "__main__":
    main()
