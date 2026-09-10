"""Operation registry — the single source of truth for lgm CLI + MCP.

Each ``Op`` has a ``name``, ``summary``, typed ``params``, and a ``run(ctx, args)``
that returns a plain JSON-serializable dict.  The CLI (``lgm.py``) generates
one argparse subcommand per op; the MCP server (``lgm_mcp.py``) generates one
tool per op.  Adding a new capability = adding one ``Op`` here.

All helper functions (gradle/maven/npm scanning, pom fetching, etc.) are
moved from the original ``lgm.py`` so the registry is self-contained.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional

from .config import Config, cfg
from .client import MirrorClient, LgmError
from .crypto import encrypt_bundle, decrypt_bundle

# npm helpers — same import the original lgm.py uses.
from _lgm_npm import (  # type: ignore
    npm_cache_dir, npm_offline_mirror,
    npm_find_tarball, _detect_npm_missing, _npm_mirror_rel,
)


# ── Dataclasses ─────────────────────────────────────────────────────────────

@dataclass
class Param:
    """A single op parameter.

    ``type`` is one of "str", "int", "bool".  A "bool" param is a flag
    (``store_true`` in argparse).  ``required`` only applies to str/int.
    """
    name: str
    type: str  # "str" | "int" | "bool"
    default: Any = None
    help: str = ""
    required: bool = False

    def __post_init__(self):
        if self.type == "bool" and self.default is None:
            self.default = False
        if self.type == "str" and self.default is None:
            self.default = ""
        if self.type == "int" and self.default is None:
            self.default = 0


@dataclass(kw_only=True)
class Op:
    name: str
    summary: str
    params: list[Param] = field(default_factory=list)
    run: Callable[["Ctx", dict], dict] = field(repr=False)
    needs_client: bool = True


@dataclass
class Ctx:
    """Execution context passed to every ``Op.run``."""
    config: Config
    client: MirrorClient


# ── Gradle cache scanner (moved from lgm.py) ────────────────────────────────

def gradle_candidate_roots() -> list[Path]:
    """All plausible gradle cache roots, same logic as DepsScanner.kt."""
    sub = Path("caches") / "modules-2" / "files-2.1"
    homes = []
    for env_var in ("GRADLE_USER_HOME",):
        v = os.environ.get(env_var) or cfg(env_var)
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


# ── Maven local scanner (moved from lgm.py) ─────────────────────────────────

def maven_local_root() -> Path:
    return Path.home() / ".m2" / "repository"


def _sha1_of(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        while True:
            chunk = f.read(8192)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def scan_maven_local(root: Path | None = None) -> list[dict]:
    """Scan ~/.m2/repository/. Returns artifacts in the same dict shape as
    scan_cache(). Heuristic for version-dir: any file matching <parent>-<this>.*.
    """
    if root is None:
        root = maven_local_root()
    if not root.is_dir():
        return []
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
            parent_name
            and any(f.stem.startswith(prefix) for f in files)
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
        else:
            for e in entries:
                if e.is_dir():
                    stack.append(e)
    return out


def maven_local_relpath(group: str, name: str, version: str, file_name: str) -> str:
    return f"{group.replace('.', '/')}/{name}/{version}/{file_name}"


# ── Artifact classification (moved from lgm.py) ─────────────────────────────

_DOC_SUFFIXES = ("-sources.jar", "-javadoc.jar", "-tests.jar", "-test.jar")


def _classify_artifact(file_name: str) -> str | None:
    """None = drop. Otherwise returns a kind tag for grouping."""
    lower = file_name.lower()
    if any(lower.endswith(s) for s in _DOC_SUFFIXES):
        return None
    if lower.endswith(".jar"):
        return "jar"
    if lower.endswith(".pom"):
        return "pom"
    if lower.endswith(".module"):
        return "module"
    if lower.endswith(".aar"):
        return "aar"
    if lower.endswith(".klib"):
        return "klib"
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
        group.sort(key=lambda a: (-Path(a["path"]).stat().st_mtime, a["path"]))
        out.append(group[0])
    return out


def _ship_rel_for(a: dict) -> str:
    """Bundle entry path for an artifact dict (gradle sha-dir vs maven layout)."""
    if a.get("source") == "gradle":
        return f"gradle/{a['group']}/{a['name']}/{a['version']}/{a['sha1']}/{a['file']}"
    return f"gradle/{maven_local_relpath(a['group'], a['name'], a['version'], a['file'])}"


# ── Parent-pom closure (moved from lgm.py) ──────────────────────────────────

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


def _parent_coord_of_pom(pom: Path) -> tuple[str, str, str] | None:
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


def _expand_parent_pom_closure(found: list[tuple[str, str]], by_gnv: dict) -> int:
    """Walk every shipped .pom for <parent> coords and add the parent's pom
    (recursively) from the local cache. Mutates `found` in place."""
    shipped_rel = {rel for rel, _ in found}
    queue = [Path(path) for rel, path in found if path.lower().endswith(".pom")]
    visited = set(queue)
    added = 0
    while queue:
        parent = _parent_coord_of_pom(queue.pop())
        if not parent:
            continue
        g, n, v = parent
        arts = by_gnv.get(f"{g}:{n}:{v}")
        if not arts:
            continue
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
                queue.append(Path(a["path"]))
    return added


# ── Gradle init script (moved from lgm.py) ──────────────────────────────────

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


def _read_root_project_name(project_dir: Path) -> str:
    for fname in ("settings.gradle.kts", "settings.gradle"):
        f = project_dir / fname
        if not f.is_file():
            continue
        m = re.search(
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
        if Path(c).name.lower() == "bin":
            c = str(Path(c).parent)
        if (Path(c) / "bin" / ("java.exe" if os.name == "nt" else "java")).is_dir() or \
           (Path(c) / "bin" / ("java.exe" if os.name == "nt" else "java")).is_file():
            return c
    return None


def _run_gradle_init(project: Path, java_home: str | None) -> tuple[Path, str, int]:
    """Drop init-script, run gradlew --offline help, return (jsonl_path, stdout, exit)."""
    out_file = Path(tempfile.mktemp(prefix="tmp-", suffix=".jsonl"))
    init_file = Path(tempfile.mktemp(prefix="tmp-", suffix=".gradle"))
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
        try:
            init_file.unlink()
        except Exception:
            pass


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


# ── Pom fetching (moved from lgm.py) ─────────────────────────────────────────

def _maven_local_jars_without_poms() -> list[tuple[str, str, str, Path]]:
    """Walk ~/.m2/repository and find every jar without a sibling .pom."""
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


def _missing_parent_poms() -> list[tuple[str, str, str, Path]]:
    """Scan every .pom under ~/.m2/repository for <parent> coords missing on disk."""
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
        parent = next((c for c in rt if c.tag.endswith("}parent") or c.tag == "parent"), None)
        if parent is None:
            continue

        def _t(name):
            for c in parent:
                if c.tag.endswith("}" + name) or c.tag == name:
                    return (c.text or "").strip()
            return ""

        g = _t("groupId")
        n = _t("artifactId")
        v = _t("version")
        if not (g and n and v):
            continue
        target_pom = root / g.replace(".", "/") / n / v / f"{n}-{v}.pom"
        if target_pom.is_file():
            continue
        key = f"{g}:{n}:{v}"
        if key in seen:
            continue
        seen.add(key)
        anchor = target_pom.parent / f"{n}-{v}.jar"
        out.append((g, n, v, anchor))
    return out


def _fetch_pom_batch(repo_url: str, targets, dry_run: bool) -> tuple[int, int, int]:
    import urllib.request
    import urllib.error
    fetched = skipped_private = failed = 0
    for group, name, version, jar_path in targets:
        group_url = group.replace(".", "/")
        url = f"{repo_url}/{group_url}/{name}/{version}/{name}-{version}.pom"
        target_pom = jar_path.parent / f"{name}-{version}.pom"
        if dry_run:
            continue
        try:
            target_pom.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(url, timeout=10) as r:
                target_pom.write_bytes(r.read())
            fetched += 1
        except urllib.error.HTTPError as e:
            if e.code == 404:
                skipped_private += 1
            else:
                failed += 1
        except Exception:
            failed += 1
    return fetched, skipped_private, failed


# ── npm lockfile helpers (moved from lgm.py) ────────────────────────────────

def _npmrc_registries(project: Path) -> list:
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
    return name.replace("/", "-") + "-" + version + ".tgz"


def _read_tgz_name_version(tgz: Path):
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
    mirror.mkdir(parents=True, exist_ok=True)
    n = 0
    for tgz in tarballs_root.rglob("*.tgz"):
        nv = _read_tgz_name_version(tgz)
        if not nv or not nv[0] or not nv[1]:
            continue
        shutil.copyfile(tgz, mirror / _yarn_tarball_name(nv[0], nv[1]))
        n += 1
    return n


def _set_global_yarn_mirror(mirror: Path):
    yarnrc = Path.home() / ".yarnrc"
    existing = yarnrc.read_text(encoding="utf-8", errors="replace") if yarnrc.is_file() else ""
    keep = [ln for ln in existing.splitlines()
            if ln.strip() and not ln.strip().startswith("yarn-offline-mirror")]
    mp = str(mirror).replace("\\", "/")
    keep += [f'yarn-offline-mirror "{mp}"', 'yarn-offline-mirror-pruning false']
    yarnrc.write_text("\n".join(keep) + "\n", encoding="utf-8")


# ── Protected-groups scanners (for publish) ─────────────────────────────────

def scan_maven_local_protected(repo_root: Path, protected_groups: tuple) -> list[dict]:
    """Scan ~/.m2/repository for protected artifacts (full Maven identity)."""
    if not repo_root.is_dir():
        return []
    arts = []
    for g_path in repo_root.iterdir():
        if not g_path.is_dir():
            continue
        group = g_path.name.replace("/", ".")
        if not any(group == g or group.startswith(g + ".") for g in protected_groups):
            continue
        for a_path in g_path.iterdir():
            if not a_path.is_dir():
                continue
            artifact = a_path.name
            for v_path in a_path.iterdir():
                if not v_path.is_dir():
                    continue
                version = v_path.name
                for f in v_path.iterdir():
                    if not f.is_file():
                        continue
                    name = f.name
                    if name.startswith("maven-metadata") or name.endswith((".sha1", ".md5", ".sha256", ".sha512")):
                        continue
                    prefix = f"{artifact}-{version}"
                    if not name.startswith(prefix):
                        continue
                    remainder = name[len(prefix):]
                    if not remainder:
                        continue
                    if remainder.startswith("."):
                        classifier = ""
                        extension = remainder[1:]
                    elif remainder.startswith("-"):
                        rest = remainder[1:]
                        dot_pos = rest.rfind(".")
                        if dot_pos == -1:
                            continue
                        classifier = rest[:dot_pos]
                        extension = rest[dot_pos + 1:]
                    else:
                        continue
                    if not extension:
                        continue
                    arts.append({
                        "group": group, "artifact": artifact, "version": version,
                        "classifier": classifier, "extension": extension,
                        "path": str(f), "size": f.stat().st_size,
                    })
    return arts


def scan_gradle_cache_protected(cache_root: Path, protected_groups: tuple) -> list[dict]:
    """Scan Gradle files-2.1 for protected artifacts."""
    if not cache_root.is_dir():
        return []
    arts = []
    for g_dir in cache_root.iterdir():
        if not g_dir.is_dir():
            continue
        group = g_dir.name
        if not any(group == g or group.startswith(g + ".") for g in protected_groups):
            continue
        for n_dir in g_dir.iterdir():
            if not n_dir.is_dir():
                continue
            artifact = n_dir.name
            for v_dir in n_dir.iterdir():
                if not v_dir.is_dir():
                    continue
                version = v_dir.name
                for sha_dir in v_dir.iterdir():
                    if not sha_dir.is_dir():
                        continue
                    for f in sha_dir.iterdir():
                        if not f.is_file() or f.name.startswith("_") or f.name == ".lock":
                            continue
                        name = f.name
                        prefix = f"{artifact}-{version}"
                        if not name.startswith(prefix):
                            continue
                        remainder = name[len(prefix):]
                        if not remainder:
                            continue
                        if remainder.startswith("."):
                            classifier = ""
                            extension = remainder[1:]
                        elif remainder.startswith("-"):
                            rest = remainder[1:]
                            dot_pos = rest.rfind(".")
                            if dot_pos == -1:
                                continue
                            classifier = rest[:dot_pos]
                            extension = rest[dot_pos + 1:]
                        else:
                            continue
                        if not extension:
                            continue
                        arts.append({
                            "group": group, "artifact": artifact, "version": version,
                            "classifier": classifier, "extension": extension,
                            "path": str(f), "size": f.stat().st_size,
                        })
    return arts


def build_publication_zip(artifacts: list[dict]) -> tuple[bytes, dict]:
    """Build ZIP publication from artifact list. Returns (zip_bytes, manifest)."""
    buf = io.BytesIO()
    manifest = {"schema": 1, "maven": []}
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for art in artifacts:
            path = Path(art["path"])
            data = path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            group_path = art["group"].replace(".", "/")
            filename = f"{art['artifact']}-{art['version']}"
            if art["classifier"]:
                filename += f"-{art['classifier']}"
            filename += f".{art['extension']}"
            maven_path = f"{group_path}/{art['artifact']}/{art['version']}/{filename}"
            zip_entry = f"maven/{maven_path}"
            zf.writestr(zip_entry, data)
            manifest["maven"].append({
                "path": maven_path, "sha256": digest,
                "group": art["group"], "artifact": art["artifact"],
                "version": art["version"], "classifier": art["classifier"],
                "extension": art["extension"],
            })
        zf.writestr("manifest.json", json.dumps(manifest, indent=2))
    return buf.getvalue(), manifest


# ── Op run functions ─────────────────────────────────────────────────────────

def _client(ctx: Ctx) -> MirrorClient:
    """Get the client, ensuring sync_password is set."""
    c = ctx.client
    c.sync_password = ctx.config.sync_password
    return c


def _repo_arg(args: dict) -> str:
    """Resolve the mirror repo name; the legacy dead default is gone."""
    repo = (args.get("repo") or "").strip()
    if not repo:
        raise LgmError("config", "--repo is required (mirror repository name)")
    return repo


def _role_guess(caps: dict, repos: dict) -> str:
    """Guess whether this machine is 'work' or 'home' from capabilities/repos."""
    features = caps.get("sync", {}).get("features", {})
    repo_list = repos.get("repos", [])
    # Heuristic: if there are repos with content, likely home; else work.
    if features.get("v3"):
        return "home-or-work (v3)"
    if len(repo_list) > 1:
        return "home"
    return "work"


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
    repo = args.get("repo", "onyx-platform")
    r = c.deps_pending(repo)
    items = r.get("items", [])
    return {"repo": repo, "items": items, "count": len(items)}


def op_debug(ctx: Ctx, args: dict) -> dict:
    """Full diagnostics: env vars, cache roots, mirror connectivity."""
    c = _client(ctx)
    repo = args.get("repo", "onyx-platform")
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


def op_respond(ctx: Ctx, args: dict) -> dict:
    """Find requested coords in local cache and ship them to Mirror."""
    c = _client(ctx)
    repo = args.get("repo", "onyx-platform")
    project = args.get("project", "")
    dry_run = args.get("dry_run", False)
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")

    pending = c.deps_pending(repo)
    items = pending.get("items", [])
    if not items:
        return {"repo": repo, "message": "No pending requests.", "found": 0, "not_found": 0}
    req = items[0]
    req_id = req["id"]

    raw_manifest = c.deps_manifest(repo, req_id)
    manifest = json.loads(decrypt_bundle(raw_manifest, ctx.config.sync_password))

    if manifest.get("version", 0) < 2 or not manifest.get("missing"):
        return {"repo": repo, "request_id": req_id, "manifest": manifest,
                "message": "Empty or legacy manifest"}

    # collect from all caches
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
                "message": "Nothing to send."}

    if dry_run:
        return {"repo": repo, "request_id": req_id, "manifest": manifest,
                "found": len(found), "not_found": len(not_found), "not_found_items": not_found,
                "skipped_unshipable": skipped_unshipable, "added_parents": added_parents,
                "dry_run": True, "items": [{"rel": r, "path": p} for r, p in found]}

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
            "encrypted_size": len(encrypted), "response": res}


def op_apply(ctx: Ctx, args: dict) -> dict:
    """Download deps response and unpack into gradle cache."""
    c = _client(ctx)
    repo = args.get("repo", "onyx-platform")
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
    resp = items[0]
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
                "entry_count": len(names), "sample": names[:10]}

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
        "repo": repo, "response_id": resp_id,
        "installed": installed, "skipped": skipped, "invalid": invalid,
        "layout": layout_observed, "target": str(target),
    }

    # npm post-install
    npm_mirror = npm_offline_mirror()
    tgzs = sorted(npm_mirror.rglob("*.tgz")) if npm_mirror.is_dir() else []
    if tgzs:
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
            except Exception:
                npm_fail += 1
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
    repo = args.get("repo", "onyx-platform")
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
            # mavenLocal() resolves metadata from the pom; a bare jar without
            # pom/module leaves the coordinate unresolvable for Gradle.
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


def op_publish(ctx: Ctx, args: dict) -> dict:
    """Scan local caches for protected artifacts, encrypt, and publish to Mirror vault."""
    c = _client(ctx)
    dry_run = args.get("dry_run", False)
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")

    protected_raw = cfg("LGM_PROTECTED_MAVEN_GROUPS", "ru.kryptonite")
    protected_groups = tuple(g.strip() for g in protected_raw.split(",") if g.strip())

    m2_root = Path.home() / ".m2" / "repository"
    m2_arts = scan_maven_local_protected(m2_root, protected_groups)
    gradle_arts = []
    for root in gradle_candidate_roots():
        if root.is_dir():
            gradle_arts.extend(scan_gradle_cache_protected(root, protected_groups))

    seen = {}
    all_arts = []
    for art in m2_arts + gradle_arts:
        path = Path(art["path"])
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        key_tuple = (art["group"], art["artifact"], art["version"],
                     art["classifier"], art["extension"], digest)
        if key_tuple not in seen:
            seen[key_tuple] = True
            all_arts.append(art)

    if not all_arts:
        return {"message": "nothing to publish", "protected_groups": list(protected_groups)}

    if dry_run:
        return {"dry_run": True, "count": len(all_arts),
                "sample": all_arts[:20], "protected_groups": list(protected_groups)}

    zip_bytes, manifest = build_publication_zip(all_arts)
    encrypted = encrypt_bundle(zip_bytes, ctx.config.sync_password)
    res = c.vault_publish("lgm-cli", encrypted)
    return {"published": len(all_arts), "zip_size": len(zip_bytes),
            "encrypted_size": len(encrypted), "response": res,
            "protected_groups": list(protected_groups)}


# ── New ops ──────────────────────────────────────────────────────────────────

def op_status(ctx: Ctx, args: dict) -> dict:
    """Server capabilities + repos + role guess."""
    c = _client(ctx)
    caps = {}
    repos_r = {}
    try:
        caps = c.capabilities()
    except LgmError as e:
        caps = {"error": e.message, "code": e.code}
    try:
        repos_r = c.repos()
    except LgmError as e:
        repos_r = {"error": e.message, "code": e.code}
    role = _role_guess(caps, repos_r) if "error" not in caps else "unknown"
    return {"capabilities": caps, "repos": repos_r, "role_guess": role,
            "base_url": ctx.config.base_url}


def op_repos(ctx: Ctx, args: dict) -> dict:
    """List repos on the mirror."""
    c = _client(ctx)
    return c.repos()


def op_branches(ctx: Ctx, args: dict) -> dict:
    """List all branch tips on the mirror for a repo."""
    c = _client(ctx)
    repo = args.get("repo", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    return c.sync_refs(repo)


def send_branch(ctx: Ctx, repo: str, project: str, branch: str,
                dry_run: bool = False) -> dict:
    """Bundle a branch (or --all) from a local git project and upload it.

    Shared by the ``send`` op and ``mr_send`` (which fetches an MR branch from
    corporate GitLab first, then ships it to the mirror under its own name).
    """
    c = _client(ctx)
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")
    proj = Path(project).resolve()
    if not proj.is_dir():
        raise LgmError("config", f"project not found: {proj}")

    bundle_args = ["git", "bundle", "create"]
    with tempfile.TemporaryDirectory(prefix="tmp-") as tmp:
        bundle_path = Path(tmp) / "outgoing.bundle"
        if branch:
            cmd = bundle_args + [str(bundle_path), branch]
        else:
            cmd = bundle_args + [str(bundle_path), "--all"]
        proc = subprocess.run(cmd, cwd=str(proj), capture_output=True, text=True, timeout=300)
        if proc.returncode != 0:
            raise LgmError("git", proc.stderr.strip() or "git bundle create failed")
        bundle_bytes = bundle_path.read_bytes()

    if dry_run:
        return {"repo": repo, "branch": branch or "all",
                "bundle_size": len(bundle_bytes), "dry_run": True}

    res = c.sync_send(repo, bundle_bytes)
    return {"repo": repo, "branch": branch or "all",
            "bundle_size": len(bundle_bytes), "response": res}


def op_send(ctx: Ctx, args: dict) -> dict:
    """Create a git bundle locally and send it to the mirror."""
    repo = args.get("repo", "")
    branch = args.get("branch", "")
    project = args.get("project", "")
    dry_run = args.get("dry_run", False)
    if not repo:
        raise LgmError("config", "--repo is required")
    if not project:
        raise LgmError("config", "--project is required")
    return send_branch(ctx, repo, project, branch, dry_run)


def op_pull(ctx: Ctx, args: dict) -> dict:
    """Pull an encrypted git bundle from the mirror and fetch it locally."""
    c = _client(ctx)
    repo = args.get("repo", "")
    branch = args.get("branch", "")
    since = args.get("since", "")
    haves = args.get("haves", "")
    project = args.get("project", "")
    dry_run = args.get("dry_run", False)
    if not repo:
        raise LgmError("config", "--repo is required")
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")

    result = c.sync_pull(repo, branch=branch, since=since, haves=haves)
    status = result.get("status", "")
    head = result.get("head", "")
    dump = result.get("dump", b"")

    if not dump:
        return {"repo": repo, "branch": branch, "status": status,
                "head": head, "message": "No content to pull."}

    if dry_run:
        return {"repo": repo, "branch": branch, "status": status,
                "head": head, "dump_size": len(dump), "dry_run": True}

    bundle_bytes = decrypt_bundle(dump, ctx.config.sync_password)

    if not project:
        return {"repo": repo, "branch": branch, "status": status,
                "head": head, "bundle_size": len(bundle_bytes),
                "message": "Bundle decrypted. Pass --project to fetch into a repo."}

    proj = Path(project).resolve()
    if not proj.is_dir():
        raise LgmError("config", f"project not found: {proj}")
    with tempfile.TemporaryDirectory(prefix="tmp-") as tmp:
        bundle_path = Path(tmp) / "incoming.bundle"
        bundle_path.write_bytes(bundle_bytes)
        fetch_proc = subprocess.run(
            ["git", "fetch", str(bundle_path), "+refs/heads/*:refs/heads/*"],
            cwd=str(proj), capture_output=True, text=True, timeout=300,
        )
        return {"repo": repo, "branch": branch, "status": status,
                "head": head, "bundle_size": len(bundle_bytes),
                "fetch_exit": fetch_proc.returncode,
                "fetch_stderr": fetch_proc.stderr.strip()[:500] if fetch_proc.stderr else ""}


def op_deps_request(ctx: Ctx, args: dict) -> dict:
    """Post a pre-built encrypted manifest file to /api/documents/submit."""
    c = _client(ctx)
    repo = args.get("repo", "onyx-platform")
    manifest_path = args.get("manifest", "")
    if not manifest_path:
        raise LgmError("config", "--manifest is required (path to encrypted manifest)")
    p = Path(manifest_path)
    if not p.is_file():
        raise LgmError("config", f"manifest file not found: {p}")
    data = p.read_bytes()
    return c.deps_request(repo, data)


def op_vault_status(ctx: Ctx, args: dict) -> dict:
    """Vault diagnostics: inventory, conflicts, wanted."""
    c = _client(ctx)
    return c.vault_status()


# ── Branch management / GitLab MR ops ────────────────────────────────────────

def op_branch_delete(ctx: Ctx, args: dict) -> dict:
    """Delete one or more branches on the mirror (delete-ref per branch)."""
    c = _client(ctx)
    repo = args.get("repo", "")
    branches_raw = args.get("branches", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    branch_list = [b.strip() for b in branches_raw.split(",") if b.strip()]
    if not branch_list:
        raise LgmError("config", "--branches is required (comma-separated branch names)")

    deleted: list[str] = []
    failed: list[dict] = []
    for br in branch_list:
        try:
            res = c.delete_ref(repo, br)
            if res.get("success"):
                deleted.append(br)
            else:
                failed.append({"branch": br, "error": res.get("message", "unknown error")})
        except LgmError as e:
            failed.append({"branch": br, "error": e.message})
    return {
        "repo": repo,
        "deleted": deleted,
        "failed": failed,
        "message": f"deleted {len(deleted)}/{len(branch_list)} branch(es)",
    }


def op_prune(ctx: Ctx, args: dict) -> dict:
    """List (dry-run) or delete merged/stale branches on the mirror.

    Dry-run by default: without --apply the server only reports candidates.
    """
    c = _client(ctx)
    repo = args.get("repo", "")
    if not repo:
        raise LgmError("config", "--repo is required")
    bases = [b.strip() for b in (args.get("bases") or "").split(",") if b.strip()]
    keep = [k.strip() for k in (args.get("keep") or "").split(",") if k.strip()]
    try:
        older_days = int(args.get("older_days", 0) or 0)
    except (TypeError, ValueError):
        raise LgmError("config", "--older-days must be an integer") from None
    apply = bool(args.get("apply", False))
    return c.prune_branches(repo, bases=bases, older_days=older_days,
                            keep=keep, apply=apply)


def op_mr_list(ctx: Ctx, args: dict) -> dict:
    """List open GitLab merge requests (GITLAB_URL/TOKEN/PROJECT config)."""
    c = _client(ctx)
    mrs = c.gitlab_list_mrs()
    items = [
        {
            "iid": m.get("iid"),
            "title": m.get("title", ""),
            "source_branch": m.get("source_branch", ""),
            "updated_at": m.get("updated_at", ""),
        }
        for m in mrs
    ]
    return {"count": len(items), "items": items}


def op_mr_send(ctx: Ctx, args: dict) -> dict:
    """Fetch a GitLab MR branch into a local project and send it to the mirror.

    Source branch resolution: --iid (queried from GitLab) or --branch.
    The branch is fetched from origin, then bundled and uploaded so it lands
    on the mirror under its source_branch name.
    """
    c = _client(ctx)
    repo = args.get("repo", "")
    project = args.get("project", "")
    try:
        iid = int(args.get("iid", 0) or 0)
    except (TypeError, ValueError):
        raise LgmError("config", "--iid must be an integer") from None
    branch = (args.get("branch") or "").strip()
    if not repo:
        raise LgmError("config", "--repo is required")
    if not project:
        raise LgmError("config", "--project is required")
    proj = Path(project).resolve()
    if not proj.is_dir():
        raise LgmError("config", f"project not found: {proj}")

    # Resolve the MR source branch: prefer the MR itself, fall back to --branch.
    source_branch = ""
    if iid:
        mr = c.gitlab_get_mr(iid)
        source_branch = (mr.get("source_branch") or "").strip()
    if not source_branch:
        source_branch = branch
    if not source_branch:
        raise LgmError("config", "--iid or --branch is required to resolve the MR source branch")

    # Fetch the branch from origin (corporate GitLab) into the local project.
    fetch_proc = subprocess.run(
        ["git", "-C", str(proj), "fetch", "origin", source_branch],
        capture_output=True, text=True, timeout=300,
    )
    if fetch_proc.returncode != 0:
        raise LgmError(
            "git",
            fetch_proc.stderr.strip() or f"git fetch origin {source_branch} failed",
        )

    # `git fetch origin <b>` only writes FETCH_HEAD — make sure the branch ref
    # exists locally so the bundle carries it under refs/heads/<source_branch>.
    ref_check = subprocess.run(
        ["git", "-C", str(proj), "rev-parse", "--verify", f"refs/heads/{source_branch}"],
        capture_output=True, text=True, timeout=30,
    )
    if ref_check.returncode != 0:
        mk = subprocess.run(
            ["git", "-C", str(proj), "update-ref", f"refs/heads/{source_branch}", "FETCH_HEAD"],
            capture_output=True, text=True, timeout=30,
        )
        if mk.returncode != 0:
            raise LgmError(
                "git",
                mk.stderr.strip() or f"branch '{source_branch}' not found after fetch",
            )

    send_result = send_branch(ctx, repo, str(proj), source_branch)
    return {
        "repo": repo,
        "project": str(proj),
        "iid": iid or None,
        "source_branch": source_branch,
        "send": send_result,
    }


# ── REGISTRY ─────────────────────────────────────────────────────────────────

_GUIDE = """\
LocalGitMirror transfer guide (work PC <-> home PC via the stealth mirror).

