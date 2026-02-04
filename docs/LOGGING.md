# System Logging Documentation

## 🎯 Overview

LocalGitMirror includes a comprehensive system logging solution with:

- ✅ **Real-time WebSocket streaming** - See logs as they happen
- ✅ **File-based persistence** - Logs saved to disk with rotation
- ✅ **Vue.js component** - Beautiful UI with filtering and export
- ✅ **Three log levels** - INFO, WARNING, ERROR
- ✅ **Structured format** - JSON logs with context
- ✅ **Auto-reconnect** - Resilient WebSocket connection

## 🚀 Quick Start

### 1. Start the Application

```bash
python main.py
```

### 2. View Logs

The SystemLog component appears at the bottom of the Dashboard. You should see:
- 🟢 Green "Live" indicator (WebSocket connected)
- Recent log entries
- Filter buttons (All, INFO, WARNING, ERROR)
- Export and Clear buttons

### 3. Add Logging to Your Code

```python
from core.logger import get_logger

logger = get_logger()

# Info level - normal operations
logger.info("User logged in", {"user_id": 123, "ip": "192.168.1.1"})

# Warning level - potential issues
logger.warning("API rate limit approaching", {"requests": 950, "limit": 1000})

# Error level - failures
logger.error("Database connection failed", {"error": str(e), "retry": 3})
```

## 📖 Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     LocalGitMirror Application                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        BACKEND (Python)                          │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                   SystemLogger (Singleton)                │  │
│  │              ┌───────────┴───────────┐                   │  │
│  │              ▼                       ▼                    │  │
│  │    ┌─────────────────┐    ┌──────────────────┐          │  │
│  │    │  File Handler   │    │  WebSocket Pool  │          │  │
│  │    │  (Rotating)     │    │  (Broadcast)     │          │  │
│  │    └─────────────────┘    └──────────────────┘          │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      FRONTEND (Vue.js)                           │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              SystemLog.vue Component                     │  │
│  │  • WebSocket Connection                                  │  │
│  │  • Real-time updates                                     │  │
│  │  • Filter by level                                       │  │
│  │  • Export/Clear functionality                            │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Backend Components

#### 1. `core/logger.py` - SystemLogger Class

**Features:**
- Singleton pattern for global access
- Dual logging: file + WebSocket broadcast
- Log rotation (10 MB max, 5 backup files)
- Three log levels: INFO, WARNING, ERROR
- Structured JSON format
- Thread-safe operations

**Log Format:**
```json
{
  "timestamp": "2026-01-28T12:00:00.123Z",
  "level": "INFO",
  "message": "Git push received",
  "details": {"repo": "default", "branch": "main"}
}
```

#### 2. `routers/websocket.py` - WebSocket Router

**Endpoints:**

- **WebSocket:** `ws://localhost:8000/ws/logs`
  - Real-time log streaming
  - Sends last 50 logs on connect
  - Auto-reconnect support
  - Ping/pong keepalive

- **GET** `/api/logs?limit=100`
  - Retrieve log history
  - Configurable limit (1-1000)
  - Returns JSON array

- **DELETE** `/api/logs`
  - Clear all log files
  - Removes main + rotated logs

- **GET** `/api/logs/stats`
  - Log statistics
  - Count by level
  - Active WebSocket connections

### Frontend Component

#### `frontend/src/components/SystemLog.vue`

**Features:**
- Real-time WebSocket connection
- Auto-reconnect on disconnect
- Filter by level (All, INFO, WARNING, ERROR)
- Collapsible panel
- Auto-scroll to new logs
- Export logs to text file
- Clear logs button
- Color-coded log levels
- Connection status indicator
- Maximum 100 logs in memory

**Color Scheme:**
- INFO: Blue (`text-blue-400`)
- WARNING: Yellow (`text-yellow-400`)
- ERROR: Red (`text-red-400`)

## 💻 Usage Examples

### Backend - Common Patterns

**Git Operations:**
```python
logger.info("Git push received", {"repo": repo_name, "branch": branch})
logger.info("Repository synced", {"repo": repo_name, "files": file_count})
```

**File Operations:**
```python
logger.info("File opened", {"file": filename, "editor": "cursor"})
logger.error("File not found", {"file": filename, "path": abs_path})
```

