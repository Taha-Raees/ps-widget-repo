package app.pocketshell.widget.sync

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import app.pocketshell.packages.ProcessBuilderGuestCommandRunner
import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeProcessLauncher
import app.pocketshell.runtime.RuntimeState
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.terminal.ShellEnvironment
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme
import app.pocketshell.widget.HomeAppContext
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.HomeAppSpec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M8.4 — SYNC/BACKUP: the PocketShell Home Application that answers, in
 * one card, "what backup/sync do I have, and when did it actually last
 * run — verified?" — and the honest answer structures the whole design:
 *
 *   overview (N profiles; each row: backend, source → destination, last
 *             run stated as NEVER RUN until a real run record exists)
 *     ↓ tap a row
 *   detail (full specs, probed backend availability, path visibility,
 *             DRY-RUN preview, additive-only RUN NOW, TERMINAL/LINUX)
 *     ↓ back (the card's OWN back handler — only from the overview does
 *       back reach the rest of Home)
 *
 * TRUTHFULNESS CONTRACT (pinned by the sync test suite):
 *   - a profile is a RECORD OF INTENT, not a backup; the card never
 *     states that user data is safe, protected, or that a backup was
 *     verified;
 *   - the PREVIEW is a DRY RUN (`rsync -n --itemize-changes`, `rclone
 *     sync --dry-run --combined -`) — by the tools' own contracts it
 *     writes nothing;
 *   - RUN NOW (M8.4.1) is the one REAL action, and it is ADDITIVE-ONLY
 *     by construction: `rsync -a` carries no removing flag, and rclone
 *     runs "copy", never its deleting mode — it adds and updates at
 *     the destination and removes nothing. M8.4.3: EVERY finished run
 *     is recorded from the REAL process exit (0 = OK, anything else =
 *     FAILED), so failure history is visible; a run that never exited
 *     records nothing;
 *   - VERIFY (M8.4.3) is the dry-run plumbing re-labeled: "up to date"
 *     is claimed ONLY on a clean zero-change dry run, the verdict is
 *     holder-only (never stored — a check is not a run), and a result
 *     with errors verifies nothing;
 *   - runs the user starts OUTSIDE this card are invisible here — the
 *     card records only runs it performed itself;
 *   - backends are probed with `command -v` — an absent rsync/rclone is
 *     the honest "not installed" state with the real install hint (and
 *     an in-card `apk add`), never a silent skip;
 *   - no credential is ever stored or asked for (ssh remotes reference
 *     the user's existing guest ~/.ssh; rclone remotes the user's own
 *     rclone.conf), and no size is ever fabricated (neither dry-run
 *     format reports sizes; the card reports COUNTS of parsed entries).
 *
 * STATE (M8.4.2): everything the card shows and everything the user is
 * mid-way through lives in ONE process-scoped holder ([SyncState] via
 * the shared HomeAppStateStore) — screens switch by composition, so
 * remember-held state died on every navigation. The holder keeps the
 * cached snapshot, the probe's idle-gate memory and the half-filled
 * form draft alive across page disposal; the probe's AUTO_RESCAN gate
 * stays the only periodic re-probe path (refresh-on-purpose, never
 * refresh-on-arrival).
 *
 * Performance: the overview probe is ONE batched guest exec (GitProbe's
 * batching discipline), idle-gated at AUTO_RESCAN_MS, lifecycle-gated to
 * RESUMED, on Dispatchers.IO; the dry run and the real run are
 * manual-only bounded execs.
 */
object SyncApp : HomeApplication() {

    /** The registry id (HomeApplications registers this application). */
    const val SYNC_ID = "sync"

