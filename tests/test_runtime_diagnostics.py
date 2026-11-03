"""Runtime diagnostics for op_status: local version, configured role, config presence."""
import json
import subprocess
from types import SimpleNamespace

from lgm_core import runtime_diagnostics as rd
from lgm_core.op_models import Ctx
from lgm_core.ops_git import op_status
from lgm_core.render import render


def _git(repo, *args):
    proc = subprocess.run(["git", "-C", str(repo), *args],
                          capture_output=True, text=True, timeout=60, check=False)
    assert proc.returncode == 0, proc.stderr
    return proc.stdout.strip()


def _commit(repo, msg):
    _git(repo, "-c", "user.email=lgm@test", "-c", "user.name=lgm",
         "commit", "--allow-empty", "-m", msg)


def test_local_version_prefers_version_file(tmp_path):
    (tmp_path / "VERSION").write_text("0.4.2\n", encoding="utf-8")
    assert rd.local_version(tmp_path) == "0.4.2"


def test_local_version_falls_back_to_docache_version(tmp_path, monkeypatch):
    monkeypatch.setenv("DOCACHE_VERSION", "9.9.9")
    assert rd.local_version(tmp_path) == "9.9.9"


def test_local_version_falls_back_to_bounded_gitcount(tmp_path, monkeypatch):
    monkeypatch.delenv("DOCACHE_VERSION", raising=False)
    _git(tmp_path, "init")
    _commit(tmp_path, "c1")
    _commit(tmp_path, "c2")
    assert rd.local_version(tmp_path) == "gitcount-2"


def test_local_version_unknown_without_any_source(tmp_path, monkeypatch):
    monkeypatch.delenv("DOCACHE_VERSION", raising=False)
    assert rd.local_version(tmp_path) == "unknown"


def test_local_version_does_not_inherit_parent_repository(tmp_path, monkeypatch):
    monkeypatch.delenv("DOCACHE_VERSION", raising=False)
    _git(tmp_path, "init")
    _commit(tmp_path, "parent repository")
    tools = tmp_path / "deployed-tools"
    tools.mkdir()

    assert rd.local_version(tools) == "unknown"


def test_bounded_count_rejects_absurd_and_non_numeric_values():
    assert rd._bounded_count("408") == "408"
    assert rd._bounded_count("0") == ""
    assert rd._bounded_count(str(rd._GITCOUNT_MAX + 1)) == ""
    assert rd._bounded_count("not-a-number") == ""


def test_config_presence_reports_booleans_without_values(monkeypatch):
    monkeypatch.setattr(rd, "cfg", lambda key, default="": default)
    config = SimpleNamespace(api_key="secret-key", sync_password="secret-pw",
                             pin_sha256="deadbeef", pin_file="")
    present = rd.config_presence(config)
    assert present == {"api_key": True, "sync_password": True,
                       "gitlab": False, "tls_pin": True}
    blob = json.dumps(present)
    assert "secret-key" not in blob
    assert "secret-pw" not in blob
    assert "deadbeef" not in blob


def test_config_presence_gitlab_requires_full_trio(monkeypatch):
    monkeypatch.setattr(rd, "cfg", lambda key, default="": {
        "GITLAB_URL": "https://gitlab.example", "GITLAB_TOKEN": "tok",
        "GITLAB_PROJECT": ""}.get(key, default))
    assert rd.config_presence(SimpleNamespace())["gitlab"] is False

    monkeypatch.setattr(rd, "cfg", lambda key, default="": {
        "GITLAB_URL": "https://gitlab.example", "GITLAB_TOKEN": "tok",
        "GITLAB_PROJECT": "grp/proj"}.get(key, default))
    assert rd.config_presence(SimpleNamespace())["gitlab"] is True


def test_config_presence_tls_pin_accepts_pin_file(monkeypatch):
    monkeypatch.setattr(rd, "cfg", lambda key, default="": default)
    config = SimpleNamespace(pin_sha256="", pin_file="C:/pins/server.pem")
    assert rd.config_presence(config)["tls_pin"] is True