**API Endpoints:**
```python
logger.info("API request", {"endpoint": "/api/sync", "method": "POST"})
logger.error("API error", {"endpoint": "/api/sync", "status": 500, "error": str(e)})
```

### Frontend - Display Logs

```vue
<template>
  <SystemLog />
</template>

<script setup>
import SystemLog from '@/components/SystemLog.vue'
</script>
```

### API - Access Logs

```bash
# Get recent logs
curl http://localhost:8000/api/logs?limit=100

# Clear logs
curl -X DELETE http://localhost:8000/api/logs

# Get statistics
curl http://localhost:8000/api/logs/stats
```

## 🔧 Configuration

### Log Rotation

Edit `core/logger.py`:

```python
file_handler = RotatingFileHandler(
    self.log_file,
    maxBytes=10 * 1024 * 1024,  # 10 MB
    backupCount=5,               # 5 backup files
    encoding='utf-8'
)
```

### Frontend Max Logs

Edit `frontend/src/components/SystemLog.vue`:

```javascript
const MAX_LOGS = 100  // Maximum logs in memory
```

### WebSocket Keepalive

Edit `frontend/src/components/SystemLog.vue`:

```javascript
setInterval(() => {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send('ping')
  }
}, 30000)  // 30 seconds
```

## 📁 File Structure

```
storage/
└── logs/
    ├── system.log          # Current log file
    ├── system.log.1        # Rotated backup
    ├── system.log.2
    ├── system.log.3
    ├── system.log.4
    └── system.log.5        # Oldest backup
```

## 🐛 Troubleshooting

### WebSocket Not Connecting

**Symptoms:** Red "Disconnected" indicator

**Solutions:**
1. Check if backend is running
2. Verify WebSocket endpoint: `/ws/logs`
3. Check browser console for errors
4. Ensure no firewall blocking WebSocket

### Logs Not Appearing

**Symptoms:** No logs in UI or file

**Solutions:**
1. Check if logger is initialized in `main.py`
2. Verify `storage/logs/` directory exists
3. Check file permissions
4. Look for errors in console

### High Memory Usage

**Symptoms:** Browser slowing down

**Solutions:**
1. Clear old logs: `DELETE /api/logs`
2. Reduce MAX_LOGS in frontend
3. Check for log rotation issues

## 🎯 Best Practices

### Backend

1. **Use appropriate log levels**
   - INFO: Normal operations, state changes
   - WARNING: Recoverable issues, deprecations
   - ERROR: Failures, exceptions

2. **Include context in details:**
   ```python
   logger.info("File opened", {
       "file": "main.py",
       "repo": "default",
       "user": "admin"
   })
   ```

3. **Don't log sensitive data:**
   - Avoid passwords, tokens, API keys
   - Sanitize user input

4. **Keep messages concise:**
   - Clear, actionable messages
   - Details in the `details` dict

### Frontend

1. **Filter logs appropriately:**
   - Use level filters for debugging
   - Export logs for analysis

2. **Monitor connection status:**
   - Check the live indicator
   - Logs may be delayed if disconnected

3. **Clear logs periodically:**
   - Prevents memory buildup
   - Improves performance

## 📊 API Reference

### Log Levels

| Level   | Use Case                          | Color  |
|---------|-----------------------------------|--------|
| INFO    | Normal operations, state changes  | Blue   |
| WARNING | Potential issues, deprecations    | Yellow |
| ERROR   | Failures, exceptions              | Red    |

### API Endpoints

| Method | Endpoint          | Description              |
|--------|-------------------|--------------------------|
| GET    | `/api/logs`       | Get log history          |
| DELETE | `/api/logs`       | Clear all logs           |
| GET    | `/api/logs/stats` | Get log statistics       |
| WS     | `/ws/logs`        | Real-time log streaming  |

## 🔮 Future Enhancements

- [ ] Log search functionality
- [ ] Server-side filtering
- [ ] Log level configuration via API
- [ ] Email/Slack notifications
- [ ] Analytics dashboard
- [ ] Structured query language
- [ ] Log compression
- [ ] Authentication

---

**Version:** 1.0.0  
**Last Updated:** 2026-01-28  
**Part of:** LocalGitMirror v3.0
