package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult

/**
 * The GIT READER: the on-demand, per-screen git reads. The batched snapshot
 * in [GitProbe] answers the dashboard (every repository at a glance, one
 * exec); this answers ONE repository's deeper questions, each as its OWN
 * targeted command, run only when a screen actually needs it:
 *
 *   History tab     → `git log -n <window>`
 *   Branches tab    → `git branch -r`   (local branches ride the snapshot)
 *   Changes tab     → `git stash list`
 *   Files tab       → `ls-files` + a real total
 *   Commit screen   → `git show --name-status --first-parent`
 *   Diff screen     → `git diff` / `git diff --cached` / `git show --patch`
 *
 * Why split: a repository can carry 50k commits and 12k paths, and a phone
 * must not pay for what nobody is looking at. Every read here is bounded
 * (a cap, a window, a timeout) and every result says honestly what it left
 * out. Nothing in this file mutates anything — state changes live in
 * [GitOp] / [GitOpRunner].
 */
internal sealed interface ReadResult<out T> {
    data class Done<T>(val value: T) : ReadResult<T>
    data class Failed(val reason: String) : ReadResult<Nothing>
}

/** Where a diff comes from — the three sides git exposes for one path. */
internal sealed interface DiffTarget {
    /** The worktree against the index: `git diff -- <path>`. */
    data class Worktree(val path: String) : DiffTarget

    /** The index against HEAD: `git diff --cached -- <path>`. */
    data class Index(val path: String) : DiffTarget

    /**
     * A commit's own patch: `git show --patch --first-parent [-- <path>]`.
     * `--first-parent` is what turns a merge into a normal, readable patch
     * (without it git prints a combined diff that has no line numbering).
     */
    data class Commit(val hash: String, val path: String? = null) : DiffTarget
}

/** `git ls-files` output: the capped paths plus git's real total. */
internal data class FileList(val paths: List<String>, val total: Int) {
    /** Paths the cap left out — a real count, never a guess. */
    val hidden: Int get() = (total - paths.size).coerceAtLeast(0)
}

/** One `git stash list` entry (`stash@{N}` + its subject line). */
internal data class StashEntry(val ref: String, val subject: String) {
    /** The N of `stash@{N}`; null when git printed an unexpected ref shape. */
    val index: Int get() = INDEX_RE.find(ref)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    val dropped: Boolean get() = index < 0

    private companion object {
        val INDEX_RE = Regex("""stash@\{(\d+)}""")
    }
}

/** One path of a commit's `--name-status` block. */
internal data class CommitFile(
    /** The porcelain-style letter git prints: A, M, D, R100, C75, T… */
    val status: String,
    val path: String,
    /** The pre-rename path for R/C rows; null otherwise. */
    val oldPath: String? = null,
) {
    /** The single letter a dense row shows (R100 → R). */
    val letter: Char get() = status.firstOrNull() ?: '?'

    /** A change kind a human reads at a glance. */
    val kind: String
        get() = when (letter) {
            'A' -> "added"
            'M' -> "modified"
            'D' -> "deleted"
            'R' -> "renamed"
            'C' -> "copied"
            'T' -> "type changed"
            'U' -> "unmerged"
            else -> "changed"
        }
}

/** A commit's full detail: header facts + the message + its changed files. */
internal data class CommitDetail(
    val fullHash: String,
    val author: String,
    val email: String?,
    val relativeDate: String,
    /** The `%D` ref decoration ("HEAD -> main, origin/main"); null when none. */
    val refs: String?,
    val subject: String,
    /** The message body (everything after the subject line); null when empty. */
    val body: String?,
    val files: List<CommitFile>,
) {
    val shortHash: String get() = fullHash.take(7)
}

/** A bounded patch: the parsed model plus how many lines a cap left out. */
internal data class DiffText(val parsed: GitDiffParser.Parsed, val hidden: Int)


/** A capped raw text page (blame, graph, LFS listings): git's own lines. */
internal data class TextPage(val lines: List<String>, val hidden: Int)

/** One `git reflog` entry: the abbreviated hash + git's reflog subject. */
internal data class ReflogEntry(val hash: String, val subject: String)

/** One tag from `git for-each-ref refs/tags`. */
internal data class TagRow(val name: String, val hash: String, val date: String)

/** One worktree from `git worktree list --porcelain`. */
internal data class WorktreeRow(
    val path: String,
    val head: String?,
    /** The checked-out branch; null when detached or bare. */
    val branch: String?,
    val bare: Boolean,
    val detached: Boolean,
)

