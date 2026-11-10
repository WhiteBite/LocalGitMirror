# MR Review Workflow (snapshot-bound)

How an agent on HOME reviews a corporate GitLab MR whose code lives on WORK, using the
snapshot review subsystem. This is the workflow map; the normative contract (canonical
JSON, digests, batches, receipts, closed error dictionary) is
[MR_REVIEW_PROTOCOL.md](MR_REVIEW_PROTOCOL.md), and the authoritative op surface is the
`REGISTRY` in `lgm_core/ops.py` (frozen at 52 ops by `tests/test_ops_registry.py`).

Roles: **WORK** is the collector and the only publisher (it has GitLab access);
**HOME** is the reader/reviewer (no GitLab access, ever). Transport for every artifact
is the existing v3 encrypted file postbox — no cloud, no raw git protocol.

## 1. Prepare the snapshot (WORK)

```
mr_review_prepare {repo, iid, project, checkout, notes_only, work_dir}
```

`repo` and `iid` are required. `project` is the GitLab project path or numeric
id, while `checkout` is the local checkout path. A unique entry for `repo` in
the MR projects registry supplies omitted values; explicit arguments win.
Missing or ambiguous entries require explicit `project` and `checkout` values.

Collects one consistent snapshot of the MR — metadata, discussions and a self-contained
code bundle — and places the artifacts in the postbox: bundle first
(`mr-review/code/<code_id>.bundle`), transfer envelope last
(`mr-review/snapshots/<snapshot_id>.json`). Deduplicated by `code_id`; an aged bundle is
refreshed so HOME can still fetch it. `notes_only=true` skips the code artifact and
reports that as a limitation. The IDEA Review tab exposes the same action natively
("Prepare review snapshot", work PC only).

A registered **WORK full sync also prepares review snapshots**: `mr_sync_all` runs the
same preparation for every open MR of every registered project (extra GitLab calls and
postbox uploads); per-MR outcomes land in the result's `review_snapshots` key and never
fail the ordinary branch/notes sync. With `notes_only=true` no code is uploaded: the
envelope is refreshed only when the bundle is already in the postbox, otherwise the
snapshot is skipped with that limitation.

Each `repos[].mrs[]` item reports `branch_transfer` separately from `review`;
`branch` remains the source branch name. A blocked branch can have a prepared
review snapshot. `summary.review_status` describes snapshot preparation only:
`ready`, `incomplete`, `not-run` (dry-run), or `empty` (no open MRs).
`review_complete` is true only for `ready`; skipped or failed snapshots and an
unknown MR inventory prevent readiness. This does not mean HOME has imported
the snapshots, reviewed the code, or run checks. Ordinary sync `success` retains
its branch/notes meaning, independently of snapshot preparation failures.

Hard limit: one code artifact is capped at **128 MiB** before encryption
(`CODE_ARTIFACT_MAX_BYTES`); exceeding it fails with `review-package-too-large` —
there is no silent truncation.

## 2. Open: import + overview/inventory (HOME)

```
mr_review_open {repo, iid, snapshot_id, cursor, budget_bytes, home}
```

Imports the requested (or latest) **complete** snapshot from the postbox into the
persistent review store `~/.lgm/reviews/<repo-key>/` (override: `home` param or
`LGM_REVIEWS_HOME`) and returns its identity, limitations and the **first bounded
inventory page**. Subsequent inventory pages: call again with the page's `next_cursor`
— no postbox access after import, so an already-imported snapshot never depends on the
postbox TTL. A missing/expired bundle or digest drift returns a typed not-ready result
and leaves the current snapshot readable.

## 3. Bounded read / search / context (HOME)

```
mr_review_read    {repo, snapshot_id, view=diff|file|discussions, paths, group,
                   side=old|new|target, cursor, budget_bytes, context_lines, home}
mr_review_search  {repo, snapshot_id, query, side, scope=tree|added|removed|changed,
                   paths, cursor, budget_bytes, home}
mr_review_context {repo, snapshot_id, path, line, side, budget_bytes, context_lines, home}
```

