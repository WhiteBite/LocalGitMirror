"""Test that the CLI (lgm.py) generates correct argparse from the REGISTRY."""
import json
import subprocess
import sys
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parent.parent
_LGM = _REPO_ROOT / "lgm.py"


def _run_lgm(*args, timeout=30) -> tuple[int, str, str]:
    """Run lgm.py with args, return (exit_code, stdout, stderr)."""
    proc = subprocess.run(
        [sys.executable, str(_LGM), *args],
        capture_output=True, text=True, timeout=timeout,
        cwd=str(_REPO_ROOT),
    )
    return proc.returncode, proc.stdout, proc.stderr


def test_help_exit_zero():
    """--help must exit 0 and list commands."""
    code, out, err = _run_lgm("--help")
    assert code == 0, f"exit={code} stderr={err}"
    assert "scan" in out
    assert "pending" in out
    assert "status" in out
    assert "branches" in out


def test_no_command_exits_2():
    """No subcommand must exit 2 (usage error)."""
    code, out, err = _run_lgm()
    assert code == 2


def test_unknown_command_exits_2():
    code, out, err = _run_lgm("nonexistent-command")
    assert code == 2


def test_scan_help_exit_zero():
    code, out, err = _run_lgm("scan", "--help")
    assert code == 0, f"exit={code} stderr={err}"
    assert "--gradle-home" in out
    assert "--filter" in out
    assert "--verbose" in out


def test_scan_runs():
    """scan should run and produce output."""
    code, out, err = _run_lgm("scan")
    assert code == 0, f"exit={code} stderr={err}"
    assert "Total artifacts" in out


def test_scan_json_output():
    code, out, err = _run_lgm("scan", "--json")
    assert code == 0, f"exit={code} stderr={err}"
    data = json.loads(out)
    assert "roots" in data
    assert "total_artifacts" in data


def test_scan_verbose_json():
    code, out, err = _run_lgm("scan", "--verbose", "--json")
    assert code == 0, f"exit={code} stderr={err}"
    data = json.loads(out)
    assert "roots" in data


def test_pending_help():
    code, out, err = _run_lgm("pending", "--help")
    assert code == 0
    assert "--repo" in out


def test_request_help():
    code, out, err = _run_lgm("request", "--help")
    assert code == 0
    assert "--project" in out
    assert "--npm-scopes" in out
    assert "--dry-run" in out


def test_apply_help():
    code, out, err = _run_lgm("apply", "--help")
    assert code == 0
    assert "--project" in out
    assert "--npm-install" in out
    assert "--yarn" in out
    assert "--yarn-install" in out
    assert "--dry-run" in out


def test_respond_help():
    code, out, err = _run_lgm("respond", "--help")
    assert code == 0
    assert "--project" in out
    assert "--dry-run" in out


def test_fetch_poms_help():
    code, out, err = _run_lgm("fetch-poms", "--help")
    assert code == 0
    assert "--repo-url" in out
    assert "--dry-run" in out


def test_publish_help():
    code, out, err = _run_lgm("publish", "--help")
    assert code == 0
    assert "--dry-run" in out


def test_debug_help():
    code, out, err = _run_lgm("debug", "--help")
    assert code == 0
    assert "--repo" in out


def test_status_help():
    code, out, err = _run_lgm("status", "--help")
    assert code == 0


def test_branches_help():
    code, out, err = _run_lgm("branches", "--help")
    assert code == 0
    assert "--repo" in out


def test_send_help():
    code, out, err = _run_lgm("send", "--help")
    assert code == 0
    assert "--repo" in out
    assert "--branch" in out
    assert "--project" in out


def test_pull_help():
    code, out, err = _run_lgm("pull", "--help")
    assert code == 0
    assert "--repo" in out
    assert "--branch" in out


def test_deps_request_help():
    code, out, err = _run_lgm("deps_request", "--help")
    assert code == 0
    assert "--manifest" in out


def test_vault_status_help():
    code, out, err = _run_lgm("vault_status", "--help")
    assert code == 0


def test_global_repo_flag_before_subcommand():
    """--repo before the subcommand should be accepted (backward compat)."""
    code, out, err = _run_lgm("--repo", "test-repo", "scan", "--json")
    assert code == 0, f"exit={code} stderr={err}"


def test_dry_run_flags_are_store_true():
    """--dry-run should not require a value (store_true)."""
    code, out, err = _run_lgm("respond", "--repo", "test-repo", "--dry-run", "--json")
    # Will fail on config/network but should parse fine (exit 1, not 2).
    assert code != 2, f"exit={code} stderr={err} — argparse rejected --dry-run"
