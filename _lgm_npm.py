# lgm npm helpers -- auto-imported by lgm.py
import os, sys, urllib.request, urllib.error
from pathlib import Path

_PUBLIC_NPM_HOSTS = {"registry.npmjs.org", "registry.yarnpkg.com", "registry.npmmirror.com"}
_npm_probe_cache: dict = {}

def npm_cache_dir() -> Path:
	env_cache = os.environ.get("npm_config_cache")
	if env_cache:
		return Path(env_cache)
	if sys.platform == "win32":
		la = os.environ.get("LOCALAPPDATA")
		return Path(la) / "npm-cache" if la else Path.home() / "AppData/Local/npm-cache"
	return Path.home() / ".npm"

def npm_offline_mirror() -> Path:
	return Path.home() / ".lgm-npm-offline"

def _npm_is_public(url: str) -> bool:
	ns = url.split("://", 1)[-1] if "://" in url else url
	host = ns.split("/")[0].split("@")[-1].split(":")[0].lower()
	return any(host == h or host.endswith("." + h) for h in _PUBLIC_NPM_HOSTS)

def _npm_probe_public(name: str, version: str, timeout: int = 8) -> str:
	"""Classify a package@version against public npm.
	Returns 'available' (200), 'absent' (404 -> corporate), or 'unknown'.
	We do NOT send the 'install-v1' Accept header: on the /<name>/<version>
	endpoint some npm CDN edges answer it with 406, which we'd misread as
	'unknown' and over-classify as corporate. A plain GET is reliable.
	"""
	key = f"{name}@{version}"
	if key in _npm_probe_cache:
		return _npm_probe_cache[key]
	encoded = name.replace("/", "%2f")
	url = f"https://registry.npmjs.org/{encoded}/{version}"
	import ssl as _ssl
	ctx = _ssl.create_default_context()
	result = "unknown"
	for attempt in (1, 2):  # one retry on transient failure
		try:
			req = urllib.request.Request(url)
			with urllib.request.urlopen(req, context=ctx, timeout=timeout) as r:
				result = "available" if r.status == 200 else "unknown"
		except urllib.error.HTTPError as e:
			result = "absent" if e.code == 404 else "unknown"
		except Exception:
			result = "unknown"
		if result != "unknown":
			break
	_npm_probe_cache[key] = result
	return result

def _npm_split_scope(full: str):
	if full.startswith("@") and "/" in full:
		slash = full.index("/")
		return full[:slash], full[slash + 1:]
	return "", full

def _npm_mirror_rel(group: str, name: str, version: str) -> str:
	scope_dir = (group.lstrip("@") + "/") if group else ""
	return f"{scope_dir}{name}/{name}-{version}.tgz"

def parse_yarn_lock_for_npm(text: str) -> list:
	out: dict = {}
	raw = text.splitlines()
	i = 0
	while i < len(raw):
		line = raw[i]
		stripped = line.strip()
		is_hdr = line and line[0] not in (" ", "\t", "#") and stripped.endswith(":")
		if not is_hdr:
			i += 1
			continue
		first = stripped.rstrip(":").split(",")[0].strip().strip(chr(34))
		at = first.rfind("@")
		pkg = first[:at] if at > 0 else first
		ver = res = integ = ""
		j = i + 1
		while j < len(raw):
			bl = raw[j]
			if bl and bl[0] not in (" ", "\t"):
				break
			bt = bl.strip()
			if bt.startswith("version "):
				ver = bt.split(" ", 1)[1].strip().strip(chr(34))
			elif bt.startswith("resolved "):
				res = bt.split(" ", 1)[1].strip().strip(chr(34)).split("#")[0]
			elif bt.startswith("integrity "):
				integ = bt.split(" ", 1)[1].strip()
			j += 1
		if res and res.startswith("http") and not _npm_is_public(res) and pkg and ver:
			sc, nm = _npm_split_scope(pkg)
			k = f"{sc}:{nm}:{ver}:{integ}"
			out[k] = {"ecosystem": "npm", "group": sc, "name": nm, "version": ver, "classifier": integ}
		i = j
	return list(out.values())

