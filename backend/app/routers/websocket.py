import asyncio
import os

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.core.logger import get_logger

router = APIRouter(tags=["websocket"])

# Active WebSocket connections
active_connections = []
file_watch_connections = []
exchange_connections = []

# Captured from the lifespan so sync (threadpool) router code can broadcast.
_main_loop = None


def set_main_loop(loop) -> None:
    global _main_loop
    _main_loop = loop


def _validate_ws_token(websocket: WebSocket) -> bool:
    """Validate API key from query param before accepting WS connection."""
    expected = os.getenv("API_KEY", "")
    if not expected:
        return False
    token = websocket.query_params.get("key", "")
    return token == expected


@router.websocket("/ws/files")
async def websocket_files(websocket: WebSocket):
    """WebSocket endpoint for real-time file system updates"""
    if not _validate_ws_token(websocket):
        await websocket.close(code=1008)
        return
    await websocket.accept()
    file_watch_connections.append(websocket)

    try:
        while True:
            # Just keep connection open and handle ping/pong
            data = await websocket.receive_text()
            if data == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    except (ConnectionResetError, RuntimeError):
        # Handle Windows connection reset errors silently
        pass
    finally:
        try:
            if websocket in file_watch_connections:
                file_watch_connections.remove(websocket)
        except Exception:
            pass


async def notify_file_change(event_data):
    """Broadcast file change event to all connected clients"""
    for connection in file_watch_connections:
        try:
            await connection.send_json({"type": "file_change", "data": event_data})
        except Exception:
            # Connection might be dead, it will be removed in finally block
            pass


async def _broadcast_exchange(payload):
    for connection in list(exchange_connections):
        try:
            await connection.send_json(payload)
        except Exception:
            pass


def notify_exchange_change(kind: str, **extra) -> None:
    """Broadcast an exchange mutation to /ws/exchange subscribers.

    Safe to call from sync router code: run_coroutine_threadsafe is legal from
    any thread, including the loop's own.
    """
    loop = _main_loop
    if loop is None or not loop.is_running():
        return
    payload = {"type": "exchange_change", "kind": kind}
    payload.update(extra)
    try:
        asyncio.run_coroutine_threadsafe(_broadcast_exchange(payload), loop)
    except RuntimeError:
        pass


@router.websocket("/ws/exchange")
async def websocket_exchange(websocket: WebSocket):
    """Exchange (chat buffer + postbox) change notifications."""
    if not _validate_ws_token(websocket):
        await websocket.close(code=1008)
        return
    await websocket.accept()
    exchange_connections.append(websocket)

    try:
        while True:
            data = await websocket.receive_text()
            if data == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    except (ConnectionResetError, RuntimeError):
        pass
    finally:
        try:
            if websocket in exchange_connections:
                exchange_connections.remove(websocket)
        except Exception:
            pass


@router.websocket("/ws/logs")
async def websocket_logs(websocket: WebSocket):
    """WebSocket endpoint for real-time log streaming"""
    if not _validate_ws_token(websocket):
        await websocket.close(code=1008)
        return
    await websocket.accept()

    logger = get_logger()
    logger.add_websocket(websocket)
    active_connections.append(websocket)

    try:
        # Send recent logs on connect
        recent_logs = logger.get_recent_logs(limit=50)
        for log_entry in recent_logs:
            await websocket.send_json(log_entry)

        # Keep connection alive and listen for client messages
        while True:
            # Wait for any message from client (ping/pong)
            data = await websocket.receive_text()

            # Echo back to confirm connection is alive
            if data == "ping":
                await websocket.send_text("pong")

    except WebSocketDisconnect:
        pass
    except (ConnectionResetError, RuntimeError):
        # Handle Windows connection reset errors silently
        pass
    except Exception as e:
        print(f"WebSocket error: {e}")
    finally:
        # Clean up on disconnect
        try:
            logger.remove_websocket(websocket)
            if websocket in active_connections:
                active_connections.remove(websocket)
        except Exception:
            pass
