package app.pocketshell.widget.network

/**
 * A line-preserving view of `~/.ssh/config`.
 *
 * The file is the user's: hand-written comments, `Include`, `Match`, `Host *` and
 * multi-alias blocks are all legitimate and must survive an edit made from a
 * phone. So the parse keeps every line, remembers where each `Host` block starts
 * and ends, and an edit replaces EXACTLY those lines. Blocks this app cannot
 * model faithfully are listed read-only and never rewritten.
 *
 * Values that reach the file are validated first: a newline in a HostName would
 * otherwise be a way to smuggle in a `ProxyCommand`.
 */
data class SshHost(
    val alias: String,
    val hostName: String? = null,
    val user: String? = null,
    val port: Int? = null,
    val identityFile: String? = null,
    val proxyJump: String? = null,
    /** Directives this app does not model (ForwardAgent, ProxyCommand, …), kept verbatim on edit. */
    val extra: List<String> = emptyList(),
) {
    /** What to connect to: the HostName if set, else the alias itself. */
    val target: String get() = hostName ?: alias

    /** The `user@host:port` line shown in lists. */
    val summary: String
        get() = buildString {
            if (user != null) append(user).append('@')
            append(target)
            if (port != null && port != 22) append(':').append(port)
        }
}

/** One `Host` block (or an unmodelled one) with its line range in the file. */
data class HostBlock(
    val firstLine: Int,
    /** Exclusive. Trailing blank lines are not part of the block. */
    val endLine: Int,
    val patterns: List<String>,
    /** The parsed host when this block is a plain single-alias `Host` this app may edit. */
    val host: SshHost?,
    val isMatch: Boolean = false,
) {
    val editable: Boolean get() = host != null
}

class ParsedConfig(val lines: List<String>, val blocks: List<HostBlock>) {
    val hosts: List<SshHost> get() = blocks.mapNotNull { it.host }
    val readOnly: List<HostBlock> get() = blocks.filter { !it.editable }
    fun find(alias: String): HostBlock? = blocks.firstOrNull { it.host?.alias == alias }
}

object SshConfig {

