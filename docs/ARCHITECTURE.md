# Architecture & deployment topology

Current-state reference for LocalGitMirror. Every claim here is traceable to a source file; read the file, not this doc, when precision matters. If this doc and the code disagree, the code wins and this doc is a bug.

Personal cross-machine tool bridging a restricted corporate work environment and a free home environment through one self-hosted encrypted mirror. No cloud, no third-party services. Python backend + Kotlin IntelliJ plugin + Vue SPA + Python CLI/MCP.

## Deployment (as run by the owner)

> на домашнем компе запущен сервер и плагин и мсп. на рабочем будет плагин и мб мсп (мб и не будет)

| Component | HOME PC | WORK PC |
| --- | --- | --- |
| Mirror server (`run.py` → FastAPI backend + built Vue SPA) | runs | never |
| IntelliJ plugin (`idea-plugin/`, display name **DocCache**) | installed | installed, primary day-to-day surface |
| MCP server (`lgm_mcp.py`) | runs (for home-side AI agents) | optional, may be absent |
| CLI (`lgm.py`) | used (primary scriptable surface) | not used — agents use MCP instead |

The server exists only on HOME. WORK reaches it over HTTPS/LAN as an API client. On HOME the plugin, MCP, and CLI all talk to the locally-running server (`baseUrl` = localhost or the home LAN IP). The CLI (`lgm.py`) is exercised on HOME only; on WORK the plugin and (if present) the MCP server talk to the home server's LAN address.

## The four user-facing surfaces

Not interchangeable; each covers a different slice:

| Surface | Entry point | What it can do | Where it runs |
| --- | --- | --- | --- |
| IntelliJ plugin | `idea-plugin/`, tool window "DocCache" (right anchor), menus Tools / VCS / Project view | Full daily flow: send/pull branches, GitLab MR transfer, MR review replies, deps request/respond/apply/publish, file postbox, cross-machine clipboard buffer, branch prune, preflight/vault/dry-run diagnostics | HOME and WORK |
| Web SPA | served by the backend from `frontend/dist` at `/` | Server-side management only: dashboard, file browser, global search, exchange/buffer, commit history, settings. No send/pull of bundles, no GitLab, no deps actions | HOME (it *is* part of the server) |
| CLI | `python lgm.py <command>` | Scriptable subset: everything in `lgm_core/ops.py REGISTRY` (24 ops) rendered to text/JSON | HOME only (owner runs it there; WORK uses MCP) |
| MCP | `python lgm_mcp.py` (stdio JSON-RPC) | Same 24 ops as the CLI, exposed as agent tools generated from the same REGISTRY; `serverInfo.name = "doccache-tools"` | HOME (mainly); optionally WORK |

