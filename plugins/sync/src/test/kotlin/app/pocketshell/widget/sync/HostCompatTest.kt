package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCompatTest {

    @Test
    fun `an older host degrades instead of crashing a backup`() {
        for (e in listOf<Throwable>(NoClassDefFoundError("PluginJobs"), NoSuchMethodError("begin"), IllegalStateException("x"))) {
            assertEquals(-1L, HostCompat.safely(-1L) { throw e })
        }
        assertEquals(5L, HostCompat.safely(-1L) { 5L })
    }

    @Test
    fun `real failures are not swallowed`() {
        val raised = try { HostCompat.safely(0) { throw OutOfMemoryError() }; false } catch (_: OutOfMemoryError) { true }
        assertTrue(raised)
    }

    @Test
    fun `every long operation registers a job and the job is always ended`() {
        val src = java.io.File("src/main/kotlin/app/pocketshell/widget/sync/VaultState.kt").takeIf { it.exists() }
            ?: java.io.File("plugins/sync/src/main/kotlin/app/pocketshell/widget/sync/VaultState.kt")
        val code = src.readText()
        for (label in listOf("\"Backup\"", "\"Restore\"", "\"Reading backup\"", "\"Installing packages\"", "\"Installing zstd\"")) {
            assertTrue("$label must be a host job", code.contains("launch($label"))
        }
        assertTrue("the job is ended in a finally", code.substringAfter("private fun launch(").substringBefore("fun update(").contains("finally"))
    }
}
