import secrets
from pathlib import Path
from pydantic_settings import BaseSettings


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


class Settings(BaseSettings):
    """Application settings with environment variable support."""

    # Server settings
    host: str = "0.0.0.0"
    port: int = 8080
    service_name: str = "PhotoBackupServer"

    # Storage settings
    storage_path: Path = Path("./storage")

    # Security
    api_key: str = ""

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


settings = Settings()
