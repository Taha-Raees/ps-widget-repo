package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The on-screen gate: pure geometry, plus a pin that the tick loop honours it. */
class VisibilityTest {

    @Test
    fun `a card inside the window is visible, a parked pager page is not, a half-swiped one still counts`() {
        assertTrue(Visibility.isOnScreen(0f, 100f, 400f, 300f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(1080f, 100f, 1480f, 300f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(-1080f, 100f, -680f, 300f, 1080f, 2400f))
        assertEquals(0.5f, Visibility.visibleFraction(540f, 100f, 1620f, 300f, 1080f, 2400f), 0.01f)
        assertTrue(Visibility.isOnScreen(540f, 100f, 1620f, 300f, 1080f, 2400f))
    }

    @Test
    fun `a card scrolled out of the window stops counting`() {
        assertFalse(Visibility.isOnScreen(0f, -600f, 400f, -10f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(0f, 2500f, 400f, 2700f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(0f, -190f, 400f, 10f, 1080f, 2400f))
        assertEquals(0f, Visibility.visibleFraction(10f, 10f, 10f, 50f, 1080f, 2400f), 0f)
    }

    @Test
    fun `the periodic loop runs only while the card is on screen`() {
        val f = listOf("src/main/kotlin/app/pocketshell/widget/sync/SyncApp.kt", "plugins/sync/src/main/kotlin/app/pocketshell/widget/sync/SyncApp.kt")
            .map { java.io.File(it) }.firstOrNull { it.isFile }
        assumeTrue("source not found on this runner", f != null)
        val app = f!!.readText()
        assertTrue(app.contains("snapshotFlow { onScreen.value }"))
        assertTrue(app.contains("trackOnScreen(onScreen)"))
        assertTrue(app.indexOf("snapshotFlow { onScreen.value }") < app.indexOf("delay("))
    }
}
