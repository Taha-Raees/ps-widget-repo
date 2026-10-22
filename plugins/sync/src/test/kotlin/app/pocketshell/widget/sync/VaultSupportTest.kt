package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure support code around the backup UI: cancel's process walk, the settings store, display text. */
class VaultSupportTest {

    // ------------------------------------------------------------ process tree

    @Test
    fun `the parent pid comes from after the LAST paren of a real stat line`() {
        // Real lines captured on the device (the login shell under proot; the Gradle daemon under psx).
        val sh = "17417 (sh) S 17412 17417 17412 34816 17445 4194304 297 1810 2 4 0 0 2 0 10 -10 1 0 149476152 1843200 63 0"
        val java = "13925 (java) S 1 13925 13925 0 -1 1077936128 428324 83183 227452 134 13949 2312 796 116 10 -10 34 0 149432038 0"
        assertEquals(17412, ProcTree.parentOfStat(sh))
        assertEquals(1, ProcTree.parentOfStat(java))
        // A comm holding spaces and parens must not shift the fields.
        assertEquals(7, ProcTree.parentOfStat("99 (tmux: server (x) y) S 7 99 99 0 -1 0"))
        assertNull(ProcTree.parentOfStat(""))
        assertNull(ProcTree.parentOfStat("12 (x)"))
    }

    @Test
    fun `descendants are everything below the job, children first, and never the unrelated`() {
        // job 100 → tar 101 + gzip 102; tar → helper 103; an unrelated pair 200 → 201.
        val parentOf = mapOf(101 to 100, 102 to 100, 103 to 101, 201 to 200, 100 to 1)
        assertEquals(listOf(101, 102, 103), ProcTree.descendants(100, parentOf))
        assertTrue(ProcTree.descendants(999, parentOf).isEmpty())
        assertFalse(ProcTree.descendants(100, parentOf).contains(200))
    }

    @Test
    fun `a parent cycle cannot hang the walk`() {
        assertEquals(listOf(2), ProcTree.descendants(1, mapOf(2 to 1, 1 to 2)))
    }

    // ------------------------------------------------------------- settings

    @Test
    fun `settings round-trip, including options, location and the last backup`() {
        val s = VaultSettings(
            location = BackupLocation(LocationKind.SAF_TREE, "content://tree/x", "Download"),
            options = BackupOptions(keep = 5, includeSensitive = true, scopes = setOf(BackupScope.HOME, BackupScope.OPT)),
            encrypt = true,
            last = LastBackup("n.psbak", 5, 99, true, "zstd", "Download", false),
        )
        assertEquals(s, VaultSettingsCodec.decode(VaultSettingsCodec.encode(s)))
    }

    @Test
    fun `a damaged or absent store means defaults, never invented settings`() {
        assertEquals(VaultSettings(), VaultSettingsCodec.decode(null))
        assertEquals(VaultSettings(), VaultSettingsCodec.decode("{not json"))
        assertEquals(VaultSettings(), VaultSettingsCodec.decode("[]"))
        // A newer writer's extra keys must not break this reader.
        val json = VaultSettingsCodec.encode(VaultSettings(encrypt = true)).dropLast(1) + ",\"future\":1}"
        assertTrue(VaultSettingsCodec.decode(json).encrypt)
    }

    @Test
    fun `the passphrase has nowhere to be stored`() {
        // Encrypting is only a PREFERENCE; there is no field a passphrase could land in.
        val json = VaultSettingsCodec.encode(VaultSettings(encrypt = true))
        assertFalse(json.contains("pass", ignoreCase = true))
        assertTrue(json.contains("\"encrypt\":true"))
    }

    @Test
    fun `the backup settings store is not part of what a backup carries`() {
        // SAF grants are device-specific; restoring them onto another phone would point at nothing.
        assertFalse("sync_vault" in AppSettingsFiles.NAMES)
    }

    // ---------------------------------------------------------------- display

    @Test
    fun `sizes read the way a person says them`() {
        assertEquals("0 B", VaultState.formatSize(0))
        assertEquals("999 B", VaultState.formatSize(999))
        assertEquals("1 KB", VaultState.formatSize(1024))
        assertEquals("1.5 MB", VaultState.formatSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", VaultState.formatSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `the last-backup line states facts and never a promise`() {
        val now = 10_000_000L
        val line = lastBackupText(LastBackup("n", now - 2 * 3_600_000L, 412L * 1024 * 1024, true, "zstd", "Download", true), now)
        assertEquals("Last backup 2h ago · 412.0 MB · encrypted", line)
        val plain = lastBackupText(LastBackup("n", now, 10, false, "gzip", "App storage", false), now)
        assertTrue(plain.endsWith("not encrypted"))
        listOf("safe", "protected", "backed up", "complete").forEach { assertFalse(line.lowercase().contains(it)) }
    }
}
