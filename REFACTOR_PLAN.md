# LocalGitMirror — Полный план рефакторинга

**Инвариант скрытности:** данные по-прежнему едут как JSON-«документ»
(`/api/documents/export`, поля `status/head/repo/filename/data`, бандл в base64
внутри `data`). Формат запроса/ответа не меняется. `StreamingResponse` не вводим.

---

## Этап 0 — Backend: фикс «канал был закрыт» ✅ СДЕЛАНО

Лечение причины, а не симптома.

| Файл | Было | Стало |
|---|---|---|
| `run.py` | `timeout_keep_alive: 0` | `30` |
| `run.py` | `timeout_graceful_shutdown: 0` | `30` |
| `api.py` | `async def sync_export_dump` | `def` (→ threadpool) |
| `api.py` | `_git()` без таймаута | `timeout=600`, обработка `TimeoutExpired` (rc=124) |

Предыдущий «фикс» (`f278b94`) только поднял таймауты на клиенте — лечил симптом.

---

## Этап 1 — Удаление GitLab

Убираем GitLab полностью: токен, MR/PR, бейдж, настройки, i18n.

### 1.1 Удалить файлы
- [ ] `idea-plugin/.../gitlab/GitLabApi.kt`
- [ ] `idea-plugin/.../actions/SyncGitLabMrToMirrorAction.kt`

### 1.2 `PanelSyncActions.kt`
- [ ] Удалить функции `syncMr()` и `pickMrIid()`
- [ ] Удалить `import localgitmirror.idea.gitlab.GitLabApi`

### 1.3 `PanelDiagnostics.kt`
- [ ] Удалить функцию `testGitLab()`
- [ ] Удалить `import localgitmirror.idea.gitlab.GitLabApi`

### 1.4 `LocalGitMirrorPanel.kt`
- [ ] Удалить `gitLabBadge: BadgeLabel`
- [ ] Удалить весь код, управляющий видимостью `gitLabBadge`
- [ ] Убрать кнопку «Send MR» из `rebuildActions()`
- [ ] Убрать пункт меню «Test GitLab» из `rebuildGearMenu()`
- [ ] Удалить блоки `if (workMode) {…}` в `rebuildActions()` — теперь всегда одна
      ветка без переключения по режиму (подготовка к Этапу 3)

### 1.5 `MirrorSettingsService.State`
- [ ] Удалить поля: `gitLabBaseUrl`, `gitLabProject`, `gitLabDefaultTargetBranch`,
      `gitLabInsecureTls`
- [ ] Упростить `resolveMode()`: без GitLab «auto» теряет смысл → `workMode`
      удалить из State полностью (режима work/home больше нет)
- [ ] Удалить `isWorkMode()`, `isHomeMode()`

### 1.6 `MirrorSettingsConfigurable.kt`
- [ ] Удалить `collapsibleGroup("GitLab…")` с полями
- [ ] Удалить поле `gitLabTokenLocal` и всё с ним связанное в `isModified/apply/reset`

### 1.7 `SecretsStore.kt`
- [ ] Удалить свойство `gitLabToken` (чтение/запись в PasswordSafe)

### 1.8 `ConfigSnapshot` + `ConfigLineCodec.kt`
- [ ] Из `ConfigSnapshot` удалить: `gitLabBaseUrl`, `gitLabProject`,
      `gitLabInsecureTls`, `gitLabToken`, `simpleUiMode`, `workMode`
- [ ] В `ConfigLineCodec.encode()` убрать соответствующие строки
- [ ] В `ConfigLineCodec.parseRawPayload()` убрать поля из маппинга —
      **оставить тихий ignore** (неизвестные ключи просто пропускаются,
      старые конфиги с gitLab-полями должны парситься без ошибки!)
- [ ] Новая версия кодека: `LGM_CONFIG_V3` — чтобы можно было различить
      «старый конфиг с gitLab» от «нового без»

### 1.9 `PanelConfig.kt`
- [ ] В `copyConfigLine()` убрать gitLab-поля из `ConfigSnapshot(...)`
- [ ] В `applySnapshot()` убрать присвоения gitLab-полей

### 1.10 `plugin.xml`
- [ ] Удалить `<action id="LocalGitMirror.SyncGitLabMR">` и `<reference>` на неё

### 1.11 i18n bundle (все языки)
Ключи к удалению:
- `notify.gitlab.*` (все)
- `toolwindow.badge.gitlabConnected`
- `toolwindow.badge.gitlabNotConfigured`
- `settings.gitlab.*` (все)
- `toolwindow.menu.sendMr`
- `toolwindow.menu.testGitLab`
- `badge.mode.work`, `badge.mode.home` (если mode убирается)

