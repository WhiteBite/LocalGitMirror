#!/usr/bin/env bash
# Thin wrapper around run.py. No args = production, "dev" = development.
#   ./start.sh        -> production
#   ./start.sh dev    -> development (backend --reload + Vite, one terminal)
cd "$(dirname "$0")"
exec python3 run.py "$@"
