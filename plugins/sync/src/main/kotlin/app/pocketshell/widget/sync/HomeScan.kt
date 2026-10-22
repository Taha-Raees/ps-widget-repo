package app.pocketshell.widget.sync

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * "What is in my home, and how big is it?" — the data behind the folder picker.
 *
 * The user decides what their data is worth: nothing is hidden for being large.
 * Sizes are measured on the HOST side (the Linux home is `rootfs/root`, readable
 * by the app), walking WITHOUT following symlinks, and they honour the SAME
 * leave-out rules the real backup will apply — so the number on screen is what
 * will actually be archived (before compression), not a raw directory total.
 *
 * Known tools (Claude Code, ZCode, Codex, OpenCode, Kilo, Gemini, …) are
 * recognised by the folders they keep their settings and history in, and
 * labelled, because "~/.claude" means nothing to most people and "Claude Code —
 * settings, history, skills" does.
 */

/** Tools whose data lives in well-known places under `~`; only those that exist are shown. */
object KnownApps {

    /** Path relative to `~` → what it is. Order is display order within the "tools" group. */
    val ALL: List<Pair<String, String>> = listOf(
        ".claude" to "Claude Code — settings, history, skills",
        ".claude.json" to "Claude Code — account and preferences file",
        ".config/Claude" to "Claude — desktop settings",
        ".local/share/claude" to "Claude Code — program files (re-downloadable)",
        ".zcode" to "ZCode",
        ".codex" to "Codex",
        ".opencode" to "OpenCode",
        ".config/opencode" to "OpenCode — settings",
        ".local/share/opencode" to "OpenCode — data",
        ".config/kilo" to "Kilo Code — settings",
        ".local/share/kilo" to "Kilo Code — data",
        ".gemini" to "Gemini CLI",
        ".hermes" to "Hermes Agent",
        ".cline" to "Cline",
        ".qwen" to "Qwen",
        ".agent" to "Agent settings",
        ".ssh" to "SSH keys and hosts",
        ".gitconfig" to "Git settings",
        ".bashrc" to "Shell setup (.bashrc)",
        ".profile" to "Shell setup (.profile)",
        ".bun" to "Bun",
        ".cargo" to "Rust (Cargo)",
        ".npm" to "npm cache",
        ".gradle" to "Gradle",
        ".android" to "Android tooling",
    )

    private val byPath: Map<String, String> = ALL.toMap()

    fun label(rel: String): String? = byPath[rel]
}

/** One line of the picker: a path under `~`, what it is, and (once measured) how big it is. */
data class HomeRow(
    val rel: String,
    val label: String?,
    val isDir: Boolean,
    /** null while the size is still being measured. */
    val bytes: Long? = null,
    val files: Long? = null,
    /** How many files/folders inside were skipped by the leave-out rules. */
    val leftOut: Int = 0,
) {
    val known: Boolean get() = label != null
}

/** tar `-X` globs as the guest's tar reads them: `*` crosses `/`, `?` is one character, the rest is literal. */
object TarGlob {

    fun compile(patterns: List<String>): (String) -> Boolean {
        val regexes = patterns.map { toRegex(it) }
        return { name -> regexes.any { it.matches(name) } }
    }

    fun toRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString())
    }
}

/** The outcome of measuring one path. */
data class Measured(val bytes: Long, val files: Long, val leftOut: Int)

class HomeScanner(private val home: File) {

    /**
     * The picker's rows, sizes still unknown: known tool paths that exist
     * first (in [KnownApps.ALL] order), then every other top-level entry —
     * folders before files, alphabetical. A known path that is ALSO a top-level
     * entry appears once, in the tools group.
     */
    fun entries(): List<HomeRow> {
        val tools = KnownApps.ALL.mapNotNull { (rel, label) ->
            val f = File(home, rel)
            if (exists(f)) HomeRow(rel, label, isDir = isDir(f)) else null
        }
        val toolPaths = tools.map { it.rel }.toSet()
        val others = home.listFiles().orEmpty()
            .filter { it.name !in toolPaths }
            .map { HomeRow(it.name, null, isDir = isDir(it)) }
            .sortedWith(compareByDescending<HomeRow> { it.isDir }.thenBy { it.rel.lowercase() })
        return tools + others
    }

    private fun exists(f: File): Boolean = Files.exists(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    private fun isDir(f: File): Boolean = Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    /**
     * The size of [rel] AS IT WILL BE ARCHIVED: excluded directories are skipped
     * whole, excluded files are not counted, symlinks count as nothing (they are
     * stored as links). [isCancelled] is polled at every file, so a big folder
     * can be abandoned instantly when the user changes a setting.
     */
    fun measure(rel: String, isExcluded: (String) -> Boolean, isCancelled: () -> Boolean = { false }): Measured? {
        val start = File(home, rel).toPath()
        if (!Files.exists(start, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Measured(0, 0, 0)
        var bytes = 0L
        var files = 0L
        var leftOut = 0
        val homePath = home.toPath()

        fun member(p: Path): String = "root/${homePath.relativize(p).joinToString("/")}"

        try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isCancelled()) return FileVisitResult.TERMINATE
                    if (dir != homePath && isExcluded(member(dir))) {
                        leftOut++
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isCancelled()) return FileVisitResult.TERMINATE
                    if (isExcluded(member(file))) {
                        leftOut++
                    } else if (attrs.isRegularFile) {
                        bytes += attrs.size()
                        files++
                    }
                    return FileVisitResult.CONTINUE
                }

                // An unreadable entry degrades alone: it simply is not counted.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // partial counts are still the honest best answer
        }
        return if (isCancelled()) null else Measured(bytes, files, leftOut)
    }
}

/** Selection arithmetic for the picker — pure, so the UI stays declarative. */
object HomeSelection {

    /** Total bytes of the ticked rows once measured; null while any ticked row is still being measured. */
    fun selectedBytes(rows: List<HomeRow>, selected: Set<String>): Long? {
        val picked = rows.filter { it.rel in selected }
        if (picked.any { it.bytes == null }) return null
        return picked.sumOf { it.bytes ?: 0L }
    }

    /** Ticking a folder drops any ticked descendant, and ticking a descendant of a ticked folder is a no-op: one path, once. */
    fun toggle(selected: Set<String>, rel: String): Set<String> =
        if (rel in selected) selected - rel else normalize(selected + rel)

    /** Remove entries already covered by a ticked ancestor (`.config` covers `.config/kilo`). */
    fun normalize(selected: Set<String>): Set<String> =
        selected.filter { path -> selected.none { other -> other != path && path.startsWith("$other/") } }.toSet()

    /** "4.2 GB"-style size for the picker. */
    fun size(bytes: Long?): String = when {
        bytes == null -> "…"
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
