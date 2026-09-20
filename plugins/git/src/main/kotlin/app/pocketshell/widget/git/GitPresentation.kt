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

    // ------------------------------------------- bounded inspection pages

    /** The diff page renders at most this many lines. */
    const val DIFF_MAX_LINES = 400

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

    // ----------------------------------------------------- the diff renderer

    /** What one raw diff line IS — the color/weight a diff row gets. */
    enum class DiffLineKind { ADD, DEL, HUNK, META, CONTEXT }

    /**
     * Classify one raw unified-diff line. The header words are git's own
     * vocabulary (diff --git / index / --- +++ / mode & rename lines /
     * "Binary files … differ"); "---"/"+++" are headers ONLY with their
     * trailing space+path — a deleted line whose content begins "--" is a
     * DEL, not a header. Anything unrecognized is CONTEXT: an honest
     * default, never a made-up classification.
     */
    fun diffLineKind(line: String): DiffLineKind = when {
        line.startsWith("@@") -> DiffLineKind.HUNK
        line.startsWith("diff --git") || line.startsWith("index ") -> DiffLineKind.META
        line.startsWith("old mode") || line.startsWith("new mode") -> DiffLineKind.META
        line.startsWith("new file") || line.startsWith("deleted file") -> DiffLineKind.META
        line.startsWith("similarity index") || line.startsWith("rename ") ||
            line.startsWith("copy ") || line.startsWith("Binary files") -> DiffLineKind.META
        line.startsWith("--- ") || line.startsWith("+++ ") -> DiffLineKind.META
        line.startsWith("+") -> DiffLineKind.ADD
        line.startsWith("-") -> DiffLineKind.DEL
        else -> DiffLineKind.CONTEXT
    }
}
