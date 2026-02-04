# Migration Guide: Old Structure → New Structure

## What Changed

### Old Structure (v3.1)
```
LocalGitMirror/
├── core/              # Backend logic
├── routers/           # API endpoints
├── main.py            # Entry point
├── frontend/          # Vue app
└── *.md               # Docs everywhere
```

### New Structure (v3.2)
```
LocalGitMirror/
├── backend/
│   ├── app/
│   │   ├── core/      # Business logic
│   │   ├── routers/   # API endpoints
│   │   └── main.py    # FastAPI app
│   └── run.py         # Entry point
├── frontend/          # Vue app
├── docs/              # All documentation
└── README.md
```

## Benefits

✅ **Clean separation** - Frontend and backend are isolated  
✅ **Standard Python structure** - Follows best practices  
✅ **Better imports** - `from app.core.logger import get_logger`  
✅ **Scalable** - Easy to add new modules  
✅ **Docker-ready** - Each part can be containerized  
✅ **Organized docs** - All documentation in `/docs/`

## Migration Steps

### 1. Update Python Path

The backend now runs from `/backend/` directory. The `run.py` script handles this automatically.

### 2. Running the Application

**Old way:**
```bash
python main.py
```

**New way:**
```bash
cd backend
python run.py
```

### 3. Import Changes

All imports now use `app.` prefix:

**Old:**
```python
from core.logger import get_logger
from routers.api import router
```

**New:**
```python
from app.core.logger import get_logger
from app.routers.api import router
```

### 4. Environment Variables

`.env` file stays in project root (no changes needed).

### 5. Storage Path

Storage path remains relative to project root: `storage/`

## Testing the Migration

1. **Stop old version** (if running)

2. **Install dependencies** (if needed)
   ```bash
   cd backend
   pip install -r requirements.txt
   ```

3. **Run new version**
   ```bash
   python run.py
   ```

4. **Verify**
   - Visit http://localhost:8000
   - Check Git server starts
   - Test file browser
   - Check logs work

## Rollback (if needed)

The old structure files are still present. To rollback:

1. Stop new version
2. Run old version: `python main.py`

## Cleanup Old Files

Once you've verified everything works, you can remove:

```bash
# Old backend files (keep for now as backup)
# core/
# routers/
# main.py
# test_logging.py

# Old docs (moved to /docs/)
# *.md files in root (except README.md)
```

## Common Issues

### Import Errors

**Error:** `ModuleNotFoundError: No module named 'core'`

**Solution:** Make sure you're running from `/backend/` directory:
```bash
cd backend
python run.py
```

### Path Issues

**Error:** `FileNotFoundError: storage/`

**Solution:** Storage path is relative to project root. The app handles this automatically.

### Port Already in Use

**Error:** `Address already in use`

**Solution:** Stop the old version first or change ports in `.env`

## Questions?

See `/docs/TODO.md` for development roadmap or check other documentation in `/docs/`.
