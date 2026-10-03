"""Bootstrap, tools distribution and plugin-repository endpoints.

Serves four things the work PC needs on day one (and on every update):
  /api/tools/latest    — zip of lgm.py + lgm_mcp.py + lgm_core/, optionally sealed
  /api/bootstrap       — PowerShell one-liner script (stealth-risky; see /work-pc-bundle)
  /api/work-pc-bundle  — single zip with plugin + tools + README, no credentials inside
  /api/plugin/repo.xml — IntelliJ custom plugin repository descriptor
"""

from __future__ import annotations

import hashlib
import io
import os
import subprocess
import zipfile
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import PlainTextResponse, Response

from app.core.bundle_crypto import encrypt_bundle_bytes
from app.core.envelope_crypto import encrypt_envelope

router = APIRouter(prefix="/api", tags=["bootstrap"])


def _repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent.parent


def _tools_files() -> list[tuple[str, Path]]:
    root = _repo_root()
    files: list[tuple[str, Path]] = []
    for name in ("lgm.py", "lgm_mcp.py"):
        p = root / name
        if p.is_file():
            files.append((name, p))
    core = root / "lgm_core"
    if core.is_dir():
        for p in sorted(core.rglob("*.py")):
            files.append((str(p.relative_to(root)).replace("\\", "/"), p))
    return files


def _tools_zip() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for arcname, path in _tools_files():
            zf.write(path, arcname)
    return buf.getvalue()


def _tools_version() -> str:
    try:
        count = subprocess.run(
            ["git", "rev-list", "--count", "HEAD"],
            cwd=str(_repo_root()), capture_output=True, text=True, timeout=10,
        ).stdout.strip()
        return f"0.{count}.0" if count.isdigit() else "0.0.0"
    except Exception:
        return "0.0.0"


def _sync_password() -> str:
    password = os.getenv("SYNC_PASSWORD", "")
    if not password:
        raise HTTPException(status_code=503, detail="SYNC_PASSWORD not configured")
    return password


_SHA256_CACHE: dict[str, tuple[float, str]] = {}


def _sha256(path: Path) -> str:
    mtime = path.stat().st_mtime
    cached = _SHA256_CACHE.get(str(path))
    if cached and cached[0] == mtime:
        return cached[1]
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    digest = h.hexdigest()
    _SHA256_CACHE[str(path)] = (mtime, digest)
    return digest


def _plugin_archive() -> Path:
    from app.routers.plugin import _current_zip
    return _current_zip()


@router.get("/tools/latest")
def tools_latest(enc: bool = Query(False)):
    """Zip lgm.py + lgm_mcp.py + lgm_core/ and serve, optionally sealed."""
    blob = _tools_zip()
    if enc:
        sealed = encrypt_bundle_bytes(blob, _sync_password())
        return Response(
            content=sealed,
            media_type="application/octet-stream",
            headers={"X-LGM-Version": _tools_version()},
        )
    return Response(
        content=blob,
        media_type="application/zip",
        headers={"X-LGM-Version": _tools_version()},
    )


@router.get("/plugin/repo.xml")
def plugin_repo_xml():
    """IntelliJ custom plugin-repository descriptor (points at /plugin/latest)."""
    archive = _plugin_archive()
    from app.routers.plugin import _parse_version
    version = _parse_version(archive.name) or "0.0.0"
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<plugins>\n"
        f'  <plugin id="localgitmirror.settings" '
        f'url="{{BASE_URL}}/api/plugin/latest" version="{version}">\n'
        f'    <idea-version since-build="241" until-build="263.*"/>\n'
        "    <name>DocCache</name>\n"
        "  </plugin>\n"
        "</plugins>\n"
    )
    return PlainTextResponse(content=xml, media_type="application/xml")


