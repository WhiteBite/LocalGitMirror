"""Human renderers: every REGISTRY op renders text, never a JSON passthrough,
and mr_list surfaces per-repo postbox errors instead of hiding them."""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.ops import REGISTRY
from lgm_core.render import _RENDERERS, render

OP_NAMES = [op.name for op in REGISTRY]

_SHA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"

REALISTIC = {
    "scan": {"total_artifacts": 3, "roots": [
        {"path": "C:/Users/Mind/.gradle", "exists": True, "artifacts": 3,
         "groups": 2, "truncated": False, "remaining": 0,
         "sample": [{"group": "com.corp", "name": "lib", "version": "1.0",
                     "file": "lib-1.0.jar", "size": 1024}]}]},
    "pending": {"repo": "phonyx", "items": [
        {"id": "a1b2c3d4e5f6", "size": 2048, "mtime": "2026-10-18T10:00:00"}]},
    "request": {"project": "D:/proj", "raw_missing": 4,
                "guh": "C:/Users/Mind/.gradle", "cached_coords": 10,
                "missing": 2, "present": 5, "manifest_size": 1024,
                "posted": True, "response": {"id": "a1b2c3d4e5f6", "size": 2048}},
    "respond": {"repo": "phonyx", "request_id": "a1b2c3d4e5f6", "remaining": 1,
                "manifest": {"version": 3, "missing": []}, "found": 3,
                "not_found": 1, "not_found_items": ["com.corp:missing:1.0"],
                "encrypted_size": 4096, "response": {"id": "resp1"}},
    "apply": {"repo": "phonyx", "response_id": "resp1", "remaining": 0,
              "target": "C:/Users/Mind/.gradle", "layout": "gradle",
              "installed": 3, "skipped": 1, "invalid": 0,
              "ack": {"success": True}},
    "fetch-poms": {"repo_url": "https://repo.maven.apache.org/maven2",
                   "fetched": 2, "skipped_private": 1, "failed": 0},
    "publish": {"published": 4, "response": {"added": 3, "existed": 1,
                                              "conflicts": []}},
    "debug": {"repo": "phonyx",
              "env": {"GRADLE_USER_HOME_env": "C:/Users/Mind/.gradle",
                      "HOME": "C:/Users/Mind"},
              "cache_roots": [{"path": "C:/Users/Mind/.gradle", "status": "OK",
                               "artifacts": 3, "groups": 2}],
              "mirror": {"base_url": "https://127.0.0.1",
                         "capabilities": {"server": {"name": "lgm"}},
                         "pending": {"items": []},
                         "responses": {"items": []}}},
    "status": {"capabilities": {"server": {"name": "LocalGitMirror",
                                            "version": "1.0"},
                                "sync": {"protocolVersion": 3,
                                         "features": {"v3": True,
                                                      "uploadAndApply": True,
                                                      "exportDump": True}}},
               "repos": {"repos": ["phonyx"], "current": "phonyx"},
               "role_guess": "home", "base_url": "https://127.0.0.1",
               "tools": {"local": "dev", "mirror": "0.42.0", "outdated": True}},
    "repos": {"repos": ["phonyx", "doctransformer"], "current": "phonyx"},
    "branches": {"repo": "phonyx", "head": _SHA, "refs": {
        "main": {"sha": _SHA, "updated": "2026-10-18", "is_head": True,
                 "divergence": "ahead", "ahead": 2},
        "feature/x": {"sha": _SHA, "updated": "2026-10-17", "is_head": False}}},
    "send": {"repo": "phonyx", "branch": "feature-x", "bundle_size": 12345,
             "response": {"e": "x", "success": True}},
    "pull": {"repo": "phonyx", "branch": "main", "status": "ok", "head": _SHA,
             "bundle_size": 12345, "fetch_exit": 0, "fetch_stderr": ""},
    "preview_pull": {"success": True, "repo": "phonyx", "remoteHead": _SHA,
                     "hasUpdates": True, "reason": "ahead", "since": _SHA},
    "has_commits": {"success": True, "repo": "phonyx", "known": [_SHA],
                    "head": _SHA},
    "deps_request": {"repo": "phonyx", "id": "a1b2c3d4e5f6", "size": 2048},
    "vault_status": {"vault": "enabled", "projection": "ok",
                     "protectedGroups": ["com.corp"],
                     "stats": {"positions": 10}, "conflicts": [], "wanted": []},
    "branch_delete": {"repo": "phonyx", "deleted": ["old-branch"],
                      "failed": [{"branch": "main", "error": "HEAD protected"}],
                      "message": "deleted 1/2 branch(es)"},
    "prune": {"repo": "phonyx", "apply": False, "message": "2 candidate(s)",
              "candidates": ["stale-1", "stale-2"], "pruned": [],
              "protected": ["main"]},
    "mr_list": {"count": 1, "errors": [], "items": [
        {"iid": 41, "title": "Refactor sync", "source_branch": "feature/sync",
         "updated_at": "2026-10-17T12:00:00Z", "source": "gitlab",
         "state": "open"}]},
    "mr_send": {"repo": "phonyx", "project": "D:/proj",
                "sent": [{"iid": 41, "branch": "feature/sync", "tip": _SHA}],
                "skipped": [{"iid": 42, "branch": "feature/other",
                             "tip": _SHA}],
                "warnings": [],
                "send": {"repo": "phonyx", "branch": "feature/sync",
                         "bundle_size": 8192, "excluded_bases": 2,
                         "response": {"e": "x"}}},
    "mr_notes": {"success": True, "repo": "phonyx", "count": 1, "notes": [
        {"iid": 41, "path": "mr-notes/mr-!41.md",
         "markdown": "# MR !41 — Refactor sync\n\n- **Ветка:** `feature/sync`\n"
                     "\n## ⚠ НЕ РЕШЕНО · тред 1\n"}]},
    "mr_replies_send": {"success": True, "repo": "phonyx",
                        "path": "mr-replies/mr-!41.md", "size": 256,
                        "precheck": {"status": "ok", "collisions": [],
                                     "unknown_threads": []},
                        "response": {"id": "r1"}},
    "mr_replies_status": {"success": True, "repo": "phonyx", "count": 1,
                          "statuses": [
                              {"iid": 41,
                               "path": "mr-replies-status/mr-!41.md",
                               "markdown": "<!-- lgm-replies-status v1 -->\n"
                                           "# MR !41 — status\n- posted: 2\n",
                               "posted": 2, "duplicates": 0, "skipped": 0,
                               "failed": 0, "not_anchored": 0,
                               "at": "2026-10-18 10:00", "details": [],
                               "parsed": True}]},
    "mr_pending": {"success": True, "repo": "phonyx", "count": 2, "items": [
        {"iid": 41, "notes": {"mtime": 1763000000, "age_seconds": 3600},
         "replies_pending": False, "reply_ids": [],
         "last_publish": {"posted": 2, "duplicates": 0, "skipped": 0,
                          "failed": 0, "not_anchored": 0,
                          "at": "2026-10-18 10:00", "details": [],
                          "parsed": True},
         "state": "open", "state_evidence": "note-mtime"},
        {"iid": 42, "notes": None, "replies_pending": True,
         "reply_ids": ["b" * 32], "last_publish": None,
         "state": "unknown", "state_evidence": "expired"}]},
    "mr_replies_push": {"success": True, "repo": "phonyx", "dry_run": False,
                        "count": 1,
                        "totals": {"posted": 2, "duplicates": 1, "skipped": 0,
                                   "failed": 0, "not_anchored": 1},
                        "reports": [{"iid": 41, "path": "mr-replies/mr-!41.md",
                                     "posted": 2, "duplicates": 1, "skipped": 0,
                                     "failed": 0, "parse_errors": 0, "acked": True,
                                     "not_anchored": [
                                         "reply 3: B.java:9 — line outside diff hunks"],
                                     "replies": [
                                         {"label": "reply 1", "kind": "thread",
                                          "target": "d1", "outcome": "posted",
                                          "note": "posted into thread d1"},
                                         {"label": "reply 2", "kind": "thread",
                                          "target": "d2", "outcome": "dup",
                                          "note": "duplicate, already in GitLab"}],
                                     "details": []}],
                        "message": ""},
    "guide": {"success": True,
              "guide": "LocalGitMirror transfer guide (work PC <-> home PC).\n\n"
                       "CODE:\n  1. branches repo=<name>"},
    "update": {"success": True, "version": "0.42.0", "files_updated": 12,
               "tools_root": "D:/Sources/WhiteBite/LocalGitMirror",
               "message": "Updated 12 files to version 0.42.0. Restart the CLI "
                          "to use the new version."},
    "chat_send": {"success": True, "id": "msg-1", "text": "ping"},
    "chat_list": {"success": True, "count": 2, "messages": [
        {"id": "m2", "text": "pong", "ts": 1763000000, "hint": "chat:mcp"},
        {"id": "m1", "text": "ping", "ts": 1762990000, "hint": "chat:mcp"}]},
    "file_send": {"success": True, "id": "f1", "file": "notes.txt",
                  "size": 1024},
    "file_list": {"success": True, "repo": "phonyx", "count": 1, "files": [
        {"id": "f1", "path": "notes.txt", "size": 1024}]},
    "file_get": {"success": True, "id": "f1", "saved": "D:/out/notes.txt",
                 "size": 1024},
    "file_ack": {"success": True, "repo": "phonyx", "id": "f1",
                 "result": {"success": True}},
    "config_get": {"success": True, "settings": {
        "general": {"apiTitle": "Mirror"}, "git": {"defaultBranch": "main"}}},
    "config_set": {"success": True, "section": "general", "key": "apiTitle",
                   "value": "Mirror"},
    "mr_projects_scan": {"success": True, "roots": ["D:/Sources/kryptonit"],
                         "scanned_dirs": 1, "added": ["phonyx"], "updated": [],
                         "total": 1,
                         "projects": [{"repo": "phonyx",
                                       "path": "D:/Sources/kryptonit/phonyx",
                                       "gitlab": "kryptonit/phonyx"}]},
    "mr_projects_list": {"success": True, "count": 1, "projects": [
        {"repo": "phonyx", "path": "D:/Sources/kryptonit/phonyx",
         "gitlab": "kryptonit/phonyx", "exists": True}]},
    "mr_sync_all": {"success": True, "dry_run": False, "repos": [
        {"repo": "phonyx", "gitlab": "kryptonit/phonyx", "open_mrs": 1,
         "mrs": [{"iid": 41, "title": "Refactor sync",
                  "branch": "feature/sync", "notes": "uploaded"}],
         "branches_sent": ["feature/sync"], "branches_skipped": [],
         "branch_warnings": [], "closed": []}],
        "summary": {"repos": 1, "mrs": 1, "branches_sent": 1,
                    "branches_skipped": 0, "branch_warnings": 0,
                    "notes_uploaded": 1, "notes_unchanged": 0,
                    "closed_marked": 0, "errors": []}},
}


