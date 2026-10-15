"""op_apply deps-zip consumer contract: maven/legacy layouts, __meta__ lock, npm mirror."""
import io
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

from lgm_core.crypto import encrypt_bundle
from lgm_core.ops import Ctx, op_apply

PWD = "test-sync-password"
SHA = "0123456789abcdef0123456789abcdef01234567"
_ITEM = {"id": "resp1", "size": 1, "mtime": 1}


def _zip(entries: dict) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return buf.getvalue()


class _RespClient:
    def __init__(self, zip_bytes: bytes):
        self.sync_password = PWD
        self._zip = zip_bytes
        self.acks = []

    def deps_responses(self, repo):
        return {"items": [_ITEM]}

    def deps_fetch(self, repo, item_id):
        return encrypt_bundle(self._zip, PWD)

    def deps_ack(self, repo, item_id):
        self.acks.append(item_id)
        return {"acked": item_id}


@pytest.fixture()
def env(monkeypatch, tmp_path):
    m2 = tmp_path / "m2"
    npm = tmp_path / "npm-mirror"
    monkeypatch.setattr("lgm_core.ops_deps.maven_local_root", lambda: m2)
    monkeypatch.setattr("lgm_core.ops_deps.npm_offline_mirror", lambda: npm)
    return SimpleNamespace(m2=m2, npm=npm, tmp=tmp_path)


def _ctx(zip_bytes: bytes) -> Ctx:
    config = SimpleNamespace(sync_password=PWD, deps_automation="off",
                             machine_role="home")
    return Ctx(config=config, client=_RespClient(zip_bytes))


def test_apply_maven_layout_unpacks_to_maven_local_root(env):
    ctx = _ctx(_zip({
        "gradle/com/example/demo/1.0/demo-1.0.jar": b"jar-bytes",
        "gradle/com/example/demo/1.0/demo-1.0.pom": b"pom-bytes",
    }))
    res = op_apply(ctx, {"repo": "r"})
    assert res["installed"] == 2 and res["skipped"] == 0 and res["invalid"] == 0
    assert res["layout"] == "maven"
    assert res["remaining"] == 0
    assert (env.m2 / "com/example/demo/1.0/demo-1.0.jar").read_bytes() == b"jar-bytes"
    assert (env.m2 / "com/example/demo/1.0/demo-1.0.pom").read_bytes() == b"pom-bytes"
    assert ctx.client.acks == [_ITEM["id"]]


def test_apply_legacy_sha_dir_layout_converts_to_maven_layout(env):
    ctx = _ctx(_zip({
        f"gradle/com.example/demo/1.0/{SHA}/demo-1.0.jar": b"jar-bytes",
        f"gradle/com.example/demo/1.0/{SHA}/demo-1.0.pom": b"pom-bytes",
    }))
    res = op_apply(ctx, {"repo": "r"})
    assert res["installed"] == 2 and res["invalid"] == 0
    assert res["layout"] == "gradle"
    assert (env.m2 / "com/example/demo/1.0/demo-1.0.jar").read_bytes() == b"jar-bytes"
    assert (env.m2 / "com/example/demo/1.0/demo-1.0.pom").read_bytes() == b"pom-bytes"


def test_apply_mixed_layouts_in_one_zip(env):
    ctx = _ctx(_zip({
        "gradle/org/acme/lib/2.0/lib-2.0.jar": b"maven-jar",
        f"gradle/com.example/demo/1.0/{SHA}/demo-1.0.jar": b"legacy-jar",
    }))
    res = op_apply(ctx, {"repo": "r"})
    assert res["installed"] == 2 and res["invalid"] == 0
    assert res["layout"] == "gradle"
    assert (env.m2 / "org/acme/lib/2.0/lib-2.0.jar").read_bytes() == b"maven-jar"
    assert (env.m2 / "com/example/demo/1.0/demo-1.0.jar").read_bytes() == b"legacy-jar"


def test_apply_meta_package_lock_written_to_project_dir(env):
    proj = env.tmp / "proj"
    proj.mkdir()
    ctx = _ctx(_zip({
        "gradle/com/example/demo/1.0/demo-1.0.jar": b"jar-bytes",
        "__meta__/package-lock.json": b'{"lockfileVersion": 3}',
    }))
    res = op_apply(ctx, {"repo": "r", "project": str(proj)})
    assert res["installed"] == 1
    assert res["npm_lock"] == {"rewritten": 0}
    assert (proj / "package-lock.json").read_bytes() == b'{"lockfileVersion": 3}'


