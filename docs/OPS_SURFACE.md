# LGM op surface

Generated from `lgm_core/ops.py REGISTRY` (52 ops). `Side` is the machine the op is
meant for; `needs_client=no` ops touch only the local review store.

| Op | Params | needs_client | Side |
| --- | --- | --- | --- |
| `scan` | gradle_home, filter, verbose | no | any |
| `pending` | repo* | yes | any |
| `request` | repo*, project*, npm_scopes, dry_run | yes | any |
| `respond` | repo*, project, dry_run, id | yes | any |
| `apply` | repo*, project, npm_install, yarn, yarn_install, dry_run, id | yes | any |
| `fetch-poms` | repo_url, dry_run | no | any |
| `publish` | dry_run | yes | any |
| `debug` | repo* | yes | any |
| `status` | - | yes | any |
| `repos` | - | yes | any |
| `branches` | repo*, project | yes | any |
| `send` | repo*, branch, project*, dry_run | yes | any |
| `pull` | repo*, branch, since, haves, project, dry_run | yes | any |
| `preview_pull` | repo*, since, project | yes | any |
| `has_commits` | repo*, commits, project | yes | any |
| `deps_request` | repo*, manifest* | yes | any |
| `vault_status` | - | yes | any |
| `branch_delete` | repo*, branches* | yes | any |
| `prune` | repo*, bases, older_days, keep, apply | yes | any |
| `mr_list` | repo | yes | any |
| `mr_send` | iid, iids, all_open, branch, project*, repo*, dry_run | yes | WORK |
| `mr_notes` | repo*, iid | yes | any |
| `mr_replies_send` | repo*, iid*, file, text, force, project, check, review_file | yes | HOME |
| `mr_replies_status` | repo*, iid | yes | any |
| `mr_replies_push` | repo*, iid, dry_run | yes | WORK |
| `mr_pending` | repo* | yes | any |
| `mr_status` | repo*, iid, project | yes | WORK |
| `mr_merge` | repo*, iid*, confirm*, project, when_pipeline_succeeds, remove_source_branch, sha | yes | WORK |
| `mr_overview` | repo, home | yes | HOME |
| `mr_review_receipts` | action*, repo*, iid, dry_run | yes | any |
| `mr_review_prepare` | repo*, iid*, project, checkout, notes_only, work_dir | yes | WORK |
| `mr_review_open` | repo*, iid*, snapshot_id, cursor, budget_bytes, home | yes | HOME |
| `mr_review_read` | repo*, snapshot_id*, view*, paths, group, side, cursor, budget_bytes, context_lines, home | no | HOME |
| `mr_review_search` | repo*, snapshot_id*, query*, side, scope, paths, cursor, budget_bytes, home | no | HOME |
| `mr_review_context` | repo*, snapshot_id*, path*, line, side, budget_bytes, context_lines, home | no | HOME |
| `mr_review_progress` | repo*, iid*, action, file, text, expected_revision, home | no | HOME |
| `mr_review_refresh` | repo*, iid*, previous_snapshot_id, home | no | HOME |
| `mr_review_materialize` | repo*, gitlab_url*, target_project_id*, action*, environment_id, purpose, fresh, snapshot_id, home | no | HOME |
| `mr_review_findings` | action*, file, text, expected_revision, home | no | HOME |
| `guide` | - | no | any |
| `update` | - | yes | any |
| `chat_send` | text* | yes | any |
| `chat_list` | count | yes | any |
| `file_send` | path*, repo* | yes | any |
| `file_list` | repo* | yes | any |
| `file_get` | repo*, id*, save | yes | any |
| `file_ack` | repo*, id* | yes | any |
| `config_get` | section | yes | any |
| `config_set` | section*, key*, value* | yes | any |
| `mr_projects_scan` | root* | no | any |
| `mr_projects_list` | - | no | any |
| `mr_sync_all` | dry_run, notes_only | yes | WORK |

`*` = required parameter.

## Summaries

