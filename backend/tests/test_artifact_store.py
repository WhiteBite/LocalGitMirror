"""
Тесты CAS-хранилища корпоративных артефактов.

Проверяются свойства, на которых держится весь дизайн: immutability,
идемпотентность, отказ подменять байты, точное сравнение namespace и
невозможность выйти за пределы хранилища по пути.
"""

import json

import pytest

from app.core.artifact_store import (
    ADDED,
    CONFLICT,
    EXISTS,
    ArtifactStore,
    MavenCoord,
    coord_from_file,
    is_protected_group,
    is_protected_maven_path,
    normalize_maven_path,
    parse_maven_path,
    sha256_bytes,
    split_classifier_extension,
)

PLUGIN = MavenCoord("ru.kryptonite.build", "kryptonite-gradle-plugin", "2.0.3", "", "jar")


@pytest.fixture()
def store(tmp_path):
    return ArtifactStore(tmp_path / "vault")


# ── политика namespace ───────────────────────────────────────────────────────

def test_protected_group_exact_and_descendants():
    assert is_protected_group("ru.kryptonite")
    assert is_protected_group("ru.kryptonite.build")
    assert is_protected_group("ru.kryptonite.code-quality")


def test_protected_group_rejects_lookalike_namespace():
    """Голый startswith поймал бы это — и чужая группа получила бы доверие
    защищённого пространства имён."""
    assert not is_protected_group("ru.kryptoniteevil")
    assert not is_protected_group("ru.kryptonitex.build")


def test_protected_group_unrelated():
    assert not is_protected_group("org.springframework")
    assert not is_protected_group("")


def test_protected_maven_path_matches_by_segments():
    assert is_protected_maven_path("ru/kryptonite/build/foo/1.0/foo-1.0.jar")
    assert not is_protected_maven_path("ru/kryptoniteevil/foo/1.0/foo-1.0.jar")
    assert not is_protected_maven_path("org/springframework/core/6.0/core-6.0.jar")


# ── разбор имени файла ───────────────────────────────────────────────────────

def test_split_plain_artifact():
    assert split_classifier_extension("foo", "1.0", "foo-1.0.jar") == ("", "jar")


def test_split_classifier_with_dashes():
    assert split_classifier_extension("foo", "1.0", "foo-1.0-linux-x64.jar") == ("linux-x64", "jar")


def test_split_rejects_foreign_file():
    """В каталоге версии может лежать файл от другого артефакта — он не наш."""
    assert split_classifier_extension("foo", "1.0", "bar-1.0.jar") is None


def test_coord_from_file_drops_sources_and_javadoc():
    assert coord_from_file("g", "foo", "1.0", "foo-1.0-sources.jar") is None
    assert coord_from_file("g", "foo", "1.0", "foo-1.0-javadoc.jar") is None
    assert coord_from_file("g", "foo", "1.0", "foo-1.0.jar") is not None


def test_coord_from_file_keeps_module_and_pom():
    assert coord_from_file("g", "foo", "1.0", "foo-1.0.pom").extension == "pom"
    assert coord_from_file("g", "foo", "1.0", "foo-1.0.module").extension == "module"


def test_classifier_and_main_artifact_are_distinct_identities():
    """Ключ по g:n:v склеил бы их, и один молча потерялся бы."""
    main = MavenCoord("g", "foo", "1.0", "", "jar")
    linux = MavenCoord("g", "foo", "1.0", "linux", "jar")
    assert main.maven_path != linux.maven_path


def test_maven_path_layout():
    assert PLUGIN.maven_path == (
        "ru/kryptonite/build/kryptonite-gradle-plugin/2.0.3/"
        "kryptonite-gradle-plugin-2.0.3.jar"
    )


# ── валидация путей ──────────────────────────────────────────────────────────

@pytest.mark.parametrize("bad", [
    "",
    "../../etc/passwd",
    "ru/kryptonite/../../../secret/foo/1.0/foo-1.0.jar",
    "/abs/foo/1.0/foo-1.0.jar",
    "C:/windows/foo/1.0/foo-1.0.jar",
    "ru//kryptonite/foo/1.0/foo-1.0.jar",
    "too/short.jar",
])
def test_normalize_rejects_unsafe_paths(bad):
    assert normalize_maven_path(bad) is None


def test_parse_maven_path_roundtrip():
    assert parse_maven_path(PLUGIN.maven_path) == PLUGIN


# ── запись: идемпотентность и конфликты ──────────────────────────────────────

def test_put_adds_artifact(store):
    r = store.put(b"jar-bytes", PLUGIN)
    assert r.status == ADDED
    assert store.has(PLUGIN.maven_path)


def test_put_same_bytes_twice_is_idempotent(store):
    store.put(b"jar-bytes", PLUGIN)
    r = store.put(b"jar-bytes", PLUGIN)
    assert r.status == EXISTS
    assert len(store.load_index()["entries"]) == 1


def test_put_different_bytes_same_path_is_conflict_and_keeps_active(store):
    """Ключевое свойство: подменить артефакт нельзя. Активной остаётся первая
    версия, новые байты сохраняются, но не подставляются."""
    first = store.put(b"original", PLUGIN)
    second = store.put(b"tampered", PLUGIN)

    assert second.status == CONFLICT
    assert second.existing_sha256 == first.sha256

    path, entry = store.resolve(PLUGIN.maven_path)
    assert path.read_bytes() == b"original", "активная версия не должна меняться"
    assert entry["sha256"] == first.sha256


