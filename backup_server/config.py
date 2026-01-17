import secrets
from pathlib import Path
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Application settings with environment variable support."""

    # Server settings
    host: str = "0.0.0.0"
    port: int = 8080
    service_name: str = "PhotoBackupServer"

    # Storage settings
    storage_path: Path = Path("./storage")
    db_path: Path = Path("./storage/.backup_db.sqlite")

    # Security
    api_key: str = ""

    class Config:
        env_prefix = "BACKUP_"
        env_file = ".env"

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        # Generate API key if not provided
        if not self.api_key:
            self.api_key = secrets.token_urlsafe(32)
        # Ensure storage directory exists
        self.storage_path.mkdir(parents=True, exist_ok=True)


settings = Settings()
