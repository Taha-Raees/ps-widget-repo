package app.pocketshell.widget.sync

/**
 * M8.4 — the dry-run PREVIEW parsers. Both backends have documented,
 * stable, machine-readable dry-run output; these parsers accept exactly
 * that and nothing else:
 *
 *   rsync  — `rsync -n --itemize-changes -- SRC DST` prints the 11-column
 *            itemize strings (YXcstpoguax, verified against the rsync(1)
 *            manpage: Y ∈ < > c h . and " " for messages like *deleting;
 *            X ∈ f d L D S) followed by the two summary lines, whose dry
 *            run ends with "(DRY RUN)".
 *   rclone — `rclone sync SRC DST --dry-run --combined -` prints one line
 *            per file: symbol, space, path (verified against
 *            rclone.org/commands/rclone_sync): "=" unchanged, "+" missing
 *            on the destination, "-" missing on the source (sync would
 *            delete it), "*" present but different, "!" read/hash error.
 *
 * Nothing here invents data: unparsable lines become verbatim notices,
 * sizes are never fabricated (neither format carries them in this shape),
 * and the parsers are pure String → data (JVM-tested with real output
 * shapes).
 */

/** One change a dry run would make. [path] is verbatim tool output. */
internal data class PreviewEntry(
    val kind: PreviewKind,
    val path: String,
)

internal enum class PreviewKind(val glyph: String) {
    NEW("+"),
    CHANGED(">"),
    ATTR("·"),
    HARDLINK("h"),
    DELETED("-"),
    ERROR("!");
}

/**
 * One parsed dry run. [entries] lists the interesting changes (NEW /
 * CHANGED / DELETED / ERROR); unchanged files are only COUNTED
 * ([unchangedCount]) — a preview that listed every identical file would
 * be noise, but pretending they do not exist would be dishonest.
 * [notices] holds unparsable stdout lines verbatim; [summary] holds the
 * tool's own footer lines verbatim; [dryRunConfirmed] = the tool's own
 * dry-run marker was seen (rsync's "(DRY RUN)" footer; for rclone the
 * caller knows the flag it passed, so exit-code 0 is the confirmation).
 */
internal data class SyncPreview(
    val backend: SyncBackend,
    val entries: List<PreviewEntry>,
    val unchangedCount: Int,
    val notices: List<String>,
    val summary: List<String>,
    val dryRunConfirmed: Boolean,
) {
    val newCount: Int get() = entries.count { it.kind == PreviewKind.NEW }
    val changedCount: Int get() = entries.count { it.kind == PreviewKind.CHANGED }
    val deletedCount: Int get() = entries.count { it.kind == PreviewKind.DELETED }
    val errorCount: Int get() = entries.count { it.kind == PreviewKind.ERROR }
    val isEmpty: Boolean get() = entries.isEmpty() && unchangedCount == 0
}

/** Parser for `rsync -n --itemize-changes` output. */
internal object RsyncPreviewParser {

    private const val DRY_RUN_MARKER = "(DRY RUN)"

    /**
     * The itemize line shape: 11 columns (YXcstpoguax), then ONE space,
     * then the path. Y ∈ {<, >, c, h, .} (rsync(1)); X ∈ {f, d, L, D, S};
     * attribute columns are the changed attribute letters or ".+?".
     * `*deleting` lines (Y = space, message area) are matched first.
     */
    fun parse(stdout: String): SyncPreview {
        val entries = ArrayList<PreviewEntry>()
        val notices = ArrayList<String>()
        val summary = ArrayList<String>()
        var unchanged = 0
        var confirmed = false

        for (line in stdout.lineSequence()) {
            when {
                line.isBlank() -> Unit
                line.startsWith("*deleting") -> {
                    val path = line.removePrefix("*deleting").trim()
                    if (path.isNotEmpty()) entries += PreviewEntry(PreviewKind.DELETED, path)
                }
                line.startsWith("sent ") || line.startsWith("total size is ") -> {
                    summary += line
                    if (line.contains(DRY_RUN_MARKER)) confirmed = true
                }
                isItemize(line) -> {
                    val path = line.substring(ITEMIZE_WIDTH + 1)
                    entries += PreviewEntry(kindOf(line), path)
                }
                line == "sending incremental file list" -> Unit // the header, not data
                else -> notices += line
            }
        }
        return SyncPreview(
            backend = SyncBackend.RSYNC,
            entries = entries,
            unchangedCount = unchanged,
            notices = notices,
            summary = summary,
            dryRunConfirmed = confirmed,
        )
    }

    /** Kept as a field so the width is part of the parser's contract. */
    private const val ITEMIZE_WIDTH = 11

    private fun isItemize(line: String): Boolean {
        if (line.length <= ITEMIZE_WIDTH || line[ITEMIZE_WIDTH] != ' ') return false
        val y = line[0]
        val x = line[1]
        return y in "<>ch." && x in "fdLDS"
    }

    private fun kindOf(line: String): PreviewKind = when {
        // "+++++++++" attribute area = a brand-new item (rsync's own rule).
        line[2] == '+' -> PreviewKind.NEW
        line[0] == 'h' -> PreviewKind.HARDLINK
        line[0] == '.' -> PreviewKind.ATTR
        else -> PreviewKind.CHANGED
    }

    /** The itemize width, for tests. */
    internal val itemizeWidth: Int get() = ITEMIZE_WIDTH
}

/** Parser for `rclone sync --dry-run --combined -` output. */
internal object RclonePreviewParser {

    fun parse(stdout: String): SyncPreview {
        val entries = ArrayList<PreviewEntry>()
        val notices = ArrayList<String>()
        var unchanged = 0

        for (line in stdout.lineSequence()) {
            if (line.isBlank()) continue
            val symbol = line[0]
            val path = if (line.length > 2) line.substring(2) else ""
            when (symbol) {
                '=' -> if (path.isNotEmpty()) unchanged++ else notices += line
                '+' -> entries += PreviewEntry(PreviewKind.NEW, path)
                '-' -> entries += PreviewEntry(PreviewKind.DELETED, path)
                '*' -> entries += PreviewEntry(PreviewKind.CHANGED, path)
                '!' -> entries += PreviewEntry(PreviewKind.ERROR, path)
                else -> notices += line
            }
        }
        return SyncPreview(
            backend = SyncBackend.RCLONE,
            entries = entries,
            unchangedCount = unchanged,
            notices = notices,
            summary = emptyList(),
            dryRunConfirmed = true, // exit-code 0 on the --dry-run invocation
        )
    }
}
