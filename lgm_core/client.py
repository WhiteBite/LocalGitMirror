"""MirrorClient — all network access for lgm, via urllib (stdlib only).

Auth: sends **both** ``Authorization: Bearer <key>`` and the legacy
``X-Session-ID: <key>`` header so the same client works against servers
that accept either (the backend's ``get_api_key`` checks both).

TLS: when ``insecure_tls`` is True (default), an unverified SSL context is
used — the mirror runs with a self-signed cert.

Errors: ``LgmError`` carries a structured ``code`` (HTTP status int or
``"network"``) and ``message``.  Callers can inspect ``code`` to decide
whether to retry.
"""
from __future__ import annotations

import json
import ssl
import urllib.parse
import uuid
import urllib.request
import urllib.error
from pathlib import Path
from typing import Optional

from .crypto import encrypt_envelope, decrypt_envelope, encrypt_bundle
from .config import cfg


class LgmError(Exception):
    """Structured error: ``code`` (int HTTP status or "network") + ``message``."""

    def __init__(self, code, message: str):
        self.code = code
        self.message = message
        super().__init__(f"[{code}] {message}")


def _ssl_ctx(insecure: bool = True) -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    if insecure:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    return ctx


def _auth_headers(api_key: str) -> dict:
    """Return auth headers — both Bearer and legacy X-Session-ID."""
    h = {}
    if api_key:
        h["Authorization"] = f"Bearer {api_key}"
        h["X-Session-ID"] = api_key
    return h


