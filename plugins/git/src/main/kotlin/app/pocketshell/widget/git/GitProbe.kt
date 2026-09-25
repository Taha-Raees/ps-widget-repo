package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult

/**
 * The Git application's probe: ONE batched, read-only guest exec answers
 * everything a refresh needs (binary presence, repository discovery under
 * the guest's home, per-repo porcelain status, last commits, local
 * branches, remotes). Never per-field spawns — the getInstalledVersions
 * batching discipline (AlpinePackageManager).
 *
 * The script and its parser are designed as a strict little protocol:
 * marker lines ("@@…") are structural; everything between @@REPO and @@RC
 * is passed to [GitStatusParser] untouched, and the per-repo @@LOG /
 * @@BRANCHES / @@REMOTES sections after @@RC are parsed into bounded
 * inspection lists. A repo path would have to start with "@@" to confuse
 * it — and discovery only yields real directories under the guest home.
 * A missing or failed section degrades ALONE: an empty list, never
 * invented data, and never a poisoned status fact.
 *
 * Read-only by contract: `git --version`, `find`, `git status`,
 * `git log`, `git branch --format`, `git remote -v`, and the manual-only
 * `git show --stat` / `git diff` ([showCommit], [diffFile]) — nothing
 * that touches the index, the refs or the worktree (the Home application
 * never stages, commits or checks out; a terminal is where git work
 * happens, and the card only LOOKS). No DNS/workspace repair either — the
 * probe is offline and must never mutate the rootfs from Home.
 */

/** One discovered repository's rendered snapshot. */
internal data class RepoSnapshot(
    /** Absolute guest path (the discovery root of truth). */
    val path: String,
    /** Basename for display; the detail page shows the full mapped path. */
    val name: String,
    /** Parsed status; null when git could not read this repository. */
    val status: GitStatusParser.RepoStatus?,
    /** Real failure text (git exit code / truncated output); null = healthy. */
    val error: String?,
    /** Last commits (newest first); empty when git reported none. */
    val log: List<LogEntry> = emptyList(),
    /** Local branches with upstream + tracking state; empty when none. */
    val branches: List<Branch> = emptyList(),
    /** First URL per remote name; empty when no remote is configured. */
    val remotes: List<Remote> = emptyList(),
)

internal data class GitSnapshot(
    /** "2.34.1"-style version; null = git is not installed (honest state). */
    val gitVersion: String?,
    val repos: List<RepoSnapshot>,
) {
    val hasGit: Boolean get() = gitVersion != null
    val dirtyRepos: Int get() = repos.count { it.status?.dirty == true }
}

/** One commit of a repository's recent history (%h, %ar, %D, %an, %s). */
internal data class LogEntry(
    /** The abbreviated hash git printed (%h) — the commit screen's key. */
    val hash: String,
    val author: String,
    val relativeTime: String,
    val subject: String,
    /** The `%D` ref decoration ("HEAD -> main, origin/main"); null when none. */
    val refs: String? = null,
)

/** One local branch, with its upstream tracking state kept raw + parsed. */
internal data class Branch(
    val name: String,
    /** Short upstream name; null when no upstream is configured. */
    val upstream: String?,
    /** The raw %(upstream:track) string ("[ahead 2]", "[gone]", ""). */
    val track: String,
) {
    /** Null = no divergence info in the track string. */
    val ahead: Int? = AHEAD_RE.find(track)?.groupValues?.get(1)?.toIntOrNull()
    val behind: Int? = BEHIND_RE.find(track)?.groupValues?.get(1)?.toIntOrNull()
    val gone: Boolean = track.contains("gone")
}

private val AHEAD_RE = Regex("""ahead (\d+)""")
private val BEHIND_RE = Regex("""behind (\d+)""")

/**
 * One configured remote. Git reports BOTH its URLs (`git remote -v` prints a
 * fetch and a push line per remote) and they can differ — so both are kept,
 * raw as git printed them. Credentials are never rendered: the UI passes
 * every URL through [GitPresentation.sanitizeUrl] before it reaches a pixel.
 */
