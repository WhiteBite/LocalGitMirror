from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from .config import cfg


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


_sha1_memo: dict[tuple[str, int, int], str] = {}
_SHA1_MEMO_MAX = 8192


def _sha1_of(path: Path) -> str:
    """File SHA-1, memoized by (path, size, mtime) for repeated scans."""
    st = path.stat()
    key = (str(path), st.st_size, st.st_mtime_ns)
    cached = _sha1_memo.get(key)
    if cached is not None:
        return cached
    h = hashlib.sha1()
    with path.open("rb") as f:
        while True:
            chunk = f.read(8192)
            if not chunk:
                break
            h.update(chunk)
    digest = h.hexdigest()
    if len(_sha1_memo) >= _SHA1_MEMO_MAX:
        _sha1_memo.clear()
    _sha1_memo[key] = digest
    return digest


def scan_maven_local(root: Path | None = None, with_sha1: bool = True) -> list[dict]:
    """Scan ~/.m2/repository/. Returns artifacts in the same dict shape as
    scan_cache(). Heuristic for version-dir: any file matching <parent>-<this>.*.
    ``with_sha1=False`` leaves ``sha1`` empty for callers that never read it.
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
                    "sha1": _sha1_of(f) if with_sha1 else "",
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
