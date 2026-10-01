from __future__ import annotations

import hashlib
import io
import json
import zipfile
from pathlib import Path

from .config import cfg
from .client import LgmError
from .crypto import encrypt_bundle
from .op_models import Ctx, _client
from .deps_scanner import gradle_candidate_roots


# ── Protected-groups scanners (for publish) ─────────────────────────────────

def scan_maven_local_protected(repo_root: Path, protected_groups: tuple) -> list[dict]:
    """Scan ~/.m2/repository for protected artifacts (full Maven identity)."""
    if not repo_root.is_dir():
        return []
    arts = []
    for g_path in repo_root.iterdir():
        if not g_path.is_dir():
            continue
        group = g_path.name.replace("/", ".")
        if not any(group == g or group.startswith(g + ".") for g in protected_groups):
            continue
        for a_path in g_path.iterdir():
            if not a_path.is_dir():
                continue
            artifact = a_path.name
            for v_path in a_path.iterdir():
                if not v_path.is_dir():
                    continue
                version = v_path.name
                for f in v_path.iterdir():
                    if not f.is_file():
                        continue
                    name = f.name
                    if name.startswith("maven-metadata") or name.endswith((".sha1", ".md5", ".sha256", ".sha512")):
                        continue
                    prefix = f"{artifact}-{version}"
                    if not name.startswith(prefix):
                        continue
                    remainder = name[len(prefix):]
                    if not remainder:
                        continue
                    if remainder.startswith("."):
                        classifier = ""
                        extension = remainder[1:]
                    elif remainder.startswith("-"):
                        rest = remainder[1:]
                        dot_pos = rest.rfind(".")
                        if dot_pos == -1:
                            continue
                        classifier = rest[:dot_pos]
                        extension = rest[dot_pos + 1:]
                    else:
                        continue
                    if not extension:
                        continue
                    arts.append({
                        "group": group, "artifact": artifact, "version": version,
                        "classifier": classifier, "extension": extension,
                        "path": str(f), "size": f.stat().st_size,
                    })
    return arts


def scan_gradle_cache_protected(cache_root: Path, protected_groups: tuple) -> list[dict]:
    """Scan Gradle files-2.1 for protected artifacts."""
    if not cache_root.is_dir():
        return []
    arts = []
    for g_dir in cache_root.iterdir():
        if not g_dir.is_dir():
            continue
        group = g_dir.name
        if not any(group == g or group.startswith(g + ".") for g in protected_groups):
            continue
        for n_dir in g_dir.iterdir():
            if not n_dir.is_dir():
                continue
            artifact = n_dir.name
            for v_dir in n_dir.iterdir():
                if not v_dir.is_dir():
                    continue
                version = v_dir.name
                for sha_dir in v_dir.iterdir():
                    if not sha_dir.is_dir():
                        continue
                    for f in sha_dir.iterdir():
                        if not f.is_file() or f.name.startswith("_") or f.name == ".lock":
                            continue
                        name = f.name
                        prefix = f"{artifact}-{version}"
                        if not name.startswith(prefix):
                            continue
                        remainder = name[len(prefix):]
                        if not remainder:
                            continue
                        if remainder.startswith("."):
                            classifier = ""
                            extension = remainder[1:]
                        elif remainder.startswith("-"):
                            rest = remainder[1:]
                            dot_pos = rest.rfind(".")
                            if dot_pos == -1:
                                continue
                            classifier = rest[:dot_pos]
                            extension = rest[dot_pos + 1:]
                        else:
                            continue
                        if not extension:
                            continue
                        arts.append({
                            "group": group, "artifact": artifact, "version": version,
                            "classifier": classifier, "extension": extension,
                            "path": str(f), "size": f.stat().st_size,
                        })
    return arts


def build_publication_zip(artifacts: list[dict]) -> tuple[bytes, dict]:
    """Build ZIP publication from artifact list. Returns (zip_bytes, manifest)."""
    buf = io.BytesIO()
    manifest = {"schema": 1, "maven": []}
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for art in artifacts:
            path = Path(art["path"])
            data = path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            group_path = art["group"].replace(".", "/")
            filename = f"{art['artifact']}-{art['version']}"
            if art["classifier"]:
                filename += f"-{art['classifier']}"
            filename += f".{art['extension']}"
            maven_path = f"{group_path}/{art['artifact']}/{art['version']}/{filename}"
            zip_entry = f"maven/{maven_path}"
            zf.writestr(zip_entry, data)
            manifest["maven"].append({
                "path": maven_path, "sha256": digest,
                "group": art["group"], "artifact": art["artifact"],
                "version": art["version"], "classifier": art["classifier"],
                "extension": art["extension"],
            })
        zf.writestr("manifest.json", json.dumps(manifest, indent=2))
    return buf.getvalue(), manifest


def op_publish(ctx: Ctx, args: dict) -> dict:
    """Scan local caches for protected artifacts, encrypt, and publish to Mirror vault."""
    c = _client(ctx)
    dry_run = args.get("dry_run", False)
    if not ctx.config.sync_password:
        raise LgmError("config", "SYNC_PASSWORD not set")

    protected_raw = cfg("LGM_PROTECTED_MAVEN_GROUPS", "ru.kryptonite")
    protected_groups = tuple(g.strip() for g in protected_raw.split(",") if g.strip())

    m2_root = Path.home() / ".m2" / "repository"
    m2_arts = scan_maven_local_protected(m2_root, protected_groups)
    gradle_arts = []
    for root in gradle_candidate_roots():
        if root.is_dir():
            gradle_arts.extend(scan_gradle_cache_protected(root, protected_groups))

    seen = {}
    all_arts = []
    for art in m2_arts + gradle_arts:
        path = Path(art["path"])
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        key_tuple = (art["group"], art["artifact"], art["version"],
                     art["classifier"], art["extension"], digest)
        if key_tuple not in seen:
            seen[key_tuple] = True
            all_arts.append(art)

    if not all_arts:
        return {"message": "nothing to publish", "protected_groups": list(protected_groups)}

    if dry_run:
        return {"dry_run": True, "count": len(all_arts),
                "sample": all_arts[:20], "protected_groups": list(protected_groups)}

    zip_bytes, manifest = build_publication_zip(all_arts)
    encrypted = encrypt_bundle(zip_bytes, ctx.config.sync_password)
    res = c.vault_publish("lgm-cli", encrypted)
    return {"published": len(all_arts), "zip_size": len(zip_bytes),
            "encrypted_size": len(encrypted), "response": res,
            "protected_groups": list(protected_groups)}
