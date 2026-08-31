#!/usr/bin/env python3
"""
Setup transparent mirror access via hosts + CA.

After running this script:
- Projects can access nexus.kryptodev.ru from home machine
- Requests go to 127.0.0.1 (local vault)
- TLS works via self-signed CA

Usage:
  python setup_hosts_mirror.py [--uninstall]

Prerequisites:
  - mkcert (recommended) OR openssl
  - Admin privileges (for hosts file)
"""

import subprocess
import sys
import os
import shutil
from pathlib import Path

HOSTS_FILE = Path(r"C:\Windows\System32\drivers\etc\hosts")
HOSTNAME = "nexus.kryptodev.ru"
CERT_DIR = Path.home() / ".localgitmirror" / "certs"


def is_admin() -> bool:
    """Check if running with admin privileges."""
    try:
        result = subprocess.run(
            ["net", "session"],
            capture_output=True,
            text=True,
        )
        return result.returncode == 0
    except Exception:
        return False


def add_hosts_entry() -> None:
    """Append 127.0.0.1 nexus.kryptodev.ru to hosts file."""
    entry = f"127.0.0.1 {HOSTNAME}"

    content = HOSTS_FILE.read_text(encoding="utf-8")
    if entry in content:
        print(f"  hosts: entry already exists ({HOSTNAME} -> 127.0.0.1)")
        return

    if not is_admin():
        print("ERROR: Run as Administrator to modify hosts file")
        sys.exit(1)

    with open(HOSTS_FILE, "a", encoding="utf-8") as f:
        f.write(f"\n{entry}\n")
    print(f"  hosts: added {HOSTNAME} -> 127.0.0.1")


def remove_hosts_entry() -> None:
    """Remove the nexus.kryptodev.ru line from hosts file."""
    entry = f"127.0.0.1 {HOSTNAME}"

    content = HOSTS_FILE.read_text(encoding="utf-8")
    if entry not in content:
        print(f"  hosts: entry not found ({HOSTNAME})")
        return

    if not is_admin():
        print("ERROR: Run as Administrator to modify hosts file")
        sys.exit(1)

    lines = content.splitlines(keepends=True)
    new_lines = [
        line for line in lines if line.strip() != entry and HOSTNAME not in line
    ]
    HOSTS_FILE.write_text("".join(new_lines), encoding="utf-8")
    print(f"  hosts: removed {HOSTNAME}")


def has_mkcert() -> bool:
    """Check if mkcert is available in PATH."""
    return shutil.which("mkcert") is not None


def generate_with_mkcert() -> None:
    """Generate CA + cert using mkcert."""
    CERT_DIR.mkdir(parents=True, exist_ok=True)

    print("  mkcert: installing local CA...")
    subprocess.run(["mkcert", "-install"], check=True, cwd=CERT_DIR)

    cert_path = CERT_DIR / "cert.pem"
    key_path = CERT_DIR / "key.pem"

    print(f"  mkcert: generating cert for {HOSTNAME}...")
    subprocess.run(
        [
            "mkcert",
            "-cert-file", str(cert_path),
            "-key-file", str(key_path),
            HOSTNAME,
        ],
        check=True,
        cwd=CERT_DIR,
    )

    # mkcert also generates a root CA at ~/.local/share/mkcert/rootCA.pem
    # Copy it to our cert dir for easy reference
    mkcert_root = Path(os.environ.get("CAROOT", Path.home() / ".local" / "share" / "mkcert")) / "rootCA.pem"
    if mkcert_root.exists():
        ca_path = CERT_DIR / "ca.pem"
        shutil.copy(mkcert_root, ca_path)
        print(f"  mkcert: CA copied to {ca_path}")

    print(f"  mkcert: cert -> {cert_path}")
    print(f"  mkcert: key  -> {key_path}")


