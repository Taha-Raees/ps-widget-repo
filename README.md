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
api/pocketshell-api.jar     host API jar the plugins compile against
api/PROVENANCE.md            where that jar came from + refresh procedure
releases/<id>/<version>/plugin.jar   published artifacts (classes.dex inside)
tools/build-plugin.sh        build + dex + publish one plugin
tools/validate-catalog.py    validate catalog.json (CI gate)
```

## The plugins

| id | entry class | version | sources |
|----|-------------|---------|---------|
| `git` | `app.pocketshell.widget.git.GitPlugin` | 3.0.0 | `plugins/git/` |
| `sync` | `app.pocketshell.widget.sync.SyncPlugin` | 2.0.0 | `plugins/sync/` |
| `hello` | `repo.hello.HelloPlugin` | 1.0.0 | `plugins/hello/` (minimal shape — start new plugins here) |

Each module has its own README. The plugins compile against the checked-in
host API jar (`api/pocketshell-api.jar`, provenance + refresh
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
- **`api/pocketshell-api.jar` out of date after host API changes** —
  refresh per `api/PROVENANCE.md` (clean `:app:pluginClassesJar` in a
  PocketShell checkout, copy in, update provenance). Stale plugin classes
  in that jar would be harmless for compilation (each module's own sources
  shadow the jar), but refresh clean anyway.

## Versions & updates

Plugin versions are INDEPENDENT of the PocketShell app version:

- PocketShell app: `0.14.0-m8.5` (the host)
- Git plugin: `3.0.0` · Sync plugin: `2.0.0` · Hello plugin: `1.0.0`

PocketShell compares the catalog entry's `version` against the installed
plugin's version (semver, compared major→minor→patch). A newer catalog
version makes the Widget catalog row show **Update**; tapping it
re-downloads the artifact, re-verifies the SHA-256 and replaces the
installed record. Same-version entries show **Installed** — there is no
downgrade path in the UI (remove the widget, then install an explicitly
older release if ever needed).

What a version bump means (policy):

- **patch** — fixes inside the widget, no behavior surface change.
- **minor** — new widget functionality or template fields.
- **major** — behavior or compatibility change; check `minAppVersion`.

`minAppVersion` gates installation: a plugin whose minimum app version is
newer than the running PocketShell is refused with the reason shown in
the Control Center. The plugin API itself (`WidgetPlugin`, the
`HomeApplication` contract, theme tokens) evolves only with app releases
— that stability is what lets plugins update independently.

## Debugging an install failure

Install/update failures surface as a status line in the Widget catalog
section of Control Center (Settings → Home applications). The reasons are
the loader's/downloader's real causes — work through them in order:

- `HTTP <code>` — the catalog or artifact URL failed; check the file
  exists in the repo at the catalog's `plugin.dex` path.
- `sha256 mismatch (got … expected …)` — the artifact bytes differ from
  the catalog pin; re-run `tools/build-plugin.sh <id> <version>
  --update-catalog` to re-pin, or the repo was updated inconsistently.
- `refusing … plugin record` — the record failed shape validation
  (bad id/className/dex path); fix the catalog entry.
- `minAppVersion` refusal — the plugin needs a newer app; update
  PocketShell first.

Installed records and artifact files live in the app's private storage
(`files/plugins/`); "Remove" deletes both. If a row still renders after
removal, it is the carousel configuration (Control Center → On Home → ✕),
not the plugin.

## Licenses

- PocketShell is **GPL-3.0-only**; plugins compile against PocketShell
  host classes and are therefore linked works — **this repo's plugin
  sources are GPL-3.0-compatible** (GPL-3.0-or-later for your own
  widgets).
- The data manifests and card templates are configuration for PocketShell's
  renderer; attribute what you reuse and do not import third-party code
  without checking its license.
- The vendored terminal modules in PocketShell remain GPLv3 (pinned
  upstream — see PocketShell `docs/THIRD_PARTY.md`).

## Developing your first widget

The fastest path is copying `plugins/hello/` — it is the minimal working
shape (one manifest-free plugin module: `WidgetPlugin` implementation +
one Compose card):

1. `cp -r plugins/hello plugins/mywidget` and rename the package/class.
2. Set `pluginId`, `pluginVersion` and implement `create(context)` to
   return your `HomeApplication` (a themed Compose card; use the host's
   `HomeTokens`/`TerminalTheme` so the widget follows the user's theme).
3. Build + test: `./gradlew :plugins:mywidget:assembleDebug
   :plugins:mywidget:testDebugUnitTest`.
4. Publish + pin: `tools/build-plugin.sh mywidget 1.0.0
   --update-catalog`.
5. Install: PocketShell → Control Center → Widget catalog → Fetch → the
   row appears → Install → add it to Home from Available.
6. Iterate: change the source, bump `version` (even a rebuild of the same
   version needs a version bump to re-download — the catalog pins bytes),
   publish, tap Update.

Anything the compiled card can do, a plugin can do — the Git and Sync
plugins are the full-capability reference implementations.