def test_conflict_is_recorded_with_both_digests(store):
    store.put(b"original", PLUGIN)
    store.put(b"tampered", PLUGIN)
    conflicts = store.list_conflicts()
    assert len(conflicts) == 1
    assert conflicts[0]["active_sha256"] == sha256_bytes(b"original")
    assert conflicts[0]["incoming_sha256"] == sha256_bytes(b"tampered")


def test_conflicting_bytes_are_still_preserved_in_cas(store):
    """Конфликтные байты не выбрасываем — иначе разбирать конфликт будет нечем."""
    store.put(b"original", PLUGIN)
    r = store.put(b"tampered", PLUGIN)
    assert store.cas_path(r.sha256).read_bytes() == b"tampered"


def test_repeated_conflict_increments_count_not_duplicates(store):
    store.put(b"original", PLUGIN)
    store.put(b"tampered", PLUGIN)
    store.put(b"tampered", PLUGIN)
    conflicts = store.list_conflicts()
    assert len(conflicts) == 1
    assert conflicts[0]["count"] == 2


def test_cas_deduplicates_identical_bytes_across_coordinates(store):
    a = MavenCoord("g", "foo", "1.0", "", "jar")
    b = MavenCoord("g", "bar", "1.0", "", "jar")
    r1 = store.put(b"same", a)
    r2 = store.put(b"same", b)
    assert r1.sha256 == r2.sha256
    assert len(list(store.cas_dir.rglob("*"))) == 2  # каталог <ab> + один объект


def test_put_rejects_invalid_coordinate(store):
    with pytest.raises(ValueError):
        store.put(b"x", MavenCoord("g", "..", "1.0", "", "jar"))


# ── устойчивость ─────────────────────────────────────────────────────────────

def test_resolve_returns_none_when_cas_object_missing(store):
    """Индекс есть, байты снесли — честно сообщаем «нет», а не отдаём мусор."""
    r = store.put(b"jar", PLUGIN)
    store.cas_path(r.sha256).unlink()
    assert store.resolve(PLUGIN.maven_path) is None


def test_corrupt_index_does_not_raise(store):
    store.put(b"jar", PLUGIN)
    store.index_path.write_text("{ not json", encoding="utf-8")
    assert store.load_index()["entries"] == {}


def test_put_after_corrupt_index_still_works(store):
    store.index_path.write_text("garbage", encoding="utf-8")
    assert store.put(b"jar", PLUGIN).status == ADDED


def test_index_is_valid_json_on_disk(store):
    store.put(b"jar", PLUGIN)
    data = json.loads(store.index_path.read_text(encoding="utf-8"))
    assert PLUGIN.maven_path in data["entries"]


# ── inventory / wanted ───────────────────────────────────────────────────────

def test_inventory_maps_path_to_digest(store):
    r = store.put(b"jar", PLUGIN)
    assert store.inventory() == {PLUGIN.maven_path: r.sha256}


def test_wanted_dedupes_and_counts(store):
    store.add_wanted(PLUGIN.maven_path)
    store.add_wanted(PLUGIN.maven_path)
    wanted = store.list_wanted()
    assert len(wanted) == 1
    assert wanted[0]["count"] == 2
    assert wanted[0]["state"] == "PENDING"


def test_wanted_records_parsed_coordinate(store):
    store.add_wanted(PLUGIN.maven_path)
    assert store.list_wanted()[0]["coord"]["version"] == "2.0.3"


def test_wanted_resolved_after_publish(store):
    store.add_wanted(PLUGIN.maven_path)
    assert store.mark_wanted_resolved([PLUGIN.maven_path]) == 1
    assert store.list_wanted()[0]["state"] == "RESOLVED"
    assert store.list_wanted(state="PENDING") == []


# ── projection ───────────────────────────────────────────────────────────────

def test_projection_materialises_maven_layout(store):
    store.put(b"jar-bytes", PLUGIN)
    store.rebuild_projection()
    dst = store.projection_dir / PLUGIN.maven_path
    assert dst.is_file()
    assert dst.read_bytes() == b"jar-bytes"


def test_projection_writes_sha1_checksum(store):
    store.put(b"jar-bytes", PLUGIN)
    store.rebuild_projection()
    sha1_file = store.projection_dir / (PLUGIN.maven_path + ".sha1")
    assert sha1_file.is_file()
    assert len(sha1_file.read_text().strip()) == 40


def test_projection_rebuild_is_idempotent(store):
    store.put(b"jar-bytes", PLUGIN)
    store.rebuild_projection()
    second = store.rebuild_projection()
    assert second["linked"] == 0 and second["copied"] == 0


def test_projection_can_be_deleted_and_rebuilt(store):
    """Projection — расходник: удаление не теряет данные."""
    import shutil
    store.put(b"jar-bytes", PLUGIN)
    store.rebuild_projection()
    shutil.rmtree(store.projection_dir)
    store.rebuild_projection()
    assert (store.projection_dir / PLUGIN.maven_path).read_bytes() == b"jar-bytes"


def test_index_rebuilt_from_projection_when_lost(store):
    """Потеря index.json не должна означать потерю зеркала."""
    store.put(b"jar-bytes", PLUGIN)
    store.rebuild_projection()
    store.index_path.unlink()

    report = store.rebuild_index()

    assert report["restored"] == 1
    assert store.resolve(PLUGIN.maven_path)[0].read_bytes() == b"jar-bytes"


def test_stats_reports_artifacts_and_groups(store):
    store.put(b"jar-bytes", PLUGIN)
    s = store.stats()
    assert s["artifacts"] == 1
    assert s["bytes"] == len(b"jar-bytes")
    assert "ru.kryptonite.build" in s["groups"]
