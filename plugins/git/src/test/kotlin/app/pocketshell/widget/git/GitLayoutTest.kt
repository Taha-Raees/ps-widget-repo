package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Git application's responsive contract: the information hierarchy
 * adapts to the available card space, it is never scaled. Thresholds are
 * the device-derived INNER dimensions the Servers application pinned (the
 * card geometry is identical, so they carry over unchanged). The M9
 * redesign keeps the flag for SUBLINE detail (authors, times, paths under
 * rows) — the navigation structure itself is identical on every width.
 */
class GitLayoutTest {

    @Test
    fun `a phone card is COMPACT - no sublines`() {
        // Phone content width ≈ 320-390dp; INNER card height ≈ 240dp·scale
        // minus the 32dp chrome padding.
        assertEquals(GitLayout.COMPACT, GitLayout.from(320f, 208f))
        assertEquals(GitLayout.COMPACT, GitLayout.from(390f, 172f)) // COMPACT card size
        val layout = GitLayout.from(320f, 240f)
        assertFalse(layout.showsSublines)
    }

    @Test
    fun `a tablet card is ROOMY - sublines on rows that earn them`() {
        // Tablet content width up to 720−40dp; INNER height 240dp·1.0−32.
        assertEquals(GitLayout.ROOMY, GitLayout.from(680f, 208f))
        assertEquals(GitLayout.ROOMY, GitLayout.from(420f, 251f)) // LARGE card size
        val layout = GitLayout.ROOMY
        assertTrue(layout.showsSublines)
    }

    @Test
    fun `the roomy threshold requires BOTH width and height`() {
        // Wide but short (landscape phone) → COMPACT hierarchy.
        assertEquals(GitLayout.COMPACT, GitLayout.from(680f, 180f))
        assertEquals(GitLayout.COMPACT, GitLayout.from(680f, 199.9f))
        // Tall but narrow (portrait phone) → COMPACT hierarchy.
        assertEquals(GitLayout.COMPACT, GitLayout.from(390f, 300f))
    }

    @Test
    fun `the thresholds sit exactly at the documented boundaries`() {
        assertEquals(GitLayout.ROOMY, GitLayout.from(420f, 200f))
        assertEquals(GitLayout.COMPACT, GitLayout.from(419.9f, 200f))
        assertEquals(GitLayout.COMPACT, GitLayout.from(420f, 199.9f))
    }
}
