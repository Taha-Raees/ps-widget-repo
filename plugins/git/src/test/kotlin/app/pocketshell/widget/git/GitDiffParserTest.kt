package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diff model: git is the diff engine, this parses what it prints and
 * numbers the hunks' lines with the hunks' own cursors — verified against
 * real unified-diff shapes.
 */
class GitDiffParserTest {

    @Test
    fun `a simple patch parses hunks with real old and new line numbers`() {
        val parsed = GitDiffParser.parse(
            listOf(
                "diff --git a/src/a.kt b/src/a.kt",
                "--- a/src/a.kt",
                "+++ b/src/a.kt",
                "@@ -1,3 +1,3 @@ class A",
                " context",
                "-removed",
                "+added",
                " more",
            ),
        )
        val file = parsed.files.single()
        assertEquals("src/a.kt", file.displayPath)
        assertEquals(1, file.hunks.size)
        assertEquals(" class A", file.hunks[0].section?.let { " $it" } ?: file.hunks[0].header)
        val lines = file.hunks[0].lines
        // lines[0] is the hunk's own header line (Kind.HUNK, unnumbered)
        assertEquals(GitDiffParser.Kind.HUNK, lines[0].kind)
        assertEquals(1, lines[1].oldLine)
        assertEquals(1, lines[1].newLine)
        assertEquals(2, lines[2].oldLine)
        assertNull(lines[2].newLine)
        assertNull(lines[3].oldLine)
        assertEquals(2, lines[3].newLine)
        assertEquals(3, lines[4].oldLine)
        assertEquals(3, lines[4].newLine)
        assertEquals(1, parsed.additions)
        assertEquals(1, parsed.deletions)
    }

    @Test
    fun `counts grow across hunks`() {
        val parsed = GitDiffParser.parse(
            listOf(
                "@@ -1 +1 @@",
                "-a",
                "+b",
                "@@ -10 +10 @@",
                "+c",
            ),
        )
        assertEquals(2, parsed.additions)
        assertEquals(1, parsed.deletions)
        assertEquals(2, parsed.files.single().hunks.size)
    }

    @Test
    fun `rename new-file and deleted-file shapes parse their facts`() {
        val rename = GitDiffParser.parse(
            listOf(
                "diff --git a/old.kt b/new.kt",
                "similarity index 90%",
                "rename from old.kt",
                "rename to new.kt",
            ),
        ).files.single()
        assertEquals("old.kt", rename.renamedFrom)
        assertEquals("new.kt", rename.newPath)
        assertTrue(rename.meta.isNotEmpty()) // a pure rename keeps its meta facts

        val added = GitDiffParser.parse(
            listOf(
                "diff --git a/x b/x",
                "new file mode 100644",
                "--- /dev/null",
                "+++ b/x",
                "@@ -0,0 +1 @@",
                "+hello",
            ),
        ).files.single()
        assertTrue(added.newFile)

        val deleted = GitDiffParser.parse(
            listOf(
                "diff --git a/x b/x",
                "deleted file mode 100644",
                "--- a/x",
                "+++ /dev/null",
                "@@ -1 +0,0 @@",
                "-bye",
            ),
        ).files.single()
        assertTrue(deleted.deletedFile)
    }

    @Test
    fun `binary files are a fact - never rendered as a broken patch`() {
        val parsed = GitDiffParser.parse(
            listOf(
                "diff --git a/img.png b/img.png",
                "Binary files a/img.png and b/img.png differ",
            ),
        )
        val file = parsed.files.single()
        assertTrue(file.binary)
        assertTrue(parsed.anyBinary)
        assertTrue(parsed.allBinary)
    }

    @Test
    fun `a combined diff is kept as meta - never renumbered`() {
        val parsed = GitDiffParser.parse(
            listOf(
                "diff --git a/x b/x",
                "@@@ -1,1 -1,1 +1,1 @@@",
                "-- x",
                "++ y",
            ),
        )
        val file = parsed.files.single()
        assertTrue(file.combined)
        assertTrue(file.hunks.isEmpty())
        assertTrue(file.meta.any { it.startsWith("@@@") })
    }

    @Test
    fun `no-newline markers survive as their own kind`() {
        val parsed = GitDiffParser.parse(
            listOf(
                "@@ -1 +1 @@",
                "-old",
                "\\ No newline at end of file",
                "+new",
            ),
        )
        val lines = parsed.files.single().hunks.single().lines
        assertEquals(GitDiffParser.Kind.DEL, lines[1].kind)
        assertEquals(GitDiffParser.Kind.NO_NEWLINE, lines[2].kind)
    }

    @Test
    fun `quoted paths decode through the shared C-string reader`() {
        val parsed = GitDiffParser.parse(
            listOf(
                "diff --git a/\"caf\\303\\251.txt\" b/\"caf\\303\\251.txt\"",
                "--- a/\"caf\\303\\251.txt\"",
                "+++ b/\"caf\\303\\251.txt\"",
                "@@ -1 +1 @@",
                "-x",
            ),
        )
        assertEquals("café.txt", parsed.files.single().displayPath)
    }

    @Test
    fun `a patch without a diff-git header still parses`() {
        val parsed = GitDiffParser.parse(
            listOf(
                "--- a/x.kt",
                "+++ b/x.kt",
                "@@ -1 +1 @@",
                "-a",
                "+b",
            ),
        )
        assertEquals("x.kt", parsed.files.single().displayPath)
        assertEquals(1, parsed.additions)
    }

    @Test
    fun `a single-file diff is named by singleFile`() {
        val parsed = GitDiffParser.parse(listOf("@@ -1 +1 @@", "-a", "+b"))
        assertEquals(1, parsed.files.size)
        assertTrue(parsed.hasHunks)
        assertFalse(parsed.isEmpty)
    }
}
