"""Regression tests for the unauthenticated LAN discovery marker."""

import asyncio

from app.main import public_capabilities


def test_public_capabilities_identifies_mirror_without_secrets():
    payload = asyncio.run(public_capabilities())

    assert payload == {
        "service": "DocCache",
        "discoveryVersion": 1,
    }
