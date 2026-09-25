package app.pocketshell.widget.git

/**
 * Pure presentation rules for the Git card's overview pane: the grouping
 * of porcelain entries into the CONFLICTS / STAGED / UNSTAGED sections and
 * the branch tracking + worktree glyphs. No android imports — JVM-tested
 * (GitPresentationTest), exactly like the parser it reads.
 *
 * The grouping follows git's own index/worktree split on the porcelain XY
 * columns (git-scm.com/docs/git-status):
 *
 *   CONFLICTS — the unmerged entries (X or Y is 'U', or AA/DD): shown ONCE
 *               here, above the other groups, with one guidance line — a
 *               conflicted path is resolution work, never staging noise;
 *   STAGED    — the index column X carries a real change (X != ' ' and
 *               X != '?', so untracked paths never count as staged);
 *   UNSTAGED — the worktree column Y carries a change, or the entry is
 *              untracked ("??").
 *
 * An entry changed on BOTH sides (e.g. "MM") appears in both STAGED and
 * UNSTAGED — that is git's model, not a rendering bug: the staged half and
 * the worktree half are two facts.
 */
internal object GitPresentation {

    /** One rendered file row: the single status letter + the display path. */
    data class EntryRow(
        val letter: Char,
        /** Renames render as the parser provides them: "old -> new". */
        val label: String,
    )

    /** The CONFLICTS section: every unmerged entry, exactly once. */
    fun conflictRows(entries: List<GitStatusParser.PorcelainEntry>): List<EntryRow> =
        entries.filter { it.conflict }
            .map { EntryRow(letter = conflictLetter(it), label = displayPath(it)) }

    /** The STAGED section: X is a real change letter (never '?'). */
    fun stagedRows(entries: List<GitStatusParser.PorcelainEntry>): List<EntryRow> =
        entries.filter { !it.conflict && it.x != ' ' && it.x != '?' }
            .map { EntryRow(letter = it.x, label = displayPath(it)) }

    /** The UNSTAGED section: a worktree change, or an untracked path. */
    fun unstagedRows(entries: List<GitStatusParser.PorcelainEntry>): List<EntryRow> =
        entries.filter { !it.conflict && (it.y != ' ' || it.untracked) }
            .map { EntryRow(letter = it.y, label = displayPath(it)) }

    /**
     * The row letter for a conflict: the unmerged side ('U') wins — it is
     * THE fact that needs a terminal — otherwise the index letter (AA's
     * 'A', DD's 'D').
     */
    private fun conflictLetter(entry: GitStatusParser.PorcelainEntry): Char = when {
        entry.x == 'U' -> 'U'
        entry.y == 'U' -> 'U'
        entry.x != ' ' -> entry.x
        else -> entry.y
    }

    private fun displayPath(entry: GitStatusParser.PorcelainEntry): String =
        if (entry.origPath != null) "${entry.origPath} -> ${entry.path}" else entry.path

    /**
     * The tracking glyphs after the branch name: "↑N" when ahead, "↓N"
     * when behind, in that order; nothing when in sync or when the head
     * line carries no divergence info at all. Zero counters render nothing
     * — git never emits them, and the glyph means a nonzero divergence —
     * so a rendered glyph is always worth the accent color.
     */
    fun trackingGlyphs(ahead: Int?, behind: Int?): String = buildString {
        if (ahead != null && ahead > 0) append('↑').append(ahead)
        if (behind != null && behind > 0) append('↓').append(behind)
    }

    /** The worktree marker: ● changes pending, ○ clean. */
    fun worktreeGlyph(dirty: Boolean): Char = if (dirty) '●' else '○'

