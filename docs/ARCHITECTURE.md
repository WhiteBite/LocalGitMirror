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
| CLI | `python lgm.py <command>` | Scriptable subset: everything in `lgm_core/ops.py REGISTRY` (23 ops) rendered to text/JSON | HOME only (owner runs it there; WORK uses MCP) |
| MCP | `python lgm_mcp.py` (stdio JSON-RPC) | Same 23 ops as the CLI, exposed as agent tools generated from the same REGISTRY; `serverInfo.name = "doccache-tools"` | HOME (mainly); optionally WORK |

CLI and MCP are functionally identical because both are generated from `lgm_core/ops.py REGISTRY`; the plugin implements its own richer flows in Kotlin (`lgm_core` has no buffer or generic file-postbox ops; its MR ops ride the same postbox).

## Machine roles

`MirrorSettingsService.State.machineRole` = `auto` | `home` | `work`. `auto` (default) resolves via `idea/deps/RoleDetector.kt`: host parsed from `baseUrl` is loopback or literally matches a local interface address → HOME; anything else or any failure → WORK (safe default). Result cached per settings change.

What each role enables (defaults from `MirrorSettingsService.State`):

| Setting (default) | HOME | WORK |
| --- | --- | --- |
| `autoRequestDeps` (true) | detect unresolvable corporate deps, post manifest | off by role logic |
| `autoApplyDeps` (true) | unpack received dep responses into local caches | n/a |
| `autoRespondDeps` (false) | n/a | poll pending requests, ship artifacts from local cache |
| `autoMrReview` (true) | auto-write incoming `mr-notes/mr-!N.md` into `.mr-notes/` | re-upload changed mr-notes (`MrNotesSync`), push replies to GitLab when `autoPushReplies` |
| `autoPushReplies` (false) | n/a | push pending replies automatically instead of only notifying about them |
| `depsPollSec` (300) | shared poll interval (±30% jitter, 15-min fast window, backoff to 4x) | same |

Startup activities (`plugin.xml`): `PullCheckStartupActivity`, `DepsAutomationStartupActivity`, `MrReviewAutoStartupActivity`, `LgmToolWindowPlacementActivity`. Role gating lives in `DepsAutomationService` and `MrReviewAutoService`.

Topology note: HOME is where the server runs, so HOME's plugin detects role `home` naturally (loopback baseUrl). WORK's baseUrl points at the home machine's LAN IP → role `work`.

## Data flows (directional)

Branch bundles stay opaque to the server (envelope + bundle crypto, sealed to the pinned server key). Relay flows (deps, file postbox, buffer, MR notes/replies, vault publish) carry v3 ECIES sealed to the home server's pinned X25519 pubkey; the server terminates that crypto on write and stores plaintext re-encrypted at rest under an independent `relay.key` (see Crypto). The clipboard buffer rides `/api/buffer/*` with the same relay scheme. The file postbox is v3-only (no password fallback); deps and buffer still accept the legacy password path for unpinned clients.

### 1. Branch bundles (send / pull)

```
WORK ──plugin "Send current"/"Send branch…"──▶ POST /api/documents/upload (encrypted git bundle) ──▶ mirror bare repo + workspace checkout
HOME ──plugin "Sync pull…"/lgm pull──▶ POST /api/documents/check (which tips mirror has) ──▶ incremental bundle download ──▶ git fetch locally
HOME ──edits, builds──▶ sends back the same way; WORK pulls via "Pull back…"
```

Negotiation: `check` reports known commits; `link` applies pointer-only updates when the target commit already exists (`apply-known` semantics in `backend/app/routers/sync.py`). Incremental bundles use mirror tips as `^` prerequisites (`send_branches` in `lgm_core/ops_git.py`).

### 2. GitLab MR review loop

```
WORK ──"Send MR branch to Cache…"──▶ git fetch origin <MR source branch> ──one deduplicated bundle──▶ mirror
WORK ──MrNotesSync (auto poll / Review tab)──▶ renders every open MR's discussions ──▶ postbox mr-notes/mr-!N.md (only when the content hash changed)
HOME ──agent reads .mr-notes/ (auto-written when autoMrReview)──edits replies──▶ .mr-notes/replies-!N.md
HOME ──"Send review replies to work…"──▶ postbox mr-replies/mr-!N.md
WORK ──"Push MR replies to GitLab…"──▶ pulls mr-replies/* from postbox, posts to GitLab (exactly-once markers), uploads report mr-replies-status/mr-!N.md
```