FULL_ID = "1219be2670054df9866ca75e8eda0d5f"


def test_rendered_ids_are_not_truncated_so_they_round_trip():
    cases = {
        "file_list": {"repo": "r", "count": 1, "files": [{"id": FULL_ID, "size": 10, "path": "p.md"}]},
        "respond": {"repo": "r", "request_id": FULL_ID},
        "apply": {"repo": "r", "response_id": FULL_ID},
    }
    for name, payload in cases.items():
        out = render(name, payload)
        text = "\n".join(out) if isinstance(out, list) else out
        assert FULL_ID in text, f"{name} truncated an id the next command needs: {text}"


def test_payloads_cover_every_registry_op():
    assert set(OP_NAMES) == set(REALISTIC), (
        f"payload fixtures out of sync with REGISTRY: "
        f"{set(OP_NAMES) ^ set(REALISTIC)}")


@pytest.mark.parametrize("name", OP_NAMES)
def test_op_has_renderer(name):
    assert name in _RENDERERS, f"op '{name}' falls back to raw JSON output"


@pytest.mark.parametrize("name", OP_NAMES)
def test_render_is_not_json_passthrough(name):
    payload = REALISTIC[name]
    fallback = json.dumps(payload, ensure_ascii=False, indent=2)
    assert render(name, payload) != fallback, (
        f"op '{name}' renders the result dict as raw JSON")


@pytest.mark.parametrize("name", OP_NAMES)
def test_render_realistic_payload_is_text(name):
    out = render(name, REALISTIC[name])
    assert isinstance(out, str)
    assert out.strip(), f"op '{name}' renderer produced empty output"


@pytest.mark.parametrize("name", OP_NAMES)
def test_render_empty_result_is_stable(name):
    first = render(name, {})
    assert isinstance(first, str)
    assert first == render(name, {})


def test_mr_list_surfaces_postbox_errors():
    payload = {"count": 0, "items": [],
               "errors": [{"repo": "phonyx",
                           "error": "fetch/decrypt failed (v3 relay)"}]}
    out = render("mr_list", payload)
    assert "phonyx" in out
    assert "decrypt" in out


def test_mr_list_clean_run_has_no_error_lines():
    out = render("mr_list", {"count": 0, "items": [], "errors": []})
    assert "FAILED" not in out
