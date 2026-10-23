package app.pocketshell.widget.servers

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract facts a catalog install depends on, and the pid-set gate's
 * read — exercised against a fake /proc so the test never touches the
 * real one.
 */
class ServersAppContractTest {

    @Test
    fun `the plugin answers to the catalog id and version`() {
        val plugin = ServersPlugin()
        assertEquals("servers", plugin.pluginId)
        assertEquals("2.2.0", plugin.pluginVersion)
    }

    @Test
    fun `the application id matches the catalog entry it replaces`() {
        assertEquals("servers", ServersApp.SERVERS_PLUGIN_ID)
        assertEquals("servers", ServersApp.spec.id)
        assertEquals("Processes", ServersApp.spec.name)
        // The id never changes with the name: Home layouts and the catalog refer to it.
        assertEquals("servers", ServersApp.spec.id)
        // The summary states the monitor's facts, never a promise of data.
        assertTrue(ServersApp.spec.summary.contains("dev servers") && ServersApp.spec.summary.contains("Gradle"))
    }

    @Test
    fun `the gate reads only numeric proc dirs`() {
        val fake = File.createTempFile("proc", "dir").apply { delete(); mkdirs() }
        try {
            listOf("12", "345", "6789").forEach { File(fake, it).mkdirs() }
            // Non-numeric entries a real /proc also carries.
            File(fake, "uptime").writeText("1.0 1.0")
            File(fake, "acpi").mkdirs()
            File(fake, "12x").mkdirs()

            val gate = ServersProbeWrapper(procRoot = fake.path)
            assertEquals(setOf(12, 345, 6789), gate.peekPidSet())
            // A fresh probe has no cached pid set: the first tick always scans.
            assertTrue(gate.shouldFullScan(gate.peekPidSet()))
        } finally {
            fake.deleteRecursively()
        }
    }

    @Test
    fun `the system facts reader degrades alone per file`() {
        val fake = File.createTempFile("proc", "dir").apply { delete(); mkdirs() }
        try {
            File(fake, "loadavg").writeText("0.10 0.20 0.30 1/100 999\n")
            // meminfo and uptime missing on purpose.
            val strip = SystemFactsReader(procRoot = fake.path, rootfsDir = null).read()
            assertEquals(0.10f, strip.load?.one!!, 0.001f)
            assertEquals(null, strip.mem)
            assertEquals(null, strip.uptimeSec)
            assertEquals(null, strip.diskUsedFrac)
        } finally {
            fake.deleteRecursively()
        }
    }

    @Test
    fun `the disk fraction is the rootfs usage`() {
        val dir = File.createTempFile("rootfs", "dir").apply { delete(); mkdirs() }
        try {
            val frac = SystemFactsReader(procRoot = "/nonexistent", rootfsDir = dir)
                .read().diskUsedFrac
            assertTrue(frac != null && frac in 0f..1f)
        } finally {
            dir.deleteRecursively()
        }
    }
}
