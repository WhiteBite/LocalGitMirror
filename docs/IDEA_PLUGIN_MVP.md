# IntelliJ IDEA Plugin MVP (Orchestrator)

## Goal

Provide a **one-click sync UX** in IntelliJ IDEA without replacing Git:

1. Generate encrypted dump (`dump_*.dmp`) from current project
2. Upload dump to Mirror backend
3. Trigger apply on Mirror
4. Show clear success/error status in IDE

## Extended MVP (code transport workflow)

In addition to syncing to Mirror, the plugin can optionally integrate with GitLab to:
- Detect **current branch** and sync it.
- Sync **a selected branch**.
- Sync **selected commits** (temporary branch + cherry-pick).
- Sync a **GitLab Merge Request / PR code** by fetching its source branch.
- Pull changes back from remote (`ff-only` or `new-branch`).

GitLab is the MVP target provider (GitHub can be added later).

## Non-goals

- No custom SCM implementation
- No gRPC requirement
- No 7-Zip dependency

## Runtime flow

1. User clicks `Tools -> LocalGitMirror -> Sync to Mirror`
2. Plugin validates configured Mirror URL/API key/repo/password
3. Plugin runs work-kit script in project root:
   - `backup_work_stealth.bat <password>`
4. Plugin finds latest `dump_<project>_*.dmp`
5. Plugin uploads to `POST /api/sync/upload-and-apply` with multipart:
   - `repo`
   - `dump_file`
6. Plugin shows notification with commit hash/message

## Settings

- Mirror Base URL (e.g. `https://192.168.0.104`)
- API Key
- Target Repo
- Sync Password
- Request timeout

### GitLab settings (for MR fetch)
- GitLab Base URL (e.g. `https://gitlab.company.local`)
- GitLab Token (PAT)
- GitLab Project (numeric id or `group/name`)
- Default target branch (e.g. `main`)

### Git transport settings
- Git remote name (default `origin`)
- Pull-back default mode (`new-branch` or `ff-only`)

## UX / Actions

Actions exposed in:
- Tools menu
- Git tool window / VCS context
- Project tree context menu

Planned actions:
- **Send Current Branch to Mirror**
- **Send Branch…** (pick from local branches)
- **Send Selected Commits…**
- **Send MR/PR…** (by MR IID)
- **Pull Back…**

## Repo scaffold

See `idea-plugin/` directory.

## Acceptance criteria

- Action visible and executable from IDEA Tools menu
- If settings missing -> actionable error
- On success -> notification with `repo`, `dump_file`, `commit`
- On failure -> backend message shown (no generic silent error)

## Status

- Mirror sync actions implemented:
  - Send Current Branch
  - Send Branch…
  - Send Selected Commits…
  - Pull Back…
- GitLab actions implemented:
  - Send GitLab MR/PR… (by IID)
  - ToolWindow MR picker: open MRs list with fallback to manual IID

Known limitations (MVP):
- Work-kit execution assumes Windows (`backup_work_stealth.bat`).
- No pagination UI for very large MR lists yet (current picker requests latest open MRs only).
- Secrets are stored in IntelliJ Password Safe (no plaintext token/password fields in persistent state).
- Commit picker is currently local recent commits (not full graph query).