internal data class Remote(
    val name: String,
    /** The fetch URL git reports for this name; null when git printed none. */
    val fetchUrl: String?,
    /** The push URL; equal to [fetchUrl] unless the remote pushes elsewhere. */
    val pushUrl: String?,
) {
    /** True when this remote pushes somewhere other than it fetches. */
    val pushUrlDiffers: Boolean = pushUrl != null && pushUrl != fetchUrl
}

internal sealed interface ScanResult {
    data class Done(val snapshot: GitSnapshot) : ScanResult
    data class Failed(val reason: String) : ScanResult
}

/**
 * The stateful snapshotter — a UI-domain cache exactly like PortProbe: it
 * owns no lifecycle truth; the application's lifecycle-aware collect
 * decides WHEN to ask, and [shouldFullScan] is the idle gate that keeps an
 * open, idle Home card from exec'ing into the guest.
 *
 * Unlike ServerProbe there is no cheap kernel signal to gate on (the
 * contract for this application bans own-hand process-table reading — the
 * guest exec path is the ONLY probe), so the gate is time-based and the
 * cost bound is explicit: at most one guest exec per AUTO_RESCAN_MS while
 * the card is open, plus immediate scans on the Home→resume edge and on
 * the manual refresh control. The M8.4.3 inspection execs ([showCommit],
 * [diffFile]) are MANUAL-ONLY (one user tap = one bounded exec), like
 * SyncProbe's dry run, and deliberately outside the gate.
 */
