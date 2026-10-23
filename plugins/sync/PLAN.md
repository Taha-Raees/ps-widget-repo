# Sync 3.0.0 — "take my Linux with me": backup, move, restore

## The product (user's words → decision)

Sync becomes the app where you **back up your Linux data and app data, carry it to a
new phone, pick the backup from storage there, restore it, and keep updating it from
the same app.** The existing rsync/rclone profiles stay (as the SYNC tab).

### Linux data or the whole app data? — **Linux data, plus a small allow-listed slice of app data. Never the rootfs, never raw app data.**

| | verdict | why (evidence from this device) |
|---|---|---|
| `/root` (the Linux home: projects, dotfiles, tool configs) | **YES — the core** | it is the user's work. Physically `rootfs/root` (read from the launcher: neither exec profile binds a separate home; `filesDir/home` is only the launcher's host cwd), the same directory in both profiles |
| Package list (`/etc/apk/world`, `repositories`) | **YES** | 63 lines vs a 420-package / 1.8 GB rootfs. Restore = fresh Alpine + `apk add` the list. Reproducible, tiny, version-tolerant |
| App settings: `notes_store`, `launchers`, `settings`, `sync_profiles` DataStores | **YES, opt-in restore** | small and valuable (the user's notes!). Restored with a `.pre-restore` copy + a "close and reopen PocketShell" step |
| `/usr/local`, `/opt` | optional toggles | user-installed things outside home |
| **The rootfs** (`noBackupFilesDir/runtime/rootfs`) | **NO** | the app itself marks it regenerable (noBackup); GBs; and fragile: proot's `.l2s.*` link emulation stores ABSOLUTE host paths that break on another device/user id |
| Plugin consents/revocations, `widget_catalog` (SHA pins), `home_widgets`, notifications | **NO** | security grants and machine-specific state must be re-granted on the new device, never imported |
| Whole raw app data dir | **NO** | would carry the above; the host sets `allowBackup=false`, so there is today **no** migration path at all — this fills it |

## Format — one portable file: `.psbak`

`[8-byte header: "PSBAK" ver flags codec] [payload]`, payload = `tar` stream compressed
by **zstd** (when installed) / gzip, optionally **encrypted**. One file, because the
only way a plugin reaches shared Android storage is the system file picker (SAF),
which streams one document at a time; a many-file repository (restic/borg) is
unusable over SAF.

- **Container: `tar`** — preserves modes, symlinks (absolute and relative — verified
  on this device with busybox tar), spaces in names. Entry paths are root-relative:
  `tmp/psbak-meta/{manifest.json,apk-world,apk-repositories,appdata/*}`, `root/…`.
- **Compression: zstd** (`apk add zstd`: one package, 1.5.7 — simulated on this
  device). ≈5× faster than gzip at a better ratio, multi-threaded, frame checksum.
  The file records its codec in the header; **gzip fallback** works with a bare
  Alpine (busybox) — a new phone is never stuck. xz is read-compatible.
- **Encryption: AES-256-GCM in Kotlin (STREAM construction), PBKDF2-HMAC-SHA256
  600k.** Why not a tool: `age` refuses non-interactive passphrases, `openssl enc` has
  no authentication, `gpg` is heavy — and none exist on a fresh phone with no network
  at the moment of restore. Only platform primitives (`javax.crypto`) are used; the
  framing (per-chunk nonce counter + last-chunk flag, header as AAD) is the standard
  STREAM design and is covered by round-trip / wrong-passphrase / bit-flip /
  truncation / reorder tests.
- **Integrity: sha256** of the stored file in a plaintext sidecar `NAME.psbak.json`
  (device, date, sizes, counts, codec, encrypted?, secretsIncluded?) — the restore
  chooser lists backups from sidecars without reading gigabytes.
- **Recoverable without PocketShell:** `tail -c +9 f.psbak | zstd -d | tar x` (unencrypted).

## Choose your folders (any size)

The user decides what their data is worth. The home can be backed up **whole** or as
**only the folders ticked**: known tools are recognised by where they keep their data and
labelled (Claude Code `.claude`, ZCode, Codex, OpenCode, Kilo, Gemini, Hermes, Cline,
Qwen …), every other top-level entry is listed too, and a path can be added by hand
(`.config/kilo`). Sizes are measured host-side, symlinks not followed, at low priority on
one background thread, **with the same leave-out rules the backup applies** — the number
shown is what will actually be archived. Nothing is hidden for being large.

## Safety hardening from the independent review

- Restore audits the archive's member list BEFORE extracting anything: refuses `..`,
  absolute names, anything outside `tmp/psbak-meta`, `root`, `usr/local`, `opt`, and any
  member written THROUGH an earlier symlink (defense in depth; busybox tar also refuses
  these, verified on the device).
- Retention counts and prunes only the SAME device's backups — a new phone's backups can
  never push out the old phone's, which are the ones being migrated.
- A finished backup is never deleted because a later listing hiccuped.
- One cancel token per operation kind; closing the restore screen can never cancel a backup.
- The decrypted staged copy is discarded on every exit path; "interrupted" never lists (and
  clean-up never deletes) a stage that is in use; stale pids are cleared before a job starts.
- A second restore keeps every earlier `.pre-restore` copy.

## Secrets

Backups of a dev phone contain SSH keys and tokens. Without a passphrase they are
**left out by default** (`~/.ssh`, `~/.gnupg`, `*.env`, `.git-credentials`, `.netrc`,
`.aws`, `id_*`…; shown to the user, labelled best-effort); including them in an
unencrypted backup needs an explicit acknowledgement. With a passphrase they are in.

## Where backups go

1. **A folder the user picks (SAF `OpenDocumentTree`, persisted grant)** — Downloads,
   SD card, USB, a cloud provider's folder. **The flagship**: survives uninstall,
   reachable from the new phone.
2. **App storage** (`getExternalFilesDir/backups`, no permission) — convenient, but
   **deleted when PocketShell is uninstalled**, so the UI says so.
3. The existing rsync / rclone profiles (ssh, cloud remotes) remain on the SYNC tab.

Restore on the new phone: **CHOOSE BACKUP FILE** (SAF `OpenDocument`) — any `.psbak`,
from any provider the system picker offers.

## How it runs (and why)

- **Guest job, foreground exec, plugin-owned.** The staging tree lives in the guest
  `/tmp/psbak/<id>/` (host path `rootfs/tmp/psbak/<id>/`, readable by the app). A
  constant `job.sh` takes only an op + the stage dir; every variable input
  (include/exclude lists, members, codec, destination) is a FILE read by the script —
  nothing user-controlled is ever spliced into shell text. The job is NOT detached:
  proot runs `--kill-on-exit`, so only an exec that stays alive keeps its tracees.
  Honest limit: if Android kills the app mid-backup the job dies; the next open says
  "interrupted" and offers to clean up. (Run with PocketShell open for big backups.)
- **Progress by polling files** (`phase`, growing `payload` size) — no output parsing,
  no exec per tick. Kotlin copies payload ↔ SAF stream, hashing and (de)crypting on
  the way, with byte-accurate progress.
- **Process-scoped scope.** Work is launched on the state holder's own scope, not a
  `LaunchedEffect`: rotation/leaving Home must never cancel — or re-run — a backup
  (the bug class fixed in Git 3.3.1 and Servers 2.1.0).
- **Cancel** signals the job's process tree from the host (`/proc` ppid walk +
  `Process.sendSignal`), then cleans the stage.
