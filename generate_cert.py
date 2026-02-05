import datetime
import socket
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from pathlib import Path


def generate_self_signed_cert(cert_path="cert.pem", key_path="key.pem"):
    # Generate key
    key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048,
    )

    # Get local IP to add to SAN (Subject Alternative Name)
    hostname = socket.gethostname()
    local_ip = socket.gethostbyname(hostname)

    print(f"Generating cert for {hostname} ({local_ip})...")

    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COUNTRY_NAME, "US"),
            x509.NameAttribute(NameOID.STATE_OR_PROVINCE_NAME, "California"),
            x509.NameAttribute(NameOID.LOCALITY_NAME, "San Francisco"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "LocalGitMirror"),
            x509.NameAttribute(NameOID.COMMON_NAME, "LocalGitMirror"),
        ]
    )

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
            x509.SubjectAlternativeName(
                [
                    x509.DNSName("localhost"),
                    x509.IPAddress(enumerate_ip(local_ip)),
                    x509.IPAddress(enumerate_ip("127.0.0.1")),
                    x509.IPAddress(enumerate_ip("0.0.0.0")),
                ]
            ),
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


def enumerate_ip(ip_str):
    import ipaddress

    return ipaddress.ip_address(ip_str)


if __name__ == "__main__":
    generate_self_signed_cert()
