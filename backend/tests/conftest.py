"""Shared test helpers for the LocalGitMirror backend test suite."""

from app.core.envelope_crypto import decrypt_envelope, encrypt_envelope


def make_envelope(payload: dict, password: str) -> str:
    """Encrypt *payload* and return the base64 envelope string."""
    return encrypt_envelope(payload, password)


def parse_envelope(response_body: dict, password: str) -> dict:
    """Decrypt the 'e' field of a response dict and return the inner dict."""
    return decrypt_envelope(response_body["e"], password)


def envelope_post(client, path: str, payload: dict, password: str, **kw):
    """POST to an envelope endpoint: encrypt payload, POST JSON, decrypt response."""
    e = make_envelope(payload, password)
    resp = client.post(path, json={"e": e}, **kw)
    return resp


def envelope_form_post(client, path: str, payload: dict, password: str, files=None, **kw):
    """POST to a multipart envelope endpoint (e.g. export/upload)."""
    e = make_envelope(payload, password)
    data = {"e": e}
    resp = client.post(path, data=data, files=files or {}, **kw)
    return resp
