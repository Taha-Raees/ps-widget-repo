package app.pocketshell.widget.git

/**
 * C-string path decoding — ONE implementation shared by every git reader in
 * this module (status porcelain, `ls-files`, `show --name-status`, diff
 * headers). Git quotes any path containing whitespace or non-ASCII bytes in
 * the manner of a C string literal (`"caf\303\251.txt"`), and the escapes
 * are BYTES: reassembling them as UTF-8 is what keeps non-ASCII paths
 * readable (a byte-wise Latin-1 mapping would mojibake every one of them).
 *
 * The rules here mirror `git status`'s own documented quoting (see
 * git-scm.com/docs/git-status, "quoting paths"), which is the same code
 * path `ls-files` and `--name-status` use via quote_c_style().
 */
internal object GitQuoted {

    /** One decoded field plus the index just past its end in the source. */
    data class Field(val value: String, val end: Int)

    /**
     * Read one field starting at [start]. A quoted field is scanned to its
     * unescaped closing quote and decoded; a bare field runs to the end of
     * [s]. Null only when a quoted field is never closed — a malformed line
     * is refused, never guessed at.
     */
    fun read(s: String, start: Int): Field? {
        if (start >= s.length) return null
        if (s[start] != '"') return Field(s.substring(start), s.length)
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

    /** Decode one whole token (git's `diff --git` path halves, ls-files rows). */
    fun decodeToken(token: String): String {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return ""
        return read(trimmed, 0)?.value?.trimEnd() ?: trimmed
    }

    /**
     * Split a TAB-separated record into at most [limit] fields, decoding each
     * one. Tabs inside quoted fields do not split (that is exactly why git
     * quotes such paths); empty fields are preserved (`a␉␉b` → `a`,``,`b`);
     * the last field keeps any remainder, so free text with tabs survives
     * verbatim.
     */
    fun splitTabFields(line: String, limit: Int): List<String> {
        val fields = mutableListOf<String>()
        var start = 0
        while (fields.size < limit - 1) {
            val tab = nextUnquotedTab(line, start)
            if (tab < 0) break
            fields += decodeToken(line.substring(start, tab))
            start = tab + 1
        }
        fields += decodeToken(line.substring(start.coerceAtMost(line.length)))
        return fields
    }

    /** The first tab at [from] that is not inside a quoted field; -1 when none. */
    private fun nextUnquotedTab(s: String, from: Int): Int {
        var i = from
        var inQuotes = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && inQuotes -> i++
                c == '"' -> inQuotes = !inQuotes
                c == '\t' && !inQuotes -> return i
            }
            i++
        }
        return -1
    }

    /**
     * The two path halves of a `diff --git` line. The line is ambiguous for
     * exotic paths (git's own docs say so), which is why callers prefer the
     * `---` / `+++` / `rename from|to` lines when they exist; this parser
     * only has to be right for the pure-rename and pure-mode-change cases
     * where those lines are absent.
     */
    fun parseDiffGitLine(line: String): Pair<String?, String?> {
        val rest = line.removePrefix("diff --git ").trim()
        val first = read(rest, 0) ?: return null to null
        var i = first.end
        while (i < rest.length && rest[i] == ' ') i++
        if (i >= rest.length) return stripPrefix(first.value) to null
        val second = read(rest, i) ?: return stripPrefix(first.value) to null
        return stripPrefix(first.value) to stripPrefix(second.value)
    }

    /**
     * One side of a diff's `---`/`+++` line: git appends a TAB to spaced
     * paths, prefixes repo-relative paths with `a/` or `b/`, and uses
     * `/dev/null` for the absent side of adds/deletes. The prefix is
     * stripped BEFORE quote decoding, so a quoted path after the prefix
     * still decodes (`b/"caf\303\251.txt"` → `café.txt`).
     */
    fun decodeDiffSide(token: String): String? {
        val trimmed = token.trim().trimEnd('\t')
        if (trimmed == "/dev/null") return null
        val body = if (trimmed.length > 2 && trimmed[1] == '/' && (trimmed[0] == 'a' || trimmed[0] == 'b')) {
            trimmed.substring(2)
        } else {
            trimmed
        }
        return decodeToken(body)
    }

    /** `a/src/x.kt` / `b/src/x.kt` → `src/x.kt`; `/dev/null` stays itself. */
    fun stripPrefix(path: String?): String? = when {
        path == null -> null
        path == "/dev/null" -> null
        path.startsWith("a/") || path.startsWith("b/") -> path.substring(2)
        else -> path
    }
}
