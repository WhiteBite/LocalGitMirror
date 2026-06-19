from typing import List, Optional

from fastapi import APIRouter, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

router = APIRouter(prefix="/api", tags=["shared"])

shared_manager = None
system_logger = None


# ============ Pydantic models ============


class CreateFolderRequest(BaseModel):
    name: str


class RestoreFileRequest(BaseModel):
    folder: str
    path: str
    commit_hash: str


class CreateSubfolderRequest(BaseModel):
    folder: str
    path: str


class BulkDeleteRequest(BaseModel):
    folder: str
    paths: List[str]


class UpdateTagsRequest(BaseModel):
    folder: str
    path: str
    tags: List[str]


# ============ Routes ============


@router.get("/shared/folders")
async def get_shared_folders():
    """Get list of shared folders with sizes"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    try:
        folders = shared_manager.get_folders()
        return {"success": True, "folders": folders}
    except Exception as e:
        if system_logger:
            system_logger.error("Не удалось получить список общих папок", {"error": str(e)})
        raise HTTPException(500, f"Не удалось получить список общих папок: {str(e)}")


@router.post("/shared/folders")
async def create_shared_folder(request: CreateFolderRequest):
    """Create a new shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.create_folder(request.name)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Создана общая папка", {"name": request.name})

    return result


@router.delete("/shared/folders/{name}")
async def delete_shared_folder(name: str):
    """Delete a shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.delete_folder(name)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Удалена общая папка", {"name": name})

    return result


@router.get("/shared/files")
async def get_shared_files(folder: str = Query(...), subfolder: Optional[str] = Query(None)):
    """Get files in shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.get_files(folder, subfolder)
    if not result["success"]:
        raise HTTPException(404, result["message"])

    return result


@router.post("/shared/upload")
async def upload_shared_file(
    folder: str = Form(...),
    file: UploadFile = File(...),
    subfolder: Optional[str] = Form(None),
    tags: Optional[str] = Form(None),
    description: Optional[str] = Form(""),
):
    """Upload file to shared folder using async streaming"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    try:
        # Build file path
        file_path = file.filename
        if subfolder:
            file_path = f"{subfolder}/{file.filename}"

        # Parse tags
        tag_list = []
        if tags:
            tag_list = [t.strip() for t in tags.split(",") if t.strip()]

        # Use streaming method to avoid loading entire file into RAM
        result = await shared_manager.save_upload_file_streaming(folder, file_path, file, tag_list, description)
        if not result["success"]:
            raise HTTPException(400, result["message"])

        if system_logger:
            system_logger.info("Файл загружен", {"folder": folder, "file": file.filename})

        return result
    except Exception as e:
        if system_logger:
            system_logger.error("Не удалось загрузить файл", {"error": str(e)})
        raise HTTPException(500, f"Не удалось загрузить файл: {str(e)}")


@router.delete("/shared/files")
async def delete_shared_file(folder: str = Query(...), path: str = Query(...)):
    """Delete file from shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.delete_file(folder, path)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Файл удален", {"folder": folder, "path": path})

    return result


@router.get("/shared/history")
async def get_file_history(folder: str = Query(...), path: str = Query(...)):
    """Get Git history for a file"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.get_file_history(folder, path)
    if not result["success"]:
        raise HTTPException(404, result["message"])

    return result


@router.post("/shared/restore")
async def restore_shared_file(request: RestoreFileRequest):
    """Restore file to specific commit"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.restore_file(request.folder, request.path, request.commit_hash)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info(
            "Файл восстановлен", {"folder": request.folder, "path": request.path, "commit": request.commit_hash}
        )

    return result


@router.post("/shared/subfolder")
async def create_shared_subfolder(request: CreateSubfolderRequest):
    """Create subfolder in shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager не инициализирован")

    result = shared_manager.create_subfolder(request.folder, request.path)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Создана подпапка", {"folder": request.folder, "path": request.path})

    return result


@router.get("/shared/search")
async def search_shared_files(folder: str = Query(...), query: str = Query(...)):
    """Search files in shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.search_files(folder, query)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    return result


@router.post("/shared/bulk-delete")
async def bulk_delete_shared_files(request: BulkDeleteRequest):
    """Delete multiple files at once"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.bulk_delete_files(request.folder, request.paths)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Bulk delete completed", {"folder": request.folder, "count": len(request.paths)})

    return result


@router.get("/shared/download")
async def download_shared_file(folder: str = Query(...), path: str = Query(...)):
    """Download file from shared folder"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.get_file_content(folder, path)
    if not result["success"]:
        raise HTTPException(404, result["message"])

    # Return file as download
    return Response(
        content=result["content"],
        media_type="application/octet-stream",
        headers={"Content-Disposition": f'attachment; filename="{result["filename"]}"'},
    )


@router.post("/shared/tags")
async def update_file_tags(request: UpdateTagsRequest):
    """Update file tags"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    result = shared_manager.update_tags(request.folder, request.path, request.tags)
    if not result["success"]:
        raise HTTPException(400, result["message"])

    if system_logger:
        system_logger.info("Tags updated", {"folder": request.folder, "path": request.path})

    return result


@router.get("/shared/metadata")
async def get_file_metadata(folder: str = Query(...), path: str = Query(...)):
    """Get file metadata"""
    if not shared_manager:
        raise HTTPException(500, "Shared manager not initialized")

    try:
        metadata = shared_manager._get_file_metadata(folder, path)
        return {"success": True, "metadata": metadata}
    except Exception as e:
        if system_logger:
            system_logger.error("Failed to get metadata", {"error": str(e)})
        raise HTTPException(500, f"Failed to get metadata: {str(e)}")