### 1.12 Тесты `ConfigLineCodecTest.kt`
- [ ] Убрать gitLab-поля из фикстур
- [ ] Добавить тест: старый V2-конфиг с gitLab-полями парсится без падения
- [ ] Обновить ожидаемые строки кодека (V3)
- [ ] Удалить `simpleUiMode` из фикстур

---

## Этап 2 — Удаление мёртвого кода настроек

### 2.1 `simpleUiMode` — мёртвая настройка, нигде не читается в UI
- [ ] Удалить из `MirrorSettingsService.State`
- [ ] Удалить из `MirrorSettingsConfigurable`
- [ ] Убрать из `ConfigSnapshot` / `ConfigLineCodec` (уже в Этапе 1.8)
- [ ] Убрать из `applySnapshot()` / `copyConfigLine()`

### 2.2 `workMode` — теряет смысл без GitLab (уже в Этапе 1.5)
Вся логика `isWorkMode()` управляла видимостью GitLab-кнопок. После удаления
GitLab режима больше нет — панель одна для всех.

### 2.3 `gitLabDefaultTargetBranch`
- [ ] Удалить из State (GitLab удалён, поле не нужно)

---

## Этап 3 — Редизайн панели

Новая ментальная модель: **выбор ветки → одно из двух действий → прогресс**.
Никаких режимов work/home, никаких чипов, никаких 7 кнопок.

### 3.1 Новый макет `LocalGitMirrorPanel`

```
┌─ LocalGitMirror ──────────────────────────── ⚙ ─┐
│  ● Mirror подключён    ✓ 12:37:08               │
│  confluence_mcp · рабочее дерево чистое           │
│                                                   │
│  Ветка: [confluence_mcp              ▼]           │
│                                                   │
│  [ ⬆ Отправить ]   [ ⬇ Подтянуть ]   [ ⋯ ]      │
│                                                   │
│  ████████░░░░  Скачивание объектов… 4.2 / 8 МБ   │
│                                                   │
│  ▸ История  🗑                                     │
└───────────────────────────────────────────────────┘
```

### 3.2 Компоненты

**Строка статуса** (две строки, мелко):
- Строка 1: `● Mirror подключён · ✓ Синхр. 12:37` (бейджи через пробел, без рамок)
- Строка 2: `confluence_mcp · рабочее дерево чистое` (человеческий текст,
  не `Чисто: true`)

**Селектор ветки** (`JComboBox`):
- [ ] Один, наверху, перед кнопками действий
- [ ] Дефолт — текущая ветка из `GitLocal.currentBranch()`
- [ ] Список формируется из локальных веток (`GitLocal.listBranches()`)
- [ ] При «Подтянуть» к списку примешиваются ветки с Mirror (из `getRefs()`,
      дёргается в фоне при открытии панели, кешируется)
- [ ] Убрать чипы `+master/+develop/Ещё ветки` — они путали (добавляют ветку
      или переключают?)

**Кнопка «⬆ Отправить»** (primary):
- Отправляет выбранную ветку. Если выбранная = текущая — поведение как
  `syncCurrentBranch()`. Если другая — `syncBranch()` с checkout.
- Заменяет: «Стянуть с Mirror» + «Отправить на Mirror» (дублировали друг друга)

**Кнопка «⬇ Подтянуть»**:
- Тянет выбранную ветку с Mirror. По умолчанию — текущая, если она есть
  на Mirror. Подробности в Этапе 4.

**Меню «⋯» (редкие операции)**:
- Отправить под другим именем (Push as)
- Отправить выбранные коммиты (Send commits)
- Подтянуть назад из remote (Pull back)
- Применить локальный пакет (Apply local dump)
- Офлайн-режим (toggle, если `offlineGenerateOnly`)
- --- разделитель ---
- Предпроверка
- Dry-run отправки
- Dry-run получения
- Проверить Mirror
- --- разделитель ---
- Копировать конфиг
- Вставить конфиг
- --- разделитель ---
- ☑ Авто-проверка при открытии проекта
- Настройки

**Прогресс-бар** (подробнее в Этапе 5):
- Видим только во время операции
- Текст стадии + проценты на pull, текст стадии + крутилка на push

**История**: toggle «▸ История» + кнопка очистки 🗑, без изменений по функционалу

### 3.3 Что убрать из панели
- [ ] Чипы `branchChipPanel` / `selectedAdditionalBranches` / `refreshBranchChips()`
- [ ] Метод `createBranchChip()`, `styleChip()`, `updateChipStyles()`
- [ ] Всю логику `rebuildActions()` с ветвлением по `isWorkMode()`
- [ ] Поле `gitLabBadge` (удалено в Этапе 1)
- [ ] Метод `isWorkMode()` (удалён в Этапе 1)

