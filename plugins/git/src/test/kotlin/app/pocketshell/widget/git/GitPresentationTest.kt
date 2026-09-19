package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overview pane's pure presentation rules: the CONFLICTS / STAGED /
 * UNSTAGED grouping of porcelain entries (the parser's XY columns, git's
 * own index/worktree split), the tracking + worktree glyphs, the render
 * caps of the bounded inspection pages and the remote URL shortening.
 * Every grouping fixture is a literal `git status --porcelain=v1 -b`
 * block, so the grouping is tested against the exact bytes git prints.
 */
class GitPresentationTest {

    // ----------------------------------------------------- file grouping

    @Test
    fun `staged and unstaged and untracked partition into the right sections`() {
        val status = GitStatusParser.parse("## main\nM  a.txt\n M b.txt\nMM c.txt\n?? d.txt\n")
        val staged = GitPresentation.stagedRows(status.entries)
        val unstaged = GitPresentation.unstagedRows(status.entries)
        // STAGED: the index column carries the change (M  a, MM c)
        assertEquals(listOf("a.txt", "c.txt"), staged.map { it.label })
        assertEquals(listOf('M', 'M'), staged.map { it.letter })
        // UNSTAGED: a worktree change or untracked ( M b, MM c, ?? d)
        assertEquals(listOf("b.txt", "c.txt", "d.txt"), unstaged.map { it.label })
        assertEquals(listOf('M', 'M', '?'), unstaged.map { it.letter })
        // an untracked path is never staged
        assertFalse(staged.any { it.label == "d.txt" })
    }

    @Test
    fun `a staged-only deletion never appears under unstaged`() {
        val status = GitStatusParser.parse("## main\nD  gone.txt\n")
        assertEquals(listOf('D'), GitPresentation.stagedRows(status.entries).map { it.letter })
        assertTrue(GitPresentation.unstagedRows(status.entries).isEmpty())
    }

    @Test
    fun `a worktree-only change never appears under staged`() {
        val status = GitStatusParser.parse("## main\n M dirty.txt\n")
        assertTrue(GitPresentation.stagedRows(status.entries).isEmpty())
        assertEquals(listOf("dirty.txt"), GitPresentation.unstagedRows(status.entries).map { it.label })
    }

    @Test
    fun `an entry changed on both sides appears in both sections`() {
        // git's model, not a rendering bug: the staged half and the
        // worktree half are two facts
        val status = GitStatusParser.parse("## main\nMM c.txt\n")
        assertEquals(listOf("c.txt"), GitPresentation.stagedRows(status.entries).map { it.label })
        assertEquals(listOf("c.txt"), GitPresentation.unstagedRows(status.entries).map { it.label })
    }

    @Test
    fun `a conflicted entry is shown once - in CONFLICTS - never as staging noise`() {
        // conflicts sit ABOVE staged/unstaged with one guidance line, so a
        // conflicted path renders exactly once
        val status = GitStatusParser.parse("## main\nUU both.txt\n M other.txt\n")
        val conflicts = GitPresentation.conflictRows(status.entries)
        assertEquals(listOf("both.txt"), conflicts.map { it.label })
        assertEquals('U', conflicts.single().letter)
        // " M other.txt" is a worktree-side change: unstaged only
        assertTrue(GitPresentation.stagedRows(status.entries).isEmpty())
        assertEquals(listOf("other.txt"), GitPresentation.unstagedRows(status.entries).map { it.label })
    }

    @Test
    fun `every unmerged shape lands in CONFLICTS with its unmerged side first`() {
        val status = GitStatusParser.parse("## main\nUU u.txt\nAA a.txt\nDD d.txt\nUA ua.txt\nAU au.txt\n")
        val rows = GitPresentation.conflictRows(status.entries)
        assertEquals(listOf("u.txt", "a.txt", "d.txt", "ua.txt", "au.txt"), rows.map { it.label })
        // the unmerged 'U' wins; AA stays 'A', DD stays 'D'
        assertEquals(listOf('U', 'A', 'D', 'U', 'U'), rows.map { it.letter })
    }

    @Test
    fun `a conflicted rename renders the arrow form in CONFLICTS`() {
        val status = GitStatusParser.parse("## main\nUU \"old name.txt\" -> \"new name.txt\"\n")
        assertEquals(
            listOf("old name.txt -> new name.txt"),
            GitPresentation.conflictRows(status.entries).map { it.label },
        )
    }

    @Test
    fun `renames render the arrow form the parser provides`() {
        val status = GitStatusParser.parse("## main\nR  old.txt -> new.txt\n")
        val row = GitPresentation.stagedRows(status.entries).single()
        assertEquals('R', row.letter)
        assertEquals("old.txt -> new.txt", row.label)
        assertTrue(GitPresentation.unstagedRows(status.entries).isEmpty())
    }

