#!/usr/bin/env python
"""
yarn_offline_setup.py — prepare a yarn (classic v1) offline-mirror from the
corporate tarballs delivered by LocalGitMirror, and wire the project to use it.

After `lgm apply`, the corporate .tgz live under ~/.lgm-npm-offline (npm layout).
yarn v1 looks them up in its offline-mirror by the filename
`<name with '/'->'-'>-<version>.tgz` (e.g. @krypto-ui/core@0.30.0 ->
`@krypto-ui-core-0.30.0.tgz`). This script:
  1. reads name+version from each tarball's package.json (layout-agnostic),
  2. copies it into the yarn offline-mirror under the yarn filename,
  3. writes .yarnrc in the project (offline-mirror + npmjs registry),
  4. rewrites yarn.lock `resolved` URLs corporate-registry -> npmjs (public
     packages download from npmjs; corporate resolve from the mirror by name),
  5. optionally runs `yarn install`.

Usage:
  python yarn_offline_setup.py --project D:\\Sources\\kryptonit\\frontend [--install]
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

import lgm  # reuse _npmrc_registries / registry detection


def yarn_filename(name: str, version: str) -> str:
    return name.replace("/", "-") + "-" + version + ".tgz"


def read_pkg(tgz: Path):
    """Return (name, version) from package/package.json inside the tarball."""
    try:
        with tarfile.open(tgz, "r:gz") as tf:
            member = None
            for m in tf.getmembers():
                if m.name.endswith("package.json") and m.name.count("/") <= 1:
                    member = m
                    break
            if member is None:
                return None
            data = tf.extractfile(member).read()
            j = json.loads(data)
            return j.get("name"), j.get("version")
    except Exception as e:
        print(f"  ! cannot read {tgz.name}: {e}")
        return None


def build_mirror(tarballs_root: Path, mirror: Path) -> int:
    mirror.mkdir(parents=True, exist_ok=True)
    n = 0
    for tgz in tarballs_root.rglob("*.tgz"):
        nv = read_pkg(tgz)
        if not nv or not nv[0] or not nv[1]:
            continue
        name, version = nv
        dest = mirror / yarn_filename(name, version)
        shutil.copyfile(tgz, dest)
        print(f"  + {dest.name}")
        n += 1
    return n


def write_yarnrc(project: Path, mirror: Path):
    yarnrc = project / ".yarnrc"
    existing = yarnrc.read_text(encoding="utf-8", errors="replace") if yarnrc.is_file() else ""
    lines = [ln for ln in existing.splitlines()
             if not ln.strip().startswith("yarn-offline-mirror")
             and not ln.strip().startswith("registry ")]
    mirror_posix = str(mirror).replace("\\", "/")
    lines += [
        f'yarn-offline-mirror "{mirror_posix}"',
        'yarn-offline-mirror-pruning false',
        'registry "https://registry.npmjs.org"',
    ]
    yarnrc.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  wrote {yarnrc}")


def rewrite_yarn_lock(project: Path) -> int:
    lock = project / "yarn.lock"
    if not lock.is_file():
        print("  ! no yarn.lock")
        return 0
    text = lock.read_text(encoding="utf-8", errors="replace")
    npmjs = "https://registry.npmjs.org/"
    count = 0
    for base in lgm._npmrc_registries(project):
        if base == npmjs:
            continue
        count += text.count(base)
        text = text.replace(base, npmjs)
    lock.write_text(text, encoding="utf-8")
    print(f"  yarn.lock: {count} resolved URL(s) -> npmjs")
    return count


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--tarballs-from", default=str(Path.home() / ".lgm-npm-offline"))
    ap.add_argument("--mirror", default=str(Path.home() / ".lgm-yarn-offline"))
    ap.add_argument("--install", action="store_true")
    args = ap.parse_args()

    project = Path(args.project)
    mirror = Path(args.mirror)
    tarballs = Path(args.tarballs_from)
    if not project.is_dir():
        print(f"! project not a dir: {project}"); sys.exit(1)

    print(f"building yarn offline-mirror at {mirror} from {tarballs}")
    n = build_mirror(tarballs, mirror)
    print(f"mirror: {n} corporate tarball(s)")
    write_yarnrc(project, mirror)
    rewrite_yarn_lock(project)

    if args.install:
        yarn = os.environ.get("LGM_YARN", r"D:\Cache\npm\global\yarn.cmd")
        yarn = yarn if Path(yarn).is_file() else "yarn"
        print(f"running: {yarn} install (public from npmjs, corporate from mirror)...")
        cmd = (["cmd", "/c", yarn] if os.name == "nt" else [yarn]) + ["install", "--non-interactive"]
        rc = subprocess.run(cmd, cwd=str(project)).returncode
        print(f"yarn install exit={rc}")
    else:
        print("done. run `yarn install` in the project.")


if __name__ == "__main__":
    main()
