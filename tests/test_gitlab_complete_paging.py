from test_gitlab_paging import _client


def test_lists_mrs_beyond_ten_pages(monkeypatch):
    client = _client(monkeypatch)
    def page(url, token):
        n = int(url.rsplit("page=", 1)[1])
        return [{"iid": n}], str(n + 1) if n < 12 else ""
    monkeypatch.setattr(client, "_gitlab_get_paged", page)

    result = client.gitlab_list_mrs()

    assert [row["iid"] for row in result] == list(range(1, 13))


def test_discussions_use_next_page_without_empty_request(monkeypatch):
    client = _client(monkeypatch)
    calls = []
    def page(url, token):
        calls.append(url)
        return [{"id": "thread"}], ""
    monkeypatch.setattr(client, "_gitlab_get_paged", page)

    result = client.gitlab_list_discussions(1)

    assert result == [{"id": "thread"}]
    assert len(calls) == 1
