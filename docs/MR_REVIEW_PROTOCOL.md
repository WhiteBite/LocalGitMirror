# MR Review Protocol (v1)

Нормативная спецификация snapshot-bound MR review subsystem. Единственный источник
правды для обеих реализаций:

- Python: `lgm_core/mr_review_models.py` (stdlib only);
- Kotlin: `idea-plugin/src/main/kotlin/localgitmirror/idea/gitlab/MrReviewProtocol.kt`
  (без зависимости от Python, только kotlinx-serialization-json + JDK).

Общие cross-language fixtures: `tests/fixtures/mr_review_protocol/`; раннеры —
`tests/test_mr_review_protocol.py` и `MrReviewProtocolTest.kt`. Обе реализации
обязаны давать байт-идентичный canonical JSON и идентичные SHA-256 дайджесты для
одних и тех же входных данных. Любое изменение контракта повышает `schema_version`
и сопровождается новыми fixtures.

Термины: WORK — машина с доступом к GitLab (collector, publisher); HOME — машина
с review-store (reader, findings, import). Транспорт — существующий v3 encrypted
file postbox; протокол не зависит от транспорта.

## 1. Canonical JSON

Canonical encoding используется для всех дайджестов и для persisted-артефактов
(snapshots, batches, receipts). Правила:

1. Кодировка UTF-8, без BOM.
2. Ключи объектов: только ASCII `[A-Za-z0-9_]+` (нарушение — `invalid-schema`);
   сортировка по code point (для ASCII совпадает с байтовым порядком). Python
   `sorted(keys)` и Kotlin `keys.sorted()` дают одинаковый порядок.
3. Разделители компактные: `,` и `:` без пробелов.
4. Числа с плавающей точкой запрещены везде в protocol payloads
   (`invalid-schema`). Допустимы только целые числа в canonical JSON-форме
   `-?(0|[1-9][0-9]*)`; сериализатор нормализует `-0` в `0`.