def test_apply_meta_package_lock_corporate_registry_rewritten_to_npmjs(env):
    proj = env.tmp / "proj"
    proj.mkdir()
    (proj / ".npmrc").write_text("registry=https://npm.corp.example/\n")
    lock = b'{"resolved": "https://npm.corp.example/left-pad/-/left-pad-1.3.0.tgz"}'
    ctx = _ctx(_zip({"__meta__/package-lock.json": lock}))
    res = op_apply(ctx, {"repo": "r", "project": str(proj)})
    assert res["npm_lock"] == {"rewritten": 1}
    written = (proj / "package-lock.json").read_text()
    assert "https://registry.npmjs.org/left-pad/-/left-pad-1.3.0.tgz" in written
    assert "npm.corp.example" not in written


def test_apply_meta_package_lock_without_project_is_not_written(env):
    ctx = _ctx(_zip({"__meta__/package-lock.json": b'{"lockfileVersion": 3}'}))
    res = op_apply(ctx, {"repo": "r"})
    assert res["installed"] == 0
    assert "npm_lock" not in res


def test_apply_npm_entries_land_in_npm_offline_mirror(env, monkeypatch):
    calls = []

    def fake_run(cmd, **kw):
        calls.append(cmd)
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr("lgm_core.ops_deps.subprocess",
                        SimpleNamespace(run=fake_run))
    ctx = _ctx(_zip({
        "npm/left-pad/left-pad-1.3.0.tgz": b"tgz-1",
        "npm/my-scope/pkg/pkg-2.0.0.tgz": b"tgz-2",
    }))
    res = op_apply(ctx, {"repo": "r"})
    assert res["installed"] == 2 and res["layout"] is None
    assert (env.npm / "left-pad/left-pad-1.3.0.tgz").read_bytes() == b"tgz-1"
    assert (env.npm / "my-scope/pkg/pkg-2.0.0.tgz").read_bytes() == b"tgz-2"
    assert res["npm"] == {"ok": 2, "fail": 0, "mirror": str(env.npm)}
    added = {c[-1] for c in calls}
    assert added == {str(env.npm / "left-pad/left-pad-1.3.0.tgz"),
                     str(env.npm / "my-scope/pkg/pkg-2.0.0.tgz")}
    assert all(c[-3:-1] == ["cache", "add"] for c in calls)


@pytest.mark.xfail(
    reason="layout heuristic routes maven entries whose version is exactly 40 hex "
           "chars as legacy sha-dir and drops the version dir",
    strict=True,
)
def test_apply_maven_entry_with_40_hex_version_lands_in_version_dir(env):
    version = "b" * 40
    ctx = _ctx(_zip({f"gradle/com/example/demo/{version}/demo.jar": b"jar-bytes"}))
    res = op_apply(ctx, {"repo": "r"})
    assert res["layout"] == "maven"
    assert (env.m2 / f"com/example/demo/{version}/demo.jar").read_bytes() == b"jar-bytes"


def test_apply_dry_run_counts_entries_and_writes_nothing(env):
    proj = env.tmp / "proj"
    proj.mkdir()
    ctx = _ctx(_zip({
        "gradle/com/example/demo/1.0/demo-1.0.jar": b"jar-bytes",
        "npm/left-pad/left-pad-1.3.0.tgz": b"tgz-1",
        "__meta__/package-lock.json": b'{"lockfileVersion": 3}',
    }))
    res = op_apply(ctx, {"repo": "r", "project": str(proj), "dry_run": True})
    assert res["dry_run"] is True and res["entry_count"] == 3
    assert set(res["sample"]) == {
        "gradle/com/example/demo/1.0/demo-1.0.jar",
        "npm/left-pad/left-pad-1.3.0.tgz",
        "__meta__/package-lock.json",
    }
    assert env.m2.is_dir() and not any(env.m2.iterdir())
    assert not env.npm.exists()
    assert not (proj / "package-lock.json").exists()
    assert ctx.client.acks == []


def test_apply_reapply_skips_unchanged_entries(env):
    ctx = _ctx(_zip({
        "gradle/com/example/demo/1.0/demo-1.0.jar": b"jar-bytes",
        "gradle/com/example/demo/1.0/demo-1.0.pom": b"pom-bytes",
    }))
    first = op_apply(ctx, {"repo": "r"})
    assert first["installed"] == 2 and first["skipped"] == 0
    second = op_apply(ctx, {"repo": "r"})
    assert second["installed"] == 0 and second["skipped"] == 2
    assert ctx.client.acks == [_ITEM["id"], _ITEM["id"]]


def test_apply_rejects_traversal_and_malformed_entries(env):
    ctx = _ctx(_zip({
        "README": b"x",
        "gradle/../evil.jar": b"x",
        "gradle//abs.jar": b"x",
        "gradle/com/example/demo/1.0/demo-1.0.jar": b"jar-bytes",
    }))
    res = op_apply(ctx, {"repo": "r"})
    assert res["installed"] == 1 and res["invalid"] == 3
    assert not (env.tmp / "evil.jar").exists()
    assert (env.m2 / "com/example/demo/1.0/demo-1.0.jar").read_bytes() == b"jar-bytes"


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
