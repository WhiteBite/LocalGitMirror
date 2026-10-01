"""
Разбор и импорт публикаций артефактов (maven/npm) в CAS.

Публикация — зашифрованный ZIP с рабочей машины. Здесь часть без HTTP:
разбор архива, фильтрация по защищённым пространствам имён, заливка в хранилище.
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import tarfile
import zipfile
from typing import Optional

from fastapi import HTTPException

from app.core.artifact_store import (
    ADDED,
    CONFLICT,
    DEFAULT_PROTECTED_MAVEN_GROUPS,
    EXISTS,
    ArtifactStore,
    ImportReport,
    is_protected_maven_path,
    normalize_maven_path,
    parse_maven_path,
    sha256_bytes,
)
from app.core.npm_cache import (
    DEFAULT_PROTECTED_NPM_SCOPES,
    NpmArtifact,
    add_to_npm_index,
    is_protected_npm_package,
)

#: Предохранитель от zip-бомбы: суммарный распакованный размер публикации.
MAX_PUBLICATION_UNPACKED = 2 * 1024 * 1024 * 1024
MAX_PUBLICATION_ENCRYPTED = 2 * 1024 * 1024 * 1024
MAX_PUBLICATION_NPM = 500 * 1024 * 1024

_PREFIX_MAVEN = "maven/"
_MANIFEST_NAME = "manifest.json"

_PREFIX_NPM = "npm/"
_NPM_TARBALLS = "tarballs/"
_NPM_MANIFEST = "manifest.json"


def protected_groups() -> tuple:
    """Защищённые maven-группы. Переопределяются ``LGM_PROTECTED_MAVEN_GROUPS``.

    Список важен не для удобства, а для безопасности: по этим группам зеркало
    работает fail-closed, то есть промах даёт 404 и НЕ проваливается в
    Maven Central. Иначе кто угодно опубликовал бы туда одноимённый артефакт.
    """
    raw = os.getenv("LGM_PROTECTED_MAVEN_GROUPS", "").strip()
    if not raw:
        return DEFAULT_PROTECTED_MAVEN_GROUPS
    groups = tuple(g.strip() for g in raw.split(",") if g.strip())
    return groups or DEFAULT_PROTECTED_MAVEN_GROUPS


def protected_npm_scopes() -> tuple:
    raw = os.getenv("LGM_PROTECTED_NPM_SCOPES", "").strip()
    if not raw:
        return DEFAULT_PROTECTED_NPM_SCOPES
    scopes = tuple(s.strip() for s in raw.split(",") if s.strip())
    return scopes or DEFAULT_PROTECTED_NPM_SCOPES


def import_publication(store: ArtifactStore, zip_bytes: bytes,
                       protected: tuple) -> ImportReport:
    """Разобрать публикацию и залить её в CAS.

    Путь внутри архива — источник истины для координаты: манифест может
    рассинхронизироваться с содержимым, а путь не может. Манифест используется
    только для сверки sha256, если он его сообщает.

    Артефакты вне защищённых пространств имён отвергаются осознанно. Хранилище
    существует для того, чего дом не может достать сам; всё остальное он берёт
    с Maven Central. И если появилась новая корпоративная группа, её надо
    внести в политику явно — потому что её тоже надо обслуживать fail-closed.
    """
    report = ImportReport()
    manifest_hashes = {}

    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        total_unpacked = sum(i.file_size for i in zf.infolist())
        if total_unpacked > MAX_PUBLICATION_UNPACKED:
            raise HTTPException(413, "Publication too large when unpacked")

        if _MANIFEST_NAME in zf.namelist():
            try:
                manifest = json.loads(zf.read(_MANIFEST_NAME).decode("utf-8"))
                for entry in manifest.get("maven", []):
                    p = normalize_maven_path(entry.get("path", ""))
                    if p and entry.get("sha256"):
                        manifest_hashes[p] = entry["sha256"]
            except (json.JSONDecodeError, UnicodeDecodeError, AttributeError):
                # Манифест необязателен: пути внутри архива самодостаточны.
                report.rejected.append(f"{_MANIFEST_NAME}: unreadable")

        for info in zf.infolist():
            name = info.filename
            if info.is_dir() or name == _MANIFEST_NAME:
                continue
            if not name.startswith(_PREFIX_MAVEN):
                report.rejected.append(f"{name}: неизвестный префикс")
                continue

            rel = name[len(_PREFIX_MAVEN):]
            coord = parse_maven_path(rel)
            if coord is None:
                report.rejected.append(f"{rel}: не разбирается как maven-путь")
                continue
            if not is_protected_maven_path(coord.maven_path, protected):
                report.rejected.append(f"{coord.label}: вне защищённых групп")
                continue

            data = zf.read(name)
            declared = manifest_hashes.get(coord.maven_path)
            actual = sha256_bytes(data)
            if declared and declared != actual:
                # Манифест обещал одно, в архиве другое: порча при передаче или подмена.
                report.rejected.append(
                    f"{coord.label}: sha256 не совпал с манифестом")
                continue

            result = store.put(data, coord)
            if result.status == ADDED:
                report.added += 1
                report.bytes_added += len(data)
            elif result.status == EXISTS:
                report.existed += 1
            elif result.status == CONFLICT:
                report.conflicts.append({
                    "artifact": result.coord.label,
                    "path": result.coord.maven_path,
                    "active_sha256": result.existing_sha256,
                    "incoming_sha256": result.sha256,
                })

    return report


def import_npm_publication(store: ArtifactStore, zip_bytes: bytes,
                           protected: tuple) -> ImportReport:
    """Разобрать npm-публикацию и залить tarballs в CAS."""
    report = ImportReport()
    vault = store.root

    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        total_unpacked = sum(i.file_size for i in zf.infolist())
        if total_unpacked > MAX_PUBLICATION_NPM:
            raise HTTPException(413, "Publication too large when unpacked")

        manifest = {}
        if _NPM_MANIFEST in zf.namelist():
            try:
                manifest = json.loads(zf.read(_NPM_MANIFEST).decode("utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError):
                report.rejected.append(f"{_NPM_MANIFEST}: unreadable")

        for info in zf.infolist():
            name = info.filename
            if info.is_dir() or name == _NPM_MANIFEST:
                continue
            if not name.startswith(_PREFIX_NPM):
                report.rejected.append(f"{name}: unknown prefix")
                continue

            rel = name[len(_PREFIX_NPM):]
            if not rel.startswith(_NPM_TARBALLS):
                report.rejected.append(f"{name}: not in tarballs/")
                continue

            tarball_name = rel[len(_NPM_TARBALLS):]
            if not tarball_name.endswith(".tgz"):
                report.rejected.append(f"{name}: not a .tgz")
                continue

            data = zf.read(name)
            pj = _read_package_json_from_tarball_bytes(data)
            if pj is None:
                report.rejected.append(f"{name}: cannot read package.json")
                continue

            pkg_name = pj.get("name", "")
            version = pj.get("version", "")
            if not pkg_name or not version:
                report.rejected.append(f"{name}: missing name/version in package.json")
                continue

            if not is_protected_npm_package(pkg_name, protected):
                report.rejected.append(f"{pkg_name}: outside protected scopes")
                continue

            digest = sha256_bytes(data)
            tarball_path = store.cas_path(digest)

            if tarball_path.exists():
                report.existed += 1
                continue

            store._write_cas(data, digest)

            integ, shasum = _compute_tarball_hashes(data)
            artifact = NpmArtifact(
                name=pkg_name,
                version=version,
                tarball_path=str(tarball_path),
                integrity=integ,
                shasum=shasum,
                packument=pj,
            )
            add_to_npm_index(vault, artifact, digest)
            report.added += 1
            report.bytes_added += len(data)

    return report


def _read_package_json_from_tarball_bytes(data: bytes) -> Optional[dict]:
    """Read package.json from .tgz bytes."""
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tf:
            for member in tf.getmembers():
                parts = member.name.split("/")
                if len(parts) == 2 and parts[0] == "package" and parts[1] == "package.json":
                    if member.isdir():
                        continue
                    f = tf.extractfile(member)
                    if f is None:
                        return None
                    return json.loads(f.read().decode("utf-8"))
        return None
    except (tarfile.TarError, OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None


def _compute_tarball_hashes(data: bytes) -> tuple:
    sha512 = hashlib.sha512(data).digest()
    sha1 = hashlib.sha1(data).hexdigest()
    return f"sha512-{base64.b64encode(sha512).decode('ascii')}", sha1
