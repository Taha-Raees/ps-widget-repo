package app.pocketshell.widget.sync

import app.pocketshell.packages.ExecResult

/**
 * M8.4 — the SYNC/BACKUP application's probe: ONE batched, read-only
 * guest exec answers what a refresh needs (which backends are installed —
 * `command -v`, honestly probed — plus whether each profile's source and
 * destination are visible in the guest), and a SECOND, manual-only bounded
 * exec runs one profile's DRY RUN.
 *
 * INJECTION DISCIPLINE (the contract test pins this): user-supplied specs
 * are NEVER spliced into a shell string. The overview script receives them
 * as POSITIONAL PARAMETERS via the exec argv list (execve bytes, never
 * reparsed — command substitution cannot live in data that no shell ever
 * re-quotes), and the direct backend invocations (dry run, real run) pass
 * them as direct argv elements of the backend binary itself — no shell in
 * either path at all. Leading "-" specs are refused at creation
 * ([SyncProfiles.validate]), so a path can never masquerade as an option.
 *
 * What each exec may DO is pinned: `command -v`, `rsync --version`,
 * `rclone version`, `[ -e path ]` existence tests, and `-n`/`--dry-run`
 * invocations that the tools themselves document as writing nothing.
 * The ONE real-data action is [runNow] (M8.4.1) — ADDITIVE-ONLY by
 * contract: `rsync -a` carries no removing flag, and rclone runs "copy",
 * never its deleting mode, so it adds and updates at the destination and
 * removes nothing. The destructive modes are unreachable from this card.
 */

/** One backend's honest availability. */
internal data class BackendStatus(
    /** Absolute guest path from `command -v`; null = not installed. */
    val path: String?,
    /** Version string as the tool reports it ("3.5.0", "v1.74.1"). */
    val version: String?,
)

/** Whether one profile's source/destination were visible at probe time. */
internal data class PathPairStatus(
    val sourceRemote: Boolean,
    /** null = unknown (probe output truncated). Remotes are never stat-ed. */
    val sourceExists: Boolean?,
    val destinationRemote: Boolean,
    val destinationExists: Boolean?,
)

internal data class SyncSnapshot(
    val rsync: BackendStatus,
    val rclone: BackendStatus,
    /** Request-order statuses; the caller zips them with its profiles. */
    val pairs: List<PathPairStatus>,
    /** False = the stream ended before "@@DONE" — output not trusted. */
    val complete: Boolean,
)

internal sealed interface ProbeResult {
    data class Done(val snapshot: SyncSnapshot) : ProbeResult
    data class Failed(val reason: String) : ProbeResult
}

internal sealed interface DryRunResult {
    data class Done(val preview: SyncPreview) : DryRunResult
    data class Failed(val reason: String) : DryRunResult
}

/**
 * One REAL run's recorded truth: the exit code plus one honest stats line,
 * and (M8.4.3) the tool's FULL combined output — the raw evidence the
 * stats parsers mine for [SyncProfile.lastStats] on a successful run.
 * Empty when nothing was spawned (backend absent).
 */
internal data class RunResult(
    val exitCode: Int?,
    val summary: String,
    val output: String = "",
)

/**
 * The stateful snapshotter — the GitProbe shape exactly: it owns no
 * lifecycle truth; the application's lifecycle-aware collect decides WHEN
 * to ask, and [shouldFullScan] is the idle gate that keeps an open, idle
 * Home card from exec'ing into the guest. The dry run is MANUAL-ONLY
 * (one user tap = one bounded exec) and deliberately outside the gate.
 */
