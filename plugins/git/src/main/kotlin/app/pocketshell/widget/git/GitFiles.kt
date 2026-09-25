package app.pocketshell.widget.git

/**
 * Pure file-browsing model for a repository: the tracked paths git reports
 * (`ls-files`), the untracked paths porcelain reports (`?? path` / `?? dir/`)
 * and the porcelain letters that mark a pending change. No android imports —
 * every rule here is JVM-tested (GitFilesTest).
 *
 * DIRECTORY BROWSING is derived, never scanned: git lists FILES, so a level's
 * subdirectories (and each one's descendant file count) are computed from the
 * paths. Two honest limits are modelled rather than hidden:
 *
 *   - an UNTRACKED directory (`?? freshdir/`) is a single fact git reports and
 *     does not enumerate, so descending into it says so instead of pretending
 *     to know its contents ([Listing.unindexed]);
 *   - the listing is capped ([Listing.moreEntries] carries the real remainder).
 */
internal object GitFiles {

    /** One browsable row: a directory or a tracked/untracked file. */
    data class Entry(
        val path: String,
        val name: String,
        val isDir: Boolean,
        /** Tracked files under a directory (0 for files). */
        val fileCount: Int,
        /** The porcelain letter when git reports a pending change; null = clean. */
        val status: Char?,
        /** True when git reports this path as untracked ("??"). */
        val untracked: Boolean,
    ) {
        val dirty: Boolean get() = status != null
    }

    /** What one directory level (or one query) renders. */
    data class Listing(
        val dirs: List<Entry>,
        val files: List<Entry>,
        /** Entries the cap dropped — a real count, never a guess. */
        val moreEntries: Int,
        /** True when the level lives inside an untracked, unenumerated dir. */
        val unindexed: Boolean,
        /** True when the level is rendered as a flat search result. */
        val searching: Boolean,
    ) {
        val isEmpty: Boolean get() = dirs.isEmpty() && files.isEmpty()
    }

    /** An untracked path as porcelain reports it: `?? path` or `?? dir/`. */
    data class UntrackedRef(val path: String, val isDir: Boolean)

    /** The untracked refs of a porcelain snapshot (a trailing "/" means dir). */
    fun untrackedRefs(entries: List<GitStatusParser.PorcelainEntry>): List<UntrackedRef> =
        entries.filter { it.untracked }
            .map { entry ->
                val dir = entry.path.endsWith("/")
                UntrackedRef(path = entry.path.trimEnd('/'), isDir = dir)
            }

    /**
     * `git ls-files` rows → relative paths, capped at [cap]. The remainder is
     * returned as a real count so the UI can say how much it is not showing.
     */
    fun parseLsFiles(lines: List<String>, cap: Int = LS_FILES_CAP): Pair<List<String>, Int> {
        val paths = ArrayList<String>(minOf(lines.size, cap))
        var hidden = 0
        for (line in lines) {
            val decoded = GitQuoted.decodeToken(line)
            if (decoded.isEmpty()) continue
            if (paths.size >= cap) hidden += 1 else paths += decoded
        }
        return paths to hidden
    }

    /** Ancestor directories of a path, root-first: `a/b/c.txt` → `a`, `a/b`. */
    fun ancestors(path: String): List<String> {
        val out = mutableListOf<String>()
        var i = path.indexOf('/')
        while (i > 0) {
            out += path.substring(0, i)
            i = path.indexOf('/', i + 1)
        }
        return out
    }

    /** Breadcrumb segments of a directory path ("" → empty, the root). */
    fun crumbs(dir: String): List<String> =
        if (dir.isEmpty()) emptyList() else dir.split('/')

    /** The name shown for a directory row ("" is the repository root). */
    fun dirLabel(dir: String): String =
        if (dir.isEmpty()) "/" else dir.substringAfterLast('/')