CLI and MCP are functionally identical because both are generated from `lgm_core/ops.py REGISTRY`; the plugin implements its own richer flows in Kotlin (`lgm_core` does not cover the plugin's file-postbox, buffer, or full review UI).

## Machine roles

`MirrorSettingsService.State.machineRole` = `auto` | `home` | `work`. `auto` (default) resolves via `idea/deps/RoleDetector.kt`: host parsed from `baseUrl` is loopback or literally matches a local interface address → HOME; anything else or any failure → WORK (safe default). Result cached per settings change.

What each role enables (defaults from `MirrorSettingsService.State`):

| Setting (default) | HOME | WORK |
| --- | --- | --- |
| `autoRequestDeps` (true) | detect unresolvable corporate deps, post manifest | off by role logic |
| `autoApplyDeps` (true) | unpack received dep responses into local caches | n/a |
| `autoRespondDeps` (false) | n/a | poll pending requests, ship artifacts from local cache |
| `autoMrReview` (true) | auto-write incoming `mr-notes/mr-!N.md` into `.mr-notes/` | push reply files from postbox to GitLab |
| `autoPushReplies` (false) | n/a | post approved replies without opening the dialog |
| `depsPollSec` (300) | shared poll interval (+/-30% jitter, 15-min fast window, backoff to 4x) | same |

Startup activities (`plugin.xml`): `PullCheckStartupActivity`, `DepsAutomationStartupActivity`, `MrReviewAutoStartupActivity`, `LgmToolWindowPlacementActivity`. Role gating lives in `DepsAutomationService` and `MrReviewAutoService`.

Topology note: HOME is where the server runs, so HOME's plugin detects role `home` naturally (loopback baseUrl). WORK's baseUrl points at the home machine's LAN IP → role `work`.

## Data flows (directional)

All three ride the same encrypted HTTP transport (`/api/documents/*`). Branch bundles stay opaque to the server (envelope + bundle crypto, sealed to the pinned server key). Relay flows (deps, file postbox, buffer, MR notes/replies, vault publish) carry v3 ECIES sealed to the home server's pinned X25519 pubkey; the server terminates that crypto on write and stores plaintext re-encrypted at rest under an independent `relay.key` (see Crypto). The clipboard buffer rides `/api/buffer/*` with the same relay scheme.

### 1. Branch bundles (send / pull)

```
WORK ──plugin "Send current"/"Send branch…"──▶ POST /api/documents/upload (encrypted git bundle) ──▶ mirror bare repo + workspace checkout
HOME ──plugin "Sync pull…"/lgm pull──▶ POST /api/documents/check (which tips mirror has) ──▶ incremental bundle download ──▶ git fetch locally
HOME ──edits, builds──▶ sends back the same way; WORK pulls via "Pull back…"
```

Negotiation: `check` reports known commits; `link` applies pointer-only updates when the target commit already exists (`apply-known` semantics in `backend/app/routers/sync.py`). Incremental bundles use mirror tips as `^` prerequisites (`send_branches` in `lgm_core/ops.py`).

### 2. GitLab MR review loop

```
WORK ──"Send MR branch to Cache…"──▶ git fetch origin <MR source branch> ──one deduplicated bundle──▶ mirror
WORK ──(same action)──▶ GitLab discussions markdown ──▶ postbox mr-notes/mr-!N.md
HOME ──agent reads .mr-notes/ (auto-written when autoMrReview)──edits replies──▶ .mr-notes/replies-!N.md
HOME ──"Send review replies to work…"──▶ postbox mr-replies/mr-!N.md
WORK ──"Push MR replies to GitLab…"──▶ pulls mr-replies/* from postbox, posts to GitLab, uploads report mr-replies-status/mr-!N.md
HOME ──sees status──▶ (if notes missing) mr_notes_request → postbox mr-notes-request/mr-!N.md → WORK transfers threads
```

Home has no GitLab access; `mr_list` falls back to inventorying transferred `mr-notes/` blobs (`_mr_list_from_postbox`, `lgm_core/ops.py`).

Notes and replies travel through the file postbox as v3 relay payloads (AAD `lgm/v3/relay/postbox`): uploads carry the ephemeral pubkey in form field `k`, reads carry header `X-LGM-Epk`; the server opens, stores re-sealed under `relay.key`, and re-seals per reader. On a v3-pinned WORK machine none of this needs `SYNC_PASSWORD` (`sealPostboxPayload` / `fileSyncDownload` in `MirrorApi.kt`).

### 3. Corporate dependencies (gradle/npm)

```
HOME ──request──▶ manifest v3 (missing/present coords) encrypted ──▶ POST /api/documents/submit
WORK ──respond──▶ GET queue / queue-item ──scan local gradle+m2+npm caches──▶ ZIP publication ──▶ POST fulfill
HOME ──apply──▶ GET ready / ready-item ──unpack into ~/.gradle/caches/modules-2/files-2.1 etc.──▶ DELETE ack
HOME ──publish (vault)──▶ scan protected artifacts (LGM_PROTECTED_MAVEN_GROUPS) ──▶ POST /api/cache/publish (loopback-guarded data plane)
```

Server storage: `storage/.lgm/deps/<sha256(repo)[:16]>/requests/*.bin` and `.../responses/*.bin` (`backend/app/routers/deps.py`). Vault: `storage/.lgm/vault` (override `LGM_VAULT_PATH`). File postbox: `storage/.lgm/files/<repo>/<id>.{json,bin}` (`file_sync.py`), 7-day stale cleanup.

All relay blobs are stored as `0x04 || nonce[12] || AES-256-GCM(plaintext, relay_key, aad=PURPOSE_AAD)` — the server opens the client seal on write and re-seals for each reader; see Crypto. Deps uploads carry the ephemeral pubkey in form field `k` (manifest under AAD `deps/req`, response ZIP under `deps/resp`); downloads carry header `X-LGM-Epk`. V3 postbox uploads seal their routing metadata (`path`, `plain_size`) into form field `meta`, so the cleartext fields stay neutral. Vault publish (`POST /api/cache/publish`, `publish-npm`) is sealed to the server key with AAD `lgm/v3/relay/vault`; the server decrypts it here by design — gradle reads the vault unpacked from disk.

## Repository layout

| Path | Purpose |
| --- | --- |
| `run.py` | canonical launcher (`prod` default / `dev`); bootstraps `backend/venv`, ensures `cert.pem`/`key.pem`, optional in-process HTTP→HTTPS redirect thread |
| `start.bat` / `start.sh` | thin wrappers around `run.py` |
| `cli.py`, `dev.py`, `bridge_manager.py` | legacy launchers, superseded (see Divergence) |
| `backend/app/main.py` | FastAPI app: auth (`X-Session-ID` / `Authorization: Bearer`), router mounts, v3 key injection into routers (lifespan), dulwich git daemon on `GIT_PORT`, static SPA mount |
| `backend/app/routers/` | `sync` (bundle upload/export/check/link), `deps` (manifest submit/queue/fulfill/ready/ack), `file_sync` + `documents` prefix (postbox), `mirror` (vault cache data plane, loopback-guarded), `buffer`, `plugin` (dist info/latest), `system`, `repos`, `files`, `shared`, `settings`, `web`, `websocket` (`/ws/logs`, `/ws/files`), `git_http` (unmounted) |
| `backend/app/core/` | `repo_manager`, `git_handler` (dulwich TCP), `bundle_crypto`, `envelope_crypto`, `hybrid_crypto` (ECIES v3), `artifact_store`, `npm_cache`, `vault_backup`, `lan_beacon`, `logger`, `settings_manager`, `shared_manager`, `system_monitor`, `watcher` |
| `frontend/` | Vue 3 SPA; routes `/` Dashboard, `/files`, `/search`, `/buffer` (alias `/exchange`), `/history`, `/settings`; built to `frontend/dist`, served by backend |
| `idea-plugin/` | Kotlin IntelliJ plugin ("DocCache"); tool window tabs: Branches, Review, Dependencies, Exchange; secrets in PasswordSafe (`mirror.apiKey`, `mirror.syncPassword`, `gitlabToken`); pinned server pubkey in plain settings (`serverPubKeyB64` — not a secret); state file `doccache.xml` |
| `lgm_core/` | shared engine: `config.py` (.env resolution), `client.py` (HTTP), `crypto.py`, `ops.py` (REGISTRY), `render.py` |
| `lgm.py` | CLI wrapper over REGISTRY |
| `lgm_mcp.py` | MCP stdio server over REGISTRY |
| `storage/` | server data root (gitignored): bare repos, `workspaces/`, `.lgm/{deps,files,vault,buffer}`, key files `server_x25519.key` / `relay.key`, logs |
| `tests/` | pytest suite (`pytest.ini`, `integration` marker needs live server) |

## Operational constants

Ports (verified defaults):

| Constant | Value | Source |
| --- | --- | --- |
| `WEB_PORT` (prod HTTPS) | **443** | `run.py _web_port()`, `backend/app/main.py CONFIG`, `.env` |
| `GIT_PORT` (dulwich daemon) | **8444** | `main.py CONFIG`, `run.py` fallback |
| `cli.py PROD_PORT` | 8443 (sets `WEB_PORT=8443`, `GIT_PORT=8444` env before delegating to `run.py`) | `cli.py` |
| dev backend (`run.py dev`, `cli.py dev`, `dev.py`) | 8000 | `run.py`, `cli.py DEV_BACKEND_PORT`, `dev.py` |
| Vite dev frontend | 5173; proxy targets `https://localhost:443` | `cli.py`, `frontend/vite.config.js` |
| `REDIRECT_HTTP_PORT` | unset by default (redirect disabled) | `run.py` |

Env vars:

| Key | Meaning | Read by |
| --- | --- | --- |
| `BASE_URL` | mirror base URL for CLI/MCP (fallback `https://localhost:443`) | `lgm_core/config.py` |
| `API_KEY` | shared secret header (`X-Session-ID` and/or `Authorization: Bearer`) | `backend/app/main.py`, `lgm_core` |
| `SYNC_PASSWORD` | legacy AES-GCM envelope password (dumps/blobs/postbox); relay flows no longer need it on WORK — the server keeps it only to read/pre-serve legacy `0x01`/`L` blobs and for the legacy CLI/MCP | backend, `lgm_core`, plugin SecretsStore |
| `STORAGE_PATH` | server data root (`.env` currently points at `D:\Sources\kryptonit`) | `main.py CONFIG` |
| `GITLAB_URL` / `GITLAB_TOKEN` / `GITLAB_PROJECT` | GitLab MR trio for CLI/MCP (`client.py`); plugin uses Settings + PasswordSafe instead | `lgm_core/client.py` |
| `LGM_PROTECTED_MAVEN_GROUPS` | protected group prefixes, default `ru.kryptonite` | `ops.py op_publish`, `mirror.py`, `artifact_store.py` |
| `LGM_USE_ENV_<KEY>=1` | flip precedence so process env beats project `.env` for that key | `lgm_core/config.py cfg()` |
| `GRADLE_USER_HOME`, `JAVA_HOME` | scanning hints for deps ops | `ops.py` via `cfg()` |
| `LGM_VAULT_PATH`, `LGM_BUFFER_DIR/_MAX_ITEMS/_MAX_SIZE/_TTL_SECONDS`, `SILENT_GIT`, `OLLAMA_URL/_MODEL` | server-side tuning | `mirror.py`, `buffer.py`, `core/` |
| `LGM_M2_ALLOW_REMOTE` | open the loopback-guarded Maven data plane to non-loopback clients | `mirror.py _guard_data_plane()` |

Config precedence for CLI/MCP: project `.env` beats process env by default (the workstation may carry unrelated `API_KEY`s); explicit flags win over both; `LGM_USE_ENV_<KEY>` flips one key.

Postbox path prefixes (inside the encrypted file store): `mr-notes/`, `mr-replies/`, `mr-replies-status/`, `mr-notes-request/`. Local review directory on HOME: `<project>/.mr-notes/` (`MrNotesWriter.kt`). Plugin per-project sync state: `<project>/.localgitmirror/state/`.

Crypto: protocol v3 hybrid ECIES (X25519 + HKDF-SHA256 + AES-256-GCM). The plugin pins the home server's long-term X25519 pubkey (`GET /api/auth/pubkey`, fingerprint check, TOFU in `ensureServerKeyPinned`); every call seals to that key with a fresh ephemeral. Empty pin falls back to the legacy `SYNC_PASSWORD` envelope (`MirrorSettingsService.serverPubKeyB64`, `backend/app/core/hybrid_crypto.py`).

Relay flows (deps, file postbox, buffer, MR notes/replies, vault publish) — Option A: the server terminates the wire crypto. Write carries form field `k` (ephemeral pubkey, url-safe b64; JSON body field for buffer); the server opens via `HybridServerContext.open_relay` (HKDF label `lgm/v3/relay/req`) and stores plaintext re-encrypted at rest under an independent random key file `storage/.lgm/relay.key`: `0x04 || nonce[12] || AES-256-GCM(plaintext, relay_key, aad=PURPOSE_AAD)`. Read carries header `X-LGM-Epk`; the server decrypts at rest and re-seals to that ephemeral (`seal_relay`, label `lgm/v3/relay/resp`). Legacy `0x01`/`L` blobs are opened with `SYNC_PASSWORD` and re-sealed; 409 when the server has no password. Per-purpose AAD binds the GCM tag so a blob sealed for one purpose cannot replay as another: `lgm/v3/relay/deps/req`, `.../deps/resp`, `.../postbox`, `.../buffer`, `.../vault`. Branch bundles keep the envelope/bundle labels (`lgm/v3/env/*`, `lgm/v3/bundle/*`; upload attachment uses its own ephemeral in form field `kb`).

A WORK machine needs no `SYNC_PASSWORD` for deps respond, MR notes/replies, buffer, vault publish, or delete-ref/prune (envelope-only calls work with an empty password once the key is pinned). HOME keeps it only to read/pre-serve legacy blobs and for the legacy CLI/MCP (`lgm_core` speaks the password path exclusively).

Cross-language byte-compat (Python ↔ Kotlin) is gated by the KAT vector `backend/tests/vectors/v3_relay.json`, consumed by `backend/tests/test_hybrid_relay_kat.py` and the plugin's `HybridCryptoTest`. Server keys are created on first startup in the lifespan (`backend/app/main.py`): `storage/.lgm/server_x25519.key` (long-term X25519) and `storage/.lgm/relay.key` (at-rest, deliberately not derived from the wire key).

## Known divergence / dead code

Docs contradict code in these places. Code is authoritative.

- Four server launchers exist; only `run.py` is canonical (`start.bat` wraps it). `cli.py` (prod port 8443, aggressive `taskkill /F /IM uvicorn.exe` in `stop_all`), `dev.py` (hardcoded 8000/5173, ignores `.env`), `bridge_manager.py` (hardcoded `PORT = 8443`) are legacy/old paths.
- `README.md` is wrong throughout: claims web port 8000 and a native git-daemon on 8081 (`git remote add home git://…:8081`). Actual prod default is HTTPS 443; raw git smart HTTP is explicitly disabled (`main.py` comment "GIT SMART HTTP DISABLED"; `backend/app/routers/git_http.py` is not mounted). A dulwich TCP `GitHandler` on `GIT_PORT` 8444 is still started by `main.py` lifespan, but nothing in the README workflow uses it and no launcher starts a `git daemon` binary.
- `DEPS_PLAN.md` documents manifest **v1** and `/api/deps/*` endpoints. Code emits manifest **v3** (`{"version": 3, ..., "missing", "present"}`, `ops.py op_request`) over `/api/documents/submit|queue|queue-item|fulfill|ready|ready-item|ack` (`backend/app/routers/deps.py`).
- Dead i18n strings: `settings.v3.*` (14 keys) in `idea-plugin/src/main/resources/messages/LocalGitMirrorBundle*.properties` have zero references in Kotlin sources.
- Frontend i18n gaps: ~146 `t('…')` keys used in `frontend/src/**/*.vue` are absent from `frontend/src/locales/en.json` (e.g. `codeEditor.*`, `commits.*`, `commandPalette.*`, many `fileBrowser.*`); en/ru themselves have equal key counts (154), so the gap is locales-vs-usage, not en-vs-ru.
- `idea-plugin/README.md` describes the old MVP: tool window "bottom" (actual: right, id "DocCache"), tab list without Review/Deps/Exchange, mentions `../IDEA_PLUGIN_MVP.md` (that file actually lives at `docs/IDEA_PLUGIN_MVP.md`).
- `AGENTS.md` command block (`npm test`) has no root `package.json`; the Python suite runs via `pytest` per `pytest.ini`.
- `docs/INDEX.md` links several nonexistent files (`FILETREE_SUMMARY.md`, `PROJECT_SUMMARY.md`, `SUMMARY.md`, `TODO.md`, `CLEANUP_REPORT.md`).
- Naming drift: user-visible product name is **DocCache** (`plugin.xml` id/name/toolWindow/notificationGroup, MCP `serverInfo.name = "doccache-tools"`, FastAPI title "Document Cache Server") while repo/remote identity is LocalGitMirror. Both names refer to the same system.
- `.env` values checked into nothing (gitignored) but present in working tree contain live secrets (`API_KEY`, `SYNC_PASSWORD`); never copy them into docs, commits, or issues.

## Verification pointers

- Surfaces/actions/groups: `idea-plugin/src/main/resources/META-INF/plugin.xml`
- Tool window tabs: `LocalGitMirrorPanel.kt` (~line 1071, `tab.branches/review/deps/exchange`)
- Settings fields: `idea-plugin/.../settings/MirrorSettingsService.kt`
- Role detection: `idea-plugin/.../deps/RoleDetector.kt`
- Ops list: `lgm_core/ops.py` `REGISTRY` (lines ~2082-2305)
- MCP schema generation: `lgm_mcp.py _build_tool_schema / _list_tools`
- Router mounts and auth: `backend/app/main.py` (~lines 342-432)
- Relay crypto (server side): `backend/app/core/hybrid_crypto.py`, `_decrypt_incoming`/`_serve_blob` in `backend/app/routers/{deps,file_sync,buffer}.py`
- Relay crypto (client side): `idea-plugin/.../workkit/HybridCrypto.kt`, seal/download helpers in `idea-plugin/.../mirror/MirrorApi.kt`
- Cross-language KAT: `backend/tests/vectors/v3_relay.json` ↔ `backend/tests/test_hybrid_relay_kat.py` ↔ `HybridCryptoTest.kt`
- Deps transport contract: `backend/app/routers/deps.py` module docstring
