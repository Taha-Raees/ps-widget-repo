package app.pocketshell.widget.serverhub

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

    private val dir: File = listOf("src/main/kotlin/app/pocketshell/widget/serverhub", "plugins/serverhub/src/main/kotlin/app/pocketshell/widget/serverhub")
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
            if (name == "HubOps.kt") continue
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
        val app = src("HubApp.kt")
        assertFalse(app.contains("while (true)"))
        assertEquals("the app entry has exactly one delay: the settle wait", 1, Regex("delay\\(").findAll(app).count())
        assertTrue(app.contains("delay(SETTLE_MS)"))
        assertTrue("the settle comes before any read", app.indexOf("delay(SETTLE_MS)") < app.indexOf("state.reload()"))
        assertTrue(HubApp.SETTLE_MS in 300L..2_000L)
        assertTrue(HubApp.STALE_MS >= 60_000L)
        assertFalse(src("HubState.kt").contains("while (true)"))
    }

    @Test
    fun `no intrinsic measurement anywhere`() {
        for ((name, text) in all) assertFalse("$name must not use IntrinsicSize", text.contains("IntrinsicSize"))
    }

    @Test
    fun `state work runs on its own scope, never a composition effect`() {
        val st = src("HubState.kt")
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
        assertEquals("serverhub", ServerHubPlugin().pluginId)
        assertEquals("serverhub", HubApp.spec.id)
        assertEquals("Server Hub", HubApp.spec.name)
        assertTrue(Regex("^\\d+\\.\\d+\\.\\d+$").matches(ServerHubPlugin().pluginVersion))
    }

    @Test
    fun `nothing is logged`() {
        for ((name, text) in all) assertFalse("$name must not log", text.contains("Log.") || text.contains("println("))
    }

    @Test
    fun `a service runs from a parsed argv, never from shell text`() {
        val st = src("HubState.kt")
        assertTrue(st.contains("Services.argv("))
        assertFalse("the state never builds a shell string", st.contains("\"sh\", \"-c\"") || st.contains("sh -c"))
        assertTrue("the log is capped before every start", st.contains("files.prepareLog("))
        assertTrue("crash loops are bounded by the policy", st.contains("RestartPolicy.decide("))
    }

    @Test
    fun `every loop in the state has a deadline or an ownership check`() {
        val st = src("HubState.kt")
        for (m in Regex("while \\(.*\\) \\{").findAll(st)) {
            val cond = m.value
            assertTrue("unbounded loop: $cond", cond.contains("deadline") || cond.contains("===") || cond.contains("waited <"))
        }
    }

    @Test
    fun `the only free-running loop is the opt-in log follow`() {
        val screens = src("HubScreens.kt")
        assertEquals(1, Regex("while \\(").findAll(screens).count())
        assertTrue(screens.contains("while (follow)"))
    }

    @Test
    fun `the lan link is only offered for services marked for the wifi`() {
        val screens = src("HubScreens.kt")
        assertTrue(screens.contains("if (lan != null && s.wifi)"))
    }
}
