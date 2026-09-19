# Host API jar provenance

- file:    pocketshell-api.jar (stable name; see below)
- built:   2026-09-20 by PocketShell `:app:pluginClassesJar`
- from:    PocketShell commit 28f9262 (versionName 0.14.0-m8.5)
- surface: the app's compiled classes (WidgetPlugin, HomeApplication,
           HomeAppContext, HomeAppStateStore, theme tokens, runtime/guest-exec)
- usage:   plugin modules compile against it (compileOnly); at runtime the
           HOST provides these classes via parent classloader delegation —
           the jar is never loaded by the app and plugins never re-ship it.

Refresh after every app release that touches the plugin-visible surface:
    tools/publish-widget-api.sh

## Naming convention

The jar's filename is STABLE (`api/pocketshell-api.jar`) — module build
files never change on a refresh. The authoritative version/commit record
is this file (rewritten on every publish).