CODE (branches/commits):
  1. branches repo=<name>          — see what the mirror has (names + SHAs).
  2. send repo=<name> project=<git root> [branch=<b>]   — bundle & upload (work side).
  3. pull repo=<name> [branch=<b>] [project=<git root>] — download & fetch (home side).
  Direct git push/pull between the machines does NOT exist; corporate GitLab is
  unreachable from home. Local git inside a workspace is fine.

CORPORATE DEPS (gradle/npm):
  HOME:  request project=<gradle root>   — build manifest of unresolvable deps, post it.
  WORK:  pending [repo=<name>]           — see incoming requests.
  WORK:  respond [project=<root>]        — ship requested artifacts from local cache/Nexus.
  HOME:  apply [project=<root>]          — unpack response into ~/.gradle / ~/.m2.
  Extras: scan (inspect local gradle cache), fetch-poms (public poms),
          publish / vault_status (corporate artifact vault).

DIAGNOSTICS: status, repos, debug.

Rules: never hardcode URLs/keys (config comes from .env next to lgm.py);
check `branches` before `pull`; all payloads are encrypted with the sync password.
"""


def op_guide(ctx: Ctx, args: dict) -> dict:
    return {"success": True, "guide": _GUIDE}


def op_mr_notes(ctx: Ctx, args: dict) -> dict:
    """Decrypt MR discussion notes (mr-notes/mr-!N.md) from the file postbox."""
    c = _client(ctx)
    repo = _repo_arg(args)
    lst = c.file_sync_list(repo)
    items = [i for i in (lst.get("items") or []) if str(i.get("path", "")).startswith("mr-notes/")]
    iid = int(args.get("iid") or 0)
    if iid:
        items = [i for i in items if str(i.get("path", "")).endswith(f"mr-!{iid}.md")]
    notes = []
    for i in items:
        blob = c.file_sync_fetch(repo, i["id"])
        try:
            plain = decrypt_bundle(blob, ctx.config.sync_password)
            notes.append({"path": i["path"], "markdown": plain.decode("utf-8")})
        except Exception:
            notes.append({"path": i["path"], "error": "decrypt failed: sync password mismatch?"})
    return {"success": True, "repo": repo, "notes": notes}


REGISTRY: list[Op] = [
    Op(
        name="scan",
        summary="Scan local gradle cache for cached artifacts.",
        params=[
            Param("gradle_home", "str", "", "Override gradle user home"),
            Param("filter", "str", "", "Group substring filter"),
            Param("verbose", "bool", False, "Show individual artifacts"),
        ],
        run=op_scan,
        needs_client=False,
    ),
    Op(
        name="pending",
        summary="List pending dependency requests on the Mirror server.",
        params=[Param("repo", "str", "", "Repository name")],
        run=op_pending,
    ),
    Op(
        name="request",
            summary="Build a manifest v3 from a project and POST it to Mirror.",
            params=[
                Param("repo", "str", "", "Mirror repository name", required=True),
                Param("project", "str", "", "Path to gradle project root", required=True),
            Param("npm_scopes", "str", "", "Comma-separated npm scopes to treat as corporate"),
            Param("dry_run", "bool", False, "Show what would be requested without posting"),
        ],
        run=op_request,
    ),
    Op(
        name="respond",
        summary="Find requested deps in local cache and ship them to Mirror.",
            params=[
                Param("repo", "str", "", "Mirror repository name", required=True),
                Param("project", "str", "", "Project dir (for package-lock.json bundling)"),
            Param("dry_run", "bool", False, "Show what would be sent without posting"),
        ],
        run=op_respond,
    ),
    Op(
        name="apply",
        summary="Download and unpack a deps response into the local cache.",
        params=[
                Param("repo", "str", "", "Mirror repository name", required=True),
                Param("project", "str", "", "Project dir to write package-lock.json into"),
            Param("npm_install", "bool", False, "Run npm install after writing lockfile"),
            Param("yarn", "bool", False, "Set up yarn offline-mirror from corporate tarballs"),
            Param("yarn_install", "bool", False, "Like --yarn, then run yarn install --offline"),
            Param("dry_run", "bool", False, "Show what would be installed without writing"),
        ],
        run=op_apply,
    ),
    Op(
        name="fetch-poms",
        summary="Fetch missing poms from a public Maven repository.",
        params=[
            Param("repo_url", "str", "https://repo.maven.apache.org/maven2", "Public Maven repo URL"),
            Param("dry_run", "bool", False, "Show what would be fetched without downloading"),
        ],
        run=op_fetch_poms,
        needs_client=False,
    ),
    Op(
        name="publish",
        summary="Scan local caches for protected artifacts, encrypt, and publish to Mirror vault.",
        params=[Param("dry_run", "bool", False, "Show what would be published without sending")],
        run=op_publish,
    ),
    Op(
        name="debug",
        summary="Full diagnostics: env vars, cache roots, mirror connectivity.",
        params=[Param("repo", "str", "", "Repository name")],
        run=op_debug,
    ),
    # ── New ops ──────────────────────────────────────────────────────────
    Op(
        name="status",
        summary="Server capabilities + repos + role guess.",
        params=[],
        run=op_status,
    ),
    Op(
        name="repos",
        summary="List repositories on the mirror.",
        params=[],
        run=op_repos,
    ),
    Op(
        name="branches",
        summary="List all branch tips on the mirror for a repo.",
        params=[Param("repo", "str", "", "Repository name", required=True)],
        run=op_branches,
    ),
    Op(
        name="send",
        summary="Create a git bundle locally and send it to the mirror.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("branch", "str", "", "Branch to bundle (default: --all)"),
            Param("project", "str", "", "Path to git project root", required=True),
            Param("dry_run", "bool", False, "Create bundle without sending"),
        ],
        run=op_send,
    ),
    Op(
        name="pull",
        summary="Pull an encrypted git bundle from the mirror and fetch it locally.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("branch", "str", "", "Branch to pull"),
            Param("since", "str", "", "Exclude commits reachable from this SHA"),
            Param("haves", "str", "", "Comma-separated SHAs the client already has"),
            Param("project", "str", "", "Path to git project to fetch into"),
            Param("dry_run", "bool", False, "Download bundle without fetching"),
        ],
        run=op_pull,
    ),
    Op(
        name="deps_request",
        summary="Post a pre-built encrypted manifest file to /api/documents/submit.",
        params=[
            Param("repo", "str", "", "Repository name"),
            Param("manifest", "str", "", "Path to encrypted manifest file", required=True),
        ],
        run=op_deps_request,
    ),
    Op(
        name="vault_status",
        summary="Vault diagnostics: inventory, conflicts, wanted positions.",
        params=[],
        run=op_vault_status,
    ),
    Op(
        name="branch_delete",
        summary="Delete one or more branches on the mirror (comma-separated).",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("branches", "str", "", "Comma-separated branch names to delete", required=True),
        ],
        run=op_branch_delete,
    ),
    Op(
        name="prune",
        summary="List (dry-run) or delete merged/stale branches on the mirror.",
        params=[
            Param("repo", "str", "", "Repository name", required=True),
            Param("bases", "str", "master,develop,plan_fix",
                  "Comma-separated base branches (a branch merged into any base is prunable)"),
            Param("older_days", "int", 0,
                  "Also prune branches older than N days by committerdate (0=off)"),
            Param("keep", "str", "",
                  "Comma-separated branches to always keep (default: master,develop,plan_fix)"),
            Param("apply", "bool", False,
                  "Actually delete candidates (default: dry-run listing)"),
        ],
        run=op_prune,
    ),
    Op(
        name="mr_list",
        summary="List open GitLab merge requests (needs GITLAB_URL/TOKEN/PROJECT config).",
        params=[],
        run=op_mr_list,
    ),
    Op(
        name="mr_send",
        summary="Fetch a GitLab MR branch into a local project and send it to the mirror.",
        params=[
            Param("iid", "int", 0, "GitLab MR iid (resolves the source branch)"),
            Param("branch", "str", "", "MR source branch (used when --iid is 0)"),
            Param("project", "str", "", "Local git root to fetch into", required=True),
            Param("repo", "str", "", "Mirror repository name", required=True),
        ],
        run=op_mr_send,
    ),
    Op(
        name="mr_notes",
        summary="Decrypt and show MR discussion notes stored on the mirror (mr-notes/mr-!N.md).",
        params=[
            Param("repo", "str", "", "Mirror repository name", required=True),
            Param("iid", "int", 0, "Only this MR iid (0 = all)"),
        ],
        run=op_mr_notes,
    ),
    Op(
        name="guide",
        summary="How to transfer code and corporate deps between work and home via the Mirror.",
        params=[],
        run=op_guide,
        needs_client=False,
    ),
]


def get_op(name: str) -> Op | None:
    """Find an op by name (case-insensitive)."""
    for op in REGISTRY:
        if op.name == name:
            return op
    return None


def op_names() -> list[str]:
    return [op.name for op in REGISTRY]
