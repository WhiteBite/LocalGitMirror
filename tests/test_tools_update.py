"""op_update tools-blob unwrap tolerates both raw-zip and sealed servers."""
import io
import zipfile

import pytest

from lgm_core.crypto import encrypt_bundle
from lgm_core.ops_git import _unwrap_tools_blob

PWD = "pw"


def _zip() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("lgm_core/ops_git.py", b"x = 1\n")
    return buf.getvalue()


def test_raw_zip_passes_through_undecrypted():
    blob = _zip()
    assert blob[:2] == b"PK"
    assert _unwrap_tools_blob(blob, PWD) == blob


def test_sealed_bundle_is_decrypted_to_zip():
    plain = _zip()
    sealed = encrypt_bundle(plain, PWD)
    assert sealed[:2] != b"PK"
    assert _unwrap_tools_blob(sealed, PWD) == plain


def test_malformed_blob_is_rejected_not_silently_passed():
    with pytest.raises(Exception):
        _unwrap_tools_blob(b"garbage-not-zip-not-bundle", PWD)