5. Строки: минимальное экранирование по RFC 8259 — `"` → `\"`, `\` → `\\`,
   `\b`, `\f`, `\n`, `\r`, `\t` короткими формами; прочие C0-контрольные
   символы (U+0000–U+001F) как `\u00xx` с lowercase hex. Весь остальной
   non-ASCII (включая U+2028/U+2029) остаётся literal UTF-8, никогда `\uXXXX`.
6. `true` / `false` / `null` — литералы.
7. Массивы сохраняют порядок, определённый протоколом (сортировка запрещена).

Эквивалент в Python — `json.dumps(value, ensure_ascii=False, separators=(",", ":"),
sort_keys=True)`; тесты фиксируют побайтовое равенство `canonical_json()` этому
вызову на всех fixtures.

## 2. Дайджесты

Все дайджесты — SHA-256 от canonical JSON, lowercase hex (64 символа).

### 2.1 snapshot_id

`snapshot_id = sha256(canonical_json(snapshot_object_without_snapshot_id))`.

Из объекта snapshot удаляется только ключ `snapshot_id`; `code_id` и
`version_fingerprint` входят в хешируемый payload.

### 2.2 code_id

`code_id` считается ТОЛЬКО из набора уникальных labeled tips — никогда из дат,
текстов обсуждений или иного контента:

```
tips = sorted(unique([
  ["diff_base",  gitlab_diff_refs.base_sha],
  ["diff_head",  gitlab_diff_refs.head_sha],
  ["diff_start", gitlab_diff_refs.start_sha],
  ["source",     source_sha],
  ["target_tip", target_tip_sha],
]))                      # сортировка по (label, sha)
code_id = sha256(canonical_json(tips))
```

Одинаковый набор вершин кода ⇒ одинаковый `code_id` у разных snapshot одного MR
(например, при discussion-only refresh).

### 2.3 version_fingerprint

```
payload = {
  "diff_refs": {"base_sha": …, "head_sha": …, "start_sha": …},
  "gitlab_url": …, "iid": …,
  "source_project_id": …, "source_sha": …,
  "target_branch": …, "target_project_id": …, "target_tip_sha": …
}
version_fingerprint = sha256(canonical_json(payload))
```

GitLab identity (url, iid, оба project ID) + source SHA + target tip/branch/project
+ все diff refs. `source_branch` намеренно не входит: identity источника — это SHA.

### 2.4 Порядок проверки

Сначала полная структурная валидация, затем пересчёт всех трёх дайджестов и
сравнение с сохранёнными значениями. Любое расхождение — `hash-mismatch`, и
артефакт отвергается ДО любой записи/persistence (HOME store обязан вызывать
verify до установки snapshot как current).

## 3. Snapshot

Закрытый набор ключей: пропуск или неизвестный ключ — `invalid-schema`.
`schema_version` проверяется первым: не-integer — `invalid-schema`, integer вне
`{1}` — `unknown-schema-version`.

Базовые типы-ограничения: `sha40` = `^[0-9a-f]{40}$`; `hex64` = `^[0-9a-f]{64}$`;
`uuid` = lowercase canonical `8-4-4-4-12` hex; `iso` =
`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$`; `url` =
http(s), без trailing slash, без пробельных символов.

| Поле | Тип | Правила |
| --- | --- | --- |
| `schema_version` | int | `1` |
| `snapshot_id`, `code_id`, `version_fingerprint` | hex64 | см. раздел 2 |
| `repo` | string | mirror repo name, непустой |
| `gitlab_url` | url | нормализованный GitLab base URL |
| `source_project_id`, `target_project_id`, `iid` | int | ≥ 1 |
| `mr_url` | url | GitLab web_url MR |
| `title` | string | непустой |
| `description` | string\|null | пустая строка допустима |
| `state` | enum | `opened\|closed\|locked\|merged` |
| `draft` | bool | |
| `source_branch`, `target_branch` | string | непустые; target может быть любым (non-default поддерживается) |
| `source_sha`, `target_tip_sha` | sha40 | различаются по смыслу; target tip НЕ равен diff start |
| `source_fetch_ref` | string\|null | fork (source_project_id ≠ target_project_id): обязан быть `refs/merge-requests/<iid>/head`; иначе обязан быть null |
| `gitlab_diff_refs` | object\|null | `{base_sha, start_sha, head_sha}`, все sha40; null или пустая строка в любом из трёх — `diff-not-ready` (async GitLab) |
| `computed_merge_bases` | array of sha40 | все найденные merge bases; массив, никогда не сворачивается в первый элемент; может быть пустым |
| `comparison_base_sha` | sha40 | обязан равняться `gitlab_diff_refs.base_sha` при `comparison_kind=gitlab-diff` |
| `comparison_kind` | enum | только `gitlab-diff`: canonical diff — GitLab base..head |
| `observed_started_at`, `observed_finished_at`, `mr_updated_at`, `discussions_fetched_at` | iso | observation-поля; несколько API-запросов не атомарная фотография GitLab |
| `discussions_complete` | bool | все страницы discussions получены |
| `limitations` | array of string | явные ограничения снимка |
| `discussions` | array | см. 3.1 |

### 3.1 Discussion / note / position

```
discussion = {"discussion_id": string, "notes": [note]}
note = {
  "note_id": int ≥ 1, "body": string (пустой допустим),
  "author": string, "created_at": iso,
  "system": bool, "resolvable": bool, "resolved": bool,
  "position": position | null
}
position = {
  "old_path": string|null, "new_path": string|null,
  "old_line": int≥1|null, "new_line": int≥1|null,
  "side": "old"|"new",
  "base_sha": sha40, "start_sha": sha40, "head_sha": sha40
}
```

Position сохраняется ПОЛНОСТЬЮ: old/new paths, side-specific линии и refs.
Свёртывание в file+line запрещено (это GAP-4 legacy-рендеров
`mr_notes_render.py` / `MrNotesWriter.kt`). Правила стороны:

- `side` вне `{old,new}` — `invalid-side`;
- хотя бы один из paths ненулевой, иначе `invalid-schema`;
- `side=old` требует `old_path` (иначе `invalid-schema`) и `old_line`
  (отсутствие линии при ненулевом path — `invalid-line`);
- `side=new` симметрично требует `new_path` и `new_line`;
- линия, присутствующая, но < 1 или не integer — `invalid-line`.

Deletion: позиция удалённой строки — `side=old`, `old_line` задан,
`new_line=null`. Rename: `old_path ≠ new_path`, обе линии по стороне.

## 4. Finding

```
finding = {
  "schema_version": 1, "finding_id": uuid,
  "repo": string, "gitlab_url": url,
  "target_project_id": int≥1, "iid": int≥1,
  "revisions": [revision]        # непустой, номера contiguous from 1
}
revision = {
  "revision": int≥1, "created_at": iso,
  "snapshot_id": hex64, "code_id": hex64, "version_fingerprint": hex64,
  "anchor": anchor | null, "thread_id": string|null,
  "comment": string (непустой), "trigger": string,
  "evidence": [string], "limitations": [string],
  "verification": "hypothesis"|"confirmed"|"refuted",
  "applicability": "current"|"needs-recheck"|"withdrawn"|"fixed",
  "delivery": "local"|"queued"|"received"|"posted"|"failed"|"blocked"
}
anchor = {
  "old_path": string|null, "new_path": string|null,
  "side": "old"|"new",           # вне enum — invalid-side
  "line": int≥1 | null           # <1 или не integer — invalid-line; null = file-level
}
```

Правила:

- Три state-оси НЕЗАВИСИМЫ: verification (доказанность), applicability
  (актуальность к версии), delivery (транспортный статус). Resolved thread в
  GitLab не означает `fixed`; никакой автоконверсии статусов GitLab в
  `confirmed`/`fixed` нет и быть не должно.
- `applicability=fixed` требует непустой `evidence` (иначе `invalid-schema`).
- Правка текста создаёт новую revision с тем же `finding_id`; новый
  `finding_id` для той же находки запрещён.
- `anchor` и `thread_id` взаимоисключающи (оба заданы — `invalid-schema`).
- В anchor `line=null` допустим (file-level); в position (раздел 3.1) линия
  стороны обязательна.
- Side anchor: `side=old` требует `old_path`, `side=new` требует `new_path`;
  оба path null — `invalid-schema`.

### 4.1 Stale-проверка

`finding_stale_against(finding, snapshot)` (Python) /
`findingStaleAgainst` (Kotlin): latest revision сравнивается со snapshot по всем
трём полям `snapshot_id`, `code_id`, `version_fingerprint`; любое расхождение —
finding stale (публикация запрещена до recheck, автоперенос якоря запрещён).

## 5. V2 publication batch

Artifact path в postbox: `mr-replies/mr-!<iid>-<batch_id>.review.json`.
Batch immutable; разные v2 batches одного IID не supersede друг друга
(legacy latest-per-IID действует только для legacy `.md`).

```
batch = {
  "schema_version": 1, "batch_id": uuid,
  "repo": string, "gitlab_url": url,
  "target_project_id": int≥1, "iid": int≥1, "created_at": iso,
  "items": [item]                # непустой
}
item = {
  "finding_id": uuid, "revision": int≥1,
  "kind": "anchored"|"thread"|"general",
  "thread_id": string|null, "resolve": bool,
  "body": string (непустой), "anchor": anchor|null,
  "snapshot_id": hex64, "code_id": hex64, "version_fingerprint": hex64,
  "source_sha": sha40, "target_tip_sha": sha40,
  "diff_refs": {base_sha, start_sha, head_sha}   # sha40; пустой — diff-not-ready
}
```

Правила items:

- `kind=anchored` ⇒ `anchor` задан, `thread_id=null`, `resolve=false`;
- `kind=thread` ⇒ `thread_id` непустой, `anchor=null`, `resolve` допустим;
- `kind=general` ⇒ `anchor=null`, `thread_id=null`, `resolve=false`;
  general — только явно выбранный kind, fallback из anchored в v2 запрещён;
- `resolve=true` при `kind≠thread` — `invalid-schema`;
- дубликат `(finding_id, revision)` внутри batch — `invalid-schema`;
- все items обязаны нести одну тройку `snapshot_id/code_id/version_fingerprint`
  (нарушение — `invalid-schema`); эта тройка — identity batch.

В batch попадают только revisions с `verification=confirmed` и
`applicability=current` (проверяет отбор на стороне HOME, задача 10/11).

## 6. Marker

Грамматика v2 marker (не зависит от текста и позиции):

```
lgm-review:<batch_id>:<finding_id>:<revision>
```

- `batch_id`, `finding_id` — lowercase canonical UUID; `revision` — `[0-9]+`, ≥ 1;
- публикация встраивает marker как HTML-комментарий `<!-- lgm-review:… -->`;
  парсер ищет bare token в тексте (regex по всей строке);
- конструирование с невалидными компонентами — `invalid-marker`;
- повторная отправка той же finding revision обязана переиспользовать прежнюю
  публикационную identity (тот же marker), а не новый batch;
- legacy marker `<!-- lgm:xxxxxxxx -->` (алгоритм `_reply_marker`) не меняется и
  используется только для дедупликации уже отправленного legacy.

## 7. Receipt v2

Receipt immutable по (batch, attempt). Порядок публикации: persist journal →
upload detailed receipt → ack batch; upload failure ⇒ нет ack.

```
receipt = {
  "schema_version": 1,
  "batch_id": uuid, "finding_id": uuid, "revision": int≥1,
  "outcome": "posted"|"failed"|"blocked"|"recovered",
  "kind": "anchored"|"thread"|"general",
  "snapshot_id": hex64, "code_id": hex64, "version_fingerprint": hex64,
  "discussion_id": string|null, "note_id": int≥1|null,
  "url": string|null, "mr_url": url,
  "submitted_refs": {source_sha, target_tip_sha, base_sha, start_sha, head_sha} | null,
  "fallback": bool,
  "attempted_at": iso, "finished_at": iso|null,
  "error": string|null
}
```

Правила:

- `outcome=posted|recovered` ⇒ `discussion_id`, `note_id`, `url`,
  `submitted_refs` обязательны (non-null), `error=null`;
- `url` — note permalink, ровно `mr_url + "#note_" + note_id` (не discussion
  URL и не выдуманный путь); нарушение — `invalid-schema`;
- `outcome=failed|blocked` ⇒ `error` обязателен (non-null);
- `recovered` — IDs восстановлены по найденному marker без повторного POST;
- `submitted_refs` фиксирует реально отправленные refs (межзапросную race
  полностью исключить нельзя — receipt документирует факт);
- `fallback` документирует general-fallback (только legacy-путь; в v2 anchored
  публикациях всегда false);
- тройка `snapshot_id/code_id/version_fingerprint` связывает публикацию с
  проверенной версией кода (IS-6).

## 8. Error codes (закрытый реестр)

Коды — kebab-case строки; реестр закрыт: реализации не принимают и не создают
коды вне списка. `MrReviewError` / `MrReviewProtocolException` несут `code`.

| Code | Триггер |
| --- | --- |
| `snapshot-raced` | WORK collector: fingerprint/state/draft/updated_at/target tip изменились между detail A и detail B; одна попытка на вызов, current snapshot не публикуется (задачи 3/4) |
| `diff-not-ready` | GitLab diff_refs ещё не вычислены: `gitlab_diff_refs` null или любой из base/start/head пуст (валидация и runtime) |
| `review-object-missing` | точный Git-объект (target/start/base/source commit) недоступен после fetch; подстановка другой ветки запрещена (задачи 3/4) |
| `review-package-too-large` | code artifact > 128 MiB до шифрования (`CODE_ARTIFACT_MAX_BYTES`); проверяется до чтения байтов, без усечения/OOM (задачи 4/5) |
| `stale-snapshot` | WORK pre-publish gate: source head, target tip, target branch/project или diff refs дрейфовали; POST/resolve запрещены, автоперенос якоря запрещён (задача 12) |
| `possible-duplicate` | v2 proximity precheck нашёл близкий thread; это handle с side/ref/thread, не доказательство дубля и не terminal (задача 12) |
| `incomplete-checkout` | materialization: gitlink/LFS pointer или отключённый filter не дают полной среды (задача 9) |
| `invalid-schema` | структурное нарушение: пропущенный/неизвестный ключ, неверный тип, float в canonical payload, non-ASCII ключ, неверный enum (кроме side), форма url/uuid/sha/hex64/iso, fork-ref mismatch, comparison_base mismatch, не-contiguous revisions, нарушение правил batch/receipt |
| `unknown-schema-version` | `schema_version` — integer, но вне supported set |
| `invalid-side` | `side` вне `{old,new}` в position или anchor |
| `invalid-line` | линия присутствует, но не integer или < 1; либо отсутствует обязательная линия anchored-стороны в position |
| `hash-mismatch` | пересчитанный `snapshot_id`/`code_id`/`version_fingerprint` не совпал с сохранённым; отвергается до записи |
| `invalid-marker` | компоненты marker не соответствуют грамматике раздела 6 |
| `invalid-budget` | `budget_bytes` не integer или вне `4096..48000` |
| `revision-conflict` | store CAS: записываемая строка не находится в заявленном `expected_revision` (или уже существует при `expected_revision=0`); перезапись чужой правки запрещена (задачи 2/8/10) |
| `store-locked` | import-lock review-store занят дольше таймаута (задача 2); импорты сериализуются, это не молчаливое ожидание вечностью |

## 9. Budget

`budget_bytes` — integer в диапазоне **4096..48000** включительно
(нарушение — `invalid-budget`). Это верхняя граница UTF-8 байтов ПОЛНОСТЬЮ
сериализованного MCP `tools/call` result:

1. `text = json.dumps(payload, ensure_ascii=False, indent=2, default=str)` —
   pretty-printed JSON полезного результата;
2. envelope: success — `{"content": [{"type": "text", "text": text}]}`;
   error — `{"isError": true, "content": [{"type": "text", "text": text}]}`
   (ключ `isError` первым);
3. измерение — `len(json.dumps(envelope, ensure_ascii=False).encode("utf-8"))`:
   повторное экранирование `text` внутри envelope включено;
4. внешний JSON-RPC framing `{"jsonrpc","id","result"}` НЕ входит в бюджет и
   учитывается отдельно (задача 15).

Единственный общий helper — `mcp_result_bytes(payload, is_error=False)` в
`lgm_core/mr_review_models.py`; задачи 6/7/15 обязаны переиспользовать его и не
изобретать per-task sizing (считать только исходные символы запрещено). CLI
`--json` result не должен превышать ту же границу. Kotlin-поверхности бюджет не
измеряют: bounded reader/search — Python-side ops; при появлении JVM-поверхности
алгоритм переносится из этого раздела дословно.

## 10. Postbox artifacts (v2/v3 пути)

| Artifact | Path |
| --- | --- |
| code bundle | `mr-review/code/<code_id>.bundle` |
| snapshot manifest | `mr-review/snapshots/<snapshot_id>.json` |
| v2 review batch | `mr-replies/mr-!<iid>-<batch_id>.review.json` |

Manifest загружается последним и содержит digest/size code bundle; HOME
проверяет digest, prerequisites, объекты и identity до атомарной установки —
полуснимок не становится current. Лимит отдельного code artifact до шифрования —
128 MiB (`CODE_ARTIFACT_MAX_BYTES`), превышение — `review-package-too-large`.
Legacy `.md` пути `mr-replies/` не меняются.

## 11. Fixtures

Каталог `tests/fixtures/mr_review_protocol/` — единственный общий источник для
обоих раннеров. Envelope fixture (метаданные теста, не protocol payload):

```
{
  "fixture": "<имя>",
  "object_kind": "snapshot"|"finding"|"batch"|"receipt"|"probe",
  "expect": "valid"|"invalid"|"stale",
  "expected_error": "<code>",        # только expect=invalid
  "expected_digests": {…},           # только expect=valid|stale: тройка раздела 2
  "object": {…},                     # protocol payload
  "context_snapshot": {…},           # только expect=stale: текущий snapshot
  "expected_canonical": "…"          # только object_kind=probe
}
```

Состав (обязателен, проверяется обоими раннерами):

| Fixture | Покрытие |
| --- | --- |
| `snapshot_non_default_target` | target branch не master/main |
| `snapshot_fork_mr` | source_project_id ≠ target, `source_fetch_ref=refs/merge-requests/30/head` |
| `snapshot_deletion` | old-side position удалённого файла (`new_line=null`) |
| `snapshot_rename` | `old_path ≠ new_path`, обе линии сохранены |
| `snapshot_unicode_null` | Unicode, JSON null, C0-контрольные символы, U+2028, пустой body system note |
| `finding_stale` | latest revision identity ≠ context snapshot identity ⇒ stale=true; после подстановки текущей тройки ⇒ false |
| `batch_v2` | anchored + thread(resolve) items, единая snapshot identity |
| `receipt_recovery` | outcome=recovered, IDs и note permalink восстановлены без повторного POST |
| `canonical_probe` | побайтовая идентичность canonical serializer (сортировка ключей с учётом регистра, экранирование, literal non-ASCII, null/bool/int) |
| `invalid_tampered_snapshot_id` | структурно валиден, digest gate ⇒ `hash-mismatch` |
| `invalid_side` | anchor `side="both"` ⇒ `invalid-side` |
| `invalid_negative_line` | anchor `line=-3` ⇒ `invalid-line` |
| `invalid_unknown_schema_version` | `schema_version=2` ⇒ `unknown-schema-version` |
| `invalid_diff_not_ready` | `gitlab_diff_refs=null` ⇒ `diff-not-ready`; раннеры дополнительно проверяют empty-string форму |

Ожидаемые дайджесты в fixtures вычислены Python-реализацией; Kotlin-тесты
assert'ят те же значения — это и есть доказательство parity. Добавление fixture
требуется для любого нового сценария валидации в задачах 2–17.

## 12. Границы ответственности v1

Модели не реализуют runtime-поведение: store/persistence (задача 2), collectors
(3/4), transport (5), reader/search/progress/environment/findings runtime (6–10),
handoff/publisher/receipts (11–13) строятся поверх этого контракта без его
изменений. Runtime-коды реестра (`snapshot-raced`, `review-object-missing`,
`review-package-too-large`, `stale-snapshot`, `possible-duplicate`,
`incomplete-checkout`, `revision-conflict`, `store-locked`) объявлены здесь как
закрытый словарь и выбрасываются соответствующими задачами.
