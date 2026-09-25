package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult

/**
 * The HOST seam: GitHub as an additional collaboration layer over the
 * repositories the workstation already shows. Git-first means this whole
 * file is OPTIONAL — every screen renders honest states when `gh` is
 * absent, unauthenticated, or the remote is not GitHub.
 *
 * Design facts:
 *
 *   - The provider rides `gh` IN THE GUEST (one binary, the user's own
 *     auth). Tokens stay inside gh's guest-side store; this code never
 *     sees one (`gh auth status`'s exit code is the only fact used).
 *   - Structure comes from `--jq … | @tsv`: gh's embedded jq reduces each
 *     JSON document to TAB-separated rows, so the module needs no JSON
 *     parser and every list is bounded by --limit BEFORE parsing.
 *   - Reads are GET-shaped gh calls only; the WRITE verbs live in GitOps
 *     (GhPrCreate/GhPrMerge/GhIssueCreate/GhIssueClose), confirmed and
 *     network-marked like every other op.
 *   - GitLab/Gitea stay future work: only [githubSlug] and this class
 *     know anything about the host; the UI renders the seam's states.
 */
internal class GitHost(
    private val exec: GitProbe.GuestExec,
) {

    /** gh's presence + auth, as the UI renders it. */
    sealed interface HostStatus {
        data object NotInstalled : HostStatus
        data object NotAuthed : HostStatus
        data object Ready : HostStatus
        data class Failed(val reason: String) : HostStatus
    }

    /** One open pull request row. */
    data class PrSummary(
        val number: Int,
        val head: String,
        val author: String,
        val title: String,
    )

    /** One PR's full page: the TSV header facts + the free-text body. */
    data class PrDetail(
        val number: Int,
        val state: String,
        val author: String,
        val head: String,
        val base: String,
        val reviewDecision: String,
        val mergeable: String,
        val title: String,
        val body: String?,
    )

    /** One issue row. */
    data class IssueSummary(
        val number: Int,
        val state: String,
        val author: String,
        val title: String,
    )

    /** One Actions run row. */
    data class RunRow(
        val id: Long,
        val status: String,
        val conclusion: String,
        val workflow: String,
        val title: String,
    ) {
        /** The one glyph a row shows: ✓ success, ✗ failure, • pending. */
        val glyph: Char
            get() = when {
                conclusion == "success" -> '✓'
                conclusion == "failure" || conclusion == "cancelled" || conclusion == "timed_out" -> '✗'
                else -> '•'
            }
    }

    /** One release row. */
    data class ReleaseRow(val tag: String, val name: String, val date: String)

    /** One notification row (the subject title + where it came from). */
    data class NoteRow(val title: String, val repo: String, val reason: String)

    /** gh's presence + auth in ONE exec (see [GH_DETECT_SCRIPT]). */
    fun status(): HostStatus {
        val out = gh(listOf("/bin/sh", "-c", GH_DETECT_SCRIPT, "sh"), DETECT_TIMEOUT_MS)
        if (out.error != null) return HostStatus.Failed(out.error!!)
        if (out.exitCode != 0) return HostStatus.Failed(stderrTail(out) ?: "gh detection failed")
        var installed = false
        var authed = false
        for (line in out.stdout.lineSequence()) {
            when {
                line.startsWith("@@GH:authed") -> { installed = true; authed = true }
                line.startsWith("@@GH:unauthed") -> installed = true
                line.startsWith("@@GH:no") -> return HostStatus.NotInstalled
            }
        }
        return when {
            !installed -> HostStatus.NotInstalled
            !authed -> HostStatus.NotAuthed
            else -> HostStatus.Ready
        }
    }

    /** The open pull requests, capped. */
    fun pullRequests(slug: String, cap: Int = LIST_CAP): ReadResult<List<PrSummary>> = ghRows(
        argv = listOf(
            "gh", "pr", "list", "-R", slug, "--state", "open", "--limit", cap.toString(),
            "--json", "number,title,headRefName,author",
            "--jq", PRS_JQ,
        ),
        parse = { fields ->
            if (fields.size >= 4 && fields[0].isNotBlank() && fields[0].toIntOrNull() != null) {
                PrSummary(number = fields[0].toInt(), head = fields[1], author = fields[2], title = fields[3])
            } else {
                null
            }
        },
    )

    /** One PR's facts + body (one script: TSV facts, sentinel, free text). */
    fun prDetail(slug: String, number: Int): ReadResult<PrDetail> {
        if (number < 1) return ReadResult.Failed("a pull request number is never negative")
        val out = gh(
            listOf(
                "/bin/sh", "-c", PR_DETAIL_SCRIPT, "sh", slug, number.toString(),
            ),
            READ_TIMEOUT_MS,
        )
        return classify(out) { stdout ->
            parsePrDetail(stdout.lineSequence().toList())
        }
    }

    /** The issues (all states — the row carries its own), capped. */
    fun issues(slug: String, cap: Int = LIST_CAP): ReadResult<List<IssueSummary>> = ghRows(
        argv = listOf(
            "gh", "issue", "list", "-R", slug, "--state", "all", "--limit", cap.toString(),
            "--json", "number,title,state,author",
            "--jq", ISSUES_JQ,
        ),
        parse = { fields ->
            if (fields.size >= 4 && fields[0].isNotBlank() && fields[0].toIntOrNull() != null) {
                IssueSummary(number = fields[0].toInt(), state = fields[1], author = fields[2], title = fields[3])
            } else {
                null
            }
        },
    )

    /** The recent Actions runs, capped. */
    fun runs(slug: String, cap: Int = RUN_CAP): ReadResult<List<RunRow>> = ghRows(
        argv = listOf(
            "gh", "run", "list", "-R", slug, "--limit", cap.toString(),
            "--json", "databaseId,status,conclusion,workflowName,displayTitle",
            "--jq", RUNS_JQ,
        ),
        parse = { fields ->
            if (fields.size >= 5 && fields[0].isNotBlank() && fields[0].toLongOrNull() != null) {
                RunRow(
                    id = fields[0].toLong(),
                    status = fields[1],
                    conclusion = fields[2],
                    workflow = fields[3],
                    title = fields[4],
                )
            } else {
                null
            }
        },
    )

    /** The releases, capped. */
    fun releases(slug: String, cap: Int = RELEASE_CAP): ReadResult<List<ReleaseRow>> = ghRows(
        argv = listOf(
            "gh", "release", "list", "-R", slug, "--limit", cap.toString(),
            "--json", "tagName,name,createdAt",
            "--jq", RELEASES_JQ,
        ),
        parse = { fields ->
            if (fields.size >= 3 && fields[0].isNotBlank()) {
                ReleaseRow(tag = fields[0], name = fields[1], date = fields[2])
            } else {
                null
            }
        },
    )

    /** The unread notifications, capped. */
    fun notifications(cap: Int = NOTE_CAP): ReadResult<List<NoteRow>> = ghRows(
        argv = listOf(
            "gh", "api", "notifications", "--paginate=false",
            "--jq", NOTES_JQ,
        ),
        parse = { fields ->
            if (fields.size >= 3) {
                NoteRow(title = fields[0], repo = fields[1], reason = fields[2])
            } else {
                null
            }
        },
    )

    // ------------------------------------------------------------ plumbing

    private fun gh(argv: List<String>, timeoutMs: Long): ExecResult = try {
        exec.exec(argv, timeoutMs)
    } catch (t: Throwable) {
        ExecResult(exitCode = null, stdout = "", stderr = "", error = t.message ?: t.javaClass.simpleName)
    }

    /**
     * One gh read: exec, classify (a dead guest, a gh failure and an
     * unparseable answer stay three DIFFERENT honest states), parse rows.
     */
    private inline fun <T> ghRows(
        argv: List<String>,
        parse: (fields: List<String>) -> T?,
    ): ReadResult<List<T>> {
        val out = gh(argv, READ_TIMEOUT_MS)
        return classify(out) { stdout ->
            parseSimpleRows(stdout.lineSequence().toList(), limit = Int.MAX_VALUE, map = parse)
        }
    }

    private inline fun <T> classify(out: ExecResult, parse: (String) -> T?): ReadResult<T> = when {
        out.error != null -> ReadResult.Failed(out.error!!)
        out.exitCode != 0 -> ReadResult.Failed(stderrTail(out) ?: "gh exited with ${out.exitCode}")
        else -> parse(out.stdout)?.let { ReadResult.Done(it) }
            ?: ReadResult.Failed("unrecognized gh output")
    }

    private fun stderrTail(out: ExecResult): String? =
        out.stderr.lineSequence().lastOrNull { it.isNotBlank() }

    companion object {
        /** A network read: gh contacts the API. Still bounded. */
        const val READ_TIMEOUT_MS = 60_000L
        const val DETECT_TIMEOUT_MS = 20_000L

        /**
         * The GitHub slug (owner/repo) the remotes point at, or null.
         * Handles the shapes git uses for GitHub:
         *   https://github.com/owner/repo.git  and  git@github.com:owner/repo.git
         */
        fun githubSlug(remotes: List<Remote>): String? {
            for (remote in remotes) {
                val url = remote.fetchUrl ?: continue
                val slug = slugOf(url)
                if (slug != null) return slug
            }
            return null
        }

        private fun slugOf(url: String): String? {
            val trimmed = url.trim().removeSuffix(".git")
            val tail = when {
                trimmed.startsWith("https://github.com/") -> trimmed.removePrefix("https://github.com/")
                trimmed.startsWith("http://github.com/") -> trimmed.removePrefix("http://github.com/")
                trimmed.startsWith("ssh://git@github.com/") -> trimmed.removePrefix("ssh://git@github.com/")
                trimmed.startsWith("git@github.com:") -> trimmed.removePrefix("git@github.com:")
                else -> return null
            }
            val parts = tail.split('/')
            // owner/repo — deeper paths are not GitHub's shape
            if (parts.size != 2) return null
            val (owner, repo) = parts
            if (!SLUG_PART.matches(owner) || !SLUG_PART.matches(repo)) return null
            return "$owner/$repo"
        }

        private val SLUG_PART = Regex("""[A-Za-z0-9._-]+""")

        /** List caps — every --limit is in the ARGV, before any parsing. */
        const val LIST_CAP = 30
        const val RUN_CAP = 20
        const val RELEASE_CAP = 10
        const val NOTE_CAP = 20

        /**
         * gh presence in ONE exec: `command -v gh` decides installation;
         * `gh auth status`'s EXIT CODE decides authentication (it prints
         * to stderr either way — only the marker lines are facts).
         */
        const val GH_DETECT_SCRIPT = """
            command -v gh >/dev/null 2>&1 || { echo "@@GH:no"; exit 0; }
            if gh auth status >/dev/null 2>&1; then
              echo "@@GH:authed"
            else
              echo "@@GH:unauthed"
            fi
        """

        /** The boundary between a PR's TSV header and its free-text body. */
        const val BODY_SENTINEL = "@@POCKETSHELL-BODY"

        private val PRS_JQ = """.[] | [.number, .headRefName // "-", .author.login // "unknown", .title] | @tsv"""
        private val ISSUES_JQ = """.[] | [.number, .state, .author.login // "unknown", .title] | @tsv"""
        private val RUNS_JQ = """.[] | [.databaseId, .status, .conclusion // "pending", .workflowName // "-", .displayTitle // "-"] | @tsv"""
        private val RELEASES_JQ = """.[] | [.tagName // "-", .name // "-", .createdAt // "-"] | @tsv"""
        private val NOTES_JQ = """.[] | [.subject.title // "-", .repository.full_name // "-", .reason // "-"] | @tsv"""


        /**
         * The PR detail: the TSV facts line, then the sentinel, then the
         * free-text body (jq cannot emit multi-line text inside @tsv).
         * The slug and number are POSITIONAL arguments, never interpolated.
         */
        val PR_DETAIL_SCRIPT = """
            gh pr view "${'$'}2" -R "${'$'}1" --json number,state,author,headRefName,baseRefName,reviewDecision,mergeable,title \
              --jq '[.number, .state, .author.login // "unknown", .headRefName // "-", .baseRefName // "-", .reviewDecision // "-", .mergeable // "UNKNOWN", .title] | @tsv'
            echo "$BODY_SENTINEL"
            gh pr view "${'$'}2" -R "${'$'}1" --json body --jq '.body // ""'
        """.trimIndent()

        /** Parse one [PR_DETAIL_SCRIPT] answer. Null when the shape is wrong. */
        fun parsePrDetail(lines: List<String>): PrDetail? {
            val sentinel = lines.indexOfFirst { it.trim() == BODY_SENTINEL }
            val headerLines = if (sentinel >= 0) lines.take(sentinel) else lines
            val header = headerLines.firstOrNull { it.isNotBlank() } ?: return null
            val fields = GitQuoted.splitTabFields(header, limit = 8)
            if (fields.size < 8 || fields[0].toIntOrNull() == null) return null
            val body = if (sentinel >= 0) {
                lines.drop(sentinel + 1).joinToString("\n").trim().ifEmpty { null }
            } else {
                null
            }
            return PrDetail(
                number = fields[0].toInt(),
                state = fields[1],
                author = fields[2],
                head = fields[3],
                base = fields[4],
                reviewDecision = fields[5],
                mergeable = fields[6],
                title = fields[7],
                body = body,
            )
        }
    }
}