class MirrorClient:
    """Thin HTTP client covering the full Mirror API surface."""

    def __init__(self, base_url: str = "", api_key: str = "",
                 insecure_tls: bool = True, timeout: int = 15,
                 sync_password: str = ""):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.insecure_tls = insecure_tls
        self.timeout = timeout
        self.sync_password = sync_password

    # ── low-level helpers ────────────────────────────────────────────────

    def _url(self, path: str) -> str:
        return self.base_url + path

    def _get_json(self, path: str, timeout: Optional[int] = None) -> dict:
        url = self._url(path)
        req = urllib.request.Request(url, headers=_auth_headers(self.api_key))
        try:
            with urllib.request.urlopen(
                req, context=_ssl_ctx(self.insecure_tls),
                timeout=timeout or self.timeout,
            ) as r:
                return json.loads(r.read())
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
            with urllib.request.urlopen(
                req, context=_ssl_ctx(self.insecure_tls), timeout=self.timeout,
            ) as r:
                return json.loads(r.read())
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
            with urllib.request.urlopen(
                req, context=_ssl_ctx(self.insecure_tls),
                timeout=timeout or self.timeout,
            ) as r:
                return json.loads(r.read())
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
            with urllib.request.urlopen(
                req, context=_ssl_ctx(self.insecure_tls),
                timeout=timeout or 120,
            ) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            body_txt = e.read().decode(errors="replace")
            raise LgmError(e.code, body_txt) from None
        except Exception as e:
            raise LgmError("network", str(e)) from None

    def _download(self, path: str, out: Path) -> bool:
        url = self._url(path)
        req = urllib.request.Request(url, headers=_auth_headers(self.api_key))
        try:
            with urllib.request.urlopen(
                req, context=_ssl_ctx(self.insecure_tls), timeout=120,
            ) as r:
                out.write_bytes(r.read())
            return True
        except Exception:
            return False

    def _download_bytes(self, path: str) -> bytes:
        url = self._url(path)
        req = urllib.request.Request(url, headers=_auth_headers(self.api_key))
        try:
            with urllib.request.urlopen(
                req, context=_ssl_ctx(self.insecure_tls), timeout=120,
            ) as r:
                return r.read()
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
        """GET /api/repos — list repos + current."""
        return self._get_json("/api/repos")

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
        return self._post_multipart(
            "/api/documents/upload",
            fields={"e": e},
            files={"attachment": ("cache.bin", encrypted)},
        )

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
            import base64
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

    def _gitlab_config(self) -> tuple[str, str, str]:
        """Resolve (url, token, project) from cfg; raise LgmError when unset."""
        url = cfg("GITLAB_URL").rstrip("/")
        token = cfg("GITLAB_TOKEN")
        project = cfg("GITLAB_PROJECT")
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
        req = urllib.request.Request(url, headers={"PRIVATE-TOKEN": token})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")[:300]
            raise LgmError(e.code, f"GitLab API error {e.code}: {body}") from None
        except Exception as e:
            raise LgmError("network", f"GitLab unreachable: {e}") from None

    def gitlab_list_mrs(self) -> list:
        """GET /api/v4/projects/<id>/merge_requests?state=opened — open MRs."""
        url, token, project = self._gitlab_config()
        proj_id = urllib.parse.quote(project, safe="")
        data = self._gitlab_get(
            f"{url}/api/v4/projects/{proj_id}/merge_requests?state=opened", token
        )
        if not isinstance(data, list):
            raise LgmError("network", f"GitLab returned non-list for merge_requests: {type(data).__name__}")
        return data

    def gitlab_get_mr(self, iid: int) -> dict:
        """GET /api/v4/projects/<id>/merge_requests/<iid> — one MR."""
        url, token, project = self._gitlab_config()
        proj_id = urllib.parse.quote(project, safe="")
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

    # ── deps (gradle/npm artifact sync) — /api/deps/* ───────────────────
    # These endpoints are plain multipart (no envelope) — the payload itself
    # is pre-encrypted by the caller.

    def deps_request(self, repo: str, manifest_bytes: bytes) -> dict:
        """POST /api/deps/request — upload an encrypted manifest."""
        return self._post_multipart(
            "/api/deps/request",
            fields={"repo": repo},
            files={"attachment": ("manifest.bin", manifest_bytes)},
        )

    def deps_pending(self, repo: str) -> dict:
        """GET /api/deps/pending — list outstanding requests."""
        return self._get_json(f"/api/deps/pending?repo={repo}")

    def deps_manifest(self, repo: str, item_id: str) -> bytes:
        """GET /api/deps/manifest — download a request blob (raw bytes)."""
        return self._download_bytes(f"/api/deps/manifest?repo={repo}&id={item_id}")

    def deps_respond(self, repo: str, request_id: str,
                     archive_bytes: bytes) -> dict:
        """POST /api/deps/respond — upload an encrypted response archive."""
        return self._post_multipart(
            "/api/deps/respond",
            fields={"repo": repo, "request_id": request_id},
            files={"attachment": ("response.bin", archive_bytes)},
        )

    def deps_responses(self, repo: str) -> dict:
        """GET /api/deps/responses — list ready responses."""
        return self._get_json(f"/api/deps/responses?repo={repo}")

    def deps_fetch(self, repo: str, item_id: str) -> bytes:
        """GET /api/deps/fetch — download a response blob (raw bytes)."""
        return self._download_bytes(f"/api/deps/fetch?repo={repo}&id={item_id}")

    def deps_ack(self, repo: str, item_id: str) -> dict:
        """DELETE /api/deps/ack — confirm a response was applied."""
        return self._delete_json(f"/api/deps/ack?repo={repo}&id={item_id}")

    # ── vault (corporate artifact mirror) — /api/deps/mirror/* ──────────

    def vault_status(self) -> dict:
        """GET /api/deps/mirror/status — vault diagnostics."""
        return self._get_json("/api/deps/mirror/status")

    def vault_index(self) -> dict:
        """GET /api/deps/mirror/index — inventory + wanted."""
        return self._get_json("/api/deps/mirror/index")

    def vault_publish(self, repo: str, publication_bytes: bytes) -> dict:
        """POST /api/deps/mirror/publish — encrypted publication → CAS."""
        return self._post_multipart(
            "/api/deps/mirror/publish",
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

    # ── file sync (repo-scoped encrypted file postbox) ──────────────────

    def file_sync_send(self, repo: str, path: str, plain_size: int,
                       data: bytes) -> dict:
        """POST /api/file-sync/upload — upload an encrypted file container."""
        return self._post_multipart(
            "/api/file-sync/upload",
            fields={"repo": repo, "path": path, "plain_size": str(plain_size)},
            files={"attachment": ("file.bin", data)},
        )

    def file_sync_list(self, repo: str) -> dict:
        """GET /api/file-sync/list — list items for a repo."""
        return self._get_json(f"/api/file-sync/list?repo={repo}")

    def file_sync_fetch(self, repo: str, item_id: str) -> bytes:
        """GET /api/file-sync/download — download a file blob."""
        return self._download_bytes(f"/api/file-sync/download?repo={repo}&id={item_id}")

    def file_sync_ack(self, repo: str, item_id: str) -> dict:
        """DELETE /api/file-sync/ack — confirm applied, server deletes."""
        return self._delete_json(f"/api/file-sync/ack?repo={repo}&id={item_id}")