internal class GitProbe(
    private val exec: GuestExec,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Runs one guest command (argv tail after proot) and waits, bounded. */
    fun interface GuestExec {
        fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult
    }

    private val lock = Any()
    private var hasScanned = false
    private var lastScanAt = 0L
    private var lastSnapshot: GitSnapshot? = null

    /**
     * The idle gate (ServerProbe's shouldFullScan shape): a tick that fires
     * too soon after the last full scan does nothing at all — no guest
     * exec, no rootfs reads, no cost. The first tick always scans.
     */
    fun shouldFullScan(nowMs: Long): Boolean = synchronized(lock) {
        !hasScanned || nowMs - lastScanAtMs >= AUTO_RESCAN_MS
    }

    /** The most recent successful snapshot, if any (for instant re-layout). */
    val cached: GitSnapshot? get() = synchronized(lock) { lastSnapshot }

    /**
     * One full probe pass. Blocking (proot spawn + script) — callers wrap
     * in Dispatchers.IO, exactly like ServerProbe.snapshot(). Real exec
     * failures come back as [ScanResult.Failed]; the UI never dresses a
     * dead probe up as "no repositories".
     */
    fun snapshot(): ScanResult = synchronized(lock) {
        hasScanned = true
        lastScanAt = clock()
        val result: ScanResult = try {
            val out = exec.exec(
                listOf("/bin/sh", "-c", PROBE_SCRIPT, "sh"),
                SCAN_TIMEOUT_MS,
            )
            when {
                !out.success && out.error != null -> ScanResult.Failed(out.error!!)
                !out.success -> ScanResult.Failed(
                    out.stderr.lineSequence().lastOrNull { it.isNotBlank() }
                        ?: "guest exec exited with ${out.exitCode}",
                )
                else -> {
                    val parsed = parseProbeOutput(out.stdout)
                    when {
                        parsed == null -> ScanResult.Failed("unrecognized probe output")
                        !parsed.complete -> ScanResult.Failed("probe output truncated")
                        else -> ScanResult.Done(toSnapshot(parsed))
                    }
                }
            }
        } catch (t: Throwable) {
            ScanResult.Failed(t.message ?: t.javaClass.simpleName)
        }
        if (result is ScanResult.Done) lastSnapshot = result.snapshot
        result
    }

    /**
     * Forces the next tick to scan. Used after a mutation (the only way the
     * UI may show new state is git answering again) and by the manual
     * refresh control.
     */
    fun invalidate() = synchronized(lock) { hasScanned = false }

    /** When the last scan finished (for the honest "scanned Ns ago" line). */
    val lastScanAtMs: Long get() = synchronized(lock) { lastScanAt }

    private fun stderrTail(out: ExecResult): String? =
        out.stderr.lineSequence().lastOrNull { it.isNotBlank() }

    private fun toSnapshot(out: ProbeOutput): GitSnapshot {
        if (out.gitVersion == null) return GitSnapshot(gitVersion = null, repos = emptyList())
        return GitSnapshot(
            gitVersion = out.gitVersion,
            repos = out.repos.map { block ->
                RepoSnapshot(
                    path = block.path,
                    name = block.path.substringAfterLast('/').ifEmpty { block.path },
                    status = if (block.rc == 0) GitStatusParser.parse(block.lines.joinToString("\n")) else null,
                    error = when (block.rc) {
                        null -> "probe output truncated"
                        0 -> null
                        else -> "git exited with ${block.rc}"
                    },
                    log = parseLogBlock(block.logLines),
                    branches = parseBranchBlock(block.branchLines),
                    remotes = parseRemoteBlock(block.remoteLines),
                )
            },
        )
    }

    companion object {

        /** Bounded: a full guest round-trip (proot + find + N status calls). */
        const val SCAN_TIMEOUT_MS = 20_000L

        /** Cheap heartbeat tick: decides, spends nothing when the gate says no. */
        const val TICK_MS = 5_000L

        /** Minimum space between full guest execs on an open, watched card. */
        const val AUTO_RESCAN_MS = 20_000L

        /** Protocol caps — MUST match the script's own bounds (-5, head -n). */
        const val LOG_MAX_ENTRIES = 5
        const val BRANCH_MAX_ENTRIES = 24
        const val REMOTE_MAX_ENTRIES = 10

        /** The lone "$" — the probe script is a shell script, not a template. */
        private const val D = "$"

        /**
         * THE one batched script for a whole refresh (busybox ash, Alpine):
         *
         *   1. binary check — `command -v git`; absent → "@@GIT:" and done
         *      (the UI renders the honest "Git unavailable" state);
         *   2. discovery — one shallow find per well-known root ($HOME,
         *      $HOME/Projects) for any .git/HEAD file at repo depth ≤ 2 (a
         *      .git/HEAD FILE is what makes a directory a real, non-bare
         *      checkout); busybox find supports -maxdepth/-path;
         *   3. per repo — `git status --porcelain=v1 -b` (stable, script-
         *      documented format), its exit code after every block so one
         *      unreadable repository degrades alone;
         *   4. per repo — last 5 commits, local branches (`git branch
         *      --format`, head -n 24) and remotes (`git remote -v`, head
         *      -n 20 = 10 remotes at 2 lines each) — every section bounded,
         *      every failure silenced into an empty section. Deeper reads
         *      (history windows, remote branches, stashes, the file index,
         *      commit detail, diffs) are NOT here: they happen on demand,
         *      per screen, in GitReader;
         *   5. terminal `exit 0` — a completed scan is a successful probe
         *      no matter what git printed (the v0.4.4 mixed-answer lesson).
         *
         * `sort` gives a deterministic repo order; the pipeline's while
         * loop keeps the whole thing ONE exec (discovery, status, history
         * and refs share the same proot spawn). The format strings are
         * single-quoted; the LOG separator is `%x1f` (a byte a ref name can
         * never contain, so the refs field cannot be ambiguous) and the
         * BRANCH separator is a TAB for the same reason — ref-filter does
         * not support `%x1f`, but it does honour `\t`.
         */
        val PROBE_SCRIPT = """
            command -v git >/dev/null 2>&1 || { echo "@@GIT:"; exit 0; }
            echo "@@GIT:${D}(git --version 2>/dev/null | cut -d ' ' -f3)"
            for root in "${D}HOME" "${D}HOME/Projects"; do
              [ -d "${D}root" ] || continue
              find "${D}root" -maxdepth 3 -type f -path '*/.git/HEAD' 2>/dev/null
            done | sort | while IFS= read -r headpath; do
              d=${D}(dirname "${D}(dirname "${D}headpath")")
              echo "@@REPO:${D}d"
              git -C "${D}d" status --porcelain=v1 -b 2>/dev/null
              echo "@@RC:${D}?"
              echo "@@LOG"
              git -C "${D}d" log -5 --pretty=format:'%h%x1f%ar%x1f%D%x1f%an%x1f%s' 2>/dev/null && echo ""
              echo "@@BRANCHES"
              git -C "${D}d" branch --format='%(refname:short)\t%(upstream:short)\t%(upstream:track)' 2>/dev/null | head -n 24
              echo "@@REMOTES"
              git -C "${D}d" remote -v 2>/dev/null | head -n 20
            done
            echo "@@DONE"
            exit 0
        """.trimIndent()
    }
}

