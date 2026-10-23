package app.pocketshell.widget.guiapps

/**
 * A small strict JSON reader (objects, arrays, strings, numbers, booleans, null). The plugin cannot use
 * `org.json` in JVM tests, and GitHub's release answer is plain JSON. Depth and size are bounded; anything
 * malformed returns null instead of throwing.
 */
object MiniJson {

    const val MAX_CHARS = 4_000_000
    private const val MAX_DEPTH = 24

    fun parse(text: String): Any? {
        if (text.length > MAX_CHARS) return null
        return try {
            val p = Parser(text)
            val v = p.value(0)
            p.skipWs()
            if (p.i == text.length) v else null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalStateException("too deep")
            skipWs()
            if (i >= s.length) throw IllegalStateException("eof")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalStateException("bad char")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalStateException("bad literal")
            i += word.length
            return v
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalStateException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalStateException("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalStateException("bad unicode")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> throw IllegalStateException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value(depth + 1)
                skipWs()
                if (i >= s.length) throw IllegalStateException("eof")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                throw IllegalStateException("bad array")
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw IllegalStateException("bad key")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalStateException("no colon")
                i++
                out[k] = value(depth + 1)
                skipWs()
                if (i >= s.length) throw IllegalStateException("eof")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                throw IllegalStateException("bad object")
            }
        }
    }
}

/** A downloadable release asset. */
data class ReleaseAsset(val tag: String, val name: String, val url: String, val size: Long, val sha256: String?)

object Releases {

    /** The asset of GitHub's `releases/latest` answer that matches [pattern] (see [Assets.pick]); null when none does. */
    fun pickAsset(json: String, pattern: String): ReleaseAsset? {
        val root = MiniJson.obj(MiniJson.parse(json)) ?: return null
        val tag = (root["tag_name"] as? String)?.takeIf { it.length in 1..80 && it.none { c -> c < ' ' } } ?: return null
        val assets = MiniJson.arr(root["assets"])?.mapNotNull { MiniJson.obj(it) } ?: return null
        val byName = assets.mapNotNull { a -> (a["name"] as? String)?.let { it to a } }.toMap()
        val chosen = Assets.pick(byName.keys.toList(), pattern) ?: return null
        val a = byName.getValue(chosen)
        val url = a["browser_download_url"] as? String ?: return null
        if (!url.startsWith("https://") || !Assets.safeFileName(chosen)) return null
        val size = (a["size"] as? Long) ?: 0L
        return ReleaseAsset(tag, chosen, url, size, Assets.sha256(a["digest"] as? String))
    }
}
