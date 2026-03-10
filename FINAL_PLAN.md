# LocalGitMirror — Final Plan (IDEA Orchestrator + Mirror)

This document фиксирует финальную архитектуру и дорожную карту.

## 0) Scope / Principles

### What we are building
1) **Mirror Server (LocalGitMirror)** on home machine
- Stores:
  - `repo.git` (bare repo for git push/pull)
  - `repo/` (workspace for UI browse/edit)
2) **Work-kit** (workstation scripts)
- `backup_work_stealth.bat` + `stealth_dump_tool.py`
- Generates `dump_<project>_YYYYMMDD_HHMM.dmp` (AES-GCM container over git bundle)
3) **Web UI**
- Shared folders upload (manual path)
- Sync Wizard apply (manual path)
4) **IntelliJ IDEA Plugin — Orchestrator**
- “One button sync” UX
- Does NOT replace Git. It orchestrates existing steps.

### What we are NOT building
- No custom SCM, no “raw diffs as primary transport”.
- No gRPC requirement (optional later if needed, but not part of MVP).
- No dependency on 7-Zip (everything uses Python libs).

## 1) Data flows

### A) Work → Home (primary)
1. Work: commit locally
2. Work: generate `dump_*.dmp` (full first time, incremental next times via `.last_sync`)
3. Transfer:
   - Manual: upload dump via Web UI → Shared Folders
   - Orchestrated: plugin uploads via HTTPS
4. Home: apply dump to selected repo (fetch/reset) and update bare repo

### B) Home → Work (primary)
1. Home: commit in workspace (UI editor or IDE)
2. Home: push to bare (`repo.git`)
3. Work: `git pull`

> Note: “dump back to work” is an optional offline fallback, not the default.

## 2) Transport / Availability Layer

### Primary transport
- HTTPS (port 443) to the home Mirror.

### Availability Layer (optional)
- Client supports **standard proxy configuration** (SOCKS5/HTTP).
- This is an *infrastructure / availability* option to operate in heterogeneous network environments.
- Not part of business logic; not required for normal operation.

## 3) IntelliJ IDEA Plugin — MVP

### UX
- Button: **Sync to Mirror**
- Status panel/toast:
  - upload progress
  - apply result
  - last applied commit hash

### Under the hood
1) Validate repo state (git present, clean working tree)
2) Run work-kit generator (preferred):
   - `backup_work_stealth.bat [password]` in project root
3) Find newest `dump_<project>_*.dmp`
4) Upload to mirror via HTTPS (API key)
5) Trigger apply on mirror
6) Show result

### Settings
- Mirror base URL (default `https://<home-ip>`)
- API key
- Sync password input (or env)
- Shared folder name (optional)
- Proxy: rely on IDE’s own proxy settings

## 4) Server-side roadmap

### Phase 1 — Simplify API for plugin
Add a single endpoint:
- `POST /api/sync/upload-and-apply`
  - Accepts `.dmp` file
  - Applies to current selected repo (or repo param)
  - Returns `{ success, repo, dump_file, commit, message }`

Keep manual fallback:
- Shared folders upload + `POST /api/sync/apply-bundle`

### Phase 2 — Improve UX & diagnostics
- Better error messages:
  - repo mismatch
  - wrong password
  - uncommitted changes
  - unsupported dump format
- UI shows scanned folders / selected dump

## 5) Tests

### E2E smoke (re-runnable)
- `scripts/e2e_stealth_roundtrip.py` covers:
  - work commit → dump → upload → apply → home edit+commit → work pull

### One-command runner
- `run_e2e_happy_test.bat`

## 6) “Done” criteria
- Work-kit works on a workstation without LocalGitMirror codebase.
- Web UI fully RU localized (no `some.key.path` visible).
- E2E script passes consistently.
- IDEA plugin MVP runs the end-to-end sync in 1 click.

## 7) Execution status (2026-03-06)

### Completed
- `POST /api/sync/upload-and-apply` implemented and wired in UI (`SyncWizard.vue`).
- Python-only stealth dump flow is active (AES-GCM container, no 7-Zip dependency).
- Workstation tool is self-contained (`stealth_dump_tool.py` does not import backend modules).
- Filename repo inference fixed for underscore repo names:
  - Supports `dump_<repo>_YYYYMMDD_HHMM.dmp` where `<repo>` can contain `_`.
  - Added validation so only valid repo names are inferred.
- Added regression tests:
  - `backend/tests/test_upload_filename_inference.py`
  - `backend/tests/test_upload_and_apply_endpoint.py`

### Verification evidence
- Focused backend tests:
  - `python -m pytest tests/test_upload_and_apply_endpoint.py tests/test_upload_filename_inference.py -q` → **12 passed**
- Compile check:
  - `python -m compileall backend/app/routers/api.py` → success
- LSP diagnostics:
  - `backend/app/routers/api.py` → clean
  - `backend/tests/test_upload_and_apply_endpoint.py` → clean
  - `backend/tests/test_upload_filename_inference.py` → clean

### E2E smoke notes
- `scripts/e2e_stealth_roundtrip.py` supports two apply modes:
  - default: shared folder upload + `POST /api/sync/apply-bundle`
  - one-shot: `--use-upload-and-apply` uses `POST /api/sync/upload-and-apply`

### Remaining to close full roadmap
- Run full end-to-end manual smoke on a live 443 instance (real upload via UI + home edit + work pull) and record result in docs.
- Finish IDEA plugin scaffold polish (remove duplicated `tasks { runIde ... }` block in `idea-plugin/build.gradle.kts`).

## 8) Release checklist addendum (2026-03-10)

- Security mode is explicit on backend:
  - `REQUIRE_API_KEY=true` requires non-empty `API_KEY` (fail-closed on misconfig).
  - Client auth header: `X-Session-ID`.
- Sync password handling in plugin/work-kit path:
  - Plugin passes password through env `SYNC_PASSWORD` (not via process argv).
- Plugin localization:
  - Explicit plugin language selector independent from IDE UI language (`auto|en|ru`).
- Build artifact path for distribution:
  - `idea-plugin/build/distributions/localgitmirror-idea-plugin-<version>.zip`.
