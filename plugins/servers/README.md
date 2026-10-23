# servers — the Processes plugin (id `servers`)

> Renamed **Processes** in 2.2.0: it shows servers, background jobs like Gradle, terminal sessions and what outlives the terminal,
> which "Servers" undersold. The id stays `servers` (Home layouts refer to it).

## 2.2.0 - CLEAN UP, and an audit of what every page shows

**CLEAN UP tab** (also a one-line nudge on the card and a row on the Overview). Two rules, both **OFF** until you turn them on, each
**Off / Ask me / Auto**: *Build daemons* (Gradle / Kotlin / Maven daemons - they restart on demand) and *Heavy and idle* (detached
programs using 500 MB+ that have done nothing for an hour+). A process counts as idle only if it **and everything below it** used
under 1 % CPU in every look for the whole window; a gap in our looking is unknown, never idle. Never touched by any rule: terminals,
tmux/screen, anything that serves a port, agents (Claude, Codex...), ssh/rsync/tar/git and other transfers, anything started from a
terminal that is still open, and programs you mark KEEP. Auto sends **SIGTERM only**, re-identifies the process right before, stops
at most 3 per check (once a minute), does a fresh full scan first, refuses a "heavy" program that holds network sockets, and stops
auto-stopping a program that keeps coming back or ignores the signal. Rules are stored in `~/.config/pocketshell/processes-cleanup.txt`
(a Sync backup carries them). The design and its tests are in `ServersCleanup.kt` / `ServersCleanupTest.kt`.

**Audit fixes** (found by reading how each page is fed, then pinned by `TickPlanTest`):
1. A server that **binds its port seconds after its process starts** (every dev server) was invisible until another process started or
   you tapped refresh - the host probe's idle gate skips a quiet machine. Full scans now continue for 60 s after the process set changes
   and recheck periodically while a page is open.
2. On a quiet machine the **device strip (load / memory) and the "scanned Ns ago" label froze** while the process list moved: only a full
   scan wrote them. A process pass on the maximized page now refreshes both.