def test_runtime_diagnostics_survives_minimal_config(tmp_path, monkeypatch):
    monkeypatch.delenv("DOCACHE_VERSION", raising=False)
    monkeypatch.setattr(rd, "cfg", lambda key, default="": default)
    config = SimpleNamespace(base_url="https://localhost:1", sync_password="pw")
    diag = rd.runtime_diagnostics(config, root=tmp_path)
    assert diag["local_version"] == "unknown"
    assert diag["machine_role"] == "undetermined"
    eff = diag["effective_config"]
    assert eff["timeout"] == 15
    assert eff["mcp_host_deadline"] == "unknown"
    assert "per-request" in eff["timeout_scope"]
    assert eff["present"]["api_key"] is False
    assert eff["present"]["sync_password"] is True


def test_runtime_diagnostics_role_comes_from_cfg_not_config_default(tmp_path, monkeypatch):
    monkeypatch.setattr(rd, "cfg", lambda key, default="":
                        "work" if key == "LGM_MACHINE_ROLE" else default)
    config = SimpleNamespace(machine_role="home", timeout=30)
    diag = rd.runtime_diagnostics(config, root=tmp_path)
    assert diag["machine_role"] == "work"
    assert diag["effective_config"]["timeout"] == 30


def test_runtime_diagnostics_role_undetermined_when_unset(tmp_path, monkeypatch):
    monkeypatch.setattr(rd, "cfg", lambda key, default="": default)
    config = SimpleNamespace(machine_role="home", timeout=15)

    diag = rd.runtime_diagnostics(config, root=tmp_path)

    assert diag["machine_role"] == "undetermined"


def test_runtime_diagnostics_preserves_zero_timeout(tmp_path):
    config = SimpleNamespace(machine_role="work", timeout=0)

    diag = rd.runtime_diagnostics(config, root=tmp_path)

    assert diag["effective_config"]["timeout"] == 0


class _Client:
    def capabilities(self):
        return {"service": "DocCache"}

    def repos(self):
        return {"repos": ["default"], "current": "default"}

    def tools_version(self):
        return {"version": "0.9.9"}


def test_op_status_adds_runtime_additively(monkeypatch):
    monkeypatch.setattr("lgm_core.ops_git._local_tools_version", lambda root=None: "0.1.0")
    monkeypatch.setattr(rd, "cfg", lambda key, default="":
                        "home" if key == "LGM_MACHINE_ROLE" else default)
    ctx = Ctx(config=SimpleNamespace(base_url="https://localhost:1",
                                     sync_password="pw", machine_role="home",
                                     timeout=15),
              client=_Client())
    res = op_status(ctx, {})
    assert res["tools"] == {"local": "0.1.0", "mirror": "0.9.9", "outdated": True}
    assert res["role_guess"]
    assert res["runtime"]["machine_role"] == "home"
    assert res["runtime"]["effective_config"]["timeout"] == 15


def test_render_status_shows_runtime_lines():
    out = render("status", {
        "capabilities": {"server": {"name": "DocCache", "version": "1"},
                         "sync": {"protocolVersion": 3, "features": {}}},
        "repos": {"repos": ["default"], "current": "default"},
        "role_guess": "home", "base_url": "https://localhost:443",
        "runtime": {
            "local_version": "gitcount-408",
            "machine_role": "home",
            "effective_config": {
                "timeout": 15,
                "timeout_scope": "default HTTP per-request timeout, not a whole-operation deadline",
                "mcp_host_deadline": "unknown",
                "present": {"api_key": True, "sync_password": True,
                            "gitlab": False, "tls_pin": False},
            },
        },
    })
    assert "local version: gitcount-408" in out
    assert "machine role (configured): home" in out
    assert "timeout: 15s" in out
    assert "mcp host deadline: unknown" in out
    assert "api_key=yes" in out
