import json
import logging
from pathlib import Path
from datetime import datetime
from typing import Optional, Dict, Any, List
from logging.handlers import RotatingFileHandler
import asyncio
from threading import Lock


class SystemLogger:
    """Singleton system logger with file and WebSocket support"""

    _instance = None
    _lock = Lock()

    def __new__(cls, *args, **kwargs):
        if not cls._instance:
            with cls._lock:
                if not cls._instance:
                    cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, storage_path: Optional[Path] = None):
        # Only initialize once
        if hasattr(self, "_initialized"):
            return

        self._initialized = True
        self.storage_path = storage_path or Path("storage")

        self.log_dir = self.storage_path / "logs"
        self.log_file = self.log_dir / "system.log"
        self.websocket_connections: List[Any] = []
        self._setup_logger()

    def _setup_logger(self):
        """Setup file logger with rotation"""
        self.log_dir.mkdir(parents=True, exist_ok=True)

        # Create Python logger
        self.logger = logging.getLogger("LocalGitMirror")
        self.logger.setLevel(logging.INFO)
        self.logger.handlers.clear()

        # Rotating file handler (10 MB, 5 files)
        file_handler = RotatingFileHandler(
            self.log_file,
            maxBytes=10 * 1024 * 1024,  # 10 MB
            backupCount=5,
            encoding="utf-8",
        )
        file_handler.setLevel(logging.INFO)

        # JSON formatter for structured logs
        formatter = logging.Formatter("%(message)s")
        file_handler.setFormatter(formatter)

        self.logger.addHandler(file_handler)

    def _create_log_entry(
        self, level: str, message: str, details: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        """Create structured log entry"""
        return {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "level": level,
            "message": message,
            "details": details or {},
        }

    def _log(self, level: str, message: str, details: Optional[Dict[str, Any]] = None):
        """Internal logging method"""
        entry = self._create_log_entry(level, message, details)

        # Log to file as JSON
        self.logger.log(getattr(logging, level), json.dumps(entry, ensure_ascii=False))

        # Broadcast to WebSocket clients
        self._broadcast_to_websockets(entry)

    def _broadcast_to_websockets(self, entry: Dict[str, Any]):
        """Broadcast log entry to all connected WebSocket clients"""
        if not self.websocket_connections:
            return

        # Create async task to send to all connections
        try:
            # Get or create event loop
            try:
                loop = asyncio.get_event_loop()
            except RuntimeError:
                # No event loop in current thread
                return

            if loop.is_running():
                # Schedule coroutine in running loop
                asyncio.create_task(self._send_to_all_websockets(entry))
        except Exception:
            # Silently fail if we can't broadcast
            pass

    async def _send_to_all_websockets(self, entry: Dict[str, Any]):
        """Send log entry to all WebSocket connections"""
        disconnected = []

        for connection in self.websocket_connections:
            try:
                await connection.send_json(entry)
            except Exception:
                disconnected.append(connection)

        # Remove disconnected clients
        for conn in disconnected:
            try:
                self.websocket_connections.remove(conn)
            except ValueError:
                pass

    def add_websocket(self, websocket):
        """Add WebSocket connection"""
        if websocket not in self.websocket_connections:
            self.websocket_connections.append(websocket)

    def remove_websocket(self, websocket):
        """Remove WebSocket connection"""
        try:
            self.websocket_connections.remove(websocket)
        except ValueError:
            pass

    def info(self, message: str, details: Optional[Dict[str, Any]] = None):
        """Log INFO level message"""
        self._log("INFO", message, details)

    def warning(self, message: str, details: Optional[Dict[str, Any]] = None):
        """Log WARNING level message"""
        self._log("WARNING", message, details)

    def error(self, message: str, details: Optional[Dict[str, Any]] = None):
        """Log ERROR level message"""
        self._log("ERROR", message, details)

    def get_recent_logs(self, limit: int = 100) -> List[Dict[str, Any]]:
        """Get recent logs from file"""
        logs = []

        if not self.log_file.exists():
            return logs

        try:
            with open(self.log_file, "r", encoding="utf-8") as f:
                lines = f.readlines()

            # Get last N lines
            recent_lines = lines[-limit:] if len(lines) > limit else lines

            for line in recent_lines:
                try:
                    log_entry = json.loads(line.strip())
                    logs.append(log_entry)
                except json.JSONDecodeError:
                    continue

        except Exception as e:
            self.error("Failed to read logs", {"error": str(e)})

        return logs

    def clear_logs(self):
        """Clear all log files"""
        try:
            # Clear main log file
            if self.log_file.exists():
                self.log_file.write_text("")

            # Clear rotated log files
            for i in range(1, 6):
                rotated_file = Path(f"{self.log_file}.{i}")
                if rotated_file.exists():
                    rotated_file.unlink()

            self.info("Logs cleared")
            return True
        except Exception as e:
            self.error("Failed to clear logs", {"error": str(e)})
            return False


# Global logger instance
_logger_instance: Optional[SystemLogger] = None


def get_logger(storage_path: Optional[Path] = None) -> SystemLogger:
    """Get or create global logger instance"""
    global _logger_instance
    if _logger_instance is None:
        _logger_instance = SystemLogger(storage_path)
    return _logger_instance
