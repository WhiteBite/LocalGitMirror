# LocalGitMirror IntelliJ Plugin (MVP)

This directory contains the IntelliJ IDEA plugin **MVP** for the "Orchestrator".

See `../IDEA_PLUGIN_MVP.md` for the functional specification.

## Features (MVP)

Mirror sync:
- Send **current branch** to Mirror (generates dump + `POST /api/sync/upload-and-apply`)
- Send **selected branch** to Mirror
- Send **selected commits** to Mirror (via temporary branch + cherry-pick)
- Pull back changes from remote (`ff-only` or into new branch)

GitLab:
- Send **GitLab MR/PR code** to Mirror (fetch MR source branch, then sync)
- MR picker of open MRs (fallback to manual IID input)

Actions are available in:
- Tools menu
- VCS menu
- Project tree context menu

UI:
- Tool Window: **LocalGitMirror** (bottom side)
- Built-in actions: Send, Pull back, Test Mirror, Test GitLab, Settings
- Commit picker uses searchable multi-select dialog
- Status badges (Mirror/GitLab/Last sync) + persistent operation history

## Build / Run (local)

Prereqs:
- IntelliJ IDEA (Community/Ultimate)
- JDK 17+

From this directory:

```bash
./gradlew runIde
```

## Configuration

Open:
- Settings → Tools → LocalGitMirror (or search "LocalGitMirror")

Required for Mirror sync:
- Mirror Base URL (e.g. `https://192.168.0.104`)
- Mirror Repo (target repo on home Mirror)
- Sync Password (used by `backup_work_stealth.bat` / `stealth_dump_tool.py`; passed via env `SYNC_PASSWORD`, not command-line args)
- Mirror API Key (optional if `API_KEY` not set on server; header `X-Session-ID`)

Required for GitLab MR integration:
- GitLab Base URL
- GitLab Token (PAT)
- GitLab Project (id or `group/name`)

Optional Git transport settings:
- Git remote name (default `origin`)
- Pull-back default mode (`new-branch` or `ff-only`)

Plugin UI language:
- Explicit setting independent from IDE UI language: `auto | en | ru`

## Notes / Limitations

- Work-kit execution assumes Windows (`backup_work_stealth.bat`).
- GitLab API response parsing uses `kotlinx-serialization-json` (no regex parsing for MR metadata).
- Secrets are stored in IntelliJ Password Safe (Mirror API key, Sync password, GitLab token).
- Operation history (last events) is persisted in plugin state.

## Notes

- The plugin is intentionally thin and uses existing LocalGitMirror APIs.
- Transport proxy is handled by IDE/JVM configuration.

## Build artifact

Installable plugin ZIP is produced at:

`idea-plugin/build/distributions/localgitmirror-idea-plugin-<version>.zip`
