# ps-widget-repo

PocketShell's widget distribution repository — and the **canonical source
home** of the downloadable compiled plugins. Home Applications marked
**installable** in the app download their manifests and plugin artifacts
from here: one place to develop, build, publish and update widgets outside
the app build, with a standalone Gradle build that needs NO PocketShell
checkout.

## Trust model

The catalog listing is **data, never code**: `catalog.json` and the
`widgets/*.json` manifests reference only built-in probe primitives
(`proc.net.listen`, `ssh.guest`, `storage.rootfs`) and card templates —
nothing in a manifest is executed.

Compiled plugins are the deliberate exception, and they are gated twice:

1. a plugin entry carries a `plugin` ref (`className`, `dex`, `sha256`) —
   the dex is a SEPARATE download, capped in size, and its bytes must hash
   to the catalog's SHA-256 BEFORE they are kept and at every load;
2. loaded with `DexClassLoader` into the widget sandbox — the plugin only
   gets the host surface it compiles against (`WidgetPlugin`,
   `HomeApplication`, `HomeAppContext`, `HomeAppStateStore`, `HomeTokens`,
   `TerminalTheme`, probe/guest-exec primitives) through parent classloader
   delegation. It cannot replace the shell, intercept input, or escape the
   host API.

## Layout

```
catalog.json                 the index the app fetches (HTTPS only)
widgets/*.json               data-manifest widgets (probe + card template)
plugins/git|sync|hello/      CANONICAL SOURCES of the compiled plugins
api/pocketshell-0.14.0-api.jar  host API jar the plugins compile against
api/PROVENANCE.md            where that jar came from + refresh procedure
releases/<id>/<version>/plugin.jar   published artifacts (classes.dex inside)
tools/build-plugin.sh        build + dex + publish one plugin
tools/validate-catalog.py    validate catalog.json (CI gate)
```

## The plugins

| id | entry class | version | sources |
|----|-------------|---------|---------|
| `git` | `app.pocketshell.widget.git.GitPlugin` | 2.0.0 | `plugins/git/` |
| `sync` | `app.pocketshell.widget.sync.SyncPlugin` | 2.0.0 | `plugins/sync/` |
| `hello` | `repo.hello.HelloPlugin` | 1.0.0 | `plugins/hello/` (minimal shape — start new plugins here) |

Each module has its own README. The plugins compile against the checked-in
host API jar (`api/pocketshell-0.14.0-api.jar`, provenance + refresh
procedure in `api/PROVENANCE.md`) and the same Compose stack the host ships
— none of that is dexed into the artifacts.

## Canonical workflow (verified)

One-time setup: create `local.properties` (git-ignored) with the Android SDK:

```
sdk.dir=/home/<you>/Android/Sdk
```

Requirements: JDK 17, Android SDK with platform `android-36` and
build-tools `36.1.0` (what the app itself builds with).

### Build + test

```bash
./gradlew :plugins:hello:assembleDebug :plugins:git:assembleDebug :plugins:sync:assembleDebug
./gradlew :plugins:git:testDebugUnitTest :plugins:sync:testDebugUnitTest
```

### Publish (build + dex + release + catalog pin)

```bash
tools/build-plugin.sh <id> <version> [--update-catalog]
```

does, in order:

1. `./gradlew :plugins:<id>:assembleDebug`
2. jars the module's own classes (`plugins/<id>/build/tmp/kotlin-classes/debug`)
3. dexes: `d8 --release --min-api 26 --lib <android-36.jar> --classpath
   <module-classes.jar> --output <dir> <jar>` → `classes.dex`
4. zips `classes.dex` → `releases/<id>/<version>/plugin.jar`, prints the
   SHA-256; with `--update-catalog` it pins `version`, `plugin.dex` and
   `plugin.sha256` in `catalog.json`.

Worked example (this is how the current `hello` artifact was produced):

```bash
tools/build-plugin.sh hello 1.0.0 --update-catalog
# ARTIFACT: releases/hello/1.0.0/plugin.jar
# SHA-256 : 4003ca4c6c7a98772862b2de17671fcd52cc699edbba1c31dead9ae22352479c
```

Notes:

- The `classes.dex` inside is deterministic: rebuilding from an unchanged
  checkout produces byte-identical dex (verified across a clean rebuild).
- Artifacts are not byte-identical across root-project renames — the Kotlin
  module name `ps-widget-repo.plugins:<id>_debug` is embedded in `@Metadata`
  (artifacts built in the PocketShell repo carry `PocketShell.plugins:…`).
- `plugin.jar` is a zip around `classes.dex`; zip timestamps vary per run,
  so the JAR sha changes on every publish even when the dex is identical.
  The app only ever checks sha-vs-catalog, which the publish step pins.

### Validate

```bash
python3 tools/validate-catalog.py
```

Checks ids, semver fields, required fields, path safety, artifact
existence and sha256-vs-bytes. Non-zero exit with reasons on any problem.
CI (`.github/workflows/validate.yml`) runs this plus the Gradle build and
unit tests on every push/PR.

## Troubleshooting

- **d8 prints `malformed kotlin.Metadata ... version 2.4.0, while maximum
  supported version is 2.2.0`** — benign. build-tools 36.1.0's embedded
  kotlin-metadata-jvm is older than Kotlin 2.4; the metadata is stripped in
  dex output anyway. Expect a few `Info:` lines and a
  `Warning: Unexpected error during rewriting of Kotlin metadata` stack
  trace per class; the dex is valid (the published artifacts were built
  with exactly these warnings).
- **`d8 not found` / `android.jar not found`** — build-plugin.sh expects
  `$SDK_DIR/build-tools/36.1.0/` and `$SDK_DIR/platforms/android-36/`;
  install via the SDK manager or fix `local.properties`.
- **`api/pocketshell-0.14.0-api.jar` out of date after host API changes** —
  refresh per `api/PROVENANCE.md` (clean `:app:pluginClassesJar` in a
  PocketShell checkout, copy in, update provenance). Stale plugin classes
  in that jar would be harmless for compilation (each module's own sources
  shadow the jar), but refresh clean anyway.
