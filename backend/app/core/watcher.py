import asyncio
from pathlib import Path

from watchdog.events import FileSystemEventHandler
from watchdog.observers import Observer

from app.core.logger import get_logger


class FileChangeHandler(FileSystemEventHandler):
    """Handles filesystem events and triggers WebSocket updates"""

    def __init__(self, callback):
        self.callback = callback
        self.logger = get_logger()

    def on_any_event(self, event):
        # Ignore git internal files and logs to prevent infinite loops
        # Use str() to ensure path is string for comparison
        path = str(event.src_path)
        if ".git" in path or "backend.log" in path or "__pycache__" in path:
            return

        if event.is_directory:
            return

        try:
            # We are in a background thread of watchdog.
            # We need to call the callback which might be async.
            self.callback(event)
        except Exception as e:
            self.logger.error(f"Error handling file event: {e}")


class FilesystemWatcher:
    """Watchdog wrapper for monitoring storage"""

    def __init__(self, path: Path, event_callback):
        self.path = path
        self.event_callback = event_callback
        self.observer = Observer()
        self.handler = FileChangeHandler(self._handle_event)
        self.logger = get_logger()
        self._running = False
        self._loop = None

    def _handle_event(self, event):
        """Internal handler to bridge to the async callback"""
        event_data = {
            "type": event.event_type,
            "path": event.src_path,
            "is_dir": event.is_directory,
        }

        if self._loop and self._loop.is_running():
            from app.routers.websocket import notify_file_change

            asyncio.run_coroutine_threadsafe(notify_file_change(event_data), self._loop)

    def start(self, loop=None):
        if self._running:
            return

        self._loop = loop or asyncio.get_event_loop()

        if not self.path.exists():
            self.logger.warning(f"Cannot watch non-existent path: {self.path}")
            return

        self.observer.schedule(self.handler, str(self.path), recursive=True)
        self.observer.start()
        self._running = True
        self.logger.info(f"Started filesystem watcher on {self.path}")

    def stop(self):
        if not self._running:
            return

        self.observer.stop()
        self.observer.join()
        self._running = False
        self.logger.info("Stopped filesystem watcher")
