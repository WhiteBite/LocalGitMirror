# LocalGitMirror — единый план: зависимости, ветки, чистка настроек

**Топология:** дом = этот ПК (на нём бэкенд + Vue-фронт), работа = ноут (там
корпоративные либы gradle/npm). Стелс важен на ноуте.

**Инвариант скрытности:** транспорт остаётся «документами»
(`/api/documents/*`, `/api/deps/*`), бандл/блобы шифруются `BundleCrypto`,
формат не меняем. Ничего не писать в дерево проекта на ноуте.

Статусы: ✅ сделано · 🔜 в этом плане · ⏳ потом

---

## Часть A — Передача зависимостей (gradle + npm)

### A0. Редизайн механизма ✅ СДЕЛАНО
- Инверсия: дом сам определяет, что не резолвится локально, и точечно
  запрашивает (вместо «дом шлёт что есть → работа диффит по origin-эвристике»).
- Абстракция `DepsEcosystem` (gradle + npm), общий транспорт/крипто/ZIP/UI.
- **gradle**: `resolveMissing` через init-script + `--refresh-dependencies`
  ловит unresolved (включая плагины foojay/spotless); `collect` из
  `modules-2`; `unpackRouted` в кеш.
- **npm**: `resolveMissing` парсит `package-lock.json` (v1/v2/v3), берёт пакеты
  с не-публичным `resolved`; `collect` по `integrity` из `_cacache`;
  `postInstall` = `npm cache add` на доме.
- Удалён мёртвый код: `RepoDetector`, `matchesInternalRepo`/sidecar,
  v1 `DepsManifest`/`DepsDiff`, настройка `internalRepos`.
- 31 тест зелёный (`DepsLogicTest`, `EcosystemDepsTest`, `GradleResolverTest`).

### A1. Стелс-диагностика ✅ СДЕЛАНО
- `DepsDiagnostics` пишет в IDE-лог и опц. файл под лог-папкой IDE — **никогда**
  в проект. По умолчанию запись на диск выключена.
- `sweepLegacyDepsFiles` удаляет старые `.lgm-*.txt` из проектов.

### A2. Остаток по deps
- [x] **Стабильный ключ репо** — `RepoResolver.repoNameFromRemoteUrl` выводит slug из git remote URL (https/ssh/scp), одинаковый на обеих машинах. Приоритет: settings > git-remote > project.name > dir > default. Все точки (`DepsActions`, `PullFromMirrorAction`, `ManageMirrorBranchesAction`, `PullCheckStartupActivity`) routed через `RepoResolver`. 17 тестов в `RepoResolverTest`.
- [x] Тесты на реальный `GradleResolver.resolveMissing` — `GradleResolveMissingIntegrationTest` (2 теста) реально форкает gradle: проект с пустым local-репо → unresolved координата ловится; чистый проект → ничего лишнего. Скипается, если gradle нет в PATH.
- [x] Тесты на `NpmEcosystem` — `npmCacheDirFrom` (pure, 5 кейсов: npm_config_cache/LocalAppData/fallback/unix/blank) + `postInstall` no-op без тарболов. (+6 в `EcosystemDepsTest` → 19).
- [x] Объединить 2 чекбокса диагностики — оставил 2 строки, но переименованы в «Deps diagnostics (debug)» + verbose; функционально один режим.

### C5. Чистка мёртвых i18n-ключей ✅ СДЕЛАНО
- [x] Удалены из обоих бандлов (EN+RU): `settings.deps.internalRepos*`, `settings.ui.language`, `settings.git.remote`, `settings.git.pullMode`, `settings.ui.simpleMode`, `settings.behavior.title`, `settings.ui.title`, дублирующий блок `deps.*` (scanStart/diffComputed/responded/applied/empty/title.*).
- [x] Проверка честная: скрипт сверил все `message("…")`-ключи из кода против обоих бандлов — 0 отсутствующих (иначе ключ рендерился бы как сырая строка).

