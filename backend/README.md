# LocalGitMirror Backend

FastAPI-бэкенд для синхронизации проектов LocalGitMirror.

## Структура

```
backend/
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI приложение
│   ├── core/                # Бизнес-логика
│   │   ├── git_handler.py   # Git-сервер
│   │   ├── repo_manager.py  # Управление репозиториями
│   │   ├── git_utils.py     # Git-операции
│   │   ├── logger.py        # Система логирования
│   │   ├── settings_manager.py
│   │   └── system_monitor.py
│   ├── routers/             # API endpoints
│   │   ├── api.py           # Основной API
│   │   ├── web.py           # Веб-страницы
│   │   ├── settings.py      # API настроек
│   │   └── websocket.py     # WebSocket логи
│   └── models/              # Pydantic модели
├── requirements.txt
└── run.py                   # Точка входа
```

## Установка

```bash
cd backend
pip install -r requirements.txt
```

## Запуск

```bash
# Разработка
python run.py

# Продакшн
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Конфигурация

Создайте файл `.env` в корне проекта:

```env
WEB_PORT=8000
GIT_PORT=8081
STORAGE_PATH=storage
OLLAMA_URL=http://localhost:11434
OLLAMA_MODEL=llama3.2

# API auth mode
# - If REQUIRE_API_KEY=true, API_KEY must be set (fail-closed).
# - If REQUIRE_API_KEY=false and API_KEY is empty, API is open for local/dev mode.
REQUIRE_API_KEY=false
API_KEY=

# Stealth sync password (used to decrypt uploaded .dmp)
SYNC_PASSWORD=change-me
```

### API key behavior

- Header used by clients: `X-Session-ID`
- `REQUIRE_API_KEY=true` + empty `API_KEY` => server returns `500` (misconfiguration)
- `API_KEY` configured => all protected endpoints require exact `X-Session-ID`
- `API_KEY` empty + `REQUIRE_API_KEY=false` => local/dev open mode

## Документация API

После запуска откройте:
- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc

## Stealth Sync (DMP) API

### POST /api/sync/upload-and-apply

One-shot flow for UI/plugins: upload an encrypted stealth dump (`.dmp`) and apply it immediately.

**Request**: `multipart/form-data`
- `repo` (string): target repository name (must exist)
- `dump_file` (file): encrypted dump created by `stealth_dump_tool.py`

**Response** (always structured):
```json
{
  "success": true,
  "repo": "my-project",
  "dump_file": "dump_my-project_20260306_1200.dmp",
  "commit": "a1b2c3d Fix something",
  "message": "Sync applied successfully"
}
```

Notes:
- Only Python AES-GCM container dumps are supported (no legacy 7-Zip format).
- The endpoint refuses to apply if the workspace has uncommitted changes.
