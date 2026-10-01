"""
Зеркало корпоративных артефактов: публикация, инвентарь и Maven data plane.

Отличие от ``/api/documents/*`` принципиальное. Там сервер — глупый почтовый ящик:
хранит непрозрачные блобы, никогда не расшифровывает, чистит по TTL. Здесь —
наоборот: это домашнее хранилище, оно обязано понимать содержимое, потому что
его читает локальный gradle, а gradle расшифровывать не умеет.

Почему это не ломает модель угроз: скрытность нужна на РАБОЧЕМ ноуте, где
смотрит EDR. Домашняя машина доверенная целиком, и если она скомпрометирована,
у атакующего и так есть сессия пользователя. Расшифровка здесь ничего не
ослабляет, а взамен даёт главное — одну кнопку на ноуте и ноль действий дома.

Эндпоинты::

    GET  /api/cache/index    инвентарь (path -> sha256) + wanted
    POST /api/cache/publish  зашифрованная публикация -> CAS
    GET  /api/cache/status   диагностика
    GET  /api/cache/m2/{path}       Maven-репозиторий для сборок
"""

from __future__ import annotations

import io
import json
import os
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import Optional
from urllib.parse import unquote

from dataclasses import asdict

from fastapi import APIRouter, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, PlainTextResponse
from starlette.concurrency import run_in_threadpool