- `read` pages the changed-file inventory (`view=diff`), literal file content of one
  side (`view=file`) or review threads (`view=discussions`); `paths`/`group` narrow one
  page. Reads come only from the snapshot's Git objects, **never from a worktree**.
- `search` is **literal text only** (no regex) over the snapshot objects.
- `context` returns a textual window around one line (`line=0` = file head); huge lines
  are split with byte continuation, never silently truncated.

Every page carries `complete` / `next_cursor` / totals / shown range. `budget_bytes` is
the upper bound of the **fully serialized** result in UTF-8 bytes: **4096..48000**
inclusive, default **24000** (violation: `invalid-budget`).

Honesty boundary: all context is **textual**. Search results carry `method="textual"`
and `semantic_complete=false` — the subsystem makes **no semantic completeness claim**.
`complete=true` means "no more bytes under this budget/cursor", not "you have seen
everything that matters".

## 4. Record progress (HOME)

```
mr_review_progress {repo, iid, action=list|mark, file, text, expected_revision, home}
```

Per-unit review state in the store. `action=mark` takes one JSON unit
(`{snapshot_id?, path, state, reason?, evidence?}`) via exactly one of `file`/`text`,
with compare-and-swap on `expected_revision` (0 inserts a fresh row); concurrent
writers get a typed `revision-conflict`, never a silent loss. Evidence must be a
structured handle (`cursor:` / `blob:` / `snapshot:` / `probe:<environment_id>:<probe_id>`);
free-text summaries are not evidence.

## 5. Optional environment + probe (HOME)

```
mr_review_materialize {repo, gitlab_url, target_project_id, action=create|status|release,
                       environment_id, purpose, fresh, snapshot_id, home}
```

`action=create` checks out the snapshot's exact `source_sha` as a **detached worktree**
under `environments/<environment_id>/` (one reusable clean environment per snapshot;
`fresh=true` creates an independent one). It never writes the ordinary mirror checkout;
a dirty or busy environment is never reset (typed refusals); `release` is explicit and
clean-only.

The **probe is not an op** — there is no probe entry in the REGISTRY. You run your own
local command with the environment directory as cwd; captured stdout/stderr land under
`probes/<environment_id>/` in the store, and progress marks cite them as
`probe:<environment_id>:<probe_id>`.

Honesty boundary: the worktree isolates **files only** — processes, network and caches
are NOT isolated. It is **not a security sandbox**; run only probes you would run in
your own checkout. Preparation runs no hooks, no build, no submodule update, no LFS
fetch; gitlinks and LFS pointer files are reported as `incomplete-checkout`
limitations, never as a fully ready environment.

Windows note: every git call inside an environment is pinned with
`-c core.autocrlf=false`. Restore and cleanup inside an environment must use the same
pinned config — a plain `git checkout -- <file>` under a global `core.autocrlf=true`
smudges the file to CRLF and the environment keeps reporting `dirty`.

## 6. Findings (HOME)

```
mr_review_findings {action=save|list|create_batch, file, text, expected_revision, home}
```

Revisioned findings (`kind = anchored | thread | general`) with CAS on
`expected_revision`. `action=create_batch` validates the selection and writes the v2
publication batch artifact `mr-replies/mr-!<iid>-<batch_id>.review.json` consumed by
`mr_replies_send review_file=`. Only revisions with `verification=confirmed` and
`applicability=current` may enter a batch; all items of one batch share the same
`snapshot_id`/`code_id`/`version_fingerprint` identity triple. Batches are immutable
and never supersede each other.

JSON input (exactly one of `file`/`text`, mirroring §4): `action=list` takes one query
`{repo, gitlab_url, target_project_id, iid}` plus optional `cursor` (integer offset)
and `limit` (page size, max 50). `action=create_batch` takes
`{repo, gitlab_url, target_project_id, iid, items}` where each item is
`{finding_id, revision, kind, thread_id?, resolve?, body?}` selecting one stored
revision. `action=save` takes the full finding document (identity + complete
`revisions` history) with CAS on `expected_revision`.

