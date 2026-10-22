package app.pocketshell.widget.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the rules that cost Git and Sync real bugs. They read the plugin's own sources,
 * so a future edit that breaks a rule fails here instead of on a user's phone.
 */
class NetworkContractTest {

    private val dir: File = listOf("src/main/kotlin/app/pocketshell/widget/network", "plugins/network/src/main/kotlin/app/pocketshell/widget/network")
        .map(::File).first { it.isDirectory }

    /** Source with comments removed: a rule is about what the code DOES, not what its docs say. */
    private fun code(text: String): String = text.replace(Regex("(?s)/\\*.*?\\*/"), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }

    private fun src(name: String) = code(File(dir, name).readText())
    private val all: Map<String, String> = dir.listFiles { f -> f.name.endsWith(".kt") }!!.associate { it.name to code(it.readText()) }

    @Test
    fun `only NetworkOps can run a guest command or open a socket`() {
        val execMarkers = listOf("ProcessBuilderGuestCommandRunner", "GuestLaunch.spec", "ProcessBuilder(", "Runtime.getRuntime", "Socket(", "DatagramSocket(", "HttpURLConnection", "openConnection")
        for ((name, text) in all) {
            if (name in setOf("NetworkOps.kt", "GuestLaunch.kt")) continue
            for (m in execMarkers) assertFalse("$name must not use $m — exec and sockets live in NetworkOps.kt", text.contains(m))
        }
    }

    @Test
    fun `the mini card is a pure view - it cannot touch the network or exec`() {
        val mini = src("NetworkMini.kt")
        for (m in listOf("NetFacts", "GuestExec", "NetFiles", "state.", "NetworkState")) assertFalse("NetworkMini.kt must not reference $m", mini.contains(m))
    }

    @Test
    fun `nothing polls - the only waits are the settle delay and bounded detectors`() {
        val app = src("NetworkApp.kt")
        assertFalse(app.contains("while (true)"))
        assertEquals("the app entry has exactly one delay: the settle wait", 1, Regex("delay\\(").findAll(app).count())
        assertTrue(app.contains("delay(SETTLE_MS)"))
        assertTrue("the settle comes before any read", app.indexOf("delay(SETTLE_MS)") < app.indexOf("state.reloadLocal()"))
        assertTrue(NetworkApp.SETTLE_MS in 300L..2_000L)
        assertTrue(NetworkApp.STALE_MS >= 60_000L)
        // state never loops forever: every loop in it has a deadline or an ownership check
        assertFalse(src("NetworkState.kt").contains("while (true)"))
    }

    @Test
    fun `no intrinsic measurement anywhere`() {
        for ((name, text) in all) assertFalse("$name must not use IntrinsicSize", text.contains("IntrinsicSize"))
    }

    @Test
    fun `long-lived processes always go through the pid-file wrapper`() {
        val ops = src("NetworkOps.kt")
        assertTrue(ops.contains("Tunnels.withPidFile("))
        val start = ops.substringAfter("fun start(").substringBefore("// ----")
        assertTrue(start.contains("withPidFile"))
    }

    @Test
    fun `state work runs on its own scope, never a composition effect`() {
        val st = src("NetworkState.kt")
        assertTrue(st.contains("SupervisorJob()"))
        assertFalse("state must not depend on a LaunchedEffect", st.contains("LaunchedEffect"))
        // busy flags are cleared in a finally
        assertTrue(st.contains("finally"))
    }

    @Test
    fun `every action is single-flight`() {
        val st = src("NetworkState.kt")
        for (g in listOf("loadGuard.compareAndSet", "reachGuard.compareAndSet", "listenGuard.compareAndSet")) assertTrue(g, st.contains(g))
        assertTrue(st.contains("if (checking) return"))
        assertTrue(st.contains("live.containsKey(t.id)"))
    }

    @Test
    fun `the plugin identity is stable`() {
        assertEquals("network", NetworkPlugin().pluginId)
        assertEquals("network", NetworkApp.spec.id)
        assertEquals("Network", NetworkApp.spec.name)
        assertTrue(Regex("^\\d+\\.\\d+\\.\\d+$").matches(NetworkPlugin().pluginVersion))
    }

    @Test
    fun `secrets are never printed or logged`() {
        for ((name, text) in all) {
            assertFalse("$name must not log", text.contains("Log.") || text.contains("println("))
        }
    }
}
