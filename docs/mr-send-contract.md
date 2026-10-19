# MR-send dedup contract (Kotlin plugin ↔ Python CLI)

The IntelliJ plugin (Kotlin) and the CLI/MCP (`lgm_core/ops_mr.py`, Python) both
decide which GitLab MR branches travel to the mirror in one `mr_send` transfer.
They cannot share code, so parity is enforced by ONE shared fixture executed by
BOTH test suites:

| Piece | Path |
| --- | --- |
| Shared fixture | `tests/fixtures/mr_send_scenarios.json` |
| Python runner | `tests/test_mr_send_contract.py` (drives `op_mr_send` with faked git/HTTP) |
| Kotlin runner | `idea-plugin/src/test/kotlin/localgitmirror/idea/gitlab/MrSendPlannerTest.kt` (drives the pure `MrSendPlanner.planSend`) |

Any change to the dedup decision on either side must update the fixture in the
same commit; both suites must stay green.

## Invariants

Grounded in `lgm_core/ops_mr.py` (`op_mr_send`, `_local_tip`, `_mirror_refs_safe`,
`_existing_shas`, `send_exclusions`, `send_branches` in `ops_git.py`) — the
authoritative implementation — and mirrored by
`idea-plugin/.../gitlab/MrSendPlanner.kt`:

1. **Tip-equal skip.** A branch whose local tip (`git rev-parse refs/heads/<b>`)
   equals the mirror's tip for the same branch name is SKIPPED: it never appears
   in `sent` and is never bundled. A mirror ref with an empty or missing sha
   never skips anything. A branch missing from the local tips is an error
   (Python: `LgmError` from `_local_tip`; Kotlin: `IllegalArgumentException`).
   This is the ONLY skip rule: a branch whose tip differs from its own mirror
   ref is ALWAYS sent, even when every one of its commits already exists on the
   mirror under a different ref — the bundle also carries the ref update, and
   without it the mirror's ref for the branch would stay stale forever (the
   next sync would see the same objects-covered state and skip again).
2. **One bundle.** All branches that survive the skip check are packed into a
   SINGLE git bundle (`send_branches`), never one bundle per branch, so shared
   history is packed once.
3. **Exclusions.** The bundle's `^`-prerequisites are the mirror tips that exist
   in the local object database (`git cat-file -e <sha>^{commit}`): deduplicated,
   non-empty, in mirror-refs order — MINUS the tips of the branches being sent
   (`send_exclusions(mirror_tips, sent_tips, existing_locally)`). Two reasons:
   mirror SHAs the local repo lacks ("ghost" tips) would make
   `git bundle create` fail on an unknown `^sha`; and an exclusion equal to a
   sent branch's own tip makes git refuse the bundle outright
   ("Refusing to create empty bundle", exit 128) because every commit of that
   branch is covered by the exclusion. Exclusions are computed only when at
   least one branch is sent.
4. **Nothing new.** Only when every requested branch is tip-equal is the result
   "nothing new": `sent` is empty, every branch is `skipped` in request order,
   and no bundle is uploaded. `exclude_shas` is empty whenever nothing is sent.

## Fixture schema

`tests/fixtures/mr_send_scenarios.json` is a JSON array; each entry:

| Field | Type | Meaning |
| --- | --- | --- |
| `name` | str | scenario id, used as the test id by both runners |
| `mirror_refs` | `{branch: {"sha": str}}` | what `sync_refs` reports from the mirror |
| `local_tips` | `{branch: sha}` | local `refs/heads` tips after the fetch |
| `existing_shas` | `[sha]` | SHAs the local repo has (`cat-file -e` succeeds) |
| `branches` | `[branch]` | requested branches, deduplicated, in order |
| `expected.sent` | `[branch]` | branches that must travel |
| `expected.skipped` | `[branch]` | branches that must not |
| `expected.exclude_shas` | `[sha]` | the bundle `^`-exclusions; empty when nothing is sent |

## Scenario list

| Scenario | Exercises |
| --- | --- |
| `fresh-repo-sends-all` | mirror has no refs → full send, no exclusions |
| `tip-equal-to-mirror-is-skipped` | invariant 1, single branch |
| `new-tip-on-known-branch-sends-incremental` | invariant 3, incremental bundle `^old1` |
| `mixed-skip-and-send` | invariants 1+3 together; exclusions follow mirror-refs order |
| `multi-branch-one-bundle-dedup-exclusions` | invariants 2+3; two mirror refs on one sha dedup to one exclusion |
| `ghost-mirror-sha-not-excluded` | invariant 3 negative: unknown mirror sha is not an exclusion |
| `zero-new-commits-stale-ref-is-sent` | invariant 1: objects exist on the mirror under another ref, the branch's own mirror ref is stale → the branch IS sent (ref update must ship) |
| `zero-new-commits-sends-non-tip-equal-branches` | invariants 1+4: only the tip-equal branch is skipped |
| `all-tips-equal-nothing-new` | invariant 4: every branch tip-equal |
| `sent-tip-shared-with-other-mirror-ref-is-not-excluded` | invariant 3: the sent branch's own tip (shared with another mirror ref) never becomes an exclusion — git would refuse the empty pack |

## Runners

Python (`python -m pytest -q tests/test_mr_send_contract.py`): builds a fake git
and a fake mirror client from the scenario (stubbing pattern of
`tests/test_mr_send_multi.py`), calls `op_mr_send` with `--branch`, and asserts
`sent`/`skipped` plus the exact single `git bundle create` argument vector
(`refs/heads/<sent>` then `^<exclude>`) and the upload count.

Kotlin (`gradle test --console=plain` in `idea-plugin/`): `MrSendPlannerTest`
resolves the fixture by walking up from the test working directory to the repo
root, parses it with kotlinx-serialization, and asserts `MrSendPlanner.planSend`
returns the same `sent`/`skipped`/`excludeShas`.

`MrSendPlanner` is pure — no IDE, no git, no network. The caller supplies mirror
refs, local tips and locally-present SHAs, and gets the send decision. `GitLabMrSender` (the plugin's current MR flow: per-branch checkout +
full sync, no mirror-tip dedup) does not call the planner yet; converging the
plugin's send path onto the planner is a separate later step — this contract is
the gate that keeps the two implementations from silently diverging in the
meantime.