## 7. Check / send (HOME)

```
mr_replies_send {repo, iid, file, text, review_file, check, force, project}
```

`review_file=<batch.json>` sends a v2 batch (exclusive with `file`/`text`);
`file`/`text` remain the legacy v1 markdown-draft path (`## thread <id>` /
`## new <file>:<line>` / `## new` sections, optional `resolve: yes`). `check=true`
validates grammar + precheck without uploading; `force=true` skips the
duplicate-anchor/unknown-thread precheck. Sending only uploads to the encrypted
postbox — **HOME never talks to GitLab**.

## 8. Publish / receipt (WORK)

```
mr_replies_push    {repo, iid, dry_run}
mr_review_receipts {action=handoff|import, repo, iid, dry_run}
```

`mr_replies_push` (or the IDEA publication dialog) is the **only** surface that posts
to GitLab, and it runs on WORK only. A v2 batch is gated: the live MR fingerprint
(source sha, target tip, `diff_refs` → `version_fingerprint`) is re-read fresh
immediately before posting; **any drift since the snapshot refuses publication**
(`stale-snapshot`, zero posts/resolves, the batch stays pending). Publication is
per-entry journalled; invisible exactly-once markers make duplicate retries never
repost.

Afterwards `action=handoff` (WORK) uploads the immutable per-(batch, attempt) receipt
artifact to `mr-review/receipts/` and acks the batch item only after a successful
upload of an all-terminal attempt; `action=import` (HOME) stores receipt artifacts in
the review store, appends finding delivery revisions and acks only after a full
import. `mr_replies_status` shows the publish reports.

## Stale handling

- **Publication drift**: the fingerprint gate above blocks the push; nothing is
  auto-re-anchored.
- **Review drift**: after `mr_review_open` imported a newer snapshot, run
  `mr_review_refresh {repo, iid, previous_snapshot_id, home}` — it migrates review
  progress (unchanged units carry their state; changed units fall back to `delivered`)
  and invalidates stale findings. A finding is stale when its latest revision differs
  from the current snapshot in **any** of `snapshot_id`, `code_id`,
  `version_fingerprint`; stale findings cannot be published until re-checked. The
  current pointer is never moved by refresh.

## Privacy

Code bundles, snapshot envelopes, v2 batches and receipts travel as v3 relay-sealed
payloads through the same encrypted file postbox as notes/replies (re-sealed at rest
under the server's `relay.key`). No cloud service is involved anywhere; HOME has no
GitLab credentials and no GitLab route.

## Legacy notes origin

`.mr-notes/mr-!N.md` markdown drafts (`MrNotesSync` / `mr_sync_all` / `mr_notes`) are
the **legacy v1** surface: human-readable thread notes plus markdown replies. v2
review runs on findings/batches in the review store; the two coexist, and a v2 batch
is never accepted as legacy markdown (nor vice versa).

Code snippets inside v1 notes come from `git show refs/heads/<source branch>:<path>` in
the WORK checkout. When that revision is not available locally (branch missing, file
absent at the revision, blank branch metadata), the notes render an explicit
`**Контекст:** недоступен` marker instead — the live worktree is **never** substituted,
so a note snippet is either revision-true or honestly absent (identical behavior in
`lgm_core/mr_notes_render.py` and the plugin's `MrNotesWriter`).

## Role split for review publication

| Side | Ops |
| --- | --- |
| WORK | `mr_review_prepare`, `mr_replies_push`, `mr_review_receipts action=handoff` (+ IDEA prepare/publish actions) |
| HOME | `mr_review_open` / `read` / `search` / `context` / `progress` / `refresh` / `materialize` / `findings`, `mr_replies_send`, `mr_review_receipts action=import` |

The op registry is frozen at **52 ops** — nine `mr_review_*` plus `mr_review_receipts`,
the read-only `mr_status` (WORK) and the HOME-side `mr_overview` on top of the legacy
MR cycle (`_FROZEN_OPS` in
`tests/test_ops_registry.py`).
