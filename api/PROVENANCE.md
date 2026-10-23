# Host API jar provenance

- file:    pocketshell-api.jar (stable name; see below)
- built:   2026-09-20 by PocketShell `:app:pluginClassesJar`
- from:    PocketShell commit 28f9262 (versionName 0.14.0-m8.5)
- surface: the app's compiled classes (WidgetPlugin, HomeApplication,
           HomeAppContext, HomeAppStateStore, theme tokens, runtime/guest-exec)
- usage:   plugin modules compile against it (compileOnly); at runtime the
           HOST provides these classes via parent classloader delegation —
           the jar is never loaded by the app and plugins never re-ship it.

## Surgical update 2026-10-23

Two additions to the plugin-visible surface, grafted in from `:app:pluginClassesJar` of the PocketShell
`feature/plugin-resilience` branch (on top of a914f4f; not yet committed there at the time):

- `app/pocketshell/jobs/*` — `PluginJobs` (begin / end / stopAll): a plugin registers long-running work so the
  foreground service keeps the app alive (ssh tunnels, backups, a shared folder).
- `app/pocketshell/widget/WidgetNav*` — adds `openTerminalWith(text)`: open the terminal and TYPE text at the
  prompt (never Enter).

Every other entry of the jar is byte-identical to the 2026-09-20 build, on purpose: the other plugins were built and
proven against that surface, and a wholesale refresh would risk signature drift in Git, Sync and Servers.
A plugin that uses either addition must guard for an older app (`LinkageError`) — see `HostJobs` in the network and sync
plugins — and the catalog `minAppVersion` stays low so older apps still install it, just without the addition.

Refresh after every app release that touches the plugin-visible surface (the same surgical approach is preferred):
    ./gradlew :app:pluginClassesJar   # then graft the changed classes into api/pocketshell-api.jar

## Naming convention

The jar's filename is STABLE (`api/pocketshell-api.jar`) — module build
files never change on a refresh. The authoritative version/commit record
is this file.
