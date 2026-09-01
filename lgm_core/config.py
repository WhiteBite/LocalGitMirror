"""Config resolution for lgm — moved verbatim from lgm.py.

Resolution order (same semantics as the original ``cfg()``):
  1.  Project ``.env`` (same directory as ``lgm.py``) takes priority over the
      process environment, because the env may carry an UNRELATED variable of
      the same name (e.g. a workstation-wide ``API_KEY`` belonging to some
      other tool).  To opt OUT for a specific key, set
      ``LGM_USE_ENV_<KEY>=1``.
  2.  Explicit overrides passed to ``Config`` / ``from_env()`` win over both.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

# Directory containing lgm.py (repo root).  Used to locate .env.
_HERE = Path(__file__).resolve().parent.parent


def load_env(path: Path | None = None) -> dict:
    """Parse a ``.env`` file into a flat ``{key: value}`` dict.

    Blank lines and ``#`` comments are skipped.  Values are stripped but
    otherwise taken verbatim (no quote removal, no interpolation).
    """
    if path is None:
        path = _HERE / ".env"
    env: dict[str, str] = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, _, v = line.partition("=")
            env[k.strip()] = v.strip()
    return env


# Module-level cache of the .env file (loaded once at import, same as lgm.py).
_ENV = load_env()


def cfg(key: str, default: str = "") -> str:
    """Resolve a config key with the LGM_USE_ENV_<KEY> opt-out semantics.

    By default the project ``.env`` takes priority over the process
    environment (see module docstring).  Setting ``LGM_USE_ENV_<KEY>=1``
    flips the priority for *that key only* — the process env is checked
    first, then .env, then *default*.
    """
    env_override = os.environ.get(f"LGM_USE_ENV_{key}")
    if env_override:
        return os.environ.get(key) or _ENV.get(key) or default
    return _ENV.get(key) or os.environ.get(key) or default


@dataclass
class Config:
    """Resolved configuration for a CLI / MCP invocation.

    Fields mirror the keys used throughout lgm.py.  ``from_env()`` is the
    normal constructor; explicit overrides (from CLI flags) take precedence
    over .env / process env.

    Additional generic keys resolved ad-hoc via ``cfg()`` (no field needed):
    GRADLE_USER_HOME, JAVA_HOME, LGM_PROTECTED_MAVEN_GROUPS, and the GitLab
    MR-transfer trio GITLAB_URL / GITLAB_TOKEN / GITLAB_PROJECT.
    """

    base_url: str = ""
    api_key: str = ""
    sync_password: str = ""
    storage_path: str = ""
    insecure_tls: bool = True
    # Extra env values needed by gradle/npm scanning (GRADLE_USER_HOME, etc.)
    env: dict = field(default_factory=dict, repr=False)

    @classmethod
    def from_env(
        cls,
        *,
        base_url: str = "",
        api_key: str = "",
        sync_password: str = "",
        storage_path: str = "",
        insecure_tls: bool | None = None,
        env_path: Path | None = None,
    ) -> "Config":
        """Build a Config from .env → process env → explicit overrides.

        Explicit (non-empty) overrides always win.  ``insecure_tls`` defaults
        to ``True`` (self-signed cert on the mirror).
        """
        # Re-load .env if a custom path was given (tests).
        local_env = load_env(env_path) if env_path else _ENV

        def _resolve(key: str, override: str) -> str:
            if override:
                return override
            env_override = os.environ.get(f"LGM_USE_ENV_{key}")
            if env_override:
                return os.environ.get(key) or local_env.get(key) or ""
            return local_env.get(key) or os.environ.get(key) or ""

        return cls(
            base_url=_resolve("BASE_URL", base_url) or "https://localhost:443",
            api_key=_resolve("API_KEY", api_key),
            sync_password=_resolve("SYNC_PASSWORD", sync_password),
            storage_path=_resolve("STORAGE_PATH", storage_path),
            insecure_tls=True if insecure_tls is None else insecure_tls,
            env=local_env,
        )

    def client_defaults(self) -> dict:
        """Return kwargs for ``MirrorClient`` constructor."""
        return {
            "base_url": self.base_url,
            "api_key": self.api_key,
            "insecure_tls": self.insecure_tls,
        }


def from_env(**kwargs) -> Config:
    """Convenience wrapper for ``Config.from_env(**kwargs)``."""
    return Config.from_env(**kwargs)