Notes transfer is independent of branch sync: `MrNotesSync` (`idea/gitlab/MrNotesSync.kt`) renders discussions for all open MRs and uploads `mr-notes/mr-!N.md` only when the rendered markdown hash changed since the last upload (per-iid hash in project state). It runs from the `MrReviewAutoService` WORK-side poll (jittered 60 s) and from the Review tab. The old `mr-notes-request` back-channel is gone — notes refresh is push-from-work, not pull-on-demand.

Home has no GitLab access; `mr_list` falls back to inventorying transferred `mr-notes/` blobs (`_mr_list_from_postbox`, `lgm_core/ops_mr.py`).

Notes, replies, and status reports travel through the file postbox as v3 relay payloads (AAD `lgm/v3/relay/postbox`) — see the postbox contract below. No `SYNC_PASSWORD` is involved anywhere in this loop: uploads are sealed by `MirrorCrypto.sealPostboxPayload`, downloads opened by `MirrorPostboxApi.fileSyncDownload` (plugin) or `lgm_core/relay_crypto.py RelaySession` (CLI/MCP).

### 3. Corporate dependencies (gradle/npm)

```
HOME ──request──▶ manifest v3 (missing/present coords) encrypted ──▶ POST /api/documents/submit
WORK ──respond──▶ GET queue / queue-item ──scan local gradle+m2+npm caches──▶ ZIP publication ──▶ POST fulfill
HOME ──apply──▶ GET ready / ready-item ──unpack into ~/.gradle/caches/modules-2/files-2.1 etc.──▶ DELETE ack
HOME ──publish (vault)──▶ scan protected artifacts (LGM_PROTECTED_MAVEN_GROUPS) ──▶ POST /api/cache/publish (loopback-guarded data plane)
```

Server storage: `storage/.lgm/deps/<sha256(repo)[:16]>/requests/*.bin` and `.../responses/*.bin` (`backend/app/routers/deps.py`). Vault: `storage/.lgm/vault` (override `LGM_VAULT_PATH`). File postbox: `storage/.lgm/files/<sha256(repo)[:16]>/<id>.{json,bin}` (`file_sync.py`), 7-day stale cleanup.

All relay blobs are stored at rest as `0x04 || nonce[12] || AES-256-GCM(plaintext, relay_key, aad=PURPOSE_AAD)` — the server opens the client seal on write and re-seals for each reader; see Crypto. Deps and buffer accept both modes: uploads carry the ephemeral pubkey in form field `k` (deps) or JSON field `k` (buffer), downloads carry header `X-LGM-Epk`; without the v3 fields the server falls back to the legacy `SYNC_PASSWORD` bundle. Vault publish (`POST /api/cache/publish`, `publish-npm`) is sealed to the server key with AAD `lgm/v3/relay/vault`; the server decrypts it here by design — gradle reads the vault unpacked from disk.

### 4. File postbox (v3-only)

`backend/app/routers/file_sync.py` — repo-scoped encrypted file store under `/api/documents/attachment-*`:

- Upload (non-loopback): multipart fields `rid`, `k` (url-safe b64 ephemeral pubkey), `meta` (base64 of the relay-sealed routing metadata `{"path", "plain_size"}`), and the relay-sealed `attachment`. There are no cleartext path fields — `path_enc` is gone. The server opens the sealed meta, validates and resolves the real relative path, and stores it in the item's `*.json` sidecar; the blob is decrypted and stored re-sealed at rest under `relay.key`.
- Read: header `X-LGM-Epk` is required (400 without it); the server decrypts at rest and re-seals to that ephemeral.
- Loopback exception: loopback clients — the SPA served on the HOME host, a fully trusted machine — may omit `k`/`meta` and send legacy-shaped `path` + `plain_size` fields with a plaintext attachment (`frontend/src/stores/postbox.js`); reads without `X-LGM-Epk` return plaintext. Non-loopback clients without the v3 fields fail closed.
- No password path exists for the postbox: a client without the pinned server key cannot use it at all.

