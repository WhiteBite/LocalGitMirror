# LocalGitMirror Backend

FastAPI-бэкенд домашнего сервера: зашифрованный почтовый ящик, git-хранилище томов, раздача инструментов и плагина. Полная картина, роли машин и крипто-контракты — в [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md), это главный документ.

## Запуск

Отдельной точки входа у бэкенда нет: `backend/run.py` не существует. Единый лаунчер — `run.py` в корне репозитория.

```bash
python run.py        # прод: HTTPS на WEB_PORT (443), отдаёт собранный SPA из frontend/dist
python run.py dev    # разработка: uvicorn --reload на том же WEB_PORT + Vite на 5173, один консольный лог
```

Лаунчер сам создаёт `backend/venv`, ставит зависимости и генерирует самоподписанный `cert.pem` / `key.pem`. В dev-режиме прокси Vite нацелен на `https://localhost:443`, поэтому `WEB_PORT` оставляют равным 443 — иначе лаунчер печатает предупреждение.

Ручной `uvicorn app.main:app` годится только для отладки с собственным `.env`: приложение рассчитывает, что lifespan создаст менеджеры и передаст v3-ключи в роутеры.

Swagger, ReDoc и OpenAPI отключены в `app/main.py` (`docs_url=None, redoc_url=None, openapi_url=None`) — маршруты смотрите в `app/routers/`, а не через `/docs`.

## Тесты

```powershell
backend\venv\Scripts\python.exe -m pytest backend/tests/ -q `
  --ignore=backend/tests/test_api.py `
  --ignore=backend/tests/test_integration.py `
  --ignore=backend/tests/test_ui_api.py
```

Три исключённых файла бьют по живому запущенному серверу и в обычный прогон не входят.

## Структура

```
backend/app/
├── main.py       # сборка приложения: auth (X-Session-ID / Authorization: Bearer, fail-closed 503
│                 # без API_KEY), монтирование роутеров, lifespan (менеджеры, v3-ключи в роутеры,
│                 # планировщик бэкапов vault, LAN-beacon), статика SPA
├── routers/      # sync.py (бандлы, negotiate, export, preview, delete_ref, prune), file_sync.py
│                 # (почтовый ящик, TTL около 7 дней), deps.py (запрос/ответ/ack корпоративных
│                 # артефактов), files.py (файлы чекаута), repos.py (тома, storage, git-демон по
│                 # требованию), settings.py (частичные секции general/git/ui), mirror.py (vault,
│                 # maven, артефакты), plugin.py (раздача плагина), bootstrap.py (скрипт первой
│                 # установки для рабочей машины), system.py, auth.py, buffer.py, shared.py,
│                 # web.py, websocket.py, state.py, _rid.py, git_http.py (smart-HTTP — в рабочем
│                 # процессе не монтируется)
└── core/         # repo_manager.py (тома: storage/<repo> — это домашний чекаут,
                  # storage/.lgm/bare/<repo>.git — bare-зеркало), git_bundle.py (сборка и
                  # применение бандлов; отказ применять в грязный чекаут), path_safety.py
                  # (containment клиентских путей), bundle_crypto / envelope_crypto, artifact_store
                  # и vault_backup (CAS-хранилище и его бэкапы), shared_manager, settings_manager,
                  # repo_lock, logger, git_handler (dulwich-демон: конструируется, но автоматически
                  # не стартует), git_utils, npm_cache, corporate_tools, mirror_dataplane,
                  # system_monitor
```

## Конфигурация (`.env` в корне репозитория)

| Ключ | По умолчанию | Назначение |
|---|---|---|
| `WEB_PORT` | **443** | прод и dev: HTTPS (FastAPI + SPA + API) |
| `GIT_PORT` | **8444** | dulwich-демон; автоматически не стартует, включается только `POST /api/git/start` |
| `REDIRECT_HTTP_PORT` | не задан | HTTP→HTTPS редирект выключен |
| `STORAGE_PATH` | `storage` | корень хранилища томов; смена применяется только после рестарта сервера |
| `API_KEY` | — | обязателен: без него защищённые маршруты отвечают 503 |
| `SYNC_PASSWORD` | — | пароль шифрования бандлов и архива плагина (legacy-путь); v3-релей использует закреплённый ключ сервера |
| `GIT_USER`, `GIT_PASS`, `SILENT_GIT` | — | smart-HTTP; рабочий процесс его не использует |
| `BACKUP_PASSWORD` | — | бэкапы vault |
| `OLLAMA_URL`, `OLLAMA_MODEL` | — | наследство ранних версий: отдаётся только в `/api/config`, подключений к Ollama нет |

Прочие серверные ключи (`LGM_VAULT_PATH`, `LGM_BUFFER_*`) перечислены в разделе Operational constants в [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).
