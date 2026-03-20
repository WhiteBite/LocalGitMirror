import sys
from pathlib import Path

import pytest

# Ensure `app` package is importable when tests are launched from repo root.
BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.routers.api import _infer_repo_from_dump_filename


@pytest.mark.parametrize(
    "filename,expected",
    [
        ("dump_repo_20260306_0100.dmp", "repo"),
        ("dump_upload_apply_smoke_repo_20260306_0100.dmp", "upload_apply_smoke_repo"),
        ("dump_my-repo_20260306_2359.dmp", "my-repo"),
        (r"C:\tmp\dump_upload_apply_smoke_repo_20260306_0100.dmp", "upload_apply_smoke_repo"),
        ("dump_repo_only.dmp", None),
        ("dump_repo_20260306_100.dmp", None),
        ("dump_repo_2026030A_0100.dmp", None),
        ("wrong_prefix_repo_20260306_0100.dmp", None),
        ("dump_repo_20260306_0100.zip", None),
        ("dump_bad/repo_20260306_0100.dmp", None),
    ],
)
def test_infer_repo_from_dump_filename(filename: str, expected: str | None):
    assert _infer_repo_from_dump_filename(filename) == expected
