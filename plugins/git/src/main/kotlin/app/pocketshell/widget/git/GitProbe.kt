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

/** One commit of a repository's recent history (%h|%an|%ar|%s). */
internal data class LogEntry(
    /** The abbreviated hash git printed (%h) — the commit page's key. */
    val hash: String,
    val author: String,
    val relativeTime: String,
    val subject: String,
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

/** One configured remote — the first URL git reports for the name. */
internal data class Remote(val name: String, val url: String)

/** One commit's `git show --stat` facts — the commit page's data. */
internal data class CommitDetail(
    /** The FULL hash (%H), distinct from the RECENT list's %h. */
    val fullHash: String,
    val author: String,
    val email: String?,
    val relativeDate: String,
    val subject: String,
    /** The --stat lines (path + insertions/deletions), already capped. */
    val statLines: List<String>,
    /** How many stat lines were cut by the cap — a real count, or zero. */
    val hiddenStatLines: Int,
)

/** A working-tree or index diff, already capped for render. */
internal data class DiffText(
    val lines: List<String>,
    /** How many lines were cut by the cap — a real count, or zero. */
    val hidden: Int,
)

internal sealed interface CommitResult {
    data class Done(val detail: CommitDetail) : CommitResult
    data class Failed(val reason: String) : CommitResult
}

internal sealed interface DiffResult {
    data class Done(val text: DiffText) : DiffResult
    data class Failed(val reason: String) : DiffResult
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
    private var lastScanAtMs: Long = 0L
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
        lastScanAtMs = clock()
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
     * M8.4.3 — the commit page: ONE bounded, read-only `git show --stat`
     * for one commit. One tap = one exec; the result carries the tool's
     * real stderr tail on failure. The hash must be bare hex (our own %h
     * output) — anything else is refused BEFORE any exec (Sync's
     * option-injection discipline: no guest text becomes an option).
     */
    fun showCommit(repoPath: String, hash: String): CommitResult {
        if (!COMMIT_HASH_RE.matches(hash)) return CommitResult.Failed("not a commit hash: $hash")
        return try {
            val out = exec.exec(
                listOf(
                    "git", "-C", repoPath,
                    "show", "--stat",
                    "--pretty=format:%H%n%an <%ae>%n%ar%n%s",
                    hash,
                ),
                MANUAL_TIMEOUT_MS,
            )
            when {
                out.error != null -> CommitResult.Failed(out.error!!)
                out.exitCode != 0 -> CommitResult.Failed(
                    stderrTail(out) ?: "git exited with ${out.exitCode}",
                )
                else -> parseShowOutput(out.stdout)?.let { CommitResult.Done(it) }
                    ?: CommitResult.Failed("unrecognized git show output")
            }
        } catch (t: Throwable) {
            CommitResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * M8.4.3 — the diff page: ONE bounded, read-only `git diff` for one
     * path — `--cached` for the index side of a change, plain for the
     * worktree side. The path rides as a direct argv element after "--"
     * (execve bytes, never reparsed — nothing to escape).
     */
    fun diffFile(repoPath: String, path: String, staged: Boolean): DiffResult = try {
        val argv = if (staged) {
            listOf("git", "-C", repoPath, "diff", "--cached", "--", path)
        } else {
            listOf("git", "-C", repoPath, "diff", "--", path)
        }
        val out = exec.exec(argv, MANUAL_TIMEOUT_MS)
        when {
            out.error != null -> DiffResult.Failed(out.error!!)
            out.exitCode != 0 -> DiffResult.Failed(
                stderrTail(out) ?: "git exited with ${out.exitCode}",
            )
            else -> {
                val capped = GitPresentation.capLines(
                    out.stdout.lineSequence().toList(),
                    GitPresentation.DIFF_MAX_LINES,
                )
                DiffResult.Done(DiffText(lines = capped.lines, hidden = capped.hidden))
            }
        }
    } catch (t: Throwable) {
        DiffResult.Failed(t.message ?: t.javaClass.simpleName)
    }

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

        /** The manual inspection execs are single git calls — cheap, bounded. */
        const val MANUAL_TIMEOUT_MS = 15_000L

        /** A bare hex object name — our own %h output, never guest text. */
        private val COMMIT_HASH_RE = Regex("""[0-9a-fA-F]{4,40}""")

        /** Protocol caps — MUST match the script's own bounds (-5, head -n). */
        const val LOG_MAX_ENTRIES = 5
        const val BRANCH_MAX_ENTRIES = 12
        const val REMOTE_MAX_ENTRIES = 6

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
         *   4. per repo — last 5 commits (`git log -5`, pipe-separated
         *      fields), local branches (`git branch --format`, capped at
         *      12 via head) and remotes (`git remote -v`, capped at 12 raw
         *      lines = 6 remotes at 2 lines each) — every section bounded,
         *      every failure silenced into an empty section;
         *   5. terminal `exit 0` — a completed scan is a successful probe
         *      no matter what git printed (the v0.4.4 mixed-answer lesson).
         *
         * `sort` gives a deterministic repo order; the pipeline's while
         * loop keeps the whole thing ONE exec (discovery, status, history
         * and refs share the same proot spawn). The format strings are
         * single-quoted: a bare "|" inside a word is a shell pipe, so the
         * separators can never ride unquoted.
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
              git -C "${D}d" log -5 --pretty=format:'%h|%an|%ar|%s' 2>/dev/null && echo ""
              echo "@@BRANCHES"
              git -C "${D}d" branch --format='%(refname:short)|%(upstream:short)|%(upstream:track)' 2>/dev/null | head -n 12
              echo "@@REMOTES"
              git -C "${D}d" remote -v 2>/dev/null | head -n 12
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
    /** Raw @@LOG lines (hash|author|time|subject), verbatim. */
    val logLines: List<String> = emptyList(),
    /** Raw @@BRANCHES lines (name|upstream|track), verbatim. */
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
 * The @@LOG block: `hash|author|relative-time|subject` — the split is
 * capped at 4 fields so a subject containing "|" stays one field, and at
 * [GitProbe.LOG_MAX_ENTRIES] entries (the script's `log -5` is the first
 * bound; this is the defensive second). Malformed lines are skipped,
 * never guessed into commits.
 */
internal fun parseLogBlock(lines: List<String>): List<LogEntry> =
    lines.asSequence()
        .filter { it.isNotBlank() }
        .map { it.split('|', limit = 4) }
        .filter { it.size == 4 && it[0].isNotBlank() }
        .map { LogEntry(it[0].trim(), it[1].trim(), it[2].trim(), it[3]) }
        .take(GitProbe.LOG_MAX_ENTRIES)
        .toList()

/**
 * The @@BRANCHES block: `name|upstream|track` (refnames cannot contain
 * "|", so the split is unambiguous). An empty upstream parses to null;
 * the track string is kept raw and parsed into ahead/behind/gone.
 */
internal fun parseBranchBlock(lines: List<String>): List<Branch> =
    lines.asSequence()
        .filter { it.isNotBlank() }
        .map { it.split('|', limit = 3) }
        .filter { it.size == 3 && it[0].isNotBlank() }
        .map { Branch(it[0].trim(), it[1].trim().ifEmpty { null }, it[2].trim()) }
        .take(GitProbe.BRANCH_MAX_ENTRIES)
        .toList()

/**
 * The @@REMOTES block: `git remote -v`'s "name<TAB>url (fetch|push)"
 * lines — the fetch URL wins, and a name seen once is never replaced
 * (first URL per name). Two lines per remote is why the script caps the
 * raw output at 12 lines for [GitProbe.REMOTE_MAX_ENTRIES] remotes.
 */
internal fun parseRemoteBlock(lines: List<String>): List<Remote> {
    val firstUrlByName = LinkedHashMap<String, String>()
    for (line in lines) {
        if (line.isBlank()) continue
        val parts = line.trim().split(Regex("""\s+"""))
        if (parts.size < 2) continue
        if (parts.size >= 3 && parts[2] == "(push)") continue
        if (!firstUrlByName.containsKey(parts[0])) firstUrlByName[parts[0]] = parts[1]
    }
    return firstUrlByName.entries.take(GitProbe.REMOTE_MAX_ENTRIES)
        .map { Remote(name = it.key, url = it.value) }
}

/**
 * `git show --stat --pretty=format:%H%n%an <%ae>%n%ar%n%s` output: four
 * header lines by position (full hash, author, relative date, subject)
 * followed by the --stat block (blank separator lines dropped, capped for
 * render by [GitPresentation.capLines]). Null when the protocol cannot
 * vouch for the shape — a Failed result, never invented facts.
 */
internal fun parseShowOutput(raw: String): CommitDetail? {
    val lines = raw.lineSequence().toList()
    if (lines.size < 4) return null
    val fullHash = lines[0].trim()
    if (!SHOW_HASH_RE.matches(fullHash)) return null
    val authorLine = lines[1].trim()
    val lt = authorLine.indexOf(" <")
    val author = if (lt > 0) authorLine.substring(0, lt).trim() else authorLine
    val email = if (lt > 0) {
        authorLine.substring(lt + 2).removeSuffix(">").trim().ifEmpty { null }
    } else {
        null
    }
    val statRaw = lines.drop(4).filter { it.isNotBlank() }
    val capped = GitPresentation.capLines(statRaw, GitPresentation.COMMIT_STAT_MAX_LINES)
    return CommitDetail(
        fullHash = fullHash,
        author = author,
        email = email,
        relativeDate = lines[2].trim(),
        subject = lines[3],
        statLines = capped.lines,
        hiddenStatLines = capped.hidden,
    )
}

private val SHOW_HASH_RE = Regex("""[0-9a-fA-F]{7,40}""")

/**
 * Guest paths for humans: the guest home (where discovery looks) shows as
 * "~", everything else passes through verbatim — never reinterpreted.
 */
internal fun displayGuestRepoPath(path: String, guestHome: String = "/root"): String = when {
    path == guestHome -> "~"
    path.startsWith("$guestHome/") -> "~" + path.removePrefix(guestHome)
    else -> path
}
