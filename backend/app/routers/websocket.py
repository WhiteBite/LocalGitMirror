from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query
from app.core.logger import get_logger

router = APIRouter(tags=["websocket"])

# Active WebSocket connections
active_connections = []


@router.websocket("/ws/logs")
async def websocket_logs(websocket: WebSocket):
    """WebSocket endpoint for real-time log streaming"""
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
    except Exception as e:
        print(f"WebSocket error: {e}")
    finally:
        # Clean up on disconnect
        logger.remove_websocket(websocket)
        if websocket in active_connections:
            active_connections.remove(websocket)


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
