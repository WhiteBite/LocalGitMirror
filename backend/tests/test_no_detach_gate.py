"""Structural gate: backend server code must never detach the workspace HEAD.

Bug class (recurred on both machines, see test_detach_head_regression.py):
`git checkout --detach` on the storage workspace stranded HEAD on every
early-return path and re-detached it forever via apply-known. The sync flows
now move refs through neutral namespaces (refs/lgm/*) and attach HEAD with
checkout -f / reset --hard only.

Gate level: AST scan (Python `ast`) over every source file in backend/app —
any "--detach" string constant is a violation. Runs in CI as part of the
normal suite. The sabotage test below proves the scanner catches the shape
of the bug when reintroduced.
"""
import ast
from pathlib import Path

BACKEND_APP = Path(__file__).resolve().parents[1] / "app"


def _scan_source(source: str, filename: str = "<unknown>") -> list[str]:
    """Return violation descriptions for any '--detach' constant in source."""
    violations = []
    tree = ast.parse(source, filename=filename)
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            if node.value.strip() == "--detach":
                violations.append(f"{filename}:{node.lineno}: git '--detach' is forbidden in backend code")
    return violations


def test_scanner_catches_detach_shape():
    """Sabotage test: the bug form reintroduced must be flagged."""
    bad = (
        "import subprocess\n"
        "def f(path):\n"
        "    subprocess.run(['git', 'checkout', '--detach'], cwd=path)\n"
    )
    found = _scan_source(bad, "sabotage.py")
    assert len(found) == 1, f"scanner missed the detach shape: {found}"
    assert "sabotage.py:3" in found[0]

    good = "def g(path):\n    _git(path, 'checkout', '-f', 'main')\n"
    assert _scan_source(good, "clean.py") == []


def test_backend_app_has_no_detach():
    assert BACKEND_APP.is_dir(), f"{BACKEND_APP} missing"
    violations = []
    for py_file in sorted(BACKEND_APP.rglob("*.py")):
        violations.extend(_scan_source(py_file.read_text(encoding="utf-8"), str(py_file.relative_to(BACKEND_APP))))
    assert not violations, "HEAD-detaching code reintroduced:\n" + "\n".join(violations)
