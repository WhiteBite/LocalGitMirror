#!/usr/bin/env python3
"""
lgm_diag — locally reproduce what the IDEA plugin sends in a deps request,
then categorise each "missing" coordinate against the local Gradle cache.

This isolates whether the request is realistic or bloated by false-positives
(e.g. BOM-only artifacts that have no jar, only a pom).

Usage:
  python lgm_diag.py [--project D:\\Sources\\kryptonit\\onyx-platform]
                     [--gradle-home D:\\gradle-8.10.2\\.gradle]
                     [--keep]            # keep init-script + jsonl on disk

What it does:
  1. Drops the SAME init-script as buildMissingInitScript() in
     idea-plugin/.../GradleResolver.kt next to the project.
  2. Runs `gradlew --offline -q --no-daemon --init-script <X> help`
     with JAVA_HOME from the user's running JDK or from --java-home.
  3. Parses every {"g","n","v","f"} line.
  4. For each unique g:n:v, looks up files under
     <gradle-home>/caches/modules-2/files-2.1/<g>/<n>/<v>/<sha1>/
     and decides:
        TRULY_MISSING   nothing in cache at all
        POM_ONLY        pom present, no jar  (likely BOM — false-positive)
        JAR_PRESENT     jar present (cache hit — definitely false-positive)
        OTHER           something else present (.module, .aar, …)
  5. Prints a summary so we can see how much of the 500 MB request is bogus.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import json
from pathlib import Path
from collections import Counter, defaultdict


# ── init-script (verbatim copy of buildMissingInitScript) ─────────────────────
INIT_SCRIPT_TEMPLATE = r"""
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
      } catch (Throwable ignored) {
        lgmWriteMissing(out, id.group, id.name, id.version)
      }
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


def find_gradle_command(project_dir: Path) -> list[str]:
    is_win = os.name == "nt"
    wrapper = project_dir / ("gradlew.bat" if is_win else "gradlew")
    if wrapper.exists():
        return [str(wrapper)]
    return ["gradle.bat" if is_win else "gradle"]


def detect_java_home(explicit: str | None) -> str | None:
    if explicit and Path(explicit).is_dir():
        return explicit
    # Probe usual locations
    candidates = [
        os.environ.get("JAVA_HOME"),
        r"C:\Users\Mind\.jdks\openjdk-21",
        r"C:\Users\Mind\.jdks\ms-21.0.10",
        r"D:\SDKs\Java\temurin-23.0.2",
    ]
    for c in candidates:
        if c and (Path(c) / "bin" / ("java.exe" if os.name == "nt" else "java")).exists():
            # unwrap \bin if user pointed at /bin
            if Path(c).name.lower() == "bin":
                c = str(Path(c).parent)
            return c
    return None


def run_init_script(project_dir: Path, java_home: str | None) -> tuple[Path, str, int]:
    """Drop init-script, run gradlew --offline help, return (jsonl_path, stdout, exit)."""
    out_file = Path(tempfile.mktemp(prefix="lgm-missing-", suffix=".jsonl"))
    init_file = Path(tempfile.mktemp(prefix="lgm-init-", suffix=".gradle"))
    init_file.write_text(
        INIT_SCRIPT_TEMPLATE.replace("__OUT__", str(out_file).replace("\\", "/")),
        encoding="utf-8",
    )

    cmd = find_gradle_command(project_dir) + [
        "--init-script", str(init_file),
        "-q", "--no-daemon", "--offline", "help",
    ]
    env = os.environ.copy()
    if java_home:
        env["JAVA_HOME"] = java_home
        env["JDK_HOME"] = java_home

    print(f"  cmd: {' '.join(cmd)}")
    print(f"  cwd: {project_dir}")
    print(f"  JAVA_HOME: {java_home or '(not set)'}")
    print(f"  init-script: {init_file}")
    print(f"  output:      {out_file}")

    proc = subprocess.run(
        cmd, cwd=str(project_dir), env=env,
        capture_output=True, text=True, timeout=600,
    )
    return out_file, (proc.stdout or "") + (proc.stderr or ""), proc.returncode