    override val spec = HomeAppSpec(
        id = SYNC_ID,
        name = "Sync",
        summary = "Backup/sync profiles — probed backends, dry-run previews, additive-only runs",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        val repository = remember { SyncRepository(appContext) }
        // M8.4.2 — ONE process-scoped holder: the screen state, the in-card
        // navigation, the manual run/preview/install ticks, the probe
        // ITSELF (its idle-gate memory is part of the cache) and the
        // half-filled new-profile draft. Leaving Home disposes this
        // composition; the holder survives it, so returning renders the
        // cached snapshot instantly and costs no guest exec.
        val state = remember {
            context.stateStore.forApp(SYNC_ID) { SyncState(SyncProbe(guestExec(appContext))) }
        }
        // null until DataStore's first emission — the honest loading state.
        val profilesState by repository.profiles.collectAsState(initial = null)
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current

        suspend fun runScan() {
            val profiles = state.profilesRef.value
            state.scannedIds.value = profiles.map { it.id }
            state.scannedOnce = true
            val result = withContext(Dispatchers.IO) { state.probe.snapshot(profiles) }
            state.ui = when (result) {
                is ProbeResult.Failed -> SyncUi.ProbeFailed(result.reason)
                is ProbeResult.Done -> SyncUi.Ready(result.snapshot, profiles)
            }
        }

        LaunchedEffect(context.runtimeState) {
            if (context.runtimeState != RuntimeState.READY) {
                state.ui = SyncUi.Unavailable
                return@LaunchedEffect
            }
            lifecycleOwner.lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.RESUMED) }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    // The holder's cached snapshot is already on screen;
                    // only the probe's idle gate decides whether RETURNING
                    // to the card costs a probe. The gate passes on the
                    // first ever visit and when the last scan is stale.
                    if (state.probe.shouldFullScan(System.currentTimeMillis())) runScan()
                    while (true) {
                        delay(SyncProbe.TICK_MS)
                        // M8.4.3 — the render clock for relative times
                        // ("2h ago · OK"): a pure state write, no exec.
                        state.nowMs = System.currentTimeMillis()
                        // The idle gate: a tick that fires too soon after
                        // the last scan does NOTHING — no guest exec while
                        // the card sits open and idle.
                        if (!state.probe.shouldFullScan(System.currentTimeMillis())) continue
                        runScan()
                    }
                }
        }

        // Profile edits rescan immediately (a fresh profile's visibility is
        // the first thing the user wants to know); the first emission is
        // skipped when a scan already covered it — the open-race dedupe.
        LaunchedEffect(profilesState, context.runtimeState) {
            val list = profilesState ?: return@LaunchedEffect
            state.profilesRef.value = list
            if (context.runtimeState != RuntimeState.READY) return@LaunchedEffect
            val ids = list.map { it.id }
            if (ids == state.scannedIds.value) return@LaunchedEffect
            if (state.scannedOnce) runScan()
        }

        val ready = state.ui as? SyncUi.Ready
        val profiles = ready?.profiles.orEmpty()

        // M8.4.5 — PATH COMPLETION: typing "/" (or "~") in the form lists
        // the guest directory at that path — ONE bounded headless ls per
        // request, entries capped; picking an entry appends it, and a
        // directory entry (trailing "/") lists the next level. No shell,
        // the path rides as argv.
        LaunchedEffect(state.listRequest) {
            val dir = state.listRequest ?: return@LaunchedEffect
            kotlinx.coroutines.delay(250) // debounce rapid typing
            val target = state.listTarget ?: return@LaunchedEffect
            val out = withContext(Dispatchers.IO) {
                guestExec(appContext).exec(listOf("/bin/ls", "-1Ap", dir), 5_000L)
            }
            val names = if (out.error != null || out.exitCode != 0) {
                emptyList()
            } else {
                out.stdout.lines()
                    .map { it.trimEnd('\r') }
                    .filter { it.isNotBlank() && it != "." && it != ".." }
                    .sortedWith(compareByDescending<String> { it.endsWith("/") })
                    .take(24)
            }
            if (state.listRequest == dir) {
                if (target == "source") state.sourceSuggestions = names
                else state.destSuggestions = names
            }
        }

        // A selection whose profile was deleted degrades to the overview —
        // never a stale detail page.
        val selected = state.detailId?.let { id -> profiles.firstOrNull { it.id == id } }
        val closeForm = {
            // Cancel is deliberate: it discards the draft. Navigation away
            // does not — the holder keeps the half-filled form alive.
            state.formOpen = false
            state.resetDraft()
        }

        BackHandler(enabled = state.formOpen || selected != null) {
            if (state.formOpen) closeForm() else state.detailId = null
        }

        // A fresh detail page starts with a clean preview/verify/run slate.
        LaunchedEffect(state.detailId) {
            state.previewUi = PreviewUi.Idle
            state.previewTick = 0
            state.runUi = RunUi.Idle
            state.verifyUi = VerifyUi.Idle
            state.verifyTick = 0
        }

        // M8.4.1 — the REAL run: additive-only (rsync -a with no removing
        // flag; rclone copy, never its deleting mode), bounded. M8.4.3 —
        // EVERY finished run is recorded via [runRecord]: exit 0 = OK,
        // any other exit = FAILED, both visible in the profile's history;
        // a run with no exit (timeout, destroyed process) records nothing
        // — a run that happened is a fact, and so is a failure.
        LaunchedEffect(state.runTick) {
            if (state.runTick == 0) return@LaunchedEffect
            val profile = state.detailId?.let { id -> state.profilesRef.value.firstOrNull { it.id == id } }
                ?: return@LaunchedEffect
            val snapshot = (state.ui as? SyncUi.Ready)?.snapshot ?: return@LaunchedEffect
            // A starting run invalidates the point-in-time VERIFY result.
            state.verifyUi = VerifyUi.Idle
            state.runUi = RunUi.Running
            // M8.4.4 — the headless pre-step: the destination folder is
            // created if missing (local dests only), so a one-tap backup
            // never dies on "destination does not exist".
            val prepared = withContext(Dispatchers.IO) { state.probe.prepare(profile) }
            val result = if (prepared.exitCode != null && prepared.exitCode != 0) {
                prepared
            } else {
                withContext(Dispatchers.IO) {
                    state.probe.runNow(profile, snapshot.rsync.path, snapshot.rclone.path)
                }
            }
            state.runUi = RunUi.Done(result)
            runRecord(profile, result, SyncRepository.now())?.let { record ->
                repository.add(record)
                // No rescan fires for a recorded run (the profile ids are
                // unchanged), so the ui state's list is refreshed HERE —
                // the STATUS row and the overview glyph show the real
                // record now, not one AUTO_RESCAN_MS from now.
                val refreshed = SyncProfiles.upsert(state.profilesRef.value, record)
                state.profilesRef.value = refreshed
                (state.ui as? SyncUi.Ready)?.let { ready ->
                    state.ui = SyncUi.Ready(ready.snapshot, refreshed)
                }
            }
        }

        // M8.4.3 — VERIFY: the existing zero-write dry-run plumbing,
        // interpreted as a point-in-time destination check. "Up to date"
        // is claimed ONLY on a clean zero-change dry run; the verdict is
        // HOLDER-ONLY — a check is not a run, and the store records runs.
        LaunchedEffect(state.verifyTick) {
            if (state.verifyTick == 0) return@LaunchedEffect
            val profile = state.detailId?.let { id -> state.profilesRef.value.firstOrNull { it.id == id } }
                ?: return@LaunchedEffect
            val snapshot = (state.ui as? SyncUi.Ready)?.snapshot ?: return@LaunchedEffect
            state.verifyUi = VerifyUi.Running
            val result = withContext(Dispatchers.IO) {
                state.probe.dryRun(profile, snapshot.rsync.path, snapshot.rclone.path)
            }
            state.verifyUi = when (result) {
                is DryRunResult.Done ->
                    VerifyUi.Done(interpretVerify(result.preview, System.currentTimeMillis()))
                is DryRunResult.Failed -> VerifyUi.Failed(result.reason)
            }
        }

        // M8.4.1 — install a missing backend with the guest's own apk
        // (one bounded `apk add`), then re-probe so availability flips.
        LaunchedEffect(state.installTick) {
            if (state.installTick == 0) return@LaunchedEffect
            val profile = state.detailId?.let { id -> state.profilesRef.value.firstOrNull { it.id == id } }
                ?: return@LaunchedEffect
            state.installUi = RunUi.Running
            val result = withContext(Dispatchers.IO) {
                state.probe.installBackend(profile.backend)
            }
            state.installUi = RunUi.Done(result)
            runScan()
        }

        // The manual dry run — one tick, one bounded exec, result assigned
        // only if this effect is still the current one.
        LaunchedEffect(state.previewTick) {
            if (state.previewTick == 0) return@LaunchedEffect
            val profile = state.detailId?.let { id -> state.profilesRef.value.firstOrNull { it.id == id } }
                ?: return@LaunchedEffect
            val snapshot = (state.ui as? SyncUi.Ready)?.snapshot ?: return@LaunchedEffect
            state.previewUi = PreviewUi.Running
            val result = withContext(Dispatchers.IO) {
                state.probe.dryRun(profile, snapshot.rsync.path, snapshot.rclone.path)
            }
            state.previewUi = PreviewUi.Done(result)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layout = SyncLayout.from(maxWidth.value, maxHeight.value)
            when {
                state.formOpen -> SyncForm(
                    state = state,
                    onCancel = closeForm,
                    onSave = { backend, source, destination ->
                        state.formOpen = false
                        state.resetDraft()
                        scope.launch {
                            repository.add(
                                SyncProfile(
                                    id = SyncRepository.newId(),
                                    backend = backend,
                                    source = source,
                                    destination = destination,
                                    createdAtMs = SyncRepository.now(),
                                ),
                            )
                        }
                    },
                )
                selected != null -> SyncDetail(
                    profile = selected,
                    status = ready?.snapshot?.pairs?.getOrNull(profiles.indexOf(selected)),
                    snapshot = ready?.snapshot,
                    previewUi = state.previewUi,
                    runUi = state.runUi,
                    onRunNow = { state.runTick++ },
                    installUi = state.installUi,
                    onInstall = { state.installTick++ },
                    verifyUi = state.verifyUi,
                    onVerify = { state.verifyTick++ },
                    nowMs = state.nowMs,
                    roomy = layout == SyncLayout.ROOMY,
                    maxEntries = if (layout == SyncLayout.ROOMY) {
                        SyncLayout.DETAIL_MAX_ENTRIES_ROOMY
                    } else {
                        SyncLayout.DETAIL_MAX_ENTRIES_COMPACT
                    },
                    onBack = { state.detailId = null },
                    onPreview = { state.previewTick++ },
                    onDelete = {
                        scope.launch {
                            repository.remove(selected.id)
                            state.detailId = null
                        }
                    },
                    onOpenTerminal = { context.nav.openTerminal() },
                    onOpenLinuxShell = { context.nav.openLinuxShell() },
                )
                else -> SyncOverview(
                    ui = state.ui,
                    profiles = profiles,
                    snapshot = ready?.snapshot,
                    layout = layout,
                    nowMs = state.nowMs,
                    onOpenDetail = { state.detailId = it.id },
                    onOpenForm = {
                        // A freshly opened form starts with a clean slate —
                        // a SAVE attempt from an earlier visit must not
                        // haunt it ("source is required" on first paint).
                        state.formSubmitted = false
                        state.sourceSuggestions = emptyList()
                        state.destSuggestions = emptyList()
                        state.formOpen = true
                    },
                    onOpenLinuxShell = { context.nav.openLinuxShell() },
                    onOpenDiagnostics = { context.nav.openDiagnostics() },
                )
            }
        }
    }

    /**
     * The real exec: the sanctioned non-PTY guest path — the SAME
     * buildLaunchSpec + background-runner pairing GitApp uses (single exec
     * infrastructure, no duplicate proot logic). The minimal
     * PACKAGE_OPERATION profile is the right mount configuration for a
     * read-only probe and a dry run (the tools write nothing); DNS/network
     * quirks surface as the honest failure text of a remote attempt.
     */
    private fun guestExec(appContext: Context): SyncProbe.GuestExec =
        SyncProbe.GuestExec { argv, timeoutMs ->
            val storage = RuntimeStorage(appContext.noBackupFilesDir)
            val spec = RuntimeProcessLauncher.buildLaunchSpec(
                nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
                rootfsDir = storage.rootfsDir,
                hostCwd = ShellEnvironment.homeDir(appContext),
                prootTmpDir = File(appContext.cacheDir, "proot-tmp").apply { mkdirs() },
                guestCommand = argv,
                profile = GuestExecutionProfile.PACKAGE_OPERATION,
                // M8.4.5 — Android storage as a backup DESTINATION: the
                // app's external-files dir is bound read-write at
                // /mnt/android, so "/mnt/android/backup" lands on shared
                // storage (Android/data/app.pocketshell/files/backup —
                // reachable from a computer over USB; no permissions
                // needed; the app owns it).
                extraBinds = listOf(
                    "${appContext.getExternalFilesDir(null)!!.absolutePath}:/mnt/android",
                ),
            )
            val process = ProcessBuilderGuestCommandRunner().start(spec)
            try {
                process.waitFor(timeoutMs)
            } catch (t: Throwable) {
                process.destroy()
                throw t
            }
        }
}

