"""
Machine-level corporate tools management.

Tools like krypto-cli are used in npm scripts but not listed in package.json dependencies.
This module installs them in an isolated prefix and adds to PATH.

Config: %LOCALAPPDATA%\\LocalGitMirror\\corporate-tools.json
Format:
{
  "krypto-cli": "2.4.1",
  "krypto-lint": "1.0.0"
}

Installation:
- Tools installed to %LOCALAPPDATA%\\LocalGitMirror\\tools\\<name>\\<version>\\
- Bin directory: %LOCALAPPDATA%\\LocalGitMirror\\tools\\bin\\
- Shim scripts in bin/ that call the real tool
- User adds bin/ to PATH (one-time setup)
"""

from __future__ import annotations

import json
import os
import shutil
import tarfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional


def _local_app_data() -> Path:
    override = os.environ.get("LGM_LOCAL_APP_DATA")
    if override:
        return Path(override)
    local = os.environ.get("LOCALAPPDATA")
    if local:
        return Path(local)
    return Path.home() / "AppData" / "Local"


def tools_config_dir() -> Path:
    return _local_app_data() / "LocalGitMirror"


def tools_config_file() -> Path:
    return tools_config_dir() / "corporate-tools.json"


def tools_install_dir() -> Path:
    return tools_config_dir() / "tools"


def tools_bin_dir() -> Path:
    return tools_install_dir() / "bin"


TOOLS_CONFIG_DIR = tools_config_dir()
TOOLS_CONFIG_FILE = tools_config_file()
TOOLS_INSTALL_DIR = tools_install_dir()
TOOLS_BIN_DIR = tools_bin_dir()


@dataclass
class ToolInfo:
    name: str
    version: str
    installed: bool
    bin_path: Optional[str] = None


def load_tools_config() -> Dict[str, str]:
    """Load corporate-tools.json or return empty dict."""
    try:
        return json.loads(tools_config_file().read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}


def save_tools_config(tools: Dict[str, str]) -> None:
    """Save corporate-tools.json."""
    tools_config_dir().mkdir(parents=True, exist_ok=True)
    tools_config_file().write_text(json.dumps(tools, indent=2), encoding="utf-8")


def list_tools() -> List[ToolInfo]:
    """List all configured tools with installation status."""
    config = load_tools_config()
    tools = []
    for name, version in config.items():
        install_dir = tools_install_dir() / name / version
        bin_path = tools_bin_dir() / (name + (".cmd" if os.name == "nt" else ""))
        installed = install_dir.is_dir() and bin_path.is_file()
        tools.append(ToolInfo(
            name=name,
            version=version,
            installed=installed,
            bin_path=str(bin_path) if installed else None,
        ))
    return tools


def install_tool(name: str, version: str, tarball_path: Path) -> bool:
    """Install a tool from a tarball.

    1. Extract tarball to tools/<name>/<version>/
    2. Create shim in tools/bin/<name>
    3. Return True on success
    """
    install_dir = tools_install_dir() / name / version
    if install_dir.is_dir():
        return True  # Already installed

    install_dir.mkdir(parents=True, exist_ok=True)

    # Extract tarball
    try:
        with tarfile.open(tarball_path, "r:gz") as tf:
            tf.extractall(install_dir)
    except (tarfile.TarError, OSError):
        shutil.rmtree(install_dir, ignore_errors=True)
        return False

    # Find bin directory in extracted package
    pkg_dir = install_dir / "package"
    if not pkg_dir.is_dir():
        # Try to find it
        for candidate in install_dir.iterdir():
            if candidate.is_dir() and (candidate / "package.json").is_file():
                pkg_dir = candidate
                break

    if not pkg_dir.is_dir():
        shutil.rmtree(install_dir, ignore_errors=True)
        return False

    # Read package.json to find bin entry
    try:
        pkg_json = json.loads((pkg_dir / "package.json").read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        shutil.rmtree(install_dir, ignore_errors=True)
        return False

    bin_entry = pkg_json.get("bin", {})
    if isinstance(bin_entry, str):
        bin_entry = {name: bin_entry}

    if not bin_entry:
        shutil.rmtree(install_dir, ignore_errors=True)
        return False

    # Create shims
    tools_bin_dir().mkdir(parents=True, exist_ok=True)

    for bin_name, bin_script in bin_entry.items():
        script_path = pkg_dir / bin_script
        if not script_path.is_file():
            continue

        if os.name == "nt":
            # Windows: create .cmd shim
            shim = tools_bin_dir() / f"{bin_name}.cmd"
            shim.write_text(f'@node "{script_path}" %*\n', encoding="utf-8")
        else:
            # Unix: create shell shim
            shim = tools_bin_dir() / bin_name
            shim.write_text(f'#!/bin/sh\nnode "{script_path}" "$@"\n', encoding="utf-8")
            shim.chmod(0o755)

    return True


def get_path_instructions() -> str:
    """Return instructions for adding tools/bin to PATH."""
    return f"Add to PATH: {tools_bin_dir()}"
