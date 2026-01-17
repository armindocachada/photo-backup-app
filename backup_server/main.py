#!/usr/bin/env python3
"""
Photo Backup Server - Main entry point.

A local server that receives photo and video backups from Android devices
over WiFi. The server is discoverable via mDNS, so the Android app can
find it automatically on the local network.

Usage:
    python main.py

Environment variables:
    BACKUP_PORT         Server port (default: 8080)
    BACKUP_STORAGE_PATH Storage directory (default: ./storage)
    BACKUP_API_KEY      API key for authentication (auto-generated if not set)
"""

import asyncio
import sys

import uvicorn

from config import settings
from server.app import create_app
from server.services.discovery import ServiceDiscovery


def print_banner(local_ip: str):
    """Print server startup information."""
    print()
    print("=" * 60)
    print("  PHOTO BACKUP SERVER")
    print("=" * 60)
    print()
    print(f"  Server running at: http://{local_ip}:{settings.port}")
    print(f"  Storage path:      {settings.storage_path.absolute()}")
    print()
    print("  API Key (configure in Android app):")
    print(f"  {settings.api_key}")
    print()
    print("  mDNS Service: _photobackup._tcp.local.")
    print()
    print("=" * 60)
    print("  Press Ctrl+C to stop the server")
    print("=" * 60)
    print()


async def main():
    """Run the backup server."""
    # Create FastAPI app
    app = create_app(settings)

    # Start mDNS service discovery
    discovery = ServiceDiscovery(
        service_name=settings.service_name,
        port=settings.port,
    )

    local_ip = await discovery.register()
    print_banner(local_ip)

    # Configure uvicorn
    config = uvicorn.Config(
        app,
        host=settings.host,
        port=settings.port,
        log_level="info",
        access_log=True,
    )
    server = uvicorn.Server(config)

    try:
        await server.serve()
    finally:
        await discovery.unregister()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped.")
        sys.exit(0)
