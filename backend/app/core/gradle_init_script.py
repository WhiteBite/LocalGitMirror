"""
Генератор init-скрипта gradle для домашней машины.

Зачем генерировать, а не держать файл в репозитории: скрипт содержит путь к
хранилищу, адрес loopback-репозитория и API-ключ. Ни одно из этого не должно
попасть в корпоративный git. Поэтому файл живёт только в
``~/.gradle/init.d/`` на домашней машине — gradle подхватывает его на ЛЮБОМ
проекте автоматически, и в самих проектах про него нет ни строчки.

Как достигается fail-closed. Напрашивающийся ``exclusiveContent`` не подходит:
он привязывает защищённые группы к ОДНОМУ репозиторию, а нам нужны два —
``file://`` projection (быстрая, работает под ``--offline``) и loopback HTTP
(регистрирует промахи в очереди ``wanted``). Поэтому фильтруем с двух сторон:

* репозитории хранилища объявляют ``includeGroupByRegex`` — они отдают ТОЛЬКО
  защищённые группы и не мешают резолву публичных зависимостей;
* всем остальным репозиториям добавляется ``excludeGroupByRegex`` — защищённую
  группу они отдать не могут физически.

В сумме это ровно то, что нужно: ``ru.kryptonite:*`` приходит либо из
хранилища, либо никак. Провалиться в Maven Central он не может, а значит и
перехватить это имя, опубликовав туда одноимённый артефакт, нельзя.
"""

from __future__ import annotations

import re
from typing import Iterable, Sequence

#: Имена, по которым узнаём свои же репозитории, чтобы не навесить на них
#: exclude-фильтр вместе с чужими.
VAULT_REPO_NAMES = ("LgmVaultFiles", "LgmVaultHttp")

SCRIPT_FILE_NAME = "lgm-vault.gradle"


def protected_group_regex(groups: Sequence[str]) -> str:
    """Регулярка «группа или её потомок» для ``includeGroupByRegex``.

    ``ru.kryptonite`` → ``ru\\.kryptonite(\\..*)?``. Gradle сопоставляет
    регулярку с ПОЛНЫМ именем группы, поэтому ``ru.kryptoniteevil`` не подойдёт:
    после ``ru.kryptonite`` остаётся ``evil``, а необязательная часть требует
    либо точку, либо конец строки. Именно это отличие и защищает от чужой
    группы, специально названной похоже.
    """
    alternatives = [re.escape(g.strip()) + r"(\..*)?" for g in groups if g.strip()]
    if not alternatives:
        raise ValueError("список защищённых групп пуст")
    if len(alternatives) == 1:
        return alternatives[0]
    return "(" + "|".join(alternatives) + ")"


def _groovy_string(value: str) -> str:
    """Экранировать значение для одинарных кавычек Groovy."""
    return value.replace("\\", "\\\\").replace("'", "\\'")


