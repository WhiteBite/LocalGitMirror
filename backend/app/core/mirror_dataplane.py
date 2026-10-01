"""
Data plane зеркала: отдача артефактов локальным сборкам.

Гвард loopback и запись промахов по защищённым группам в очередь ``wanted``.
"""

from __future__ import annotations

import ipaddress
import os

from fastapi import HTTPException, Request

from app.core.artifact_publication import protected_groups
from app.core.artifact_store import ArtifactStore, is_protected_maven_path


def _is_loopback(host: str) -> bool:
    if not host:
        return False
    if host in ("localhost", "::1"):
        return True
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    # ::ffff:127.0.0.1 — dual-stack клиент приходит как IPv4-mapped IPv6.
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return ip.is_loopback


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
        # 404, а не 403: не подтверждать существование сервиса сканирующему.
        raise HTTPException(404, "Not Found")


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
