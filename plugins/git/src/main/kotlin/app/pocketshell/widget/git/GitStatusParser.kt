package app.pocketshell.widget.git

/**
 * Pure parser for `git status --porcelain=v1 -b` output — the format git
 * documents as stable for scripts (git-scm.com/docs/git-status): porcelain
 * v1 "is guaranteed not to change in a backwards-incompatible way", color
 * is always off, and paths are always relative to the repository root.
 *
 * Shapes handled (all real git output):
 *
 *   ## main
 *   ## main...origin/main [ahead 1, behind 2]
 *   ## main...origin/main [gone]
 *   ## HEAD (no branch)              (detached HEAD)
 *   ## No commits yet on master      (fresh repository)
 *    M file        (worktree change)    M  file     (staged)
 *   MM file        (staged + worktree)  ?? file     (untracked)
 *   UU/AA/DD/…     (unmerged)           !! ignored  (only with --ignored)
 *   R  old -> new  (rename/copy; either side may be a quoted C string)
 *
 * Fields containing whitespace or non-ASCII bytes are quoted in the manner
 * of a C string literal ("my file.txt", "caf\303\251.txt") — git never
 * emits a space inside an unquoted field, which is what makes the " -> "
 * split of rename entries safe. Decoding reassembles UTF-8 from the octal
 * byte escapes (a byte-wise Latin-1 mapping would mojibake every non-ASCII
 * path).
 */
internal object GitStatusParser {

    /** One porcelain entry: X = index status, Y = worktree status. */
    data class PorcelainEntry(
        val x: Char,
        val y: Char,
        val path: String,
        /** Rename/copy source; null when the entry is not a rename/copy. */
        val origPath: String? = null,
    ) {
        val untracked: Boolean get() = x == '?' && y == '?'
        val conflict: Boolean get() =
            x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')
    }

    /** The `## ` head line's facts. */
    data class BranchHead(
        val branch: String?,
        val detached: Boolean = false,
        val noCommits: Boolean = false,
        val upstream: String? = null,
        val upstreamGone: Boolean = false,
        /** Null = no divergence info in the head line (in sync, or no upstream). */
        val ahead: Int? = null,
        val behind: Int? = null,
    )

    /** One repository's full status: head facts + the porcelain entries. */
    data class RepoStatus(
        val branch: String?,
        val detached: Boolean,
        val noCommits: Boolean,
        val upstream: String?,
        val upstreamGone: Boolean,
        val ahead: Int?,
        val behind: Int?,
        val entries: List<PorcelainEntry>,
    ) {
        val stagedCount: Int get() = entries.count { !it.untracked && !it.conflict && it.x != ' ' }
        val changedCount: Int get() = entries.count { !it.untracked && !it.conflict && it.y != ' ' }
        val untrackedCount: Int get() = entries.count { it.untracked }
        val conflictCount: Int get() = entries.count { it.conflict }
        val totalChanges: Int
            get() = stagedCount + changedCount + untrackedCount + conflictCount
        val dirty: Boolean get() = entries.isNotEmpty()

        /** The honest at-a-glance summary; "clean" when nothing is pending. */
        fun summary(): String {
            val parts = mutableListOf<String>()
            if (changedCount > 0) parts += "$changedCount changed"
            if (stagedCount > 0) parts += "$stagedCount staged"
            if (untrackedCount > 0) parts += "$untrackedCount untracked"
            if (conflictCount > 0) parts += "$conflictCount conflicts"
            return parts.joinToString(" · ").ifEmpty { "clean" }
        }
    }