/** One submodule from `git submodule status --recursive`. */
internal data class SubmoduleRow(
    /** The status prefix: ' ' in sync, '-' not initialized, '+' differs, 'U' conflicts. */
    val status: Char,
    val path: String,
    val describe: String,
) {
    val initialized: Boolean get() = status != '-'
    val inSync: Boolean get() = status == ' '
}

/** The repository's configured identity (git config user.name / user.email). */
internal data class Identity(val name: String?, val email: String?) {
    val complete: Boolean get() = !name.isNullOrBlank() && !email.isNullOrBlank()
}

/** A capped list plus git's real total, so "N more" is always a fact. */
internal data class Bounded<T>(val items: List<T>, val total: Int) {
    val hidden: Int get() = (total - items.size).coerceAtLeast(0)
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * The on-demand reader. Blocking, like [GitProbe]: callers wrap it in
 * Dispatchers.IO and cache the answer; nothing here decides WHEN to run
 * (that is the screens' business), and nothing here mutates a repository.
 */
internal class GitReader(
    private val exec: GitProbe.GuestExec,
) {

    /** The last commits (newest first), bounded by the caller's window. */
    fun history(repoPath: String, limit: Int): ReadResult<List<LogEntry>> {
        val window = limit.coerceIn(1, MAX_HISTORY_WINDOW)
        return read(
            timeoutMs = READ_TIMEOUT_MS,
            argv = listOf("git", "-C", repoPath, "log", "-n", window.toString(), "--pretty=format:$LOG_FORMAT"),
        ) { stdout -> parseLogBlock(stdout.lineSequence().toList()) }
    }

    /** The remote-tracking branches (`git branch -r`), capped with a total. */
    fun remoteBranches(repoPath: String, cap: Int = REMOTE_BRANCH_CAP): ReadResult<Bounded<Branch>> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf("git", "-C", repoPath, "branch", "-r", "--format=$BRANCH_FORMAT"),
    ) { stdout ->
        val all = parseBranchBlock(stdout.lineSequence().toList())
        Bounded(items = all.take(cap), total = all.size)
    }

    /** The stashes (`git stash list`), capped with a total. */
    fun stashes(repoPath: String, cap: Int = STASH_CAP): ReadResult<Bounded<StashEntry>> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf("git", "-C", repoPath, "stash", "list", "--format=$STASH_FORMAT"),
    ) { stdout ->
        val all = parseStashBlock(stdout.lineSequence().toList())
        Bounded(items = all.take(cap), total = all.size)
    }

    /**
     * Every TRACKED path (`git ls-files`), plus git's own total. One shell
     * script, because the count and the listing must come from the same
     * answer; the repository path is a positional argument ($1), never
     * interpolated into the script text.
     */
    fun files(repoPath: String, cap: Int = GitFiles.LS_FILES_CAP): ReadResult<FileList> = read(
        timeoutMs = FILES_TIMEOUT_MS,
        argv = listOf("/bin/sh", "-c", FILES_SCRIPT, "sh", repoPath),
    ) { stdout -> parseFileListOutput(stdout, cap) }

    /** One commit's facts, message and changed files. */
    fun commitDetail(repoPath: String, hash: String): ReadResult<CommitDetail> {
        GitOps.hashRefusal(hash)?.let { return ReadResult.Failed(it) }
        return read(
            timeoutMs = READ_TIMEOUT_MS,
            argv = listOf(
                "git", "-C", repoPath, "show",
                "--name-status", "--first-parent",
                "--pretty=format:$COMMIT_FORMAT",
                hash,
            ),
        ) { stdout -> parseCommitDetail(stdout.lineSequence().toList()) }
    }

    /**
     * One bounded patch for one target. git is the diff engine; this caps
     * what a phone renders and reports the cap honestly.
     */
    fun diff(repoPath: String, target: DiffTarget, maxLines: Int = DIFF_MAX_LINES): ReadResult<DiffText> {
        val argv = when (target) {
            is DiffTarget.Worktree -> listOf("git", "-C", repoPath, "diff", "--", target.path)
            is DiffTarget.Index -> listOf("git", "-C", repoPath, "diff", "--cached", "--", target.path)
            is DiffTarget.Commit -> buildList {
                addAll(
                    listOf(
                        "git", "-C", repoPath, "show",
                        "--format=", "--patch", "--first-parent",
                        target.hash,
                    ),
                )
                target.path?.let { addAll(listOf("--", it)) }
            }
        }
        return read(timeoutMs = DIFF_TIMEOUT_MS, argv = argv) { stdout ->
            val capped = GitPresentation.capLines(stdout.lineSequence().toList(), maxLines)
            // The cap is applied to the TEXT, then the surviving text is
            // parsed: line numbers stay exactly git's, never re-numbered
            // around the cut.
            DiffText(parsed = GitDiffParser.parse(capped.lines), hidden = capped.hidden)
        }
    }


