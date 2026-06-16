import socket
from typing import Dict, List, Optional

import psutil


class SystemMonitor:
    @staticmethod
    def get_metrics(path: Optional[str] = None) -> Dict:
        """Get current system metrics"""
        import os

        cpu = psutil.cpu_percent(interval=0.1)
        memory = psutil.virtual_memory()

        # Safe disk usage check
        target_path = os.getenv("SystemDrive", "C:\\")

        if path:
            try:
                # Try to get drive from path
                if os.path.exists(path):
                    target_path = os.path.splitdrive(os.path.abspath(path))[0]
                    if not target_path:  # If path is relative or no drive
                        target_path = os.path.abspath(path)
                else:
                    # If path doesn't exist, try its parent or fallback
                    target_path = os.getenv("SystemDrive", "C:\\")
            except Exception:
                target_path = os.getenv("SystemDrive", "C:\\")

        # Ensure target path exists and is a directory/drive for psutil
        if not os.path.exists(target_path):
            target_path = os.getenv("SystemDrive", "C:\\")

        # Must add trailing slash for root drive on Windows (e.g. "C:") fails, "C:\\" works
        if len(target_path) == 2 and target_path[1] == ":":
            target_path += "\\"

        try:
            disk = psutil.disk_usage(target_path)
        except Exception:
            # Absolute fallback if everything fails
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

            # Method 3: Fallback - any non-loopback address
            for iface_name, iface_addrs in psutil.net_if_addrs().items():
                for addr in iface_addrs:
                    if addr.family == socket.AF_INET and not addr.address.startswith("127."):
                        return addr.address

            return "127.0.0.1"

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
