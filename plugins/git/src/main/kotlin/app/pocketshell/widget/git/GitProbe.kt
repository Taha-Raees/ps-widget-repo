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
 * inspections (`git show --numstat`, `git show <hash> -- <path>`,
 * `git diff`, `git log --skip` pages, `git stash list`, the directory
 * listing and file preview scripts) — nothing here touches the index,
 * the refs or the worktree. MUTATIONS live in [GitOps]: explicit,
 * user-initiated, one confirmed tap = one bounded exec — the scan script
 * itself stays strictly read-only, so background refreshing can never
 * change a repository. No DNS/workspace repair either — the probe is
 * offline and must never mutate the rootfs from Home.
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

/**
 * One commit's facts — the commit page's data. The stat list is PARSED
 * numstat (`--numstat`: added<TAB>deleted<TAB>path), not raw --stat lines,
 * so the UI renders real per-file +/- columns instead of guessing widths.
 */
internal data class CommitDetail(
    /** The FULL hash (%H), distinct from the RECENT list's %h. */
    val fullHash: String,
    val author: String,
    val email: String?,
    /** The author date (%ad) — git's own absolute rendering. */
    val dateText: String,
    /** The relative date (%ar) — "2 days ago" — for the compact header. */
    val relativeDate: String,
    val subject: String,
    /** The message body (%b) below the subject; empty when there is none. */
    val body: List<String>,
    /** Changed files with their +/- counts ("-" = binary), capped. */
    val files: List<CommitFile>,
    /** How many file rows were cut by the cap — a real count, or zero. */
    val hiddenFiles: Int,
)

/** One changed file of a commit: "src/App.kt" +12 −3 (added/deleted "-": binary). */
internal data class CommitFile(
    val path: String,
    val added: String,
    val deleted: String,
)

/** One page of a repository's history (`git log --skip=N -n M+1`). */
internal data class LogPage(
    val entries: List<LogEntry>,
    /** True when the exec returned one entry MORE than asked — there is more. */
    val hasMore: Boolean,
)

/** One worktree directory entry (the Files tab's row). */
internal data class DirEntry(val name: String, val isDir: Boolean)

/** A file preview: capped head lines + honest facts about what is not shown. */
internal data class TextPage(
    val lines: List<String>,
    /** True when the file continued past the cap (the total is UNKNOWN — no fake count). */
    val truncated: Boolean,
    /** Real file size in bytes from `wc -c`; null when it could not be read. */
    val sizeBytes: Long?,
    /** True when the bytes contain NUL — shown as a fact, never mojibake. */
    val isBinary: Boolean,
)

/** One stash entry: its index (the stash@{N} the ops address) + git's subject. */
internal data class StashEntry(val index: Int, val subject: String)

/** A working-tree or index diff, already capped for render. */
internal data class DiffText(
    val lines: List<String>,
    /** How many lines were cut by the cap — a real count, or zero. */
    val hidden: Int,
)

internal sealed interface DiffResult {
    data class Done(val text: DiffText) : DiffResult
    data class Failed(val reason: String) : DiffResult
}

internal sealed interface CommitResult {
    data class Done(val detail: CommitDetail) : CommitResult
    data class Failed(val reason: String) : CommitResult
}

internal sealed interface LogPageResult {
    data class Done(val page: LogPage) : LogPageResult
    data class Failed(val reason: String) : LogPageResult
}

internal sealed interface ListDirResult {
    data class Done(val entries: List<DirEntry>) : ListDirResult
    data class Failed(val reason: String) : ListDirResult
}

internal sealed interface ReadHeadResult {
    data class Done(val page: TextPage) : ReadHeadResult
    data class Failed(val reason: String) : ReadHeadResult
}

internal sealed interface StashListResult {
    data class Done(val entries: List<StashEntry>) : StashListResult
    data class Failed(val reason: String) : StashListResult
}

