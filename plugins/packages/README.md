# packages - Packages

Add, update and remove Linux packages and command-line tool bundles, and look after the Linux environment.
A Home application (`PkgApp`) loaded as a compiled plugin.

| | |
|---|---|
| Catalog id | `packages` |
| Entry class | `app.pocketshell.widget.packages.PackagesPlugin` |
| Version | `2.0.0` (Debian guest; 1.x was the Alpine/apk version) |

## Tabs

- **INSTALLED** - every package straight from the dpkg database `/var/lib/dpkg/status` (no process, no network): size,
  description, what needs it, and *Added by you* (apt's manual set: installed, not marked automatic in
  `/var/lib/apt/extended_states`, not part of the base system) versus *Everything*. INFO, UPDATE, REMOVE per package.
- **ADD** - search the repositories (`apt-cache search`), add by name, or install a **tool bundle** (Essentials, nicer
  shell, Python, Node, Go, Rust, Java, C/C++ build, network tools, self-hosting, media, VPN, DevOps, debugging). Every
  package in a bundle was checked against Debian 13's arm64 index.
- **UPDATES** - refresh the index, list what can be updated (`apt list --upgradable`), update one package or everything.
- **SYSTEM** - distribution, architecture, kernel, C library, dev tools layer, repositories (deb822 `.sources` and
  one-line `.list`), package cache size, and maintenance: refresh index, repair (`dpkg --configure -a`, then
  `apt-get install -f`), clear the cache (downloaded `*.deb` files only).

## The safety model

1. Nothing changes without a **simulated preview**: `apt-get --simulate` lists what would be installed, updated or
   removed; you confirm; only then the real command runs - the same arguments, without `--simulate`. (apt prints no
   size change in simulation mode, so the preview shows none rather than a guess.)
2. `apt-get remove` cascades: whatever depends on the package goes too, and the preview lists every removal.
3. Removals that would take a package Linux stands on (`dpkg`, `apt`, `bash`, `coreutils`, `libc6`... and every
   package the database marks Essential or Priority required) are refused; 12 or more removals need a typed
   confirmation; more than 80 are refused.
4. Package names are validated (Debian policy) before they reach apt's option parser (`-o ...` cannot be smuggled in).
5. One apt operation at a time, a host job keeps the app alive while it runs, and failures are translated
   (dpkg lock held, unable to locate package, repository unreachable).

apt runs through the same launch spec the host's own package screen builds (`PACKAGE_OPERATION` profile, which sets
`DEBIAN_FRONTEND=noninteractive`, and the host's package cache directory).

## Verified / not verified

- **Tests (38):** the dpkg status parser, the manual set, reverse-dependency graph, `apt-get --simulate` plan parsing
  for installs / upgrades / reinstalls / removals, argv construction and name validation, guards, search ranking,
  `apt list --upgradable`, deb822 sources, bundles, source contracts.
- **Verified live on the PocketShell Debian 13 guest (Tab S7, 2026-10-26):** the output formats used as fixtures
  (`apt-get -s install / remove / --reinstall`, `apt list --upgradable`, `apt-cache search`, `extended_states`).
- **Not verified:** a real install/update/remove driven through the plugin UI on a device.

## Build

```
./gradlew :plugins:packages:testDebugUnitTest
tools/build-plugin.sh packages 2.0.0 --update-catalog
```