### A3. Расширения экосистем ✅ СДЕЛАНО (npm-семейство)
- [x] `NpmEcosystem.resolveMissing` — диспетчер по lockfile: `package-lock.json` → `pnpm-lock.yaml` → `yarn.lock`. Парсеры `parsePnpmLockForCorporate` (мини-YAML) и `parseYarnLockForCorporate` (classic v1). 14 тестов в `NpmLockfilesTest`.
- [x] **Проба публичного реестра (точное определение «корпоративного»)** — реальный кейс: при `.npmrc registry=<nexus>` ВСЕ пакеты резолвятся через nexus, парсер возвращает всё дерево. `filterCorporate` пробивает `registry.npmjs.org/<pkg>/<ver>`: 200 = публичный (не слать), 404 = корпоративный (слать), ошибка = слать консервативно. Кеш проб per-process. Симметрия с gradle: дом сам точно знает чего нет публично. Проверено на доме: lodash→200, @krypto-ui/core→404. 14 тестов в `NpmRegistryProbeTest`.
- [x] **Scope-override** (`npmCorporateScopes` в настройках, напр. `@krypto-ui,krypto-`) — опциональный force-include для случая «дом без публичного npm» или явного форса. По умолчанию пусто → работает проба.
- [ ] gradle composite builds / `buildSrc` (не делалось — отдельно).

---

## Часть D — Надёжность транспорта

- [x] **D1: Авто-TTL и чистка брошенных blob'ов** в postbox. `_cleanup_stale(dir, max_age=7d)` вызывается лениво в `request`/`pending`/`responses`. 9 тестов (`test_deps_ttl.py`): граница ровно на TTL не удаляет, TTL+1 удаляет, не-`.bin` не трогаются, точный микс old/fresh.
- [x] **D2: Хеширование имени каталога репо** в `storage/deps` (sha256[:16]) для стелса на доме. Миграция plain→hashed при первом доступе. 9 тестов (`test_deps_repo_hash.py`): детерминизм, отсутствие коллизий, миграция и доступность данных после неё.
- [x] **D3: Кеш бандлов на сервере** — content-addressed по `(repo, head, branch, since, haves)`. Кешируется plaintext-бандл (шифрование остаётся свежим per-request), TTL 1ч + LRU cap 20, atomic write, graceful fallback на битый кеш. **Контракт `/documents/export` не изменён.** 4 теста (`test_export_cache.py`): hit отдаёт корректное; инвалидация при новом коммите (критичный); ограничение размера; fallback при порче.
- [ ] **D4: Стриминг больших архивов** — СОЗНАТЕЛЬНО пропущен: изменение base64-в-JSON ломает стелс-контракт `/documents/*`. RAM-оптимизация без смены формата возможна, но риск > выгоды. Оставлено как есть.
- [x] **D5: Авто-проверка pending deps на старте** — `PullCheckStartupActivity` после pull-чека проверяет `depsPending`/`depsResponses`, показывает balloon с кнопкой «Выдать»/«Применить» (на том же cooldown). Чистая функция `shouldNotifyPending` + кеши видимости. Тесты в `DepsActionsVisibilityTest`.

### C4. Сокращение действий (plugin.xml + меню) ✅ СДЕЛАНО
- [x] Перегруппировка `LocalGitMirror.FullGroup` (Send/Pull на виду, остальное в подменю) — сделано ранее.
- [x] Smart-видимость deps-действий: `RequestDepsAction` включён только при наличии gradle/npm проекта + конфигурации; `RespondDepsAction`/`ApplyDepsAction` — по кешу pending/responses (`computeRespondEnabled`/`computeApplyEnabled`, чистые, без сети в `update()`). 17 тестов в `DepsActionsVisibilityTest`.

---

## Часть B — Удаление веток на Mirror 🔜

**Проблема (подтверждена):** на Mirror копятся старые ветки. Причина:
`upload-and-apply` только force-push'ит (добавляет/обновляет рефы), а
`/documents/list` отдаёт всё из bare-репо. Удаления рефа нет нигде — ни в
бэкенде, ни в плагине. Локально удалённая ветка остаётся на Mirror навсегда.

### B1. Бэкенд: эндпоинт удаления ветки ✅ СДЕЛАНО
- [x] `POST /api/documents/delete-ref` — валидация, удаление из bare + workspace, защита от удаления HEAD и последней ветки.

### B2. Бэкенд: явный список с метаданными ✅ СДЕЛАНО
- [x] `/documents/list` теперь возвращает `{"sha","updated","is_head"}` на каждую ветку вместо plain-sha. Backward-compatible (старые клиенты читают `sha`).

