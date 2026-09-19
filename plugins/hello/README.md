# plugins/hello — the pilot widget plugin (minimal shape)

**Hello** is the MINIMAL compiled widget plugin: the reference shape every
other plugin starts from (git/sync are this same build with a bigger source
tree). The host downloads `releases/hello/<version>/plugin.jar`, verifies it
against the catalog's SHA-256 and loads it with `DexClassLoader`. Its KDoc
notes its future role as the app's Welcome/onboarding plugin.

| | |
|---|---|
| Catalog id | `hello` |
| Entry class | `repo.hello.HelloPlugin` (implements `app.pocketshell.widget.plugin.WidgetPlugin`) |
| Current version | `1.0.0` |
| Namespace | `repo.hello` |
| Source of truth | THIS directory |

## Source layout

- `HelloPlugin.kt` — entry point + a single-screen Compose application
  demonstrating the host surface a plugin gets: `HomeApplication`,
  `HomeAppContext`, `HomeTokens`, `TerminalTheme`.

## What a plugin compiles against

`compileOnly(files(rootProject.file("api/pocketshell-0.14.0-api.jar")))` —
the checked-in host API jar (see `api/PROVENANCE.md`) — plus the same
Compose stack the host ships. None of that is dexed into the artifact; at
runtime the host resolves it all through parent classloader delegation.

## Origin / history

Moved from the PocketShell app repository
(`plugins/hello/src/**` at pocketshell commit `94a2f72`, the M8.5
plugin-platform commit). Git history cannot follow across repositories — use
`git log --follow plugins/hello` **in the PocketShell checkout** for the
pre-move history.

## Build / publish (the worked example)

```bash
./gradlew :plugins:hello:assembleDebug
tools/build-plugin.sh hello 1.0.0                   # build + dex to releases/hello/1.0.0/plugin.jar
tools/build-plugin.sh hello 1.0.1 --update-catalog  # new version + pin sha256 in catalog.json
```
