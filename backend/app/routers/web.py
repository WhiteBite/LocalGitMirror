from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, HTMLResponse

router = APIRouter(tags=["web"])

# Serve frontend index.html
FRONTEND_DIST = Path(__file__).parent.parent.parent.parent / "frontend" / "dist"
INDEX_HTML = FRONTEND_DIST / "index.html"


@router.get("/")
@router.get("/dashboard")
@router.get("/files")
@router.get("/settings")
async def serve_index():
    """Serve frontend index.html for all main routes (SPA)"""
    if INDEX_HTML.exists():
        return FileResponse(INDEX_HTML)
    return HTMLResponse(
        content="<h1>Frontend not built</h1><p>Please run 'npm run build' in the frontend directory.</p>",
        status_code=404,
    )
