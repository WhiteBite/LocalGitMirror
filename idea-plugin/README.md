# DocCache IntelliJ Plugin

IntelliJ IDEA plugin **DocCache** — основная ежедневная поверхность LocalGitMirror: отправка и вытягивание git-веток, GitLab MR-цикл, корпоративные зависимости, файловый почтовый ящик и буфер обмена между машинами. Функциональная спецификация: `../docs/IDEA_PLUGIN_MVP.md`. Архитектура и роли машин: `docs/ARCHITECTURE.md` (в репозитории).

## Возможности

Mirror sync:
- Send **current branch** to Mirror (шифрованный git-bundle dump + `POST /api/documents/upload`)
- Send **selected branch** to Mirror
- Send **selected commits** to Mirror (via temporary branch + cherry-pick)
- Pull back changes from remote (`ff-only` или в новую ветку), inspect-before-send (`/api/documents/preview`, `/api/documents/has-commits`)

GitLab:
- Send **GitLab MR/PR code** to Mirror (fetch MR source branches, дедупликация по зеркальным tips через `MrSendPlanner`, один бандл)
- MR picker of open MRs (fallback to manual IID input)
- Перенос заметок ревью и ответов через почтовый ящик (`mr-notes/`, `mr-replies/`)

Deps (gradle/npm):
- Request недостающих артефактов с домашней машины, respond с рабочей, apply полученных ZIP в локальные кэши, publish защищённых артефактов в vault

Actions доступны в: Tools menu, VCS menu, Project tree context menu.

UI:
- Tool Window: **DocCache** (правая панель, `anchor="right"`), вкладки Review / Branches / Exchange / Deps
- Settings → Tools → DocCache
- Язык интерфейса плагина — отдельная настройка: `auto | en | ru`

## Build / Run (local)

Prereqs:
- IntelliJ IDEA (Community/Ultimate)
- JDK 17+

From this directory:

```bash
./gradlew runIde         # запуск IDE с плагином
./gradlew buildPlugin    # installable ZIP
```

## Configuration

Open:
- Settings → Tools → DocCache (или поиск "DocCache")

Required for Mirror sync:
- Mirror Base URL (e.g. `https://192.168.0.104`)
- Mirror Repo (optional; auto-created by project name if empty)
- Mirror API Key (header `X-Session-ID`; сервер fail-closed без `API_KEY` в `.env`)
- Server public key pin (TOFU при первом handshake) — нужен для v3 relay; без него sync/deps/buffer работают по Sync Password
- Sync Password — только legacy-режим (без закреплённого ключа сервера); пустой пароль допустим, когда v3-ключ закреплён

Required for GitLab MR integration:
- GitLab Base URL
- GitLab Token (PAT)
- GitLab Project (id or `group/name`)

Optional Git transport settings:
- Git remote name (default `origin`)
- Machine role: `auto | home | work` (auto определяет HOME по loopback/local-IP baseUrl; при неудаче детекции роль остаётся UNDETERMINED и показывается в панели, а не молча WORK)

## Транспорт

- Только шифрованный HTTP через `/api/documents/*`: upload (`upload`), проверка known-коммитов (`has-commits`, `negotiate`), pointer-only apply (`link` = apply-known), экспорт (`export`), предпросмотр (`preview`, `preview-details`), удаление веток (`delete-ref`, `prune-branches`).
- Сырой git smart-HTTP на зеркало не используется; `git remote add` на mirror не нужен.
- Ветки идут как непрозрачные шифрованные бандлы; релейные потоки (deps, почтовый ящик, буфер, MR-заметки) запечатываются v3 ECIES к закреплённому ключу сервера.

### Smart sync (avoid full dumps)

Plugin избегает полных дампов:
- per-project state под `.localgitmirror/state/` (per-branch и last-sent heads)
- negotiated pointer-only apply, когда Mirror уже имеет целевой коммит (`/api/documents/has-commits` + `/api/documents/link`)
- инкрементальные бандлы от ancestor base, когда он известен

## Notes / Limitations

- Runtime send/apply paths are Kotlin-native (no .bat/.py dependency).
- GitLab API response parsing uses `kotlinx-serialization-json` (no regex parsing for MR metadata).
- Secrets are stored in IntelliJ Password Safe (Mirror API key, Sync password, GitLab token).
- Operation history (last events) is persisted in plugin state.
- Грязный домашний checkout блокирует применение бандла: сервер отвечает типизированным `workspace_dirty`, плагин показывает явное сообщение — закоммитьте или заstashьте изменения дома и повторите.

## Build artifact

Installable plugin ZIP is produced at:

`idea-plugin/build/distributions/localgitmirror-idea-plugin-<version>.zip`

Версия плагина = `0.<число коммитов репозитория>.0`. Домашний сервер раздаёт самый свежий ZIP из этой директории по `/api/plugin/info?enc=1` и `/api/plugin/latest?enc=1` (запросы без `?enc=1` сервер отклоняет — незашифрованная раздача выключена).