    // ------------------------------------------------- deep reads (M3)

    /** One file's blame, capped — git's own `-l` lines, verbatim. */
    fun blame(repoPath: String, path: String, maxLines: Int = TEXT_PAGE_MAX_LINES): ReadResult<TextPage> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf("git", "-C", repoPath, "blame", "-l", "--", path),
    ) { stdout ->
        val capped = GitPresentation.capLines(stdout.lineSequence().toList(), maxLines)
        TextPage(lines = capped.lines, hidden = capped.hidden)
    }

    /** One file's history (`log -- <path>`), bounded like the History tab. */
    fun fileHistory(repoPath: String, path: String, limit: Int): ReadResult<List<LogEntry>> {
        val window = limit.coerceIn(1, MAX_HISTORY_WINDOW)
        return read(
            timeoutMs = READ_TIMEOUT_MS,
            argv = listOf(
                "git", "-C", repoPath, "log", "-n", window.toString(),
                "--pretty=format:$LOG_FORMAT", "--", path,
            ),
        ) { stdout -> parseLogBlock(stdout.lineSequence().toList()) }
    }

    /** The repository's reflog, capped. */
    fun reflog(repoPath: String, cap: Int = REFLOG_CAP): ReadResult<List<ReflogEntry>> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf("git", "-C", repoPath, "reflog", "-n", cap.toString(), "--format=$REFLOG_FORMAT"),
    ) { stdout ->
        parseSimpleRows(stdout.lineSequence().toList(), limit = cap) { fields ->
            if (fields.size >= 2 && fields[0].isNotBlank()) {
                ReflogEntry(hash = fields[0].trim(), subject = fields[1].trim())
            } else {
                null
            }
        }
    }

    /** The tags (`for-each-ref refs/tags`), capped with a total. */
    fun tags(repoPath: String, cap: Int = TAG_CAP): ReadResult<Bounded<TagRow>> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf(
            "git", "-C", repoPath, "for-each-ref", "refs/tags",
            "--format=$TAG_FORMAT", "--sort=-creatordate",
        ),
    ) { stdout ->
        val all = parseSimpleRows(stdout.lineSequence().toList(), limit = Int.MAX_VALUE) { fields ->
            if (fields.size >= 3 && fields[0].isNotBlank()) {
                TagRow(name = fields[0].trim(), hash = fields[1].trim(), date = fields[2].trim())
            } else {
                null
            }
        }
        Bounded(items = all.take(cap), total = all.size)
    }

    /** The commit graph as git draws it (`log --graph`), capped. */
    fun graph(repoPath: String, limit: Int = GRAPH_LIMIT): ReadResult<TextPage> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf(
            "git", "-C", repoPath, "log", "-n", limit.toString(),
            "--graph", "--pretty=format:%h%d %s",
        ),
    ) { stdout ->
        val capped = GitPresentation.capLines(stdout.lineSequence().toList(), maxLines = limit * 3)
        TextPage(lines = capped.lines, hidden = capped.hidden)
    }

    /** The worktrees (`worktree list --porcelain`). */
    fun worktrees(repoPath: String, cap: Int = WORKTREE_CAP): ReadResult<List<WorktreeRow>> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf("git", "-C", repoPath, "worktree", "list", "--porcelain"),
    ) { stdout -> parseWorktrees(stdout.lineSequence().toList(), cap) }

    /** The submodules (`submodule status --recursive`). */
    fun submodules(repoPath: String, cap: Int = SUBMODULE_CAP): ReadResult<List<SubmoduleRow>> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf("git", "-C", repoPath, "submodule", "status", "--recursive"),
    ) { stdout ->
        parseSimpleRows(stdout.lineSequence().toList(), limit = cap) { fields ->
            // porcelain: one line " <42-char hash> <path> (<describe>)"; the
            // 42 covers the status char git puts BEFORE the hash
            val raw = fields[0]
            if (raw.length < 42) {
                null
            } else {
                val status = raw.first()
                val rest = raw.substring(42).trim()
                SubmoduleRow(
                    status = if (status == 'U') 'U' else status,
                    path = rest.substringBefore(' ').trim(),
                    describe = rest.substringAfter(' ', "").trim('(', ')'),
                )
            }
        }
    }

    /** The LFS-tracked files (`git lfs ls-files --size`), capped. */
    fun lfsFiles(repoPath: String, maxLines: Int = TEXT_PAGE_MAX_LINES): ReadResult<TextPage> = read(
        timeoutMs = FILES_TIMEOUT_MS,
        argv = listOf("git", "-C", repoPath, "lfs", "ls-files", "--size"),
    ) { stdout ->
        val capped = GitPresentation.capLines(stdout.lineSequence().toList(), maxLines)
        TextPage(lines = capped.lines, hidden = capped.hidden)
    }

    /**
     * The sparse-checkout cone (`git sparse-checkout list`), capped. A
     * repository not using sparse checkout fails here with git's own
     * words — which IS the honest answer, not an error to hide.
     */
    fun sparseCheckout(repoPath: String, maxLines: Int = TEXT_PAGE_MAX_LINES): ReadResult<TextPage> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf("git", "-C", repoPath, "sparse-checkout", "list"),
    ) { stdout ->
        val capped = GitPresentation.capLines(stdout.lineSequence().toList(), maxLines)
        TextPage(lines = capped.lines, hidden = capped.hidden)
    }

    /** The configured identity — one small script, two config keys. */
    fun identity(repoPath: String): ReadResult<Identity> = read(
        timeoutMs = READ_TIMEOUT_MS,
        argv = listOf("/bin/sh", "-c", IDENTITY_SCRIPT, "sh", repoPath),
    ) { stdout ->
        var name: String? = null
        var email: String? = null
        for (line in stdout.lineSequence()) {
            when {
                line.startsWith(IDENTITY_NAME_PREFIX) -> name = line.removePrefix(IDENTITY_NAME_PREFIX).trim().ifEmpty { null }
                line.startsWith(IDENTITY_EMAIL_PREFIX) -> email = line.removePrefix(IDENTITY_EMAIL_PREFIX).trim().ifEmpty { null }
            }
        }
        Identity(name = name, email = email)
    }

    /**
     * The one exec path every read shares: run the argv, then classify.
     * A dead guest ([ExecResult.error]) and a git failure (non-zero exit)
     * are DIFFERENT honest states, and neither is ever "no results". A
     * parser that cannot vouch for the shape (null) is a failure too —
     * unrecognized output is never dressed up as an empty answer.
     */
    private inline fun <T> read(timeoutMs: Long, argv: List<String>, parse: (String) -> T?): ReadResult<T> {
        val out = try {
            exec.exec(argv, timeoutMs)
        } catch (t: Throwable) {
            return ReadResult.Failed(t.message ?: t.javaClass.simpleName)
        }
        return when {
            out.error != null -> ReadResult.Failed(out.error!!)
            out.exitCode != 0 -> ReadResult.Failed(stderrTail(out) ?: "git exited with ${out.exitCode}")
            else -> parse(out.stdout)?.let { ReadResult.Done(it) }
                ?: ReadResult.Failed("unrecognized git output")
        }
    }

    private fun stderrTail(out: ExecResult): String? =
        out.stderr.lineSequence().lastOrNull { it.isNotBlank() }

    companion object {
        /** A local read: `git log` / `branch -r` / `stash list` are cheap but bounded. */
        const val READ_TIMEOUT_MS = 20_000L

        /** A diff can be large even when capped: a little more room. */
        const val DIFF_TIMEOUT_MS = 25_000L

        /** `ls-files` walks the whole index on a big repository. */
        const val FILES_TIMEOUT_MS = 25_000L

        /** The history window never grows past this — a phone, not a pager. */
        const val MAX_HISTORY_WINDOW = 300

        /** How much one LOAD MORE grows the history window. */
        const val HISTORY_WINDOW_STEP = 30

        /** Lines rendered for one patch before the honest truncation notice. */
        const val DIFF_MAX_LINES = 600

        /** Remote branches listed before the "+N more" line. */
        const val REMOTE_BRANCH_CAP = 40

        /** Stashes listed before the "+N more" line. */
        const val STASH_CAP = 10

        /** `--pretty=format` for the log list: fixed fields, then free text. */
        const val LOG_FORMAT = "%h%x1f%ar%x1f%D%x1f%an%x1f%s"

        /** The boundary between a commit's header and its name-status block. */
        const val FILES_SENTINEL = "@@POCKETSHELL-FILES"

        /** Closes the file-listing stream (see [FILES_SCRIPT]). */
        const val FILES_END = "@@END"

        /**
         * The commit header: hash, author, date, refs, subject, body — then a
         * SENTINEL line, because the body is free text and must never be
         * misread as the --name-status block that follows it.
         */
        const val COMMIT_FORMAT =
            "%H%x1f%an <%ae>%x1f%ar%x1f%D%x1f%s%x1f%b%n" + FILES_SENTINEL

        /** The stash record: the ref, then its subject line. */
        const val STASH_FORMAT = "%gd%x1f%gs"

        /**
         * `git branch --format` uses ref-filter, which does NOT support the
         * `%xNN` escapes `%x1f` needs — but it does honour a TAB, and a TAB
         * (a control character) can never appear in a ref name. Verified
         * against real git output.
         */
        const val BRANCH_FORMAT = "%(refname:short)\t%(upstream:short)\t%(upstream:track)"

        /**
         * The file listing: the real total first (a marker line), then the
         * rows. `wc -l` counts exactly what the listing prints, so the total
         * and the rows can never disagree. A plain val (not const): the
         * escaped dollars keep the shell script's own variables literal.
         */
        /** Reflog entries listed before the honest cap line. */
        const val REFLOG_CAP = 20

        /** Tags listed before the "+N more" line. */
        const val TAG_CAP = 40

        /** Graph commits rendered before the honest cap line. */
        const val GRAPH_LIMIT = 60

        /** Worktrees listed before the honest cap line. */
        const val WORKTREE_CAP = 12

        /** Submodules listed before the honest cap line. */
        const val SUBMODULE_CAP = 40

        /** Raw text pages (blame / graph / LFS) render this many lines. */
        const val TEXT_PAGE_MAX_LINES = 400

        /** The reflog record: the abbreviated hash, then git's subject. */
        const val REFLOG_FORMAT = "%h\t%gs"

        /** The tag record: name TAB abbreviated hash TAB creation date. */
        const val TAG_FORMAT = "%(refname:short)\t%(objectname:short)\t%(creatordate:short)"

        /** The identity script's markers. */
        const val IDENTITY_NAME_PREFIX = "@@NAME:"
        const val IDENTITY_EMAIL_PREFIX = "@@EMAIL:"

        /**
         * The two identity keys in ONE exec (a script, like [FILES_SCRIPT]):
         * the repository path is a positional argument, never interpolated.
         */
        val IDENTITY_SCRIPT = """
            echo "@@NAME:${'$'}(git -C "${'$'}1" config user.name 2>/dev/null)"
            echo "@@EMAIL:${'$'}(git -C "${'$'}1" config user.email 2>/dev/null)"
        """.trimIndent()

        val FILES_SCRIPT = """
            total=${'$'}(git -C "${'$'}1" ls-files 2>/dev/null | wc -l)
            echo "@@FILES:${'$'}total"
            git -C "${'$'}1" ls-files 2>/dev/null
            echo "$FILES_END"
        """.trimIndent()
    }
}