### B3. Плагин: API + UI ✅ СДЕЛАНО
- [x] `MirrorApi.RefInfo`, `MirrorApi.RefsResult` обновлены под новый формат; парсинг старого формата оставлен для совместимости.
- [x] `MirrorApi.deleteRef(...)` добавлен.
- [x] `ManageMirrorBranchesAction` — показывает ветки Mirror с датами и `★ HEAD`, позволяет выбрать и удалить; двойное подтверждение.
  ```
  body: { repo: str, branch: str }
  ```
  - валидировать `repo` и имя ветки (без `..`, без пробелов, refname-safe);
  - удалять реф в **bare** (`git update-ref -d refs/heads/<branch>`) и в
    workspace, если там есть;
  - запретить удаление последней ветки и текущего HEAD bare-репо (иначе
    репозиторий осиротеет) → вернуть 409 с понятным сообщением;
  - залогировать через `system_logger`.
- [ ] Опц. `prune`: `git worktree prune` / `gc` не трогаем (дорого, потом).

### B2. Бэкенд: явный список с метаданными
- [ ] Расширить `/documents/list`: к каждому рефу добавить `updated` (committer
      date последнего коммита) и `is_head`. Нужно, чтобы плагин показывал
      «старые» ветки и давал безопасно их чистить.

### B3. Плагин: API + UI
- [ ] `MirrorApi.deleteRef(baseUrl, apiKey, repo, branch, insecureTls)`.
- [ ] `MirrorApi.listRefs(...)` (если ещё нет обёртки над `/documents/list`).
- [ ] Действие **«Manage Mirror branches…»** в меню «⋯»:
  - диалог со списком веток Mirror (имя · дата · `★ HEAD`);
  - мультивыбор + кнопка «Delete»;
  - подтверждение (это операция с blast radius → явный confirm, как того
    требуют гайдлайны);
  - после удаления — рефреш списка и истории.
- [ ] Не показывать удаление для HEAD/последней ветки (disabled + tooltip).

### B4. Тесты ✅ СДЕЛАНО
- [x] Бэкенд `test_delete_ref.py` (8 тестов): успех; 409 для HEAD; 409 для последней; 404 несуществующая ветка; 400 невалидное имя (`../escape`, пробел, `~`, `..`); 404 несуществующий репо; «удалил → list больше не показывает».
- [x] Тест на enriched `/documents/list` (`sha`/`updated`/`is_head`).
- **Реальный баг, пойманный тестом:** `is_head` считался сравнением SHA с workspace-HEAD, но рефы мерджатся «bare поверх workspace». Ветка, запушенная прямо в bare (workspace не тронут), давала неверный `is_head`. Фикс: `is_head` теперь по ИМЕНИ ветки против `symbolic-ref HEAD` из bare (тот же источник, что и guard в delete-ref).

---

## Часть C — Чистка настроек и действий

Цель: оставить на виду только то, что реально меняют. Остальное —
автоопределение или «Дополнительно».

### C0. Аудит настроек (текущее использование)

| Поле | Где читается | Вердикт |
|---|---|---|
| `baseUrl`, `syncPassword` | везде | **оставить** (суть) |
| Discover / Test | диалог настроек | **оставить** |
| `repo` | sync/deps | **оставить, авто-дефолт** из git remote/имени |
| `mirrorInsecureTls` | каждый HTTP | 🔜 **убрать из UI**, авто при Discover, дефолт true |
| `uiLanguage` | `LocalGitMirrorBundle` | 🔜 **убрать**, следовать языку IDE |
| `mirrorApiKey` | каждый HTTP (Bearer) | оставить в «Дополнительно» |
| `gitRemoteName` | pullBack, pushCurrent | 🔜 **убрать**, авто `GitLocal.defaultRemote()` |
| `pullBackDefaultMode` | pullBack (и так диалог) | 🔜 **убрать из UI**, дефолт new-branch |
| `offlineGenerateOnly` | sync v2 | 🔜 **убрать из UI**, флаг оставить в State |
| `autoCheckPullOnStartup` | startup | оставить только в меню «⋯» (не дублировать) |
| `depsDiagnostics*` (2 шт.) | deps | 🔜 свернуть в один чекбокс |

