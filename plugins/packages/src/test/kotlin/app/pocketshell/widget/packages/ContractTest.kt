package app.pocketshell.widget.packages

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the rules that cost Git and Sync real bugs. They read the plugin's own sources,
 * so a future edit that breaks a rule fails here instead of on a user's phone.
 */
class ContractTest {

    private val dir: File = listOf("src/main/kotlin/app/pocketshell/widget/packages", "plugins/packages/src/main/kotlin/app/pocketshell/widget/packages")
        .map(::File).first { it.isDirectory }

    /** Source with comments removed: a rule is about what the code DOES, not what its docs say. */
    private fun code(text: String): String = text.replace(Regex("(?s)/\\*.*?\\*/"), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    private fun src(name: String) = code(File(dir, name).readText())
    private val all: Map<String, String> = dir.listFiles { f -> f.name.endsWith(".kt") }!!.associate { it.name to code(it.readText()) }

    @Test
    fun `only the guest layer can run a process`() {
        val execMarkers = listOf("ProcessBuilderGuestCommandRunner", "GuestLaunch.spec", "ProcessBuilder(", "Runtime.getRuntime")
        for ((name, text) in all) {
            if (name in setOf("Guest.kt", "GuestLaunch.kt")) continue
            for (m in execMarkers) assertFalse("$name must not use $m - exec lives in Guest.kt", text.contains(m))
        }
    }

    @Test
    fun `only the ops file opens sockets`() {
        val socketMarkers = listOf("Socket(", "DatagramSocket(", "HttpURLConnection", "openConnection")
        for ((name, text) in all) {
            if (name == "PkgOps.kt") continue
            for (m in socketMarkers) assertFalse("$name must not use $m", text.contains(m))
        }
    }

    @Test
    fun `the shared UI kit is pure presentation`() {
        val kit = src("Kit.kt")
        for (m in listOf("File(", "GuestExec", "ProcessBuilder", "Socket", "java.net")) assertFalse("Kit.kt must not reference $m", kit.contains(m))
    }

    @Test
    fun `nothing polls - the only wait in the entry is the settle delay`() {
        val app = src("PkgApp.kt")
        assertFalse(app.contains("while (true)"))
        assertEquals("the app entry has exactly one delay: the settle wait", 1, Regex("delay\\(").findAll(app).count())
        assertTrue(app.contains("delay(SETTLE_MS)"))
        assertTrue("the settle comes before any read", app.indexOf("delay(SETTLE_MS)") < app.indexOf("state.reload()"))
        assertTrue(PkgApp.SETTLE_MS in 300L..2_000L)
        assertTrue(PkgApp.STALE_MS >= 60_000L)
        assertFalse(src("PkgState.kt").contains("while (true)"))
    }

    @Test
    fun `no intrinsic measurement anywhere`() {
        for ((name, text) in all) assertFalse("$name must not use IntrinsicSize", text.contains("IntrinsicSize"))
    }

    @Test
    fun `state work runs on its own scope, never a composition effect`() {
        val st = src("PkgState.kt")
        assertTrue(st.contains("SupervisorJob()"))
        assertFalse("state must not depend on a LaunchedEffect", st.contains("LaunchedEffect"))
        assertTrue("busy flags are cleared in a finally", st.contains("finally"))
    }

    @Test
    fun `host features are guarded against an older app`() {
        val compat = src("HostCompat.kt")
        assertTrue(compat.contains("catch (_: LinkageError)"))
        // PluginJobs and the typed-terminal call are only ever reached through HostCompat
        for ((name, text) in all) {
            if (name == "HostCompat.kt") continue
            assertFalse("$name must go through HostCompat for PluginJobs", text.contains("PluginJobs."))
            assertFalse("$name must go through HostCompat for openTerminalWith", text.contains("openTerminalWith"))
        }
    }

    @Test
    fun `the plugin identity is stable`() {
        assertEquals("packages", PackagesPlugin().pluginId)
        assertEquals("packages", PkgApp.spec.id)
        assertEquals("Packages", PkgApp.spec.name)
        assertTrue(Regex("^\\d+\\.\\d+\\.\\d+$").matches(PackagesPlugin().pluginVersion))
    }

    @Test
    fun `nothing is logged`() {
        for ((name, text) in all) assertFalse("$name must not log", text.contains("Log.") || text.contains("println("))
    }

    @Test
    fun `every change is simulated first and applied only from the confirmed plan`() {
        val st = src("PkgState.kt")
        // the only non-simulated change command is the one in applyPending
        assertEquals(1, Regex("simulate = false").findAll(st).count())
        val apply = st.substringAfter("fun applyPending").substringBefore("fun install(")
        assertTrue(apply.contains("simulate = false"))
        assertTrue("a refused plan is never applied", apply.contains("p.refusal != null"))
        val preview = st.substringAfter("fun preview(").substringBefore("fun dismissPending")
        assertTrue(preview.contains("simulate = true"))
    }

    @Test
    fun `apk argv is built in one place from validated names`() {
        for ((name, text) in all) {
            if (name == "PkgModel.kt") continue
            assertFalse("$name must not hard-code the apk path", text.contains("/sbin/apk"))
        }
        val model = src("PkgModel.kt")
        assertTrue(model.contains("require(list.all { PkgNames.valid(it) })"))
    }

    @Test
    fun `clearing the cache deletes package files only`() {
        val ops = src("PkgOps.kt")
        val clear = ops.substringAfter("fun clearCache").substringBefore("fun facts")
        assertTrue(clear.contains(".endsWith(\".apk\")"))
        assertFalse(clear.contains("deleteRecursively"))
    }
}
