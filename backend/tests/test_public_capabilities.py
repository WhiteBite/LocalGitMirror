"""Regression tests for the unauthenticated LAN discovery marker."""

import asyncio

from fastapi.testclient import TestClient

from app.main import app, public_capabilities


def test_public_capabilities_identifies_mirror_without_secrets():
    payload = asyncio.run(public_capabilities())

    assert payload == {
        "service": "DocCache",
        "discoveryVersion": 1,
    }


def test_capabilities_route_stays_public_with_api_key_set(monkeypatch):
    monkeypatch.setenv("API_KEY", "public-cap-test-key")
    client = TestClient(app)  # no context manager: skips lifespan

    res = client.get("/api/capabilities")
    assert res.status_code == 200, res.text
    assert res.json() == {
        "service": "DocCache",
        "discoveryVersion": 1,
    }