## Repository layout

| Path | Purpose |
| --- | --- |
| `run.py` | canonical launcher (`prod` default / `dev`); bootstraps `backend/venv`, ensures `cert.pem`/`key.pem`, optional in-process HTTP→HTTPS redirect thread |
| `start.bat` / `start.sh` | thin wrappers around `run.py` |
| `cli.py`, `dev.py`, `bridge_manager.py` | legacy launchers, superseded (see Divergence) |
| `backend/app/main.py` | FastAPI app: auth (`X-Session-ID` / `Authorization: Bearer`, fail-closed 503 when `API_KEY` is unset), router mounts, v3 key injection into routers (lifespan), dulwich git daemon on `GIT_PORT`, static SPA mount |
| `backend/app/routers/` | `auth` (health / auth-verify / pubkey), `sync` (bundle upload/export/check/link), `deps` (manifest submit/queue/fulfill/ready/ack), `file_sync` + `documents` prefix (postbox), `mirror` (vault cache data plane, loopback-guarded), `buffer`, `plugin` (dist info/latest), `system` (status/config/connection-info/logs), `repos`, `files`, `shared`, `settings`, `web`, `websocket` (`/ws/logs`, `/ws/files`), `git_http` (unmounted), `_rid` (repo identifier resolution) |
| `backend/app/core/` | `repo_manager`, `git_handler` (dulwich TCP), `git_bundle` (bundle/workspace helpers for the sync API), `sync_envelope` (v3/password envelope transport), `bundle_crypto`, `envelope_crypto`, `hybrid_crypto` (ECIES v3 + at-rest relay sealing), `artifact_store` (CAS), `artifact_publication` (publication ZIP import into CAS), `mirror_dataplane` (loopback guard + wanted-queue misses), `npm_cache`, `vault_backup`, `corporate_tools`, `git_utils`, `gradle_init_script`, `lan_beacon`, `logger`, `settings_manager`, `shared_manager`, `system_monitor`, `watcher`, `i18n` |
| `frontend/` | Vue 3 SPA; routes `/` Dashboard, `/files`, `/search`, `/buffer` (alias `/exchange`), `/history`, `/settings`; built to `frontend/dist`, served by backend |
| `idea-plugin/` | Kotlin IntelliJ plugin ("DocCache"); see module map below; secrets in PasswordSafe (`mirror.apiKey`, `mirror.syncPassword`, `gitlab.token`); pinned server pubkey in plain settings (`serverPubKeyB64` — not a secret); state file `doccache.xml` |
| `lgm_core/` | shared engine: `config.py` (.env resolution), `client.py` (HTTP + v3 relay postbox client), `crypto.py` (legacy password bundles), `relay_crypto.py` (ECIES v3 relay client, byte-compatible with the server and the plugin), `ops.py` (REGISTRY) split across `ops_deps.py` / `ops_git.py` / `ops_mr.py` / `ops_vault.py` + `deps_scanner.py` / `op_models.py`, `render.py` |
| `lgm.py` | CLI wrapper over REGISTRY |
| `lgm_mcp.py` | MCP stdio server over REGISTRY |
| `storage/` | server data root (gitignored): bare repos, `workspaces/`, `.lgm/{deps,files,vault,buffer}`, key files `server_x25519.key` / `relay.key`, logs |
| `tests/`, `backend/tests/` | pytest suites: `tests/` for CLI/MCP/ops (`pytest.ini`, `integration` marker needs live server), `backend/tests/` for the server (incl. the cross-language KAT vector) |

Plugin module map (`idea-plugin/src/main/kotlin/localgitmirror/idea/`):

