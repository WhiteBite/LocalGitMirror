#!/usr/bin/env python3
"""Work PC setup: configure .env and OpenCode MCP entry in one run.

Usage:
    python setup.py                          # interactive prompts
    python setup.py --url https://... --key ...
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
DEFAULT_INSTALL = Path.home() / "Tools" / "lgm"

OPENCODE_PATHS = [
    Path.home() / ".config" / "opencode" / "opencode.json",
    Path.home() / ".opencode" / "opencode.json",
]


def find_opencode_config() -> Path | None:
    for p in OPENCODE_PATHS:
        if p.is_file():
            return p
    for p in OPENCODE_PATHS:
        if p.parent.is_dir():
            return p
    return None


def prompt(name: str, default: str = "") -> str:
    hint = f" [{default}]" if default else ""
    val = input(f"{name}{hint}: ").strip()
    return val or default


def write_env(install_dir: Path, url: str, key: str, password: str) -> None:
    install_dir.mkdir(parents=True, exist_ok=True)
    for f in TOOLS_DIR.glob("*.py"):
        shutil.copy2(f, install_dir / f.name)
    core_src = TOOLS_DIR / "lgm_core"
    core_dst = install_dir / "lgm_core"
    if core_src.is_dir():
        if core_dst.exists():
            shutil.rmtree(core_dst)
        shutil.copytree(core_src, core_dst)
    npm_src = TOOLS_DIR / "_lgm_npm.py"
    if npm_src.is_file():
        shutil.copy2(npm_src, install_dir / "_lgm_npm.py")
    env = install_dir / ".env"
    env.write_text(f"BASE_URL={url}\nAPI_KEY={key}\nSYNC_PASSWORD={password}\n", encoding="utf-8")
    print(f"[ok] Tools installed: {install_dir}")
    print(f"[ok] .env written: {env}")


def configure_opencode(install_dir: Path, url: str, key: str) -> None:
    config_path = find_opencode_config()
    if config_path is None:
        print(f"[skip] OpenCode config not found at {OPENCODE_PATHS}")
        print("       Add manually after installing OpenCode:")
        mcp_path = install_dir / "lgm_mcp.py"
        print(f'       "mcp": {{"doccache-tools": {{"command": "python", "args": ["{mcp_path}"]}}}}')
        return

    config_path.parent.mkdir(parents=True, exist_ok=True)
    if config_path.is_file():
        raw = config_path.read_text(encoding="utf-8").strip() or "{}"
    else:
        raw = "{}"

    try:
        config = json.loads(raw)
    except json.JSONDecodeError:
        backup = config_path.with_suffix(".json.bak")
        shutil.copy2(config_path, backup)
        print(f"[warn] Config was invalid JSON, backed up to {backup}")
        config = {}

    config.setdefault("mcp", {})
    config["mcp"]["doccache-tools"] = {
        "command": "python",
        "args": [str(install_dir / "lgm_mcp.py")],
        "env": {
            "BASE_URL": url,
            "API_KEY": key,
        },
    }

    config_path.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"[ok] OpenCode MCP configured: {config_path}")
    print(f"     Restart OpenCode to pick up doccache-tools")


def main() -> None:
    ap = argparse.ArgumentParser(description="Configure DocCache tools on this machine")
    ap.add_argument("--url", default="", help="Mirror server URL")
    ap.add_argument("--key", default="", help="Mirror API key")
    ap.add_argument("--password", default="", help="Mirror sync password")
    ap.add_argument("--install-dir", default="", help="Where to place the tools")
    args = ap.parse_args()

    url = args.url or prompt("Server URL", f"https://{__import__('socket').gethostname()}.local")
    if not url.startswith(("http://", "https://")):
        url = f"https://{url}"
    key = args.key or prompt("API Key")
    password = args.password or prompt("Sync Password")
    install = Path(args.install_dir) if args.install_dir else DEFAULT_INSTALL
    if str(install) == str(TOOLS_DIR):
        install = TOOLS_DIR
    else:
        install = Path(input(f"Install to [{install}]: ").strip() or install)

    write_env(install, url, key, password)
    configure_opencode(install, url, key)

    print()
    print("Done. Test: python", install / "lgm.py", "status")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(1)
