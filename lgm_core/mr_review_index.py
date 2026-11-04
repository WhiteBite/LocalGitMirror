"""Read-only index of imported review snapshots across the HOME review stores.

The review store is per (GitLab URL, target project, repo) under
``reviews_home()/<repo-key>/review.sqlite3``; a repo key cannot be recomputed on
HOME without the GitLab project id, so the stores are enumerated by path and the
rows are indexed by the ``repo`` column instead.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path

from .mr_review_store import reviews_home


def imported_review_index(home: Path | str | None = None) -> dict[tuple[str, int], dict]:
    """Map ``(repo, iid)`` to ``{"snapshot_id", "marks"}`` for every readable store."""
    root = reviews_home(home)
    index: dict[tuple[str, int], dict] = {}
    if not root.is_dir():
        return index
    for db in sorted(root.glob("*/review.sqlite3")):
        try:
            conn = sqlite3.connect(db.as_uri() + "?mode=ro", uri=True, timeout=10)
            conn.row_factory = sqlite3.Row
            try:
                snapshots = conn.execute(
                    "SELECT snapshot_id, repo, iid, imported_at FROM snapshots "
                    "ORDER BY imported_at").fetchall()
                current = {(r["repo"], r["iid"]): r["snapshot_id"] for r in conn.execute(
                    "SELECT snapshot_id, repo, iid FROM current_snapshot").fetchall()}
                marks = {r["snapshot_id"]: r["n"] for r in conn.execute(
                    "SELECT snapshot_id, COUNT(*) AS n FROM progress "
                    "GROUP BY snapshot_id").fetchall()}
            finally:
                conn.close()
        except (sqlite3.Error, OSError):
            continue
        for row in snapshots:
            key = (row["repo"], int(row["iid"]))
            snapshot_id = current.get(key, row["snapshot_id"])
            index[key] = {"snapshot_id": snapshot_id, "marks": marks.get(snapshot_id, 0)}
    return index
