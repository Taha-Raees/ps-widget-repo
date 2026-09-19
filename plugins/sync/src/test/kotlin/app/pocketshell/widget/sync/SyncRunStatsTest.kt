package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M8.4.3 — the real-run stats parsers, fed REAL output shapes:
 *
 *   rsync  — the tail of `rsync -a --info=stats1 -- /root/src /mnt/dst`
 *            (rsync 3.2.x stats1: labeled totals with grouping commas);
 *   rclone — the end-of-run stats block `rclone copy /root/src remote:b`
 *            prints to stderr (bytes "Transferred:" line first, counts
 *            second).
 *
 * Pinned: parts missing → null (never invented), zeros are real zeros,
 * the byte formatter's binary units and one-decimal rule.
 */
class SyncRunStatsTest {

    private val rsyncStats = """
        sending incremental file list
        >f+++++++++ photos/a.jpg
        >f.st...... docs/b.txt

        Number of files: 1,348 (reg: 1,102, dir: 246)
        Number of regular files transferred: 3
        Total file size: 26,354,124 bytes
        Total transferred file size: 1,234,567 bytes
        Literal data: 1,234,567 bytes
        Matched data: 0 bytes
        File list size: 0
        Total bytes sent: 1,235,890
        Total bytes received: 85

        sent 1,235,890 bytes  received 85 bytes  824,650.00 bytes/sec
        total size is 26,354,124  speedup is 21.33
    """.trimIndent()

    private val rcloneStats = """
        Transferred:   	      1.234 MiB / 2.500 GiB, 0%, 0 B/s, ETA -
        Errors:                 0
        Checks:                 1 / 1, 100%
        Transferred:            1 / 1, 100%
        Elapsed time:         3.5s
    """.trimIndent()

    // ------------------------------------------------------------ rsync

    @Test
    fun `rsync stats1 block - transferred files and size extracted`() {
        assertEquals("3 files · 1.2 MB", SyncRunStats.fromRsync(rsyncStats))
    }

    @Test
    fun `rsync zero transfer - an honest zero, not a null`() {
        val out = "Number of regular files transferred: 0\nTotal transferred file size: 0 bytes\n"
        assertEquals("0 files · 0 B", SyncRunStats.fromRsync(out))
    }

    @Test
    fun `rsync with no parsable stats lines yields null - nothing invented`() {
        assertNull(SyncRunStats.fromRsync("sending incremental file list\nsent 816 bytes\n"))
        assertNull(SyncRunStats.fromRsync(""))
    }

    @Test
    fun `rsync with only one of the two lines keeps the real part`() {
        assertEquals("2 files", SyncRunStats.fromRsync("Number of regular files transferred: 2\n"))
        assertEquals("512 B", SyncRunStats.fromRsync("Total transferred file size: 512 bytes\n"))
    }

    // ----------------------------------------------------------- rclone

    @Test
    fun `rclone copy stats - the sizes from the first Transferred line`() {
        assertEquals("1.234 MiB / 2.500 GiB", SyncRunStats.fromRclone(rcloneStats))
    }

    @Test
    fun `rclone with no Transferred line yields null - honest`() {
        assertNull(SyncRunStats.fromRclone("Errors:                 0\nElapsed time:         3.5s\n"))
        assertNull(SyncRunStats.fromRclone(""))
    }

    @Test
    fun `rclone bare transferred value passes through`() {
        assertEquals("0 B", SyncRunStats.fromRclone("Transferred: 0 B\n"))
    }

    // --------------------------------------------------------- dispatch

    @Test
    fun `the backend dispatch picks the parser`() {
        assertEquals("3 files · 1.2 MB", SyncRunStats.from(SyncBackend.RSYNC, rsyncStats))
        assertEquals("1.234 MiB / 2.500 GiB", SyncRunStats.from(SyncBackend.RCLONE, rcloneStats))
    }

    // ---------------------------------------------------- byte formatter

    @Test
    fun `byte formatting - binary units, one decimal under a hundred`() {
        assertEquals("512 B", SyncRunStats.formatBytes(512L))
        assertEquals("1 KB", SyncRunStats.formatBytes(1_024L))
        assertEquals("1.5 KB", SyncRunStats.formatBytes(1_536L))
        assertEquals("100 KB", SyncRunStats.formatBytes(102_400L))
        assertEquals("1.2 MB", SyncRunStats.formatBytes(1_234_567L))
        assertEquals("5 GB", SyncRunStats.formatBytes(5_368_709_120L))
    }
}
