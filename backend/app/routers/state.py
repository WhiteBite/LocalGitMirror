from datetime import datetime
from typing import Optional


class ServerState:
    status = "idle"  # idle, processing, ready
    last_push_time = None
    last_sync_time: Optional[datetime] = None


state = ServerState()