    private val ALIAS = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val HOSTNAME = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,252}$")
    private val USER = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$")
    private val IDENTITY = Regex("^(~/\\.ssh/|/root/\\.ssh/)[A-Za-z0-9._-]{1,100}$")
    private val JUMP = Regex("^[A-Za-z0-9._@:,-]{1,200}$")

    fun validAlias(s: String): Boolean = ALIAS.matches(s)

    /** A message when [h] must not be written, else null. */
    fun problem(h: SshHost): String? = when {
        !ALIAS.matches(h.alias) -> "Name: letters, digits, . _ - (no spaces, no wildcards)"
        h.hostName != null && !HOSTNAME.matches(h.hostName) -> "Host: a name or address, no spaces"
        h.user != null && !USER.matches(h.user) -> "User: letters, digits, . _ -"
        h.port != null && h.port !in 1..65535 -> "Port: 1–65535"
        h.identityFile != null && !IDENTITY.matches(h.identityFile) -> "Key: a file inside ~/.ssh"
        h.proxyJump != null && !JUMP.matches(h.proxyJump) -> "Jump host: names separated by commas"
        h.extra.any { '\n' in it || '\r' in it || '\u0000' in it } -> "Unsupported characters"
        else -> null
    }

    // --------------------------------------------------------------- parse

    fun parse(text: String): ParsedConfig {
        val lines = if (text.isEmpty()) emptyList() else text.split("\n").let { if (it.last().isEmpty()) it.dropLast(1) else it }
        data class Start(val line: Int, val patterns: List<String>, val isMatch: Boolean)
        val starts = ArrayList<Start>()
        lines.forEachIndexed { i, raw ->
            val (key, value) = directive(raw) ?: return@forEachIndexed
            when (key.lowercase()) {
                "host" -> starts += Start(i, value.split(Regex("\\s+")).filter { it.isNotEmpty() }, false)
                "match" -> starts += Start(i, listOf(value), true)
            }
        }
        val blocks = starts.mapIndexed { n, s ->
            var end = if (n + 1 < starts.size) starts[n + 1].line else lines.size
            while (end > s.line + 1 && lines[end - 1].isBlank()) end--
            val body = lines.subList(s.line + 1, end)
            val host = if (!s.isMatch && s.patterns.size == 1 && validAlias(s.patterns[0])) readHost(s.patterns[0], body) else null
            HostBlock(s.line, end, s.patterns, host, s.isMatch)
        }
        return ParsedConfig(lines, blocks)
    }

    /** `Key value` or `Key=value`; null for blanks and comments. */
    internal fun directive(raw: String): Pair<String, String>? {
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val m = Regex("^([A-Za-z][A-Za-z0-9]*)\\s*(?:=\\s*|\\s+)(.*)$").matchEntire(t) ?: return null
        return m.groupValues[1] to m.groupValues[2].trim().trim('"')
    }

    private fun readHost(alias: String, body: List<String>): SshHost {
        var hostName: String? = null
        var user: String? = null
        var port: Int? = null
        var identity: String? = null
        var jump: String? = null
        val extra = ArrayList<String>()
        for (raw in body) {
            val d = directive(raw)
            if (d == null) {
                if (raw.isNotBlank()) extra += raw.trim()
                continue
            }
            when (d.first.lowercase()) {
                "hostname" -> hostName = d.second
                "user" -> user = d.second
                "port" -> port = d.second.toIntOrNull()
                "identityfile" -> if (identity == null) identity = d.second else extra += raw.trim()
                "proxyjump" -> jump = d.second
                else -> extra += raw.trim()
            }
        }
        return SshHost(alias, hostName, user, port, identity, jump, extra)
    }

    // ---------------------------------------------------------------- edit

    fun render(h: SshHost): List<String> = buildList {
        add("Host ${h.alias}")
        h.hostName?.let { add("    HostName $it") }
        h.user?.let { add("    User $it") }
        h.port?.let { add("    Port $it") }
        h.identityFile?.let { add("    IdentityFile $it") }
        h.proxyJump?.let { add("    ProxyJump $it") }
        h.extra.forEach { add("    $it") }
    }

    sealed interface Edit {
        data class Done(val text: String) : Edit
        data class Refused(val reason: String) : Edit
    }

    /** Add [h], or replace the block of the same alias. Everything else is untouched. */
    fun upsert(text: String, h: SshHost): Edit {
        problem(h)?.let { return Edit.Refused(it) }
        val cfg = parse(text)
        val existing = cfg.find(h.alias)
        if (existing == null && cfg.blocks.any { h.alias in it.patterns }) {
            return Edit.Refused("\"${h.alias}\" is part of a block this app does not edit")
        }
        val lines = cfg.lines.toMutableList()
        val block = render(h)
        if (existing != null) {
            repeat(existing.endLine - existing.firstLine) { lines.removeAt(existing.firstLine) }
            lines.addAll(existing.firstLine, block)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.addAll(block)
        }
        return Edit.Done(join(lines))
    }

    fun remove(text: String, alias: String): Edit {
        val cfg = parse(text)
        val block = cfg.find(alias) ?: return Edit.Refused("No editable host named \"$alias\"")
        val lines = cfg.lines.toMutableList()
        repeat(block.endLine - block.firstLine) { lines.removeAt(block.firstLine) }
        // Removing the LAST block also removes the blank separator that preceded it.
        if (block.firstLine >= lines.size) while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        // Do not leave a double blank line where the block was.
        while (block.firstLine in 1 until lines.size && lines[block.firstLine].isBlank() && lines[block.firstLine - 1].isBlank()) {
            lines.removeAt(block.firstLine)
        }
        return Edit.Done(join(lines))
    }

    private fun join(lines: List<String>): String = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
}