_BOOTSTRAP_TEMPLATE = r'''# DocCache bootstrap — generated {version}
param(
    [string]$InstallDir = "D:\Tools\lgm",
    [string]$OpenCodeConfig = "$env:USERPROFILE\.config\opencode\opencode.json",
    [switch]$NoPlugin,
    [switch]$NoOpenCode
)
$ErrorActionPreference = 'Stop'
$base = "{base_url}"
$key = "{api_key}"
$enc = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($key))
$hdr = @{{ Authorization = "Bearer $key" }}

Write-Host "==> Downloading tools..."
$toolsRaw = Invoke-RestMethod -Uri "$base/api/tools/latest?enc=1" -Headers $hdr -SkipCertificateCheck
$toolsBytes = [byte[]]$toolsRaw
$syncPw = Read-Host "Sync password" -AsSecureString
$pwPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($syncPw))

# v2 dump format: version(1) salt(16) nonce(12) ctlen(8) ciphertext
$salt = $toolsBytes[1..16]
$nonce = $toolsBytes[17..28]
$ctLen = [BitConverter]::ToInt64([byte[]]@($toolsBytes[29..36]) + @(0,0,0,0,0,0,0,0), 0)
$ct = $toolsBytes[37..(37 + $ctLen - 1)]
$kdf = [Security.Cryptography.Rfc2898DeriveBytes]::new($pwPlain, $salt, 200000, [Security.Cryptography.HashAlgorithmName]::SHA256)
$aesKey = $kdf.GetBytes(32)
$aes = [Security.Cryptography.AesGcm]::new($aesKey)
$zipBytes = $aes.Decrypt($nonce, $ct, $null)

Write-Host "==> Extracting to $InstallDir..."
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
$ms = [IO.MemoryStream]::new($zipBytes)
$zip = [IO.Compression.ZipArchive]::new($ms)
foreach ($e in $zip.Entries) {{
    $target = Join-Path $InstallDir $e.FullName
    $dir = Split-Path $target -Parent
    if (!(Test-Path $dir)) {{ New-Item -ItemType Directory -Path $dir -Force | Out-Null }}
    [IO.File]::WriteAllBytes($target, $e.Open().ReadAllBytes())
}}
$zip.Dispose()

Write-Host "==> Writing .env..."
$envContent = @"
BASE_URL=$base
API_KEY=$key
SYNC_PASSWORD=$pwPlain
"@
Set-Content -Path (Join-Path $InstallDir ".env") -Value $envContent -NoNewline

if (-not $NoOpenCode) {{
    Write-Host "==> Configuring OpenCode MCP..."
    $configDir = Split-Path $OpenCodeConfig -Parent
    if (!(Test-Path $configDir)) {{ New-Item -ItemType Directory -Path $configDir -Force | Out-Null }}

    $mcpEntry = @{{
        command = "python"
        args = @((Join-Path $InstallDir "lgm_mcp.py"))
        env = @{{
            BASE_URL = $base
            API_KEY = $key
        }}
    }}

    $envPath = Join-Path $InstallDir ".env"
    $envContent | Out-File $envPath -Encoding utf8 -NoNewline
    Write-Host "    MCP entry point: $(Join-Path $InstallDir 'lgm_mcp.py')"
    Write-Host "    Add to opencode.json manually if needed:"
    Write-Host "    $env:USERPROFILE\.config\opencode\opencode.json"
    Write-Host "    See server config for details."
}}

if (-not $NoPlugin) {{
    Write-Host "==> Downloading IDEA plugin..."
    $pluginRaw = Invoke-RestMethod -Uri "$base/api/plugin/latest?enc=1" -Headers $hdr -SkipCertificateCheck
    $pluginBytes = [byte[]]$pluginRaw
    $pluginFile = Join-Path $InstallDir "doccache-plugin.zip"
    [IO.File]::WriteAllBytes($pluginFile, $pluginBytes)
    Write-Host "    Plugin zip: $pluginFile"
    Write-Host "    Install: IDEA -> Settings -> Plugins -> gear -> Install from Disk"
}}

Write-Host ""
Write-Host "Done. Tools in $InstallDir"
Write-Host "Test: python $InstallDir\lgm.py status"
Write-Host "Plugin zip: $InstallDir\doccache-plugin.zip (encrypted — decrypt with sync password)"
'''


@router.get("/bootstrap")
def bootstrap_script(request: Request):
    """PowerShell one-liner bootstrap: download tools, set up .env, get plugin."""
    base_url = str(request.base_url).rstrip("/")
    api_key = os.getenv("API_KEY", "")
    if not api_key:
        raise HTTPException(status_code=503, detail="API_KEY not configured")

    script = _BOOTSTRAP_TEMPLATE.format(
        version=_tools_version(),
        base_url=base_url,
        api_key=api_key,
    )
    return PlainTextResponse(content=script, media_type="text/plain")


