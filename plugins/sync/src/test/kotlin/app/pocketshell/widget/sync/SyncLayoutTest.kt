package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8.4 — the responsive contract (the same geometry and thresholds as the
 * reference ServersLayout/GitLayout: the card dimensions are identical, so
 * the device-derived thresholds carry over).
 */
class SyncLayoutTest {

    @Test
    fun `phone-sized cards are COMPACT - one-line rows, capped`() {
        val layout = SyncLayout.from(widthDp = 360f, heightDp = 172f)
        assertEquals(SyncLayout.COMPACT, layout)
        assertFalse(layout.showsSubline)
        assertFalse(layout.showsStatusHeader)
    }

    @Test
    fun `tablet-sized cards are ROOMY - sublines, header, scrolling`() {
        val layout = SyncLayout.from(widthDp = 720f, heightDp = 208f)
        assertEquals(SyncLayout.ROOMY, layout)
        assertTrue(layout.showsSubline)
        assertTrue(layout.showsStatusHeader)
    }

    @Test
    fun `either dimension short falls back to COMPACT`() {
        assertEquals(SyncLayout.COMPACT, SyncLayout.from(widthDp = 720f, heightDp = 150f))
        assertEquals(SyncLayout.COMPACT, SyncLayout.from(widthDp = 300f, heightDp = 240f))
    }

    @Test
    fun `the thresholds are the shared card geometry`() {
        assertEquals(420f, SyncLayout.ROOMY_MIN_WIDTH_DP, 0f)
        assertEquals(200f, SyncLayout.ROOMY_MIN_HEIGHT_DP, 0f)
    }

    @Test
    fun `a maximized portrait phone page is the studio even under 420dp wide`() {
        assertEquals(SyncLayout.FULL, SyncLayout.from(widthDp = 411f, heightDp = 800f))
        assertEquals(SyncLayout.FULL, SyncLayout.from(widthDp = 320f, heightDp = 400f))
        assertEquals(SyncLayout.COMPACT, SyncLayout.from(widthDp = 319.9f, heightDp = 800f))
        assertTrue(SyncLayout.FULL.isStudio)
        assertFalse(SyncLayout.ROOMY.isStudio)
        assertTrue(SyncLayout.FULL.showsSubline && SyncLayout.FULL.showsStatusHeader)
    }

    @Test
    fun `a maximized landscape phone page is the studio, a wide card is not`() {
        assertEquals(SyncLayout.FULL, SyncLayout.from(widthDp = 850f, heightDp = 380f))
        assertEquals(SyncLayout.ROOMY, SyncLayout.from(widthDp = 680f, heightDp = 251f))
    }

    @Test
    fun `the side rail needs a wide page`() {
        assertFalse(SyncLayout.usesSideRail(411f))
        assertTrue(SyncLayout.usesSideRail(600f))
    }
}
