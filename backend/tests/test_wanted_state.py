"""
Тесты машины состояний wanted-позиций.

Проверяются: создание записи, дедупликация, переходы состояний,
разрешение после публикации, обратная совместимость со старым форматом,
фильтрация по состоянию.
"""

import json

import pytest

from app.core.artifact_store import (
    VALID_WANTED_STATES,
    ArtifactStore,
    MavenCoord,
)

PLUGIN = MavenCoord("ru.kryptonite.build", "kryptonite-gradle-plugin", "2.0.3", "", "jar")
OTHER = MavenCoord("ru.kryptonite.build", "kryptonite-common", "1.0.0", "", "jar")


@pytest.fixture()
def store(tmp_path):
    return ArtifactStore(tmp_path / "vault")


# ── создание и дедупликация ──────────────────────────────────────────────────

def test_add_wanted_creates_pending_entry(store):
    store.add_wanted(PLUGIN.maven_path)
    wanted = store.list_wanted()
    assert len(wanted) == 1
    assert wanted[0]["state"] == "PENDING"
    assert wanted[0]["reason"] == "miss"
    assert wanted[0]["count"] == 1
    assert "first_seen" in wanted[0]
    assert "last_seen" in wanted[0]


def test_add_wanted_with_custom_reason(store):
    store.add_wanted(PLUGIN.maven_path, reason="build-miss")
    assert store.list_wanted()[0]["reason"] == "build-miss"


def test_duplicate_add_wanted_increments_count(store):
    store.add_wanted(PLUGIN.maven_path)
    store.add_wanted(PLUGIN.maven_path)
    store.add_wanted(PLUGIN.maven_path)
    wanted = store.list_wanted()
    assert len(wanted) == 1
    assert wanted[0]["count"] == 3


def test_duplicate_add_wanted_keeps_state(store):
    store.add_wanted(PLUGIN.maven_path)
    wanted = store.list_wanted()[0]
    assert wanted["state"] == "PENDING"
    store.add_wanted(PLUGIN.maven_path)
    assert store.list_wanted()[0]["state"] == "PENDING"


# ── переходы состояний ───────────────────────────────────────────────────────

def test_update_state_transitions(store):
    store.add_wanted(PLUGIN.maven_path)
    assert store.update_wanted_state(PLUGIN.maven_path, "FOUND") is True
    assert store.list_wanted()[0]["state"] == "FOUND"


def test_update_state_all_valid_states(store):
    store.add_wanted(PLUGIN.maven_path)
    for state in sorted(VALID_WANTED_STATES):
        assert store.update_wanted_state(PLUGIN.maven_path, state) is True
        assert store.list_wanted()[0]["state"] == state


def test_update_state_unknown_entry_returns_false(store):
    assert store.update_wanted_state("nonexistent/path/1.0/file-1.0.jar", "FOUND") is False


def test_update_state_invalid_state_raises(store):
    store.add_wanted(PLUGIN.maven_path)
    with pytest.raises(ValueError, match="Недопустимое состояние"):
        store.update_wanted_state(PLUGIN.maven_path, "BOGUS")


def test_update_state_updates_last_seen(store):
    import time
    store.add_wanted(PLUGIN.maven_path)
    first = store.list_wanted()[0]["last_seen"]
    time.sleep(0.01)
    store.update_wanted_state(PLUGIN.maven_path, "FOUND")
    second = store.list_wanted()[0]["last_seen"]
    assert second >= first


# ── разрешение ────────────────────────────────────────────────────────────────

def test_mark_wanted_resolved_sets_state(store):
    store.add_wanted(PLUGIN.maven_path)
    assert store.mark_wanted_resolved([PLUGIN.maven_path]) == 1
    assert store.list_wanted()[0]["state"] == "RESOLVED"
    assert "resolved_at" in store.list_wanted()[0]


def test_mark_wanted_resolved_idempotent(store):
    store.add_wanted(PLUGIN.maven_path)
    store.mark_wanted_resolved([PLUGIN.maven_path])
    assert store.mark_wanted_resolved([PLUGIN.maven_path]) == 0


