package app.pocketshell.widget.guiapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KitTest {

    @Test fun statParsingSurvivesAwkwardCommNames() {
        assertEquals(42 to 7, ProcTree.parseStat("42 (node) S 7 42 42 0 -1"))
        assertEquals(43 to 42, ProcTree.parseStat("43 (my (weird) name) S 42 43 43 0 -1"))
        assertNull(ProcTree.parseStat("garbage"))
        assertNull(ProcTree.parseStat("x (a) S 1"))
    }

    @Test fun descendantsAreCollectedChildrenFirst() {
        val table = mapOf(10 to 1, 11 to 10, 12 to 10, 13 to 11, 99 to 1)
        assertEquals(listOf(11, 12, 13), ProcTree.descendants(10, table))
        assertTrue(ProcTree.descendants(99, table).isEmpty())
        // a cycle in a corrupt table must not loop forever
        assertEquals(listOf(2), ProcTree.descendants(1, mapOf(2 to 1, 1 to 2)))
    }

    @Test fun jobWrapperIsConstantTextWithPositionalArguments() {
        val plain = GuestExec.wrap("/tmp/j/a.pid", null, listOf("sleep", "5"))
        assertEquals(listOf("sh", "-c"), plain.take(2))
        assertEquals(listOf("job", "/tmp/j/a.pid", "sleep", "5"), plain.drop(3))
        assertEquals("echo \$\$ > \"\$1\"; shift 1; exec \"\$@\"", plain[2])

        val logged = GuestExec.wrap("/tmp/j/a.pid", "/root/a.log", listOf("echo", "\$(reboot)"))
        // user data is argv, never part of the script text (index 2)
        assertFalse(logged[2].contains("reboot"))
        assertFalse(logged[2].contains("/root/a.log"))
        assertEquals("\$(reboot)", logged.last())
        assertTrue(logged[2].contains("exec >>\"\$2\" 2>&1"))

        val full = GuestExec.wrap("/tmp/j/a.pid", "/root/a.log", listOf("srv", "--port", "80"), cwd = "/root/my site")
        assertEquals(listOf("job", "/tmp/j/a.pid", "/root/a.log", "/root/my site", "srv", "--port", "80"), full.drop(3))
        assertFalse("the folder is a positional argument, not script text", full[2].contains("my site"))
        assertTrue(full[2].contains("cd \"\$3\" || exit 126"))
        assertTrue(full[2].contains("shift 3; exec"))

        val cwdOnly = GuestExec.wrap("/tmp/j/a.pid", null, listOf("srv"), cwd = "/x")
        assertTrue(cwdOnly[2].contains("cd \"\$2\""))
        assertEquals(listOf("job", "/tmp/j/a.pid", "/x", "srv"), cwdOnly.drop(3))
    }

    @Test fun envNamesAreChecked() {
        assertEquals(listOf("env", "A=1", "run"), GuestExec.withEnv(mapOf("A" to "1"), listOf("run")))
        assertEquals(listOf("run"), GuestExec.withEnv(emptyMap(), listOf("run")))
        assertTrue(runCatching { GuestExec.withEnv(mapOf("a b" to "1"), listOf("run")) }.isFailure)
        assertTrue(runCatching { GuestExec.withEnv(mapOf("A=B" to "1"), listOf("run")) }.isFailure)
    }

    @Test fun layoutIsDecidedByHeightFirst() {
        assertEquals(Layout.FULL, Layout.from(410f, 800f))
        assertEquals(Layout.CARD, Layout.from(410f, 260f))
        assertEquals(Layout.CARD, Layout.from(300f, 800f))
        assertEquals(Layout.FULL, Layout.from(800f, 350f))
        assertTrue(Layout.usesSideRail(600f)); assertFalse(Layout.usesSideRail(410f))
    }

    @Test fun visibilityNeedsAQuarterOfTheCard() {
        assertTrue(Visibility.isOnScreen(0f, 0f, 100f, 100f, 1000f, 1000f))
        assertTrue(Visibility.isOnScreen(0f, 900f, 100f, 1100f, 1000f, 1000f))
        assertFalse(Visibility.isOnScreen(0f, 990f, 100f, 1190f, 1000f, 1000f))
        assertFalse(Visibility.isOnScreen(2000f, 0f, 2100f, 100f, 1000f, 1000f))
    }
}
