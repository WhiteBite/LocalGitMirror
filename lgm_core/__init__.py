"""lgm_core — shared core for the LocalGitMirror CLI and MCP server.

This package contains the operation registry (``ops.REGISTRY``) that drives
both the thin ``lgm.py`` CLI and the ``lgm_mcp.py`` MCP server.  All network
access goes through ``client.MirrorClient`` (urllib, stdlib only); crypto
helpers live in ``crypto``; config resolution in ``config``.
"""
from __future__ import annotations

from .config import Config, load_env, cfg, from_env
from .crypto import (
    encrypt_bundle,
    decrypt_bundle,
    encrypt_envelope,
    decrypt_envelope,
)
from .client import MirrorClient, LgmError

__all__ = [
    "Config",
    "load_env",
    "cfg",
    "from_env",
    "encrypt_bundle",
    "decrypt_bundle",
    "encrypt_envelope",
    "decrypt_envelope",
    "MirrorClient",
    "LgmError",
]
