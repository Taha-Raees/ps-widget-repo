# plugins/sync — the Sync widget (compiled plugin)

The **Sync** Home Application as a compiled widget plugin: backup profiles
(source, destination, backend, last run outcome) with RUN NOW, path
completion and Android-storage destinations. Not a data manifest — the full
app is compiled code that the host downloads
(`releases/sync/<version>/plugin.jar`), verifies against the catalog's
SHA-256 and loads with `DexClassLoader`.

| | |
|---|---|
| Catalog id | `sync` |
| Entry class | `app.pocketshell.widget.sync.SyncPlugin` (implements `app.pocketshell.widget.plugin.WidgetPlugin`) |
| Current version | `2.0.0` |
| Namespace | `app.pocketshell.widget.sync` |
| Source of truth | THIS directory |

## Source layout

- `SyncPlugin.kt` — entry point; `create()` returns the `SyncApp` HomeApplication
- `SyncApp.kt` — the Compose application
- `SyncProbe.kt` — host-primitive probe execution
- `SyncProfile.kt` — `@Serializable` profile + persisted-store codec (kotlinx-serialization)
- `SyncRepository.kt` — owns the `sync_profiles` DataStore file
- `SyncPreviewParser.kt`, `SyncRunStats.kt` — preview/run-stats parsing

## Origin / history

Moved from the PocketShell app repository
(`plugins/sync/src/**` at pocketshell commit `94a2f72`, the M8.5 plugin-platform
commit). Git history cannot follow across repositories — use
`git log --follow plugins/sync` **in the PocketShell checkout** for the
pre-move history.

## Build / publish

```bash
./gradlew :plugins:sync:assembleDebug :plugins:sync:testDebugUnitTest
tools/build-plugin.sh sync 2.0.0               # build + dex to releases/sync/2.0.0/plugin.jar
tools/build-plugin.sh sync 2.0.1 --update-catalog   # publish a new version + pin sha256 in catalog.json
```
