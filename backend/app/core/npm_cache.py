"""
npm cache scanner for protected corporate packages.

Scans two sources:
1. npm's ``_cacache`` (``~/.npm/_cacache``) — content-addressed cache
   with index entries pointing to tarball blobs.
2. Yarn offline mirror (``~/.lgm-yarn-offline``) — flat directory of .tgz files.

Protected scopes are hardcoded for now (same reasoning as Maven groups):
the mirror exists for what the home machine cannot get from a public registry.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import tarfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from app.core.artifact_store import ArtifactStore

# ─────────────────────────────────────────────────────────────────────────────
# Protected npm scopes
# ─────────────────────────────────────────────────────────────────────────────

DEFAULT_PROTECTED_NPM_SCOPES: Tuple[str, ...] = ("@krypto-ui/", "@krypto-sdk/", "krypto-")


def is_protected_npm_package(name: str, scopes: Tuple[str, ...] = DEFAULT_PROTECTED_NPM_SCOPES) -> bool:
    return any(name.startswith(s) for s in scopes)


# ─────────────────────────────────────────────────────────────────────────────
# NpmArtifact
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class NpmArtifact:
    """A single npm package tarball ready for vault import."""

    name: str                 # "@krypto-ui/components"
    version: str              # "1.2.3"
    tarball_path: str         # absolute path to .tgz
    integrity: str            # "sha512-<base64>"
    shasum: str               # hex sha1 (npm legacy)
    packument: dict = field(default_factory=dict)  # full package metadata if available

    @property
    def package_spec(self) -> str:
        return f"{self.name}@{self.version}"


# ─────────────────────────────────────────────────────────────────────────────
# npm cache (_cacache) scanner
# ─────────────────────────────────────────────────────────────────────────────

_EXTRACT_PACKAGE_RE = re.compile(r"^pacote:(?:tarball|manifest):(?:https?://[^/]+/)?(.+)$")


def _extract_package_name(key: str) -> Optional[str]:
    """Extract package name from a pacote cache key.

    Examples:
        "pacote:tarball:@krypto-ui/components@1.2.3" -> "@krypto-ui/components"
        "pacote:manifest:krypto-common@2.0.0" -> "krypto-common"
        "made-up-request:https://..." -> None
    """
    m = _EXTRACT_PACKAGE_RE.match(key)
    if not m:
        return None
    raw = m.group(1)
    # Strip version suffix: "pkg@1.2.3" -> "pkg"
    if "@" in raw:
        # For scoped packages like "@scope/pkg@1.2.3", split on the LAST @
        last_at = raw.rfind("@")
        pkg = raw[:last_at]
        if not pkg:
            return None
        return pkg
    return raw


def _read_package_json_from_tarball(tarball_path: Path) -> Optional[dict]:
    """Read package.json from inside a .tgz tarball."""
    try:
        with tarfile.open(tarball_path, "r:gz") as tf:
            for member in tf.getmembers():
                if member.name.endswith("package.json") and not member.isdir():
                    # npm tarballs have "package/package.json" at the top
                    parts = member.name.split("/")
                    if len(parts) == 2 and parts[0] == "package" and parts[1] == "package.json":
                        f = tf.extractfile(member)
                        if f is None:
                            return None
                        return json.loads(f.read().decode("utf-8"))
        return None
    except (tarfile.TarError, OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None


def _compute_tarball_hashes(data: bytes) -> Tuple[str, str]:
    """Return (integrity, shasum) for tarball bytes.

    integrity is "sha512-<base64>" (standard npm format).
    shasum is hex sha1 (legacy npm format).
    """
    sha512 = hashlib.sha512(data).digest()
    sha1 = hashlib.sha1(data).hexdigest()
    return f"sha512-{base64.b64encode(sha512).decode('ascii')}", sha1


def scan_npm_cache(
    cache_root: Path,
    protected_scopes: Tuple[str, ...] = DEFAULT_PROTECTED_NPM_SCOPES,
) -> List[NpmArtifact]:
    """Scan npm ``_cacache`` for protected packages.

    npm cache structure::

        _cacache/
          index-v5/<sha1-prefix(2)>/<sha1-hex>  — metadata entries (JSON)
          content-v2/sha512/<prefix(2)>/<hash>   — actual tarballs

    For each index entry:
    1. Read JSON metadata: {key: "pacote:tarball:...", integrity: "sha512-...", ...}
    2. Extract package name from key
    3. Filter by protected_scopes
    4. Locate content blob by integrity hash
    5. Read package.json from tarball to verify name/version
    6. Return NpmArtifact

    Returns list of NpmArtifact, deduplicated by (name, version).
    """
    index_dir = cache_root / "index-v5"
    content_dir = cache_root / "content-v2" / "sha512"

    if not index_dir.is_dir():
        return []

    seen: Dict[str, NpmArtifact] = {}
    artifacts: List[NpmArtifact] = []

    for prefix_dir in index_dir.iterdir():
        if not prefix_dir.is_dir() or len(prefix_dir.name) != 2:
            continue
        for entry_file in prefix_dir.iterdir():
            if not entry_file.is_file():
                continue
            try:
                entry = json.loads(entry_file.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError, UnicodeDecodeError):
                continue

            key = entry.get("key", "")
            pkg_name = _extract_package_name(key)
            if pkg_name is None:
                continue
            if not is_protected_npm_package(pkg_name, protected_scopes):
                continue

            integrity = entry.get("integrity", "")
            if not integrity:
                continue

            # Locate content blob in content-v2/sha512
            tarball_path = _resolve_content_path(content_dir, integrity)
            if tarball_path is None:
                continue

            # Verify by reading package.json from tarball
            pj = _read_package_json_from_tarball(tarball_path)
            if pj is None:
                continue

            version = pj.get("version", "")
            actual_name = pj.get("name", "")
            if not version or actual_name != pkg_name:
                continue

            spec = f"{pkg_name}@{version}"
            if spec in seen:
                continue

            tarball_bytes = tarball_path.read_bytes()
            integ, shasum = _compute_tarball_hashes(tarball_bytes)

            artifact = NpmArtifact(
                name=pkg_name,
                version=version,
                tarball_path=str(tarball_path),
                integrity=integrity or integ,
                shasum=shasum,
                packument=pj,
            )
            seen[spec] = artifact
            artifacts.append(artifact)

    return artifacts


def _resolve_content_path(content_dir: Path, integrity: str) -> Optional[Path]:
    """Resolve an integrity string to a content blob path in content-v2.

    npm content-v2 uses sha512 hashes. The integrity string is "sha512-<base64>".
    """
    if not integrity.startswith("sha512-"):
        return None
    b64 = integrity[len("sha512-"):]
    try:
        digest = base64.b64decode(b64)
    except Exception:
        return None
    hex_digest = digest.hex()
    if hex_digest is None:
        return None
    # content-v2/sha512/<prefix(2)>/<full-hex>
    subdir = content_dir / hex_digest[:2]
    if not subdir.is_dir():
        return None
    for candidate in subdir.iterdir():
        if candidate.is_file() and candidate.name == hex_digest:
            return candidate
    return None


# ─────────────────────────────────────────────────────────────────────────────
# Yarn offline mirror scanner
# ─────────────────────────────────────────────────────────────────────────────

# Yarn offline mirror filenames: <name>-<version>.tgz
# For scoped packages: @scope-pkg-1.0.0.tgz (the "/" becomes "-")
_YARN_OFFLINE_RE = re.compile(
    r"^(@[^@]+?)-(.+?)\.tgz$"
)


def scan_npm_offline_mirror(
    mirror_root: Path,
    protected_scopes: Tuple[str, ...] = DEFAULT_PROTECTED_NPM_SCOPES,
) -> List[NpmArtifact]:
    """Scan yarn offline mirror for protected tarballs.

    Yarn offline mirror: flat directory of .tgz files named ``<name>-<version>.tgz``.
    For scoped packages, ``@scope/name-1.0.0.tgz`` becomes ``@scope-name-1.0.0.tgz``.

    Parse filename to extract name/version, verify by reading package.json inside.
    """
    if not mirror_root.is_dir():
        return []

    artifacts: List[NpmArtifact] = []
    seen: Dict[str, NpmArtifact] = {}

    for tgz_file in sorted(mirror_root.iterdir()):
        if not tgz_file.is_file() or not tgz_file.name.endswith(".tgz"):
            continue

        # Read package.json to get actual name and version
        pj = _read_package_json_from_tarball(tgz_file)
        if pj is None:
            continue

        pkg_name = pj.get("name", "")
        version = pj.get("version", "")
        if not pkg_name or not version:
            continue

        if not is_protected_npm_package(pkg_name, protected_scopes):
            continue

        spec = f"{pkg_name}@{version}"
        if spec in seen:
            continue

        tarball_bytes = tgz_file.read_bytes()
        integrity, shasum = _compute_tarball_hashes(tarball_bytes)

        artifact = NpmArtifact(
            name=pkg_name,
            version=version,
            tarball_path=str(tgz_file),
            integrity=integrity,
            shasum=shasum,
            packument=pj,
        )
        seen[spec] = artifact
        artifacts.append(artifact)

    return artifacts


# ─────────────────────────────────────────────────────────────────────────────
# npm index helpers
# ─────────────────────────────────────────────────────────────────────────────

def load_npm_index(vault_root: Path) -> dict:
    """Load npm index from vault, or return empty dict."""
    index_path = vault_root / "npm-index.json"
    try:
        return json.loads(index_path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}


def save_npm_index(vault_root: Path, index: dict) -> None:
    """Atomically save npm index."""
    index_path = vault_root / "npm-index.json"
    tmp = index_path.with_suffix(index_path.suffix + ".tmp")
    tmp.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, index_path)


def add_to_npm_index(vault_root: Path, artifact: NpmArtifact, sha256: str) -> None:
    """Add an artifact to the npm index.

    Index format::

        {"@krypto-ui/components": {"1.2.3": {"tarball_sha256": "...", "integrity": "...", "shasum": "..."}}}
    """
    index = load_npm_index(vault_root)
    pkg_entry = index.setdefault(artifact.name, {})
    pkg_entry[artifact.version] = {
        "tarball_sha256": sha256,
        "integrity": artifact.integrity,
        "shasum": artifact.shasum,
    }
    save_npm_index(vault_root, index)


def build_packument(package_name: str, index: dict) -> Optional[dict]:
    """Build a minimal npm packument from vault index.

    Returns JSON-serializable dict with versions, dist-tags, and tarball URLs
    pointing to localhost.
    """
    pkg_versions = index.get(package_name)
    if not pkg_versions:
        return None

    versions = sorted(pkg_versions.keys())
    latest = versions[-1]

    versions_data = {}
    for ver in versions:
        info = pkg_versions[ver]
        versions_data[ver] = {
            "name": package_name,
            "version": ver,
            "dist": {
                "shasum": info.get("shasum", ""),
                "integrity": info.get("integrity", ""),
                "tarball": f"http://localhost:8000/api/deps/npm/{package_name}/-/{package_name.replace('/', '%2f')}-{ver}.tgz",
            },
        }

    return {
        "name": package_name,
        "dist-tags": {"latest": latest},
        "versions": versions_data,
        "time": {ver: "" for ver in versions},
    }


# ─────────────────────────────────────────────────────────────────────────────
# Yarn v1 offline mirror projection
# ─────────────────────────────────────────────────────────────────────────────

def build_yarn_projection(vault_root: Path, projection_dir: Path, index: dict) -> int:
    """Build yarn v1 offline mirror from CAS.

    Yarn offline mirror is a flat directory of .tgz files.
    We only include corporate packages (from vault index), not public ones.

    Args:
        vault_root: path to vault (for CAS access)
        projection_dir: path to yarn projection (e.g. ~/.lgm-yarn-offline)
        index: npm index dict from load_npm_index()

    Returns:
        Number of tarballs written
    """
    projection_dir.mkdir(parents=True, exist_ok=True)

    # Clear old projection (safe — it's rebuildable)
    for old in projection_dir.glob("*.tgz"):
        old.unlink()

    count = 0
    store = ArtifactStore(vault_root)

    for pkg_name, versions in index.items():
        for version, info in versions.items():
            sha256 = info.get("tarball_sha256", "")
            if not sha256:
                continue

            tarball_path = store.cas_path(sha256)
            if not tarball_path.is_file():
                continue

            # Yarn expects filename: <name>-<version>.tgz (scope converted to dash)
            safe_name = pkg_name.replace("/", "-").replace("@", "")
            filename = f"{safe_name}-{version}.tgz"
            dest = projection_dir / filename

            # Hardlink if same volume, else copy
            try:
                if dest.exists():
                    dest.unlink()
                os.link(tarball_path, dest)
            except OSError:
                shutil.copy2(tarball_path, dest)

            count += 1

    return count