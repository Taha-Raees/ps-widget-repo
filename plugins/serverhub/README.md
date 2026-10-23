# serverhub - Server Hub

Start and manage web servers, dashboards, local APIs and self-hosted services from the phone - with **ports, logs,
health, restart controls and access links**. A Home application (`HubApp`) loaded as a compiled plugin.

| | |
|---|---|
| Catalog id | `serverhub` |
| Entry class | `app.pocketshell.widget.serverhub.ServerHubPlugin` |
| Version | `1.0.0` |

## What it does

- **SERVICES** - your saved services with state (`stopped / starting / running / stopping / crashed`) and health
  (`up / slow / error / down`), START / STOP / RESTART, CHECK, OPEN (companion browser), copy link, copy Wi-Fi link,
  a **LOG** panel (last 80 lines, errors and warnings coloured, REFRESH, opt-in FOLLOW, CLEAR), EDIT and DELETE.
- **PORTS** - everything listening inside Linux (the host's own verified `ServerProbe`), which of it is a hub service,
  and whether other devices on the Wi-Fi can reach it.
- **NEW** - quick starts: static website (Python or Caddy or Node), a tiny **local JSON API** (stdlib Python, file
  generated for you), Redis, code-server (from GUI Apps), or your own command. The folder and starter files are
  created; the program is checked first and the missing Alpine package is named.

## Rules

- **No shell.** A command is split into words and quotes and run as an argv. Unquoted `| & ; > < `` ` ``  and `$VAR` /
  `$(...)` are refused with a hint (`sh -c '...'` when you really want a shell). The job wrapper is constant text:
  pid file, log file and folder are positional arguments.
- **Loopback by default.** Every template binds `127.0.0.1`. "Show the Wi-Fi link" only *displays* a link and tests
  it; it does not open anything. Nothing adds a password.
- **Restart on crash** is bounded: 1 s, 3 s, 8 s back-off, at most 3 automatic restarts in 5 minutes, then it gives up
  and says so. A stop you asked for is never undone.
- **Logs are capped** (kept to the last quarter once past 2 MB).
- Running services hold a host job, so PocketShell stays alive in the background with a notification and STOP.
  They end when PocketShell is closed; there is no start-at-boot.
- Nothing polls. Health is read once a service has started and when you ask; the log follows only while it is open
  and FOLLOW is on.

## Verified / not verified

- **Tests (34):** command-line parser, service validation and storage, templates, health verdicts, restart policy,
  links, log helpers, process-tree helpers, source contracts (no shell text, bounded loops, single free-running loop).
- **Verified live in a PocketShell guest:** the exact job wrapper (pid file, log capture, folder, `exec`, SIGTERM) against a
  real `python3 -m http.server`, and a failing `cd` ending with exit 126 and a line in the log.
- **Not verified:** a full start/stop/restart cycle driven through the plugin UI on a device, the Wi-Fi reachability
  check from a second device, and host jobs in the notification shade.

## Build

```
./gradlew :plugins:serverhub:testDebugUnitTest
tools/build-plugin.sh serverhub 1.0.0 --update-catalog
```