def parse_jsonl(path: Path) -> tuple[list[dict], str | None]:
    """Return (missing_list, gradle_user_home)."""
    if not path.exists():
        return [], None
    items = []
    guh = None
    seen = set()
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
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
        key = f"{obj.get('g')}:{obj.get('n')}:{obj.get('v')}"
        if key in seen:
            continue
        seen.add(key)
        items.append(obj)
    return items, guh


def cache_status(g: str, n: str, v: str, cache_root: Path) -> tuple[str, list[str]]:
    """
    Look under <cache>/<g>/<n>/<v>/ — return (status, file_names).
    Status:
      JAR_PRESENT  — at least one *.jar
      POM_ONLY     — only *.pom (or pom + module/etc)
      OTHER        — something else (aar, klib, …)
      TRULY_MISSING — directory doesn't exist or no files at all
    """
    base = cache_root / g / n / v
    if not base.is_dir():
        return "TRULY_MISSING", []
    files = []
    for sha_dir in base.iterdir():
        if sha_dir.is_dir():
            for f in sha_dir.iterdir():
                if f.is_file():
                    files.append(f.name)
    if not files:
        return "TRULY_MISSING", []
    if any(f.endswith(".jar") for f in files):
        return "JAR_PRESENT", files
    if any(f.endswith(".pom") for f in files):
        return "POM_ONLY", files
    return "OTHER", files


def main():
    ap = argparse.ArgumentParser(description="Diagnose deps-missing init-script output.")
    ap.add_argument("--project", default=r"D:\Sources\kryptonit\onyx-platform")
    ap.add_argument("--gradle-home", default="")
    ap.add_argument("--java-home", default="")
    ap.add_argument("--keep", action="store_true", help="don't delete temp files")
    args = ap.parse_args()

    project = Path(args.project)
    if not project.is_dir():
        sys.exit(f"project not found: {project}")

    print(f"\n=== Running missing-init-script on {project.name} ===")
    java_home = detect_java_home(args.java_home or None)

    out_file, stdout, exit_code = run_init_script(project, java_home)

    print(f"\n  exit={exit_code}  stdout-tail:")
    for ln in stdout.splitlines()[-15:]:
        print(f"    {ln}")

    items, guh_from_script = parse_jsonl(out_file)
    print(f"\n  jsonl items: {len(items)}")
    print(f"  gradle user home (reported): {guh_from_script}")

    # pick cache root
    if args.gradle_home:
        cache_root = Path(args.gradle_home) / "caches/modules-2/files-2.1"
    elif guh_from_script:
        cache_root = Path(guh_from_script) / "caches/modules-2/files-2.1"
    else:
        cache_root = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    print(f"  using cache root: {cache_root}\n")

    # categorise
    by_status: dict[str, list[tuple[str, list[str]]]] = defaultdict(list)
    for it in items:
        g, n, v = it.get("g", ""), it.get("n", ""), it.get("v", "")
        st, files = cache_status(g, n, v, cache_root)
        by_status[st].append((f"{g}:{n}:{v}", files))

    print("=== SUMMARY ===")
    total = sum(len(v) for v in by_status.values())
    for st in ["TRULY_MISSING", "POM_ONLY", "JAR_PRESENT", "OTHER"]:
        n = len(by_status.get(st, []))
        pct = (100 * n / total) if total else 0
        print(f"  {st:14s}  {n:>5}  ({pct:5.1f}%)")
    print(f"  {'TOTAL':14s}  {total:>5}")

    print("\n=== TRULY_MISSING (first 20) ===")
    for coord, _ in by_status.get("TRULY_MISSING", [])[:20]:
        print(f"  {coord}")
    if len(by_status.get("TRULY_MISSING", [])) > 20:
        print(f"  ... and {len(by_status['TRULY_MISSING']) - 20} more")

    print("\n=== POM_ONLY (first 10) — these are likely BOMs (false-positive) ===")
    for coord, files in by_status.get("POM_ONLY", [])[:10]:
        print(f"  {coord:60s}  {files}")

    print("\n=== JAR_PRESENT (first 10) — these are TRUE FALSE-POSITIVES ===")
    for coord, files in by_status.get("JAR_PRESENT", [])[:10]:
        print(f"  {coord:60s}  {len(files)} file(s)")

    if not args.keep:
        try: out_file.unlink()
        except Exception: pass
    else:
        print(f"\n  (kept jsonl: {out_file})")


if __name__ == "__main__":
    main()