- **Retention:** keep the newest N (default 3) of *this app's* backups (name pattern +
  valid sidecar), pruned only after the new one is fully written and size-checked.

## Restore, safely

1. pick file → header check → (passphrase) → stream-decrypt into the stage;
   the sidecar's sha256 is checked when present
2. **INSPECT**: guest lists the archive and extracts the manifest → preview card
   (from which device, when, size, N packages, app settings present, secrets?) — nothing
   written to the user's data yet
3. choose scope (home / `/usr/local` / `/opt` / app settings) and mode:
   **in place** (overwrite same paths, keep everything else) or **side folder**
   (`~/restore-YYYYMMDD-HHMM/`, review first)
4. typed confirmation → apply → optional **REINSTALL PACKAGES (N)** (`apk add`, needs
   network) → app settings applied only if ticked, existing files kept as
   `.pre-restore`, then "close and reopen PocketShell"

## UI — two modes, one flat language (hairlines, mono small-caps, accent text, no cards)

**MINI (card).** One glance at your safety: `✓ Backup · 2h ago · 412 MB · encrypted`,
where it lives, and the two safe actions — **BACK UP NOW**, and the profiles list below
(unchanged). Running: `Backing up · archiving · 128 MB written` + CANCEL. No backup:
`No backup yet — your Linux home is not protected` + SET UP. No restore on the card
(destructive actions live in the maximized tier, as everywhere else).

**FULL (maximized) — three tabs behind a bottom bar / side rail:**
`BACKUP · RESTORE · SYNC`
- **BACKUP**: location · what's included (checklist) · leave-out presets (node_modules,
  gradle caches, …) · secrets & passphrase · compression (zstd? INSTALL) · BACK UP
  NOW · live progress · the backups at the location (VERIFY / DELETE)
- **RESTORE**: CHOOSE BACKUP FILE → preview → scope + mode → RESTORE → progress →
  REINSTALL PACKAGES → app-settings note
- **SYNC**: the existing rsync/rclone profile app, unchanged

## Also fixed on the way

- `buildLaunchSpec$default` `NoSuchMethodError` — Sync's guest exec would fail on
  this host exactly like Git's did (routed through `GuestLaunch`, with `extraBinds`).
- FULL gated on 420 dp width → a maximized phone page rendered the card (height-first).

## Tests

Header/manifest codecs, scope→include/exclude plans, secret patterns, retention, file
naming, the crypto matrix, job-script argv safety, **real end-to-end pipeline tests
(run the generated job.sh against a temp root with the real busybox tar + gzip)**,
storage fakes, layout tiers, contract pins.

## Limits (stated, not hidden)

- Every backup is a full snapshot (zstd + excludes keep it small). Deduplicated
  incremental engines (restic/borg) don't fit single-file SAF transfer; the rsync/rclone
  SYNC tab remains the continuous-mirror path.
- zstd's own binary path is verified by command construction only (not installed here);
  the gzip pipeline is verified end to end.
- SAF I/O is compile-checked and logic-tested through the file-backed storage; it is not
  exercised against a real document provider in this session.
- **3.1.0:** every long operation (backup, restore, reading a backup, package install, checksum check) registers
  as a HOST JOB (`PluginJobs`), so the app's foreground service keeps it alive when the user switches apps and the
  notification offers STOP. This needs an app release with the job service; on an older app a backup still dies if
  Android stops the app, as in 3.0.x.
- Package restore needs network. App-settings restore needs an app restart.
