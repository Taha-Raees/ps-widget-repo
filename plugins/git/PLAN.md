# Git Home Application — engineering plan

The Git plugin as a complete, production-quality Git workstation: Git-first,
a full-screen Home Application when maximized, a compact useful card when
minimized, GitHub as an optional service layer, terminal as the escape hatch.
This plan is the contract for the work; each milestone ends with the module
compiling and its unit tests green.

## 1. Baseline (audited 2026-09-25)

Repo `ps-widget-repo`, branch `feature/agent-zc-git-home-overhaul` (at
main `c1a9fb4`), carrying an INTERRUPTED refactor as uncommitted work:

Already done (data layer — complete, high quality):
- `GitProbe.kt` — batched read-only dashboard probe (git binary, repo
  discovery under `$HOME`/`$HOME/Projects`, per-repo porcelain status +
  last 5 commits + local branches w/ upstream tracking + remotes), idle
  gate, strict `@@`-marker protocol.
- `GitStatusParser.kt` — `status --porcelain=v1 -b` (unchanged).
- `GitPresentation.kt` — grouping (CONFLICTS/STAGED/UNSTAGED), glyphs,
  URL credential redaction, age labels, line caps.
- `GitOps.kt` (new) — THE mutation seam: every mutating git verb as an
  argv list, `network`/`destructive` flags, confirm policy, argument
  validation (ref-format, path safety, hash), timeouts, `GitOpRunner`
  with re-validation + `GitOpOutcome` (git's own words only).
- `GitReads.kt` (new) — `GitReader`: per-screen bounded reads (history
  window, remote branches, stashes, ls-files, commit detail w/ sentinel,
  capped diffs through `GitDiffParser`).
- `GitFiles.kt` (new) — derived directory browsing over tracked+untracked
  paths, search mode, honest caps.
- `GitDiffParser.kt` (new) — unified-diff model with hunk line numbering.
- `GitQuoted.kt` (new) — C-string path decoding, tab-field splitting.

Broken / missing (what this plan fixes):
- `GitApp.kt` gutted to a 73-line stub — `Content()` not implemented; the
  module DOES NOT COMPILE.
- `GitDiffParser.kt` structurally broken (the `Hunk` data class split in
  half; its tail stranded at end-of-file).
- `GitFiles.kt` structurally broken (object closes early; `search`,
  `cap`, constants orphaned outside).
- The UI layer (`GitScreens`/`GitDetails`/`GitComponents` per the stub's
  own doc) was never written.
- `GitLayoutTest` pins `GitLayout`, which no longer exists.
- `GitAppContractTest` pins the OLD architecture (`probe.showCommit`,
  `head -n 12` script caps, read-only-only contract).

Host contract (verified read-only in the PocketShell checkout):
- `HomeApplication.Content(HomeAppContext)` renders at whatever size the
  host gives — the same composable is the minimized card AND the maximized
  full-screen page; the app adapts via `BoxWithConstraints` tiers.
- `HomeAppContext(nav, runtimeState, stateStore)`; `WidgetNav` seams:
  `openTerminal/openLinuxShell/openDiagnostics/openGuestFiles/openWidgetSettings`.
- `stateStore.forApp(id) { … }` — process-scoped state slot.
- ONE sanctioned exec seam: `RuntimeProcessLauncher.buildLaunchSpec` +
  `GuestExecutionProfile.PACKAGE_OPERATION` +
  `ProcessBuilderGuestCommandRunner` (pinned by contract test).
- Plugin ships as dex via `tools/build-plugin.sh git <version>`
  (`releases/git/<v>/plugin.jar`, sha pinned in `catalog.json`).

## 2. Architecture

Layering (strict, JVM-pure below the app file):

```
GitPlugin.kt            entry point (unchanged)
GitApp.kt               application object, Content(), exec seam,
                        GitState (nav stack + screen state + op runner wiring)
GitLayout.kt            responsive tiers (pure)
GitComponents.kt        shared composables (rows, sections, banners, sheets,
                        confirm dialog, tabs, states)
GitScreens.kt           dashboard + repository workspace tabs
GitDetails.kt           drill-downs (diff, commit, file, branch, stash, host)
GitHost.kt              provider seam + GitHub-over-gh implementation (M4)
GitProbe.kt             batched dashboard probe (read-only, pinned)
GitReader.kt (GitReads.kt)   per-screen bounded reads
GitOps.kt               mutation argv + confirm policy + runner
GitFiles.kt / GitDiffParser.kt / GitQuoted.kt / GitStatusParser.kt /
GitPresentation.kt      pure models and rules
```

Invariants (pinned by tests):
1. Read discipline: git is the source of truth. Dashboard = one batched
   read-only exec, idle-gated. Every deeper screen = one bounded read on
   open, with an age label. After every mutation = forced rescan.
2. Mutations only through `GitOp` (argv lists, never shell strings);
   `GitOps.refusal` re-validates inside the runner; destructive ops and
   consequential ops ALWAYS confirm; outcomes show git's own words.
3. Exec only through the sanctioned guest seam; no process-table reading.
4. Probe script stays strictly read-only (contract test greps it).
5. Theme tokens only (`HomeTokens` / `TerminalTheme`), no private colors.
6. State lives in the `stateStore.forApp` slot; back pops the innermost
   screen; external navigation only via `WidgetNav`.
7. Honest degradation: git missing, runtime not ready, probe failed,
   empty — every state says what is true, never a fake "no data".

Navigation model: one `Screen` sealed hierarchy + a stack in `GitState`
(dashboard → repo(tab) → diff/commit/file/branch/stash/host pages);
`BackHandler(enabled = stack.isNotEmpty())` pops innermost; only the
dashboard's back reaches Home.

Responsive tiers:
- `COMPACT` (phone card) — dashboard: repo chips + pane, one-line rows.
- `ROOMY` (large card) — adds paths, sublines, status header.
- `FULL` (maximized) — the workstation: dashboard list + workspace with
  tab bar (Overview, Changes, History, Branches, Files, Remotes, +GitHub
  when a host is detected), split/panes where width allows.

## 3. Milestones (start → end)

M0 — REPAIR & BASELINE (this commit series)
- Fix `GitDiffParser`, `GitFiles` structure; restore `GitLayout`.
- Rewrite `GitAppContractTest` to pin the NEW architecture (read-only
  probe script; mutations confined to `GitOps.kt`; confirm policy;
  exec seam; theme; state ownership).
- Gate: `:plugins:git:compileDebugKotlin` + `testDebugUnitTest` green.

M1 — UI FOUNDATION (screens read-only)
- `GitLayout`, `GitComponents`, `GitState` + nav stack, `Content()`.
- Dashboard: repo list (name, branch, tracking glyphs, dirty dot,
  summary), search + dirty filter, per-process favourites, refresh with
  age label, honest states (no git / not ready / failed / empty).
- Workspace tabs: Overview (branch/upstream/summary/log/branches/remotes),
  Changes (conflicts/staged/unstaged + stash count), History (windowed
  log, load-more), Files (browser + search + untracked descent note),
  Branches (local + remote), Remotes (list, sanitized URLs, fetch).
- Diff drill-down (worktree/index/commit), commit detail page.
- Gate: compile + tests green; card (COMPACT) still renders.

M2 — MUTATIONS (the workstation becomes an operator)
- Staging: stage/unstage (file, all), discard (file, all tracked),
  delete untracked; commit composer (subject+body, amend) with the
  identity missing-state handled honestly; stash push/pop/drop.
- Branch ops: switch, create, rename, delete (safe then force-after-refusal),
  merge, rebase-onto; fetch/pull (ff-only)/push (+set-upstream).
- Conflict surfacing: CONFLICTS group first with resolution guidance
  (terminal handoff named); op banner shows git's exact failure.
- Extra ops: cherry-pick, revert, reset (soft/mixed/hard — destructive
  confirms), checkout commit (detach), tag create/delete.
- Gate: compile + tests green; confirm policy pinned by tests
  (destructive ⇒ ConfirmSpec, safe reads never).

M3 — FILES & HISTORY DEEP (advanced reads)
- Blame (capped), file history (`log --follow -- path`), commit graph
  (text `--graph` rendering in History), reflog, tags list.
- Worktree list (+add/remove ops), submodule status, LFS detection +
  file list, sparse-checkout surfacing, git identity read/set.
- Hunk/line staging where practical (`git apply --cached` plumbing) —
  falls back to whole-file staging honestly if a patch cannot be built.
- Gate: compile + tests green.

M4 — GITHUB LAYER (provider seam)
- `GitHost` interface; `GitHub` provider riding `gh` in the guest
  (`command -v gh` detection; absent → honest "gh not installed" state
  with a Linux Shell handoff). Tokens stay inside gh's own guest store —
  the plugin never reads or stores one (`gh auth status` shows state).
- Reads: repo metadata, open PRs, PR detail/comments/files, issues,
  Actions runs (+ log tail), releases, notifications.
- Ops (network + confirm): PR create from current branch, PR merge,
  issue create/close/comment.
- Provider seam keeps GitLab/Gitea future-work additive.
- Gate: compile + tests green (parsers JVM-tested on fixture output).

M5 — MINIMIZED CARD & POLISH
- COMPACT tier: chips + pane (branch, glyphs, dirty dot, staged/changed
  counts), tap-through to the workspace; no giant cards, no dead ends.
- Loading/empty/error states on every screen; long-list caps with real
  "+N more"; age labels on cached reads; destructive confirms worded
  specifically.
- Gate: compile + tests green.

M6 — PUBLISH
- Version 3.0.0, README + PLAN updates, `tools/build-plugin.sh git 3.0.0
  --update-catalog`, `validate-catalog.py` green.
- Device gate (owner): install the artifact in PocketShell, exercise
  dashboard → workspace → stage → commit → push on a real repo.

## 4. Parallel workstreams

The data layer (M0 repair) is independent of UI composition; within M1+
the safe parallel cuts are: pure models/parsers (+ their JVM tests) vs.
Compose screens vs. contract tests. All touch disjoint files. Only
`GitApp.kt` (state/nav) and `GitOps.kt` (op vocabulary) are shared edit
surfaces — serialized by design. GitHub (M4) is isolated behind
`GitHost.kt` and cannot conflict with core work.

## 5. Testing strategy

- JVM unit tests per pure unit (existing style: fixture git output in,
  model out; refusal tables; confirm-policy tables; layout thresholds).
- `GitAppContractTest` (source-reading pins): read-only probe script;
  mutations exist ONLY in GitOps.kt; exec seam; theme tokens; state
  ownership; confirm-before-destructive; no invented navigation.
- New tests: GitOpsTest, GitReaderTest, GitDiffParserTest, GitFilesTest,
  GitHostTest (fixture gh output), layout tests extended for FULL.
- Gate per milestone: `assembleDebug` + `testDebugUnitTest` green.
- Final: `build-plugin.sh` artifact + catalog validation; on-device
  verification is the owner's acceptance gate.

## 6. Completion definition

- Full-screen: repository manager + six-tab workspace + drill-downs +
  GitHub tab (when gh present) all usable touch-only.
- Minimized: the card answers "what is happening in my repos right now"
  in one glance and opens the workspace.
- Core Git work (stage → commit → branch → merge → push/pull → stash →
  inspect) completable in the GUI with confirms; everything else has an
  honest terminal handoff.
- Useful without GitHub; useful without knowing every git command.
- Tests green, artifact published, catalog pinned, docs current.

## 7. Risks / blockers

- Device verification of the maximized workspace needs the host app's
  plugin-install flow — prepared here, exercised by the owner (gate).
- `gh` presence in the guest rootfs varies; every GitHub screen carries
  an honest absent-state (M4).
- Hunk staging (M3) depends on patch plumbing (`git apply --cached`);
  shipped behind whole-file staging with an honest fallback if flaky.
- proot guest exec latency bounds list sizes; all reads are capped and
  say so (existing discipline, kept).
