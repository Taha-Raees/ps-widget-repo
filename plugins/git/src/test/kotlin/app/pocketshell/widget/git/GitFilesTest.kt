package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure file-browsing model: directories are DERIVED from git's file
 * lists (never scanned), untracked directories stay one honest row, and
 * every cap carries a real remainder.
 */
class GitFilesTest {

    private fun entry(path: String, x: Char = ' ', y: Char = ' ') =
        GitStatusParser.PorcelainEntry(x = x, y = y, path = path)

    @Test
    fun `ls-files rows cap with a real remainder`() {
        val lines = (0 until 25).map { "f$it.kt" }
        val (paths, hidden) = GitFiles.parseLsFiles(lines, cap = 10)
        assertEquals(10, paths.size)
        assertEquals(15, hidden)
    }

    @Test
    fun `untracked refs mark directories by their trailing slash`() {
        val refs = GitFiles.untrackedRefs(listOf(entry("fresh.txt", '?', '?'), entry("dir/", '?', '?')))
        assertEquals(2, refs.size)
        assertFalse(refs[0].isDir)
        assertTrue(refs[1].isDir)
        assertEquals("dir", refs[1].path)
    }

    @Test
    fun `a level lists child directories with their tracked counts and files`() {
        val listing = GitFiles.listing(
            tracked = listOf("src/a.kt", "src/b.kt", "src/deep/c.kt", "readme.md"),
            untracked = emptyList(),
            statusByPath = emptyMap(),
            dir = "",
        )
        assertEquals(1, listing.dirs.size) // src — deep appears one level in
        assertEquals(3, listing.dirs.single().fileCount) // every tracked file beneath it
        assertEquals(listOf("readme.md"), listing.files.map { it.name })
        assertFalse(listing.searching)

        val inside = GitFiles.listing(
            tracked = listOf("src/a.kt", "src/b.kt", "src/deep/c.kt", "readme.md"),
            untracked = emptyList(),
            statusByPath = emptyMap(),
            dir = "src",
        )
        assertEquals(listOf("deep"), inside.dirs.map { it.name })
        assertEquals(1, inside.dirs.single().fileCount)
        assertEquals(listOf("a.kt", "b.kt"), inside.files.map { it.name })
    }

    @Test
    fun `a dirty directory carries the directory mark`() {
        val listing = GitFiles.listing(
            tracked = listOf("src/a.kt"),
            untracked = emptyList(),
            statusByPath = mapOf("src/a.kt" to 'M'),
            dir = "",
        )
        assertEquals(GitFiles.DIRTY_DIR, listing.dirs.single().status)
    }

    @Test
    fun `an untracked directory stays ONE row and descending says unindexed`() {
        val listing = GitFiles.listing(
            tracked = listOf("src/a.kt"),
            untracked = listOf(GitFiles.UntrackedRef("fresh", isDir = true)),
            statusByPath = emptyMap(),
            dir = "",
        )
        val row = listing.dirs.single { it.name == "fresh" }
        assertTrue(row.untracked)

        val inside = GitFiles.listing(
            tracked = listOf("src/a.kt"),
            untracked = listOf(GitFiles.UntrackedRef("fresh", isDir = true)),
            statusByPath = emptyMap(),
            dir = "fresh",
        )
        assertTrue(inside.unindexed)
    }

    @Test
    fun `untracked files ride the level as their own rows`() {
        val listing = GitFiles.listing(
            tracked = emptyList(),
            untracked = listOf(GitFiles.UntrackedRef("notes.txt", isDir = false)),
            statusByPath = emptyMap(),
            dir = "",
        )
        val row = listing.files.single()
        assertTrue(row.untracked)
        assertEquals(GitFiles.UNTRACKED, row.status)
    }

    @Test
    fun `a query switches to the flat search across the whole repository`() {
        val listing = GitFiles.listing(
            tracked = listOf("src/SearchTarget.kt", "docs/other.md"),
            untracked = listOf(GitFiles.UntrackedRef("target-dir/", isDir = true)),
            statusByPath = emptyMap(),
            dir = "src",
            query = "target",
        )
        assertTrue(listing.searching)
        assertTrue(listing.dirs.isEmpty())
        assertTrue(listing.files.any { it.path == "src/SearchTarget.kt" })
    }

    @Test
    fun `the listing cap keeps directories first with an honest remainder`() {
        val tracked = (0 until 30).map { "d$it" } + (0 until 30).map { "f$it.kt" }
        val listing = GitFiles.listing(
            tracked = tracked,
            untracked = emptyList(),
            statusByPath = emptyMap(),
            dir = "",
            maxEntries = 40,
        )
        assertEquals(40, listing.dirs.size + listing.files.size)
        assertEquals(20, listing.moreEntries)
    }

    @Test
    fun `ancestors crumbs and labels agree`() {
        assertEquals(listOf("a", "a/b"), GitFiles.ancestors("a/b/c.txt"))
        assertEquals(listOf("a", "b"), GitFiles.crumbs("a/b"))
        assertTrue(GitFiles.crumbs("").isEmpty())
        assertEquals("/", GitFiles.dirLabel(""))
        assertEquals("b", GitFiles.dirLabel("a/b"))
        assertEquals("c.txt", GitFiles.fileName("a/b/c.txt"))
    }
}
