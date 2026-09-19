# plugins/git — the Git widget (compiled plugin)

The **Git** Home Application as a compiled widget plugin: branch, dirty
state, ahead/behind for repositories in the Linux guest. Not a data
manifest — the probe, parser, presentation and the full Compose app are
compiled code that the host downloads (`releases/git/<version>/plugin.jar`),
verifies against the catalog's SHA-256 and loads with `DexClassLoader`.

| | |
|---|---|
| Catalog id | `git` |
| Entry class | `app.pocketshell.widget.git.GitPlugin` (implements `app.pocketshell.widget.plugin.WidgetPlugin`) |
| Current version | `2.0.0` |
| Namespace | `app.pocketshell.widget.git` |
| Source of truth | THIS directory |

## Source layout

- `GitPlugin.kt` — entry point; `create()` returns the `GitApp` HomeApplication
- `GitApp.kt` — the Compose application (card + full-screen app)
- `GitProbe.kt` — host-primitive probe execution
- `GitStatusParser.kt` — `git status --porcelain` parsing
- `GitPresentation.kt` — card layout

## Origin / history

Moved from the PocketShell app repository
(`plugins/git/src/**` at pocketshell commit `94a2f72`, the M8.5 plugin-platform
commit). Git history cannot follow across repositories — use
`git log --follow plugins/git` **in the PocketShell checkout** for the
pre-move history.

## Build / publish

```bash
./gradlew :plugins:git:assembleDebug :plugins:git:testDebugUnitTest
tools/build-plugin.sh git 2.0.0               # build + dex to releases/git/2.0.0/plugin.jar
tools/build-plugin.sh git 2.0.1 --update-catalog   # publish a new version + pin sha256 in catalog.json
```
