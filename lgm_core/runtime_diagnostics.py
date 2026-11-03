"""Additive runtime diagnostics for ``op_status``.

Reports the local tools version, the machine role from ``LGM_MACHINE_ROLE``
(``undetermined`` when unset; never an actual-host autodetection), the effective
per-request HTTP timeout and the presence — never the value — of secrets.
Nothing here reads a secret or touches the network.
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

from .config import cfg

_GITCOUNT_MAX = 1_000_000
_TIMEOUT_SCOPE = ("default HTTP per-request timeout, not a whole-operation "
                  "deadline")


def _bounded_count(raw: str) -> str:
    if not raw.isdigit():
        return ""
    count = int(raw)
    if count <= 0 or count > _GITCOUNT_MAX:
        return ""
    return str(count)


def _bounded_gitcount(root: Path) -> str:
    if not (root / ".git").exists():
        return ""
    try:
        proc = subprocess.run(
            ["git", "-C", str(root), "rev-list", "--count", "HEAD"],
            capture_output=True, text=True, timeout=10, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    if proc.returncode != 0:
        return ""
    return _bounded_count(proc.stdout.strip())


def local_version(root: Path | None = None) -> str:
    """VERSION file, then DOCACHE_VERSION, then a bounded git count, else unknown."""
    root = root or Path(__file__).resolve().parent.parent
    try:
        version = (root / "VERSION").read_text(encoding="utf-8").strip()
    except OSError:
        version = ""
    if version:
        return version
    env_version = os.environ.get("DOCACHE_VERSION", "").strip()
    if env_version:
        return env_version
    count = _bounded_gitcount(root)
    return f"gitcount-{count}" if count else "unknown"


def configured_machine_role() -> str:
    """The configured role, mirroring _role_guess: config.machine_role defaults
    to 'home', so only an explicit LGM_MACHINE_ROLE selects home/work."""
    role = cfg("LGM_MACHINE_ROLE").strip().lower()
    if role in ("home", "work"):
        return role
    return "undetermined"


def config_presence(config) -> dict:
    """Booleans only: whether each secret/config input is set, never its value."""
    return {
        "api_key": bool(str(getattr(config, "api_key", "") or "").strip()),
        "sync_password": bool(str(getattr(config, "sync_password", "") or "").strip()),
        "gitlab": all(cfg(k).strip() for k in
                      ("GITLAB_URL", "GITLAB_TOKEN", "GITLAB_PROJECT")),
        "tls_pin": bool(str(getattr(config, "pin_sha256", "") or "").strip()
                        or str(getattr(config, "pin_file", "") or "").strip()),
    }


def _effective_timeout(config) -> int:
    raw = getattr(config, "timeout", None)
    return int(raw) if isinstance(raw, int) else 15


def runtime_diagnostics(config, root: Path | None = None) -> dict:
    return {
        "local_version": local_version(root),
        "machine_role": configured_machine_role(),
        "effective_config": {
            "timeout": _effective_timeout(config),
            "timeout_scope": _TIMEOUT_SCOPE,
            "mcp_host_deadline": "unknown",
            "present": config_presence(config),
        },
    }
