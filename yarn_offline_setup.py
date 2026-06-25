#!/usr/bin/env python
"""
yarn_offline_setup.py — set up yarn (classic v1) for offline install from the
corporate tarballs already delivered by `lgm apply` (~/.lgm-npm-offline), and
optionally run `yarn install --offline`.

Repo-clean by design: the offline-mirror is configured in the GLOBAL ~/.yarnrc
(outside any repo, survives branch switches) and the project's yarn.lock / .yarnrc
are NOT modified. Public packages come from yarn's own global cache; corporate
from the offline-mirror (matched by filename, so nexus URLs in yarn.lock are
irrelevant offline). `--pure-lockfile` keeps yarn.lock untouched.

Usage:
  python yarn_offline_setup.py --project D:\\Sources\\kryptonit\\frontend [--install]
"""
import argparse
import os
import subprocess
from pathlib import Path

import lgm  # reuse the single source of truth


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--install", action="store_true", help="also run yarn install --offline --pure-lockfile")
    args = ap.parse_args()

    proj = Path(args.project)
    if not (proj / "yarn.lock").is_file():
        print(f"no yarn.lock in {proj} — not a yarn project")
        return

    ymir = lgm._yarn_offline_mirror()
    cnt = lgm._build_yarn_mirror(lgm.npm_offline_mirror(), ymir)
    lgm._set_global_yarn_mirror(ymir)
    print(f"yarn offline-mirror: {cnt} corporate tarball(s) at {ymir}")
    print("global ~/.yarnrc set; project yarn.lock/.yarnrc NOT touched (repo stays clean)")

    if args.install:
        yarn_bin = os.environ.get("LGM_YARN", "yarn")
        cmd = (["cmd", "/c", yarn_bin] if os.name == "nt" else [yarn_bin]) + \
              ["install", "--offline", "--pure-lockfile", "--non-interactive"]
        print("running: yarn install --offline --pure-lockfile ...")
        try:
            rc = subprocess.run(cmd, cwd=str(proj)).returncode
            print(f"yarn install exit={rc}")
            if rc != 0:
                print("if it failed on a missing PUBLIC package, that version isn't in yarn's "
                      "global cache yet — run one ONLINE `yarn install` to populate it, then "
                      "--offline works on every branch.")
        except FileNotFoundError:
            print("'yarn' not on PATH — set LGM_YARN or run `yarn install --offline --pure-lockfile` manually")
    else:
        print("run: yarn install --offline --pure-lockfile")


if __name__ == "__main__":
    main()
