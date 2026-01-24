"""Health check endpoints."""

import socket

from fastapi import APIRouter, Depends

from ..models.file_info import HealthResponse, StatusResponse
from ..services.storage import StorageService

router = APIRouter(tags=["health"])

# Will be set by app.py
_server_id: str = ""


def set_server_id(server_id: str):
    """Set the server ID for health check responses."""
    global _server_id
    _server_id = server_id


def get_storage_service():
    """Dependency injection for storage service - will be overridden in app.py."""
    raise NotImplementedError("Storage service not configured")


@router.get("/api/health", response_model=HealthResponse)
async def health_check():
    """Check if the server is running."""
    return HealthResponse(
        status="ok",
        version="1.0.0",
        server_name=socket.gethostname(),
        server_id=_server_id,
    )


@router.get("/api/status", response_model=StatusResponse)
async def get_status(storage: StorageService = Depends(get_storage_service)):
    """Get server status and storage information."""
    storage_info = storage.get_storage_info()

    return StatusResponse(
        status="ok",
        storage_path=storage_info["storage_path"],
        total_files=storage_info["total_files"],
        total_size_bytes=storage_info["total_size_bytes"],
        total_size_human=storage_info["total_size_human"],
        api_version="1.0.0",
    )