---

## Этап 4 — Pull с выбором ветки

Сейчас `PullFromMirrorAction` тянет все refs скопом. Нужно выбрать одну.

### 4.1 Логика `PullFromMirrorAction`

Новый поток:
1. `getRefs()` — GET запрос, получаем список веток на Mirror
2. Если список пустой — показать «Mirror пустой»
3. Предложить выбор ветки (через параметр, переданный из панели)
4. Скачать бандл только для выбранной ветки:
   - если `since`-хеш известен (уже есть локально) — слать `since=<hash>`,
     иначе `since=null`
5. Применить только нужный ref, не трогать остальные

### 4.2 Интеграция с панелью
- [ ] Кнопка «Подтянуть» берёт ветку из `JComboBox` и передаёт в action
- [ ] Список в `JComboBox` обогащается ветками с Mirror (загружается в фоне
      при открытии панели через `MirrorApi.getRefs()`, кешируется на 30 с)
- [ ] Если выбранной ветки нет на Mirror — предупреждение до старта операции

### 4.3 Изменения в `MirrorApi`
- [ ] Добавить опциональный параметр `branch: String?` в `exportDump()`
      (пока не используется сервером, но готовим контракт)
- [ ] Добавить метод `getCachedRefs()` — кешированный `getRefs()` для
      использования из панели без блокировки EDT

---

## Этап 5 — Прогресс с процентами

Меняем только клиента. Формат ответа сервера не трогаем — скрытность цела.

### 5.1 `HttpClient.kt`
- [ ] Добавить `readBodyWithProgress()`:
  ```kotlin
  fun readBodyWithProgress(
    conn: HttpURLConnection,
    onProgress: ((read: Long, total: Long) -> Unit)? = null
  ): String
  ```
  Читает тело по буферу (8 KB), считает байты против `conn.contentLengthLong`,
  вызывает callback каждые ~100 мс или каждые 5% изменения.
- [ ] Старый `readBody()` оставить как обёртку без callback — не ломаем остальных

### 5.2 `MirrorApi.exportDump()`
- [ ] Принять `progressCallback: ((read: Long, total: Long) -> Unit)?`
- [ ] Использовать `readBodyWithProgress()` для чтения JSON-ответа

### 5.3 `PullFromMirrorAction` / интеграция с панелью
- [ ] Передавать callback: `progressCallback = { read, total -> … }`
- [ ] В callback: обновлять `indicator.fraction = read.toDouble() / total`
      и `indicator.text2 = "Скачивание… ${read/1048576} / ${total/1048576} МБ"`
- [ ] Если `total <= 0` (сервер не прислал `Content-Length`) — крутилка без %

### 5.4 Текстовые стадии (на отправке и скачивании)
Уже частично есть в `PullFromMirrorAction` через `indicator.text`. Довести до:

| Стадия | Текст |
|---|---|
| Pull: старт | Получаем список веток с Mirror… |
| Pull: проверка объектов | Проверяем локальные объекты… |
| Pull: скачивание | Скачивание… X / Y МБ |
| Pull: распаковка | Распаковываем объекты… |
| Pull: обновление веток | Обновляем локальные ветки… |
| Push: упаковка | Упаковываем бандл… |
| Push: шифрование | Шифруем пакет… |
| Push: отправка | Отправляем на Mirror… |

---

## Этап 6 — Чистка настроек

### 6.1 Что остаётся на главном экране

```
Сервер Mirror
  Mirror URL            [https://192.168.1.50]  [Найти]  [Проверить]
  Пароль синхронизации  [•••••••]
  Репозиторий           [пусто = по имени проекта]
  ☐ Разрешить self-signed TLS

Язык интерфейса         [auto ▼]
```

5 полей. Всё остальное — в свёрнутую группу «Дополнительно».

### 6.2 «Дополнительно» (collapsibleGroup, свёрнута по умолчанию)
- `Mirror API key` — сервер проверяет его через `Bearer` в `get_api_key()`;
  поле нужно, но не для каждого запуска → прячем
- `Имя remote` (дефолт: origin)
- `Режим pull-back` (new-branch / ff-only)
- `Офлайн-режим` (только генерация, без отправки)

### 6.3 Изменения в коде `MirrorSettingsConfigurable`

- [ ] Добавить кнопку **«Проверить»** рядом с URL — вызывает `testMirror()`
  прямо из диалога настроек (через временный `MirrorApi.ping()`)