| Package | Contents |
| --- | --- |
| `mirror/` | per-domain API objects: `MirrorAuthApi` (capabilities, TOFU key pinning), `MirrorSyncApi`, `MirrorDepsApi`, `MirrorPostboxApi`, `MirrorBufferApi`, `MirrorVaultApi`, `MirrorPluginApi`; shared `MirrorTransport` (HTTP/multipart), `MirrorCrypto` (v3/password codec, relay sealing), `MirrorConnectionContract`. The old monolithic `MirrorApi.kt` is gone. |
| `sync/`, `sync/v2/` | `SyncEngine` + `SyncNegotiation` / `SyncExport` / `SyncApply` / `SyncPlanning` / `SyncPorts` / `SyncModels` / `SyncFacadeService` / `RepoResolver`, plus `HandshakeCache`, `SyncStateStore`, `SyncOrchestrator`, `SyncLogger` |
| `ui/` | `LocalGitMirrorPanel` (tool window, tabs Branches/Review/Deps/Exchange) composed from `PanelSetup` / `PanelConfig` / `PanelChrome` / `PanelBundleActions` / `PanelSyncActions` / `PanelDiagnostics`, tab files `BranchesTab` / `ReviewTab` / `DepsTab` / `ExchangeTab`, dialogs |
| `ui/exchange/` | `ExchangeStore` (persistent received files under `<project>/.doccache/exchange`), `ExchangeFeed`, `ExchangeItem`, `ExchangeRender`, `ExchangeTransfer`, `ChatTransferHandler`, `BubbleRenderer` |
| `gitlab/` | `GitLabApi`, `GitLabConfig`, `MrNotesSync` (work-side notes transfer), `MrNotesWriter` (`.mr-notes/` rendering), `MrReplies` (reply file format), `MrRepliesTransport` (postbox upload), `MrReplyPushService` (work-side push + status reports), `MrReviewService`, `MrReviewAutoService`, `MrReviewDialog`, `MrSendPlanner` |
| `deps/` | `DepsAutomationService`, `DepsPollScheduler`, `RoleDetector`, gradle side (`GradleEcosystem`, `GradleResolver`, `MavenLocalScanner`, `DepsScanner`, …), npm side (`NpmEcosystem`, `NpmCache`, `NpmLockfiles`, `NpmRegistryProbe`, `NpmRepack`), `DepsBundler`, `PublishMirrorAction`, `VaultCacheSync`, `YarnMirror` |
| `workkit/` | crypto primitives: `HybridCrypto` (ECIES v3), `EnvelopeCrypto`, `BundleCrypto`, `ExchangeCrypto`, `RepoFileSyncCrypto`, `NativeBundleBuilder`, `BundleImporter` |

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
| `API_KEY` | shared secret header (`X-Session-ID` and/or `Authorization: Bearer`); fail-closed — unset means every protected route answers 503 | `backend/app/main.py`, `lgm_core` |
| `SYNC_PASSWORD` | legacy AES-GCM envelope password (dumps, deps/buffer blobs); the file postbox never uses it; the server keeps it only to read/pre-serve legacy `0x01`/`L` blobs and for password-mode clients | backend, `lgm_core`, plugin SecretsStore |
| `STORAGE_PATH` | server data root (`.env` currently points at `D:\Sources\kryptonit`) | `main.py CONFIG` |
| `GITLAB_URL` / `GITLAB_TOKEN` / `GITLAB_PROJECT` | GitLab MR trio for CLI/MCP (`client.py`); plugin uses Settings + PasswordSafe instead | `lgm_core/client.py` |
| `LGM_PROTECTED_MAVEN_GROUPS` | protected group prefixes, default `ru.kryptonite` | `ops_vault.py op_publish`, `artifact_publication.py` (used by `mirror.py`) |
| `LGM_PROTECTED_NPM_SCOPES` | protected npm scope prefixes for vault publication | `artifact_publication.py` (used by `mirror.py`) |
| `LGM_USE_ENV_<KEY>=1` | flip precedence so process env beats project `.env` for that key | `lgm_core/config.py cfg()` |
| `GRADLE_USER_HOME`, `JAVA_HOME` | scanning hints for deps ops | `lgm_core` deps scanning via `cfg()` |
| `LGM_VAULT_PATH`, `LGM_BUFFER_DIR/_MAX_ITEMS/_MAX_SIZE/_TTL_SECONDS`, `SILENT_GIT`, `OLLAMA_URL/_MODEL` | server-side tuning | `mirror.py`, `buffer.py`, `core/` |
| `LGM_M2_ALLOW_REMOTE` | open the loopback-guarded Maven data plane to non-loopback clients | `mirror_dataplane.py _guard_data_plane()` |

