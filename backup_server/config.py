import logging
import secrets
import sys
import uuid
from pathlib import Path

from pydantic_settings import BaseSettings


def get_log_file_path(storage_path: Path) -> Path:
    """Get the log file path."""
    return storage_path / "server.log"


def setup_logging(storage_path: Path) -> logging.Handler:
    """
    Configure logging to write to a file in the storage directory.
    The log file is recreated on each server restart.

    Returns the file handler so it can be used by uvicorn config.
    """
    log_file = get_log_file_path(storage_path)

    # Ensure storage directory exists
    storage_path.mkdir(parents=True, exist_ok=True)

    # Create a file handler that overwrites the log file on each start
    file_handler = logging.FileHandler(log_file, mode='w', encoding='utf-8')
    file_handler.setLevel(logging.INFO)

    # Create console handler for stdout (will show when not hidden)
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setLevel(logging.INFO)

    # Create formatter
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    file_handler.setFormatter(formatter)
    console_handler.setFormatter(formatter)

    # Configure root logger
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.INFO)

    # Remove any existing handlers
    root_logger.handlers.clear()

    # Add our handlers
    root_logger.addHandler(file_handler)
    root_logger.addHandler(console_handler)

    return file_handler


def get_uvicorn_log_config(storage_path: Path) -> dict:
    """
    Get uvicorn logging configuration that writes to our log file.
    """
    log_file = str(get_log_file_path(storage_path))

    return {
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "default": {
                "format": "%(asctime)s - %(name)s - %(levelname)s - %(message)s",
                "datefmt": "%Y-%m-%d %H:%M:%S",
            },
            "access": {
                "format": "%(asctime)s - %(name)s - %(levelname)s - %(message)s",
                "datefmt": "%Y-%m-%d %H:%M:%S",
            },
        },
        "handlers": {
            "default": {
                "formatter": "default",
                "class": "logging.FileHandler",
                "filename": log_file,
                "mode": "a",  # Append since we already created the file
            },
            "access": {
                "formatter": "access",
                "class": "logging.FileHandler",
                "filename": log_file,
                "mode": "a",
            },
            "console": {
                "formatter": "default",
                "class": "logging.StreamHandler",
                "stream": "ext://sys.stdout",
            },
        },
        "loggers": {
            "uvicorn": {
                "handlers": ["default", "console"],
                "level": "INFO",
                "propagate": False,
            },
            "uvicorn.error": {
                "handlers": ["default", "console"],
                "level": "INFO",
                "propagate": False,
            },
            "uvicorn.access": {
                "handlers": ["access", "console"],
                "level": "INFO",
                "propagate": False,
            },
        },
    }


def _load_or_create_api_key(storage_path: Path) -> str:
    """Load API key from file, or create a new one if it doesn't exist."""
    api_key_file = storage_path / ".api_key"

    # Ensure storage directory exists
    storage_path.mkdir(parents=True, exist_ok=True)

    if api_key_file.exists():
        api_key = api_key_file.read_text().strip()
        if api_key:
            return api_key

    # Generate new API key and save it
    api_key = secrets.token_urlsafe(32)
    api_key_file.write_text(api_key)
    return api_key


def _load_or_create_server_id(storage_path: Path) -> str:
    """Load server ID from file, or create a new one if it doesn't exist."""
    server_id_file = storage_path / ".server_id"

    # Ensure storage directory exists
    storage_path.mkdir(parents=True, exist_ok=True)

    if server_id_file.exists():
        server_id = server_id_file.read_text().strip()
        if server_id:
            return server_id

    # Generate new server ID (UUID4) and save it
    server_id = str(uuid.uuid4())
    server_id_file.write_text(server_id)
    return server_id


class Settings(BaseSettings):
    """Application settings with environment variable support."""

    # Server settings
    host: str = "0.0.0.0"
    port: int = 9121
    service_name: str = "PhotoBackupServer"

    # Storage settings
    storage_path: Path = Path("./storage")

    # Security
    api_key: str = ""
    server_id: str = ""

    class Config:
        env_prefix = "BACKUP_"
        env_file = ".env"

    @property
    def db_path(self) -> Path:
        """Database path inside storage directory."""
        return self.storage_path / ".backup_db.sqlite"

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        # Ensure storage directory exists
        self.storage_path.mkdir(parents=True, exist_ok=True)
        # Load or generate API key if not provided via environment
        if not self.api_key:
            self.api_key = _load_or_create_api_key(self.storage_path)
        # Load or generate server ID if not provided via environment
        if not self.server_id:
            self.server_id = _load_or_create_server_id(self.storage_path)


settings = Settings()
