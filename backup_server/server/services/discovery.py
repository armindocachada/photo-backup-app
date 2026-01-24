"""mDNS service discovery using Zeroconf."""

import socket
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf


class ServiceDiscovery:
    """Registers and manages mDNS service for automatic discovery."""

    SERVICE_TYPE = "_photobackup._tcp.local."

    def __init__(self, service_name: str, port: int, server_id: str):
        self.service_name = service_name
        self.port = port
        self.server_id = server_id
        self.async_zeroconf: AsyncZeroconf | None = None
        self.service_info: ServiceInfo | None = None

    def _get_local_ip(self) -> str:
        """Get the local IP address of this machine."""
        try:
            # Create a socket to determine the local IP
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
            return local_ip
        except Exception:
            # Fallback to hostname resolution
            return socket.gethostbyname(socket.gethostname())

    async def register(self) -> str:
        """Register the mDNS service. Returns the local IP address."""
        self.async_zeroconf = AsyncZeroconf()

        local_ip = self._get_local_ip()
        hostname = socket.gethostname()

        self.service_info = ServiceInfo(
            self.SERVICE_TYPE,
            f"{self.service_name}.{self.SERVICE_TYPE}",
            addresses=[socket.inet_aton(local_ip)],
            port=self.port,
            properties={
                "version": "1.0",
                "serverName": hostname,
                "serverId": self.server_id,
            },
            server=f"{hostname}.local.",
        )

        await self.async_zeroconf.async_register_service(self.service_info)
        print(f"mDNS service registered: {self.service_name}")
        print(f"  Service type: {self.SERVICE_TYPE}")
        print(f"  Address: {local_ip}:{self.port}")

        return local_ip

    async def unregister(self):
        """Unregister the mDNS service."""
        if self.async_zeroconf and self.service_info:
            print("Unregistering mDNS service...")
            await self.async_zeroconf.async_unregister_service(self.service_info)
            await self.async_zeroconf.async_close()
            self.async_zeroconf = None
            self.service_info = None