// ------------------------------------------------------------ block parsing

/**
 * The `@@FILES:<total>` script's answer: git's real total, then the rows.
 * A `@@END` marker normally closes it; a truncated stream still yields the
 * rows that DID arrive (with git's total, which is what makes "showing N of
 * M" honest even then).
 */
internal fun parseFileListOutput(raw: String, cap: Int = GitFiles.LS_FILES_CAP): FileList {
    var total = -1
    var inRows = false
    val rows = mutableListOf<String>()
    for (rawLine in raw.lineSequence()) {
        val line = rawLine.removeSuffix("\r")
        when {
            line.startsWith(FILES_TOTAL_PREFIX) -> {
                total = line.removePrefix(FILES_TOTAL_PREFIX).trim().toIntOrNull() ?: -1
                inRows = true
            }
            line == GitReader.FILES_END -> inRows = false
            inRows -> rows += line
        }
    }
    val (paths, _) = GitFiles.parseLsFiles(rows, cap)
    return FileList(paths = paths, total = if (total >= 0) total else paths.size)
}

private const val FILES_TOTAL_PREFIX = "@@FILES:"

/**
 * `git stash list --format='%gd%x1f%gs'` rows: the ref, then the subject.
 * A row without the separator (an exotic `log.date` / format difference) is
 * skipped rather than guessed at.
 */
