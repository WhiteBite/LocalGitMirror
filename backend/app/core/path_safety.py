"""Containment check for client-supplied relative paths."""

from pathlib import Path


class PathEscapeError(ValueError):
    """A client-supplied path resolves outside its allowed base directory."""


def resolve_contained(base: Path, relative: str) -> Path:
    """Resolve base/relative and guarantee the result stays inside base.

    Rejects ``..`` traversal, absolute paths and drive/UNC overrides; resolves
    symlinks, so a link inside base cannot point at a target outside it.
    """
    base_abs = base.resolve()
    resolved = (base_abs / relative).resolve()
    if resolved != base_abs and base_abs not in resolved.parents:
        raise PathEscapeError(f"path escapes {base_abs}: {relative!r}")
    return resolved
