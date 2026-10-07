package app.pocketshell.widget.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The responsive contract's thresholds — the same geometry git derived. */
class ServersLayoutTest {

    @Test
    fun `phone card is COMPACT`() {
        assertEquals(ServersLayout.COMPACT, ServersLayout.from(widthDp = 340f, heightDp = 180f))
    }

    @Test
    fun `large card is ROOMY at the 420x200 threshold`() {
        assertEquals(ServersLayout.ROOMY, ServersLayout.from(widthDp = 420f, heightDp = 200f))
    }

    @Test
    fun `wide but short stays COMPACT`() {
        assertEquals(ServersLayout.COMPACT, ServersLayout.from(widthDp = 500f, heightDp = 150f))
    }

    @Test
    fun `tall page is FULL`() {
        assertEquals(ServersLayout.FULL, ServersLayout.from(widthDp = 440f, heightDp = 480f))
    }

    @Test
    fun `only FULL is the monitor`() {
        assertTrue(ServersLayout.FULL.isMonitor)
        assertFalse(ServersLayout.ROOMY.isMonitor)
        assertFalse(ServersLayout.COMPACT.isMonitor)
    }

    @Test
    fun `two pane needs a wide page`() {
        assertTrue(ServersLayout.twoPane(600f))
        assertFalse(ServersLayout.twoPane(412f))
    }

    @Test
    fun `stop action and search exist only in FULL`() {
        assertTrue(ServersLayout.FULL.showsStopAction)
        assertTrue(ServersLayout.FULL.showsSearch)
        assertFalse(ServersLayout.ROOMY.showsStopAction)
        assertFalse(ServersLayout.COMPACT.showsStopAction)
        assertFalse(ServersLayout.ROOMY.showsSearch)
    }
}
