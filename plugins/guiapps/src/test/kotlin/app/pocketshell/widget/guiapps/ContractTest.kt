package app.pocketshell.widget.guiapps

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

    private val dir: File = listOf("src/main/kotlin/app/pocketshell/widget/guiapps", "plugins/guiapps/src/main/kotlin/app/pocketshell/widget/guiapps")
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
            if (name == "GuiOps.kt") continue
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
        val app = src("GuiApp.kt")
        assertFalse(app.contains("while (true)"))
        assertEquals("the app entry has exactly one delay: the settle wait", 1, Regex("delay\\(").findAll(app).count())
        assertTrue(app.contains("delay(SETTLE_MS)"))
        assertTrue("the settle comes before any read", app.indexOf("delay(SETTLE_MS)") < app.indexOf("state.reload()"))
        assertTrue(GuiApp.SETTLE_MS in 300L..2_000L)
        assertTrue(GuiApp.STALE_MS >= 60_000L)
        assertFalse(src("GuiState.kt").contains("while (true)"))
    }

    @Test
    fun `no intrinsic measurement anywhere`() {
        for ((name, text) in all) assertFalse("$name must not use IntrinsicSize", text.contains("IntrinsicSize"))
    }

    @Test
    fun `state work runs on its own scope, never a composition effect`() {
        val st = src("GuiState.kt")
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
        assertEquals("guiapps", GuiAppsPlugin().pluginId)
        assertEquals("guiapps", GuiApp.spec.id)
        assertEquals("GUI Apps", GuiApp.spec.name)
        assertTrue(Regex("^\\d+\\.\\d+\\.\\d+$").matches(GuiAppsPlugin().pluginVersion))
    }

    @Test
    fun `nothing is logged`() {
        for ((name, text) in all) assertFalse("$name must not log", text.contains("Log.") || text.contains("println("))
    }

    @Test
    fun `downloads are https only, size capped, digest checked and path confined`() {
        val ops = src("GuiOps.kt")
        assertTrue(ops.contains("startsWith(\"https://\")"))
        assertTrue(ops.contains("Assets.MAX_BYTES"))
        assertTrue("the published SHA-256 is compared", ops.contains("expectedSha"))
        assertTrue("downloads land in a .part file first", ops.contains(".part"))
        assertTrue("deleting an app folder stays inside ~/Applications", ops.contains("appsDir.canonicalPath"))
    }

    @Test
    fun `an app launch is a constant script with positional data`() {
        val model = src("GuiModel.kt")
        assertTrue(model.contains("DBUS_SCRIPT"))
        // user/catalog data never gets concatenated into the sh -c text
        assertFalse(model.substringAfter("private const val DBUS_SCRIPT").substringBefore("\n").contains("\${"))
    }
}
