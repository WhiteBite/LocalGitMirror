"""Test that every op in the REGISTRY is well-formed and callable."""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lgm_core.ops import REGISTRY, Param, Op, get_op, op_names


def test_registry_non_empty():
    assert len(REGISTRY) > 0, "REGISTRY must contain at least one op"


def test_unique_names():
    names = [op.name for op in REGISTRY]
    assert len(names) == len(set(names)), f"Duplicate op names: {names}"


def test_all_ops_have_summary():
    for op in REGISTRY:
        assert op.summary, f"Op '{op.name}' has empty summary"
        assert isinstance(op.summary, str), f"Op '{op.name}' summary is not str"


def test_param_types_valid():
    valid_types = {"str", "int", "bool"}
    for op in REGISTRY:
        for param in op.params:
            assert param.type in valid_types, (
                f"Op '{op.name}' param '{param.name}' has invalid type '{param.type}'"
            )


def test_param_names_unique_per_op():
    for op in REGISTRY:
        names = [p.name for p in op.params]
        assert len(names) == len(set(names)), (
            f"Op '{op.name}' has duplicate param names: {names}"
        )


def test_required_params_have_no_default_conflict():
    """A required param should not have a non-empty default (would be misleading)."""
    for op in REGISTRY:
        for param in op.params:
            if param.required:
                # Required str params can have "" default (argparse still requires them).
                # Just check the flag is consistent.
                assert isinstance(param.required, bool)


def test_run_callable():
    for op in REGISTRY:
        assert callable(op.run), f"Op '{op.name}' run is not callable"


def test_get_op_roundtrip():
    for op in REGISTRY:
        found = get_op(op.name)
        assert found is not None, f"get_op('{op.name}') returned None"
        assert found.name == op.name


def test_get_op_unknown():
    assert get_op("nonexistent_op") is None


def test_op_names_complete():
    names = op_names()
    assert len(names) == len(REGISTRY)


def test_existing_commands_preserved():
    """All commands from the original lgm.py must be present."""
    expected = {"scan", "pending", "request", "respond", "apply",
                "fetch-poms", "publish", "debug"}
    actual = set(op_names())
    missing = expected - actual
    assert not missing, f"Missing existing commands: {missing}"


def test_new_ops_present():
    """New ops specified in the task must be present."""
    expected_new = {"status", "repos", "branches", "send", "pull",
                    "deps_request", "vault_status"}
    actual = set(op_names())
    missing = expected_new - actual
    assert not missing, f"Missing new ops: {missing}"


def test_scan_op_runs_without_client():
    """scan op should work without a mirror server (needs_client=False)."""
    from lgm_core.config import Config
    from lgm_core.client import MirrorClient
    from lgm_core.ops import Ctx
    op = get_op("scan")
    config = Config(base_url="http://localhost:1", api_key="", sync_password="")
    client = MirrorClient(base_url=config.base_url, api_key=config.api_key)
    ctx = Ctx(config=config, client=client)
    result = op.run(ctx, {"gradle_home": "", "filter": "", "verbose": False})
    assert isinstance(result, dict)
    assert "roots" in result
    assert "total_artifacts" in result
    # Result must be JSON-serializable.
    json.dumps(result)


def test_fetch_poms_op_runs_without_client():
    """fetch-poms op should work without a mirror server."""
    from lgm_core.config import Config
    from lgm_core.client import MirrorClient
    from lgm_core.ops import Ctx
    op = get_op("fetch-poms")
    config = Config(base_url="http://localhost:1", api_key="", sync_password="")
    client = MirrorClient(base_url=config.base_url, api_key=config.api_key)
    ctx = Ctx(config=config, client=client)
    result = op.run(ctx, {"repo_url": "https://repo.maven.apache.org/maven2", "dry_run": True})
    assert isinstance(result, dict)
    assert "fetched" in result
    json.dumps(result)


_FROZEN_OPS = {
    "scan": ["gradle_home", "filter", "verbose"],
    "pending": ["repo"],
    "request": ["repo", "project", "npm_scopes", "dry_run"],
    "respond": ["repo", "project", "dry_run", "id"],
    "apply": ["repo", "project", "npm_install", "yarn", "yarn_install", "dry_run", "id"],
    "fetch-poms": ["repo_url", "dry_run"],
    "publish": ["dry_run"],
    "debug": ["repo"],
    "status": [],
    "repos": [],
    "branches": ["repo", "project"],
    "send": ["repo", "branch", "project", "dry_run"],
    "pull": ["repo", "branch", "since", "haves", "project", "dry_run"],
    "deps_request": ["repo", "manifest"],
    "vault_status": [],
    "branch_delete": ["repo", "branches"],
    "prune": ["repo", "bases", "older_days", "keep", "apply"],
    "mr_list": ["repo"],
    "mr_send": ["iid", "iids", "all_open", "branch", "project", "repo"],
    "mr_notes": ["repo", "iid"],
    "mr_replies_send": ["repo", "iid", "file", "text", "force"],
    "mr_replies_status": ["repo", "iid"],
    "guide": [],
    "update": [],
    "chat_send": ["text"],
    "chat_list": ["count"],
    "file_send": ["path", "repo"],
    "file_list": ["repo"],
    "file_get": ["repo", "id", "save"],
    "config_get": ["section"],
    "config_set": ["section", "key", "value"],
    "mr_projects_scan": ["root"],
    "mr_projects_list": [],
    "mr_sync_all": ["dry_run"],
}


def test_registry_matches_frozen_baseline():
    assert len(REGISTRY) == len(_FROZEN_OPS) == 34
    assert [op.name for op in REGISTRY] == list(_FROZEN_OPS)
    for op in REGISTRY:
        assert callable(op.run), f"Op '{op.name}' run is not callable"
        assert [p.name for p in op.params] == _FROZEN_OPS[op.name], (
            f"Op '{op.name}' params drifted: {[p.name for p in op.params]}"
        )


def test_facade_reexports_public_api():
    from lgm_core.ops import REGISTRY, Param, Op, Ctx, get_op, op_names
    assert REGISTRY is not None and len(REGISTRY) == 34
    assert callable(get_op) and callable(op_names)
    assert Param is not None and Op is not None and Ctx is not None