Config precedence for CLI/MCP: project `.env` beats process env by default (the workstation may carry unrelated `API_KEY`s); explicit flags win over both; `LGM_USE_ENV_<KEY>` flips one key.

Security/ops behavior:

- `API_KEY` unset → `get_api_key` raises 503 on every protected route (fail closed; startup prints a warning). A wrong key returns 404, not 403, to hide the server from scanners.
- `GET /api/connection-info` (`backend/app/routers/system.py`) returns `api_key` / `sync_password` only to loopback clients; non-loopback peers get `api_key_set` / `sync_password_set` booleans and a config template with the secret fields blanked.
- The postbox is relay-sealed end to end: no shared password participates in MR notes/replies or file transfer, and the server never sees a postbox password because there is none.
- The Maven/npm data plane (`/api/cache/m2/*`, `/api/cache/npm/*`, `/api/cache/gradle-init`) is loopback-guarded (`mirror_dataplane.py`); misses on protected groups are recorded into the `wanted` queue.

Postbox path prefixes (inside the encrypted file store): `mr-notes/`, `mr-replies/`, `mr-replies-status/`. Local review directory on HOME: `<project>/.mr-notes/` (`MrNotesWriter.kt`). Plugin per-project sync state: `<project>/.localgitmirror/state/`.

Crypto: protocol v3 hybrid ECIES (X25519 + HKDF-SHA256 + AES-256-GCM). The plugin pins the home server's long-term X25519 pubkey (`GET /api/auth/pubkey`, fingerprint check, TOFU in `MirrorAuthApi.ensureServerKeyPinned`); every call seals to that key with a fresh ephemeral. Empty pin falls back to the legacy `SYNC_PASSWORD` envelope for sync/deps/buffer/vault (`MirrorSettingsService.serverPubKeyB64`, `backend/app/core/hybrid_crypto.py`) — but not for the postbox, which requires the pin.

Relay flows (deps, file postbox, buffer, MR notes/replies, vault publish) — the server terminates the wire crypto. Write carries the ephemeral pubkey (form field `k`; JSON body field for buffer; plus relay-sealed `meta` for postbox uploads); the server opens via `HybridServerContext.open_relay` (HKDF label `lgm/v3/relay/req`) and stores plaintext re-encrypted at rest under an independent random key file `storage/.lgm/relay.key`: `0x04 || nonce[12] || AES-256-GCM(plaintext, relay_key, aad=PURPOSE_AAD)`. Read carries header `X-LGM-Epk`; the server decrypts at rest and re-seals to that ephemeral (`seal_relay`, label `lgm/v3/relay/resp`). Deps and buffer also accept legacy `0x01`/`L` password blobs (opened with `SYNC_PASSWORD` and re-sealed; 409 when the server has no password); the postbox does not. Per-purpose AAD binds the GCM tag so a blob sealed for one purpose cannot replay as another: `lgm/v3/relay/deps/req`, `.../deps/resp`, `.../postbox`, `.../buffer`, `.../vault`. Branch bundles keep the envelope/bundle labels (`lgm/v3/env/*`, `lgm/v3/bundle/*`; upload attachment uses its own ephemeral in form field `kb`).

A WORK machine needs no `SYNC_PASSWORD` for deps respond, MR notes/replies, buffer, vault publish, or delete-ref/prune (envelope-only calls work with an empty password once the key is pinned). HOME keeps it only to read/pre-serve legacy blobs and for password-mode clients. `lgm_core` speaks v3 relay for the postbox (`relay_crypto.py`, server pubkey via `/api/auth/pubkey`) and the password path for deps/vault/buffer.

Cross-language byte-compat (Python ↔ Kotlin) is gated by the KAT vector `backend/tests/vectors/v3_relay.json`, consumed by `backend/tests/test_hybrid_relay_kat.py` and the plugin's `HybridCryptoTest`. Server keys are created on first startup in the lifespan (`backend/app/main.py`): `storage/.lgm/server_x25519.key` (long-term X25519) and `storage/.lgm/relay.key` (at-rest, deliberately not derived from the wire key).