3. (Found in the cleanup's own design) a Gradle daemon **listens on a loopback port for IPC**, so a naive "serves a port" guard would have
   silently disabled the build rule; build daemons are exempt from that guard, everything else that listens is untouchable.

`ServersRealProcessTest` runs the scanner, CPU measurement, classification, idle tracking, guards, identity re-check and an actual
SIGTERM against **real processes** on the machine's /proc.

The `servers` widget as a compiled plugin. 2.0.0 was the manifest→plugin
graduation (the same jump git and sync took): the v1 `widgets/servers.json`
six-line listener card became the full monitor. **2.1.0 widens "listening
ports" to "what is running on this device"**: servers, background
processes (Gradle daemons, watchers, nohup jobs), terminal sessions and
session keepers (tmux/screen) — and, as a kernel fact, which of them
**outlive closing the terminal**. Design + device evidence: `PLAN.md`.

## The two-mode contract

| | what it shows |
|---|---|
| **MINI** (the card, COMPACT/ROOMY) | the answer in one glance: `2 up · 3 bg · 1 ses`, then one line per thing — `● :3000 node` (server), `▪ tmux` (session keeper), `○ Gradle daemon 8.14.5  1.2 GB` (background) — and the headline footer `N OUTLIVE THE TERMINAL`. Rows are sized to the card's real height with a `+N more` remainder. ROOMY adds cwd, survival, the HTTP chip and the response-beat strip. Tap a row for the in-card detail (facts + TERMINAL / COMPANION). No destructive action on the card. |
| **MAXIMUM** (the maximized page, FULL) | a task manager, not a stretched card: title + live summary, four sections behind a bottom bar (side rail when wide) — **OVERVIEW** (numbers, device health, what outlives the terminal, top memory, top CPU), **SERVERS** (endpoints, HTTP checks, response history, process facts, two-pane when wide), **BACKGROUND** (search + sort by memory/CPU/uptime/name, ruled rows, full detail with argv), **SESSIONS** (session keepers, open terminals with their foreground job, what runs below). STOP (SIGTERM) and FORCE KILL (SIGKILL), both confirmed with the exact command. |

Both are flat PocketShell style — hairline rules, mono small-caps, accent text,
a state rule instead of a pill; no filled cards. The maximize toggle is HOST
chrome (the card's top-right corner); the plugin only adapts. FULL is decided
by page height first, so a maximized portrait phone (≈410 dp wide) gets the
task manager, not the card.

## What "outlives the terminal" means

A kernel fact, never a guess: `Survival.DETACHED` (no controlling tty — closing
a terminal does not signal it), `NOHUP` (keeps its tty but ignores SIGHUP) or
`ATTACHED` (ends with its terminal). It is *counted* as outliving only when it
is also reparented to init (not a child job of something still running), and
system helpers (dbus, ssh-agent) are excluded from the headline.

## Process facts (the second source)

All from `/proc` of the app's own UID (the kernel hides the rest): `stat`
(parent, session, tty, foreground group, threads, start, CPU ticks, RSS),
`status` (SigIgn, **VmSwap**), `cmdline`, `cwd`. Guest = own-UID minus
infrastructure (`app.pocketshell*`, `libproot.so`, `libpsx.so`). Kinds
(BUILD / SESSION KEEPER / SHELL / TOOL / SYSTEM / SERVER / PROCESS) are rule
labels over comm/argv and say no more than the rule matched.

- **Memory is resident + swapped.** A big idle daemon often sits in swap (the
  device showed a Gradle daemon at 155 MB resident + 1.0 GB swapped); ranking by
  resident alone would hide it.
- **Age is unknown ("—"), never 0,** when the uptime clock is evidently stale
  (a start after "now"). The host's `/proc/uptime` is real; a virtualized one
  is not trusted.
- **cwd is mapped back to the guest path** (the host sees `<rootfs>/root/…`).

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

The monitor also runs ONLY while its card is on screen (window-bounds test): Home keeps
several applications composed side by side, so "resumed" is not "visible". The host
probe's gate is asked off the main thread, and a manual refresh is claimed once (coming
back to the card never re-runs it).

The pid-set idle gate turns an idle card's tick into ONE /proc readdir;
a card with servers re-verifies (the host probe's own rule); the process scan
runs when the pid set changed, on every tick while the maximized page is
visible, else every 6th tick (30 s); a paused monitor does nothing at all.
The signal op is the ONLY guest exec.

## The signal op

ServersOps follows the GitOps discipline: refuse before any dialog when the
evidence cannot support it (no verified owner pid, or a pid ≤ 1), confirm with
the exact command (`kill -s TERM <pid>`, or `KILL` for FORCE KILL — pid and
signal are positional args, the signal comes from a closed enum, nothing is
interpolated), honest outcome (exit code / stderr), forced rescan after.
Signalling a terminal's shell says it ends that terminal. It exists only in the
maximized tier — the desktop-app context is where a task-manager action belongs.
The exec goes through `GuestLaunch`, which resolves the host's
`buildLaunchSpec` at run time (its parameter list grew; a direct call died with
`NoSuchMethodError: buildLaunchSpec$default`).

## Build + test

```bash
./gradlew :plugins:servers:assembleDebug :plugins:servers:testDebugUnitTest
```

## Publish

```bash
tools/build-plugin.sh servers 2.1.0 --update-catalog
```

## Files

- `ServersPlugin.kt` — the WidgetPlugin entry (id `servers`, 2.1.0)
- `ServersApp.kt` — the application object, process-scoped state, the
  lifecycle-aware tick, the HTTP fan-out, the signal pipeline and the exec seam
- `ServersCard.kt` — the MINI mode: row model (`CardRows`), overview, details
- `ServersStudio.kt` — the MAXIMUM mode: the task-manager shell + navigation
- `ServersProcessScreens.kt` — BACKGROUND / SESSIONS / OVERVIEW bodies, rows, process detail
- `ServersScreens.kt` — routing, the SERVERS tab's dashboard + server detail
- `ServersComponents.kt` — shared controls (rule, stat strip, kind dot, search, dialogs)
- `ServersProcesses.kt` — pure /proc parsing, classifier, survival, grouping, the scanner
- `ServersProbe.kt` — the host-probe wrapper (pid-set gate) + system-strip parsers
- `HttpCheck.kt` — the app-side HTTP check + the session beat ring
- `ServersPresentation.kt` — filter, sort, formatting (pure, JVM-tested)
- `ServersOps.kt` — the signal operation's refusal/confirm/argv/outcome
- `GuestLaunch.kt` — the host-drift-tolerant launch-spec seam
- `ServersLayout.kt` — COMPACT / ROOMY / FULL tiers (FULL is height-first)