def test_mark_wanted_resolved_multiple_entries(store):
    store.add_wanted(PLUGIN.maven_path)
    store.add_wanted(OTHER.maven_path)
    assert store.mark_wanted_resolved([PLUGIN.maven_path, OTHER.maven_path]) == 2
    for e in store.list_wanted():
        assert e["state"] == "RESOLVED"


def test_mark_wanted_resolved_partial(store):
    store.add_wanted(PLUGIN.maven_path)
    store.add_wanted(OTHER.maven_path)
    assert store.mark_wanted_resolved([PLUGIN.maven_path]) == 1
    assert store.list_wanted(state="PENDING")[0]["maven_path"] == OTHER.maven_path


def test_mark_wanted_resolved_empty_set_returns_zero(store):
    assert store.mark_wanted_resolved([]) == 0


# ── фильтрация ────────────────────────────────────────────────────────────────

def test_list_wanted_filter_by_state(store):
    store.add_wanted(PLUGIN.maven_path)
    store.add_wanted(OTHER.maven_path)
    store.update_wanted_state(PLUGIN.maven_path, "FOUND")

    assert len(store.list_wanted(state="PENDING")) == 1
    assert store.list_wanted(state="PENDING")[0]["maven_path"] == OTHER.maven_path
    assert len(store.list_wanted(state="FOUND")) == 1
    assert store.list_wanted(state="FOUND")[0]["maven_path"] == PLUGIN.maven_path
    assert len(store.list_wanted(state="RESOLVED")) == 0


def test_list_wanted_no_filter_returns_all(store):
    store.add_wanted(PLUGIN.maven_path)
    store.add_wanted(OTHER.maven_path)
    store.update_wanted_state(PLUGIN.maven_path, "FOUND")
    assert len(store.list_wanted()) == 2


# ── обратная совместимость ───────────────────────────────────────────────────

def test_backward_compat_list_format_migration(store):
    """Старый формат — список строк — должен читаться и конвертироваться."""
    old_format = [PLUGIN.maven_path, OTHER.maven_path]
    store.wanted_path.write_text(json.dumps(old_format), encoding="utf-8")

    wanted = store.list_wanted()
    assert len(wanted) == 2
    assert all(e["state"] == "PENDING" for e in wanted)
    assert all(e["reason"] == "legacy" for e in wanted)
    assert wanted[0]["maven_path"] == PLUGIN.maven_path


def test_backward_compat_migration_persists_on_write(store):
    """После add_wanted старый формат должен записаться как новый на диск."""
    old_format = [PLUGIN.maven_path]
    store.wanted_path.write_text(json.dumps(old_format), encoding="utf-8")

    store.add_wanted(OTHER.maven_path)

    raw = json.loads(store.wanted_path.read_text(encoding="utf-8"))
    assert isinstance(raw, dict)
    assert "entries" in raw
    paths = [e["maven_path"] for e in raw["entries"]]
    assert PLUGIN.maven_path in paths
    assert OTHER.maven_path in paths


def test_empty_wanted_file_returns_empty_list(store):
    assert store.list_wanted() == []
    assert store.list_wanted(state="PENDING") == []


def test_wanted_file_does_not_exist_returns_empty(store):
    assert not store.wanted_path.exists()
    assert store.list_wanted() == []


# ── координаты ────────────────────────────────────────────────────────────────

def test_wanted_records_parsed_coordinate(store):
    store.add_wanted(PLUGIN.maven_path)
    coord = store.list_wanted()[0]["coord"]
    assert coord["group"] == "ru.kryptonite.build"
    assert coord["artifact"] == "kryptonite-gradle-plugin"
    assert coord["version"] == "2.0.3"


def test_wanted_unparseable_path_stores_null_coord(store):
    store.add_wanted("just/a/string/without/coords")
    assert store.list_wanted()[0]["coord"] is None


# ── интеграция со stats ──────────────────────────────────────────────────────

def test_stats_includes_wanted_count(store):
    store.add_wanted(PLUGIN.maven_path)
    store.add_wanted(OTHER.maven_path)
    assert store.stats()["wanted"] == 2


def test_stats_resolved_not_double_counted(store):
    store.add_wanted(PLUGIN.maven_path)
    store.mark_wanted_resolved([PLUGIN.maven_path])
    assert store.stats()["wanted"] == 1  # всё ещё в списке, но RESOLVED