    @Test
    fun `quoted rename paths render decoded with the arrow`() {
        val status = GitStatusParser.parse("## main\nR  \"old name.txt\" -> \"new name.txt\"\n")
        assertEquals(
            listOf("old name.txt -> new name.txt"),
            GitPresentation.stagedRows(status.entries).map { it.label },
        )
    }

    @Test
    fun `an empty worktree renders no rows in any section`() {
        val status = GitStatusParser.parse("## main\n")
        assertTrue(GitPresentation.stagedRows(status.entries).isEmpty())
        assertTrue(GitPresentation.unstagedRows(status.entries).isEmpty())
        assertTrue(GitPresentation.conflictRows(status.entries).isEmpty())
    }

    // --------------------------------------------------- tracking glyphs

    @Test
    fun `tracking glyphs - ahead, behind, both, none`() {
        assertEquals("↑2", GitPresentation.trackingGlyphs(ahead = 2, behind = null))
        assertEquals("↓5", GitPresentation.trackingGlyphs(ahead = null, behind = 5))
        assertEquals("↑1↓2", GitPresentation.trackingGlyphs(ahead = 1, behind = 2))
        assertEquals("", GitPresentation.trackingGlyphs(ahead = null, behind = null))
    }

    @Test
    fun `zero counters render no glyph - a glyph means a nonzero divergence`() {
        assertEquals("", GitPresentation.trackingGlyphs(ahead = 0, behind = 0))
        assertEquals("↓3", GitPresentation.trackingGlyphs(ahead = 0, behind = 3))
        assertEquals("↑4", GitPresentation.trackingGlyphs(ahead = 4, behind = 0))
    }

    // ---------------------------------------------------- worktree marker

    @Test
    fun `the worktree marker is full when dirty and hollow when clean`() {
        assertEquals('●', GitPresentation.worktreeGlyph(dirty = true))
        assertEquals('○', GitPresentation.worktreeGlyph(dirty = false))
    }

    @Test
    fun `the marker derives from the parser's own dirtiness`() {
        val dirty = GitStatusParser.parse("## main\n M a.txt\n?? b\n").dirty
        val clean = GitStatusParser.parse("## main...origin/main\n").dirty
        assertTrue(dirty)
        assertFalse(clean)
        assertEquals('●', GitPresentation.worktreeGlyph(dirty))
        assertEquals('○', GitPresentation.worktreeGlyph(clean))
    }

    // --------------------------------------------------- render caps

    @Test
    fun `capLines keeps everything when under the cap`() {
        val capped = GitPresentation.capLines(listOf("+a", " b", "-c"), maxLines = 10)
        assertEquals(listOf("+a", " b", "-c"), capped.lines)
        assertEquals(0, capped.hidden)
    }

    @Test
    fun `capLines cuts at the cap with the real hidden count`() {
        val raw = (1..405).map { "line $it" }
        val capped = GitPresentation.capLines(raw, maxLines = 400)
        assertEquals(400, capped.lines.size)
        assertEquals(5, capped.hidden)
        assertEquals("line 1", capped.lines.first())
        assertEquals("line 400", capped.lines.last())
    }

    @Test
    fun `capLines ellipsises overlong lines at two hundred characters`() {
        val long = "x".repeat(300)
        val capped = GitPresentation.capLines(listOf(long, "short"), maxLines = 10)
        assertEquals("x".repeat(GitPresentation.MAX_LINE_CHARS) + "…", capped.lines[0])
        assertEquals("short", capped.lines[1])
        assertEquals(0, capped.hidden)
    }

    @Test
    fun `capLines on empty input renders nothing and hides nothing`() {
        val capped = GitPresentation.capLines(emptyList(), maxLines = 400)
        assertTrue(capped.lines.isEmpty())
        assertEquals(0, capped.hidden)
    }

    // ------------------------------------------------------- short URLs

    @Test
    fun `https urls drop scheme and git suffix`() {
        assertEquals("github.com/user/repo", GitPresentation.shortUrl("https://github.com/user/repo.git"))
        assertEquals("example.org/x", GitPresentation.shortUrl("http://example.org/x.git"))
    }

    @Test
    fun `scp-form and ssh urls shorten to host and path`() {
        assertEquals("github.com/other/repo", GitPresentation.shortUrl("git@github.com:other/repo.git"))
        assertEquals("gitlab.com/a/b", GitPresentation.shortUrl("ssh://gitlab.com/a/b.git"))
    }

    @Test
    fun `local paths pass through verbatim apart from the git suffix`() {
        assertEquals("/srv/git/repo", GitPresentation.shortUrl("/srv/git/repo"))
        assertEquals("../bare", GitPresentation.shortUrl("../bare.git"))
    }
}
