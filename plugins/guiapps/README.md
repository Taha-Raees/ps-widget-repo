# guiapps - GUI Apps

Discover, install and launch desktop apps for the PocketShell Linux guest (Debian 13): **Debian packages and AppImages, arm64**.
A Home application (`GuiApp`) loaded as a compiled plugin.

| | |
|---|---|
| Catalog id | `guiapps` |
| Entry class | `app.pocketshell.widget.guiapps.GuiAppsPlugin` |
| Version | `1.0.0` |

## The two flavours

| | Debian package | AppImage |
|---|---|---|
| What | packages from the guest's signed Debian archive | prebuilt arm64 **AppImages** (and one tarball) from the projects' own GitHub releases, run on the guest's own glibc (`/lib/ld-linux-aarch64.so.1`) |
| Install | `apt-get install <pkg>` (index refreshed and retried once if a package is not found) | latest release looked up through the GitHub API, downloaded with progress, **SHA-256 checked against the digest the release publishes**, stored in `~/Applications/<id>/` |
| Library | ~55 apps: Firefox, Chromium, LibreOffice, GIMP, Inkscape, VLC, mpv, Thunar, KeePassXC... | VSCodium, GIMP + PhotoGIMP, KeePassXC, Obsidian, Logseq, Zettlr, MarkText, MuseScore, Strawberry, DeaDBeeF, Clapper, code-server |
| Update | `apt-get install --only-upgrade <pkg>` | re-download of the latest release |
| Remove | `apt-get remove --autoremove <pkg>` | the app folder is deleted |

Every package name and release asset pattern in the library was checked against the real aarch64 index / the
real latest releases when the library was written. The library itself is data (`GuiCatalog` in `GuiModel.kt`).

## The tabs

- **DISCOVER** - search, filter by flavour and category; tap an app to install it.
- **MY APPS** - what is installed (launch / stop / log / update / remove), plus **ALSO ON THIS DEVICE**: every
  launcher found in `.desktop` files (`/usr/share/applications`, `~/.local/share/applications`, `/opt/*/applications`)
  with the libc its program is built for, read from the ELF header (`musl`, `glibc`, `static`, `script`).
  *Check for updates* asks apt for upgradable packages and GitHub for newer releases.
- **SETUP** - desktop session and C library status, a "download an AppImage from a link" form (https only; no
  published checksum for a pasted link, and the UI says so), and troubleshooting tips.

## How an app is launched

A constant `sh -c` script (data travels as positional arguments) runs the program with the host's desktop-session
environment - `XDG_RUNTIME_DIR=/tmp/ps-desktop/xdg`, `WAYLAND_DISPLAY=psgui`, `DISPLAY=:0` (the same values the host's
own desktop wrapper uses) - inside `dbus-run-session` when no session bus exists. AppImages run with
`APPIMAGE_EXTRACT_AND_RUN=1` (no FUSE). Chromium and Electron apps get `--no-sandbox --disable-gpu
--disable-dev-shm-usage`. Output goes to `~/.cache/pocketshell/gui/<id>.log`; an early exit is explained in plain
words (no display, missing library -> the Debian package that provides it, FUSE, sandbox, crash). code-server is a web app: it starts
on `127.0.0.1:8080` and opens in the companion browser.

## Cost and safety

- Nothing polls. The card reads the dpkg database, a small registry and the `.desktop` files once after it has
  stayed on screen; the network is touched only by an install, an update check or a custom download.
- Downloads: https only, 1.5 GB cap, free-space check, `.part` file then atomic move, SHA-256 verified (mismatch
  deletes the file), file names validated, deletion confined to `~/Applications/<id>`.
- One apt operation at a time (dpkg holds a database lock); a host job keeps PocketShell alive while an install runs.

## Verified / not verified

- **Verified in this repo's tests (47):** catalog consistency, asset picking, JSON parsing of the GitHub answer,
  digest parsing, `.desktop` and `Exec=` parsing, ELF flavour detection (including the real `xterm` binary on the
  device), launch-plan construction, log diagnosis, source contracts.
- **Verified live on a PocketShell guest (Alpine 3.24 at 1.0.0; Debian 13 names checked against the trixie arm64 Packages and Contents indexes for 2.0.0):** the release lookup, a real download with SHA-256
  verification and the delete-on-mismatch path; a real glibc "anylinux" AppImage (htop) running with
  `APPIMAGE_EXTRACT_AND_RUN=1`.
- **Not verified:** a window actually appearing for each app. That needs the PocketShell app's desktop session
  running on a device; GUI launch was not exercised end to end. Heavy apps (Chromium, Firefox, LibreOffice, Blender...) are
  flagged `large`.

## Build

```
./gradlew :plugins:guiapps:testDebugUnitTest
tools/build-plugin.sh guiapps 1.0.0 --update-catalog
```
