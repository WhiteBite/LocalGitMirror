import os

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect

from app.core.logger import get_logger

router = APIRouter(tags=["websocket"])

# Active WebSocket connections
active_connections = []
file_watch_connections = []


def _validate_ws_token(websocket: WebSocket) -> bool:
    """Validate API key from query param before accepting WS connection."""
    expected = os.getenv("API_KEY", "")
    if not expected:
        return True  # No auth configured — allow all
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


@router.get("/api/logs")
async def get_logs(limit: int = Query(100, ge=1, le=1000)):
    """Get recent logs (HTTP endpoint)"""
    logger = get_logger()
    logs = logger.get_recent_logs(limit=limit)

    return {"success": True, "logs": logs, "count": len(logs)}


@router.delete("/api/logs")
async def clear_logs():
    """Clear all logs"""
    logger = get_logger()
    success = logger.clear_logs()

    return {
        "success": success,
        "message": "Logs cleared successfully" if success else "Failed to clear logs",
    }


@router.get("/api/logs/stats")
async def get_log_stats():
    """Get log statistics"""
    logger = get_logger()
    logs = logger.get_recent_logs(limit=1000)

    stats = {
        "total": len(logs),
        "info": sum(1 for log in logs if log.get("level") == "INFO"),
        "warning": sum(1 for log in logs if log.get("level") == "WARNING"),
        "error": sum(1 for log in logs if log.get("level") == "ERROR"),
        "active_connections": len(active_connections),
    }

    return {"success": True, "stats": stats}
