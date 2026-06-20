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
    return os.environ.get(key) or _ENV.get(key) or default

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

    # determine target cache root
    roots = gradle_candidate_roots()
    target = next((r for r in roots if r.is_dir()), roots[0] if roots else None)
    if target is None:
        sys.exit("No gradle cache root found")
    print(f"  Target cache: {target}")

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

    r = sub.add_parser("respond", help="Find & ship requested deps from local cache")
    r.add_argument("--dry-run", action="store_true")

    a = sub.add_parser("apply", help="Download & unpack deps response")
    a.add_argument("--dry-run", action="store_true")

    d = sub.add_parser("debug", help="Full diagnostics")

    args = p.parse_args()

    if args.cmd == "scan":       cmd_scan(args)
    elif args.cmd == "pending":  cmd_pending(args)
    elif args.cmd == "respond":  cmd_respond(args)
    elif args.cmd == "apply":    cmd_apply(args)
    elif args.cmd == "debug":    cmd_debug(args)

if __name__ == "__main__":
    main()
