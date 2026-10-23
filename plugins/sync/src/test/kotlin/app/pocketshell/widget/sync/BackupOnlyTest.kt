package app.pocketshell.widget.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.2.0: the application is Backup & Restore only; the rsync / rclone profiles are switched off, not deleted. */
class BackupOnlyTest {

    private val dir: File = listOf("src/main/kotlin/app/pocketshell/widget/sync", "plugins/sync/src/main/kotlin/app/pocketshell/widget/sync")
        .map(::File).first { it.isDirectory }

    @Test
    fun `the identity is Backup and Restore and the registry id is unchanged`() {
        assertEquals("sync", SyncApp.SYNC_ID)
        assertEquals("sync", SyncPlugin().pluginId)
        assertEquals("Backup & Restore", SyncApp.spec.name)
        assertEquals("3.2.0", SyncPlugin().pluginVersion)
    }

    @Test
    fun `the studio offers backup and restore only`() {
        assertFalse(SyncApp.PROFILES_ENABLED)
        // (StudioTab carries icons, which a JVM test cannot load - so the rule is pinned in the source)
        val studio = File(dir, "SyncStudio.kt").readText()
        assertTrue(studio.contains("entries.filter { it != SYNC || SyncApp.PROFILES_ENABLED }"))
        assertFalse(studio.contains("StudioTab.entries.forEach"))
    }

    @Test
    fun `nothing probes or scans while the profiles are off`() {
        val src = File(dir, "SyncApp.kt").readText()
        // the probe loop, the rescan-on-edit effect and the card's profile list are all behind the flag
        assertTrue(src.contains("if (!PROFILES_ENABLED) {"))
        assertTrue(src.contains("if (!PROFILES_ENABLED) return@LaunchedEffect"))
        assertTrue(src.contains("else if (PROFILES_ENABLED) {"))
        val clockOnly = src.substringAfter("if (!PROFILES_ENABLED) {").substringBefore("return@LaunchedEffect")
        assertFalse("the disabled branch must not scan", clockOnly.contains("runScan()"))
        assertFalse(clockOnly.contains("state.probe"))
    }

    @Test
    fun `the studio title and the card carry the new name`() {
        val studio = File(dir, "SyncStudio.kt").readText()
        assertTrue(studio.contains("SyncApp.spec.name"))
        assertTrue(studio.contains("fun BackupCard("))
    }
}