def generate_with_openssl() -> None:
    """Fallback: generate CA + server cert using openssl."""
    if shutil.which("openssl") is None:
        print("ERROR: Neither mkcert nor openssl found. Install one of them.")
        sys.exit(1)

    CERT_DIR.mkdir(parents=True, exist_ok=True)

    ca_key = CERT_DIR / "ca-key.pem"
    ca_cert = CERT_DIR / "ca.pem"
    cert_key = CERT_DIR / "key.pem"
    cert_csr = CERT_DIR / "cert.csr"
    cert_pem = CERT_DIR / "cert.pem"
    ext_file = CERT_DIR / "cert.ext"

    # Generate CA key + cert
    print("  openssl: generating CA key...")
    subprocess.run(
        ["openssl", "genrsa", "-out", str(ca_key), "2048"],
        check=True,
        cwd=CERT_DIR,
    )
    subprocess.run(
        [
            "openssl", "req", "-x509", "-new", "-nodes",
            "-key", str(ca_key),
            "-sha256", "-days", "3650",
            "-out", str(ca_cert),
            "-subj", "/CN=LocalGitMirror CA",
        ],
        check=True,
        cwd=CERT_DIR,
    )
    print(f"  openssl: CA cert -> {ca_cert}")

    # Generate server key + CSR
    print("  openssl: generating server key + CSR...")
    subprocess.run(
        ["openssl", "genrsa", "-out", str(cert_key), "2048"],
        check=True,
        cwd=CERT_DIR,
    )
    subprocess.run(
        [
            "openssl", "req", "-new",
            "-key", str(cert_key),
            "-out", str(cert_csr),
            "-subj", f"/CN={HOSTNAME}",
        ],
        check=True,
        cwd=CERT_DIR,
    )

    # SAN extension file
    ext_content = (
        "authorityKeyIdentifier=keyid,issuer\n"
        "basicConstraints=CA:FALSE\n"
        "keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment\n"
        "subjectAltName = @alt_names\n\n"
        "[alt_names]\n"
        f"DNS.1 = {HOSTNAME}\n"
    )
    ext_file.write_text(ext_content)

    # Sign server cert with CA
    print("  openssl: signing server cert...")
    subprocess.run(
        [
            "openssl", "x509", "-req",
            "-in", str(cert_csr),
            "-CA", str(ca_cert),
            "-CAkey", str(ca_key),
            "-CAcreateserial",
            "-out", str(cert_pem),
            "-days", "3650",
            "-sha256",
            "-extfile", str(ext_file),
        ],
        check=True,
        cwd=CERT_DIR,
    )

    # Cleanup CSR
    cert_csr.unlink(missing_ok=True)
    ext_file.unlink(missing_ok=True)

    print(f"  openssl: server cert -> {cert_pem}")
    print(f"  openssl: server key  -> {cert_key}")