    /** Parse a whole `git status --porcelain=v1 -b` block. */
    fun parse(statusText: String): RepoStatus {
        var head: BranchHead? = null
        val entries = ArrayList<PorcelainEntry>()
        for (line in statusText.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                if (head == null) head = parseBranchLine(line)
                continue
            }
            parseEntryLine(line)?.let { entries.add(it) }
        }
        val h = head ?: BranchHead(branch = null)
        return RepoStatus(
            branch = h.branch,
            detached = h.detached,
            noCommits = h.noCommits,
            upstream = h.upstream,
            upstreamGone = h.upstreamGone,
            ahead = h.ahead,
            behind = h.behind,
            entries = entries,
        )
    }

    /**
     * The `## ` head line. Branch facts only — the parser invents nothing
     * for shapes it does not recognize (unknown → nulls, never guesses).
     */
    fun parseBranchLine(line: String): BranchHead {
        val rest = line.removePrefix("## ").trim()
        if (rest.isEmpty()) return BranchHead(branch = null)
        if (rest.startsWith("HEAD (no branch)")) return BranchHead(branch = null, detached = true)
        if (rest.startsWith("No commits yet on ")) {
            val branch = rest.removePrefix("No commits yet on ").trim()
            return BranchHead(branch = branch.ifEmpty { null }, noCommits = true)
        }
        val dots = rest.indexOf("...")
        val branch = if (dots >= 0) rest.substring(0, dots) else rest
        val tracking = if (dots >= 0) rest.substring(dots + 3) else ""
        var upstream: String? = null
        var gone = false
        var ahead: Int? = null
        var behind: Int? = null
        if (tracking.isNotEmpty()) {
            val bracket = tracking.indexOf('[')
            val name = if (bracket >= 0) tracking.substring(0, bracket).trim() else tracking.trim()
            upstream = name.ifEmpty { null }
            if (bracket >= 0) {
                val info = tracking.substring(bracket)
                if (info.contains("gone")) gone = true
                Regex("ahead (\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull()?.let { ahead = it }
                Regex("behind (\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull()?.let { behind = it }
            }
        }
        return BranchHead(
            branch = branch.trim().ifEmpty { null },
            upstream = upstream,
            upstreamGone = gone,
            ahead = ahead,
            behind = behind,
        )
    }

    /**
     * One `<XY> <path>` line (rename form: `<XY> <orig> -> <path>`).
     * Null when the line is not a status entry — a malformed line is
     * skipped, never guessed into a path.
     */
    fun parseEntryLine(line: String): PorcelainEntry? {
        if (line.length < 4) return null
        if (line[2] != ' ') return null
        val fields = splitFields(line.substring(3))
        return when (fields.size) {
            1 -> PorcelainEntry(line[0], line[1], fields[0])
            2 -> PorcelainEntry(line[0], line[1], fields[1], origPath = fields[0])
            else -> null
        }
    }

    /**
     * Split the post-XY region into 1-2 decoded path fields. Unquoted
     * fields cannot contain spaces (git quotes those), so the " -> "
     * separator is unambiguous; quoted fields are scanned to their
     * unescaped closing quote.
     */
    private fun splitFields(rest: String): List<String> {
        val first = readField(rest, 0) ?: return emptyList()
        if (first.end >= rest.length) return listOf(first.value)
        if (!rest.startsWith(" -> ", first.end)) return emptyList()
        val second = readField(rest, first.end + ARROW.length) ?: return emptyList()
        return listOf(first.value, second.value)
    }

    private const val ARROW = " -> "

    private class Field(val value: String, val end: Int)

    /**
     * Read one field starting at [start]. Quoted fields decode to bytes and
     * reassemble as UTF-8 (octal escapes are BYTES — "\303\251" is é, not
     * two Latin-1 characters); unquoted fields run to the arrow or the end.
     */
    private fun readField(s: String, start: Int): Field? {
        if (start >= s.length) return null
        if (s[start] != '"') {
            val arrow = s.indexOf(ARROW, start)
            val end = if (arrow >= 0) arrow else s.length
            return Field(s.substring(start, end), end)
        }
        val bytes = java.io.ByteArrayOutputStream()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return Field(String(bytes.toByteArray(), Charsets.UTF_8), i + 1)
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when {
                    n == 'n' -> { bytes.write('\n'.code); i += 2 }
                    n == 't' -> { bytes.write('\t'.code); i += 2 }
                    n == 'r' -> { bytes.write('\r'.code); i += 2 }
                    n == '"' -> { bytes.write('"'.code); i += 2 }
                    n == '\\' -> { bytes.write('\\'.code); i += 2 }
                    n in '0'..'7' -> {
                        var value = 0
                        var digits = 0
                        while (digits < 3 && i + 1 < s.length && s[i + 1] in '0'..'7') {
                            value = value * 8 + (s[i + 1] - '0')
                            i += 1
                            digits += 1
                        }
                        bytes.write(value and 0xFF)
                        i += 1
                    }
                    else -> { bytes.write(n.code and 0xFF); i += 2 }
                }
            } else {
                val encoded = c.toString().toByteArray(Charsets.UTF_8)
                bytes.write(encoded, 0, encoded.size)
                i += 1
            }
        }
        return null // unterminated quote: malformed line
    }
}