### C1. Авто-репо и авто-remote (снимает 2 поля + чинит deps/ветки)
- [x] `GitLocal.defaultRemote()` добавлен.
- [x] `PanelDiagnostics.pullBack()` — уже использует `defaultRemote`.
- [x] `PanelDiagnostics.pushCurrent()` — заменено на `defaultRemote`.
- [x] `PullBackFromRemoteAction` — заменено на `defaultRemote`; `pullBackDefaultMode` убран из пикера (дефолт new-branch).
- [x] Настройки «Имя remote» и «Режим pull-back» удалены из UI (поля в State сохранены для совместимости).

### C2. Язык — по IDE
- [x] `LocalGitMirrorBundle.preferredLocale()` удалена — используется `Locale.getDefault()`.
- [x] Поле `uiLanguage` убрано из панели настроек (поле в State сохранено для совместимости XML).

### C3. Свернуть UI настроек до 4 видимых полей
- [x] URL + Discover + Test (1 строка), Пароль, Репозиторий — 3 строки видимые.
- [x] В «Дополнительно»: API key, insecureTls, offlineMode, 2 чекбокса диагностики.
- [x] Убраны: uiLanguage row, gitRemoteName row, pullBackDefaultMode row.
```
Сервер Mirror
  Mirror URL           [https://192.168.1.50]  [Найти] [Проверить]
  Пароль синхронизации [••••••]
  Репозиторий          [пусто = авто]

Дополнительно (свёрнуто)
  Mirror API key       [••••••]
  ☐ Self-signed TLS
  ☐ Офлайн-режим (только генерация)
  ☐ Deps diagnostics (debug)
```
- [ ] Перенести `insecureTls`, `offline`, `apiKey`, deps-diag в «Дополнительно».
- [ ] Объединить два deps-чекбокса в один (verbose включается тем же флагом или
      отдельной строкой только внутри debug).

### C4. Сокращение действий (plugin.xml + меню)
Сейчас 13 действий. План:
- На виду в тулвиндоу: **Send · Pull · ⋯**.
- В «⋯»: Send branch / Send commits / Send as / Pull back / Apply local /
  **Manage branches** (B3) / Get corporate deps / Provide deps / Apply deps.
- Диагностику (Preflight, Dry-run send/pull) — в подменю «⋯ → Diagnostics».
- [ ] Перегруппировать `LocalGitMirror.FullGroup` в plugin.xml.
- [ ] Свести 3 deps-действия к сценарию «1 кнопка на роль» (дом видит «Get»,
      ноут видит «Provide») — определять по наличию pending-запроса/проекта;
      продвинутые оставить в подменю.

### C5. Чистка мёртвых i18n-ключей
- [ ] Удалить `settings.deps.internalRepos*`, дублирующие `deps.notify.*`,
      `settings.ui.language`, `settings.git.remote`, `settings.git.pullMode`
      после удаления соответствующих полей.

---

## Часть D — Надёжность транспорта ⏳ (из прошлых обсуждений)

- [ ] Авто-TTL и чистка брошенных blob'ов в postbox (`storage/deps/*`).
- [ ] Хеширование имени каталога репо в `storage/deps` (стелс на доме).
- [ ] Кеш бандлов на сервере (3 клиента за одной веткой не пересоздают).
- [ ] Стриминг больших архивов вместо base64-в-JSON (RAM на ~500 МБ).
- [ ] Авто-проверка pending deps на старте проекта (как `autoCheckPullOnStartup`).

---

## Порядок выполнения

```
C1  Авто-репо/remote        ← чинит и deps, и ветки; снимает 2 поля
C2  Язык по IDE             ← быстро, −1 поле
B1  Backend delete-ref      ← закрывает главный пробел (ветки копятся)
B2  list с метаданными
B3  Плагин: Manage branches UI
C3  Свернуть настройки
C4  Сокращение действий/меню
A2  Стабильный ключ (часть в C1) + тесты deps
B4  Тесты delete-ref
C5  Чистка i18n
D   Надёжность транспорта (по мере необходимости)
```

После каждого пункта: `gradle test` (плагин) и `pytest` (бэкенд) где применимо.

---

## Принципы (чтобы не сломать стелс/совместимость)
- Никаких файлов в дерево проекта на ноуте (диагностика → лог-папка IDE).
- Удаление веток = операция с blast radius → всегда явное подтверждение,
  запрет на HEAD/последнюю ветку.
- Неизвестные ключи конфига игнорировать тихо (совместимость «Вставить конфиг»).
- Формат `/api/documents/*` и `/api/deps/*` не менять.
