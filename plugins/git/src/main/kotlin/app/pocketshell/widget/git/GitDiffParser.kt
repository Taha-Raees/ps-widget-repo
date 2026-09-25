package app.pocketshell.widget.git

/**
 * Pure reader for unified-diff output (`git diff`, `git diff --cached`,
 * `git show --patch --first-parent`). Git is the diff engine — this parses
 * what git prints, computes the per-line numbering git does not spell out
 * (the hunks' `@@ -a,b +c,d @@` cursors), and refuses to invent anything it
 * cannot vouch for.
 *
 * Shapes handled (all observed from real git output):
 *
 *   diff --git a/x b/x           – patch start (paths best-effort; the
 *                                  ---/+++ and rename lines are authoritative)
 *   similarity index 100%
 *   rename from "old"  /  rename to "new"
 *   new file mode / deleted file mode / old mode / new mode
 *   index f0f2307..238b939 100644
 *   --- a/x␉ / +++ b/x␉          – the trailing TAB git appends to spaced paths
 *   Binary files a/x and b/x differ     /  GIT binary patch
 *   @@ -1,3 +1,3 @@ optional section
 *    context / -removed / +added / \ No newline at end of file
 *
 * A COMBINED diff (`@@@`, merge commits) is not renumbered as a hunk: its
 * lines are kept as META facts so a merge never renders a wrong line number.
 * The commit readers avoid combined diffs entirely by asking git for
 * `--first-parent`, which is what makes a merge render as a normal patch.
 */
internal object GitDiffParser {

    /** What a rendered line IS — the renderer maps this to theme tokens. */
    enum class Kind { META, HUNK, CONTEXT, ADD, DEL, NO_NEWLINE }

    /**
     * One rendered line: its kind, its verbatim text (diff prefix stripped
     * for content lines) and — when the hunks number it — its old/new line.
     */
    data class Line(
        val kind: Kind,
        val text: String,
        val oldLine: Int? = null,
        val newLine: Int? = null,
    )

    /** One `@@` hunk: the header, its optional section text, its lines. */
    data class Hunk(
        val header: String,
        val section: String?,
        val lines: List<Line>,
    )

