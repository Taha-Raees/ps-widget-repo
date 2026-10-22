# plugins/sync — the Sync widget (compiled plugin)

The **Sync** Home Application as a compiled widget plugin. **3.0.0 is "take my
Linux with me"**: back up your Linux home, package list and a small allow-list of
app settings as ONE portable (optionally encrypted) file, choose it from storage on
a new phone, restore, and keep backing up from the same app. The rsync/rclone
**profiles** (source, destination, backend, RUN NOW, path completion) remain as the
SYNC tab. Not a data manifest — the full app is compiled code that the host downloads
(`releases/sync/<version>/plugin.jar`), verifies against the catalog's SHA-256 and
loads with `DexClassLoader`. Design + device evidence: `PLAN.md`.

| | |
|---|---|
| Catalog id | `sync` |
| Entry class | `app.pocketshell.widget.sync.SyncPlugin` (implements `app.pocketshell.widget.plugin.WidgetPlugin`) |
| Current version | `3.0.0` |
| Namespace | `app.pocketshell.widget.sync` |
| Source of truth | THIS directory |

## What a backup is

| in | out |
|---|---|
| `~` (the Linux home, `rootfs/root`) — projects, dotfiles, tool settings | the **rootfs** (regenerable, GBs, fragile across devices) |
| the **package list** (`/etc/apk/world`) — reinstalled on the new phone | plugin permissions, the installed-plugin catalog, home layout, notification state |
| app settings: `notes_store`, `launchers`, `settings`, `sync_profiles` | the raw app data directory |
| optional: `/usr/local`, `/opt` | |

Regenerable weight is left out by default (node_modules, Gradle/npm/Maven/Cargo
caches, `~/.cache`, Python venvs). **Key and credential files** (`~/.ssh`, `~/.gnupg`,
`.env`, `*.pem`, `*.key`, …) are left out **unless the backup is encrypted** or the
user explicitly accepts including them — best effort, and the UI says so.

## The `.psbak` file

`PSBAK` + version + flags + codec (8 bytes), then a `tar` stream compressed with
**zstd** (when installed; `apk add zstd`) or **gzip** (always there), optionally
**encrypted**: AES-256-GCM in 64 KiB chunks (STREAM construction: per-chunk nonce
counter, last-chunk flag, header as AAD), key = PBKDF2-HMAC-SHA256 (600k) of the
passphrase. Platform primitives only — no tool is needed on a fresh phone. A
plaintext sidecar `NAME.psbak.json` (device, date, size, codec, sha256…) is written
**last**, so its presence means the backup finished; the restore chooser lists
backups from sidecars.

**Recover without PocketShell** (unencrypted backups):
`tail -c +9 NAME.psbak | zstd -d | tar -x -C /` (or `gzip -dc` for gzip ones).

## How it runs

A constant `job.sh` runs in the guest as `sh job.sh <op> <stage>`; every variable
input (include/exclude lists, members, destination, codec) is a FILE in the stage
dir — never interpolated into shell text. Progress is polled from files the job
publishes; the engine streams the payload through encryption and sha256 into the
chosen storage. Work runs on the state holder's own scope, so rotation/leaving Home
never cancels or re-runs it. The stage is cleaned on success, failure and cancel;
leftovers from an interrupted run are listed and can be discarded. **Honest limit:**
the job is a foreground exec (proot kills its tracees on exit) — if Android kills
PocketShell mid-backup the job dies; keep the app open for big backups.

## Restore

Choose a file (system picker) → header check → passphrase → the file is streamed to
the stage (sha256 checked against the sidecar when there is one) → the guest lists
the archive and extracts ONLY the manifest → **preview** → choose scope and mode
(**in place**, typed confirmation; or **side folder** `~/restore-…`) → apply →
optional **reinstall packages** → app settings (allow-listed names only, the previous
file kept as `.pre-restore`, applied on the next app start).

## Source layout

- `BackupModel.kt` — scopes, presets, plans, header, manifest/sidecar, naming, retention, compatibility (pure)
- `BackupCrypto.kt` — the passphrase layer (pure JDK)
- `BackupJob.kt` — the guest script + job files + progress state
- `BackupEngine.kt` — backup / inspect / apply / reinstall / verify orchestration
- `BackupStorage.kt` — the storage seam + file-backed implementation
- `BackupAndroid.kt` — guest runner, process-tree cancel, SAF storage and picked-file source
- `VaultState.kt` — persisted settings + the process-scoped state holder
- `SyncStudio.kt` — the maximized BACKUP / RESTORE / SYNC studio and the card's backup header
- `GuestLaunch.kt` — the host-drift-tolerant launch-spec seam (`buildLaunchSpec$default`)
- `SyncPlugin.kt` — entry point; `create()` returns the `SyncApp` HomeApplication
- `SyncApp.kt` — the Compose application (profiles) and the routing between card, studio and profiles
- `SyncProbe.kt` — host-primitive probe execution
- `SyncProfile.kt` — `@Serializable` profile + persisted-store codec (kotlinx-serialization)
- `SyncRepository.kt` — owns the `sync_profiles` DataStore file
- `SyncPreviewParser.kt`, `SyncRunStats.kt` — preview/run-stats parsing

## Limits (stated, not hidden)

- Every backup is a full snapshot (zstd + excludes keep it small); there is no
  deduplicating incremental engine — those don't fit single-file SAF transfer.
- zstd's own binary path is verified by command construction only; the gzip pipeline
  is verified end to end in the JVM suite against the real busybox tar/gzip.
- SAF I/O is compile-checked and logic-tested through the file-backed storage; it has
  not been exercised against a real document provider.
- Package restore needs network; app-settings restore needs an app restart.

## Origin / history

Moved from the PocketShell app repository
(`plugins/sync/src/**` at pocketshell commit `94a2f72`, the M8.5 plugin-platform
commit). Git history cannot follow across repositories — use
`git log --follow plugins/sync` **in the PocketShell checkout** for the
pre-move history.

## Build / publish

```bash
./gradlew :plugins:sync:assembleDebug :plugins:sync:testDebugUnitTest
tools/build-plugin.sh sync 3.0.0 --update-catalog   # build + dex + publish + pin sha256 in catalog.json
```
