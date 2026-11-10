"""lgm_core — shared core for the LocalGitMirror CLI and MCP server.

This package contains the operation registry (``ops.REGISTRY``) that drives
both the thin ``lgm.py`` CLI and the ``lgm_mcp.py`` MCP server.  All network
access goes through ``client.MirrorClient`` (urllib, stdlib only); crypto
helpers live in ``crypto``; config resolution in ``config``.
"""
from __future__ import annotations

from .client import LgmError, MirrorClient
from .config import Config, cfg, from_env, load_env
from .crypto import (
    decrypt_bundle,
    decrypt_envelope,
    encrypt_bundle,
    encrypt_envelope,
)

__all__ = [
    "Config",
    "LgmError",
    "MirrorClient",
    "cfg",
    "decrypt_bundle",
    "decrypt_envelope",
    "encrypt_bundle",
    "encrypt_envelope",
    "from_env",
    "load_env",
]
