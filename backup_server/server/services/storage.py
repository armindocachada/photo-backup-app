"""File storage service with date-based organization."""

import hashlib
import re
from datetime import datetime
from pathlib import Path

import aiofiles


# Valid backup sources
VALID_SOURCES = {"camera", "whatsapp", "wechat", "downloads"}

# Source to folder mapping
SOURCE_FOLDERS = {
    "camera": None,  # Uses mime type to determine Photos/Videos
    "whatsapp": "WhatsApp",
    "wechat": "WeChat",
    "downloads": "Downloads",
}


class StorageService:
    """Handles file storage with organization by date and media type."""

    CHUNK_SIZE = 1024 * 1024  # 1MB chunks for hashing

    def __init__(self, base_path: Path):
        self.base_path = base_path
        self.base_path.mkdir(parents=True, exist_ok=True)

    def _sanitize_filename(self, filename: str) -> str:
        """Remove dangerous characters from filename."""
        # Keep only safe characters
        safe_name = re.sub(r"[^\w\-_\. ]", "_", filename)
        # Remove leading/trailing spaces and dots
        safe_name = safe_name.strip(". ")
        return safe_name or "unnamed"

    def _get_media_folder(self, mime_type: str, source: str | None = None) -> str:
        """Determine folder based on source and MIME type."""
        # If source is specified and has a dedicated folder, use it
        if source and source in SOURCE_FOLDERS:
            folder = SOURCE_FOLDERS[source]
            if folder is not None:
                return folder

        # Default behavior: determine by MIME type (for camera source or fallback)
        if mime_type.startswith("image/"):
            return "Photos"
        elif mime_type.startswith("video/"):
            return "Videos"
        else:
            return "Other"

    def get_storage_path(
        self,
        filename: str,
        mime_type: str,
        date_taken: datetime | None = None,
        source: str | None = None,
    ) -> Path:
        """
        Generate the storage path for a file.

        Organizes files by: {MediaType}/{Year}/{Month}/{filename}
        Where MediaType can be Photos, Videos, WhatsApp, WeChat, or Downloads
        based on the source parameter.
        """
        # Use current date if not provided
        if date_taken is None:
            date_taken = datetime.now()

        media_folder = self._get_media_folder(mime_type, source)
        safe_filename = self._sanitize_filename(filename)

        relative_path = (
            Path(media_folder)
            / str(date_taken.year)
            / f"{date_taken.month:02d}"
            / safe_filename
        )

        # Handle filename collisions
        full_path = self.base_path / relative_path
        if full_path.exists():
            stem = full_path.stem
            suffix = full_path.suffix
            counter = 1
            while full_path.exists():
                new_name = f"{stem}_{counter}{suffix}"
                relative_path = relative_path.parent / new_name
                full_path = self.base_path / relative_path
                counter += 1

        return relative_path

    async def save_file(self, content: bytes, relative_path: Path) -> Path:
        """Save file content to the storage location."""
        dest_path = self.base_path / relative_path
        dest_path.parent.mkdir(parents=True, exist_ok=True)

        async with aiofiles.open(dest_path, "wb") as f:
            await f.write(content)

        return dest_path

    async def save_file_streaming(
        self, file_iterator, relative_path: Path
    ) -> tuple[Path, int]:
        """
        Save file from an async iterator (for large files).
        Returns the destination path and total bytes written.
        """
        dest_path = self.base_path / relative_path
        dest_path.parent.mkdir(parents=True, exist_ok=True)

        total_bytes = 0
        async with aiofiles.open(dest_path, "wb") as f:
            async for chunk in file_iterator:
                await f.write(chunk)
                total_bytes += len(chunk)

        return dest_path, total_bytes

    async def compute_hash(self, file_path: Path) -> str:
        """Compute SHA-256 hash of a file."""
        sha256 = hashlib.sha256()

        async with aiofiles.open(file_path, "rb") as f:
            while True:
                chunk = await f.read(self.CHUNK_SIZE)
                if not chunk:
                    break
                sha256.update(chunk)

        return sha256.hexdigest()

    def compute_hash_from_bytes(self, content: bytes) -> str:
        """Compute SHA-256 hash from bytes."""
        return hashlib.sha256(content).hexdigest()

    async def delete_file(self, relative_path: Path) -> bool:
        """Delete a file from storage. Returns True if successful."""
        full_path = self.base_path / relative_path
        if full_path.exists():
            full_path.unlink()
            return True
        return False

    def get_storage_info(self) -> dict:
        """Get storage statistics."""
        total_size = 0
        file_count = 0

        for file_path in self.base_path.rglob("*"):
            if file_path.is_file() and not file_path.name.startswith("."):
                total_size += file_path.stat().st_size
                file_count += 1

        return {
            "storage_path": str(self.base_path.absolute()),
            "total_files": file_count,
            "total_size_bytes": total_size,
            "total_size_human": self._format_size(total_size),
        }

    @staticmethod
    def _format_size(size_bytes: int) -> str:
        """Format bytes to human readable string."""
        for unit in ["B", "KB", "MB", "GB", "TB"]:
            if size_bytes < 1024:
                return f"{size_bytes:.2f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.2f} PB"