internal fun parseStashBlock(lines: List<String>): List<StashEntry> =
    lines.asSequence()
        .filter { it.isNotBlank() }
        .map { it.split(FIELD_SEP, limit = 2) }
        .filter { it.size == 2 && it[0].isNotBlank() }
        .map { StashEntry(ref = it[0].trim(), subject = it[1].trim()) }
        .toList()

/**
 * `git show --name-status --first-parent --pretty=format:'…%n@@SENTINEL'`:
 * the header (with a possibly multi-line body) before the sentinel, the
 * name-status rows after it. Null when the header cannot be vouched for —
 * a Failed read, never invented facts.
 */
internal fun parseCommitDetail(lines: List<String>): CommitDetail? {
    val sentinel = lines.indexOfFirst { it.trim() == GitReader.FILES_SENTINEL }
    val headerLines = if (sentinel >= 0) lines.take(sentinel) else lines
    val fileLines = if (sentinel >= 0) lines.drop(sentinel + 1) else emptyList()
    val header = headerLines.joinToString("\n").trimEnd()
    if (header.isEmpty()) return null
    val fields = header.split(FIELD_SEP, limit = 6)
    if (fields.size < 5) return null
    val fullHash = fields[0].trim()
    if (GitOps.hashRefusal(fullHash) != null) return null
    val authorLine = fields[1].trim()
    val open = authorLine.indexOf(" <")
    val author = if (open > 0) authorLine.substring(0, open).trim() else authorLine
    val email = if (open > 0) {
        authorLine.substring(open + 2).removeSuffix(">").trim().ifEmpty { null }
    } else {
        null
    }
    val body = fields.getOrNull(5)?.trim()?.ifEmpty { null }
    return CommitDetail(
        fullHash = fullHash,
        author = author,
        email = email,
        relativeDate = fields[2].trim(),
        refs = fields[3].trim().ifEmpty { null },
        subject = fields[4],
        body = body,
        files = parseCommitFiles(fileLines),
    )
}

