package app.pocketshell.widget.network

import app.pocketshell.widget.WidgetNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCompatTest {

    @Test
    fun `an older host degrades instead of crashing`() {
        for (e in listOf<Throwable>(NoClassDefFoundError("PluginJobs"), NoSuchMethodError("begin"), AbstractMethodError("openTerminalWith"), IllegalStateException("x"))) {
            assertEquals("fallback for ${e.javaClass.simpleName}", -1L, HostCompat.safely(-1L) { throw e })
        }
        assertEquals(7L, HostCompat.safely(-1L) { 7L })
    }

    @Test
    fun `a real failure such as running out of memory is not swallowed`() {
        val raised = try { HostCompat.safely(0) { throw OutOfMemoryError("oom") }; false } catch (_: OutOfMemoryError) { true }
        assertTrue(raised)
    }

    private class OldNav : WidgetNav {
        var opened = 0
        override fun openTerminal() { opened++ }
        override fun openLinuxShell() {}
        override fun openDiagnostics() {}
        override fun openGuestFiles() {}
        override fun openWidgetSettings() {}
        override fun openCompanion(url: String) {}
        override fun openTerminalWith(text: String): Unit = throw NoSuchMethodError("openTerminalWith")
    }

    private class NewNav : WidgetNav {
        val typed = ArrayList<String>()
        override fun openTerminal() {}
        override fun openLinuxShell() {}
        override fun openDiagnostics() {}
        override fun openGuestFiles() {}
        override fun openWidgetSettings() {}
        override fun openCompanion(url: String) {}
        override fun openTerminalWith(text: String) { typed += text }
    }

    @Test
    fun `typing reaches a new host and falls back to just opening the terminal on an old one`() {
        val new = NewNav()
        assertTrue(HostCompat.typeInTerminal(new, "ssh pi"))
        assertEquals(listOf("ssh pi"), new.typed)

        val old = OldNav()
        assertFalse(HostCompat.typeInTerminal(old, "ssh pi"))
        assertEquals("the terminal is still opened", 1, old.opened)
    }
}
