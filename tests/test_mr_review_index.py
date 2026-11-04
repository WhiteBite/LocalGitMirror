"""imported_review_index reads imported snapshots out of the review stores."""
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.mr_review_index import imported_review_index

_SCHEMA = """
CREATE TABLE snapshots (snapshot_id TEXT PRIMARY KEY, code_id TEXT,
    version_fingerprint TEXT, repo TEXT, gitlab_url TEXT,
    target_project_id INTEGER, iid INTEGER, imported_at TEXT);
CREATE TABLE current_snapshot (repo TEXT, gitlab_url TEXT,
    target_project_id INTEGER, iid INTEGER, snapshot_id TEXT, updated_at TEXT);
CREATE TABLE progress (snapshot_id TEXT, path TEXT, revision INTEGER,
    payload TEXT, updated_at TEXT);
"""


def _store(home: Path, name: str, snapshots, current, marks) -> None:
    d = home / name
    d.mkdir(parents=True)
    conn = sqlite3.connect(d / "review.sqlite3")
    conn.executescript(_SCHEMA)
    for row in snapshots:
        conn.execute("INSERT INTO snapshots VALUES (?,?,?,?,?,?,?,?)", row)
    for row in current:
        conn.execute("INSERT INTO current_snapshot VALUES (?,?,?,?,?,?)", row)
    for row in marks:
        conn.execute("INSERT INTO progress VALUES (?,?,?,?,?)", row)
    conn.commit()
    conn.close()


def test_index_reports_marks_for_the_current_snapshot(tmp_path):
    _store(tmp_path, "k1",
           [("a" * 64, "c" * 64, "f", "phonyx", "https://gl", 1, 50, "2026-01-01")],
           [],
           [("a" * 64, "p1", 1, "{}", "t"), ("a" * 64, "p2", 1, "{}", "t")])

    index = imported_review_index(tmp_path)

    assert index[("phonyx", 50)] == {"snapshot_id": "a" * 64, "marks": 2}


def test_index_prefers_current_snapshot(tmp_path):
    _store(tmp_path, "k1",
           [("a" * 64, "c", "f", "phonyx", "https://gl", 1, 50, "2026-01-01"),
            ("b" * 64, "c", "f", "phonyx", "https://gl", 1, 50, "2026-02-01")],
           [("phonyx", "https://gl", 1, 50, "a" * 64, "t")],
           [("a" * 64, "p1", 1, "{}", "t"), ("b" * 64, "p1", 1, "{}", "t"),
            ("b" * 64, "p2", 1, "{}", "t")])

    index = imported_review_index(tmp_path)

    assert index[("phonyx", 50)] == {"snapshot_id": "a" * 64, "marks": 1}


def test_index_missing_home_is_empty(tmp_path):
    assert imported_review_index(tmp_path / "nope") == {}


def test_index_handles_store_dir_with_spaces_and_hash(tmp_path):
    _store(tmp_path, "k 1#x",
           [("a" * 64, "c" * 64, "f", "phonyx", "https://gl", 1, 50, "2026-01-01")],
           [], [("a" * 64, "p1", 1, "{}", "t")])

    index = imported_review_index(tmp_path)

    assert index[("phonyx", 50)]["marks"] == 1
