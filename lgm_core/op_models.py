from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from .config import Config
from .client import MirrorClient, LgmError


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