/**
 * M8.4.2 — the SYNC application's process-scoped state holder: everything
 * the card used to keep in `remember` (and so lost on every navigation,
 * because screens switch by composition):
 *
 *   - the screen's ui state and the in-card navigation (detail id, form
 *     open), so returning renders the cached snapshot instantly;
 *   - the manual preview/run/install ticks and their UI states;
 *   - the [SyncProbe] ITSELF — its idle-gate memory (hasScanned /
 *     lastScanAtMs) IS part of the cache; a fresh probe would re-exec on
 *     the first tick after every return;
 *   - the half-filled new-profile draft, with the `formSubmitted` flag
 *     that keeps validation honest: the message renders only after the
 *     first SAVE attempt, and any input change clears it again.
 *
 * Owned by the shared HomeAppStateStore; survives page disposal, leaving
 * Home, and configuration changes. Process death still resets it — the
 * profile RECORDS live in the DataStore, as before.
 */
internal class SyncState(val probe: SyncProbe) {

    /** M8.4.5 — path-completion state for the new-profile form. */
    var sourceSuggestions by mutableStateOf<List<String>>(emptyList())
    var destSuggestions by mutableStateOf<List<String>>(emptyList())
    var listTarget by mutableStateOf<String?>(null)
    var listRequest by mutableStateOf<String?>(null)

