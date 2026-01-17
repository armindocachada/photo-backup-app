"""Pydantic models for API requests and responses."""

from pydantic import BaseModel


class FileCheckRequest(BaseModel):
    """Request to check which files already exist."""

    hashes: list[str]


class FileCheckResponse(BaseModel):
    """Response with existing and missing file hashes."""

    existing: list[str]
    missing: list[str]


class UploadResponse(BaseModel):
    """Response after successful file upload."""

    status: str
    path: str | None = None
    hash: str | None = None
    message: str | None = None


class HealthResponse(BaseModel):
    """Health check response."""

    status: str
    version: str
    server_name: str


class StatusResponse(BaseModel):
    """Server status response."""

    status: str
    storage_path: str
    total_files: int
    total_size_bytes: int
    total_size_human: str
    api_version: str
