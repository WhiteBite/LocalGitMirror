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
- Mirror Repo (optional; auto-created by project name if empty)
- Sync Password (used by Kotlin-native AES-GCM dump/apply flow)
- Mirror API Key (optional if `API_KEY` not set on server; header `X-Session-ID`)

Required for GitLab MR integration:
- GitLab Base URL
- GitLab Token (PAT)
- GitLab Project (id or `group/name`)

Optional Git transport settings:
- Git remote name (default `origin`)
- Pull-back default mode (`new-branch` or `ff-only`)

## Stealth-first workflow

- **Send current** uses HTTP stealth transport: generate encrypted git bundle dump (`.dmp`) and `POST /api/sync/upload-and-apply`.
- **Stealth pull back** uses HTTP stealth transport: `POST /api/sync/export-dump` then apply locally.

### Smart sync (avoid full dumps)

Plugin attempts to avoid full dumps by:
- keeping per-project state under `.localgitmirror/state/` (per-branch and last-sent heads)
- using a negotiated pointer-only apply when the Mirror already has the target commit (`/api/sync/has-commits` + `/api/sync/apply-known`)
- generating incremental bundles from ancestor base when known

Plugin UI language:
- Explicit setting independent from IDE UI language: `auto | en | ru`

## Notes / Limitations

- Runtime send/apply paths are Kotlin-native (no .bat/.py dependency).
- GitLab API response parsing uses `kotlinx-serialization-json` (no regex parsing for MR metadata).
- Secrets are stored in IntelliJ Password Safe (Mirror API key, Sync password, GitLab token).
- Operation history (last events) is persisted in plugin state.

## Notes

- The plugin is intentionally thin and uses existing LocalGitMirror APIs.
- Transport proxy is handled by IDE/JVM configuration.

## Build artifact

Installable plugin ZIP is produced at:

`idea-plugin/build/distributions/localgitmirror-idea-plugin-<version>.zip`