- **scan** - Scan local gradle cache for cached artifacts.
- **pending** - List pending dependency requests on the Mirror server.
- **request** - Build a manifest v3 from a project and POST it to Mirror.
- **respond** - Find requested deps in local cache and ship them to Mirror.
- **apply** - Download and unpack a deps response into the local cache.
- **fetch-poms** - Fetch missing poms from a public Maven repository.
- **publish** - Scan local caches for protected artifacts, encrypt, and publish to Mirror vault.
- **debug** - Full diagnostics: env vars, cache roots, mirror connectivity.
- **status** - Server capabilities + repos + configured machine role.
- **repos** - List repositories on the mirror.
- **branches** - List all branch tips on the mirror for a repo.
- **send** - Create a git bundle locally and send it to the mirror.
- **pull** - Pull an encrypted git bundle and fast-forward local branches and the current working tree.
- **preview_pull** - Lightweight incoming-commits check: asks the mirror whether a pull would bring anything (no bundle transfer).
- **has_commits** - Ask the mirror which of the given commits it already holds (inspect before send).
- **deps_request** - Post a pre-built encrypted manifest file to /api/documents/submit.
- **vault_status** - Vault diagnostics: inventory, conflicts, wanted positions.
- **branch_delete** - Delete one or more branches on the mirror (comma-separated).
- **prune** - List (dry-run) or delete merged/stale branches on the mirror.
- **mr_list** - List open GitLab merge requests; without GitLab config falls back to MR notes transferred via the mirror.
- **mr_send** - Fetch GitLab MR branches and send them to the mirror in one deduplicated bundle (skips tips the mirror already has).
- **mr_notes** - Read MR discussion notes from the mirror postbox (mr-notes/mr-!N.md); newest per MR. Each thread block carries its 'ID треда' and 'Место: file:line' — copy the id into a '## thread <id>' section of mr_replies_send.
- **mr_replies_send** - Upload agent MR review replies to the mirror postbox; the work machine posts them to GitLab. Reply markdown sections: '## thread <id>' — answer an existing thread (id from mr_notes), '## new <file>:<line>' — new code-anchored discussion, '## new' — general MR note; 'resolve: yes' as the first line of a thread section resolves it. The '# MR !N' header is injected when missing. For a v2 review batch (mr_review_findings create_batch JSON) pass review_file instead of file/text; it is validated by the same pipeline and uploaded to mr-replies/mr-!<iid>-<batch_id>.review.json. Comments must read as a human reviewer's: issues only (bug / bad call / miss), 1-3 sentences, anchored at the offending line — no praise, no code restating, no filler.
- **mr_replies_status** - Show the work PC's publish reports for agent MR replies (posted/failed per MR).
- **mr_replies_push** - Publish pending mr-replies files from the mirror postbox to GitLab (work side, headless twin of the plugin's push): thread replies, resolve flags, anchored discussions with a general-note fallback when the line is outside the MR diff, exactly-once markers, a status report to mr-replies-status/, and a postbox ack only when every reply reached a terminal outcome. Publishes exactly what the replies file contains.
- **mr_pending** - One pipeline view per MR: notes freshness (mtime + age), replies pending in the postbox, the last publish outcome and the closed state with evidence (gitlab | marker | note-mtime | expired).
- **mr_status** - Read-only WORK-side merge readiness for open MRs: every blocker at once (state, draft, detailed_merge_status, conflicts, pipeline, approvals) with a ready flag; never merges or approves.
- **mr_merge** - Merge one MR on the work machine. Refuses without confirm=true and on any state/draft/conflict blocker; optionally waits for the pipeline, guards on the head SHA and removes the source branch. Never merges in a batch.
- **mr_overview** - HOME: one compact cross-repo MR queue in a single call — per MR the notes freshness, pending replies, last publish outcome and whether its review snapshot is imported, plus totals. Reads only transferred notes and the review store; no GitLab call, no claim that a review is done.
- **mr_review_receipts** - Detailed v2 review publication receipts: action=handoff (work) uploads the immutable per-(batch, attempt) receipt artifact for every completed journal attempt to mr-review/receipts/ and acks the batch item only after a successful upload of an all-terminal attempt; action=import (home) stores receipt artifacts in the review store, appends finding delivery revisions and acks the artifact only after a full import.
- **mr_review_prepare** - WORK: collect one consistent MR review snapshot (metadata, discussions and a self-contained code bundle) and place its artifacts in the encrypted postbox: bundle first, transfer envelope last. Deduplicated by code_id; an aged bundle is refreshed so HOME can still fetch it.
- **mr_review_open** - HOME: import the requested (or latest) complete snapshot of an MR from the postbox into the persistent review store and return its identity, limitations and the first bounded inventory page. Call again with the page's next_cursor for subsequent inventory pages (no postbox access); an already-imported snapshot never depends on the postbox TTL. A missing/expired bundle or digest drift returns a typed not-ready result and leaves the current snapshot readable.
- **mr_review_read** - HOME: read one bounded page of an imported snapshot: view=diff (changed files with old/new coordinates and OIDs), view=file (literal file content of one side) or view=discussions (review threads). Pages carry complete/next_cursor/totals/shown range; reads come only from the store's Git objects, never a worktree. This is the bounded snapshot reader, not the legacy mr_notes full-text dump.
- **mr_review_search** - HOME: bounded literal search over an imported snapshot's Git objects (never a worktree, no regex): scope=tree searches whole file contents, added/removed/changed search the corresponding changed lines. Stable hits carry path/side/line/column plus a reader handle; pages carry complete/next_cursor/totals.
- **mr_review_context** - HOME: bounded textual context around one line of an imported snapshot's side (line=0 returns the file head window); huge lines are split with byte continuation, never silently truncated.
- **mr_review_progress** - HOME: list or mark per-unit review progress of one MR in the review store. action=list pages the current (or requested) snapshot's units with their states (optional JSON query via file/text: {snapshot_id?, cursor?, limit?}); action=mark writes one unit state (JSON input {snapshot_id?, path, state, reason?, evidence?} in exactly one of file/text) with compare-and-swap on expected_revision.
- **mr_review_refresh** - HOME: compare the previous installed snapshot of one MR with the current one and migrate review progress plus finding applicability (unchanged units carry their state over; changed units fall back to delivered; stale findings are invalidated). Run after mr_review_open imported a newer snapshot; the current pointer is never moved.
- **mr_review_materialize** - HOME: manage detached worktree environments at a snapshot's exact source_sha for local probes: action=create (one reusable clean environment per snapshot; fresh=true creates an independent one), action=status (clean/dirty/busy/missing plus tracked-diff digest and untracked/ignored summaries), action=release (explicit removal of a clean environment). A dirty or busy environment is never reset.
- **mr_review_findings** - HOME: save, list or batch-select revisioned review findings in the review store. action=save appends one finding revision (full finding document via file/text, CAS on expected_revision); action=list pages the findings of one identity; action=create_batch validates the selection and writes a v2 review batch artifact for mr_replies_send review_file.
- **guide** - How to transfer code and corporate deps between work and home via the Mirror.
- **update** - Download and install the latest CLI tools (lgm.py, lgm_mcp.py, lgm_core/) from the mirror.
- **chat_send** - Send a chat message to the other machine via the exchange buffer.
- **chat_list** - Read chat messages from the exchange buffer (newest first).
- **file_send** - Send a file to the other machine via the encrypted postbox.
- **file_list** - List files in the postbox for a repo.
- **file_get** - Download a file from the postbox.
- **file_ack** - Delete one postbox item on the mirror by id (ack).
- **config_get** - Read server settings (all, or a specific section).
- **config_set** - Update a server setting for debugging/reconfiguration.
- **mr_projects_scan** - Register local GitLab checkouts found under a root directory for mr_sync_all.
- **mr_projects_list** - Show the registered MR-sync projects (repo, path, GitLab project).
- **mr_sync_all** - Sync every open MR of every registered project to the mirror: branches (mirror delta) + notes (content-hash delta); a full sync on WORK also prepares review snapshots.

## Sides

WORK ops need corporate GitLab access (`mr_send`, `mr_replies_push`, `mr_status`,
`mr_merge`, `mr_review_prepare`, `mr_sync_all`; `mr_review_receipts action=handoff`). HOME ops read
transferred notes and the review store (`mr_review_*`, `mr_replies_send`, `mr_overview`).
Every other op talks to the mirror server or the local filesystem.

## WORK agent: why an MR will not merge

Run `mr_status --repo <repo> --json` (read-only). For each MR with `ready=false`, report
its `blockers` verbatim (`not_approved`, `conflict`, `ci_must_pass`, `ci_still_running`,
`need_rebase`, `discussions_not_resolved`, `draft`) plus `pipeline.status` and the
`approvals` counts. LGM never merges or approves.

Prompt template:

> On the WORK machine run `mr_status --repo <repo> --json`. List every open MR as a row:
> iid, ready, blockers, pipeline status, approvals (approved/required), detailed_merge_status.
> Flag any MR whose branch is `ahead of origin` or `diverged from origin` — those branches
> are not transferred; report the reason verbatim and never pull or rebase automatically.

On HOME, `mr_overview --json` gives the cross-repo queue (notes freshness, pending replies,
last publish, imported review) in one call.