_README_PLACEHOLDER = """DocCache v{version} — Work PC Setup
=====================================

Server: __SERVER_URL__

1) IDEA Plugin
   Open IDEA → Settings → Plugins → gear icon → Install Plugin from Disk
   Select: plugin/{plugin_filename}
   In plugin settings (Tools → DocCache): enter Server URL, API Key, Sync Password
   (get them from the server console banner or ask the home PC operator)

2) CLI / MCP tools
   Extract tools/ to any folder (e.g. D:\\Tools\\lgm)
   Create .env in that folder:
     BASE_URL=__SERVER_URL__
     API_KEY=<enter from server banner>
     SYNC_PASSWORD=<enter from server banner>
   Test: python lgm.py status

3) OpenCode MCP (optional)
   Add to ~/.config/opencode/opencode.json:
     "mcp": {{"doccache-tools": {{"command": "python", "args": ["D:/Tools/lgm/lgm_mcp.py"],
       "env": {{"BASE_URL": "__SERVER_URL__", "API_KEY": "<key>"}}}}}}

4) IDEA plugin auto-update (optional)
   Settings → Plugins → gear → Manage Plugin Repositories → Add:
     __SERVER_URL__/api/plugin/repo.xml
   IDEA will check for updates automatically

Updates: python lgm.py update (or re-download this bundle)
Credentials are NOT included in this file for security.
"""


_BUNDLE_DIR_NAME = "work-pc-bundle"
_BUNDLE_LOCK = __import__("threading").Lock()


def _bundle_dir() -> Path:
    return _repo_root() / "idea-plugin" / "build" / _BUNDLE_DIR_NAME


def _bundle_cache_path() -> Optional[Path]:
    d = _bundle_dir()
    if not d.is_dir():
        return None
    candidates = sorted(d.glob("doccache-setup-v*.zip"))
    return candidates[-1] if candidates else None


def _bundle_cache_version(path: Path) -> Optional[str]:
    m = __import__("re").fullmatch(r"doccache-setup-v(\d+)\.zip", path.name)
    return m.group(1) if m else None


def _generate_bundle_zip(dest_dir: Path) -> Path:
    """Build the work-pc bundle zip (plugin + tools + README placeholder)."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    plugin = _plugin_archive()
    from app.routers.plugin import _parse_version
    version = _parse_version(plugin.name) or "0.0.0"

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(plugin, f"plugin/{plugin.name}")
        for arcname, path in _tools_files():
            zf.write(path, f"tools/{arcname}")
        zf.writestr("README.txt", _README_PLACEHOLDER.format(
            version=_tools_version(),
            plugin_filename=plugin.name,
        ))

    major = version.split(".")[1] if "." in version else "0"
    dest = dest_dir / f"doccache-setup-v{major}.zip"
    dest.write_bytes(buf.getvalue())
    for old in dest_dir.glob("doccache-setup-v*.zip"):
        if old != dest:
            old.unlink(missing_ok=True)
    return dest


def ensure_work_pc_bundle() -> Path:
    """Return the current bundle path, rebuilding if stale (same pattern as plugin)."""
    cache = _bundle_cache_path()
    expected = _tools_version().split(".")[1] if "." in _tools_version() else "0"
    if cache is not None and _bundle_cache_version(cache) == expected:
        return cache

    with _BUNDLE_LOCK:
        cache = _bundle_cache_path()
        if cache is not None and _bundle_cache_version(cache) == expected:
            return cache
        return _generate_bundle_zip(_bundle_dir())


@router.get("/work-pc-bundle")
def work_pc_bundle(request: Request):
    """Serve the pre-built work-pc bundle zip, injecting the server URL into README."""
    bundle_path = ensure_work_pc_bundle()
    base_url = str(request.base_url).rstrip("/")
    from app.routers.plugin import _parse_version
    plugin = _plugin_archive()
    version = _parse_version(plugin.name) or "0.0.0"

    buf = io.BytesIO()
    with zipfile.ZipFile(bundle_path, "r") as src, \
         zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == "README.txt":
                data = data.replace(b"__SERVER_URL__", base_url.encode("utf-8"))
            dst.writestr(item, data)

    return Response(
        content=buf.getvalue(),
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="doccache-setup-v{version}.zip"',
            "X-LGM-Version": _tools_version(),
        },
    )
