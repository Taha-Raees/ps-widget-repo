# servers — the server monitor plugin

The `servers` widget as a compiled plugin. Version 2.0.0 is the
manifest→plugin graduation (the same jump git and sync took): the v1
`widgets/servers.json` six-line listener card becomes the full monitor.

## The two-mode contract

| | what it shows |
|---|---|
| **MINI** (the card, COMPACT/ROOMY) | what is needed immediately: the running count, every verified endpoint as `:port  process` (roomy: + cwd, + HTTP status chip + latency, + the response-beat strip), the honest state line. Tap a row for the in-card detail (endpoint, PID, attribution, TERMINAL / COMPANION). |
| **MAXIMUM** (the maximized page, FULL) | the desktop-class monitor: device health strip (load 1/5/15, memory used/total, uptime, rootfs disk %), search, four sort modes, per-endpoint HTTP status + latency with the session beat history and uptime %, process facts (PID, CPU%, RSS, argv, cwd), copy-URL, TERMINAL / COMPANION hand-off, and STOP. |

The SAME composable renders both — the maximized page is the card in a
larger container, its information hierarchy re-tiered (ServersLayout),
never a scaled layout. The maximize toggle is HOST chrome (the card's
top-right corner); the plugin only adapts.

## Fact discipline

- Endpoints come ONLY from the host's `ServerProbe` pipeline: a candidate
  port must answer a TCP connect (kernel fact) and attribute to an
  own-UID process (acceptor diff / cmdline rule) before it is shown.
- HTTP checks are app-side GETs on the shared loopback (guest and app
  share one network namespace): bounded count (12), parallelism (6) and
  timeouts; bodies drain ≤16 KB; redirects are reported, never followed.
  "Up" is 2xx/3xx — what a status page means by it.
- Process facts are the attributed owner's own /proc files: CPU% is the
  utime+stime tick delta (first sighting renders "—", not 0%), RSS is
  stat's rss field × page size, argv is cmdline NUL-joined.
- The system strip is labelled: load / memory / uptime are the DEVICE's
  (shared kernel); the disk number is the rootfs image's footprint on
  the host data partition. A missing file degrades its chip alone.
- Beats are in-memory BY DESIGN: the strip says "this session"; a fresh
  process starts a fresh history instead of persisting a story the
  widget can no longer stand behind.

## Cost discipline

The pid-set idle gate turns an idle card's tick into ONE /proc readdir;
a card with servers re-verifies (the host probe's own rule); a paused
monitor does nothing at all. The stop op is the ONLY guest exec.

## The stop op

ServersOps follows the GitOps discipline: refuse before any dialog when
the evidence cannot support it (no verified owner pid), confirm with the
exact command (`kill <pid>` — SIGTERM, positional arg, never
interpolated), honest outcome (exit code / stderr), forced rescan after.
It exists only in the maximized tier — the desktop-app context is where
a task-manager action belongs.

## Build + test

```bash
./gradlew :plugins:servers:assembleDebug :plugins:servers:testDebugUnitTest
```

## Publish

```bash
tools/build-plugin.sh servers 2.0.0 --update-catalog
```

## Files

- `ServersPlugin.kt` — the WidgetPlugin entry (id `servers`, 2.0.0)
- `ServersApp.kt` — the application object, process-scoped state, the
  lifecycle-aware tick, the HTTP fan-out and the exec seam
- `ServersScreens.kt` — the one composition, three tiers (card overview /
  card detail / maximized dashboard + detail, two-pane when wide)
- `ServersComponents.kt` — the shared controls (dots, chips, beat strip,
  search, confirm dialog, outcome banner)
- `ServersProbe.kt` — the probe wrapper (pid-set gate) + the /proc
  parsers (stat, cmdline, loadavg, meminfo, uptime) + the system strip
- `HttpCheck.kt` — the app-side HTTP check + the session beat ring
- `ServersPresentation.kt` — filter, sort, formatting (pure, JVM-tested)
- `ServersOps.kt` — the stop operation's refusal/confirm/argv/outcome
