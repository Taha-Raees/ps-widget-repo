# Servers 2.1.0 — from "listening ports" to "what is running on this device"

## Goal (user's words)

See running servers, background processes like Gradle, and any session that
did NOT get killed by closing the terminal — in BOTH the mini card and the
maximized page. Flat PocketShell style: hairlines, mono small-caps, accent
text; no rounded cards.

## What the device taught us (probed on the real device, read-only)

| Fact | Evidence | Consequence |
|---|---|---|
| `/proc` shows only the app's own UID (11 pids) | `ls /proc` | no uid filtering needed; every readable pid is ours |
| Guest processes are visible with GUEST cwd | Gradle daemon `cwd=/root/.gradle/daemon/8.14.5` | no host→guest path mapping needed for processes |
| Gradle daemon is directly detectable | pid 13925: `java … GradleDaemon`, ppid 1, tty 0, 1.07 GB RSS, 34 threads | "background process like gradle" is a pure argv + /proc fact |
| Terminal-attached vs detached is a kernel fact | `sh`/`claude`: tty_nr=34816 (pts); dbus/gradle: tty_nr=0, ppid 1, own sid | "survives closing the terminal" = no controlling tty |
| TracerPid is NOT a guest test | dbus/gradle have TracerPid 0 (psx engine) | guest = own-UID processes minus infrastructure (`app.pocketshell*`, `libproot.so`, `libpsx.so`) |
| Elapsed time = `uptime − starttime/100` | starttime field 22, CLK_TCK=100 | live uptime without storing anything |

`nohup cmd &` keeps its tty but ignores SIGHUP (`SigIgn` bit 0 in
`/proc/<pid>/status`) — a third survival state, read as a fact, not guessed.

## Data model (pure, JVM-tested — `ServersProcesses.kt`)

- `ProcRecord` — pid, ppid, comm, argv, state, tty, sid, pgrp, tpgid, threads,
  start ticks, cpu ticks, rss, cwd, sigHupIgnored.
- `Survival` — `DETACHED` (no controlling tty) · `NOHUP` (tty, SIGHUP ignored)
  · `ATTACHED` (dies with its terminal).
- `ProcKind` — SERVER (a verified listener's pid) · BUILD (Gradle/Kotlin
  daemon, wrapper, mvnd, sbt) · MUX (tmux/screen/zellij/dtach/abduco) · SHELL
  (a shell with a tty) · TOOL (claude/codex/opencode/kilo/…) · SYSTEM (dbus,
  ssh-agent) · OTHER. Rule-based from comm/argv, shown as a LABEL, never as
  more than the rule says.
- Views: `servers` (listeners, unchanged) · `background` (guest, SURVIVAL !=
  ATTACHED, not SERVER, not MUX — a terminal-bound process, e.g. a foreground
  `./gradlew` run, belongs to its SESSION's foreground job, not to background) ·
  `sessions` (MUX + top-level SHELL with a tty, labelled by the tty's foreground
  job via `tpgid`, with descendant count) · `outlive` (survival != ATTACHED AND
  reparented to init AND not SYSTEM — a detached child of a still-running
  process will survive too, but is a child JOB, not a daemon, so it is not
  counted; it still appears under BACKGROUND with a note).
- `ProcessScanner(procRoot)` — one pass: readdir → per-pid `stat` + `status` +
  `cmdline` + `cwd`, ALL fresh each scan (a shell that `cd`s or `exec`s must not
  keep its old directory/name). Bounded (400 pids; the NEWEST are kept). CPU%
  reuses the tick-delta rule, keyed by identity (pid + start ticks).

## Cost discipline

Process scan runs when the pid set changed, or on every tick while the FULL
page is visible (CPU%/RSS must move), else every 6th tick (30 s). Paused ⇒
nothing. No guest exec — Stop remains the ONLY exec.

## UI — two modes, one language

**MINI (card).** Answer in one glance. Header counts `2 up · 3 bg · 1 ses`;
rows are servers first, then session keepers, then background by memory:
`● :3000 node` · `○ gradle daemon  1.1 GB  7m` · `▪ tmux`. A footer line
`4 OUTLIVE THE TERMINAL` carries the headline fact. ROOMY adds cwd, HTTP chip,
beats, uptime/CPU. Tap a row → in-card detail (facts + TERMINAL/COMPANION). No
destructive action on the card (unchanged rule).

**FULL (maximized) — a task manager, not a stretched card.** App bar
(Servers, device strip, pause/refresh/terminal) + bottom nav / side rail:
`OVERVIEW · SERVERS · BACKGROUND · SESSIONS`.
- OVERVIEW: stat strip (servers up · background · sessions · outlive terminal),
  TOP MEMORY, TOP CPU, OUTLIVE THE TERMINAL list.
- SERVERS: the existing dashboard + detail (two-pane when wide), unchanged.
- BACKGROUND: search + sort (MEMORY/CPU/UPTIME/NAME), ruled rows with a state
  rule, detail with full argv and survival explanation.
- SESSIONS: keepers first ("SURVIVES CLOSE"), then open terminals; descendant
  tree in the detail.
- Actions (FULL only): STOP (SIGTERM) and FORCE (SIGKILL), both confirmed with
  the exact command; refusal before any dialog for pid ≤ 1; killing a terminal
  shell says it closes that terminal.

## Mutations

`ServersOps` generalises from "stop a server" to "signal a process": positional
args only (`kill -s "$2" "$1"`), signal from a fixed enum, honest outcome, forced
rescan. A request is CONSUMED before it runs (this state outlives the composition,
so a cancelled effect must never re-fire a kill); the pid's identity (start
ticks) is re-verified right before exec, and PocketShell's own helper processes
are refused. "Stopped" is only said when the rescan shows the process gone —
otherwise "sent … still running". Exec goes through `GuestLaunch` (the host's `buildLaunchSpec` drifted —
the same `$default` failure the Git plugin hit).

## Also fixed on the way

`ServersLayout.from` demanded 420 dp width for FULL; a maximized portrait phone is
~411 dp wide, so full-screen rendered the card. FULL is now height-first.

## Tests

Parsers (stat/status/tty/sighup), classifier table, survival, grouping, tree,
foreground-job label, scanner over a fake /proc, a real-/proc smoke test
(skipped where /proc is absent), generalised ops, layout tiers, contract.

## Known limits (stated, not hidden)

- FULL is inferred from the container size (≥320×400 dp, height-first), not from a
  host flag — the host API has no "maximized" signal. The destructive actions are
  confirm-gated, and a card is never that tall on the devices measured, but a host
  that ever gives a card ≥400 dp of height would show them there.
- The card's row count is an estimate from the card's measured height; it is
  conservative and the footer carries "+N more", but it is not verified on a device.

## Not in scope (stated, not hidden)

- Seeing INSIDE a tmux session (windows/panes) — would need a guest exec.
- Processes of other UIDs / the host app's own helpers.
- Persisting history across app restarts (beats stay "this session", by design).
- The Sync plugin has the same `buildLaunchSpec$default` call; not touched here.
