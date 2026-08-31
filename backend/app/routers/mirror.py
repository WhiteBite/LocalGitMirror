"""
Зеркало корпоративных артефактов: публикация, инвентарь и Maven data plane.

Отличие от ``/api/deps/*`` принципиальное. Там сервер — глупый почтовый ящик:
хранит непрозрачные блобы, никогда не расшифровывает, чистит по TTL. Здесь —
наоборот: это домашнее хранилище, оно обязано понимать содержимое, потому что
его читает локальный gradle, а gradle расшифровывать не умеет.

Почему это не ломает модель угроз: скрытность нужна на РАБОЧЕМ ноуте, где
смотрит EDR. Домашняя машина доверенная целиком, и если она скомпрометирована,
у атакующего и так есть сессия пользователя. Расшифровка здесь ничего не
ослабляет, а взамен даёт главное — одну кнопку на ноуте и ноль действий дома.

Эндпоинты::

    GET  /api/deps/mirror/index    инвентарь (path -> sha256) + wanted
    POST /api/deps/mirror/publish  зашифрованная публикация -> CAS
    GET  /api/deps/mirror/status   диагностика
    GET  /api/deps/m2/{path}       Maven-репозиторий для сборок
"""

from __future__ import annotations

import base64
import hashlib
import io
import ipaddress
import json
import os
import shutil
import tarfile
import tempfile
import zipfile
from pathlib import Path
from typing import List, Optional

from dataclasses import asdict

from fastapi import APIRouter, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, PlainTextResponse

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
from app.core.bundle_crypto import decrypt_dump_bytes
from app.core.corporate_tools import (
    TOOLS_BIN_DIR,
    get_path_instructions,
    install_tool,
    list_tools,
)
from app.core.gradle_init_script import SCRIPT_FILE_NAME, render_init_script
from app.core.npm_cache import (
    DEFAULT_PROTECTED_NPM_SCOPES,
    NpmArtifact,
    add_to_npm_index,
    build_packument,
    build_yarn_projection,
    is_protected_npm_package,
    load_npm_index,
    scan_npm_cache,
    scan_npm_offline_mirror,
)
from app.core.vault_backup import (
    create_backup,
    restore_from_backup,
    _load_last_backup,
    _state_dir,
    _VERIFY_STATE,
)

router = APIRouter(tags=["mirror"])

# Инжектится из main.py в lifespan, как и в остальных роутерах.
repo_manager = None
system_logger = None

#: Предохранитель от zip-бомбы: суммарный распакованный размер публикации.
#: Реальный корпоративный payload — единицы мегабайт (весь ru/kryptonite это
#: 0.3 МБ), так что запас тут кратный, а не притёртый.
MAX_PUBLICATION_UNPACKED = 2 * 1024 * 1024 * 1024
MAX_PUBLICATION_ENCRYPTED = 2 * 1024 * 1024 * 1024

_PREFIX_MAVEN = "maven/"
_MANIFEST_NAME = "manifest.json"


# ─────────────────────────────────────────────────────────────────────────────
# Конфигурация
# ─────────────────────────────────────────────────────────────────────────────

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


def vault_root() -> Path:
    """Каталог хранилища. Переопределяется ``LGM_VAULT_PATH``.

    По умолчанию лежит рядом с транзитным ``.lgm/deps``, но, в отличие от него,
    это КАНОН: TTL нет, ``ack`` не удаляет. Отсюда следует обязанность его
    бэкапить — потеря каталога равна потере зеркала.
    """
    override = os.getenv("LGM_VAULT_PATH", "").strip()
    if override:
        return Path(override)
    if not repo_manager:
        raise HTTPException(500, "Repo manager not initialised")
    return Path(repo_manager.storage_path) / ".lgm" / "vault"


def get_store() -> ArtifactStore:
    return ArtifactStore(vault_root())


def _log(msg: str, extra: Optional[dict] = None) -> None:
    if system_logger:
        system_logger.info(msg, extra or {})


# ─────────────────────────────────────────────────────────────────────────────
# Ограничение доступа к data plane
# ─────────────────────────────────────────────────────────────────────────────

