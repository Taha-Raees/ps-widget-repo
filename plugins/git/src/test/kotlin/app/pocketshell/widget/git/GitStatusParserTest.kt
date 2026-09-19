package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The porcelain v1 parser against real git output shapes (the format is
 * documented as stable for scripting — git-scm.com/docs/git-status). Every
 * fixture below is the literal bytes `git status --porcelain=v1 -b` prints
 * for the described situation, including the quoted-path cases.
 */
class GitStatusParserTest {

    // ------------------------------------------------------- head lines

    @Test
    fun `a clean repository on a branch`() {
        val status = GitStatusParser.parse("## main\n")
        assertEquals("main", status.branch)
        assertFalse(status.detached)
        assertFalse(status.noCommits)
        assertNull(status.upstream)
        assertNull(status.ahead)
        assertNull(status.behind)
        assertTrue(status.entries.isEmpty())
        assertFalse(status.dirty)
        assertEquals("clean", status.summary())
    }

    @Test
    fun `an upstream in sync shows no divergence`() {
        val status = GitStatusParser.parse("## main...origin/main\n")
        assertEquals("main", status.branch)
        assertEquals("origin/main", status.upstream)
        assertNull(status.ahead)
        assertNull(status.behind)
        assertFalse(status.upstreamGone)
    }

    @Test
    fun `diverged upstream carries both counters`() {
        val status = GitStatusParser.parse("## main...origin/main [ahead 2, behind 1]\n")
        assertEquals("origin/main", status.upstream)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
    }

    @Test
    fun `ahead-only and behind-only counters`() {
        assertEquals(3, GitStatusParser.parse("## dev...origin/dev [ahead 3]\n").ahead)
        assertNull(GitStatusParser.parse("## dev...origin/dev [ahead 3]\n").behind)
        assertNull(GitStatusParser.parse("## dev...origin/dev [behind 5]\n").ahead)
        assertEquals(5, GitStatusParser.parse("## dev...origin/dev [behind 5]\n").behind)
    }

    @Test
    fun `a gone upstream is stated`() {
        val status = GitStatusParser.parse("## topic...origin/topic [gone]\n")
        assertEquals("origin/topic", status.upstream)
        assertTrue(status.upstreamGone)
        assertNull(status.ahead)
    }

    @Test
    fun `a detached head is stated`() {
        val status = GitStatusParser.parse("## HEAD (no branch)\n")
        assertNull(status.branch)
        assertTrue(status.detached)
    }

    @Test
    fun `a fresh repository has no commits`() {
        val status = GitStatusParser.parse("## No commits yet on master\n")
        assertEquals("master", status.branch)
        assertTrue(status.noCommits)
        assertFalse(status.detached)
    }

    // ---------------------------------------------------- entry lines

    @Test
    fun `worktree, staged and combined modifications`() {
        val status = GitStatusParser.parse("## main\n M a.txt\nM  b.txt\nMM c.txt\n")
        assertEquals(3, status.entries.size)
        assertEquals('M', status.entries[0].y)
        assertEquals(' ', status.entries[0].x)
        // an MM entry is genuinely both classes: staged in the index AND
        // further changed in the worktree
        assertEquals(2, status.changedCount)
        assertEquals(2, status.stagedCount)
        assertEquals(4, status.totalChanges)
        assertTrue(status.dirty)
        assertEquals("2 changed · 2 staged", status.summary())
    }

    @Test
    fun `untracked entries are counted separately`() {
        val status = GitStatusParser.parse("## main\n?? notes.txt\n?? TODO\n")
        assertEquals(2, status.untrackedCount)
        assertEquals(0, status.stagedCount)
        assertEquals(0, status.changedCount)
        assertEquals("2 untracked", status.summary())
    }

    @Test
    fun `unmerged shapes are conflicts`() {
        val status = GitStatusParser.parse("## main\nUU both.txt\nAA added.txt\nDD gone.txt\nUA us.txt\n")
        assertEquals(4, status.conflictCount)
        // conflicts are their own class, not staged/changed noise
        assertEquals(0, status.stagedCount)
        assertEquals(0, status.changedCount)
        assertEquals("4 conflicts", status.summary())
    }

    @Test
    fun `renames parse on the unquoted arrow form`() {
        val status = GitStatusParser.parse("## main\nR  old.txt -> new.txt\n")
        val entry = status.entries.single()
        assertEquals('R', entry.x)
        assertEquals("old.txt", entry.origPath)
        assertEquals("new.txt", entry.path)
        assertEquals(1, status.stagedCount)
    }

    @Test
    fun `copies parse like renames`() {
        val entry = GitStatusParser.parseEntryLine("C  src.txt -> dst.txt")!!
        assertEquals("src.txt", entry.origPath)
        assertEquals("dst.txt", entry.path)
    }

    @Test
    fun `a quoted rename with spaces on both sides`() {
        val status = GitStatusParser.parse("## main\nR  \"old name.txt\" -> \"new name.txt\"\n")
        val entry = status.entries.single()
        assertEquals("old name.txt", entry.origPath)
        assertEquals("new name.txt", entry.path)
    }

    @Test
    fun `an arrow inside a quoted path does not split the field`() {
        val status = GitStatusParser.parse("## main\nR  \"weird -> name\" -> fine.txt\n")
        val entry = status.entries.single()
        assertEquals("weird -> name", entry.origPath)
        assertEquals("fine.txt", entry.path)
    }

    @Test
    fun `a space inside a path arrives quoted`() {
        val status = GitStatusParser.parse("## main\n M \"my file.txt\"\n")
        assertEquals("my file.txt", status.entries.single().path)
    }

    @Test
    fun `non-ascii paths reassemble as UTF-8 from octal byte escapes`() {
        // git C-quotes non-ASCII bytes: 文書.txt as UTF-8 octal escapes
        val status = GitStatusParser.parse("## main\n?? \"\\346\\226\\207\\346\\233\\270.txt\"\n")
        assertEquals("文書.txt", status.entries.single().path)
    }

    @Test
    fun `escaped quote and backslash decode inside quoted paths`() {
        assertEquals(
            "a\"b\\c.txt",
            GitStatusParser.parseEntryLine("?? \"a\\\"b\\\\c.txt\"")!!.path,
        )
    }

    @Test
    fun `malformed lines are skipped, never guessed into paths`() {
        val status = GitStatusParser.parse("## main\nthis is not status\nab\nx  \n?? ok.txt\n")
        assertEquals(listOf("ok.txt"), status.entries.map { it.path })
    }

    @Test
    fun `empty porcelain output parses as unknown-branch clean`() {
        val status = GitStatusParser.parse("")
        assertNull(status.branch)
        assertTrue(status.entries.isEmpty())
        assertFalse(status.dirty)
        assertEquals("clean", status.summary())
    }

    @Test
    fun `a full block parses head plus entries in order`() {
        val status = GitStatusParser.parse(
            """
            ## feature...origin/feature [ahead 1]
            M  staged.kt
             M dirty.kt
            ?? new.kt
            """.trimIndent() + "\n",
        )
        assertEquals("feature", status.branch)
        assertEquals("origin/feature", status.upstream)
        assertEquals(1, status.ahead)
        assertEquals(listOf("staged.kt", "dirty.kt", "new.kt"), status.entries.map { it.path })
        assertEquals("1 changed · 1 staged · 1 untracked", status.summary())
    }
}
