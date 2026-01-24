"""FastAPI application factory."""

import logging
import secrets

from fastapi import FastAPI, Header, HTTPException

from config import Settings

from .routes import files, health
from .services.dedup import DedupService
from .services.storage import StorageService

logger = logging.getLogger(__name__)


def create_app(settings: Settings) -> FastAPI:
    """Create and configure the FastAPI application."""

    app = FastAPI(
        title="Photo Backup Server",
        description="Local server for backing up photos and videos from Android devices",
        version="1.0.0",
    )

    # Initialize services
    storage_service = StorageService(settings.storage_path)
    dedup_service = DedupService(settings.db_path)

    # Set server_id for health check endpoint
    health.set_server_id(settings.server_id)

    # Dependency overrides
    def get_storage():
        return storage_service

    def get_dedup():
        return dedup_service

    def verify_api_key(x_api_key: str = Header(None, alias="X-API-Key")):
        if not x_api_key:
            logger.warning("API key missing in request")
            raise HTTPException(status_code=401, detail="API key required")
        if not secrets.compare_digest(x_api_key, settings.api_key):
            logger.warning("Invalid API key provided")
            raise HTTPException(status_code=401, detail="Invalid API key")
        logger.info("API key is valid")
        return x_api_key

    # Override dependencies in routers
    app.dependency_overrides[health.get_storage_service] = get_storage
    app.dependency_overrides[files.get_storage_service] = get_storage
    app.dependency_overrides[files.get_dedup_service] = get_dedup
    app.dependency_overrides[files.verify_api_key] = verify_api_key

    # Include routers
    app.include_router(health.router)
    app.include_router(files.router)

    @app.get("/")
    async def root():
        return {
            "name": "Photo Backup Server",
            "version": "1.0.0",
            "status": "running",
        }

    return app
