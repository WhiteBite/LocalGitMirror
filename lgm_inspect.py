"""One-shot script: decode all pending manifests and show missing coords."""
import json, sys, tempfile, pathlib

# Import only the helpers we need, skip main()
import importlib.util, types

_spec = importlib.util.spec_from_file_location("lgm_mod", r"d:\Sources\WhiteBite\LocalGitMirror\lgm.py")
_mod  = importlib.util.module_from_spec(_spec)
# Patch out argparse.ArgumentParser.parse_args so main() never runs
import argparse as _ap
_orig_parse = _ap.ArgumentParser.parse_args
_ap.ArgumentParser.parse_args = lambda *a, **kw: None
try:
    _spec.loader.exec_module(_mod)
finally:
    _ap.ArgumentParser.parse_args = _orig_parse

cfg                   = _mod.cfg
api_get               = _mod.api_get
api_download          = _mod.api_download
decrypt_bundle        = _mod.decrypt_bundle
gradle_candidate_roots = _mod.gradle_candidate_roots
scan_cache            = _mod.scan_cache

base     = cfg("BASE_URL", "https://localhost:443")
key      = cfg("API_KEY")
repo     = "onyx-platform"
password = cfg("SYNC_PASSWORD")

pending = api_get(base, f"/api/deps/pending?repo={repo}", key)
items   = pending.get("items", [])
print(f"Total pending: {len(items)}\n")

all_missing = set()
for req in items:
    req_id = req["id"]
    tmp = pathlib.Path(tempfile.mktemp(suffix=".bin"))
    ok = api_download(base, f"/api/deps/manifest?repo={repo}&id={req_id}", key, tmp)
    if not ok:
        print(f"  {req_id[:12]}  [DOWNLOAD FAILED]")
        continue
    try:
        raw      = decrypt_bundle(tmp.read_bytes(), password)
        manifest = json.loads(raw)
        missing  = manifest.get("missing", [])
        coords   = [
            f"{m['group']}:{m['name']}:{m['version']}"
            for m in missing
            if m.get("ecosystem", "gradle") == "gradle"
        ]
        print(f"  {req_id[:12]}  v={manifest.get('version')}  missing={len(coords)}")
        for c in coords:
            print(f"    - {c}")
            all_missing.add(c)
    except Exception as e:
        print(f"  {req_id[:12]}  [ERROR: {e}]")
    finally:
        tmp.unlink(missing_ok=True)

print(f"\n=== Unique missing across all requests: {len(all_missing)} ===")
for c in sorted(all_missing):
    print(f"  {c}")

# Check which are in local cache
print("\n=== Cache coverage ===")
all_arts = []
for root in gradle_candidate_roots():
    all_arts.extend(scan_cache(root))
by_gnv = {f"{a['group']}:{a['name']}:{a['version']}" for a in all_arts}
for c in sorted(all_missing):
    hit = "[HIT ]" if c in by_gnv else "[MISS]"
    print(f"  {hit} {c}")
