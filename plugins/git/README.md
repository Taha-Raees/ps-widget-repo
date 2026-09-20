# plugins/git — the Git widget (compiled plugin)

The **Git** Home Application as a compiled widget plugin: a small-screen
Git workstation for repositories in the Linux guest — repository list,
per-repo Changes/History/Branches/Files/Remotes, diffs, commits, staging,
branch work, stash and sync verbs. Not a data manifest — the probe, the
operation layer, the parsers, the presentation and the full Compose app
are compiled code that the host downloads
(`releases/git/<version>/plugin.jar`), verifies against the catalog's
SHA-256 and loads with `DexClassLoader`.

| | |
|---|---|
| Catalog id | `git` |
| Entry class | `app.pocketshell.widget.git.GitPlugin` (implements `app.pocketshell.widget.plugin.WidgetPlugin`) |
| Current version | `3.0.0` (M9 information-architecture redesign: navigation stack + staged operations) |
| Namespace | `app.pocketshell.widget.git` |
| Source of truth | THIS directory |

## Source layout

- `GitPlugin.kt` — entry point; `create()` returns the `GitApp` HomeApplication
- `GitApp.kt` — the HomeApplication object: scan loop, operation engine, navigation stack, state holder
- `GitScreens.kt` — the screen model (Home → Repo tabs → Diff/Commit/Viewer/Stash), tab enum, op kinds, dialog map
- `GitChrome.kt` — the compact shared chrome (top bar, tabs, menu, banner, dialogs, op queue)
- `GitHomeScreen.kt` — Home: the repository launch list + honest whole-card states
- `GitRepoScreen.kt` — the repository surface: CHANGES / HISTORY / BRANCHES / FILES / REMOTES tabs
- `GitDetailScreens.kt` — diff, commit detail, file preview, stash screens
- `GitProbe.kt` — host-primitive probe execution (batched read-only scan + bounded read-only inspections)
- `GitOps.kt` — the operation layer: one confirmed tap = one bounded direct-argv git exec
- `GitStatusParser.kt` — `git status --porcelain` parsing
- `GitPresentation.kt` — pure presentation rules (grouping, glyphs, diff classification, caps)

## Origin / history

Moved from the PocketShell app repository
(`plugins/git/src/**` at pocketshell commit `94a2f72`, the M8.5 plugin-platform
commit). Git history cannot follow across repositories — use
`git log --follow plugins/git` **in the PocketShell checkout** for the
pre-move history.

## Build / publish

```bash
./gradlew :plugins:git:assembleDebug :plugins:git:testDebugUnitTest
tools/build-plugin.sh git 3.0.0               # build + dex to releases/git/3.0.0/plugin.jar
tools/build-plugin.sh git 3.0.1 --update-catalog   # publish a new version + pin sha256 in catalog.json
```
