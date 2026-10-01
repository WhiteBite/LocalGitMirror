"""Home without GitLab: mr_list falls back to transferred notes; mr_notes_request uploads a request."""
import base64
from types import SimpleNamespace

import pytest

from lgm_core.crypto import decrypt_bundle, encrypt_bundle
from lgm_core.client import LgmError
from lgm_core.ops import Ctx, op_mr_list, op_mr_notes_request

PWD = "test-sync-password"

MD = "\n".join([
    "<!-- note -->",
    "# MR !7 — Title seven",
    "",
    "- **Ветка:** `feat/x`",
    "",
    "## ⚠ НЕ РЕШЕНО · тред 1",
    "## ⚠ НЕ РЕШЕНО · тред 2",
    "## ✓ решено · тред 3",
    "",
])


class _StubClient:
    def __init__(self, markdown=MD, gitlab_error=None):
        self.sync_password = PWD
        self._md = markdown
        self._gitlab_error = gitlab_error or LgmError("config", "GitLab not configured")
        self.sent = None

    def gitlab_list_mrs(self):
        raise self._gitlab_error

    def repos(self):
        return {"repos": [{"name": "r1"}]}

    def file_sync_list(self, repo):
        enc = base64.b64encode(encrypt_bundle(b"mr-notes/mr-!7.md", PWD)).decode()
        return {"items": [{"id": "i1", "path": "x/ab", "path_enc": enc, "mtime": 5}]}

    def file_sync_fetch(self, repo, item_id):
        return encrypt_bundle(self._md.encode("utf-8"), PWD)

    def file_sync_send(self, repo, path, size, data, path_enc=None):
        self.sent = (repo, path, size, data, path_enc)
        return {"id": "new1"}


def test_mr_list_falls_back_to_postbox_without_gitlab():
    res = op_mr_list(Ctx(config=SimpleNamespace(sync_password=PWD), client=_StubClient()), {})
    assert res["count"] == 1
    item = res["items"][0]
    assert item["iid"] == 7
    assert item["title"] == "Title seven"
    assert item["source_branch"] == "feat/x"
    assert item["unresolved"] == 2
    assert item["source"] == "cache"
    assert item["repo"] == "r1"
    assert res["errors"] == []


def test_mr_list_reraises_non_config_gitlab_error():
    client = _StubClient(gitlab_error=LgmError(500, "GitLab API error 500: boom"))
    with pytest.raises(LgmError) as ei:
        op_mr_list(Ctx(config=SimpleNamespace(sync_password=PWD), client=client), {})
    assert ei.value.code == 500


def test_mr_list_collects_postbox_errors():
    class _TwoRepoClient:
        sync_password = PWD

        def gitlab_list_mrs(self):
            raise LgmError("config", "GitLab not configured")

        def repos(self):
            return {"repos": [{"name": "good"}, {"name": "bad"}]}

        def file_sync_list(self, repo):
            if repo == "bad":
                raise LgmError("network", "mirror unreachable")
            return {"items": []}

    res = op_mr_list(Ctx(config=SimpleNamespace(sync_password=PWD),
                         client=_TwoRepoClient()), {})
    assert res["count"] == 0
    assert res["items"] == []
    assert res["errors"] == [{"repo": "bad", "error": "mirror unreachable"}]


def test_mr_notes_request_uploads_encrypted_request():
    client = _StubClient()
    res = op_mr_notes_request(Ctx(config=SimpleNamespace(sync_password=PWD), client=client),
                              {"repo": "r1", "iid": 7})
    assert res["success"] is True
    repo, _path, _size, _data, path_enc = client.sent
    assert repo == "r1"
    display = decrypt_bundle(base64.b64decode(path_enc), PWD).decode("utf-8")
    assert display == "mr-notes-request/mr-!7.md"


def test_mr_notes_request_requires_iid():
    res = op_mr_notes_request(Ctx(config=SimpleNamespace(sync_password=PWD), client=_StubClient()),
                              {"repo": "r1"})
    assert res["success"] is False


if __name__ == "__main__":
    pytest.main([__file__, "-q"])