## Known divergence / dead code

Docs contradict code in these places. Code is authoritative.

- Four server launchers exist; only `run.py` is canonical (`start.bat` wraps it). `cli.py` (prod port 8443, aggressive `taskkill /F /IM uvicorn.exe` in `stop_all`), `dev.py` (hardcoded 8000/5173, ignores `.env`), `bridge_manager.py` (hardcoded `PORT = 8443`) are legacy/old paths.
- `DEPS_PLAN.md` documents manifest **v1** and `/api/deps/*` endpoints. Code emits manifest **v3** (`{"version": 3, ..., "missing", "present"}`, `ops_deps.py op_request`) over `/api/documents/submit|queue|queue-item|fulfill|ready|ready-item|ack` (`backend/app/routers/deps.py`).
- Dead i18n strings: `settings.v3.*` (14 keys) in `idea-plugin/src/main/resources/messages/LocalGitMirrorBundle*.properties` have zero references in Kotlin sources.
- `idea-plugin/README.md` describes the old MVP: tool window "bottom" (actual: right, id "DocCache"), tab list without Review/Deps/Exchange, mentions `../IDEA_PLUGIN_MVP.md` (that file actually lives at `docs/IDEA_PLUGIN_MVP.md`).
- `AGENTS.md` command block (`npm test`) has no root `package.json`; the Python suite runs via `pytest` per `pytest.ini`.
- `docs/INDEX.md` links several nonexistent files (`FILETREE_SUMMARY.md`, `PROJECT_SUMMARY.md`, `SUMMARY.md`, `TODO.md`, `CLEANUP_REPORT.md`).
- Naming drift: user-visible product name is **DocCache** (`plugin.xml` id/name/toolWindow/notificationGroup, MCP `serverInfo.name = "doccache-tools"`, FastAPI title "Document Cache Server") while repo/remote identity is LocalGitMirror. Both names refer to the same system.
- `.env` values checked into nothing (gitignored) but present in working tree contain live secrets (`API_KEY`, `SYNC_PASSWORD`); never copy them into docs, commits, or issues.

## Verification pointers

- Surfaces/actions/groups: `idea-plugin/src/main/resources/META-INF/plugin.xml`
- Tool window tabs: `LocalGitMirrorPanel.kt` (`tab.branches/review/deps/exchange` addTab calls)
- Settings fields: `idea-plugin/.../settings/MirrorSettingsService.kt`
- Role detection: `idea-plugin/.../deps/RoleDetector.kt`
- Ops list: `lgm_core/ops.py` `REGISTRY`
- MCP schema generation: `lgm_mcp.py _build_tool_schema / _list_tools`
- Router mounts and auth: `backend/app/main.py` (`get_api_key`, `app.include_router` block)
- Postbox v3 contract: `backend/app/routers/file_sync.py` module docstring; client side `MirrorCrypto.sealPostboxPayload` + `MirrorPostboxApi` (plugin), `lgm_core/relay_crypto.py` + `client.py` file-sync methods (CLI/MCP)
- Loopback guards: `backend/app/core/mirror_dataplane.py` (`_is_loopback`, `_guard_data_plane`); connection-info secrets: `backend/app/routers/system.py get_connection_info`
- Relay crypto (server side): `backend/app/core/hybrid_crypto.py`, `_decrypt_incoming`/`_serve_blob` in `backend/app/routers/{deps,file_sync,buffer}.py`
- Relay crypto (client side): `idea-plugin/.../workkit/HybridCrypto.kt`, sealing helpers in `idea-plugin/.../mirror/MirrorCrypto.kt`
- MR notes transfer: `idea-plugin/.../gitlab/MrNotesSync.kt`; reply push + status reports: `gitlab/MrReplyPushService.kt`, `gitlab/MrRepliesTransport.kt`
- Cross-language KAT: `backend/tests/vectors/v3_relay.json` ↔ `backend/tests/test_hybrid_relay_kat.py` ↔ `HybridCryptoTest.kt`
- Deps transport contract: `backend/app/routers/deps.py` module docstring
