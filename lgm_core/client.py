"""MirrorClient — all network access for lgm, via urllib (stdlib only).

Auth: sends **both** ``Authorization: Bearer <key>`` and the legacy
``X-Session-ID: <key>`` header so the same client works against servers
that accept either (the backend's ``get_api_key`` checks both).

TLS: connections are pinned via TOFU (see ``tls_pin``) — the mirror runs
with a self-signed cert.  ``insecure_tls=True`` is the escape hatch: an
unverified SSL context, no pinning.

Errors: ``LgmError`` carries a structured ``code`` (HTTP status int or
``"network"``) and ``message``.  Callers can inspect ``code`` to decide
whether to retry.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import ssl
import subprocess
import urllib.parse
import urllib.request
import urllib.error
import uuid
from pathlib import Path
from typing import Any, Optional

from .crypto import encrypt_envelope, decrypt_envelope, encrypt_bundle
from .relay_crypto import RelaySession, RELAY_AAD_POSTBOX, decode_pub_b64
from .config import cfg
from .tls_pin import TlsPinMismatchError, pin_https_opener


def repo_to_rid(repo_name: str) -> str:
    """First 16 hex chars of SHA-256("lgm-repo-id:" + name); the server resolves it via _rid.py."""
    return hashlib.sha256(("lgm-repo-id:" + repo_name).encode("utf-8")).hexdigest()[:16]


class LgmError(Exception):
    """Structured error: ``code`` + ``message`` + optional ``machine_code``.

    ``code`` is an int HTTP status from the mirror, or a kind string:
    "config" (bad input/env), "network" (unreachable), "git" / "gitlab"
    (local tool or GitLab API failure), "server" (mirror answered but
    refused).  ``machine_code`` is a stable machine-readable reason
    ("already-held", "diverged", "closed-mr", "wrong-machine",
    "role-unset", "notes-unavailable") or None; lgm.py copies it into the
    JSON error envelope.  lgm.py maps kinds to exit codes: config=3,
    network=4, every other kind=5; 1 is reserved for non-LgmError crashes
    and 2 for argparse usage errors.
    """

    def __init__(self, code, message: str, machine_code: str | None = None):
        self.code = code
        self.message = message
        self.machine_code = machine_code
        super().__init__(f"[{code}] {message}")


class ResponseTooLargeError(LgmError):
    """A capped download exceeded its ``max_bytes`` limit."""


_DOWNLOAD_CHUNK = 1 << 20
# sealed-response framing slack; the exact cap applies to the opened plaintext
_RELAY_SEAL_SLACK = 4096


def _read_capped(r, max_bytes: int) -> bytes:
    buf = bytearray()
    while True:
        chunk = r.read(min(_DOWNLOAD_CHUNK, max_bytes + 1 - len(buf)))
        if not chunk:
            return bytes(buf)
        buf += chunk
        if len(buf) > max_bytes:
            raise ResponseTooLargeError(
                "server", f"download exceeds the {max_bytes} byte cap")


_ssl_ctx_cache: dict[bool, ssl.SSLContext] = {}


def _ssl_ctx(insecure: bool = True) -> ssl.SSLContext:
    """Default SSL context, built once per ``insecure`` value and reused."""
    ctx = _ssl_ctx_cache.get(insecure)
    if ctx is None:
        ctx = ssl.create_default_context()
        if insecure:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        _ssl_ctx_cache[insecure] = ctx
    return ctx


_user_agent_value: Optional[str] = None


def _user_agent() -> str:
    global _user_agent_value
    if _user_agent_value is not None:
        return _user_agent_value
    version = os.environ.get("DOCACHE_VERSION")
    if not version:
        try:
            proc = subprocess.run(
                ["git", "rev-list", "--count", "HEAD"],
                cwd=str(Path(__file__).resolve().parent.parent),
                capture_output=True, text=True, timeout=5,
            )
            if proc.returncode == 0 and proc.stdout.strip().isdigit():
                version = f"0.{proc.stdout.strip()}"
        except Exception:
            pass
    if not version:
        version = "0.0.0"
    _user_agent_value = f"DocCache/{version}"
    return _user_agent_value


def _auth_headers(api_key: str) -> dict:
    """Common request headers: User-Agent plus Bearer and legacy X-Session-ID."""
    h = {"User-Agent": _user_agent()}
    if api_key:
        h["Authorization"] = f"Bearer {api_key}"
        h["X-Session-ID"] = api_key
    return h


_GITLAB_REDIRECTS = {301, 302, 307, 308}
_GITLAB_MAX_REDIRECT_HOPS = 3


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_GITLAB_OPENER = urllib.request.build_opener(_NoRedirectHandler())


class MirrorClient:
    """Thin HTTP client covering the full Mirror API surface."""

    def __init__(self, base_url: str = "", api_key: str = "",
                 insecure_tls: bool = False, timeout: int = 15,
                 sync_password: str = "", pin_sha256: str = "",
                 pin_file: str = ""):
        base_url = base_url.strip()
        if base_url and not base_url.startswith(("http://", "https://")):
            base_url = "https://" + base_url
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.insecure_tls = insecure_tls
        self.timeout = timeout
        self.sync_password = sync_password
        self.pin_sha256 = pin_sha256
        self.pin_file = pin_file
        self._server_pub: Optional[bytes] = None
        self._opener: Optional[urllib.request.OpenerDirector] = None

    # ── low-level helpers ────────────────────────────────────────────────

    def _url(self, path: str) -> str:
        return self.base_url + path

    def _open(self, req: urllib.request.Request, timeout: int):
        """Single funnel for mirror requests: insecure escape hatch or pinned."""
        if self.insecure_tls:
            return urllib.request.urlopen(
                req, context=_ssl_ctx(True), timeout=timeout)
        if self._opener is None:
            self._opener = pin_https_opener(
                pin_sha256=self.pin_sha256, pin_file=self.pin_file)
        return self._opener.open(req, timeout=timeout)

    def _get_json(self, path: str, timeout: Optional[int] = None) -> dict:
        url = self._url(path)
        req = urllib.request.Request(url, headers=_auth_headers(self.api_key))
        try:
            with self._open(req, timeout=timeout or self.timeout) as r:
                return json.loads(r.read())
        except TlsPinMismatchError:
            raise
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            raise LgmError(e.code, body) from None
        except Exception as e:
            raise LgmError("network", str(e)) from None

    def _delete_json(self, path: str) -> dict:
        url = self._url(path)
        req = urllib.request.Request(url, method="DELETE",
                                     headers=_auth_headers(self.api_key))
        try:
            with self._open(req, timeout=self.timeout) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except TlsPinMismatchError:
            raise
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            raise LgmError(e.code, body) from None
        except Exception as e:
            raise LgmError("network", str(e)) from None

    def _post_json(self, path: str, body: bytes,
                   content_type: str = "application/octet-stream",
                   timeout: Optional[int] = None) -> dict:
        url = self._url(path)
        headers = {**_auth_headers(self.api_key), "Content-Type": content_type}
        req = urllib.request.Request(url, data=body, method="POST", headers=headers)
        try:
            with self._open(req, timeout=timeout or self.timeout) as r:
                return json.loads(r.read())
        except TlsPinMismatchError:
            raise
        except urllib.error.HTTPError as e:
            body_txt = e.read().decode(errors="replace")
            raise LgmError(e.code, body_txt) from None
        except Exception as e:
            raise LgmError("network", str(e)) from None

    def _post_multipart(self, path: str, fields: dict, files: dict,
                        timeout: Optional[int] = None) -> dict:
        """POST multipart/form-data.

        fields: {name: str_value}
        files:  {name: (filename, bytes)}
        """
        boundary = uuid.uuid4().hex
        body_parts = []
        for name, value in fields.items():
            body_parts.append(
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
                f"{value}\r\n"
            )
        for name, (filename, data) in files.items():
            header = (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                f"Content-Type: application/octet-stream\r\n\r\n"
            )
            body_parts.append(header.encode() + data + b"\r\n")
        body_parts.append(f"--{boundary}--\r\n")
        body = b"".join(
            p.encode() if isinstance(p, str) else p
            for p in body_parts
        )
        url = self._url(path)
        headers = {
            **_auth_headers(self.api_key),
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        }
        req = urllib.request.Request(url, data=body, method="POST", headers=headers)
        try:
            with self._open(req, timeout=timeout or 120) as r:
                return json.loads(r.read())
        except TlsPinMismatchError:
            raise
        except urllib.error.HTTPError as e:
            body_txt = e.read().decode(errors="replace")
            raise LgmError(e.code, body_txt) from None
        except Exception as e:
            raise LgmError("network", str(e)) from None

    def _download(self, path: str, out: Path) -> bool:
        url = self._url(path)
        req = urllib.request.Request(url, headers=_auth_headers(self.api_key))
        try:
            with self._open(req, timeout=120) as r:
                out.write_bytes(r.read())
            return True
        except TlsPinMismatchError:
            raise
        except Exception:
            return False

    def _download_bytes(self, path: str, headers: Optional[dict] = None,
                        max_bytes: Optional[int] = None) -> bytes:
        url = self._url(path)
        req = urllib.request.Request(
            url, headers={**_auth_headers(self.api_key), **(headers or {})})
        try:
            with self._open(req, timeout=120) as r:
                if max_bytes is not None:
                    return _read_capped(r, max_bytes)
                return r.read()
        except TlsPinMismatchError:
            raise
        except ResponseTooLargeError:
            raise
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            raise LgmError(e.code, body) from None
        except Exception as e:
            raise LgmError("network", str(e)) from None

    # ── capabilities / repos ─────────────────────────────────────────────

    def capabilities(self) -> dict:
        """GET /api/health — server capabilities."""
        return self._get_json("/api/health")

    def repos(self) -> dict:
        """GET /api/repos - list repos + current."""
        return self._get_json("/api/repos")

    def tools_version(self) -> dict:
        """GET /api/tools/version - mirror-side tools version probe."""
        return self._get_json("/api/tools/version")

    def plugin_info(self) -> dict:
        """GET /api/plugin/info — current plugin archive metadata."""
        return self._get_json("/api/plugin/info")

    # ── sync (git bundle) — /api/documents/* ────────────────────────────
    # These endpoints use envelope-encrypted metadata (the ``e`` field).

    def sync_refs(self, repo: str) -> dict:
        """POST /api/documents/list — all branch tips on the mirror."""
        e = encrypt_envelope({"repo": repo}, self.sync_password)
        resp = self._post_json("/api/documents/list",
                               json.dumps({"e": e}).encode(),
                               content_type="application/json")
        if "e" in resp:
            return decrypt_envelope(resp["e"], self.sync_password)
        return resp

    def sync_has_commits(self, repo: str, commits: list[str]) -> dict:
        """POST /api/documents/check — which commits the mirror already has."""
        e = encrypt_envelope({"repo": repo, "commits": commits},
                             self.sync_password)
        resp = self._post_json("/api/documents/check",
                               json.dumps({"e": e}).encode(),
                               content_type="application/json")
        if "e" in resp:
            return decrypt_envelope(resp["e"], self.sync_password)
        return resp

    def sync_negotiate(self, repo: str, commits: list[str],
                       probe: bool = False) -> dict:
        """POST /api/documents/negotiate — create repo if missing, return
        {refs, head, known}: which of ``commits`` the mirror already has.
        ``probe=True`` skips repo creation (dry-run safe)."""
        payload: dict = {"repo": repo, "commits": commits}
        if probe:
            payload["probe"] = True
        e = encrypt_envelope(payload, self.sync_password)
        resp = self._post_json("/api/documents/negotiate",
                               json.dumps({"e": e}).encode(),
                               content_type="application/json")
        if "e" in resp:
            return decrypt_envelope(resp["e"], self.sync_password)
        return resp

    def sync_apply_known(self, repo: str, commit: str,
                         branches: Optional[dict] = None,
                         local_branches: Optional[list] = None) -> dict:
        """POST /api/documents/link — apply a known commit + branch tips."""
        payload: dict = {"repo": repo, "commit": commit}
        if branches:
            payload["branches"] = branches
        if local_branches:
            payload["local_branches"] = local_branches
        e = encrypt_envelope(payload, self.sync_password)
        resp = self._post_json("/api/documents/link",
                               json.dumps({"e": e}).encode(),
                               content_type="application/json")
        if "e" in resp:
            return decrypt_envelope(resp["e"], self.sync_password)
        return resp

    def sync_send(self, repo: str, bundle_bytes: bytes,
                  local_branches: Optional[list] = None) -> dict:
        """POST /api/documents/upload — upload an encrypted git bundle.

        The bundle is encrypted with ``encrypt_bundle`` (bundle crypto) and
        the metadata (repo, local_branches) is sealed in an envelope.
        """
        encrypted = encrypt_bundle(bundle_bytes, self.sync_password)
        params: dict = {"repo": repo}
        if local_branches:
            params["local_branches"] = local_branches
        e = encrypt_envelope(params, self.sync_password)
        resp = self._post_multipart(
            "/api/documents/upload",
            fields={"e": e},
            files={"attachment": ("cache.bin", encrypted)},
        )
        if "e" in resp:
            return decrypt_envelope(resp["e"], self.sync_password)
        return resp

    def sync_pull(self, repo: str, branch: str = "",
                  since: str = "", haves: str = "") -> dict:
        """POST /api/documents/export — download an encrypted git bundle.

        Returns ``{status, head, repo, dump}`` where ``dump`` is the raw
        encrypted bundle bytes (caller decrypts with ``decrypt_bundle``).
        """
        params: dict = {"repo": repo}
        if branch:
            params["branch"] = branch
        if since:
            params["since"] = since
        if haves:
            params["haves"] = haves
        e = encrypt_envelope(params, self.sync_password)
        resp = self._post_multipart(
            "/api/documents/export",
            fields={"e": e},
            files={},
        )
        # Response: {e: envelope, d: base64 dump}
        result: dict = {}
        if "e" in resp:
            result = decrypt_envelope(resp["e"], self.sync_password)
        if "d" in resp:
            result["dump"] = base64.b64decode(resp["d"])
        return result

    def sync_preview_pull(self, repo: str, since: str = "") -> dict:
        """POST /api/documents/preview — lightweight incoming-commits check."""
        params: dict = {"repo": repo}
        if since:
            params["since"] = since
        e = encrypt_envelope(params, self.sync_password)
        resp = self._post_json("/api/documents/preview",
                               json.dumps({"e": e}).encode(),
                               content_type="application/json")
        if "e" in resp:
            return decrypt_envelope(resp["e"], self.sync_password)
        return resp

    def sync_delete_ref(self, repo: str, branch: str) -> dict:
        """POST /api/documents/delete-ref — delete a branch from mirror."""
        e = encrypt_envelope({"repo": repo, "branch": branch},
                             self.sync_password)
        resp = self._post_json("/api/documents/delete-ref",
                               json.dumps({"e": e}).encode(),
                               content_type="application/json")
        if "e" in resp:
            return decrypt_envelope(resp["e"], self.sync_password)
        return resp

    def delete_ref(self, repo: str, branch: str) -> dict:
        """POST /api/documents/delete-ref — delete a branch from mirror.

        Primary name for ``sync_delete_ref`` (kept for backward compat).
        """
        return self.sync_delete_ref(repo, branch)

    def prune_branches(self, repo: str, bases: Optional[list] = None,
                       older_days: int = 0, keep: Optional[list] = None,
                       apply: bool = False) -> dict:
        """POST /api/documents/prune-branches — list/delete prunable branches.

        ``apply=False`` (default) is a dry run: the server reports candidate
        branches without deleting anything. ``apply=True`` deletes them.
        Returns the decrypted envelope payload:
        ``{success, repo, apply, candidates, pruned, protected, message}``.
        """
        payload: dict = {
            "repo": repo,
            "older_days": int(older_days or 0),
            "apply": bool(apply),
        }
        if bases:
            payload["bases"] = list(bases)
        if keep:
            payload["keep"] = list(keep)
        e = encrypt_envelope(payload, self.sync_password)
        resp = self._post_json("/api/documents/prune-branches",
                               json.dumps({"e": e}).encode(),
                               content_type="application/json")
        if "e" in resp:
            return decrypt_envelope(resp["e"], self.sync_password)
        return resp

    # ── GitLab (corporate MR source) — /api/v4 via urllib ───────────────
    # Config: GITLAB_URL (e.g. https://gitlab.corp.example.com), GITLAB_TOKEN
    # (personal access token, sent as PRIVATE-TOKEN), GITLAB_PROJECT (path or
    # numeric id, URL-encoded into the API path).

    def _gitlab_config(self, project_override: str = "") -> tuple[str, str, str]:
        """Resolve (url, token, project); ``project_override`` wins over GITLAB_PROJECT
        so registry-driven multi-project sync needs no global default."""
        url = cfg("GITLAB_URL").rstrip("/")
        token = cfg("GITLAB_TOKEN")
        project = project_override or cfg("GITLAB_PROJECT")
        missing = [k for k, v in (("GITLAB_URL", url), ("GITLAB_TOKEN", token),
                                  ("GITLAB_PROJECT", project)) if not v]
        if missing:
            raise LgmError(
                "config",
                "GitLab not configured: set " + ", ".join(missing)
                + " (in .env next to lgm.py or the process environment)",
            )
        return url, token, project

    def _gitlab_get(self, url: str, token: str) -> object:
        """GET an absolute GitLab API URL with PRIVATE-TOKEN; LgmError on failure."""
        req = urllib.request.Request(
            url, headers={"PRIVATE-TOKEN": token, "User-Agent": _user_agent()}
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")[:300]
            raise LgmError(e.code, f"GitLab API error {e.code}: {body}") from None
        except Exception as e:
            raise LgmError("network", f"GitLab unreachable: {e}") from None

    def _gitlab_get_paged(self, url: str, token: str) -> tuple[Any, str]:
        """GET like ``_gitlab_get``, also returning the X-Next-Page header ("" when absent)."""
        req = urllib.request.Request(
            url, headers={"PRIVATE-TOKEN": token, "User-Agent": _user_agent()}
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return json.loads(r.read()), r.headers.get("X-Next-Page", "")
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")[:300]
            raise LgmError(e.code, f"GitLab API error {e.code}: {body}") from None
        except Exception as e:
            raise LgmError("network", f"GitLab unreachable: {e}") from None

    def _gitlab_write(self, url: str, token: str, method: str, payload: dict) -> dict:
        """POST/PUT JSON to GitLab. Redirects are followed with method and body
        preserved: urllib would silently turn a redirected POST into a GET."""
        data = json.dumps(payload).encode("utf-8")
        current = url
        for _ in range(_GITLAB_MAX_REDIRECT_HOPS + 1):
            req = urllib.request.Request(
                current, data=data, method=method,
                headers={"PRIVATE-TOKEN": token, "User-Agent": _user_agent(),
                         "Content-Type": "application/json"},
            )
            try:
                with _GITLAB_OPENER.open(req, timeout=self.timeout) as r:
                    body = r.read()
                    return json.loads(body) if body else {}
            except urllib.error.HTTPError as e:
                location = e.headers.get("Location", "") if e.code in _GITLAB_REDIRECTS else ""
                if location:
                    nxt = urllib.parse.urljoin(current, location)
                    parts, cur = urllib.parse.urlparse(nxt), urllib.parse.urlparse(current)
                    if (parts.hostname or "").lower() == (cur.hostname or "").lower() \
                            or parts.scheme == "https":
                        current = nxt
                        continue
                text = e.read().decode(errors="replace")[:300]
                raise LgmError(e.code, f"GitLab API error {e.code}: {text}") from None
            except Exception as e:
                raise LgmError("network", f"GitLab unreachable: {e}") from None
        raise LgmError("network",
                       f"GitLab unreachable: too many redirects (more than {_GITLAB_MAX_REDIRECT_HOPS})")

    def gitlab_list_mrs(self, project: str = "") -> list:
        """GET /api/v4/projects/<id>/merge_requests?state=opened — open MRs.

        ``project`` overrides GITLAB_PROJECT for registry-driven multi-project
        sync; follows X-Next-Page, capped at 10 pages of 50.
        """
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        base = (f"{url}/api/v4/projects/{proj_id}/merge_requests"
                "?state=opened&order_by=updated_at&sort=desc&per_page=50")
        out: list = []
        page = 1
        while True:
            data, next_page = self._gitlab_get_paged(f"{base}&page={page}", token)
            if not isinstance(data, list):
                raise LgmError("network", f"GitLab returned non-list for merge_requests: {type(data).__name__}")
            out.extend(data)
            if not next_page:
                break
            if not next_page.isdigit() or int(next_page) <= page:
                raise LgmError("network", "GitLab returned invalid pagination")
            page = int(next_page)
        return out

    def gitlab_get_mr(self, iid: int, project: str = "") -> dict:
        """GET /api/v4/projects/<id>/merge_requests/<iid> — one MR."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        try:
            iid_int = int(iid)
        except (TypeError, ValueError):
            raise LgmError("config", f"MR iid must be an integer, got: {iid!r}") from None
        data = self._gitlab_get(
            f"{url}/api/v4/projects/{proj_id}/merge_requests/{iid_int}", token
        )
        if not isinstance(data, dict):
            raise LgmError("network", f"GitLab returned non-dict for merge_request: {type(data).__name__}")
        return data

    def gitlab_base_url(self, project: str = "") -> str:
        """Normalized GitLab base URL (no trailing slash) for identity payloads."""
        url, _token, _project = self._gitlab_config(project)
        return url

    def gitlab_branch_tip(self, branch: str, project: str = "") -> str:
        """GET /api/v4/projects/<id>/repository/branches/<branch> — the branch's
        current tip commit SHA (target-tip resolution for review snapshots)."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        data = self._gitlab_get(
            f"{url}/api/v4/projects/{proj_id}/repository/branches/"
            f"{urllib.parse.quote(str(branch), safe='')}", token)
        if not isinstance(data, dict):
            raise LgmError("network", f"GitLab returned non-dict for branch: {type(data).__name__}")
        tip = (data.get("commit") or {}).get("id") or ""
        if not tip:
            raise LgmError("network", f"GitLab branch {branch!r} response has no commit id")
        return tip

    def gitlab_list_discussions(self, iid: int, project: str = "") -> list:
        """GET .../merge_requests/<iid>/discussions — every thread of the MR
        (open and resolved, system notes included), paginated until an empty
        page, capped at 10 pages of 100."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        try:
            iid_int = int(iid)
        except (TypeError, ValueError):
            raise LgmError("config", f"MR iid must be an integer, got: {iid!r}") from None
        out: list = []
        page = 1
        while True:
            data, next_page = self._gitlab_get_paged(
                f"{url}/api/v4/projects/{proj_id}/merge_requests/{iid_int}"
                f"/discussions?per_page=100&page={page}", token
            )
            if not isinstance(data, list):
                raise LgmError("network", f"GitLab returned non-list for discussions: {type(data).__name__}")
            out.extend(data)
            if not next_page:
                break
            if not next_page.isdigit() or int(next_page) <= page:
                raise LgmError("network", "GitLab returned invalid pagination")
            page = int(next_page)
        return out

    def gitlab_compare(self, from_ref: str, to_ref: str, project: str = "") -> dict:
        """GET .../repository/compare?from=<from>&to=<to> — the raw compare
        payload; callers parse ``diffs[*].diff`` hunk ranges for anchor checks."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        return self._gitlab_get(
            f"{url}/api/v4/projects/{proj_id}/repository/compare"
            f"?from={urllib.parse.quote(from_ref, safe='')}"
            f"&to={urllib.parse.quote(to_ref, safe='')}",
            token,
        )

    def gitlab_post_thread_note(self, iid: int, discussion_id: str, body: str,
                                project: str = "") -> dict:
        """POST .../merge_requests/<iid>/discussions/<id>/notes — reply into a thread."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        return self._gitlab_write(
            f"{url}/api/v4/projects/{proj_id}/merge_requests/{int(iid)}"
            f"/discussions/{urllib.parse.quote(discussion_id, safe='')}/notes",
            token, "POST", {"body": body},
        )

    def gitlab_post_mr_note(self, iid: int, body: str, project: str = "") -> dict:
        """POST .../merge_requests/<iid>/notes — general (non-anchored) MR note."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        return self._gitlab_write(
            f"{url}/api/v4/projects/{proj_id}/merge_requests/{int(iid)}/notes",
            token, "POST", {"body": body},
        )

    def gitlab_post_new_discussion(self, iid: int, body: str,
                                   diff_refs: Optional[dict] = None,
                                   position: Optional[tuple] = None,
                                   project: str = "") -> dict:
        """POST .../merge_requests/<iid>/discussions — new thread. Anchored to
        the MR diff only when both ``diff_refs`` ({base_sha, start_sha, head_sha})
        and ``position`` (new_path, new_line) are given; GitLab answers 400/422
        when the line is outside the diff, so callers fall back to a general note."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        payload: dict = {"body": body}
        if diff_refs and position:
            payload["position"] = {
                "base_sha": diff_refs.get("base_sha", ""),
                "start_sha": diff_refs.get("start_sha", ""),
                "head_sha": diff_refs.get("head_sha", ""),
                "position_type": "text",
                "new_path": position[0],
                "new_line": position[1],
            }
        return self._gitlab_write(
            f"{url}/api/v4/projects/{proj_id}/merge_requests/{int(iid)}/discussions",
            token, "POST", payload,
        )

    def gitlab_post_new_discussion_positioned(self, iid: int, body: str,
                                              position: dict, project: str = "") -> dict:
        """POST .../merge_requests/<iid>/discussions — new thread with a full
        side-aware position object: ``base_sha``/``start_sha``/``head_sha``,
        ``position_type``, ``old_path``/``new_path`` and exactly one of
        ``old_line``/``new_line``. Returns the response JSON unchanged. There is
        no general-note fallback: a 400/422 means the anchor is not valid on
        this diff and the caller must block the entry."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        payload = {"body": body, "position": position}
        return self._gitlab_write(
            f"{url}/api/v4/projects/{proj_id}/merge_requests/{int(iid)}/discussions",
            token, "POST", payload,
        )

    def gitlab_resolve_discussion(self, iid: int, discussion_id: str,
                                  project: str = "") -> dict:
        """PUT .../discussions/<id> with resolved=true — close a thread."""
        url, token, resolved = self._gitlab_config(project)
        proj_id = urllib.parse.quote(resolved, safe="")
        return self._gitlab_write(
            f"{url}/api/v4/projects/{proj_id}/merge_requests/{int(iid)}"
            f"/discussions/{urllib.parse.quote(discussion_id, safe='')}",
            token, "PUT", {"resolved": True},
        )

    # ── deps (gradle/npm artifact sync) — /api/documents/* ─────────────
    # These endpoints are plain multipart (no envelope) — the payload itself
    # is pre-encrypted by the caller. The ``rid`` field accepts either an
    # obfuscated repo identifier or a plain repo name (server resolves both).

    def deps_request(self, repo: str, manifest_bytes: bytes) -> dict:
        """POST /api/documents/submit — upload an encrypted manifest."""
        return self._post_multipart(
            "/api/documents/submit",
            fields={"rid": repo_to_rid(repo)},
            files={"attachment": ("manifest.bin", manifest_bytes)},
        )

    def deps_pending(self, repo: str) -> dict:
        """GET /api/documents/queue — list outstanding requests."""
        return self._get_json(f"/api/documents/queue?rid={repo_to_rid(repo)}")

    def deps_manifest(self, repo: str, item_id: str) -> bytes:
        """GET /api/documents/queue-item — download a request blob (raw bytes)."""
        return self._download_bytes(f"/api/documents/queue-item?rid={repo_to_rid(repo)}&id={item_id}")

    def deps_respond(self, repo: str, request_id: str,
                     archive_bytes: bytes) -> dict:
        """POST /api/documents/fulfill — upload an encrypted response archive."""
        return self._post_multipart(
            "/api/documents/fulfill",
            fields={"rid": repo_to_rid(repo), "request_id": request_id},
            files={"attachment": ("response.bin", archive_bytes)},
        )

    def deps_responses(self, repo: str) -> dict:
        """GET /api/documents/ready — list ready responses."""
        return self._get_json(f"/api/documents/ready?rid={repo_to_rid(repo)}")

    def deps_fetch(self, repo: str, item_id: str) -> bytes:
        """GET /api/documents/ready-item — download a response blob (raw bytes)."""
        return self._download_bytes(f"/api/documents/ready-item?rid={repo_to_rid(repo)}&id={item_id}")

    def deps_ack(self, repo: str, item_id: str) -> dict:
        """DELETE /api/documents/ack — confirm a response was applied."""
        return self._delete_json(f"/api/documents/ack?rid={repo_to_rid(repo)}&id={item_id}")

    # ── vault (corporate artifact mirror) — /api/cache/* ───────────────

    def vault_status(self) -> dict:
        """GET /api/cache/status — vault diagnostics."""
        return self._get_json("/api/cache/status")

    def vault_index(self) -> dict:
        """GET /api/cache/index — inventory + wanted."""
        return self._get_json("/api/cache/index")

    def vault_publish(self, repo: str, publication_bytes: bytes) -> dict:
        """POST /api/cache/publish — encrypted publication → CAS."""
        return self._post_multipart(
            "/api/cache/publish",
            fields={"repo": repo},
            files={"attachment": ("publication.enc", publication_bytes)},
            timeout=300,
        )

    # ── buffer (cross-machine clipboard) — /api/buffer ──────────────────

    def buffer_send(self, ciphertext_b64: str, hint: str = "") -> dict:
        """POST /api/buffer — append an encrypted clipboard entry."""
        body = json.dumps({"ciphertext_b64": ciphertext_b64, "hint": hint})
        return self._post_json("/api/buffer", body.encode(),
                               content_type="application/json")

    def buffer_list(self) -> dict:
        """GET /api/buffer — metadata for all entries (newest first)."""
        return self._get_json("/api/buffer")

    def buffer_get(self, item_id: str) -> bytes:
        """GET /api/buffer/{id} — raw ciphertext bytes."""
        return self._download_bytes(f"/api/buffer/{item_id}")

    def buffer_delete(self, item_id: str) -> None:
        """DELETE /api/buffer/{id} — remove one entry."""
        self._delete_json(f"/api/buffer/{item_id}")

    # ── file sync (repo-scoped encrypted file postbox) — /api/documents/* ─
    # v3 relay only: body and metadata are sealed to the pinned server key.

    def server_pubkey(self) -> dict:
        """GET /api/auth/pubkey — the server's long-term X25519 public key {alg, pub, fp}."""
        return self._get_json("/api/auth/pubkey")

    def _relay_session(self) -> RelaySession:
        """Fresh ephemeral against the pinned server key (fetched once, then cached)."""
        if self._server_pub is None:
            self._server_pub = decode_pub_b64(self.server_pubkey()["pub"])
        return RelaySession(self._server_pub)

    def file_sync_send(self, repo: str, path: str, plain_size: int,
                       data: bytes) -> dict:
        """POST /api/documents/attachment-upload — v3 relay-sealed file container.

        The body and the {"path", "plain_size"} metadata are sealed to the
        pinned server key; the form carries only "rid", "k" and base64 "meta".
        """
        session = self._relay_session()
        meta_plain = json.dumps({"path": path, "plain_size": plain_size},
                                separators=(",", ":")).encode("utf-8")
        meta = base64.b64encode(session.seal(meta_plain, RELAY_AAD_POSTBOX)).decode("ascii")
        return self._post_multipart(
            "/api/documents/attachment-upload",
            fields={"rid": repo_to_rid(repo), "k": session.epk_b64(), "meta": meta},
            files={"attachment": ("file.bin", session.seal(data, RELAY_AAD_POSTBOX))},
        )

    def file_sync_list(self, repo: str) -> dict:
        """GET /api/documents/attachment-list — list items for a repo."""
        return self._get_json(f"/api/documents/attachment-list?rid={repo_to_rid(repo)}")

    def file_sync_fetch(self, repo: str, item_id: str,
                        max_bytes: Optional[int] = None) -> bytes:
        """GET /api/documents/attachment-get — relay-sealed blob, opened via X-LGM-Epk."""
        session = self._relay_session()
        cap = {} if max_bytes is None else {"max_bytes": max_bytes + _RELAY_SEAL_SLACK}
        blob = self._download_bytes(
            f"/api/documents/attachment-get?rid={repo_to_rid(repo)}&id={item_id}",
            headers={"X-LGM-Epk": session.epk_b64()},
            **cap,
        )
        data = session.open(blob, RELAY_AAD_POSTBOX)
        if max_bytes is not None and len(data) > max_bytes:
            raise ResponseTooLargeError(
                "server", f"download exceeds the {max_bytes} byte cap")
        return data

    def file_sync_ack(self, repo: str, item_id: str) -> dict:
        """DELETE /api/documents/attachment-ack — confirm applied, server deletes."""
        return self._delete_json(f"/api/documents/attachment-ack?rid={repo_to_rid(repo)}&id={item_id}")