// ------------------------------------------------------------ block parsing

/** One @@REPO…@@DONE region straight from the probe output. */
internal data class RepoBlock(
    val path: String,
    /** The raw porcelain lines, verbatim (parsing is GitStatusParser's job). */
    val lines: List<String>,
    /** git's exit code; null when the block was cut short (truncated exec). */
    val rc: Int?,
    /** Raw @@LOG lines (hash ␟ date ␟ refs ␟ author ␟ subject), verbatim. */
    val logLines: List<String> = emptyList(),
    /** Raw @@BRANCHES lines (name TAB upstream TAB track), verbatim. */
    val branchLines: List<String> = emptyList(),
    /** Raw @@REMOTES lines (git remote -v), verbatim. */
    val remoteLines: List<String> = emptyList(),
)

internal data class ProbeOutput(
    val gitVersion: String?,
    val repos: List<RepoBlock>,
    /** False = the stream ended before "@@DONE" — output not trusted. */
    val complete: Boolean,
)

private class RepoBlockBuilder(val path: String) {
    val lines = ArrayList<String>()
    val logLines = ArrayList<String>()
    val branchLines = ArrayList<String>()
    val remoteLines = ArrayList<String>()
    var rc: Int? = null

    fun build() = RepoBlock(
        path,
        lines.toList(),
        rc,
        logLines.toList(),
        branchLines.toList(),
        remoteLines.toList(),
    )
}

/** Which per-repo section subsequent unmarked lines belong to. */
private enum class BlockSection { STATUS, AFTER_RC, LOG, BRANCHES, REMOTES }

/**
 * Parse the probe's stdout. Null when no "@@GIT:" marker was seen at all —
 * output the protocol cannot vouch for is a Failed probe, never data.
 * The status region (up to @@RC) is untouched porcelain; the sections
 * after @@RC attach to the same repository until the next @@REPO or
 * @@DONE. Lines that arrive between @@RC and the first section marker are
 * protocol noise — dropped, never guessed into data.
 */
internal fun parseProbeOutput(stdout: String): ProbeOutput? {
    var sawGitMarker = false
    var gitVersion: String? = null
    var complete = false
    val repos = ArrayList<RepoBlock>()
    var current: RepoBlockBuilder? = null
    var section = BlockSection.STATUS

    fun flush() {
        current?.let { repos.add(it.build()) }
        current = null
    }

    for (line in stdout.lineSequence()) {
        when {
            line.startsWith("@@GIT:") -> {
                sawGitMarker = true
                gitVersion = line.removePrefix("@@GIT:").trim().ifEmpty { null }
            }
            line.startsWith("@@REPO:") -> {
                flush()
                current = RepoBlockBuilder(line.removePrefix("@@REPO:"))
                section = BlockSection.STATUS
            }
            line.startsWith("@@RC:") -> {
                current?.rc = line.removePrefix("@@RC:").trim().toIntOrNull()
                section = BlockSection.AFTER_RC
            }
            line == "@@LOG" -> section = BlockSection.LOG
            line == "@@BRANCHES" -> section = BlockSection.BRANCHES
            line == "@@REMOTES" -> section = BlockSection.REMOTES
            line == "@@DONE" -> {
                complete = true
                flush()
            }
            else -> when (section) {
                BlockSection.STATUS -> current?.lines?.add(line)
                BlockSection.LOG -> current?.logLines?.add(line)
                BlockSection.BRANCHES -> current?.branchLines?.add(line)
                BlockSection.REMOTES -> current?.remoteLines?.add(line)
                BlockSection.AFTER_RC -> Unit // stray bytes between sections: not data
            }
        }
    }
    flush()
    return if (sawGitMarker) ProbeOutput(gitVersion, repos, complete) else null
}