    /** The screen state (probe-driven, never invented). */
    var ui by mutableStateOf<SyncUi>(SyncUi.Loading)

    /** Which profile's detail page fills the card; null = the overview. */
    var detailId by mutableStateOf<String?>(null)

    /** Whether the new-profile form fills the card. */
    var formOpen by mutableStateOf(false)

    /** The manual dry run: one tick = one bounded guest exec. */
    var previewUi by mutableStateOf<PreviewUi>(PreviewUi.Idle)
    var previewTick by mutableStateOf(0)

    /** M8.4.1 — the REAL run (additive-only) and the backend install. */
    var runUi by mutableStateOf<RunUi>(RunUi.Idle)
    var runTick by mutableStateOf(0)
    var installUi by mutableStateOf<RunUi>(RunUi.Idle)
    var installTick by mutableStateOf(0)

    /**
     * M8.4.3 — VERIFY: the dry-run plumbing interpreted as a point-in-time
     * destination check. Deliberately HOLDER-ONLY, never persisted: a
     * check is not a run, and the store's run history stays exit-code
     * facts. A starting run clears it (reality changed under the verdict).
     */
    var verifyUi by mutableStateOf<VerifyUi>(VerifyUi.Idle)
    var verifyTick by mutableStateOf(0)

    /** The render clock for relative times — ticked by the card's loop. */
    var nowMs by mutableStateOf(System.currentTimeMillis())

    /** A live mirror the lifecycle loop reads at scan time (a captured
     *  parameter would go stale between ticks). */
    val profilesRef = mutableStateOf<List<SyncProfile>>(emptyList())
    val scannedIds = mutableStateOf<List<String>>(emptyList())
    var scannedOnce by mutableStateOf(false)

    /** The new-profile draft — survives navigation, not process death. */
    var draftBackend by mutableStateOf(SyncBackend.RSYNC.name)
    var draftSource by mutableStateOf("")
    var draftDestination by mutableStateOf("")

    /** No premature validation: errors render only after a SAVE attempt. */
    var formSubmitted by mutableStateOf(false)

    /** Cancel (or a completed save) discards the draft on purpose. */
    fun resetDraft() {
        draftBackend = SyncBackend.RSYNC.name
        draftSource = ""
        draftDestination = ""
        formSubmitted = false
        sourceSuggestions = emptyList()
        destSuggestions = emptyList()
    }
}

/** The application's screen state (probe-driven, never invented). */
internal sealed interface SyncUi {
    data object Loading : SyncUi
    data object Unavailable : SyncUi
    data class ProbeFailed(val reason: String) : SyncUi
    data class Ready(val snapshot: SyncSnapshot, val profiles: List<SyncProfile>) : SyncUi
}

/** The detail page's dry-run state. */
internal sealed interface PreviewUi {
    data object Idle : PreviewUi
    data object Running : PreviewUi
    data class Done(val result: DryRunResult) : PreviewUi
}

/** The REAL run's UI state (M8.4.1 — additive-only run, recorded facts). */
internal sealed interface RunUi {
    data object Idle : RunUi
    data object Running : RunUi
    data class Done(val result: RunResult) : RunUi
}

/** The VERIFY control's UI state (M8.4.3 — holder-only, never stored). */
internal sealed interface VerifyUi {
    data object Idle : VerifyUi
    data object Running : VerifyUi
    data class Done(val verdict: VerifyVerdict) : VerifyUi
    data class Failed(val reason: String) : VerifyUi
}

/**
 * The point-in-time answer a dry run gives about the destination. UpToDate
 * is claimed ONLY on a clean run — entries an ADDITIVE run would transfer
 * become [Pending]; a dry run that reported errors verifies nothing.
 */
internal sealed interface VerifyVerdict {
    /** Nothing to transfer at check time — [verifiedAtMs] stamps when. */
    data class UpToDate(val verifiedAtMs: Long) : VerifyVerdict

    /** [count] entries (new/changed/attr — what the additive run copies). */
    data class Pending(val count: Int) : VerifyVerdict

    /** The check itself was incomplete (read errors) — no claim either way. */
    data object Inconclusive : VerifyVerdict
}

/**
 * The pure verify interpretation: NEW/CHANGED/ATTR/HARDLINK entries are
 * what an additive-only run (rsync -a, rclone copy) would transfer;
 * DELETED entries are not (no removing mode is ever run) and ERROR entries
 * mean the check could not see everything — an error-containing result
 * never produces an "up to date" claim.
 */
internal fun interpretVerify(preview: SyncPreview, verifiedAtMs: Long): VerifyVerdict {
    if (preview.errorCount > 0) return VerifyVerdict.Inconclusive
    val wouldTransfer = preview.entries.count {
        it.kind != PreviewKind.DELETED && it.kind != PreviewKind.ERROR
    }
    return if (wouldTransfer == 0) {
        VerifyVerdict.UpToDate(verifiedAtMs)
    } else {
        VerifyVerdict.Pending(wouldTransfer)
    }
}