- [ ] `apply()`: нормализовать `baseUrl`:
  ```kotlin
  // Добавить схему если нет
  if (!s.baseUrl.startsWith("http")) s.baseUrl = "https://${s.baseUrl}"
  // Срезать хвостовой слеш
  s.baseUrl = s.baseUrl.trimEnd('/')
  ```
- [ ] Убрать дублирующийся `autoCheckPullOnStartup` из настроек — оставить
  только в меню-шестерёнке. Сохранить само поле в `State`.
- [ ] Удалить группу «Интерфейс» с `workMode` и `simpleUiMode` (оба удалены)
- [ ] Оставить только «Язык интерфейса» отдельной строкой

### 6.4 Меню-шестерёнка (было 9 пунктов → станет 7)

**Было:**
Предпроверка / Dry-run (отпр.) / Dry-run (получ.) / Применить локальный пакет /
Проверить Mirror / Копировать конфиг / Вставить конфиг / [Авто-проверка…] / Настройки

**Станет (вместе с Этапом 3, «⋯» и шестерёнка разделены):**

Шестерёнка (`⚙`) содержит только:
- Копировать конфиг
- Вставить конфиг
- ─
- ☑ Авто-проверка при открытии проекта
- Настройки

Меню «⋯» (при кнопках) содержит операции (см. Этап 3.2).

Диагностика (Preflight, Dry-run, Проверить Mirror) живёт в «⋯».

---

## Этап 7 — Обратная совместимость «Вставить конфиг»

Функция переноса настроек между машинами — основная польза «Копировать конфиг».
После удаления gitLab-полей конфиги, скопированные до рефакторинга, должны
читаться без ошибок.

- [ ] `parseRawPayload()`: неизвестные ключи тихо игнорируются (уже почти так,
      но надо проверить)
- [ ] Ввести `LGM_CONFIG_V3` как новый формат без gitLab-полей.
      Декодировать V1/V2 по-старому (gitLab-поля читать, но не применять
      к State — просто игнорировать).
- [ ] Тест: V2-конфиг с `gitLabToken=xxx` успешно применяется, токен не падает

---

## Порядок выполнения

```
Этап 0  ✅  Backend-фиксы (сделано)
Этап 1      Удаление GitLab          ← начинать здесь: чистит 30% кода
Этап 2      Мёртвый код настроек     ← попутно с Этапом 1
Этап 7      Обратная совместимость   ← сразу после изменения кодека
Этап 6      Чистка настроек          ← быстро, снимает раздражение
Этап 3      Редизайн панели          ← крупный, но логичный после чистки
Этап 4      Pull с выбором ветки     ← функционал, зависит от нового UI
Этап 5      Прогресс с процентами    ← финальный штрих
```

После каждого этапа: `gradle buildPlugin` + `gradle test`.

---

## Сводная таблица удаляемого

| Артефакт | Тип | Причина удаления |
|---|---|---|
| `GitLabApi.kt` | файл | GitLab удалён |
| `SyncGitLabMrToMirrorAction.kt` | файл | GitLab удалён |
| `gitLabBaseUrl/Project/InsecureTls/DefaultTargetBranch` | State поля | GitLab удалён |
| `gitLabToken` | SecretsStore | GitLab удалён |
| `gitLabBadge` | UI | GitLab удалён |
| `syncMr()`, `pickMrIid()` | PanelSyncActions | GitLab удалён |
| `testGitLab()` | PanelDiagnostics | GitLab удалён |
| `simpleUiMode` | State + UI | мёртвый код, ничего не делает |
| `workMode` / `resolveMode()` / `isWorkMode()` | State + UI | теряет смысл без GitLab |
| Чипы `+master/+develop` | UI | заменяется JComboBox |
| `rebuildActions()` с ветвлением | LocalGitMirrorPanel | теряет смысл без режимов |
| `autoCheckPullOnStartup` из настроек | дубль | оставить только в меню |

## Что НЕ трогаем

| Артефакт | Причина |
|---|---|
| `mirrorApiKey` (SecretsStore + State) | Сервер реально проверяет через `Bearer` |
| `gitRemoteName`, `pullBackDefaultMode` | Нужны для Pull back; прячем в «Дополнительно» |
| `offlineGenerateOnly` | Легитимный кейс (генерация без сети); прячем в «Дополнительно» |
| `ConfigLineCodec` encrypt/decrypt | Механизм переноса настроек, работает |
| `PullBackFromRemoteAction` | Нужна; уходит в меню «⋯» |
| `SendSelectedCommitsToMirrorAction` | Нужна; уходит в меню «⋯» |
| JSON-контракт `/api/documents/export` | Скрытность — не трогаем формат |
| `backend/` в целом | Все нужные фиксы в Этапе 0 сделаны |