/**
 * The @@LOG block: `hash ␟ relative-time ␟ refs ␟ author ␟ subject` (0x1f is
 * [FIELD_SEP]). The free-text fields come LAST and the split is capped so a
 * subject containing the separator — or a raw `|` — stays one field. A
 * control byte can never appear in a ref name, which is what makes the refs
 * field unambiguous. Malformed lines are skipped, never guessed into commits.
 */
internal fun parseLogBlock(lines: List<String>): List<LogEntry> =
    lines.asSequence()
        .filter { it.isNotBlank() }
        .map { it.split(FIELD_SEP, limit = 5) }
        .filter { it.size == 5 && it[0].isNotBlank() }
        .map {
            LogEntry(
                hash = it[0].trim(),
                relativeTime = it[1].trim(),
                refs = it[2].trim().ifEmpty { null },
                author = it[3].trim(),
                subject = it[4],
            )
        }
        .toList()

/**
 * The @@BRANCHES block: `name TAB upstream TAB track`. A TAB is the
 * separator because git's ref-filter honours it and a control character
 * cannot appear in a ref name — the shipped 2.0.0 parser used "|", which a
 * branch name may legally contain (verified: `git branch 'a|b'` succeeds),
 * so this is a protocol fix, not a cosmetic one. An empty upstream parses to
 * null; the track string is kept raw and parsed into ahead/behind/gone.
 */
internal fun parseBranchBlock(lines: List<String>): List<Branch> =
    lines.asSequence()
        .filter { it.isNotBlank() }
        .map { it.split('\t', limit = 3) }
        .filter { it.size == 3 && it[0].isNotBlank() }
        .map { Branch(it[0].trim(), it[1].trim().ifEmpty { null }, it[2].trim()) }
        .toList()

/**
 * The @@REMOTES block: `git remote -v`'s "name<TAB>url (fetch|push)" lines.
 * BOTH URLs are kept — a remote may push somewhere other than it fetches
 * (the pushed-to URL is often the one carrying credentials, which is exactly
 * why the UI sanitises it before rendering). The fetch line wins for
 * [Remote.fetchUrl]; a name git printed once keeps that side and leaves the
 * other null rather than being dropped.
 */
internal fun parseRemoteBlock(lines: List<String>): List<Remote> {
    val fetchByName = LinkedHashMap<String, String?>()
    val pushByName = LinkedHashMap<String, String?>()
    for (line in lines) {
        if (line.isBlank()) continue
        val tab = line.indexOf('\t')
        if (tab <= 0) continue
        val name = line.substring(0, tab).trim()
        val rest = line.substring(tab + 1).trim()
        val isPush = rest.endsWith("(push)")
        val isFetch = rest.endsWith("(fetch)")
        val url = when {
            isPush -> rest.removeSuffix("(push)").trim()
            isFetch -> rest.removeSuffix("(fetch)").trim()
            else -> rest
        }
        when {
            isPush && !pushByName.containsKey(name) -> pushByName[name] = url
            !isPush && !fetchByName.containsKey(name) -> fetchByName[name] = url
        }
    }
    val names = (fetchByName.keys + pushByName.keys).toList().take(GitProbe.REMOTE_MAX_ENTRIES)
    return names.map { name ->
        Remote(name = name, fetchUrl = fetchByName[name], pushUrl = pushByName[name])
    }
}

/**
 * Guest paths for humans: the guest home (where discovery looks) shows as
 * "~", everything else passes through verbatim — never reinterpreted.
 */
internal fun displayGuestRepoPath(path: String, guestHome: String = "/root"): String = when {
    path == guestHome -> "~"
    path.startsWith("$guestHome/") -> "~" + path.removePrefix(guestHome)
    else -> path
}
