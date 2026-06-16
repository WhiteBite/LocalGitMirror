import os
import requests
import socket
import urllib3
import sys
import argparse
from dotenv import load_dotenv

# Disable warnings for self-signed certs
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
load_dotenv()


def check_server(target_ip="127.0.0.1"):
    print(f"\n=== Server Diagnostic [Target: {target_ip}] ===")

    target_port = int(os.getenv("WEB_PORT", 8443))
    api_key = os.getenv("API_KEY", "")
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}

    # 1. Check port
    print(f"1. Checking TCP connection to {target_ip}:{target_port}...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(3)
    result = sock.connect_ex((target_ip, target_port))
    if result == 0:
        print(f"   [OK] Port {target_port} is OPEN and LISTENING.")
    else:
        print(f"   [FAIL] Port {target_port} is CLOSED or FIREWALLED.")
        return
    sock.close()

    # 2. Check API
    base_url = f"https://{target_ip}:{target_port}"
    print(f"2. Checking API access ({base_url}/api/status)...")
    try:
        resp = requests.get(
            f"{base_url}/api/status", headers=headers, verify=False, timeout=5
        )
        if resp.status_code == 200:
            data = resp.json()
            print(f"   [OK] API responding correctly.")
            print(f"   [INFO] Storage Path: {data.get('storage_path')}")
            print(f"   [INFO] Current Repo: {data.get('current_repo')}")
        else:
            print(f"   [FAIL] API returned status {resp.status_code}. (Check API Key)")
    except Exception as e:
        print(f"   [FAIL] API request failed: {e}")

    # 3. Check Git Endpoint
    print(f"3. Checking Git HTTP access...")
    try:
        git_url = f"{base_url}/git/onyx/info/refs?service=git-upload-pack"
        print(f"   Testing: {git_url}")
        resp = requests.get(git_url, verify=False, timeout=5)
        if resp.status_code == 200:
            print(f"   [OK] Git repository 'onyx' FOUND and ACCESSIBLE.")
        elif resp.status_code == 404:
            print(
                f"   [FAIL] Git repository 'onyx' NOT FOUND (404). (Check app.mount order)"
            )
        else:
            print(f"   [FAIL] Git endpoint returned status {resp.status_code}")
    except Exception as e:
        print(f"   [FAIL] Git request failed: {e}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ip", default="127.0.0.1", help="Target IP address")
    args = parser.parse_args()

    check_server(args.ip)
