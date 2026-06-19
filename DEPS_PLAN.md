# Gradle deps sync — план

## Что делаем

Перенос только **внутренних (nexus) gradle-зависимостей** с рабочего на домашний.
Отправляется минимальная дельта: `(deps_проекта_с_nexus) − (что_дома_уже_есть)`.

## Как работает (взгляд пользователя)

```
ДОМА                         РАБОЧИЙ
1. [⋯] → Запросить deps  →   3. [⋯] → Ответить на запрос
   сканирует ~/.gradle,         резолвит проект,
   шлёт manifest                фильтрует по nexus,
                                вычитает дом,
                                шлёт архив

5. [⋯] → Применить deps  ←   4. (готово)
   распаковка в
   ~/.gradle/caches/
```

## Backend endpoints

Все blob'ы зашифрованы тем же `BundleCrypto`. Сервер их не читает.

| Метод | Путь | Назначение |
|---|---|---|
| `POST` | `/api/deps/request` | Дом → сервер: сохранить зашифрованный manifest |
| `GET`  | `/api/deps/pending?repo=` | Рабочий: список ожидающих запросов |
| `GET`  | `/api/deps/manifest?repo=&id=` | Рабочий: скачать зашифрованный manifest |
| `POST` | `/api/deps/respond` | Рабочий → сервер: зашифрованный архив (multipart) |
| `GET`  | `/api/deps/responses?repo=` | Дом: список готовых ответов |
| `GET`  | `/api/deps/fetch?repo=&id=` | Дом: скачать архив (с прогрессом) |
| `DELETE` | `/api/deps/ack?repo=&id=` | Дом: пометить применённым (удаляет с сервера) |

Хранение: `storage/deps/<repo>/requests/<id>.bin`, `storage/deps/<repo>/responses/<id>.bin`.

## Формат manifest (внутри зашифрованного blob'а)

```json
{
  "version": 1,
  "requester": "ДОМ",
  "project": "onyx-platform",
  "artifacts": [
    {"g":"org.springframework","n":"spring-core","v":"6.1.5","sha1":"abc..."},
    {"g":"com.kryptonit","n":"shared","v":"2.3.0","sha1":"def..."}
  ]
}
```

## Формат архива (внутри зашифрованного blob'а)

ZIP. Внутри — структура совпадает с `~/.gradle/caches/modules-2/files-2.1/`:

```
<group>/<name>/<version>/<sha1>/<filename>
org.springframework/spring-core/6.1.5/abc.../spring-core-6.1.5.jar
```

При распаковке кладём напрямую в `~/.gradle/caches/modules-2/files-2.1/`. Gradle подхватит.

## Плагин — новые actions в «⋯»

- **«Запросить gradle-зависимости»** (на доме) — `RequestDepsAction`
- **«Ответить на запрос зависимостей»** (на рабочем) — `RespondDepsAction`
- **«Применить полученные зависимости»** (на доме) — `ApplyDepsAction`

Кнопки видны всегда; внутри — проверка что есть pending/responses.

## Settings — одно новое поле

```
Внутренние репо (substring URL для фильтра, через запятую)
[nexus.kryptonit,artifacts.company]
```

Если пусто → дельта без фильтра по URL (передаётся всё что у получателя нет).

## Сбор зависимостей

На дом (sender of manifest):
- Сканируем `~/.gradle/caches/modules-2/files-2.1/<group>/<name>/<version>/<sha1>/`
- Каждый артефакт → одна запись в manifest
- Размер `<sha1>` берётся из имени папки (Gradle так раскладывает)

На рабочем (responder):
- `./gradlew :dependencies --configuration=runtimeClasspath` (и `compileClasspath`,
  `testRuntimeClasspath`) — список нужного
- Для каждого артефакта смотрим `~/.gradle/caches/modules-2/files-2.1/.../<sha1>/_remote.repositories`
  — содержит origin URL
- Фильтр: substring(origin) ∈ internal_repos
- Diff с manifest от дома (по `g:n:v:sha1`)
- ZIP оставшегося → шифровать → отправить

Если internal_repos пустой → пропускаем фильтр (шлём всё что отсутствует у дома).

## Тест (e2e)

`test_deps_full_flow.py`:
1. Создать репо в storage
2. Симулировать manifest от «дома» (с одной зависимостью)
3. POST /api/deps/request
4. GET /api/deps/pending — увидеть запрос
5. POST /api/deps/respond с фейковым ZIP-архивом (зашифрованным)
6. GET /api/deps/responses — увидеть готовый
7. GET /api/deps/fetch — скачать
8. Расшифровать → проверить что это валидный ZIP
9. DELETE /api/deps/ack — должно очистить

## Что не делаем сейчас

- WebSocket-уведомления (опрос вручную, как у inbox)
- Авто-применение
- Plugin-зависимости Gradle отдельно (если потребуется — выделим в отдельный manifest-class)

## Порядок работ

1. Backend: 6 endpoints + storage
2. Плагин: 3 action + scanner + bundler + applier
3. Settings: 1 поле + i18n
4. Тест e2e
5. Билд + коммит