def _is_loopback(host: str) -> bool:
    if not host:
        return False
    if host in ("localhost", "::1"):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _guard_data_plane(request: Request) -> None:
    """Maven-репозиторий обслуживает только локальные сборки.

    Он висит на том же 0.0.0.0:443, что и остальное API, поэтому без этой
    проверки получился бы артефакт-сервер, доступный всей сети. API-ключ —
    не оправдание: у data plane другой профиль использования (его URL попадает
    в конфиги сборки и логи), и ограничить его по адресу дешевле, чем потом
    объяснять утечку. Снять — ``LGM_M2_ALLOW_REMOTE=1``, осознанно.
    """
    if os.getenv("LGM_M2_ALLOW_REMOTE", "").strip() in ("1", "true", "yes"):
        return
    client = request.client.host if request.client else ""
    if not _is_loopback(client):
        # 404, а не 403 — тот же приём, что и в основной авторизации: не
        # подтверждать существование сервиса тому, кто его сканирует.
        raise HTTPException(404, "Not Found")


# ─────────────────────────────────────────────────────────────────────────────
# Инвентарь для дельта-синхронизации
# ─────────────────────────────────────────────────────────────────────────────

@router.get("/api/deps/mirror/index")
def mirror_index():
    """Что уже есть в зеркале и чего сборкам не хватило.

    Рабочая машина вычитает ``inventory`` из своего скана и отправляет только
    дельту, а ``wanted`` заменяет отдельный шаг «дом запросил»: промахи
    накопились сами, и кнопка на ноуте подхватывает их попутно.
    """
    store = get_store()
    return {
        "success": True,
        "schema": 1,
        "protectedGroups": list(protected_groups()),
        "inventory": store.inventory(),
        "wanted": store.list_wanted(state="PENDING"),
        "stats": store.stats(),
    }


@router.get("/api/deps/mirror/wanted")
def mirror_wanted(state: Optional[str] = Query(None)):
    """Список wanted-позиций, опционально отфильтрованный по состоянию.

    ``?state=PENDING`` — только ждущие следующей синхронизации.
    Без параметра — все позиции.
    """
    store = get_store()
    return {
        "success": True,
        "wanted": store.list_wanted(state=state),
    }


@router.post("/api/deps/mirror/wanted/{maven_path:path}/state")
async def mirror_wanted_set_state(maven_path: str, body: dict):
    """Перевести wanted-позицию в новое состояние.

    Тело: ``{"state": "FOUND"}``.
    Допустимые состояния: PENDING, FOUND, NOT_IN_CACHE, FETCHED_FROM_SOURCE,
    CONFLICT, RESOLVED.
    """
    store = get_store()
    state = (body.get("state") or "").strip()
    if not state:
        raise HTTPException(400, "Missing 'state' in request body")
    try:
        ok = store.update_wanted_state(maven_path, state)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if not ok:
        raise HTTPException(404, f"Wanted entry not found: {maven_path}")
    return {"success": True, "path": maven_path, "state": state}


@router.get("/api/deps/mirror/status")
def mirror_status():
    """Диагностика: то, что показывается в панели плагина."""
    store = get_store()
    return {
        "success": True,
        "vault": str(vault_root()),
        "projection": str(store.projection_dir),
        "protectedGroups": list(protected_groups()),
        "stats": store.stats(),
        "conflicts": store.list_conflicts(),
        "wanted": store.list_wanted(),
    }


# ─────────────────────────────────────────────────────────────────────────────
# Публикация
# ─────────────────────────────────────────────────────────────────────────────

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
                # Манифест обещал одно, в архиве другое — молча принимать
                # нельзя, это либо порча при передаче, либо подмена.
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