from app.core import hybrid_crypto
from app.core.artifact_publication import (
    MAX_PUBLICATION_ENCRYPTED,
    MAX_PUBLICATION_NPM,
    import_npm_publication,
    import_publication,
    protected_groups,
    protected_npm_scopes,
)
from app.core.artifact_store import ArtifactStore, normalize_maven_path
from app.core.bundle_crypto import decrypt_dump_bytes
from app.core import corporate_tools as ct_core
from app.core.corporate_tools import (
    get_path_instructions,
    install_tool,
    list_tools,
)
from app.core.gradle_init_script import SCRIPT_FILE_NAME, render_init_script
from app.core.mirror_dataplane import _guard_data_plane, _record_miss
from app.core.npm_cache import (
    build_packument,
    build_yarn_projection,
    load_npm_index,
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
# X25519-ключ сервера (v3), инжектится из main.py; None => только пароль.
server_private_key = None


# ─────────────────────────────────────────────────────────────────────────────
# Конфигурация
# ─────────────────────────────────────────────────────────────────────────────

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
# Инвентарь для дельта-синхронизации
# ─────────────────────────────────────────────────────────────────────────────

@router.get("/api/cache/index")
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


@router.get("/api/cache/wanted")
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


@router.post("/api/cache/wanted/{maven_path:path}/state")
def mirror_wanted_set_state(maven_path: str, body: dict):
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


@router.get("/api/cache/status")
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

@router.post("/api/cache/publish")
async def mirror_publish(
    attachment: UploadFile = File(...),
    repo: str = Form(""),
    k: str = Form(""),
):
    """Принять публикацию с рабочей машины.

    Одно действие на ноуте — один POST. Дома дальше ничего нажимать не нужно:
    здесь же расшифровываем, заливаем в CAS, пересобираем projection и
    закрываем позиции ``wanted``.
    """
    password = os.getenv("SYNC_PASSWORD", "")
    if not k and not password:
        raise HTTPException(500, "SYNC_PASSWORD не задан — расшифровать публикацию нечем")

    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty publication")
    if len(payload) > MAX_PUBLICATION_ENCRYPTED:
        raise HTTPException(413, "Publication too large")

    def _import() -> dict:
        if k and server_private_key is not None:
            try:
                ctx = hybrid_crypto.HybridServerContext(
                    server_private_key, hybrid_crypto.decode_epk(k)
                )
                zip_bytes = ctx.open_relay(payload, hybrid_crypto.RELAY_AAD_VAULT)
            except Exception:
                raise HTTPException(
                    400,
                    "Не удалось расшифровать публикацию (v3): неверный ключ сервера, "
                    "повреждённый блоб или чужая AAD.",
                )
        else:
            try:
                zip_bytes = decrypt_dump_bytes(payload, password)
            except Exception as e:
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

    return await run_in_threadpool(_import)


# ─────────────────────────────────────────────────────────────────────────────
# Maven data plane
# ─────────────────────────────────────────────────────────────────────────────

@router.head("/api/cache/m2/{maven_path:path}")
@router.get("/api/cache/m2/{maven_path:path}")
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


@router.get("/api/cache/gradle-init")
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


@router.post("/api/cache/backup")
def mirror_backup():
    """Trigger vault backup. Returns BackupReport JSON."""
    vault = vault_root()
    backup = vault.parent / "backup"
    report = create_backup(vault, backup)
    _log("mirror backup completed", {"files": report.files_copied, "bytes": report.bytes_copied})
    return {"success": True, **report.as_dict()}


@router.post("/api/cache/restore")
def mirror_restore(backup_path: str = Form(...)):
    """Restore vault from backup directory. Returns RestoreReport JSON."""
    src = Path(backup_path)
    if not src.is_dir():
        raise HTTPException(400, f"Backup directory not found: {backup_path}")
    target = vault_root()
    report = restore_from_backup(src, target)
    _log("mirror restore completed", {"files": report.files_restored, "errors": report.errors})
    return {"success": True, **report.as_dict()}


@router.get("/api/cache/backup/status")
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


# ─────────────────────────────────────────────────────────────────────────────
# Corporate tools
# ─────────────────────────────────────────────────────────────────────────────

@router.get("/api/cache/tools")
def mirror_tools():
    """List configured corporate tools."""
    tools = list_tools()
    return {
        "success": True,
        "tools": [asdict(t) for t in tools],
            "binDir": str(ct_core.tools_bin_dir()),
        "pathInstructions": get_path_instructions(),
    }


@router.post("/api/cache/tools/install")
async def mirror_tools_install(
    name: str = Form(...),
    version: str = Form(...),
    tarball: UploadFile = File(...),
):
    """Install a corporate tool from tarball."""
    payload = await tarball.read()

    def _install() -> dict:
        tmp = Path(tempfile.mkdtemp())
        try:
            tarball_path = tmp / "tool.tgz"
            tarball_path.write_bytes(payload)
            success = install_tool(name, version, tarball_path)
            if not success:
                raise HTTPException(400, "Failed to install tool")
            return {"success": True, "name": name, "version": version}
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    return await run_in_threadpool(_install)


# ─────────────────────────────────────────────────────────────────────────────
# npm
# ─────────────────────────────────────────────────────────────────────────────

@router.post("/api/cache/publish-npm")
async def mirror_publish_npm(
    attachment: UploadFile = File(...),
    k: str = Form(""),
):
    """Accept encrypted npm publication.

    ZIP contains:
    - manifest.json with npm entries
    - tarballs/<name>-<version>.tgz

    Import to CAS, update npm index.
    """
    password = os.getenv("SYNC_PASSWORD", "")
    if not k and not password:
        raise HTTPException(500, "SYNC_PASSWORD not set")

    payload = await attachment.read()
    if not payload:
        raise HTTPException(400, "Empty publication")
    if len(payload) > MAX_PUBLICATION_NPM:
        raise HTTPException(413, "Publication too large")

    def _import() -> dict:
        if k and server_private_key is not None:
            try:
                ctx = hybrid_crypto.HybridServerContext(
                    server_private_key, hybrid_crypto.decode_epk(k)
                )
                zip_bytes = ctx.open_relay(payload, hybrid_crypto.RELAY_AAD_VAULT)
            except Exception:
                raise HTTPException(
                    400,
                    "Failed to decrypt v3 publication (wrong server key, "
                    "corrupted blob or foreign AAD).",
                )
        else:
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

        yarn_dir = Path.home() / ".lgm-yarn-offline"
        yarn_count = build_yarn_projection(vault_root(), yarn_dir, load_npm_index(vault_root()))
        _log("yarn projection rebuilt", {"count": yarn_count})

        return {
            "success": True,
            **report.as_dict(),
            "yarnProjection": yarn_count,
        }

    return await run_in_threadpool(_import)


@router.get("/api/cache/npm/{package_name:path}/packument")
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


_SEMVER_RE = re.compile(
    r"^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.\-+]+))?$"
)


def _semver_key(version: str) -> tuple:
    """Sort key with numeric major/minor/patch and release > prerelease."""
    m = _SEMVER_RE.match(version.strip())
    if m is None:
        return (0, 0, 0, 0, "")
    major, minor, patch = (int(g or 0) for g in m.group(1, 2, 3))
    pre = m.group(4) or ""
    return (major, minor, patch, 0 if pre else 1, pre)


def _match_tgz_version(tgz_file: str, pkg_name: str, versions: dict) -> Optional[str]:
    """Resolve the version encoded in a tarball filename against the index.

    Accepts the filename spellings the mirror itself emits: ``<name>-<v>.tgz``
    with the scope kept (``@scope/name``), dashed (``@scope-name``), or fully
    flattened (``scope-name``).
    """
    if not tgz_file.endswith(".tgz"):
        return None
    variants = (
        pkg_name,
        pkg_name.replace("/", "-"),
        pkg_name.replace("/", "-").replace("@", ""),
    )
    for version in versions:
        for variant in variants:
            if tgz_file == f"{variant}-{version}.tgz":
                return version
    return None


@router.get("/api/cache/npm/{package_name:path}")
def npm_get_package(package_name: str, request: Request):
    """Serve npm tarball from CAS.

    Loopback only. Used by npm/yarn when configured to use localhost registry.
    A ``<name>/-/<file>.tgz`` path serves exactly the version encoded in the
    filename; a bare package name serves the semver-greatest known version.
    """
    _guard_data_plane(request)

    package_name = unquote(package_name.strip("/"))
    index = load_npm_index(vault_root())

    if "/-/" in package_name:
        pkg_name, _, tgz_file = package_name.rpartition("/-/")
        pkg_name = pkg_name.strip("/")
        versions = index.get(pkg_name)
        if not versions:
            raise HTTPException(404, "Package not found")
        version = _match_tgz_version(tgz_file, pkg_name, versions)
        if version is None:
            raise HTTPException(404, "Version not found")
    else:
        pkg_name = package_name
        versions = index.get(pkg_name)
        if not versions:
            raise HTTPException(404, "Package not found")
        version = max(versions, key=_semver_key)

    info = versions[version]
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
        filename=f"{pkg_name.replace('/', '-')}-{version}.tgz",
        headers={"X-Checksum-Sha1": info.get("shasum", "")},
    )
