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
```

## Документация API

После запуска откройте:
- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc