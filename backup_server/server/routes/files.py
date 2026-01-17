"""File upload endpoints."""

from datetime import datetime

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, UploadFile

from ..models.file_info import FileCheckRequest, FileCheckResponse, UploadResponse
from ..services.dedup import DedupService
from ..services.storage import StorageService

router = APIRouter(prefix="/api/files", tags=["files"])


def get_storage_service():
    """Dependency injection for storage service - will be overridden in app.py."""
    raise NotImplementedError("Storage service not configured")


def get_dedup_service():
    """Dependency injection for dedup service - will be overridden in app.py."""
    raise NotImplementedError("Dedup service not configured")


def verify_api_key(x_api_key: str = Header(None, alias="X-API-Key")):
    """Verify the API key - will be overridden in app.py."""
    raise NotImplementedError("API key verification not configured")


@router.post("/check", response_model=FileCheckResponse)
async def check_files(
    request: FileCheckRequest,
    dedup: DedupService = Depends(get_dedup_service),
    _: str = Depends(verify_api_key),
):
    """
    Check which files already exist on the server by their SHA-256 hashes.
    This allows the client to skip uploading files that are already backed up.
    """
    existing_hashes = dedup.get_existing_hashes(request.hashes)

    return FileCheckResponse(
        existing=list(existing_hashes),
        missing=[h for h in request.hashes if h not in existing_hashes],
    )


@router.post("/upload", response_model=UploadResponse)
async def upload_file(
    file: UploadFile = File(...),
    file_hash: str = Form(...),
    original_path: str = Form(None),
    date_taken: int = Form(None),
    mime_type: str = Form(None),
    device_name: str = Form(None),
    source: str = Form(None),
    storage: StorageService = Depends(get_storage_service),
    dedup: DedupService = Depends(get_dedup_service),
    _: str = Depends(verify_api_key),
):
    """
    Upload a single file to the backup server.

    Parameters:
    - file: The file to upload
    - file_hash: SHA-256 hash of the file (for verification and dedup)
    - original_path: Original path on the device (optional, for reference)
    - date_taken: Unix timestamp in milliseconds when photo/video was taken
    - mime_type: MIME type of the file (e.g., image/jpeg, video/mp4)
    - device_name: Name of the source device (optional)
    - source: Backup source (camera, whatsapp, wechat, downloads)
    """
    # Check for duplicate
    if dedup.exists(file_hash):
        return UploadResponse(status="exists", message="File already backed up")

    # Parse date taken
    dt = None
    if date_taken:
        try:
            dt = datetime.fromtimestamp(date_taken / 1000)
        except (ValueError, OSError):
            dt = None

    # Determine MIME type
    actual_mime = mime_type or file.content_type or "application/octet-stream"

    # Get storage path (source determines folder: Photos/Videos for camera, or WhatsApp/WeChat/Downloads)
    relative_path = storage.get_storage_path(
        filename=file.filename or "unknown",
        mime_type=actual_mime,
        date_taken=dt,
        source=source,
    )

    # Read file content
    content = await file.read()

    # Verify hash before saving
    computed_hash = storage.compute_hash_from_bytes(content)
    if computed_hash != file_hash:
        raise HTTPException(
            status_code=400,
            detail=f"Hash mismatch: expected {file_hash}, got {computed_hash}",
        )

    # Save file
    await storage.save_file(content, relative_path)

    # Record in dedup database
    dedup.record(
        file_hash=file_hash,
        storage_path=str(relative_path),
        original_path=original_path,
        original_filename=file.filename,
        file_size=len(content),
        mime_type=actual_mime,
        source_device=device_name,
    )

    return UploadResponse(
        status="success",
        path=str(relative_path),
        hash=file_hash,
    )


@router.get("/stats")
async def get_stats(
    dedup: DedupService = Depends(get_dedup_service),
    storage: StorageService = Depends(get_storage_service),
    _: str = Depends(verify_api_key),
):
    """Get backup statistics."""
    db_stats = dedup.get_stats()
    storage_info = storage.get_storage_info()

    return {
        "total_files": db_stats["total_files"],
        "total_size_bytes": db_stats["total_size"],
        "total_size_human": storage._format_size(db_stats["total_size"] or 0),
        "first_backup": db_stats["first_backup"],
        "last_backup": db_stats["last_backup"],
        "storage_path": storage_info["storage_path"],
    }