/**
 * The responsive contract — same geometry as GitLayout (the card
 * dimensions are identical, so the device-derived thresholds carry over):
 * COMPACT keeps one-line rows; ROOMY adds the status header, per-profile
 * sublines, the footer and scrolling rows. Pure + JVM-tested.
 */
internal enum class SyncLayout(
    val showsSubline: Boolean,
    val showsStatusHeader: Boolean,
) {
    COMPACT(showsSubline = false, showsStatusHeader = false),
    ROOMY(showsSubline = true, showsStatusHeader = true);

    companion object {
        const val ROOMY_MIN_WIDTH_DP = 420f
        const val ROOMY_MIN_HEIGHT_DP = 200f
        const val DETAIL_MAX_ENTRIES_COMPACT = 6
        const val DETAIL_MAX_ENTRIES_ROOMY = 20
        const val MAX_NOTICES_SHOWN = 2

        fun from(widthDp: Float, heightDp: Float): SyncLayout =
            if (widthDp >= ROOMY_MIN_WIDTH_DP && heightDp >= ROOMY_MIN_HEIGHT_DP) ROOMY else COMPACT
    }
}

// ------------------------------------------------------------- overview

@Composable
private fun SyncOverview(
    ui: SyncUi,
    profiles: List<SyncProfile>,
    snapshot: SyncSnapshot?,
    layout: SyncLayout,
    nowMs: Long,
    onOpenDetail: (SyncProfile) -> Unit,
    onOpenForm: () -> Unit,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val ready = ui as? SyncUi.Ready
    Column(modifier = Modifier.fillMaxSize()) {
        // Header — the application's title bar, both densities. The
        // new-profile affordance is the compact "+" icon (M8.4.4): the
        // word button spent a whole footer line the rows need.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sync",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (profiles.isNotEmpty()) {
                Text(
                    text = "${profiles.size} profiles",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 11.sp,
                    color = HomeTokens.textDim,
                )
            }
            if (ui !is SyncUi.Unavailable) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(role = Role.Button, onClickLabel = "New profile") { onOpenForm() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "New profile",
                        tint = HomeTokens.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Status area — backend availability, roomy cards only.
        if (layout.showsStatusHeader && profiles.isNotEmpty() && ready != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = backendHeaderText(ready.snapshot),
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                color = HomeTokens.accent,
            )
        }
        Spacer(Modifier.height(6.dp))

        // Rows — the user's profiles; always scrolling, never capped.
        if (profiles.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                profiles.forEachIndexed { index, profile ->
                    val status = ready?.snapshot?.pairs?.getOrNull(profiles.indexOf(profile))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Sync profile ${profile.source}",
                            ) { onOpenDetail(profile) }
                            .padding(vertical = 6.dp),
                    ) {
                        // M8.4.3 — the leading marker is the profile's run
                        // status (○ never run / ✓ OK / ✕ FAILED): the one
                        // glyph that separates configured from succeeded
                        // from failed at a glance.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = SyncProfiles.statusGlyph(profile),
                                fontFamily = TerminalTheme.mono,
                                fontSize = 11.sp,
                                color = when (SyncProfiles.runOutcome(profile)) {
                                    RunOutcome.NEVER_RUN -> HomeTokens.textDim
                                    RunOutcome.OK -> HomeTokens.accent
                                    RunOutcome.FAILED -> HomeTokens.danger
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = SyncProfiles.line(profile),
                                fontFamily = TerminalTheme.mono,
                                fontSize = 12.sp,
                                color = HomeTokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // The probe's source-missing flag keeps a slot:
                            // trailing, danger, COMPACT-only (the roomy
                            // subline states "missing" in words).
                            if (!layout.showsSubline && status?.sourceExists == false) {
                                Text(
                                    text = "!",
                                    fontFamily = TerminalTheme.mono,
                                    fontSize = 11.sp,
                                    color = HomeTokens.danger,
                                )
                            }
                        }
                        if (layout.showsSubline) {
                            Text(
                                text = profileSubline(profile, status, nowMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = HomeTokens.textDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 14.dp, top = 1.dp),
                            )
                        }
                    }
                    if (index != profiles.lastIndex) {
                        HorizontalDivider(color = HomeTokens.hairline.copy(alpha = 0.6f))
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        // Footer — roomy cards only.
                Spacer(Modifier.height(4.dp))
        // The honest state line, every density, every theme. Null when the
        // empty-state block below already carries the card's only fact.
        val bothMissing = ready != null &&
            ready.snapshot.rsync.path == null &&
            ready.snapshot.rclone.path == null
        val anyRun = profiles.any { it.lastRunMs != null }
        val stateLine: String? = when {
            ui is SyncUi.Loading -> "…"
            ui is SyncUi.Unavailable -> "Linux not ready"
            ui is SyncUi.ProbeFailed -> "Could not probe"
            ready != null && bothMissing -> "No sync backend installed"
            ready != null && profiles.isEmpty() -> null
            // M8.4.3 — failures are recorded too, so this line derives
            // from the real history instead of asserting "no runs".
            ready != null && anyRun -> "Runs recorded"
            ready != null -> "Profiles only — no runs recorded"
            else -> "…"
        }
        stateLine?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = if (profiles.isNotEmpty() && ready != null) HomeTokens.accent else HomeTokens.textDim,
                maxLines = 1,
            )
        }
        when {
            ui is SyncUi.Unavailable -> {
                TextButton(onClick = onOpenDiagnostics, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Diagnostics", color = HomeTokens.accent)
                }
            }
            ui is SyncUi.ProbeFailed -> {
                Text(
                    text = ui.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ready != null && profiles.isEmpty() -> {
                // M8.4.3 — the actionable empty state: the fact, the
                // affordance inline (the header "+" stays too), one
                // supporting line that says what RUN NOW actually does.
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "No backup profiles",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = HomeTokens.textPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable(role = Role.Button, onClickLabel = "New profile") { onOpenForm() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "New profile",
                            tint = HomeTokens.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = "A profile pairs a source with a destination; " +
                        "RUN NOW copies new and updated files — it never deletes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ready != null && bothMissing -> {
                Text(
                    text = "Install rsync (main repo) or rclone (community repo) from the Linux Shell.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                TextButton(onClick = onOpenLinuxShell, modifier = Modifier.padding(top = 2.dp)) {
                    Text("Open Linux", color = HomeTokens.accent)
                }
            }
        }
    }
}

// --------------------------------------------------------------- detail

@Composable
private fun SyncDetail(
    profile: SyncProfile,
    status: PathPairStatus?,
    snapshot: SyncSnapshot?,
    previewUi: PreviewUi,
    runUi: RunUi,
    onRunNow: () -> Unit,
    installUi: RunUi,
    onInstall: () -> Unit,
    verifyUi: VerifyUi,
    onVerify: () -> Unit,
    nowMs: Long,
    roomy: Boolean,
    maxEntries: Int,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinuxShell: () -> Unit,
) {
    // DELETE arms on the first tap — one accidental tap must not erase a
    // profile; the confirm is in-card, no dialog, no screen.
    var deleteArmed by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // In-card back header: the ONLY back is the application's own.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Back to Sync") { onBack() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                fontFamily = TerminalTheme.mono,
                fontSize = 14.sp,
                color = HomeTokens.accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Sync",
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                color = HomeTokens.textDim,
            )
        }
        Text(
            text = profile.backend.name.lowercase(),
            fontFamily = TerminalTheme.mono,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = HomeTokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = SyncProfiles.line(profile),
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = HomeTokens.textDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(4.dp))
        SyncRow("SOURCE", SyncProfiles.displayPath(profile.source))
        SyncRow("DEST", SyncProfiles.displayPath(profile.destination))
        SyncRow(
            "SRC STATE",
            pathStateText(remote = status?.sourceRemote ?: SyncProfiles.isRemote(profile.source), exists = status?.sourceExists),
        )
        SyncRow(
            "DEST STATE",
            pathStateText(remote = status?.destinationRemote ?: SyncProfiles.isRemote(profile.destination), exists = status?.destinationExists),
        )
        // M8.4.3 — the STATUS line: relative time · result · real exit,
        // e.g. "2h ago · OK (exit 0)" / "3d ago · FAILED (exit 1)" — the
        // difference between configured, succeeded and failed, in words.
        SyncRow("STATUS", SyncProfiles.statusLine(profile, nowMs))
        // The run's own one-line summary (the tool's last real output).
        profile.lastRunSummary?.let { summary ->
            Text(
                text = summary,
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = HomeTokens.textDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Successful-run transfer stats, parsed from the tool's output —
        // real process evidence, or nothing.
        profile.lastStats?.let { stats ->
            Text(
                text = stats,
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = HomeTokens.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Backend availability — probed, never assumed.
        val backendStatus = when (profile.backend) {
            SyncBackend.RSYNC -> snapshot?.rsync
            SyncBackend.RCLONE -> snapshot?.rclone
        }
        if (backendStatus?.path == null && snapshot != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (profile.backend) {
                    SyncBackend.RSYNC ->
                        "rsync is not installed in the guest."
                    SyncBackend.RCLONE ->
                        "rclone is not installed in the guest (community repository)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = HomeTokens.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
            when (val iu = installUi) {
                RunUi.Running -> Text(
                    text = "Installing ${profile.backend.name.lowercase()} (apk add)…",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 11.sp,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
                is RunUi.Done -> Text(
                    text = iu.result.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (iu.result.exitCode == 0) HomeTokens.accent else HomeTokens.danger,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Visually subordinate on purpose: one slim text line, not
                // a button competing with the card's primary action.
                else -> Text(
                    text = "INSTALL ${profile.backend.name}",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 11.sp,
                    color = HomeTokens.accent,
                    modifier = Modifier
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Install ${profile.backend.name.lowercase()} in the guest",
                        ) { onInstall() }
                        .padding(top = 2.dp),
                )
            }
        }

        // M8.4.1 — RUN NOW: the real, additive-only copy (rsync -a with no
        // removing flag; rclone copy, never its deleting mode). The
        // dry-run preview below is how the user verifies first; this
        // records the run FACT. Compact icon+word control (M8.4.4).
        Spacer(Modifier.height(6.dp))
        when (val ru = runUi) {
            RunUi.Running -> Text(
                text = "RUNNING — copying (additive only)… the first run " +
                    "copies everything and can take several minutes",
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                color = HomeTokens.accent,
            )
            is RunUi.Done -> Text(
                text = ru.result.summary,
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = if (ru.result.exitCode == 0) HomeTokens.runningGreen else HomeTokens.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> {}
        }
        if (backendStatus?.path != null && runUi !is RunUi.Running) {
            CompactAction(
                label = "RUN NOW",
                icon = Icons.Outlined.PlayArrow,
                contentDescription = "Run profile now",
                tint = HomeTokens.accent,
                onClick = onRunNow,
            )
        }
        if (backendStatus?.path != null) {
            Text(
                text = "Adds and updates at the destination — never deletes.",
                style = MaterialTheme.typography.bodySmall,
                color = HomeTokens.textDim,
                maxLines = 1,
            )
        }

        // M8.4.3 — the DRY RUN and VERIFY controls share one line: the
        // same zero-write dry-run plumbing, two readings. DRY RUN shows
        // the per-file preview; VERIFY interprets the run as a point-in-
        // time destination check (verdict below, holder-only, never
        // stored — a check is not a run).
        if (backendStatus?.path != null) {
            Spacer(Modifier.height(4.dp))
            Row {
                if (previewUi == PreviewUi.Idle) {
                    CompactAction(
                        label = "DRY RUN",
                        icon = Icons.Outlined.Search,
                        contentDescription = "Preview dry run",
                        tint = HomeTokens.accent,
                        onClick = onPreview,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (verifyUi !is VerifyUi.Running) {
                    CompactAction(
                        label = "VERIFY",
                        icon = Icons.Outlined.Verified,
                        contentDescription = "Verify destination",
                        tint = HomeTokens.accent,
                        onClick = onVerify,
                    )
                }
            }
            when (val v = verifyUi) {
                VerifyUi.Idle -> {}
                VerifyUi.Running -> Text(
                    text = "Verifying — dry run, nothing is copied…",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 11.sp,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
                is VerifyUi.Failed -> Text(
                    text = v.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                is VerifyUi.Done -> when (val verdict = v.verdict) {
                    is VerifyVerdict.UpToDate -> Text(
                        text = "Destination up to date (verified " +
                            SyncProfiles.agoText(nowMs, verdict.verifiedAtMs) + ")",
                        style = MaterialTheme.typography.bodySmall,
                        color = HomeTokens.accent,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    is VerifyVerdict.Pending -> Text(
                        text = if (verdict.count == 1) {
                            "1 new/changed file would transfer"
                        } else {
                            "${verdict.count} new/changed files would transfer"
                        },
                        fontFamily = TerminalTheme.mono,
                        fontSize = 11.sp,
                        color = HomeTokens.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    VerifyVerdict.Inconclusive -> Text(
                        text = "Verify inconclusive — the dry run reported errors",
                        style = MaterialTheme.typography.bodySmall,
                        color = HomeTokens.danger,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // The dry-run preview area — verify before you run.
        Spacer(Modifier.height(4.dp))
        when (val p = previewUi) {
            // Idle: the DRY RUN control lives in the VERIFY row above.
            PreviewUi.Idle -> {}
            PreviewUi.Running -> Text(
                text = "Running dry run — nothing is copied…",
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                color = HomeTokens.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
            is PreviewUi.Done -> when (val result = p.result) {
                is DryRunResult.Failed -> {
                    Text(
                        text = result.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = HomeTokens.danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onPreview, modifier = Modifier.padding(top = 2.dp)) {
                        Text("RETRY", fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
                    }
                }
                is DryRunResult.Done -> PreviewList(
                    preview = result.preview,
                    maxEntries = maxEntries,
                    roomy = roomy,
                    onRetry = onPreview,
                )
            }
        }

        // The same dry run, typed out for the user's own terminal — the
        // shell is the other honest door (this card's real run is the
        // RUN NOW above).
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (profile.backend) {
                SyncBackend.RSYNC ->
                    "shell: rsync -n --itemize-changes -- ${profile.source} ${profile.destination}"
                SyncBackend.RCLONE ->
                    "shell: rclone sync ${profile.source} ${profile.destination} --dry-run --combined -"
            },
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = HomeTokens.textDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(4.dp))
        Row {
            TextButton(onClick = onOpenTerminal, modifier = Modifier.height(34.dp)) {
                Text("TERMINAL", fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onOpenLinuxShell, modifier = Modifier.height(34.dp)) {
                Text("LINUX", fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    if (deleteArmed) onDelete() else deleteArmed = true
                },
                modifier = Modifier.height(34.dp),
            ) {
                Text(
                    text = if (deleteArmed) "CONFIRM DELETE" else "DELETE",
                    fontFamily = TerminalTheme.mono,
                    color = if (deleteArmed) HomeTokens.danger else HomeTokens.textDim,
                )
            }
        }
    }
}

@Composable
private fun PreviewList(
    preview: SyncPreview,
    maxEntries: Int,
    roomy: Boolean,
    onRetry: () -> Unit,
) {
    Column {
        if (preview.isEmpty) {
            Text(
                text = "Nothing to transfer — destination already in sync.",
                style = MaterialTheme.typography.bodySmall,
                color = HomeTokens.accent,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Text(
                text = previewCountsLine(preview),
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                color = if (preview.errorCount > 0) HomeTokens.danger else HomeTokens.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            val shown = preview.entries.take(maxEntries)
            shown.forEach { entry ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        text = entry.kind.glyph,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 10.sp,
                        color = HomeTokens.textDim,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        text = entry.path,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 10.sp,
                        color = HomeTokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (preview.entries.size > shown.size) {
                Text(
                    text = "+${preview.entries.size - shown.size} more",
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (roomy && preview.summary.isNotEmpty()) {
            Text(
                text = preview.summary.last(),
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = HomeTokens.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 2.dp)) {
            Text("REFRESH PREVIEW", fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
        }
    }
}

// ----------------------------------------------------------------- form

@Composable
private fun SyncForm(
    state: SyncState,
    onCancel: () -> Unit,
    onSave: (SyncBackend, String, String) -> Unit,
) {
    val backend = SyncBackend.entries.firstOrNull { it.name == state.draftBackend } ?: SyncBackend.RSYNC
    val problem = SyncProfiles.validate(state.draftSource, state.draftDestination)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // In-card back header: the ONLY back is the application's own.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Back to Sync") { onCancel() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                fontFamily = TerminalTheme.mono,
                fontSize = 14.sp,
                color = HomeTokens.accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "New profile",
                fontFamily = TerminalTheme.mono,
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                color = HomeTokens.textDim,
            )
        }

        Row {
            SyncBackend.entries.forEach { candidate ->
                TextButton(
                    onClick = {
                        state.draftBackend = candidate.name
                        state.formSubmitted = false
                    },
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(
                        text = candidate.name.lowercase(),
                        fontFamily = TerminalTheme.mono,
                        color = if (candidate == backend) HomeTokens.accent else HomeTokens.textDim,
                    )
                }
            }
        }
        fun sourceChanged(text: String) {
            state.draftSource = text
            state.formSubmitted = false
            if (text.endsWith("/") || text == "~") {
                state.listTarget = "source"
                state.listRequest = SyncProfiles.expandGuestPath(text)
            } else {
                state.sourceSuggestions = emptyList()
            }
        }

        fun destChanged(text: String) {
            state.draftDestination = text
            state.formSubmitted = false
            if (text.endsWith("/") || text == "~") {
                state.listTarget = "dest"
                state.listRequest = SyncProfiles.expandGuestPath(text)
            } else {
                state.destSuggestions = emptyList()
            }
        }

        FormField(
            label = "SOURCE",
            value = state.draftSource,
            onValue = ::sourceChanged,
            placeholder = "guest path, e.g. /root/project — type / to browse",
            suggestions = state.sourceSuggestions,
            onPickSuggestion = { name -> sourceChanged(state.draftSource + name) },
        )
        FormField(
            label = "DEST",
            value = state.draftDestination,
            onValue = ::destChanged,
            placeholder = when (backend) {
                SyncBackend.RSYNC -> "/mnt/backup, /mnt/android/backup or user@host:/path"
                SyncBackend.RCLONE -> "/mnt/backup, /mnt/android/backup or remote:path"
            },
            suggestions = state.destSuggestions,
            onPickSuggestion = { name -> destChanged(state.draftDestination + name) },
        )
        // NO premature validation: the problem renders only after the
        // first SAVE attempt, and any input change clears it again — a
        // fresh, half-filled form never opens with an error in its face.
        if (state.formSubmitted && problem != null) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodySmall,
                color = HomeTokens.danger,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "No credentials are stored here — ssh remotes use the guest's own ~/.ssh, " +
                "rclone remotes the guest's own rclone.conf. RUN NOW copies new and updated " +
                "files — it never deletes.",
            style = MaterialTheme.typography.bodySmall,
            color = HomeTokens.textDim,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        Row {
            // Stays tappable while invalid — the tap IS what surfaces the
            // validation message (the anti-"premature validation" shape).
            TextButton(
                onClick = {
                    state.formSubmitted = true
                    val source = state.draftSource.trim()
                    val destination = state.draftDestination.trim()
                    if (SyncProfiles.validate(source, destination) == null) {
                        onSave(backend, source, destination)
                    }
                },
                modifier = Modifier.height(34.dp),
            ) {
                Text("SAVE", fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel, modifier = Modifier.height(34.dp)) {
                Text("CANCEL", fontFamily = TerminalTheme.mono, color = HomeTokens.textDim)
            }
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    suggestions: List<String> = emptyList(),
    onPickSuggestion: (String) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = HomeTokens.textDim,
            modifier = Modifier.width(64.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = TerminalTheme.mono,
                fontSize = 12.sp,
                color = HomeTokens.textPrimary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { }),
            cursorBrush = SolidColor(HomeTokens.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Column {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontFamily = TerminalTheme.mono,
                            fontSize = 11.sp,
                            color = HomeTokens.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
    // M8.4.5 — the path dropdown: typing a "/" lists the guest directory
    // (headless, bounded); picking an entry appends it, so a path is
    // assembled a folder at a time without a keyboard marathon.
    if (suggestions.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .verticalScroll(rememberScrollState())
                .border(1.dp, HomeTokens.hairline),
        ) {
            suggestions.forEach { name ->
                Text(
                    text = name,
                    fontFamily = TerminalTheme.mono,
                    fontSize = 12.sp,
                    color = if (name.endsWith("/")) HomeTokens.accent else HomeTokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Choose $name",
                        ) { onPickSuggestion(name) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

// -------------------------------------------------------------- helpers

/**
 * One compact icon+word action (M8.4.4): a 34dp control — icon first, so
 * the card's primary actions cost one line, not a stack of buttons. The
 * icon carries its contentDescription AND the row an onClickLabel, so
 * the action announces itself either way a service reads the tree.
 */
@Composable
private fun CompactAction(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(34.dp)
            .clickable(role = Role.Button, onClickLabel = contentDescription) { onClick() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            fontFamily = TerminalTheme.mono,
            fontSize = 12.sp,
            color = tint,
        )
    }
}

@Composable
private fun SyncRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = HomeTokens.textDim,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            color = HomeTokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun backendHeaderText(snapshot: SyncSnapshot): String = listOf(
    snapshot.rsync.path?.let { "rsync ${snapshot.rsync.version ?: ""}".trim() } ?: "rsync missing",
    snapshot.rclone.path?.let { "rclone ${snapshot.rclone.version ?: ""}".trim() } ?: "rclone missing",
).joinToString(" · ")

/**
 * The overview subline: backend · path facts · the REAL run status
 * ([SyncProfiles.statusLine] — "never run" until a run was recorded,
 * then "2h ago · OK (exit 0)" or the failure) in one line.
 */
private fun profileSubline(profile: SyncProfile, status: PathPairStatus?, nowMs: Long): String {
    val src = pathStateText(
        remote = status?.sourceRemote ?: SyncProfiles.isRemote(profile.source),
        exists = status?.sourceExists,
    )
    val dst = pathStateText(
        remote = status?.destinationRemote ?: SyncProfiles.isRemote(profile.destination),
        exists = status?.destinationExists,
    )
    return "${profile.backend.name.lowercase()} · $src · $dst · " +
        SyncProfiles.statusLine(profile, nowMs)
}

private fun pathStateText(remote: Boolean, exists: Boolean?): String = when {
    remote -> "remote"
    exists == null -> "unchecked"
    exists -> "visible"
    else -> "missing"
}

internal fun previewCountsLine(preview: SyncPreview): String {
    val parts = listOf(
        "${preview.newCount} new",
        "${preview.changedCount} changed",
        "${preview.deletedCount} deleted",
    ) + (if (preview.unchangedCount > 0) listOf("${preview.unchangedCount} unchanged") else emptyList()) +
        (if (preview.errorCount > 0) listOf("${preview.errorCount} errors") else emptyList())
    return parts.joinToString(" · ")
}
