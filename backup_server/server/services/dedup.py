"""Deduplication service using SQLite database."""

import sqlite3
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Generator


class DedupService:
    """Tracks backed-up files to prevent duplicates."""

    def __init__(self, db_path: Path):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        """Initialize the SQLite database."""
        self.db_path.parent.mkdir(parents=True, exist_ok=True)

        with self._get_connection() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS backed_up_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sha256_hash TEXT UNIQUE NOT NULL,
                    storage_path TEXT NOT NULL,
                    original_path TEXT,
                    original_filename TEXT,
                    file_size INTEGER,
                    mime_type TEXT,
                    backed_up_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    source_device TEXT
                )
            """)
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_hash ON backed_up_files(sha256_hash)"
            )

    @contextmanager
    def _get_connection(self) -> Generator[sqlite3.Connection, None, None]:
        """Get a database connection with automatic commit/close."""
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def exists(self, file_hash: str) -> bool:
        """Check if a file with the given hash already exists."""
        with self._get_connection() as conn:
            cursor = conn.execute(
                "SELECT 1 FROM backed_up_files WHERE sha256_hash = ?", (file_hash,)
            )
            return cursor.fetchone() is not None

    def get_existing_hashes(self, hashes: list[str]) -> set[str]:
        """Check multiple hashes at once, return the ones that exist."""
        if not hashes:
            return set()

        with self._get_connection() as conn:
            placeholders = ",".join("?" * len(hashes))
            cursor = conn.execute(
                f"SELECT sha256_hash FROM backed_up_files WHERE sha256_hash IN ({placeholders})",
                hashes,
            )
            return {row["sha256_hash"] for row in cursor.fetchall()}

    def record(
        self,
        file_hash: str,
        storage_path: str,
        original_path: str | None = None,
        original_filename: str | None = None,
        file_size: int | None = None,
        mime_type: str | None = None,
        source_device: str | None = None,
    ) -> bool:
        """Record a backed-up file. Returns True if successful, False if duplicate."""
        with self._get_connection() as conn:
            try:
                conn.execute(
                    """
                    INSERT INTO backed_up_files
                    (sha256_hash, storage_path, original_path, original_filename,
                     file_size, mime_type, source_device)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        file_hash,
                        storage_path,
                        original_path,
                        original_filename,
                        file_size,
                        mime_type,
                        source_device,
                    ),
                )
                return True
            except sqlite3.IntegrityError:
                # Duplicate hash
                return False

    def get_all_hashes(self) -> set[str]:
        """Get all stored file hashes."""
        with self._get_connection() as conn:
            cursor = conn.execute("SELECT sha256_hash FROM backed_up_files")
            return {row["sha256_hash"] for row in cursor.fetchall()}

    def get_stats(self) -> dict:
        """Get backup statistics."""
        with self._get_connection() as conn:
            cursor = conn.execute(
                """
                SELECT
                    COUNT(*) as total_files,
                    SUM(file_size) as total_size,
                    MIN(backed_up_at) as first_backup,
                    MAX(backed_up_at) as last_backup
                FROM backed_up_files
                """
            )
            row = cursor.fetchone()
            return {
                "total_files": row["total_files"] or 0,
                "total_size": row["total_size"] or 0,
                "first_backup": row["first_backup"],
                "last_backup": row["last_backup"],
            }