/** The `--name-status` rows: `M\tpath`, `R100\told\tnew`, C-quoted. */
internal fun parseCommitFiles(lines: List<String>): List<CommitFile> {
    val out = mutableListOf<CommitFile>()
    for (line in lines) {
        if (line.isBlank()) continue
        if (line.trim() == GitReader.FILES_SENTINEL) continue
        val fields = GitQuoted.splitTabFields(line, limit = 3)
        when {
            fields.size >= 3 && fields[0].isNotBlank() && fields[2].isNotBlank() ->
                out += CommitFile(status = fields[0].trim(), path = fields[2], oldPath = fields[1])
            fields.size == 2 && fields[0].isNotBlank() && fields[1].isNotBlank() ->
                out += CommitFile(status = fields[0].trim(), path = fields[1])
        }
    }
    return out
}

/** The field separator `%x1f` emits — a byte no ref name can contain. */
internal const val FIELD_SEP = "\u001f"

/**
 * The row shape several refs reads share: split each line through the
 * quote-aware splitter and map, skipping malformed rows rather than
 * guessing them into data.
 */
private inline fun <T> parseSimpleRows(
    lines: List<String>,
    limit: Int,
    map: (fields: List<String>) -> T?,
): List<T> {
    val out = mutableListOf<T>()
    for (line in lines) {
        if (out.size >= limit) break
        if (line.isBlank()) continue
        map(GitQuoted.splitTabFields(line, limit = 4))?.let { out += it }
    }
    return out
}

/**
 * `git worktree list --porcelain`: blocks of `worktree <path>` /
 * `HEAD <hash>` / `branch <ref>` / `bare` / `detached`. A path git printed
 * is a path git vouched for — no re-derivation.
 */
internal fun parseWorktrees(lines: List<String>, cap: Int): List<WorktreeRow> {
    val out = mutableListOf<WorktreeRow>()
    var path: String? = null
    var head: String? = null
    var branch: String? = null
    var bare = false
    var detached = false

    fun flush() {
        val p = path
        if (p != null && out.size < cap) {
            out += WorktreeRow(
                path = p,
                head = head,
                branch = branch?.removePrefix("refs/heads/"),
                bare = bare,
                detached = detached,
            )
        }
        path = null; head = null; branch = null; bare = false; detached = false
    }

    for (line in lines) {
        when {
            line == "bare" -> bare = true
            line == "detached" -> detached = true
            line.startsWith("worktree ") -> {
                flush()
                path = line.removePrefix("worktree ")
            }
            line.startsWith("HEAD ") -> head = line.removePrefix("HEAD ")
            line.startsWith("branch ") -> branch = line.removePrefix("branch ")
        }
    }
    flush()
    return out
}