    /** One file's patch: identity, rename/mode facts, hunks, counters. */
    data class FilePatch(
        val oldPath: String?,
        val newPath: String?,
        val hunks: List<Hunk>,
        val meta: List<String>,
        val binary: Boolean,
        val newFile: Boolean,
        val deletedFile: Boolean,
        val renamedFrom: String?,
        val combined: Boolean,
    ) {
        val additions: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == Kind.ADD } }
        val deletions: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == Kind.DEL } }

        /** The path a human recognizes: the new side, else the old. */
        val displayPath: String?
            get() = newPath ?: oldPath ?: renamedFrom

        /** Nothing to render as a patch (a pure mode change still has meta). */
        val hasLines: Boolean get() = hunks.isNotEmpty() || meta.isNotEmpty()
    }

    /** A whole diff: its files, plus the totals and the empty/binary facts. */
    data class Parsed(val files: List<FilePatch>) {
        val additions: Int get() = files.sumOf { it.additions }
        val deletions: Int get() = files.sumOf { it.deletions }
        val isEmpty: Boolean get() = files.isEmpty()
        val hasHunks: Boolean get() = files.any { it.hunks.isNotEmpty() }
        val anyBinary: Boolean get() = files.any { it.binary }
        val allBinary: Boolean get() = files.isNotEmpty() && files.all { it.binary }

        /** ONE file when the diff names exactly one — the diff page's header. */
        val singleFile: FilePatch? get() = files.singleOrNull()
    }

    private val HUNK_RE = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$""")

    fun parse(raw: List<String>): Parsed {
        val files = mutableListOf<FilePatch>()
        var builder: Builder? = null
        // A diff without a `diff --git` header (an unusual single-file call,
        // or a hand-written fixture) still parses: the builder appears on
        // first use with no paths, and the ---/+++ lines fill them in.
        fun current(): Builder = builder ?: Builder(null to null).also { builder = it }
        for (line in raw) {
            when {
                line.startsWith("diff --git ") -> {
                    builder?.let { files += it.build() }
                    builder = Builder(GitQuoted.parseDiffGitLine(line))
                }
                line.startsWith("@@@") -> {
                    // Combined diff (merge without --first-parent): kept as a
                    // fact, never renumbered.
                    current().combined = true
                    current().meta += line
                }
                line.startsWith("@@") -> current().startHunk(line)
                line.startsWith("Binary files ") || line.startsWith("GIT binary patch") -> {
                    current().binary = true
                    current().meta += line
                }
                line.startsWith("--- ") -> current().oldPath = GitQuoted.decodeDiffSide(line.substring(4))
                line.startsWith("+++ ") -> current().newPath = GitQuoted.decodeDiffSide(line.substring(4))
                line.startsWith("rename from ") ->
                    current().renamedFrom = GitQuoted.decodeToken(line.substring(12))
                line.startsWith("rename to ") ->
                    current().renamedTo = GitQuoted.decodeToken(line.substring(10))
                line.startsWith("new file mode ") -> current().newFile = true
                line.startsWith("deleted file mode ") -> current().deletedFile = true
                line.startsWith("+") -> current().content(Kind.ADD, line.substring(1))
                line.startsWith("-") -> current().content(Kind.DEL, line.substring(1))
                line.startsWith("\\") -> current().content(Kind.NO_NEWLINE, line)
                line.startsWith(" ") -> current().content(Kind.CONTEXT, line.substring(1))
                else -> current().meta += line
            }
        }
        builder?.let { files += it.build() }
        return Parsed(files)
    }

    /**
     * Mutable patch state — a small builder so the parser stays a single
     * readable pass and the public model stays immutable.
     */
    private class Builder(pair: Pair<String?, String?>) {
        var oldPath: String? = pair.first
        var newPath: String? = pair.second
        var renamedFrom: String? = null
        var renamedTo: String? = null
        var newFile = false
        var deletedFile = false
        var binary = false
        var combined = false
        val meta = mutableListOf<String>()
        val hunks = mutableListOf<Hunk>()
        private var current: HunkBuilder? = null

        fun startHunk(header: String) {
            current?.let { hunks += it.build() }
            current = HunkBuilder(header)
        }

        fun content(kind: Kind, text: String) {
            val hunk = current ?: run {
                // Content before any hunk header: kept as a fact (a rename
                // patch with no content lines is exactly this shape).
                meta += text
                return
            }
            hunk.line(kind, text)
        }

        fun build(): FilePatch {
            current?.let { hunks += it.build() }
            current = null
            return FilePatch(
                oldPath = oldPath,
                newPath = newPath ?: renamedTo,
                hunks = hunks.toList(),
                meta = meta.toList(),
                binary = binary,
                newFile = newFile,
                deletedFile = deletedFile,
                renamedFrom = renamedFrom,
                combined = combined,
            )
        }
    }

    /** One hunk in flight: the header's cursors advance as lines arrive. */
    private class HunkBuilder(private val header: String) {
        private val section: String?
        private val lines = mutableListOf<Line>()
        private var oldCursor = 0
        private var newCursor = 0

        init {
            val match = HUNK_RE.find(header)
            if (match != null) {
                oldCursor = match.groupValues[1].toIntOrNull() ?: 0
                newCursor = match.groupValues[3].toIntOrNull() ?: 0
                section = match.groupValues[5].trim().ifEmpty { null }
            } else {
                section = null
            }
            lines += Line(Kind.HUNK, header)
        }

        fun line(kind: Kind, text: String) {
            when (kind) {
                Kind.ADD -> {
                    lines += Line(kind, text, newLine = newCursor)
                    newCursor += 1
                }
                Kind.DEL -> {
                    lines += Line(kind, text, oldLine = oldCursor)
                    oldCursor += 1
                }
                Kind.CONTEXT -> {
                    lines += Line(kind, text, oldLine = oldCursor, newLine = newCursor)
                    oldCursor += 1
                    newCursor += 1
                }
                // META / HUNK / NO_NEWLINE carry no numbering.
                else -> lines += Line(kind, text)
            }
        }

        fun build(): Hunk = Hunk(header = header, section = section, lines = lines.toList())
    }
}