def render_init_script(
    projection_dir: str,
    base_url: str,
    api_key: str = "",
    protected_groups: Iterable[str] = ("ru.kryptonite",),
) -> str:
    """Собрать текст init-скрипта.

    :param projection_dir: каталог maven2-projection на диске
    :param base_url: адрес сервера, например ``http://127.0.0.1:8080``
    :param api_key: значение заголовка ``X-Session-ID``; пусто — без авторизации
    :param protected_groups: корпоративные группы, обслуживаемые fail-closed
    """
    groups = [g.strip() for g in protected_groups if g.strip()]
    regex = protected_group_regex(groups)

    projection_uri = projection_dir.replace("\\", "/")
    m2_url = base_url.rstrip("/") + "/api/cache/m2/"

    # Заголовочная авторизация нужна только если ключ задан. Собираем блок
    # отдельно, чтобы в скрипте не появлялось пустых credentials — gradle на
    # них ругается.
    if api_key:
        auth_block = f"""
                credentials(HttpHeaderCredentials) {{
                    name = 'X-Session-ID'
                    value = '{_groovy_string(api_key)}'
                }}
                authentication {{
                    header(HttpHeaderAuthentication)
                }}"""
    else:
        auth_block = ""

    return f"""// LocalGitMirror — Corporate Artifact Vault. СГЕНЕРИРОВАНО, не править руками.
//
// Этот файл существует только на домашней машине и НЕ попадает ни в один
// репозиторий. Благодаря ему проекты ничего не знают про домашнюю
// инфраструктуру: в их build.gradle.kts нет ни адресов, ни путей.
//
// Защищённые группы: {', '.join(groups)}
// Хранилище:         {projection_dir}
// Data plane:        {m2_url}
//
// FAIL-CLOSED: защищённые группы отдаёт только хранилище. Всем остальным
// репозиториям они запрещены через excludeGroupByRegex. Промах даёт ошибку
// резолва, а НЕ поход в Maven Central — иначе защищённое имя можно было бы
// перехватить, опубликовав его в публичном репозитории (dependency confusion).

import org.gradle.authentication.http.HttpHeaderAuthentication

// Регулярка сопоставляется с полным именем группы, поэтому похоже названная
// чужая группа (например ru.kryptoniteevil) под неё не попадает.
def lgmProtectedRegex = '{_groovy_string(regex)}'
def lgmProjection = '{_groovy_string(projection_uri)}'
def lgmM2Url = '{_groovy_string(m2_url)}'
def lgmVaultNames = {list(VAULT_REPO_NAMES)!r}.toSet()

// Добавить оба репозитория хранилища. Порядок важен: сначала file://
// (быстро и работает под --offline), затем loopback HTTP — он нужен, чтобы
// промах попал в очередь `wanted` и следующая синхронизация с ноута его закрыла.
def lgmAddVault = {{ repos ->
    if (repos.findByName('LgmVaultFiles') == null) {{
        repos.maven {{ repo ->
            repo.name = 'LgmVaultFiles'
            repo.url = new File(lgmProjection).toURI()
            repo.content {{ it.includeGroupByRegex(lgmProtectedRegex) }}
        }}
    }}
    if (repos.findByName('LgmVaultHttp') == null) {{
        repos.maven {{ repo ->
            repo.name = 'LgmVaultHttp'
            repo.url = lgmM2Url
            // Data plane висит на loopback по обычному HTTP: TLS с
            // самоподписанным сертификатом JVM не примет без правки truststore,
            // а трафик не покидает машину.
            repo.allowInsecureProtocol = true
            repo.content {{ it.includeGroupByRegex(lgmProtectedRegex) }}{auth_block}
        }}
    }}
}}

// Запретить защищённые группы во всех прочих репозиториях. `all` покрывает и
// те, что будут добавлены позже, — иначе репозиторий, объявленный в проекте
// после нас, стал бы дыркой в fail-closed.
def lgmFenceOthers = {{ repos ->
    repos.all {{ repo ->
        if (!lgmVaultNames.contains(repo.name)) {{
            try {{
                repo.content {{ it.excludeGroupByRegex(lgmProtectedRegex) }}
            }} catch (Throwable ignored) {{
                // Не у всех типов репозиториев есть content-фильтр.
            }}
        }}
    }}
}}

def lgmWire = {{ repos ->
    lgmAddVault(repos)
    lgmFenceOthers(repos)
}}

settingsEvaluated {{ settings ->
    // pluginManagement: объявление ЛЮБОГО репозитория здесь подавляет неявный
    // gradlePluginPortal(), поэтому возвращаем его явно — иначе сломаются
    // проекты, которые полагаются на дефолт.
    try {{
        settings.pluginManagement.repositories {{ repos ->
            lgmAddVault(repos)
            if (repos.findByName('LgmPluginPortal') == null) {{
                repos.gradlePluginPortal {{ it.name = 'LgmPluginPortal' }}
            }}
            lgmFenceOthers(repos)
        }}
    }} catch (Throwable ignored) {{ }}

    // Централизованные репозитории проектов (если проект их использует).
    try {{
        lgmWire(settings.dependencyResolutionManagement.repositories)
    }} catch (Throwable ignored) {{ }}

    // buildscript самого settings.gradle.
    try {{
        lgmWire(settings.buildscript.repositories)
    }} catch (Throwable ignored) {{ }}
}}

// beforeProject срабатывает ДО вычисления build.gradle, поэтому объявленный
// там buildscript {{ classpath ... }} уже видит хранилище в списке
// репозиториев. Для onyx-platform это критично: корп-плагин подключается
// именно через buildscript classpath.
gradle.beforeProject {{ project ->
    try {{
        lgmWire(project.buildscript.repositories)
    }} catch (Throwable ignored) {{ }}
    try {{
        lgmWire(project.repositories)
    }} catch (Throwable ignored) {{ }}
}}
"""
