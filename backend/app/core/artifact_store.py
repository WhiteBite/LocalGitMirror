"""
Постоянное хранилище корпоративных артефактов (CAS).

Зачем оно вообще: раньше корп-артефакты жили в ``~/.m2/repository`` — а это
КЕШ, в который одновременно пишут gradle, maven, IDEA, ``publishToMavenLocal``
и старый ``deps apply``. Ничем не отмечено, что вот эти файлы — единственная
копия в доме, поэтому любая чистка кеша убивала их безвозвратно.

Здесь канон. Свойства, из которых всё остальное следует:

* **Content-addressed.** Байты лежат по ``cas/sha256/<ab>/<полный-digest>``.
  Один и тот же файл, пришедший дважды, занимает место один раз.
* **Immutable.** Файл в CAS никогда не перезаписывается. Если под тем же
  maven-путём приходят ДРУГИЕ байты — это конфликт, он фиксируется, а активной
  остаётся прежняя версия. Молча подменять артефакт нельзя: так протаскивают
  подмену зависимости.
* **Атомарность.** Запись идёт во ``staging/`` и переносится ``os.replace``.
  Наполовину записанного артефакта потребитель не увидит никогда.
* **Полная maven-идентичность.** Ключ — group + artifact + version +
  classifier + extension. Не ``g:n:v``: иначе ``foo-1.0.jar`` и
  ``foo-1.0-linux.jar`` конкурируют за одну запись и один молча теряется.

``~/.m2`` и gradle-кеш после этого — расходники: снёс, и следующая сборка
восстановила их отсюда, без участия рабочего ноута.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

# ─────────────────────────────────────────────────────────────────────────────
# Политика пространств имён
# ─────────────────────────────────────────────────────────────────────────────

#: Группы, которые считаются корпоративными и обслуживаются ТОЛЬКО зеркалом.
#: Промах по ним — 404, а не поход в Maven Central: иначе кто угодно может
#: опубликовать туда ``ru.kryptonite:что-нибудь`` и подсунуть его сборке
#: (dependency confusion).
DEFAULT_PROTECTED_MAVEN_GROUPS: Tuple[str, ...] = ("ru.kryptonite",)


def is_protected_group(group: str, protected: Iterable[str] = DEFAULT_PROTECTED_MAVEN_GROUPS) -> bool:
    """Точное совпадение группы или её потомка.

    Сравнение именно такое, а не ``startswith(ns)``: голый префикс поймал бы
    ``ru.kryptoniteevil``, то есть чужую группу, специально названную похоже.
    В решении о доверии таких допущений быть не должно.
    """
    g = (group or "").strip()
    if not g:
        return False
    for ns in protected:
        ns = ns.strip()
        if not ns:
            continue
        if g == ns or g.startswith(ns + "."):
            return True
    return False


def is_protected_maven_path(maven_path: str,
                            protected: Iterable[str] = DEFAULT_PROTECTED_MAVEN_GROUPS) -> bool:
    """То же, но по maven2-пути (``ru/kryptonite/build/foo/1.0/foo-1.0.jar``).

    Нужно на входе HTTP-роутера, где координаты ещё не разобраны. Проверяем
    посегментно, поэтому ``ru/kryptoniteevil/...`` не матчится.
    """
    segs = [s for s in (maven_path or "").replace("\\", "/").split("/") if s]
    if not segs:
        return False
    for ns in protected:
        ns_segs = [s for s in ns.strip().split(".") if s]
        if not ns_segs:
            continue
        if segs[:len(ns_segs)] == ns_segs:
            return True
    return False


# ─────────────────────────────────────────────────────────────────────────────
# Координата артефакта
# ─────────────────────────────────────────────────────────────────────────────

_SAFE_SEG = re.compile(r"^[A-Za-z0-9._+-]+$")


@dataclass(frozen=True)
class MavenCoord:
    """Полная идентичность maven-артефакта.

    ``classifier`` пустой для основного артефакта. ``extension`` без точки
    (``jar``, ``pom``, ``module``, ``aar``, ``klib``).
    """

    group: str
    artifact: str
    version: str
    classifier: str = ""
    extension: str = "jar"

    def validate(self) -> None:
        for name, value in (("group", self.group), ("artifact", self.artifact),
                            ("version", self.version), ("extension", self.extension)):
            if not value:
                raise ValueError(f"{name} обязателен")
        for seg in list(self.group.split(".")) + [self.artifact, self.version, self.extension]:
            if not _SAFE_SEG.match(seg) or seg in (".", ".."):
                raise ValueError(f"недопустимый сегмент координаты: {seg!r}")
        if self.classifier and not _SAFE_SEG.match(self.classifier):
            raise ValueError(f"недопустимый classifier: {self.classifier!r}")

    @property
    def file_name(self) -> str:
        suffix = f"-{self.classifier}" if self.classifier else ""
        return f"{self.artifact}-{self.version}{suffix}.{self.extension}"

    @property
    def maven_path(self) -> str:
        """Путь в maven2-layout, относительный, с прямыми слешами."""
        return f"{self.group.replace('.', '/')}/{self.artifact}/{self.version}/{self.file_name}"

    @property
    def gav(self) -> str:
        return f"{self.group}:{self.artifact}:{self.version}"

    @property
    def label(self) -> str:
        c = f":{self.classifier}" if self.classifier else ""
        return f"{self.gav}{c}@{self.extension}"

    def to_dict(self) -> dict:
        return {
            "group": self.group,
            "artifact": self.artifact,
            "version": self.version,
            "classifier": self.classifier,
            "extension": self.extension,
        }

    @staticmethod
    def from_dict(d: dict) -> "MavenCoord":
        return MavenCoord(
            group=(d.get("group") or "").strip(),
            artifact=(d.get("artifact") or d.get("name") or "").strip(),
            version=(d.get("version") or "").strip(),
            classifier=(d.get("classifier") or "").strip(),
            extension=(d.get("extension") or "jar").strip().lstrip("."),
        )


#: Расширения, которые мы храним. Всё остальное (sources/javadoc/tests) для
#: сборки не нужно и только раздувает трафик.
STORABLE_EXTENSIONS: Tuple[str, ...] = ("jar", "pom", "module", "aar", "klib", "war", "zip")

#: Classifier'ы, которые не нужны для сборки.
SKIP_CLASSIFIERS: Tuple[str, ...] = ("sources", "javadoc", "tests", "test")


def split_classifier_extension(artifact: str, version: str, file_name: str) -> Optional[Tuple[str, str]]:
    """Разобрать имя файла в ``(classifier, extension)``.

    ``foo-1.0.jar`` → ``("", "jar")``; ``foo-1.0-linux-x64.jar`` →
    ``("linux-x64", "jar")``. Возвращает ``None``, если имя не относится к этой
    координате — это и отсекает случай, когда в каталоге версии лежит файл от
    другого артефакта.
    """
    stem_prefix = f"{artifact}-{version}"
    if not file_name.startswith(stem_prefix):
        return None
    rest = file_name[len(stem_prefix):]
    if not rest:
        return None
    if rest.startswith("."):
        return "", rest[1:]
    if rest.startswith("-"):
        rest = rest[1:]
        # classifier может содержать дефисы, extension — нет; поэтому режем
        # по ПОСЛЕДНЕЙ точке.
        if "." not in rest:
            return None
        classifier, _, extension = rest.rpartition(".")
        if not classifier or not extension:
            return None
        return classifier, extension
    return None


def coord_from_file(group: str, artifact: str, version: str, file_name: str) -> Optional[MavenCoord]:
    """Собрать координату из maven-каталога. ``None`` — файл не наш / не нужен."""
    parsed = split_classifier_extension(artifact, version, file_name)
    if parsed is None:
        return None
    classifier, extension = parsed
    if extension.lower() not in STORABLE_EXTENSIONS:
        return None
    if classifier.lower() in SKIP_CLASSIFIERS:
        return None
    return MavenCoord(group, artifact, version, classifier, extension.lower())


# ─────────────────────────────────────────────────────────────────────────────
# Результаты операций
# ─────────────────────────────────────────────────────────────────────────────

ADDED = "added"
EXISTS = "exists"
CONFLICT = "conflict"

#: Допустимые состояния wanted-позиции.
VALID_WANTED_STATES = frozenset({
    "PENDING",
    "FOUND",
    "NOT_IN_CACHE",
    "FETCHED_FROM_SOURCE",
    "CONFLICT",
    "RESOLVED",
})


@dataclass
class PutResult:
    status: str          # ADDED | EXISTS | CONFLICT
    sha256: str
    coord: MavenCoord
    existing_sha256: str = ""

    @property
    def ok(self) -> bool:
        return self.status in (ADDED, EXISTS)


@dataclass
class ImportReport:
    added: int = 0
    existed: int = 0
    conflicts: List[dict] = field(default_factory=list)
    rejected: List[str] = field(default_factory=list)
    bytes_added: int = 0

    def as_dict(self) -> dict:
        return {
            "added": self.added,
            "existed": self.existed,
            "conflicts": self.conflicts,
            "rejected": self.rejected,
            "bytes_added": self.bytes_added,
        }


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha1_bytes(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


# ─────────────────────────────────────────────────────────────────────────────
# Хранилище
# ─────────────────────────────────────────────────────────────────────────────

class ArtifactStore:
    """CAS + индекс maven-ссылок + очередь ``wanted``.

    Раскладка::

        <root>/
          cas/sha256/<ab>/<digest>     неизменяемые байты
          index.json                    maven_path -> {sha256, size, coord, added}
          conflicts.json                зафиксированные расхождения байтов
          wanted.json                   чего сборкам не хватило
          staging/                      временные файлы для атомарной записи
          projections/maven2/           материализованный file:// репозиторий

    Индекс — JSON, потому что реальный объём измеряется десятками записей
    (весь ``ru/kryptonite`` — 61 файл). SQLite здесь был бы инфраструктурой
    ради инфраструктуры; когда ``wanted`` перестанет влезать в JSON, поменяем.
    """

    INDEX_SCHEMA = 1

    def __init__(self, root: Path):
        self.root = Path(root)
        self.cas_dir = self.root / "cas" / "sha256"
        self.staging_dir = self.root / "staging"
        self.index_path = self.root / "index.json"
        self.conflicts_path = self.root / "conflicts.json"
        self.wanted_path = self.root / "wanted.json"
        self.projection_dir = self.root / "projections" / "maven2"
        self._lock = threading.RLock()
        self._ensure_dirs()

    def _ensure_dirs(self) -> None:
        for d in (self.cas_dir, self.staging_dir):
            d.mkdir(parents=True, exist_ok=True)

    # ── низкоуровневый JSON с атомарной записью ──────────────────────────────

    def _read_json(self, path: Path, default):
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return default
        except (json.JSONDecodeError, OSError):
            # Битый индекс не должен ронять сервер: CAS — источник истины,
            # индекс восстанавливается через rebuild_index().
            return default

    def _write_json_atomic(self, path: Path, payload) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + f".tmp{os.getpid()}")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, path)

    # ── индекс ───────────────────────────────────────────────────────────────

    def load_index(self) -> dict:
        data = self._read_json(self.index_path, None)
        if not isinstance(data, dict) or "entries" not in data:
            return {"schema": self.INDEX_SCHEMA, "entries": {}}
        return data

    def _save_index(self, index: dict) -> None:
        index["schema"] = self.INDEX_SCHEMA
        index["updated"] = int(time.time())
        self._write_json_atomic(self.index_path, index)

    def cas_path(self, digest: str) -> Path:
        return self.cas_dir / digest[:2] / digest

    # ── запись ───────────────────────────────────────────────────────────────

    def put(self, data: bytes, coord: MavenCoord) -> PutResult:
        """Положить артефакт. Идемпотентно; конфликт байтов не перезаписывает."""
        coord.validate()
        digest = sha256_bytes(data)
        maven_path = coord.maven_path

        with self._lock:
            index = self.load_index()
            entries = index["entries"]
            existing = entries.get(maven_path)

            if existing:
                if existing.get("sha256") == digest:
                    # Уже есть ровно это. Убедимся, что байты на месте —
                    # CAS мог быть подчищен извне.
                    self._write_cas(data, digest)
                    return PutResult(EXISTS, digest, coord)
                # Тот же путь, другие байты. Сохраняем НОВЫЕ байты в CAS
                # (не теряем их), но активной оставляем прежнюю версию.
                self._write_cas(data, digest)
                self._record_conflict(maven_path, coord, existing.get("sha256", ""), digest)
                return PutResult(CONFLICT, digest, coord, existing.get("sha256", ""))

            self._write_cas(data, digest)
            entries[maven_path] = {
                "sha256": digest,
                "sha1": sha1_bytes(data),
                "size": len(data),
                "coord": coord.to_dict(),
                "added": int(time.time()),
            }
            self._save_index(index)
            return PutResult(ADDED, digest, coord)

    def _write_cas(self, data: bytes, digest: str) -> None:
        """Записать байты в CAS. Уже существующий объект не трогаем."""
        target = self.cas_path(digest)
        if target.exists():
            return
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.staging_dir / f"{digest}.{os.getpid()}.tmp"
        tmp.write_bytes(data)
        try:
            os.replace(tmp, target)
        except OSError:
            # Гонка: кто-то создал объект между exists() и replace().
            # Байты идентичны по определению CAS, так что просто убираем temp.
            tmp.unlink(missing_ok=True)
            if not target.exists():
                raise

    def _record_conflict(self, maven_path: str, coord: MavenCoord,
                         active: str, incoming: str) -> None:
        conflicts = self._read_json(self.conflicts_path, {"entries": []})
        if not isinstance(conflicts, dict):
            conflicts = {"entries": []}
        entries = conflicts.setdefault("entries", [])
        for e in entries:
            if e.get("maven_path") == maven_path and e.get("incoming_sha256") == incoming:
                e["last_seen"] = int(time.time())
                e["count"] = int(e.get("count", 1)) + 1
                break
        else:
            entries.append({
                "maven_path": maven_path,
                "coord": coord.to_dict(),
                "active_sha256": active,
                "incoming_sha256": incoming,
                "first_seen": int(time.time()),
                "last_seen": int(time.time()),
                "count": 1,
            })
        self._write_json_atomic(self.conflicts_path, conflicts)

    def list_conflicts(self) -> List[dict]:
        data = self._read_json(self.conflicts_path, {"entries": []})
        return data.get("entries", []) if isinstance(data, dict) else []

    # ── чтение ───────────────────────────────────────────────────────────────

    def resolve(self, maven_path: str) -> Optional[Tuple[Path, dict]]:
        """Найти артефакт по maven2-пути. ``(файл в CAS, запись индекса)``."""
        maven_path = normalize_maven_path(maven_path)
        if maven_path is None:
            return None
        entry = self.load_index()["entries"].get(maven_path)
        if not entry:
            return None
        path = self.cas_path(entry["sha256"])
        if not path.is_file():
            return None
        return path, entry

    def has(self, maven_path: str) -> bool:
        return self.resolve(maven_path) is not None

    def inventory(self) -> Dict[str, str]:
        """``maven_path -> sha256`` — то, что рабочая машина вычитает из своего
        скана, чтобы отправить только дельту."""
        return {k: v["sha256"] for k, v in self.load_index()["entries"].items()}

    def stats(self) -> dict:
        entries = self.load_index()["entries"]
        total = sum(int(e.get("size", 0)) for e in entries.values())
        groups = sorted({e.get("coord", {}).get("group", "") for e in entries.values()} - {""})
        return {
            "artifacts": len(entries),
            "bytes": total,
            "groups": groups,
            "conflicts": len(self.list_conflicts()),
            "wanted": len(self.list_wanted()),
        }

    # ── очередь wanted ───────────────────────────────────────────────────────

    def _load_wanted(self) -> dict:
        """Загрузить wanted.json с обратной совместимостью.

        Старый формат — список строк ``["ru/kryptonite/..."]`` — при чтении
        конвертируется в новый dict с entries. Сама миграция на диск происходит
        при следующей записи через add_wanted / mark_wanted_resolved / update_wanted_state.
        """
        data = self._read_json(self.wanted_path, {"entries": []})
        if isinstance(data, list):
            now = int(time.time())
            entries = []
            for item in data:
                maven_path = item if isinstance(item, str) else str(item)
                parsed = parse_maven_path(maven_path)
                entries.append({
                    "maven_path": maven_path,
                    "coord": parsed.to_dict() if parsed else None,
                    "reason": "legacy",
                    "state": "PENDING",
                    "first_seen": now,
                    "last_seen": now,
                    "count": 1,
                })
            return {"entries": entries}
        if not isinstance(data, dict):
            return {"entries": []}
        return data

    def add_wanted(self, maven_path: str, reason: str = "miss") -> None:
        """Зафиксировать промах. Отсюда рабочая машина узнаёт, что достать —
        без отдельного запроса с домашней стороны."""
        maven_path = normalize_maven_path(maven_path) or maven_path
        with self._lock:
            data = self._load_wanted()
            entries = data.setdefault("entries", [])
            now = int(time.time())
            for e in entries:
                if e.get("maven_path") == maven_path:
                    e["last_seen"] = now
                    e["count"] = int(e.get("count", 1)) + 1
                    break
            else:
                parsed = parse_maven_path(maven_path)
                entries.append({
                    "maven_path": maven_path,
                    "coord": parsed.to_dict() if parsed else None,
                    "reason": reason,
                    "state": "PENDING",
                    "first_seen": now,
                    "last_seen": now,
                    "count": 1,
                })
            self._write_json_atomic(self.wanted_path, data)

    def update_wanted_state(self, maven_path: str, state: str) -> bool:
        """Перевести wanted-позицию в новое состояние.

        Возвращает True, если позиция найдена и обновлена.
        """
        maven_path = normalize_maven_path(maven_path) or maven_path
        if state not in VALID_WANTED_STATES:
            raise ValueError(
                f"Недопустимое состояние: {state!r}. "
                f"Допустимые: {', '.join(sorted(VALID_WANTED_STATES))}"
            )
        with self._lock:
            data = self._load_wanted()
            for e in data.get("entries", []):
                if e.get("maven_path") == maven_path:
                    e["state"] = state
                    e["last_seen"] = int(time.time())
                    self._write_json_atomic(self.wanted_path, data)
                    return True
        return False

    def list_wanted(self, state: Optional[str] = None) -> List[dict]:
        data = self._load_wanted()
        entries = data.get("entries", [])
        if state:
            entries = [e for e in entries if e.get("state") == state]
        return entries

    def mark_wanted_resolved(self, maven_paths: Iterable[str]) -> int:
        """Закрыть позиции, которые пришли с публикацией."""
        wanted_set = {normalize_maven_path(p) or p for p in maven_paths}
        if not wanted_set:
            return 0
        with self._lock:
            data = self._load_wanted()
            n = 0
            for e in data.get("entries", []):
                if e.get("maven_path") in wanted_set and e.get("state") != "RESOLVED":
                    e["state"] = "RESOLVED"
                    e["resolved_at"] = int(time.time())
                    n += 1
            if n:
                self._write_json_atomic(self.wanted_path, data)
            return n

    # ── projection (file:// репозиторий) ─────────────────────────────────────

    def rebuild_projection(self) -> dict:
        """Материализовать maven2-layout из CAS.

        Нужно затем, что ``gradle --offline`` не может обратиться к localhost по
        HTTP, а ``file://`` репозиторий читает спокойно. Projection —
        производная от CAS и удаляется безболезненно.

        Внутри тома используем hardlink: место не тратится, а подмена файла
        снаружи не сможет испортить CAS (иной inode при записи).
        """
        linked = copied = 0
        index = self.load_index()
        self.projection_dir.mkdir(parents=True, exist_ok=True)
        for maven_path, entry in index["entries"].items():
            src = self.cas_path(entry["sha256"])
            if not src.is_file():
                continue
            dst = self.projection_dir / maven_path
            if dst.is_file() and dst.stat().st_size == int(entry.get("size", -1)):
                continue
            dst.parent.mkdir(parents=True, exist_ok=True)
            tmp = dst.with_suffix(dst.suffix + f".tmp{os.getpid()}")
            tmp.unlink(missing_ok=True)
            try:
                os.link(src, tmp)
                linked += 1
            except OSError:
                tmp.write_bytes(src.read_bytes())
                copied += 1
            os.replace(tmp, dst)
            # Контрольные суммы рядом: maven-клиенты их спрашивают, и без них
            # gradle шумит предупреждениями о непроверяемом артефакте.
            sha1 = entry.get("sha1")
            if sha1:
                (dst.parent / (dst.name + ".sha1")).write_text(sha1, encoding="ascii")
        return {"linked": linked, "copied": copied, "total": len(index["entries"])}

    # ── восстановление индекса ───────────────────────────────────────────────

    def rebuild_index(self) -> dict:
        """Пересобрать индекс из projection, если index.json потерян.

        CAS без индекса не сообщает, какому maven-пути соответствуют байты, но
        projection хранит именно раскладку — по ней восстанавливаем связь.
        """
        if not self.projection_dir.is_dir():
            return {"restored": 0}
        restored = 0
        with self._lock:
            index = self.load_index()
            entries = index["entries"]
            for path in self.projection_dir.rglob("*"):
                if not path.is_file() or path.name.endswith(".sha1"):
                    continue
                rel = path.relative_to(self.projection_dir).as_posix()
                if rel in entries:
                    continue
                coord = parse_maven_path(rel)
                if coord is None:
                    continue
                data = path.read_bytes()
                digest = sha256_bytes(data)
                self._write_cas(data, digest)
                entries[rel] = {
                    "sha256": digest,
                    "sha1": sha1_bytes(data),
                    "size": len(data),
                    "coord": coord.to_dict(),
                    "added": int(time.time()),
                    "restored": True,
                }
                restored += 1
            if restored:
                self._save_index(index)
        return {"restored": restored}


# ─────────────────────────────────────────────────────────────────────────────
# Разбор и проверка путей
# ─────────────────────────────────────────────────────────────────────────────

def normalize_maven_path(raw: str) -> Optional[str]:
    """Привести путь к каноническому виду или отвергнуть.

    Отвергаем всё, что может увести за пределы хранилища: ``..``, абсолютные
    пути, диски, пустые сегменты. Роутер отдаёт наружу файлы по этому пути,
    поэтому проверка обязательна.
    """
    if not raw:
        return None
    p = raw.replace("\\", "/").strip()
    # Проверяем ДО нормализации: иначе strip('/') сам бы убрал ведущий слеш и
    # проверка на абсолютный путь превратилась бы в мёртвый код.
    if p.startswith("/") or ":" in p:
        return None
    p = p.strip("/")
    if not p:
        return None
    segs = p.split("/")
    if any(s in ("", ".", "..") for s in segs):
        return None
    if len(segs) < 4:  # минимум group/artifact/version/file
        return None
    return "/".join(segs)


def parse_maven_path(raw: str) -> Optional[MavenCoord]:
    """``ru/kryptonite/build/foo/1.0/foo-1.0.jar`` → координата."""
    p = normalize_maven_path(raw)
    if p is None:
        return None
    segs = p.split("/")
    file_name = segs[-1]
    version = segs[-2]
    artifact = segs[-3]
    group = ".".join(segs[:-3])
    if not group:
        return None
    parsed = split_classifier_extension(artifact, version, file_name)
    if parsed is None:
        return None
    classifier, extension = parsed
    try:
        coord = MavenCoord(group, artifact, version, classifier, extension.lower())
        coord.validate()
    except ValueError:
        return None
    return coord