@router.post("/api/deps/mirror/publish")
async def mirror_publish(
    attachment: UploadFile = File(...),
    repo: str = Form(""),
):
    """Принять публикацию с рабочей машины.

    Одно действие на ноуте — один POST. Дома дальше ничего нажимать не нужно:
    здесь же расшифровываем, заливаем в CAS, пересобираем projection и
    закрываем позиции ``wanted``.
    """
    password = os.getenv("SYNC_PASSWORD", "")
    if not password:
        raise HTTPException(500, "SYNC_PASSWORD не задан — расшифровать публикацию нечем")

    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty publication")
    if len(payload) > MAX_PUBLICATION_ENCRYPTED:
        raise HTTPException(413, "Publication too large")

    try:
        zip_bytes = decrypt_dump_bytes(payload, password)
    except Exception as e:
        # Почти всегда это расхождение SYNC_PASSWORD между машинами —
        # называем причину, иначе диагностика превращается в гадание.
        raise HTTPException(
            400,
            f"Не удалось расшифровать публикацию ({type(e).__name__}). "
            "Проверь, что SYNC_PASSWORD совпадает на обеих машинах.",
        )

    if not zipfile.is_zipfile(io.BytesIO(zip_bytes)):
        raise HTTPException(400, "Расшифрованная публикация не является ZIP-архивом")

    store = get_store()
    try:
        report = import_publication(store, zip_bytes, protected_groups())
    except zipfile.BadZipFile:
        raise HTTPException(400, "Повреждённый ZIP в публикации")

    projection = store.rebuild_projection()
    resolved = store.mark_wanted_resolved(store.inventory().keys())

    _log("mirror publication imported", {
        "repo": repo,
        "added": report.added,
        "existed": report.existed,
        "conflicts": len(report.conflicts),
        "rejected": len(report.rejected),
        "bytes": report.bytes_added,
    })

    return {
        "success": True,
        **report.as_dict(),
        "wantedResolved": resolved,
        "projection": projection,
        "stats": store.stats(),
    }


# ─────────────────────────────────────────────────────────────────────────────
# Maven data plane
# ─────────────────────────────────────────────────────────────────────────────

@router.head("/api/deps/m2/{maven_path:path}")
@router.get("/api/deps/m2/{maven_path:path}")
def maven_get(maven_path: str, request: Request):
    """Отдать артефакт сборке.

    Fail-closed по защищённым группам: промах — 404 и запись в ``wanted``.
    Никакого фолбэка в публичные репозитории здесь нет и быть не должно, иначе
    защищённое имя можно перехватить, опубликовав его в Maven Central.
    """
    _guard_data_plane(request)
    store = get_store()

    # Контрольные суммы: gradle и maven спрашивают их рядом с артефактом.
    # Считаем из индекса, отдельных файлов для этого не держим.
    for suffix, field in ((".sha1", "sha1"), (".sha256", "sha256")):
        if maven_path.endswith(suffix):
            base = maven_path[: -len(suffix)]
            found = store.resolve(base)
            if found is None:
                _record_miss(store, base)
                raise HTTPException(404, "Not Found")
            return PlainTextResponse(found[1][field], media_type="text/plain")

    normalized = normalize_maven_path(maven_path)
    if normalized is None:
        raise HTTPException(400, "Invalid path")

    found = store.resolve(normalized)
    if found is None:
        _record_miss(store, normalized)
        raise HTTPException(404, "Not Found")

    path, entry = found
    return FileResponse(
        path,
        media_type="application/octet-stream",
        filename=Path(normalized).name,
        headers={"X-Checksum-Sha1": entry.get("sha1", "")},
    )


@router.get("/api/deps/mirror/gradle-init")
def mirror_gradle_init(request: Request, base_url: str = Query("")):
    """Отдать текст init-скрипта для ``~/.gradle/init.d/``.

    Генерируется здесь, а не хранится в репозитории, потому что содержит путь к
    хранилищу, адрес data plane и API-ключ. Ответ — готовый файл: сохранил и
    работает на всех проектах этой машины сразу.

    Доступ только с loopback: в теле есть API-ключ.
    """
    _guard_data_plane(request)
    store = get_store()
    # Адрес берём из самого запроса: спрашивает та же машина, которая потом
    # будет собирать, поэтому её представление об адресе и есть верное.
    effective = (base_url or str(request.base_url)).rstrip("/")
    script = render_init_script(
        projection_dir=str(store.projection_dir),
        base_url=effective,
        api_key=os.getenv("API_KEY", ""),
        protected_groups=protected_groups(),
    )
    return PlainTextResponse(
        script,
        media_type="text/plain; charset=utf-8",
        headers={"X-Suggested-Filename": SCRIPT_FILE_NAME},
    )


