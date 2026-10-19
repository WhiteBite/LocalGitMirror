"""CLI stdout/stderr encoding hardening on legacy-codepage Windows consoles."""
import os
import subprocess
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent

_DRIVER = r"""
import sys

sys.path.insert(0, {repo!r})

import lgm

if "--harden" in sys.argv:
    # getattr keeps RED faithful: pre-fix the run must crash with UnicodeEncodeError, not AttributeError.
    harden = getattr(lgm, "harden_stdio", None)
    if harden is not None:
        harden()

print("⚠ MR !24 — QA")
sys.stderr.write("⚠\n")
"""



def _run_driver(tmp_path: Path, *argv: str) -> subprocess.CompletedProcess:
    driver = tmp_path / "lgm_stdio_driver.py"
    driver.write_text(_DRIVER.format(repo=str(_REPO_ROOT)), encoding="utf-8")
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "cp1251"
    env["PYTHONUTF8"] = "0"
    return subprocess.run(
        [sys.executable, str(driver), *argv],
        capture_output=True, env=env, timeout=30,
    )


def test_harden_stdio_prevents_cp1251_crash(tmp_path):
    proc = _run_driver(tmp_path, "--harden")
    assert proc.returncode == 0, f"exit={proc.returncode} stderr={proc.stderr!r}"
    assert "⚠" in proc.stdout.decode("utf-8")


def test_cp1251_console_crashes_without_harden_stdio(tmp_path):
    proc = _run_driver(tmp_path)
    assert proc.returncode != 0
    assert b"UnicodeEncodeError" in proc.stderr