internal class SyncProbe(
    private val exec: GuestExec,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Runs one guest command (argv tail after proot) and waits, bounded. */
    fun interface GuestExec {
        fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult
    }

    private val lock = Any()
    private var hasScanned = false
    private var lastScanAtMs: Long = 0L

    /** The idle gate (GitProbe's shape): too-soon ticks spend nothing. */
    fun shouldFullScan(nowMs: Long): Boolean = synchronized(lock) {
        !hasScanned || nowMs - lastScanAtMs >= AUTO_RESCAN_MS
    }

    /**
     * One full probe pass over the current profiles. Blocking — callers
     * wrap in Dispatchers.IO. Real exec failures come back as
     * [ProbeResult.Failed]; the UI never dresses a dead probe up as
     * "no profiles".
     */
    fun snapshot(profiles: List<SyncProfile>): ProbeResult = synchronized(lock) {
        hasScanned = true
        lastScanAtMs = clock()
        val result: ProbeResult = try {
            val argv = listOf("/bin/sh", "-c", PROBE_SCRIPT, "sh") +
                profiles.flatMap { listOf(it.source, it.destination) }
            val out = exec.exec(argv, PROBE_TIMEOUT_MS)
            when {
                !out.success && out.error != null -> ProbeResult.Failed(out.error!!)
                !out.success -> ProbeResult.Failed(
                    out.stderr.lineSequence().lastOrNull { it.isNotBlank() }
                        ?: "guest exec exited with ${out.exitCode}",
                )
                else -> {
                    val parsed = parseProbeOutput(out.stdout)
                    when {
                        parsed == null -> ProbeResult.Failed("unrecognized probe output")
                        !parsed.complete -> ProbeResult.Failed("probe output truncated")
                        parsed.pairs.size != profiles.size ->
                            ProbeResult.Failed("probe output truncated")
                        else -> {
                            val statuses = parsed.pairs.zip(profiles) { pair, profile ->
                                PathPairStatus(
                                    sourceRemote = SyncProfiles.isRemote(profile.source),
                                    sourceExists = pair.sourceExists,
                                    destinationRemote = SyncProfiles.isRemote(profile.destination),
                                    destinationExists = pair.destinationExists,
                                )
                            }
                            ProbeResult.Done(
                                SyncSnapshot(
                                    rsync = parsed.rsync,
                                    rclone = parsed.rclone,
                                    pairs = statuses,
                                    complete = true,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            ProbeResult.Failed(t.message ?: t.javaClass.simpleName)
        }
        result
    }

    /**
     * ONE bounded dry-run exec for one profile. [backend] is the probed
     * absolute guest path (an absent backend fails honestly instead of
     * running). The specs ride as direct argv elements — no shell, no
     * quoting, nothing to escape. rsync exits 0 for a clean preview;
     * rclone does the same; a non-zero exit is the tool's own failure and
     * is reported with its real stderr.
     */
    fun dryRun(profile: SyncProfile, rsyncPath: String?, rclonePath: String?): DryRunResult = try {
        when (profile.backend) {
            SyncBackend.RSYNC -> {
                val path = rsyncPath
                    ?: return DryRunResult.Failed(
                        "rsync is not installed in the guest — install it from the Linux Shell (apk add rsync)",
                    )
                val out = exec.exec(
                    listOf(path, "-n", "--itemize-changes", "--", profile.source, profile.destination),
                    DRY_RUN_TIMEOUT_MS,
                )
                finish(out, SyncBackend.RSYNC) { RsyncPreviewParser.parse(it) }
            }
            SyncBackend.RCLONE -> {
                val path = rclonePath
                    ?: return DryRunResult.Failed(
                        "rclone is not installed in the guest — install it from the Linux Shell " +
                            "(apk add rclone; it lives in the community repository)",
                    )
                val out = exec.exec(
                    listOf(path, "sync", profile.source, profile.destination, "--dry-run", "--combined", "-"),
                    DRY_RUN_TIMEOUT_MS,
                )
                finish(out, SyncBackend.RCLONE) { RclonePreviewParser.parse(it) }
            }
        }
    } catch (t: Throwable) {
        DryRunResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    private fun finish(out: ExecResult, backend: SyncBackend, parse: (String) -> SyncPreview): DryRunResult = when {
        out.error != null -> DryRunResult.Failed(out.error!!)
        out.exitCode != 0 -> DryRunResult.Failed(
            out.stderr.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "${backend.name.lowercase()} exited with ${out.exitCode}",
        )
        else -> DryRunResult.Done(parse(out.stdout))
    }

    /**
     * M8.4.1 — a REAL run of one profile. ADDITIVE-ONLY by contract:
     *
     *   rsync  -a --info=stats1 -- <src> <dst>   (no delete flag: adds and
     *            updates at the destination; never removes)
     *   rclone  copy <src> <dst>                  (rclone "sync" DELETES
     *            extraneous destination files — "copy" is the additive
     *            shape and is what this action uses)
     *
     * The specs ride as direct argv elements — no shell, nothing to
     * escape. Bounded by [RUN_TIMEOUT_MS]; a timeout destroys the process
     * and is reported as the failure it is. The full tool output rides
     * back in [RunResult.output]; what gets RECORDED is [runRecord]'s
     * one decision (every finished run, OK and FAILED alike).
     */
    /**
     * M8.4.4 — the headless PRE-STEP of a run: a LOCAL destination folder
     * is created if missing (`/bin/mkdir -p`), so a one-tap backup never
     * fails on "destination does not exist". Remote specs (host:path /
     * remote:path) are skipped — their backends create containers
     * themselves. Additive-only is unaffected: mkdir -p creates, removes
     * nothing.
     */
    fun prepare(
        profile: SyncProfile,
        timeoutMs: Long = PREPARE_TIMEOUT_MS,
    ): RunResult {
        if (SyncProfiles.isRemote(profile.destination)) {
            return RunResult(exitCode = 0, summary = "remote destination — nothing to prepare")
        }
        val out = exec.exec(listOf("/bin/mkdir", "-p", profile.destination), timeoutMs)
        return when {
            out.exitCode == 0 -> RunResult(exitCode = 0, summary = "destination ready")
            else -> RunResult(
                exitCode = out.exitCode,
                summary = ("mkdir failed: " + (out.stderr.lineSequence()
                    .lastOrNull { it.isNotBlank() } ?: "exit ${out.exitCode}")).take(160),
            )
        }
    }

    fun runNow(
        profile: SyncProfile,
        rsyncPath: String?,
        rclonePath: String?,
        timeoutMs: Long = RUN_TIMEOUT_MS,
    ): RunResult {
        val backendPath = when (profile.backend) {
            SyncBackend.RSYNC -> rsyncPath
            SyncBackend.RCLONE -> rclonePath
        } ?: return RunResult(
            exitCode = null,
            summary = when (profile.backend) {
                SyncBackend.RSYNC -> "rsync is not installed (apk add rsync)"
                SyncBackend.RCLONE -> "rclone is not installed (apk add rclone)"
            },
        )
        val argv = when (profile.backend) {
            SyncBackend.RSYNC -> listOf(backendPath, "-a", "--info=stats1") +
                profile.excludes.flatMap { listOf("--exclude=$it") } +
                listOf("--", profile.source, profile.destination)
            SyncBackend.RCLONE -> listOf(backendPath, "copy") +
                profile.excludes.flatMap { listOf("--exclude", it) } +
                listOf(profile.source, profile.destination)
        }
        val out = exec.exec(argv, timeoutMs)
        // M8.4.3 — the tool's FULL output rides along (rsync prints its
        // stats1 block to stdout; rclone prints its stats to stderr): the
        // raw evidence the stats parsers mine on a successful run.
        val output = out.stdout + "\n" + out.stderr
        val fact = output.lineSequence()
            .filter { it.isNotBlank() }
            .lastOrNull { !it.startsWith("sending incremental") }
            ?.take(120)
        return RunResult(
            exitCode = out.exitCode,
            summary = "exit ${out.exitCode}" + (fact?.let { " · $it" } ?: ""),
            output = output,
        )
    }

    /**
     * M8.4.1 — install a missing backend with the guest's own apk (one
     * bounded exec; needs the guest's network — the real apk output is
     * the honest result, whatever it is).
     */
    fun installBackend(backend: SyncBackend, timeoutMs: Long = INSTALL_TIMEOUT_MS): RunResult {
        val pkg = when (backend) {
            SyncBackend.RSYNC -> "rsync"
            SyncBackend.RCLONE -> "rclone"
        }
        val out = exec.exec(listOf("/sbin/apk", "add", pkg), timeoutMs)
        val lastLine = (out.stdout.lineSequence() + out.stderr.lineSequence())
            .lastOrNull { it.isNotBlank() } ?: ""
        val detail = when {
            out.exitCode == 0 -> "installed · ${lastLine.take(120)}"
            else -> "${pkg} install failed: " +
                (out.stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit ${out.exitCode}")
        }.take(160)
        return RunResult(exitCode = out.exitCode, summary = detail)
    }

    companion object {

        /** Bounded: a full guest round-trip (proot + version calls + N stats). */
        const val PROBE_TIMEOUT_MS = 15_000L

        /**
         * Bounded: a dry run walks the real tree. Large trees can take a
         * while — the bound is generous but REAL, and a timeout is reported
         * as the failure it is, never as "nothing to transfer".
         */
        const val DRY_RUN_TIMEOUT_MS = 60_000L

        /** Cheap heartbeat tick: decides, spends nothing when the gate says no. */
        const val TICK_MS = 5_000L

        /** Minimum space between full guest execs on an open, watched card. */
        const val AUTO_RESCAN_MS = 20_000L

        /** A real run walks/copies the real tree — the most generous bound. */
        const val RUN_TIMEOUT_MS = 600_000L

        /** apk add of one backend binary: bounded, network-bound. */
        const val INSTALL_TIMEOUT_MS = 180_000L

        /** One mkdir -p: instant, but bounded like every exec. */
        const val PREPARE_TIMEOUT_MS = 10_000L

        /** The lone "$" — the probe script is a shell script, not a template. */
        private const val D = "$"

        /**
         * THE one batched script for a whole refresh (busybox ash, Alpine).
         * Profile specs arrive as positional parameters ($1, $2, …) — see
         * the injection note above; the script quotes them (harmless: they
         * are execve data, never reparsed) and never builds commands from
         * them.
         *
         *   1. backend checks — `command -v` + the tool's own version line
         *      (absent → bare marker, the UI renders the honest state);
         *   2. per profile — one existence test per spec; remote-looking
         *      specs (colon before any slash) are reported remote and NOT
         *      stat-ed (a network stat would turn a cheap probe into IO);
         *   3. terminal `exit 0` — a completed scan is a successful probe
         *      no matter what individual checks said.
         */
        val PROBE_SCRIPT = """
            if command -v rsync >/dev/null 2>&1; then
              echo "@@RSYNC:${D}(command -v rsync)"
              echo "@@RSYNCV:${D}(rsync --version 2>/dev/null | head -n 1 | awk '{print ${D}3}')"
            else
              echo "@@RSYNC:"
            fi
            if command -v rclone >/dev/null 2>&1; then
              echo "@@RCLONE:${D}(command -v rclone)"
              echo "@@RCLONEV:${D}(rclone version 2>/dev/null | head -n 1 | awk '{print ${D}2}')"
            else
              echo "@@RCLONE:"
            fi
            while [ "${D}#" -ge 2 ]; do
              echo "@@PAIR"
              case "${D}1" in *:*) echo "@@SRC:remote" ;; *) [ -e "${D}1" ] && echo "@@SRC:ok" || echo "@@SRC:missing" ;; esac
              case "${D}2" in *:*) echo "@@DST:remote" ;; *) [ -e "${D}2" ] && echo "@@DST:ok" || echo "@@DST:missing" ;; esac
              shift 2
            done
            echo "@@DONE"
            exit 0
        """.trimIndent()
    }
}

// ------------------------------------------------------------ block parsing

/** One @@PAIR… block straight from the probe output. */
private class PairBlock {
    var sourceExists: Boolean? = null
    var destinationExists: Boolean? = null
    var sourceRemote = false
    var destinationRemote = false

    /** Fully answered? An interrupted block is not data. A spec answered
     *  "remote" is complete without an existence answer. */
    val complete: Boolean
        get() = (sourceExists != null || sourceRemote) &&
            (destinationExists != null || destinationRemote)
}

internal data class ProbeOutput(
    val rsync: BackendStatus,
    val rclone: BackendStatus,
    val pairs: List<PathPairStatus>,
    /** False = the stream ended before "@@DONE" — output not trusted. */
    val complete: Boolean,
)

private class PairBlockBuilder {
    val block = PairBlock()

    fun build(): PathPairStatus = PathPairStatus(
        sourceRemote = block.sourceRemote,
        sourceExists = if (block.sourceRemote) null else block.sourceExists,
        destinationRemote = block.destinationRemote,
        destinationExists = if (block.destinationRemote) null else block.destinationExists,
    )
}

/**
 * Parse the probe's stdout. Null when no backend marker was seen at all —
 * output the protocol cannot vouch for is a Failed probe, never data.
 */
internal fun parseProbeOutput(stdout: String): ProbeOutput? {
    var sawMarker = false
    var complete = false
    var rsync = BackendStatus(null, null)
    var rclone = BackendStatus(null, null)
    val pairs = ArrayList<PathPairStatus>()
    var current: PairBlockBuilder? = null

    fun flush() {
        current?.let { if (it.block.complete) pairs.add(it.build()) }
        current = null
    }

    for (line in stdout.lineSequence()) {
        when {
            line.startsWith("@@RSYNCV:") -> rsync = rsync.copy(version = line.removePrefix("@@RSYNCV:").trim().ifEmpty { null })
            line.startsWith("@@RCLONEV:") -> rclone = rclone.copy(version = line.removePrefix("@@RCLONEV:").trim().ifEmpty { null })
            line.startsWith("@@RSYNC:") -> {
                sawMarker = true
                rsync = BackendStatus(line.removePrefix("@@RSYNC:").trim().ifEmpty { null }, rsync.version)
            }
            line.startsWith("@@RCLONE:") -> {
                sawMarker = true
                rclone = BackendStatus(line.removePrefix("@@RCLONE:").trim().ifEmpty { null }, rclone.version)
            }
            line == "@@PAIR" -> {
                flush()
                current = PairBlockBuilder()
            }
            line == "@@SRC:remote" -> current?.block?.sourceRemote = true
            line == "@@DST:remote" -> current?.block?.destinationRemote = true
            line == "@@SRC:ok" -> current?.block?.sourceExists = true
            line == "@@SRC:missing" -> current?.block?.sourceExists = false
            line == "@@DST:ok" -> current?.block?.destinationExists = true
            line == "@@DST:missing" -> current?.block?.destinationExists = false
            line == "@@DONE" -> {
                complete = true
                flush()
            }
        }
    }
    flush()
    return if (sawMarker) ProbeOutput(rsync, rclone, pairs, complete) else null
}