    /**
     * One level of the tree — or the flat result of a query (the search mode
     * exists because a real repository carries far more paths than a phone
     * can browse one directory at a time).
     */
    fun listing(
        tracked: List<String>,
        untracked: List<UntrackedRef>,
        statusByPath: Map<String, Char>,
        dir: String,
        query: String = "",
        maxEntries: Int = LISTING_CAP,
    ): Listing {
        val clean = query.trim()
        if (clean.isNotEmpty()) return search(tracked, untracked, statusByPath, clean, maxEntries)
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val dirCounts = LinkedHashMap<String, Int>()
        val files = mutableListOf<Entry>()
        for (path in tracked) {
            if (!path.startsWith(prefix)) continue
            val rest = path.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val child = prefix + rest.substring(0, slash)
                dirCounts[child] = (dirCounts[child] ?: 0) + 1
            } else {
                files += Entry(
                    path = path,
                    name = rest,
                    isDir = false,
                    fileCount = 0,
                    status = statusByPath[path],
                    untracked = false,
                )
            }
        }
        // Untracked refs that are DIRECT children of this level; an untracked
        // directory stays ONE row (git does not enumerate it).
        val untrackedDirs = LinkedHashMap<String, Boolean>()
        var unindexed = false
        for (ref in untracked) {
            if (ref.path.startsWith(prefix)) {
                val rest = ref.path.substring(prefix.length)
                if (rest.contains('/')) {
                    untrackedDirs[prefix + rest.substringBefore('/')] = true
                } else if (ref.isDir) {
                    untrackedDirs[ref.path] = true
                } else {
                    files += Entry(
                        path = ref.path,
                        name = rest,
                        isDir = false,
                        fileCount = 0,
                        status = UNTRACKED,
                        untracked = true,
                    )
                }
            } else if (dir.isNotEmpty() && (dir == ref.path || dir.startsWith("$ref.path/"))) {
                // We descended into an untracked directory: git has not listed
                // its contents and we do not invent them.
                unindexed = true
            }
        }
        val dirPaths = (dirCounts.keys + untrackedDirs.keys).toSortedSet()
        val dirs = dirPaths.map { path ->
            val isUntrackedDir = untrackedDirs.containsKey(path)
            Entry(
                path = path,
                name = dirLabel(path),
                isDir = true,
                fileCount = dirCounts[path] ?: 0,
                status = if (isUntrackedDir || isDirtyDir(path, statusByPath)) DIRTY_DIR else null,
                untracked = isUntrackedDir,
            )
        }
        return cap(
            dirs = dirs,
            files = files.sortedBy { it.name },
            maxEntries = maxEntries,
            unindexed = unindexed,
            searching = false,
        )
    }


    /** A path shortened to its file name (search results keep the context). */
    fun fileName(path: String): String = path.substringAfterLast('/')

    /** The flat, filtered mode: matching paths from the whole repository. */
    private fun search(
        tracked: List<String>,
        untracked: List<UntrackedRef>,
        statusByPath: Map<String, Char>,
        query: String,
        maxEntries: Int,
    ): Listing {
        val needle = query.lowercase()
        val matches = mutableListOf<Entry>()
        for (path in tracked) {
            if (!path.lowercase().contains(needle)) continue
            matches += Entry(
                path = path,
                name = path,
                isDir = false,
                fileCount = 0,
                status = statusByPath[path],
                untracked = false,
            )
        }
        for (ref in untracked) {
            if (!ref.path.lowercase().contains(needle)) continue
            matches += Entry(
                path = ref.path,
                name = ref.path,
                isDir = ref.isDir,
                fileCount = 0,
                status = if (ref.isDir) DIRTY_DIR else UNTRACKED,
                untracked = true,
            )
        }
        return cap(
            dirs = emptyList(),
            files = matches.sortedBy { it.path },
            maxEntries = maxEntries,
            unindexed = false,
            searching = true,
        )
    }

    /** A directory is dirty when any pending change lives beneath it. */
    private fun isDirtyDir(dir: String, statusByPath: Map<String, Char>): Boolean {
        val prefix = "$dir/"
        return statusByPath.keys.any { it.startsWith(prefix) }
    }

    private fun cap(
        dirs: List<Entry>,
        files: List<Entry>,
        maxEntries: Int,
        unindexed: Boolean,
        searching: Boolean,
    ): Listing {
        val total = dirs.size + files.size
        if (total <= maxEntries) return Listing(dirs, files, 0, unindexed, searching)
        val keptDirs = dirs.take(maxEntries)
        val keptFiles = files.take(maxEntries - keptDirs.size)
        return Listing(
            dirs = keptDirs,
            files = keptFiles,
            moreEntries = total - keptDirs.size - keptFiles.size,
            unindexed = unindexed,
            searching = searching,
        )
    }

    /** `git ls-files` output cap per read — a phone-level bound, stated in the UI. */
    const val LS_FILES_CAP = 2000

    /** Rows rendered per level before the honest "+N more" line. */
    const val LISTING_CAP = 400

    /** The letter git uses for an untracked path (status porcelain "??"). */
    const val UNTRACKED = '?'

    /** Not a git letter: the mark a directory carries when something under it is dirty. */
    const val DIRTY_DIR = '•'
}
