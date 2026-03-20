import datetime
import socket
import sys
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from pathlib import Path
import ipaddress


def generate_self_signed_cert(cert_path="cert.pem", key_path="key.pem", custom_ip=None):
    """
    Generate self-signed SSL certificate with SAN support
    
    Args:
        cert_path: Path to save certificate
        key_path: Path to save private key
        custom_ip: Optional custom IP address to include in SAN
    """
    # Generate key
    key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
    )

    # Get local IP
    hostname = socket.gethostname()
    try:
        local_ip = socket.gethostbyname(hostname)
    except:
        local_ip = "127.0.0.1"

    print(f"Generating cert for {hostname} ({local_ip})...")
    if custom_ip:
        print(f"Including custom IP: {custom_ip}")

    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COUNTRY_NAME, "US"),
            x509.NameAttribute(NameOID.STATE_OR_PROVINCE_NAME, "California"),
            x509.NameAttribute(NameOID.LOCALITY_NAME, "San Francisco"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "LocalGitMirror"),
            x509.NameAttribute(NameOID.COMMON_NAME, "storage.local"),
        ]
    )

    # Build SAN list
    san_list = [
        x509.DNSName("localhost"),
        x509.DNSName("storage.local"),
        x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
        x509.IPAddress(ipaddress.ip_address("0.0.0.0")),
    ]
    
    # Add local IP
    try:
        san_list.append(x509.IPAddress(ipaddress.ip_address(local_ip)))
    except:
        pass
    
    # Add custom IP if provided
    if custom_ip:
        try:
            san_list.append(x509.IPAddress(ipaddress.ip_address(custom_ip)))
        except ValueError:
            print(f"⚠️  Invalid IP address: {custom_ip}")

    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime.utcnow())
        .not_valid_after(
            # Valid for 10 years
            datetime.datetime.utcnow() + datetime.timedelta(days=3650)
        )
        .add_extension(
            x509.SubjectAlternativeName(san_list),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )

    # Write key
    with open(key_path, "wb") as f:
        f.write(
            key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.TraditionalOpenSSL,
                encryption_algorithm=serialization.NoEncryption(),
            )
        )

    # Write cert
    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))

    print(f"✅ Generated {cert_path} and {key_path}")
    print(f"📋 SAN entries: {len(san_list)}")
    for entry in san_list:
        print(f"   - {entry}")


if __name__ == "__main__":
    custom_ip = None
    if len(sys.argv) > 1:
        custom_ip = sys.argv[1]
        print(f"Using custom IP from command line: {custom_ip}")
    
    generate_self_signed_cert(custom_ip=custom_ip)
