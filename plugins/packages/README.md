# packages - Packages

Add, update and remove Linux packages and command-line tool bundles, and look after the Linux environment.
A Home application (`PkgApp`) loaded as a compiled plugin.

| | |
|---|---|
| Catalog id | `packages` |
| Entry class | `app.pocketshell.widget.packages.PackagesPlugin` |
| Version | `1.0.0` |

## Tabs

- **INSTALLED** - every package straight from `/lib/apk/db/installed` (no process, no network): size, description,
  what needs it, and *Added by you* (`/etc/apk/world`) versus *Everything*. INFO, UPDATE, REMOVE per package.
- **ADD** - search the repositories (`apk search -v -d`), add by name, or install a **tool bundle** (Essentials, nicer
  shell, Python, Node, Go, Rust, Java, C/C++ build, network tools, self-hosting, media, VPN, DevOps, debugging). Every
  package in a bundle was checked against the aarch64 index.
- **UPDATES** - refresh the index, list what can be updated (`apk list -u`), update one package or everything.
- **SYSTEM** - distribution, architecture, kernel, PocketShell layers (glibc, dev tools), repositories, package
  cache size, and maintenance: refresh index, repair (`apk fix`), clear the cache (downloaded `*.apk` files only).

## The safety model

1. Nothing changes without a **simulated preview**: `apk --simulate` lists what would be installed, updated or purged and
   the disk change; you confirm; only then the real command runs - the same arguments, without `--simulate`.
2. `apk del` does not cascade in apk 3: when something still needs the package it only leaves the "added by you" list.
   The preview says so ("stays installed - still needed by ...") instead of pretending it was removed.
3. Removals that would purge a package Linux stands on (`busybox`, `musl`, `apk-tools`, `bash`, `openssh`...) are refused;
   12 or more purges need a typed confirmation; more than 80 are refused.
4. Package names are validated before they reach apk's option parser (`--allow-untrusted` cannot be smuggled in).
5. One apk operation at a time, a host job keeps the app alive while it runs, and failures are translated
   (database locked, no such package, repository unreachable).

apk runs through the same launch spec the host's own package screen builds (`PACKAGE_OPERATION` profile and the
host's apk cache directory).

## Verified / not verified

- **Tests (37):** the installed-database parser (including the real database of the device it was written on),
  reverse-dependency graph, `apk --simulate` plan parsing for installs / upgrades / purges / "not removed due to",
  argv construction and name validation, guards, search ranking, bundles, source contracts.
- **Verified live on a PocketShell guest (Alpine 3.24, apk-tools 3.0.8):** the output formats of `apk add / del /
  upgrade / fix --simulate`, `apk list -u`, `apk search -v -d`, `apk info -a`.
- **Not verified:** a real install/update/remove driven through the plugin UI on a device; the plugin never ran
  `apk` for real here (only `--simulate`, which changes nothing).

## Build

```
./gradlew :plugins:packages:testDebugUnitTest
tools/build-plugin.sh packages 1.0.0 --update-catalog
```
