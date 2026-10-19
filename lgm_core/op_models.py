from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

from .config import Config, cfg
from .client import MirrorClient, LgmError


# ── Dataclasses ─────────────────────────────────────────────────────────────

@dataclass
class Param:
    """A single op parameter.

    ``type`` is one of "str", "int", "bool", "list".  A "bool" param is a
    flag (``store_true`` in argparse); a "list" param is repeatable
    (``action="append"``).  ``required`` only applies to str/int/list.
    ``enum``/``minimum``/``maximum``/``pattern`` are optional JSON Schema
    constraints surfaced to MCP clients.
    """
    name: str
    type: str  # "str" | "int" | "bool" | "list"
    default: Any = None
    help: str = ""
    required: bool = False
    enum: list | None = None
    minimum: int | None = None
    maximum: int | None = None
    pattern: str | None = None

    def __post_init__(self):
        if self.type == "bool" and self.default is None:
            self.default = False
        if self.type == "str" and self.default is None:
            self.default = ""
        if self.type == "int" and self.default is None:
            self.default = 0
        if self.type == "list" and self.default is None:
            self.default = []


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
    """The configured machine role; undetermined when LGM_MACHINE_ROLE is
    neither home nor work (auto-detection is a plugin-only feature)."""
    role = cfg("LGM_MACHINE_ROLE").strip().lower()
    if role in ("home", "work"):
        return role
    return "undetermined (set LGM_MACHINE_ROLE=home or work)"
