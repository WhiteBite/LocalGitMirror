"""HOME-side mr_overview: one call merging pending signals and imported reviews."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lgm_core.ops_mr as opmod
from lgm_core.client import LgmError
from lgm_core.config import Config
from lgm_core.op_models import Ctx
from lgm_core.render import _render_mr_overview


class _FakeClient:
    def __init__(self):
        self.sync_password = ""

    def repos(self):
        return {"repos": ["phonyx"]}


def _ctx():
    return Ctx(config=Config(base_url="http://localhost:1", api_key="",
                             sync_password="pw"),
               client=_FakeClient())


def test_overview_merges_pending_and_review(monkeypatch):
    pending = {"phonyx": {"items": [
        {"iid": 50, "state": "open", "state_evidence": "note-mtime",
         "notes": {"age_seconds": 3600}, "replies_pending": True,
         "last_publish": {"parsed": True, "posted": 2, "failed": 0, "skipped": 0}},
        {"iid": 41, "state": "open", "state_evidence": "gitlab",
         "notes": None, "replies_pending": False, "last_publish": None}]}}
    index = {("phonyx", 50): {"snapshot_id": "a" * 64, "marks": 3}}
    monkeypatch.setattr(opmod, "op_mr_pending",
                        lambda ctx, args: pending[args["repo"]])
    monkeypatch.setattr(opmod, "imported_review_index", lambda home=None: index)

    res = opmod.op_mr_overview(_ctx(), {"repo": "phonyx"})

    assert res["totals"] == {"repos": 1, "mrs": 2, "replies_pending": 1,
                             "reviews_imported": 1}
    items = res["repos"][0]["items"]
    assert items[0]["review"] == {"imported": True, "snapshot_id": "a" * 64,
                                  "marks": 3}
    assert items[0]["replies_pending"] is True
    assert items[0]["notes_age_seconds"] == 3600
    assert items[0]["last_publish"]["posted"] == 2
    assert items[1]["review"]["imported"] is False


def test_repo_error_is_isolated(monkeypatch):
    def boom(ctx, args):
        raise LgmError("network", "boom")

    monkeypatch.setattr(opmod, "op_mr_pending", boom)
    monkeypatch.setattr(opmod, "imported_review_index", lambda home=None: {})

    res = opmod.op_mr_overview(_ctx(), {"repo": "r"})

    assert "boom" in res["repos"][0]["error"]
    assert res["totals"]["repos"] == 0


def test_render_mr_overview():
    r = {"repo": "", "totals": {"repos": 1, "mrs": 1, "replies_pending": 1,
                                "reviews_imported": 1},
         "repos": [{"repo": "phonyx", "count": 1, "items": [
             {"iid": 50, "state": "open", "notes_age_seconds": 3600,
              "replies_pending": True,
              "last_publish": {"parsed": True, "posted": 2, "failed": 0},
              "review": {"imported": True, "snapshot_id": "a" * 64, "marks": 3}}]}]}

    out = "\n".join(_render_mr_overview(r))

    assert "replies pending" in out
    assert "review imported" in out
    assert "!50" in out


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
