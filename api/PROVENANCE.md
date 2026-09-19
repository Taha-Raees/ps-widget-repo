# api/pocketshell-0.14.0-api.jar — provenance

| | |
|---|---|
| Artifact | `api/pocketshell-0.14.0-api.jar` |
| Built by | PocketShell Gradle task `:app:pluginClassesJar` |
| App version | `0.14.0-m8.5` (versionName) |
| Source commit | `94a2f72` — "feat(m8.5): the widget plugin platform …" (the code this jar was compiled from; commits `1644d64`, `ea0258f`, `28f9262` after it are docs-only) |
| Copied from | pocketshell checkout tip `28f9262346aacb85e60b21135aefec30b93d853d` at copy time |
| Copy date | 2026-09-20 |
| SHA-256 | `6d21d6d71a53636ff480b7b78c31a79313091232e1874064a77557930ee6e40f` |
| Contents | 1415 `.class` entries — the app's compiled classes: `WidgetPlugin`, `HomeApplication`, `HomeAppContext`, `HomeAppStateStore`, `HomeTokens`, `TerminalTheme`, `RuntimeState`, guest-exec classes, builtin widget apps (todo/notes), etc. |

## Why this jar exists here

Plugin modules compile against the HOST's classes (`compileOnly`), and at
runtime the host resolves them through parent classloader delegation — the
plugin dex carries only the widget's own classes. Checking the jar in makes
this repo a STANDALONE build: no PocketShell checkout required.

## Stale-class caveat

`:app:pluginClassesJar` jars the app's compiled classes incrementally. If
widget plugin sources ever lived under the app's own source sets, stale
`app.pocketshell.widget.git` / `...sync` classes could linger in the jar
after the sources moved out. **Verified at copy time: zero entries matching
`widget/git|sync|hello` — no stale plugin classes in this jar.** Even when
present, such entries are harmless for COMPILATION (each module's own
`src/main` provides the same classes fresh on its compile classpath, which
shadows the jar; the jar is compile/test classpath only, never dexed into
the artifact). Refresh after a clean regardless — see below.

## Refresh procedure

When the host API surface changes (new `WidgetPlugin` methods, new primitives,
theme/state additions), refresh the jar from a PocketShell checkout:

```bash
cd <pocketshell-checkout>
git pull                      # know your commit
./gradlew clean :app:pluginClassesJar   # CLEAN first — avoids the stale-class caveat above
cp app/build/plugin/app-classes.jar \
   <ps-widget-repo>/api/pocketshell-<new-app-version>-api.jar
# then point every plugins/*/build.gradle.kts at the new filename,
# rename this file (keep the old jar if any published release still
# compiles against it), and update this PROVENANCE table.
```

Rules:

- The jar filename MUST carry the app version it was built from.
- Record: app versionName, pocketshell commit, copy date, SHA-256.
- Plugins compiled against an older api jar keep working (the HOST at
  runtime is newer); a NEW jar must never drop symbols an unpublished
  module still compiles against without bumping those modules.
- Bump `minAppVersion` in a widget's catalog entry when it starts using
  API newer than older app versions provide.
