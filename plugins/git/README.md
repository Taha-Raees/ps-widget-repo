# plugins/git — the Git workstation (compiled plugin)

The **Git** Home Application as a compiled widget plugin: a Git-FIRST
workstation for the repositories in the PocketShell Linux guest. Not a
data manifest — the probe, parsers, mutation seam and the full Compose
app are compiled code the host downloads
(`releases/git/<version>/plugin.jar`), verifies against the catalog's
SHA-256 and loads with `DexClassLoader`.

| | |
|---|---|
| Catalog id | `git` |
| Entry class | `app.pocketshell.widget.git.GitPlugin` (implements `app.pocketshell.widget.plugin.WidgetPlugin`) |
| Current version | `3.1.0` |
| Namespace | `app.pocketshell.widget.git` |
| Source of truth | THIS directory |

## What it does

- **Dashboard** (the minimized card): every repository under the guest
  home at a glance — branch, ahead/behind, dirty dot, one batched
  read-only probe, idle-gated.
- **Workspace** (the maximized page): eight tabs — Overview, Changes,
  History, Branches, Files, Remotes, Repo, GitHub.
- **Core Git in the GUI**: stage/unstage/discard (file + bulk), commit
  composer with amend, stash push/pop/drop, branch
  switch/create/rename/delete/merge/rebase, fetch / pull (ff-only) /
  push (+set-upstream), cherry-pick, revert, reset (soft/mixed/hard),
  detach, tags, worktrees, identity config — EVERY mutation argv-only,
  confirmed when destructive, followed by a forced rescan.
- **Deep reads**: windowed history + text graph + reflog, file browser
  with search, blame, per-file history, capped diffs with true hunk
  numbering, commit detail, worktrees, submodules, LFS, sparse cone.
- **GitHub layer** (optional): rides `gh` in the guest — presence/auth
  status, PRs (open / view / create / merge / squash), issues (open /
  create / close), Actions runs, releases, notifications. Tokens stay
  inside gh's own store. No GitHub remote, no gh, or no auth → honest
  states with a terminal handoff.
- **Terminal escape hatch** on every screen (`WidgetNav.openTerminal`).

## Source layout

- `GitPlugin.kt` — entry point; `create()` returns the `GitApp` HomeApplication
- `GitApp.kt` — the application object, the ONE guest exec seam, the nav
  stack, read slots and the op pipeline (`GitState`)
- `GitLayout.kt` — COMPACT / ROOMY / FULL responsive tiers
- `GitComponents.kt` — shared rows, sections, tabs, states, dialogs
- `GitScreens.kt` — dashboard + the workspace's tabs
- `GitDetails.kt` — diff / commit / file drill-downs
- `GitHostUi.kt` — the GitHub tab + PR page
- `GitHost.kt` — the provider seam + GitHub-over-`gh` implementation
- `GitProbe.kt` — the batched read-only dashboard probe
- `GitReads.kt` — per-screen bounded reads (`GitReader`)
- `GitOps.kt` — THE mutation seam: argv-only ops + confirm policy
- `GitFiles.kt` / `GitDiffParser.kt` / `GitQuoted.kt` / `GitStatusParser.kt` /
  `GitPresentation.kt` — pure, JVM-tested models and rules
- `PLAN.md` — the engineering plan this grew from

## Origin / history

Moved from the PocketShell app repository
(`plugins/git/src/**` at pocketshell commit `94a2f72`, the M8.5 plugin-platform
commit). Git history cannot follow across repositories — use
`git log --follow plugins/git` **in the PocketShell checkout** for the
pre-move history. 2.0.0 was the read-only overview card; 3.0.0/3.1.0 are the
workstation (3.1.0 supersedes a parallel 3.0.0 build).

## Build / publish

```bash
./gradlew :plugins:git:assembleDebug :plugins:git:testDebugUnitTest
tools/build-plugin.sh git 3.1.0               # build + dex to releases/git/3.1.0/plugin.jar
tools/build-plugin.sh git 3.0.1 --update-catalog   # publish a new version + pin sha256 in catalog.json
```
