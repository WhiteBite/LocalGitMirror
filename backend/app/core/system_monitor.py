import psutil
import socket
from typing import Dict, List


class SystemMonitor:
    @staticmethod
    def get_metrics() -> Dict:
        """Get current system metrics"""
        import os

        cpu = psutil.cpu_percent(interval=0.1)
        memory = psutil.virtual_memory()
        disk = psutil.disk_usage(os.getenv("SystemDrive", "C:\\"))

        return {
            "cpu_percent": round(cpu, 1),
            "memory_percent": round(memory.percent, 1),
            "memory_used_gb": round(memory.used / (1024**3), 2),
            "memory_total_gb": round(memory.total / (1024**3), 2),
            "disk_percent": round(disk.percent, 1),
            "disk_used_gb": round(disk.used / (1024**3), 2),
            "disk_total_gb": round(disk.total / (1024**3), 2),
        }

    @staticmethod
    def get_local_ip() -> str:
        """Get real LAN IP address, ignoring Docker/WSL virtual adapters"""
        try:
            # Method 1: Check all network interfaces
            for iface_name, iface_addrs in psutil.net_if_addrs().items():
                # Skip virtual adapters (Docker, WSL, VirtualBox, etc.)
                skip_keywords = [
                    "docker",
                    "veth",
                    "br-",
                    "wsl",
                    "virtualbox",
                    "vmware",
                    "hyper-v",
                    "loopback",
                ]
                if any(kw in iface_name.lower() for kw in skip_keywords):
                    continue

                for addr in iface_addrs:
                    if addr.family == socket.AF_INET:
                        ip = addr.address
                        # Prefer 192.168.x.x or 10.x.x.x (real LAN)
                        if ip.startswith("192.168.") or ip.startswith("10."):
                            return ip

            # Method 2: Fallback - try all 192.168 and 10.x addresses
            for iface_name, iface_addrs in psutil.net_if_addrs().items():
                for addr in iface_addrs:
                    if addr.family == socket.AF_INET:
                        ip = addr.address
                        if ip.startswith("192.168.") or ip.startswith("10."):
                            return ip

            # Method 3: Socket trick (may return virtual IP)
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip

        except Exception:
            return "127.0.0.1"

    @staticmethod
    def get_all_ips() -> List[Dict]:
        """Get all network interfaces with IPs for debugging"""
        result = []
        for iface_name, iface_addrs in psutil.net_if_addrs().items():
            for addr in iface_addrs:
                if addr.family == socket.AF_INET and not addr.address.startswith(
                    "127."
                ):
                    result.append({"interface": iface_name, "ip": addr.address})
        return result
