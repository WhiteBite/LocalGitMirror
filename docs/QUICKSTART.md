# Быстрый старт

Сервер запускается только на домашнем ПК. Полная картина (что где запускается) — в [ARCHITECTURE.md](ARCHITECTURE.md), он главный.

## Установка и запуск

```bash
python run.py            # прод: HTTPS на WEB_PORT (по умолчанию 443), отдаёт собранный SPA
python run.py dev        # разработка: backend с --reload + Vite в одной консоли
```

Лаунчер сам создаёт `backend/venv`, ставит зависимости и генерирует самоподписанный сертификат `cert.pem` / `key.pem`. Активировать venv вручную не нужно.

Прод-сервер слушает `https://localhost:443` (порт из `.env`, `WEB_PORT`). Swagger доступен на том же порту: `https://localhost/docs`.

## Разработка frontend

Для dev достаточно одной команды `python run.py dev` из корня репозитория: она поднимает backend (uvicorn `--reload` на `WEB_PORT`) и Vite (`http://localhost:5173`) в одной консоли, один Ctrl+C останавливает оба.

Если Vite нужен отдельно:

```bash
cd frontend
npm install
npm run dev              # http://localhost:5173
```

Dev-сервер Vite проксирует запросы на прод-порт бэкенда (`frontend/vite.config.js`):
- API-вызовы `/api/*` перенаправляются на `https://localhost:443`
- WebSocket `/ws` перенаправляется на `wss://localhost:443`

То есть для работы прокси прод-бэкенд должен быть запущен (`python run.py`). Если `WEB_PORT` в `.env` отличается от 443, launchер печатает предупреждение и прокси нужно поправить вручную в `frontend/vite.config.js`.

## Доступные маршруты SPA

- `/` - Панель управления (обзор системы, статистика, недавняя активность)
- `/files` - Браузер файлов (навигация и предпросмотр файлов)
- `/search` - Глобальный поиск
- `/buffer` (алиас `/exchange`) - Обмен файлами и буфер обмена между машинами
- `/history` - История коммитов
- `/settings` - Настройки (общая конфигурация, Git)

## Структура проекта

```
LocalGitMirror/
├── run.py               # канонический лаунчер (prod/dev)
├── lgm.py               # CLI поверх реестра операций lgm_core
├── lgm_mcp.py           # MCP-сервер (stdio), те же операции
├── backend/
│   ├── app/main.py      # FastAPI приложение
│   ├── app/core/        # Бизнес-логика
│   └── app/routers/     # API endpoints
├── frontend/            # Vue 3 SPA (Vite, Pinia, Tailwind)
├── idea-plugin/         # IntelliJ-плагин DocCache (Kotlin)
├── lgm_core/            # движок CLI/MCP (REGISTRY операций)
├── storage/             # данные сервера (STORAGE_PATH в .env)
└── docs/                # эта документация
```

## Конфигурация

Порты и ключи берутся из `.env` в корне (`WEB_PORT`, `GIT_PORT`, `REDIRECT_HTTP_PORT`, `API_KEY`, `SYNC_PASSWORD`, `STORAGE_PATH`). `API_KEY` обязателен: без него все защищённые маршруты отвечают 503 (fail-closed). Описание всех переменных — в разделе Operational constants в [ARCHITECTURE.md](ARCHITECTURE.md).

## Сборка для продакшна

```bash
npm --prefix frontend run build
```

Результат в `frontend/dist/`; прод-сервер (`python run.py`) раздаёт его сам, отдельной раздачи не нужно.

## Распространённые проблемы

### Порт уже используется

`run.py` освобождает свой порт сам: завершает только предыдущее дерево процессов LocalGitMirror, посторонний процесс на порту не трогает и печатает предупреждение с PID.

### Не удалось подключиться к API

- Убедитесь, что прод-бэкенд запущен (`python run.py`) и слушает `WEB_PORT` из `.env` (по умолчанию 443)
- Сервер на self-signed сертификате: браузер покажет предупреждение, curl нужен с `-k`
- Проверьте консоль браузера и вкладку Network на ошибки CORS
- Конфигурация прокси — `frontend/vite.config.js` (таргет `https://localhost:443`)

### Лог-стрим не обновляется

WebSocket живёт на `/ws/logs` того же порта; проверьте, что прокси/соединение доходит до бэкенда.