# ─────────────────────────────────────────────────────────────────────────────
# Backup and restore
# ─────────────────────────────────────────────────────────────────────────────


@router.post("/api/deps/mirror/backup")
def mirror_backup():
    """Trigger vault backup. Returns BackupReport JSON."""
    vault = vault_root()
    backup = vault.parent / "backup"
    report = create_backup(vault, backup)
    _log("mirror backup completed", {"files": report.files_copied, "bytes": report.bytes_copied})
    return {"success": True, **report.as_dict()}


@router.post("/api/deps/mirror/restore")
def mirror_restore(backup_path: str = Form(...)):
    """Restore vault from backup directory. Returns RestoreReport JSON."""
    src = Path(backup_path)
    if not src.is_dir():
        raise HTTPException(400, f"Backup directory not found: {backup_path}")
    target = vault_root()
    report = restore_from_backup(src, target)
    _log("mirror restore completed", {"files": report.files_restored, "errors": report.errors})
    return {"success": True, **report.as_dict()}


@router.get("/api/deps/mirror/backup/status")
def backup_status():
    """Return last backup time, size, and verification status."""
    backup_dir = vault_root().parent / "backup"
    last = _load_last_backup(backup_dir)
    verify = {}
    verify_path = _state_dir(backup_dir) / _VERIFY_STATE
    if verify_path.is_file():
        try:
            verify = json.loads(verify_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            verify = {"error": "unreadable"}
    return {
        "success": True,
        "last_backup": last,
        "last_verify": verify,
        "backup_dir": str(backup_dir),
    }


def _record_miss(store: ArtifactStore, maven_path: str) -> None:
    """Промах по защищённой группе — это заявка, а не просто 404.

    Так очередь ``wanted`` наполняется сама, без отдельного действия дома:
    следующая синхронизация с ноута увидит её в ``/mirror/index``.
    Публичные промахи не пишем — их дом закроет сам через Maven Central.
    """
    if not is_protected_maven_path(maven_path, protected_groups()):
        return
    try:
        store.add_wanted(maven_path, reason="build-miss")
    except Exception:
        # Диагностика не должна ломать выдачу 404.
        pass


# ─────────────────────────────────────────────────────────────────────────────
# Corporate tools
# ─────────────────────────────────────────────────────────────────────────────

@router.get("/api/deps/mirror/tools")
def mirror_tools():
    """List configured corporate tools."""
    tools = list_tools()
    return {
        "success": True,
        "tools": [asdict(t) for t in tools],
        "binDir": str(TOOLS_BIN_DIR),
        "pathInstructions": get_path_instructions(),
    }


@router.post("/api/deps/mirror/tools/install")
async def mirror_tools_install(
    name: str = Form(...),
    version: str = Form(...),
    tarball: UploadFile = File(...),
):
    """Install a corporate tool from tarball."""
    tmp = Path(tempfile.mkdtemp())
    try:
        tarball_path = tmp / "tool.tgz"
        tarball_path.write_bytes(await tarball.read())
        success = install_tool(name, version, tarball_path)
        if not success:
            raise HTTPException(400, "Failed to install tool")
        return {"success": True, "name": name, "version": version}
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ─────────────────────────────────────────────────────────────────────────────
# npm
# ─────────────────────────────────────────────────────────────────────────────

MAX_PUBLICATION_NPM = 500 * 1024 * 1024

_PREFIX_NPM = "npm/"
_NPM_TARBALLS = "tarballs/"
_NPM_MANIFEST = "manifest.json"


def protected_npm_scopes() -> tuple:
    raw = os.getenv("LGM_PROTECTED_NPM_SCOPES", "").strip()
    if not raw:
        return DEFAULT_PROTECTED_NPM_SCOPES
    scopes = tuple(s.strip() for s in raw.split(",") if s.strip())
    return scopes or DEFAULT_PROTECTED_NPM_SCOPES


def import_npm_publication(store: ArtifactStore, zip_bytes: bytes,
                           protected: tuple) -> ImportReport:
    """Разобрать npm-публикацию и залить tarballs в CAS."""
    report = ImportReport()
    vault = vault_root()

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


@router.post("/api/deps/mirror/publish-npm")
async def mirror_publish_npm(
    attachment: UploadFile = File(...),
):
    """Accept encrypted npm publication.

    ZIP contains:
    - manifest.json with npm entries
    - tarballs/<name>-<version>.tgz

    Import to CAS, update npm index.
    """
    password = os.getenv("SYNC_PASSWORD", "")
    if not password:
        raise HTTPException(500, "SYNC_PASSWORD not set")

    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty publication")
    if len(payload) > MAX_PUBLICATION_NPM:
        raise HTTPException(413, "Publication too large")

    try:
        zip_bytes = decrypt_dump_bytes(payload, password)
    except Exception as e:
        raise HTTPException(
            400,
            f"Failed to decrypt publication ({type(e).__name__}). "
            "Check that SYNC_PASSWORD matches on both machines.",
        )

    if not zipfile.is_zipfile(io.BytesIO(zip_bytes)):
        raise HTTPException(400, "Decrypted publication is not a ZIP archive")

    store = get_store()
    try:
        report = import_npm_publication(store, zip_bytes, protected_npm_scopes())
    except zipfile.BadZipFile:
        raise HTTPException(400, "Corrupt ZIP in publication")

    _log("npm publication imported", {
        "added": report.added,
        "existed": report.existed,
        "rejected": len(report.rejected),
        "bytes": report.bytes_added,
    })

    # Rebuild yarn projection
    yarn_dir = Path.home() / ".lgm-yarn-offline"
    yarn_count = build_yarn_projection(vault_root(), yarn_dir, load_npm_index(vault_root()))
    _log("yarn projection rebuilt", {"count": yarn_count})

    return {
        "success": True,
        **report.as_dict(),
        "yarnProjection": yarn_count,
    }


@router.get("/api/deps/npm/{package_name:path}/packument")
def npm_get_packument(package_name: str, request: Request):
    """Serve npm packument (package metadata) from vault index.

    Returns JSON with versions, dist-tags, tarball URLs pointing to localhost.
    """
    _guard_data_plane(request)
    package_name = package_name.strip("/")
    index = load_npm_index(vault_root())
    packument = build_packument(package_name, index)
    if packument is None:
        raise HTTPException(404, "Package not found")
    return packument


@router.get("/api/deps/npm/{package_name:path}")
def npm_get_package(package_name: str, request: Request):
    """Serve npm tarball from CAS.

    Loopback only. Used by npm/yarn when configured to use localhost registry.
    Returns tarball bytes with correct Content-Type.
    """
    _guard_data_plane(request)

    # Parse "<name>/-/<name>-<version>.tgz" or just "<name>@<version>"
    package_name = package_name.strip("/")

    # npm registry URL format: /<package>/-/<file>.tgz
    if "/-/" in package_name:
        pkg_name, _, tgz_file = package_name.rpartition("/-/")
        # Extract version from filename: <pkg>-<version>.tgz
        # For scoped: @scope/pkg-1.0.0.tgz — version starts after the last "-" before ".tgz"
        if tgz_file.endswith(".tgz"):
            stem = tgz_file[:-4]
            # For scoped packages: @scope-name-1.0.0.tgz -> name is @scope/name, version is 1.0.0
            # Simple approach: read from index by scanning
            pkg_name = pkg_name.strip("/")
    else:
        pkg_name = package_name

    index = load_npm_index(vault_root())
    versions = index.get(pkg_name)
    if not versions:
        raise HTTPException(404, "Package not found")

    # Find the latest version
    latest = sorted(versions.keys())[-1]
    info = versions[latest]
    sha256 = info.get("tarball_sha256", "")
    if not sha256:
        raise HTTPException(404, "Tarball not found")

    store = get_store()
    tarball_path = store.cas_path(sha256)
    if not tarball_path.is_file():
        raise HTTPException(404, "Tarball not in CAS")

    return FileResponse(
        tarball_path,
        media_type="application/octet-stream",
        filename=f"{pkg_name.replace('/', '-')}-{latest}.tgz",
        headers={"X-Checksum-Sha1": info.get("shasum", "")},
    )
