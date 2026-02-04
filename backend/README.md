# LocalGitMirror Backend

FastAPI-based backend for LocalGitMirror project synchronization.

## Structure

```
backend/
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI application
│   ├── core/                # Business logic
│   │   ├── git_handler.py   # Git server
│   │   ├── repo_manager.py  # Repository management
│   │   ├── git_utils.py     # Git operations
│   │   ├── logger.py        # Logging system
│   │   ├── settings_manager.py
│   │   └── system_monitor.py
│   ├── routers/             # API endpoints
│   │   ├── api.py           # Main API
│   │   ├── web.py           # Web pages
│   │   ├── settings.py      # Settings API
│   │   └── websocket.py     # WebSocket logs
│   └── models/              # Pydantic models
├── requirements.txt
└── run.py                   # Entry point
```

## Installation

```bash
cd backend
pip install -r requirements.txt
```

## Running

```bash
# Development
python run.py

# Production
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## Configuration

Create `.env` file in project root:

```env
WEB_PORT=8000
GIT_PORT=8081
STORAGE_PATH=storage
OLLAMA_URL=http://localhost:11434
OLLAMA_MODEL=llama3.2
```

## API Documentation

Once running, visit:
- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc
