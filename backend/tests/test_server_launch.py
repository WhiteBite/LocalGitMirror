"""Launch configs must not trust proxy headers: loopback checks use the real peer."""

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _load_run_module():
    spec = importlib.util.spec_from_file_location("lgm_run_launcher", ROOT / "run.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_run_prod_disables_proxy_headers():
    run = _load_run_module()
    kwargs = run._prod_server_kwargs(443, ROOT / "cert.pem", ROOT / "key.pem", False)
    assert kwargs["proxy_headers"] is False


def test_main_direct_run_disables_proxy_headers():
    from app.main import _direct_run_kwargs

    assert _direct_run_kwargs()["proxy_headers"] is False
