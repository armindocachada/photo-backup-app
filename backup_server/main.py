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
import logging
import sys

import uvicorn

from config import settings, setup_logging, get_uvicorn_log_config
from server.app import create_app
from server.services.discovery import ServiceDiscovery
from server.services.pairing import generate_pairing_qr

# Setup logging before anything else
setup_logging(settings.storage_path)
logger = logging.getLogger(__name__)

# Get uvicorn log config
UVICORN_LOG_CONFIG = get_uvicorn_log_config(settings.storage_path)


def print_banner(local_ip: str):
    """Print server startup information."""
    # Generate QR code for pairing (only server_id and api_key, IP is discovered via mDNS)
    qr_ascii = generate_pairing_qr(
        server_id=settings.server_id,
        api_key=settings.api_key,
        storage_path=settings.storage_path,
    )

    banner = f"""
{'=' * 60}
  PHOTO BACKUP SERVER
{'=' * 60}

  Server running at: http://{local_ip}:{settings.port}
  Storage path:      {settings.storage_path.absolute()}
  Log file:          {settings.storage_path.absolute() / 'server.log'}

  mDNS Service: _photobackup._tcp.local.
  Server ID:    {settings.server_id}

{'=' * 60}
  SCAN THIS QR CODE WITH THE APP TO PAIR:
{'=' * 60}

{qr_ascii}
  QR code also saved to: {settings.storage_path.absolute() / 'pairing_qr.png'}

{'=' * 60}
  Press Ctrl+C to stop the server
{'=' * 60}
"""
    print(banner)
    logger.info(f"Server started at http://{local_ip}:{settings.port}")
    logger.info(f"Storage path: {settings.storage_path.absolute()}")
    logger.info(f"Server ID: {settings.server_id}")
    logger.info("mDNS service registered: _photobackup._tcp.local.")
    logger.info(f"Pairing QR code saved to: {settings.storage_path.absolute() / 'pairing_qr.png'}")


async def main():
    """Run the backup server."""
    # Create FastAPI app
    app = create_app(settings)

    # Start mDNS service discovery
    discovery = ServiceDiscovery(
        service_name=settings.service_name,
        port=settings.port,
        server_id=settings.server_id,
    )

    local_ip = await discovery.register()
    print_banner(local_ip)

    # Configure uvicorn with our logging config
    config = uvicorn.Config(
        app,
        host=settings.host,
        port=settings.port,
        log_level="info",
        access_log=True,
        log_config=UVICORN_LOG_CONFIG,
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
        logger.info("Server stopped by user")
        print("\nServer stopped.")
        sys.exit(0)
