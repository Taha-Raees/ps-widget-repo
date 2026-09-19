package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8.4 — the dry-run preview parsers, fed REAL output shapes:
 *
 *   rsync  — `rsync -n --itemize-changes -- /root/src /mnt/dst` on rsync 3.x
 *            (itemize columns per rsync(1): YXcstpoguax, then " path";
 *            footer "sent … / total size is … (DRY RUN)");
 *   rclone — `rclone sync /root/src /mnt/dst --dry-run --combined -`
 *            (per rclone.org/commands/rclone_sync: one line per file,
 *            symbol + space + path, symbols = + - = * !).
 *
 * The parsers must extract every real change, ignore the tool's chatter,
 * keep unparsable lines as verbatim notices, and never invent sizes.
 */
class SyncPreviewParserTest {

    // ------------------------------------------------------------ rsync

    @Test
    fun `rsync parses the canonical itemize shapes`() {
        val stdout = """
            sending incremental file list
            >f+++++++++ notes/todo.txt
            >f.st...... src/main.c
            cd+++++++++ newdir/
            .d..t...... docs/
            .f...pog... scripts/run.sh
            *deleting   old/removed.txt
            cL+++++++++ latest -> releases/v9.tar.gz
            hf......... hard/linked.bin

            sent 1,024 bytes  received 256 bytes  2,560.00 bytes/sec
            total size is 12,345  speedup is 9.64 (DRY RUN)
        """.trimIndent()

        val preview = RsyncPreviewParser.parse(stdout)

        assertEquals(SyncBackend.RSYNC, preview.backend)
        val byPath = preview.entries.associateBy { it.path }
        assertEquals(8, preview.entries.size)
        assertEquals(PreviewKind.NEW, byPath["notes/todo.txt"]!!.kind)
        assertEquals(PreviewKind.CHANGED, byPath["src/main.c"]!!.kind)
        assertEquals(PreviewKind.NEW, byPath["newdir/"]!!.kind)
        assertEquals(PreviewKind.ATTR, byPath["docs/"]!!.kind)
        assertEquals(PreviewKind.ATTR, byPath["scripts/run.sh"]!!.kind)
        assertEquals(PreviewKind.DELETED, byPath["old/removed.txt"]!!.kind)
        // Symlink creation, link target included verbatim.
        assertEquals(PreviewKind.NEW, byPath["latest -> releases/v9.tar.gz"]!!.kind)
        // Hard-link item (only with --hard-links, but the shape is legal).
        assertEquals(PreviewKind.HARDLINK, byPath["hard/linked.bin"]!!.kind)

        assertTrue(preview.dryRunConfirmed)
        assertEquals(2, preview.summary.size)
        assertTrue(preview.summary.last().contains("(DRY RUN)"))
        // The header line is chatter, not a notice; there is none here.
        assertTrue(preview.notices.isEmpty())
        // Nothing anywhere invents sizes: the only size-looking text is the
        // tool's own verbatim footer.
        assertEquals(0, preview.unchangedCount)
    }

    @Test
    fun `rsync empty dry run - header and footer only`() {
        val stdout = """
            sending incremental file list

            sent 816 bytes  received 19 bytes  1,670.00 bytes/sec
            total size is 45,192  speedup is 54.00 (DRY RUN)
        """.trimIndent()

        val preview = RsyncPreviewParser.parse(stdout)
        assertTrue(preview.entries.isEmpty())
        assertTrue(preview.dryRunConfirmed)
        assertEquals(2, preview.summary.size)
    }

    @Test
    fun `rsync non-itemize chatter becomes verbatim notices - never entries`() {
        val stdout = """
            rsync: change_dir#3 "/no/such/dir" failed: No such file or directory (2)
            sending incremental file list
            >f+++++++++ a.txt
            rsync warning: some files vanished before they could be transferred (code 24)
        """.trimIndent()

        val preview = RsyncPreviewParser.parse(stdout)
        assertEquals(1, preview.entries.size)
        assertEquals("a.txt", preview.entries[0].path)
        assertEquals(2, preview.notices.size)
        assertTrue(preview.notices[0].startsWith("rsync: change_dir"))
    }

    @Test
    fun `rsync attribute-only change is classified as ATTR`() {
        val preview = RsyncPreviewParser.parse(".f....og... file.txt\n")
        assertEquals(1, preview.entries.size)
        assertEquals(PreviewKind.ATTR, preview.entries[0].kind)
        assertEquals(0, preview.newCount + preview.deletedCount)
    }

    // ----------------------------------------------------------- rclone

    @Test
    fun `rclone parses the combined report symbols`() {
        val stdout = """
            + docs/new-guide.md
            - old/obsolete.txt
            = keep/me.txt
            * changed/config.yml
            ! unreadable/binary.bin
        """.trimIndent()

        val preview = RclonePreviewParser.parse(stdout)

        assertEquals(SyncBackend.RCLONE, preview.backend)
        val byPath = preview.entries.associateBy { it.path }
        assertEquals(4, preview.entries.size)
        assertEquals(PreviewKind.NEW, byPath["docs/new-guide.md"]!!.kind)
        assertEquals(PreviewKind.DELETED, byPath["old/obsolete.txt"]!!.kind)
        assertEquals(PreviewKind.CHANGED, byPath["changed/config.yml"]!!.kind)
        assertEquals(PreviewKind.ERROR, byPath["unreadable/binary.bin"]!!.kind)
        // Unchanged files are counted, never listed.
        assertEquals(1, preview.unchangedCount)
        assertTrue(preview.notices.isEmpty())
    }

    @Test
    fun `rclone empty report - already in sync`() {
        val preview = RclonePreviewParser.parse("")
        assertTrue(preview.isEmpty)
        assertTrue(preview.entries.isEmpty())
        assertEquals(0, preview.unchangedCount)
    }

    @Test
    fun `rclone paths with spaces survive - the path is everything after the first space`() {
        val preview = RclonePreviewParser.parse("+ my documents/new file.txt\n")
        assertEquals(1, preview.entries.size)
        assertEquals("my documents/new file.txt", preview.entries[0].path)
    }

    @Test
    fun `rclone stray lines become notices`() {
        val preview = RclonePreviewParser.parse("NOTICE: something odd\n+ real.txt\n")
        assertEquals(1, preview.entries.size)
        assertEquals(1, preview.notices.size)
        assertEquals("NOTICE: something odd", preview.notices[0])
    }

    // ---------------------------------------------------------- honesty

    @Test
    fun `count lines derive from parsed entries only`() {
        val preview = RsyncPreviewParser.parse(
            """
            >f+++++++++ new.txt
            >f.st...... changed.txt
            *deleting   gone.txt
            """.trimIndent(),
        )
        assertEquals("1 new · 1 changed · 1 deleted", previewCountsLine(preview))
        assertFalse(preview.dryRunConfirmed) // no footer => not confirmed
    }
}