    /**
     * THE credential guard. A remote URL is shown to a human only through
     * this function, which is what makes "never expose a password or token"
     * a property of the code rather than a promise in a comment.
     *
     *   https://user:ghp_secret@github.com/u/r.git  →  https://REDACTED@github.com/u/r.git
     *   user:secret@host:path                       →  ***@host:path
     *   ssh://git@github.com/u/r.git                →  untouched (no secret)
     *   git@github.com:u/r.git                      →  untouched (no secret)
     *
     * A bare userinfo on an http(s)/ftp(s) URL is redacted too: a token in
     * the username position (`https://ghp_xxx@…`) is common and the UI
     * cannot know it is not one. Anything that is not a URL at all — a local
     * path, a relative one — passes through verbatim.
     */
    fun sanitizeUrl(url: String): String {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = trimmed.substring(0, schemeEnd).lowercase()
            val rest = trimmed.substring(schemeEnd + 3)
            val at = rest.indexOf('@')
            val slash = rest.indexOf('/')
            if (at > 0 && (slash < 0 || at < slash)) {
                val userinfo = rest.substring(0, at)
                val hostPart = rest.substring(at + 1)
                val keepUser = (scheme == "ssh" || scheme == "git") && !userinfo.contains(':')
                return if (keepUser) {
                    "$scheme://$userinfo@$hostPart"
                } else {
                    "$scheme://$REDACTED@$hostPart"
                }
            }
            return trimmed
        }
        // scp form: [user[:password]@]host:path — only when the part before
        // "@" is a user, not a local path that happens to contain one.
        val at = trimmed.indexOf('@')
        if (at > 0) {
            val userinfo = trimmed.substring(0, at)
            if (userinfo.contains(':') && !userinfo.contains('/')) {
                return "$REDACTED@${trimmed.substring(at + 1)}"
            }
        }
        return trimmed
    }

    /** The ONE way a remote URL reaches a screen: sanitized, then shortened. */
    fun displayUrl(url: String): String = shortUrl(sanitizeUrl(url))

    /**
     * How old a reading is, in words. Shown next to every cached list so a
     * stale answer can never masquerade as the current state.
     */
    fun ageLabel(nowMs: Long, thenMs: Long): String {
        if (thenMs <= 0L) return "never"
        val seconds = ((nowMs - thenMs).coerceAtLeast(0)) / 1000
        return when {
            seconds < 5 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    /**
     * ONE letter for a dense row: the unmerged side wins, then the worktree
     * side, then the index side, then untracked. (The porcelain XY pair stays
     * available where both halves matter — the Changes tab shows both.)
     */
    fun statusLetter(entry: GitStatusParser.PorcelainEntry): Char = when {
        entry.conflict -> 'U'
        entry.untracked -> '?'
        entry.y != ' ' -> entry.y
        entry.x != ' ' -> entry.x
        else -> '?'
    }

    /** path → dense letter, for the Files tab's dirty marks. */
    fun statusByPath(entries: List<GitStatusParser.PorcelainEntry>): Map<String, Char> {
        if (entries.isEmpty()) return emptyMap()
        val out = HashMap<String, Char>(entries.size)
        for (entry in entries) out[entry.path] = statusLetter(entry)
        return out
    }

    /** "3 files" / "1 file" — a count a human reads without parsing. */
    fun countLabel(count: Int, singular: String, plural: String = "${singular}s"): String =
        "$count " + if (count == 1) singular else plural

    /** A subject/path kept to ONE line: real newlines and tabs become visible. */
    fun oneLine(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    // ------------------------------------------------------------- display

    /** What replaces a credential in any rendered URL. */
    const val REDACTED = "***"

    // ------------------------------------------- bounded inspection pages

    /** One rendered line keeps at most this many characters, ellipsised. */
    const val MAX_LINE_CHARS = 200

    /** Capped text: what renders, plus how many lines were honestly cut. */
    data class CappedText(val lines: List<String>, val hidden: Int)

    /**
     * Cap raw tool output for render: at most [maxLines] lines, each at
     * most [maxLineChars] characters ("…" appended when cut). The hidden
     * count is the REAL number of dropped lines — never a guess — so the
     * "+N more lines truncated" line stays a fact.
     */
    fun capLines(
        rawInput: List<String>,
        maxLines: Int,
        maxLineChars: Int = MAX_LINE_CHARS,
    ): CappedText {
        // A newline-TERMINATED stream has no trailing empty line — one
        // empty line is dropped here so display counts match the file's
        // real lines. Empty lines INSIDE the text are kept verbatim.
        val raw = if (rawInput.lastOrNull()?.isEmpty() == true) rawInput.dropLast(1) else rawInput
        val head = raw.take(maxLines).map { line ->
            if (line.length > maxLineChars) line.take(maxLineChars) + "…" else line
        }
        return CappedText(lines = head, hidden = (raw.size - head.size).coerceAtLeast(0))
    }

    /**
     * A remote URL for one compact row: scheme and scp-form user prefixes
     * dropped, ".git" suffix dropped ("https://github.com/u/r.git" →
     * "github.com/u/r", "git@host:path" → "host/path"). Anything else
     * passes through verbatim — a display shortening, never a rewrite.
     */
    fun shortUrl(url: String): String {
        var s = url.trim()
        for (scheme in listOf("https://", "http://", "ssh://", "git://", "ftp://", "ftps://")) {
            if (s.startsWith(scheme)) {
                s = s.removePrefix(scheme)
                break
            }
        }
        if (s.startsWith("git@")) s = s.removePrefix("git@").replaceFirst(":", "/")
        if (s.endsWith(".git")) s = s.removeSuffix(".git")
        return s
    }
}