def parse_npm_lockfile_for_npm(text: str) -> list:
	import json as _j
	out: dict = {}
	try:
		root = _j.loads(text)
	except Exception:
		return []
	def consider(pkg_path, obj):
		res = obj.get("resolved", "")
		if not res or not res.startswith("http") or _npm_is_public(res):
			return
		version = obj.get("version", "")
		integrity = obj.get("integrity", "")
		if not version:
			return
		idx = pkg_path.rfind("node_modules/")
		raw = pkg_path[idx + 13:] if idx >= 0 else pkg_path
		sc, nm = _npm_split_scope(raw)
		if not nm:
			return
		k = f"{sc}:{nm}:{version}:{integrity}"
		out[k] = {"ecosystem": "npm", "group": sc, "name": nm, "version": version, "classifier": integrity}
	for path, obj in root.get("packages", {}).items():
		if path and isinstance(obj, dict):
			consider(path, obj)
	def recurse(deps):
		for dn, obj in deps.items():
			if isinstance(obj, dict):
				consider(f"node_modules/{dn}", obj)
				recurse(obj.get("dependencies", {}))
	recurse(root.get("dependencies", {}))
	return list(out.values())

def npm_find_tarball(integrity: str, cache_root=None):
	if not integrity or "-" not in integrity:
		return None
	algo, b64 = integrity.split("-", 1)
	try:
		import base64 as _b64
		hex_str = _b64.b64decode(b64 + "==").hex()
	except Exception:
		return None
	if len(hex_str) < 6:
		return None
	if cache_root is None:
		cache_root = npm_cache_dir() / "_cacache" / "content-v2"
	p = Path(cache_root) / algo.lower() / hex_str[:2] / hex_str[2:4] / hex_str[4:]
	return p if p.is_file() else None

def _npm_matches_scope(coord, scopes):
	if not scopes:
		return False
	grp = coord.get("group", ""); nm = coord.get("name", "")
	full = grp + "/" + nm if grp else nm
	for raw in scopes:
		s = raw.strip()
		if s and (full == s or full.startswith(s) or grp == s):
			return True
	return False

def _detect_npm_missing(project, scopes=None) -> list:
	for fname, fn in [
		("yarn.lock", parse_yarn_lock_for_npm),
		("package-lock.json", parse_npm_lockfile_for_npm),
		("npm-shrinkwrap.json", parse_npm_lockfile_for_npm),
	]:
		candidate = Path(project) / fname
		if not candidate.is_file():
			continue
		print(f"  npm: parsing {fname}...")
		candidates = fn(candidate.read_text(encoding="utf-8", errors="replace"))

		# Explicit scope fast-path: instant, no network. Use when offline or
		# when you want to pin exactly which scopes are corporate.
		if scopes:
			corporate = [c for c in candidates if _npm_matches_scope(c, scopes)]
			print(f"  npm: {len(candidates)} candidate(s), {len(corporate)} match scopes {scopes} (no probe)")
			return corporate

		# Auto mode (mirrors the gradle 'what's missing' idea):
		#   1. cache-diff — drop everything already in the local npm cache;
		#      the home machine already has those tarballs.
		#   2. probe the remainder against public npm IN PARALLEL — a 404 means
		#      the package only lives in the corporate registry (request it);
		#      a 200 means it's public (the home machine fetches it itself).
		root = npm_cache_dir() / "_cacache" / "content-v2"
		absent = [c for c in candidates
		          if not (c.get("classifier") and npm_find_tarball(c["classifier"], root))]
		in_cache = len(candidates) - len(absent)
		print(f"  npm: {len(candidates)} locked, {in_cache} already in local cache, "
		      f"{len(absent)} absent -> probing public npm...")
		return _npm_probe_corporate(absent)
	return []


def _npm_probe_corporate(absent: list, max_workers: int = 24) -> list:
	"""Probe each candidate against public npm in parallel.
	404 -> corporate (return it). 200 -> public (skip). 'unknown' -> can't
	confirm public, so include it conservatively (better to over-ship than to
	break the build) and warn.
	"""
	if not absent:
		print("  npm: 0 corporate package(s) to request")
		return []
	from concurrent.futures import ThreadPoolExecutor

	def _full(c):
		g = c.get("group", ""); n = c.get("name", "")
		return (g + "/" + n) if g else n

	def _probe(c):
		return c, _npm_probe_public(_full(c), c["version"])

	corporate, unknown = [], []
	with ThreadPoolExecutor(max_workers=max_workers) as ex:
		for c, status in ex.map(_probe, absent):
			if status == "absent":
				corporate.append(c)
			elif status == "unknown":
				unknown.append(c)
	if unknown:
		# Couldn't reach / classify these. If almost everything is unknown the
		# machine likely has no public-npm access — tell the user to use
		# --npm-scopes. Otherwise include them to be safe.
		if len(unknown) > max(10, len(absent) // 2):
			print(f"  npm: WARNING {len(unknown)}/{len(absent)} packages could not be "
			      f"checked against public npm (no internet?). Consider --npm-scopes.")
		else:
			print(f"  npm: {len(unknown)} package(s) unconfirmed - including them to be safe")
		corporate.extend(unknown)
	print(f"  npm: {len(corporate)} corporate package(s) to request")
	return corporate