def import_to_windows_store() -> None:
    """Import CA into Windows user trust store."""
    ca_path = CERT_DIR / "ca.pem"
    if not ca_path.exists():
        print("  trust: CA cert not found, skipping Windows trust store")
        return

    print("  trust: importing CA to Windows trust store...")
    result = subprocess.run(
        ["certutil", "-addstore", "-user", "Root", str(ca_path)],
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        print("  trust: imported to Windows Root store")
    else:
        print(f"  trust: WARNING - certutil failed: {result.stderr.strip()}")
        print("  trust: Try manually: certutil -addstore -user Root ca.pem")


def import_to_jdk_cacerts() -> None:
    """Import CA into JDK cacerts truststore."""
    ca_path = CERT_DIR / "ca.pem"
    if not ca_path.exists():
        print("  jdk: CA cert not found, skipping")
        return

    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        print("  jdk: JAVA_HOME not set, skipping cacerts import")
        print("  jdk: To add manually: keytool -importcert -cacerts -file ca.pem -alias lgm-ca")
        return

    cacerts = Path(java_home) / "lib" / "security" / "cacerts"
    if not cacerts.exists():
        cacerts = Path(java_home) / "jre" / "lib" / "security" / "cacerts"
    if not cacerts.exists():
        print(f"  jdk: cacerts not found at {cacerts}, skipping")
        return

    print(f"  jdk: importing CA to {cacerts}...")
    result = subprocess.run(
        [
            "keytool", "-importcert",
            "-cacerts",
            "-file", str(ca_path),
            "-alias", "lgm-ca",
            "-storepass", "changeit",
            "-noprompt",
        ],
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        print("  jdk: imported to cacerts")
    else:
        # May already exist
        if "already exists" in result.stderr.lower() or "already exists" in result.stdout.lower():
            print("  jdk: CA already in cacerts")
        else:
            print(f"  jdk: WARNING - {result.stderr.strip()}")


def setup_node_extra_ca() -> None:
    """Set NODE_EXTRA_CA_CERTS user env var pointing to ca.pem."""
    ca_path = CERT_DIR / "ca.pem"
    if not ca_path.exists():
        print("  node: CA cert not found, skipping")
        return

    print(f"  node: setting NODE_EXTRA_CA_CERTS={ca_path}")
    result = subprocess.run(
        ["setx", "NODE_EXTRA_CA_CERTS", str(ca_path)],
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        print("  node: user env var set (restart terminals to apply)")
    else:
        print(f"  node: WARNING - setx failed: {result.stderr.strip()}")
        print(f"  node: Set manually: setx NODE_EXTRA_CA_CERTS {ca_path}")


def install() -> None:
    """Run full setup."""
    print("=" * 50)
    print("LocalGitMirror: Transparent Mirror Setup")
    print("=" * 50)

    if not is_admin():
        print("WARNING: Not running as Administrator.")
        print("  - hosts file modification will be skipped")
        print("  - Run as Administrator for full setup\n")

    # 1. Add hosts entry
    if is_admin():
        add_hosts_entry()
    else:
        print(f"  hosts: SKIP (admin required) — add manually:")
        print(f"        127.0.0.1 {HOSTNAME}")

    # 2. Generate CA + cert
    CERT_DIR.mkdir(parents=True, exist_ok=True)
    if has_mkcert():
        generate_with_mkcert()
    else:
        print("  mkcert not found, falling back to openssl...")
        generate_with_openssl()

    # 3. Import CA into trust stores
    import_to_windows_store()
    import_to_jdk_cacerts()
    setup_node_extra_ca()

    print("\n" + "=" * 50)
    print("Setup complete")
    print("=" * 50)
    print(f"  hosts: {HOSTNAME} -> 127.0.0.1")
    print(f"  cert:  {CERT_DIR / 'cert.pem'}")
    print(f"  key:   {CERT_DIR / 'key.pem'}")
    print(f"  ca:    {CERT_DIR / 'ca.pem'}")
    print()
    print("To use with LGM server, set:")
    print(f"  SSL_CERTFILE={CERT_DIR / 'cert.pem'}")
    print(f"  SSL_KEYFILE={CERT_DIR / 'key.pem'}")
    print()
    print("Restart your terminal for NODE_EXTRA_CA_CERTS to take effect.")


def uninstall() -> None:
    """Remove hosts entry and print cert cleanup instructions."""
    print("=" * 50)
    print("LocalGitMirror: Uninstall hosts mirror")
    print("=" * 50)

    if not is_admin():
        print("ERROR: Run as Administrator to modify hosts file")
        sys.exit(1)

    remove_hosts_entry()

    # Remove NODE_EXTRA_CA_CERTS
    subprocess.run(
        ["setx", "NODE_EXTRA_CA_CERTS", ""],
        capture_output=True,
        text=True,
    )
    print("  node: removed NODE_EXTRA_CA_CERTS env var")

    cacerts_warning = False
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cacerts = Path(java_home) / "lib" / "security" / "cacerts"
        if not cacerts.exists():
            cacerts = Path(java_home) / "jre" / "lib" / "security" / "cacerts"
        if cacerts.exists():
            subprocess.run(
                [
                    "keytool", "-delete",
                    "-cacerts",
                    "-alias", "lgm-ca",
                    "-storepass", "changeit",
                    "-noprompt",
                ],
                capture_output=True,
                text=True,
            )
            cacerts_warning = True

    print("\n" + "=" * 50)
    print("Uninstall complete")
    print("=" * 50)
    print(f"  Certs left at: {CERT_DIR}")
    print("  Delete manually if desired:")
    print(f"    rmdir /s /q {CERT_DIR}")
    print()
    if cacerts_warning:
        print("  JDK cacerts entry removed")
    print("  Windows cert store: remove manually via certmgr.msc")
    print("    Look for 'LocalGitMirror CA' under Trusted Root Certification Authorities -> Certificates")


if __name__ == "__main__":
    if "--uninstall" in sys.argv:
        uninstall()
    else:
        install()