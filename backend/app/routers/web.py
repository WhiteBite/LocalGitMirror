import html
import os
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, PlainTextResponse
from starlette.staticfiles import StaticFiles

from app.core.mirror_dataplane import _is_loopback

router = APIRouter(tags=["web"])

# Serve frontend index.html
FRONTEND_DIST = Path(__file__).parent.parent.parent.parent / "frontend" / "dist"
INDEX_HTML = FRONTEND_DIST / "index.html"


class LoopbackStaticFiles(StaticFiles):
    """StaticFiles, отдающий файлы только loopback-клиентам.

    SPA-бандл живёт на том же 0.0.0.0, что и API, но предназначен только
    домашней машине; наружу отдаём 404, не подтверждая существование.
    """

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            client = scope.get("client")
            host = client[0] if client else ""
            if not _is_loopback(host):
                response = PlainTextResponse("Not Found", status_code=404)
                await response(scope, receive, send)
                return
        await super().__call__(scope, receive, send)


@router.get("/")
@router.get("/dashboard")
@router.get("/files")
@router.get("/settings")
async def serve_index(request: Request):
    """Serve frontend index.html for all main routes (SPA), loopback only."""
    client = request.client.host if request.client else ""
    if not _is_loopback(client):
        raise HTTPException(status_code=404, detail="Not Found")
    if INDEX_HTML.exists():
        api_key = html.escape(os.getenv("API_KEY", ""), quote=True)
        page = INDEX_HTML.read_text(encoding="utf-8")
        # Ключ подставляется при отдаче, а не сборкой: в dist его быть не должно.
        script = f'<script>window.__LGM_API_KEY__="{api_key}";</script>'
        return HTMLResponse(page.replace("</head>", f"{script}</head>", 1))
    return HTMLResponse(
        content="<h1>Frontend not built</h1><p>Please run 'npm run build' in the frontend directory.</p>",
        status_code=404,
    )
