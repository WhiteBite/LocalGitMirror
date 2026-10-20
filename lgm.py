#!/usr/bin/env python3
"""
lgm — LocalGitMirror CLI (thin wrapper over lgm_core).

Usage:
  python lgm.py <command> [flags]      # run an operation
  python lgm.py --help                 # list all commands
  python lgm.py <command> --help       # command-specific help

Subcommands and their flags are generated from ``lgm_core.ops.REGISTRY``.
Global flags: --base-url, --api-key, --password, --repo, --json.

Exit codes: 0 success; 1 crash (unexpected exception); 2 usage error;
3 config error; 4 network error; 5 the mirror or an op refused the
operation.  Under --json every failure prints one error envelope to
stdout: {"success": false, "error": {"kind": ..., "code": ..., "message": ...}}
(code is a stable machine reason like "diverged" or null).

Config is read from .env in the same directory as this script, or from
--base-url / --password / --api-key flags. SYNC_PASSWORD and API_KEY from
.env are used.  Set LGM_USE_ENV_<KEY>=1 to prefer the process env for a key.
"""
import argparse
import json
import sys

from lgm_core.config import Config
from lgm_core.client import MirrorClient, LgmError
from lgm_core.ops import REGISTRY, Ctx, get_op
from lgm_core.render import render

# Global flags that every subcommand also accepts (with SUPPRESS default so
# they don't clobber the parent parser's value when not specified).
_GLOBAL_FLAGS = [
    ("--base-url", {"default": argparse.SUPPRESS, "help": "Mirror base URL"}),
    ("--api-key", {"default": argparse.SUPPRESS, "help": "API key"}),
    ("--password", {"default": argparse.SUPPRESS, "help": "Sync password"}),
    ("--json", {"action": "store_true", "default": argparse.SUPPRESS, "help": "Output raw JSON"}),
]


def harden_stdio() -> None:
    """Reconfigure stdout/stderr as UTF-8 so legacy-codepage consoles (cp1251) don't crash on Unicode symbols."""
    for s in (sys.stdout, sys.stderr):
        if hasattr(s, "reconfigure"):
            s.reconfigure(encoding="utf-8", errors="replace")


def _build_parser() -> argparse.ArgumentParser:
    """Build the argparse parser from the REGISTRY."""
    p = argparse.ArgumentParser(
        prog="lgm",
        description="LocalGitMirror CLI — thin wrapper over lgm_core.ops.REGISTRY.",
    )
    # Global flags on the parent parser (real defaults).
    p.add_argument("--base-url", default=None,
                   help="Mirror base URL (e.g. https://192.168.0.100:443)")
    p.add_argument("--api-key", default=None,
                   help="API key (default from .env API_KEY)")
    p.add_argument("--password", default=None,
                   help="Sync password (default from .env SYNC_PASSWORD)")
    p.add_argument("--repo", default=None,
                   help="Repository name (global default for commands that take --repo)")
    p.add_argument("--json", action="store_true", default=False,
                   help="Output raw JSON instead of human-readable text")

    sub = p.add_subparsers(dest="cmd", required=True, metavar="<command>")

    for op in REGISTRY:
        sp = sub.add_parser(op.name, help=op.summary)
        # Add global flags to each subparser with SUPPRESS so they don't
        # overwrite the parent's value when not specified after the subcommand.
        for flag_name, flag_kwargs in _GLOBAL_FLAGS:
            sp.add_argument(flag_name, **flag_kwargs)
        for param in op.params:
            flag = f"--{param.name.replace('_', '-')}"
            if param.type == "bool":
                sp.add_argument(
                    flag,
                    action="store_true",
                    default=argparse.SUPPRESS,
                    help=param.help,
                )
            elif param.type == "int":
                sp.add_argument(
                    flag,
                    type=int,
                    default=argparse.SUPPRESS,
                    required=param.required,
                    help=param.help,
                )
            elif param.type == "list":
                sp.add_argument(
                    flag,
                    action="append",
                    default=argparse.SUPPRESS,
                    required=param.required,
                    help=param.help,
                )
            else:  # str
                sp.add_argument(
                    flag,
                    type=str,
                    default=argparse.SUPPRESS,
                    required=param.required,
                    help=param.help,
                )
    return p


def _resolve_config(args: argparse.Namespace) -> Config:
    """Build a Config from global flags + .env."""
    return Config.from_env(
        base_url=getattr(args, "base_url", None) or "",
        api_key=getattr(args, "api_key", None) or "",
        sync_password=getattr(args, "password", None) or "",
    )


def _build_ctx(config: Config) -> Ctx:
    """Build the execution context with a MirrorClient."""
    client = MirrorClient(
        base_url=config.base_url,
        api_key=config.api_key,
        insecure_tls=config.insecure_tls,
        sync_password=config.sync_password,
        pin_sha256=config.pin_sha256,
        pin_file=config.pin_file,
    )
    return Ctx(config=config, client=client)


def _extract_args(args: argparse.Namespace, op) -> dict:
    """Extract the op's params from parsed args, applying global --repo override."""
    out = {}
    for param in op.params:
        # getattr with the param's own default (SUPPRESS means the attribute
        # may not exist if the flag wasn't passed).
        out[param.name] = getattr(args, param.name, param.default)
    # Global --repo override: if the global --repo was set (not None), use it.
    global_repo = getattr(args, "repo", None)
    if global_repo is not None:
        for param in op.params:
            if param.name == "repo":
                out["repo"] = global_repo
                break
    return out


_EXIT_CRASH = 1
_EXIT_BY_KIND = {"config": 3, "network": 4}
_EXIT_REFUSED = 5


def _print_error(args: argparse.Namespace, kind, message: str,
                 machine_code=None) -> int:
    if getattr(args, "json", False):
        envelope = {"success": False,
                    "error": {"kind": kind, "code": machine_code,
                              "message": message}}
        print(json.dumps(envelope, ensure_ascii=False, indent=2))
    else:
        print(f"ERROR: [{kind}] {message}", file=sys.stderr)
    if kind == "crash":
        return _EXIT_CRASH
    return _EXIT_BY_KIND.get(kind, _EXIT_REFUSED)


def main() -> int:
    harden_stdio()
    parser = _build_parser()
    try:
        args = parser.parse_args()
    except SystemExit as e:
        # argparse exits with 2 on usage errors.
        return e.code if isinstance(e.code, int) else 2

    op = get_op(args.cmd)
    if op is None:
        print(f"Unknown command: {args.cmd}", file=sys.stderr)
        return 2

    config = _resolve_config(args)
    ctx = _build_ctx(config)
    op_args = _extract_args(args, op)

    try:
        result = op.run(ctx, op_args)
    except LgmError as e:
        return _print_error(args, e.code, e.message, e.machine_code)
    except Exception as e:
        return _print_error(args, "crash", f"{type(e).__name__}: {e}")

    if isinstance(result, dict) and result.get("success") is False:
        detail = result.get("error") or result.get("message") or "operation reported success=false"
        code = result.get("code")
        return _print_error(args, "op_failure", str(detail),
                            code if isinstance(code, str) else None)

    if getattr(args, "json", False):
        print(json.dumps(result, ensure_ascii=False, indent=2, default=str))
    else:
        output = render(op.name, result)
        if output:
            print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