internal sealed interface RemoteBranchesResult {
    data class Done(val names: List<String>) : RemoteBranchesResult
    data class Failed(val reason: String) : RemoteBranchesResult
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
     * The commit page: ONE bounded, read-only `git show --numstat` for one
     * commit — header facts, the message body and the parsed per-file
     * +/- counts. One tap = one exec; the hash must be bare hex (our own
     * %h output) — anything else is refused BEFORE any exec (Sync's
     * option-injection discipline: no guest text becomes an option).
     */
    fun showCommit(repoPath: String, hash: String): CommitResult {
        if (!COMMIT_HASH_RE.matches(hash)) return CommitResult.Failed("not a commit hash: $hash")
        return try {
            val out = exec.exec(
                listOf(
                    "git", "-C", repoPath,
                    "show", "--numstat",
                    "--pretty=format:%H%n%an <%ae>%n%ad%n%ar%n%s%n%b%n@@NUMSTAT@@%n",
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
     * The commit-file diff: ONE bounded, read-only `git show <hash> --
     * <path>` — the same commit, one path. The path rides as a direct argv
     * element after "--" (execve bytes, never reparsed).
     */
    fun commitFileDiff(repoPath: String, hash: String, path: String): DiffResult {
        if (!COMMIT_HASH_RE.matches(hash)) return DiffResult.Failed("not a commit hash: $hash")
        return diffText(
            listOf("git", "-C", repoPath, "show", hash, "--", path),
        )
    }

    /**
     * One page of a repository's history: `git log --skip=<n> -n <m+1>`
     * in the scan's pipe-separated entry format. The page size is the
     * caller's Integer (never guest text); asking for one entry MORE than
     * displayed is what makes [LogPage.hasMore] a fact, not a guess.
     */
    fun logPage(repoPath: String, skip: Int, count: Int): LogPageResult = try {
        val out = exec.exec(
            listOf(
                "git", "-C", repoPath,
                "log", "--skip=$skip", "-n", "${count + 1}",
                "--pretty=format:%h|%an|%ar|%s",
            ),
            MANUAL_TIMEOUT_MS,
        )
        when {
            out.error != null -> LogPageResult.Failed(out.error!!)
            out.exitCode != 0 -> LogPageResult.Failed(
                stderrTail(out) ?: "git exited with ${out.exitCode}",
            )
            else -> {
                val entries = out.stdout.lineSequence()
                    .filter { it.isNotBlank() }
                    .map { it.split('|', limit = 4) }
                    .filter { it.size == 4 && it[0].isNotBlank() }
                    .map { LogEntry(it[0].trim(), it[1].trim(), it[2].trim(), it[3]) }
                    .toList()
                val hasMore = entries.size > count
                LogPageResult.Done(LogPage(entries = entries.take(count), hasMore = hasMore))
            }
        }
    } catch (t: Throwable) {
        LogPageResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    /**
     * The Files tab's directory listing: ONE bounded exec lists one
     * worktree directory, dirs marked `d` and files `f`. The directory
     * path rides as the script's $1 (execve bytes, never spliced into the
     * script text — a hostile directory NAME cannot become code), and the
     * trailing @@LS-OK marker is what makes a short stream a fact instead
     * of a truncated one.
     */
    fun listDir(dirPath: String): ListDirResult = try {
        val out = exec.exec(
            listOf("/bin/sh", "-c", LIST_DIR_SCRIPT, "sh", dirPath),
            MANUAL_TIMEOUT_MS,
        )
        when {
            out.error != null -> ListDirResult.Failed(out.error!!)
            !out.stdout.contains(LIST_DIR_OK) -> ListDirResult.Failed(
                stderrTail(out) ?: "could not list this directory",
            )
            else -> {
                val entries = out.stdout.lineSequence()
                    .takeWhile { it != LIST_DIR_OK }
                    .mapNotNull { line ->
                        val tab = line.indexOf('\t')
                        if (tab != 1) return@mapNotNull null
                        val name = line.substring(2)
                        when (line[0]) {
                            'd' -> DirEntry(name, isDir = true)
                            'f' -> DirEntry(name, isDir = false)
                            else -> null
                        }
                    }
                    .filter { it.name.isNotEmpty() }
                    .toList()
                ListDirResult.Done(entries)
            }
        }
    } catch (t: Throwable) {
        ListDirResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    /**
     * The file preview: ONE bounded exec reads size + the first lines of
     * one worktree file (path as $1, same argv discipline as listDir).
     * Asking for PREVIEW_MAX_LINES + 1 lines is what makes [TextPage.truncated]
     * a fact; a NUL byte in the head is reported as binary — never rendered
     * as mojibake.
     */
    fun readHead(filePath: String): ReadHeadResult = try {
        val out = exec.exec(
            listOf("/bin/sh", "-c", READ_HEAD_SCRIPT, "sh", filePath),
            MANUAL_TIMEOUT_MS,
        )
        when {
            out.error != null -> ReadHeadResult.Failed(out.error!!)
            out.stdout.startsWith("@@MISS") -> ReadHeadResult.Failed("not a readable file")
            else -> {
                val lines = out.stdout.lineSequence().toList()
                val sizeLine = lines.firstOrNull { it.startsWith("@@SIZE:") }
                val textStart = lines.indexOf("@@TEXT")
                if (sizeLine == null || textStart < 0) {
                    ReadHeadResult.Failed("unrecognized preview output")
                } else {
                    val body = lines.drop(textStart + 1)
                    val truncated = body.size > PREVIEW_MAX_LINES
                    val shown = body.take(PREVIEW_MAX_LINES)
                    val isBinary = shown.any { line -> line.indexOf('\u0000') >= 0 }
                    ReadHeadResult.Done(
                        TextPage(
                            lines = shown,
                            truncated = truncated,
                            sizeBytes = sizeLine.removePrefix("@@SIZE:").trim().toLongOrNull(),
                            isBinary = isBinary,
                        ),
                    )
                }
            }
        }
    } catch (t: Throwable) {
        ReadHeadResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    /**
     * The stash list: `git stash list --format=%gd|%gs` — one bounded
     * read-only exec; the stash@{N} index is what the stash ops address.
     */
    fun stashList(repoPath: String): StashListResult = try {
        val out = exec.exec(
            listOf("git", "-C", repoPath, "stash", "list", "--format=%gd|%gs"),
            MANUAL_TIMEOUT_MS,
        )
        when {
            out.error != null -> StashListResult.Failed(out.error!!)
            out.exitCode != 0 -> StashListResult.Failed(
                stderrTail(out) ?: "git exited with ${out.exitCode}",
            )
            else -> {
                val entries = out.stdout.lineSequence()
                    .filter { it.isNotBlank() }
                    .map { it.split('|', limit = 2) }
                    .filter { it.size == 2 }
                    .mapNotNull { (ref, subject) ->
                        val index = Regex("""stash@\{(\d+)}""").find(ref)
                            ?.groupValues?.get(1)?.toIntOrNull()
                        index?.let { StashEntry(index = it, subject = subject) }
                    }
                    .toList()
                StashListResult.Done(entries)
            }
        }
    } catch (t: Throwable) {
        StashListResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    /**
     * The REMOTE branch names — one bounded, read-only `git branch -r`
     * (the scan lists LOCAL branches only). Refnames cannot contain the
     * separator, so a plain split is unambiguous; the cap is the
     * defensive second bound after the script's own.
     */
    fun remoteBranches(repoPath: String): RemoteBranchesResult = try {
        // The client-side take() is the bound (no head in the pipeline).
        val out = exec.exec(
            listOf(
                "git", "-C", repoPath, "branch", "-r",
                "--format=%(refname:short)",
            ),
            MANUAL_TIMEOUT_MS,
        )
        when {
            out.error != null -> RemoteBranchesResult.Failed(out.error!!)
            out.exitCode != 0 -> RemoteBranchesResult.Failed(
                stderrTail(out) ?: "git exited with ${out.exitCode}",
            )
            else -> RemoteBranchesResult.Done(
                out.stdout.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.endsWith("/HEAD").not() }
                    .take(REMOTE_BRANCH_MAX)
                    .toList(),
            )
        }
    } catch (t: Throwable) {
        RemoteBranchesResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    /** The shared bounded read of one diff-shaped git output. */
    private fun diffText(argv: List<String>): DiffResult = try {
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

    /**
     * The diff page: ONE bounded, read-only `git diff` for one path —
     * `--cached` for the index side of a change, plain for the worktree
     * side. The path rides as a direct argv element after "--" (execve
     * bytes, never reparsed — nothing to escape).
     */
    fun diffFile(repoPath: String, path: String, staged: Boolean): DiffResult =
        if (staged) {
            diffText(listOf("git", "-C", repoPath, "diff", "--cached", "--", path))
        } else {
            diffText(listOf("git", "-C", repoPath, "diff", "--", path))
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

        /** The history page asks for one MORE than it displays — hasMore is a fact. */
        const val HISTORY_PAGE_SIZE = 20

        /** The commit page shows at most this many changed-file rows. */
        const val COMMIT_FILES_MAX = 60

        /** The file preview renders at most this many lines. */
        const val PREVIEW_MAX_LINES = 200

        /** The remote-branch listing is capped — a fork's origins stay bounded. */
        const val REMOTE_BRANCH_MAX = 40

        /** The lone "$" — the probe scripts are shell scripts, not templates. */
        private const val D = "$"

        /** The listDir script's completion marker — its absence = truncated. */
        internal const val LIST_DIR_OK = "@@LS-OK"

        /**
         * The directory listing script. The directory path arrives as $1
         * (an execve element — NEVER spliced into this text), every entry
         * is printed as `d<TAB>name` / `f<TAB>name`, and the trailing
         * marker line is the completion fact the parser requires.
         */
        internal val LIST_DIR_SCRIPT = """
            cd "${D}1" 2>/dev/null || { echo "@@LS-FAIL"; exit 0; }
            find . -maxdepth 1 -mindepth 1 2>/dev/null | LC_ALL=C sort | while IFS= read -r p; do
              if [ -d "${D}p" ]; then printf 'd\t%s\n' "${D}{p#./}"; else printf 'f\t%s\n' "${D}{p#./}"; fi
            done
            echo "${LIST_DIR_OK}"
        """.trimIndent()

        /**
         * The file preview script: real size first (`wc -c`), then the
         * @@TEXT marker, then head lines (the caller asks for one MORE
         * than it shows so truncation is a fact). The path arrives as $1.
         */
        internal val READ_HEAD_SCRIPT = """
            if [ -f "${D}1" ]; then
              printf '@@SIZE:'
              wc -c < "${D}1"
              printf '@@TEXT\n'
              head -n 201 "${D}1"
            else
              echo "@@MISS"
            fi
        """.trimIndent()

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
 * `git show --numstat --pretty=format:%H%n%an <%ae>%n%ad%n%ar%n%s%n%b%n@@NUMSTAT@@%n`
 * output: five header lines by position (full hash, author, absolute date,
 * relative date, subject), the message body below the subject, the
 * @@NUMSTAT@@ marker, then the raw `added<TAB>deleted<TAB>path` rows.
 * Null when the protocol cannot vouch for the shape (no marker, too-short
 * header, non-hex hash) — a Failed result, never invented facts. A numstat
 * row that does not parse is skipped, never guessed into a file.
 */
internal fun parseShowOutput(raw: String): CommitDetail? {
    val lines = raw.lineSequence().toList()
    val marker = lines.indexOf("@@NUMSTAT@@")
    if (marker < 5) return null
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
    // The body is everything between the subject and the marker, trimmed at
    // both ends (the format's separators add blank lines around an empty %b,
    // and git's own convention puts a blank line before a real body).
    val body = lines.subList(5, marker)
        .dropLastWhile { it.isBlank() }
        .dropWhile { it.isBlank() }
    val statRows = lines.drop(marker + 1).filter { it.isNotBlank() }
    val files = statRows.mapNotNull { row ->
        val parts = row.split('\t', limit = 3)
        if (parts.size != 3 || parts[2].isEmpty()) return@mapNotNull null
        CommitFile(path = parts[2], added = parts[0], deleted = parts[1])
    }
    val capped = files.take(GitProbe.COMMIT_FILES_MAX)
    return CommitDetail(
        fullHash = fullHash,
        author = author,
        email = email,
        dateText = lines[2].trim(),
        relativeDate = lines[3].trim(),
        subject = lines[4],
        body = body,
        files = capped,
        hiddenFiles = files.size - capped.size,
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
