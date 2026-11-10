import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import lgm_core.ops_mr as opmod
from lgm_core.client import LgmError
from lgm_core.repo_name import repo_slug_from_remote_url, sanitize_slug


@pytest.mark.parametrize("raw,expected", [
    ("  Phonyx  ", "phonyx"),
    ("dev/onyx/phonyx", "dev-onyx-phonyx"),
    ("a b/c", "a-b-c"),
    ("---x---", "x"),
    ("", ""),
])
def test_sanitize_slug(raw, expected):
    assert sanitize_slug(raw) == expected


@pytest.mark.parametrize("url,expected", [
    ("https://gitlab.kryptodev.ru/dev/onyx/phonyx.git", "phonyx"),
    ("https://host:8443/group/repo", "repo"),
    ("git@host:group/sub/repo.git", "repo"),
    ("ssh://git@host:22/group/repo.git", "repo"),
    ("https://host/group/repo/", "repo"),
    ("D:/Sources/kryptonit/.lgm/bare/phonyx.git", "phonyx"),
    ("", ""),
])
def test_repo_slug_from_remote_url(url, expected):
    assert repo_slug_from_remote_url(url) == expected


def _guard(monkeypatch, repo, slug, folder, tmp_path):
    checkout = tmp_path / folder
    checkout.mkdir()
    monkeypatch.setattr(opmod, "remote_slug", lambda proj: slug)
    opmod._guard_repo_matches_remote(repo, checkout)


def test_guard_refuses_the_folder_name_when_remote_differs(monkeypatch, tmp_path):
    with pytest.raises(LgmError) as ei:
        _guard(monkeypatch, "backend", "phonyx", "backend", tmp_path)
    assert ei.value.machine_code == "repo-remote-mismatch"
    assert "phonyx" in str(ei.value)


def test_guard_allows_matching_name(monkeypatch, tmp_path):
    _guard(monkeypatch, "phonyx", "phonyx", "backend", tmp_path)


def test_guard_allows_a_deliberate_other_name(monkeypatch, tmp_path):
    _guard(monkeypatch, "legacy-key", "phonyx", "backend", tmp_path)


def test_guard_allows_when_remote_is_unknown(monkeypatch, tmp_path):
    _guard(monkeypatch, "backend", "", "backend", tmp_path)