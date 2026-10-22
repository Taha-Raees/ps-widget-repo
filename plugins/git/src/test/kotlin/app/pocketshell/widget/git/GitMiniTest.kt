package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Home-lag fixes, pinned: the on-screen geometry, the "scan only when owed"
 * policy (no periodic loop, no re-scan on re-entry), and a mini card that is
 * just the summary.
 */
class GitMiniTest {

    // ------------------------------------------------------------ visibility

    @Test
    fun `a card fully inside the window is visible, a parked pager page is not, a half-swiped one still counts`() {
        assertEquals(1f, Visibility.visibleFraction(0f, 100f, 400f, 300f, 1080f, 2400f), 0.0001f)
        assertTrue(Visibility.isOnScreen(0f, 100f, 400f, 300f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(1080f, 100f, 1480f, 300f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(-1080f, 100f, -680f, 300f, 1080f, 2400f))
        assertEquals(0.5f, Visibility.visibleFraction(540f, 100f, 1080f + 540f, 300f, 1080f, 2400f), 0.01f)
        assertTrue(Visibility.isOnScreen(540f, 100f, 1620f, 300f, 1080f, 2400f))
    }

    @Test
    fun `a card scrolled out of the window stops counting`() {
        assertFalse(Visibility.isOnScreen(0f, -600f, 400f, -10f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(0f, 2500f, 400f, 2700f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(0f, -190f, 400f, 10f, 1080f, 2400f))
        assertEquals(0f, Visibility.visibleFraction(10f, 10f, 10f, 50f, 1080f, 2400f), 0f)
        assertEquals(0f, Visibility.visibleFraction(0f, 0f, 100f, 100f, 0f, 2400f), 0f)
    }

    // -------------------------------------------------- the lag fixes stay put

    private fun src(name: String): String {
        val f = listOf("src/main/kotlin/app/pocketshell/widget/git/$name", "plugins/git/src/main/kotlin/app/pocketshell/widget/git/$name")
            .map { java.io.File(it) }.firstOrNull { it.isFile }
        assumeTrue("source not found on this runner: $name", f != null)
        return f!!.readText()
    }

    /** The probe effect: from its LaunchedEffect to the manual-refresh comment. */
    private fun probeEffect(): String {
        val app = src("GitApp.kt")
        return app.substring(app.indexOf("LaunchedEffect(context.runtimeState)"), app.indexOf("// The manual refresh control"))
    }

    @Test
    fun `there is no periodic scan loop - a scan runs only when it is owed`() {
        val effect = probeEffect()
        assertFalse("no endless loop may drive the probe", effect.contains("while (true)"))
        assertFalse("no heartbeat delay either", effect.contains("delay("))
        assertTrue("the card must be on screen first", effect.contains("snapshotFlow { onScreen.value to state.studio }"))
        // Owed = no data yet, or the maximized page opening on stale data.
        assertTrue(effect.contains("state.probe.shouldFullScan(now)"))
        assertTrue(effect.contains("GitProbe.STUDIO_STALE_MS"))
        assertTrue(GitProbe.STUDIO_STALE_MS >= 5 * 60_000L)
        assertTrue(GitProbe.AUTO_RESCAN_MS >= 45_000L)
    }

    @Test
    fun `a manual refresh is claimed once so returning to the card cannot rescan`() {
        val app = src("GitApp.kt")
        val effect = app.substring(app.indexOf("LaunchedEffect(state.refreshTick)"), app.indexOf("// THE mutation path"))
        assertTrue("the refresh effect must claim its tick", effect.contains("state.refreshRunOnce.claim(state.refreshTick)"))
        assertTrue(effect.indexOf("claim(") < effect.indexOf("state.rescan()"))
        // And the claim object behaves: one tick, one run.
        val once = RunOnce()
        assertTrue(once.claim(3))
        assertFalse("re-entering the card with the same tick must not scan again", once.claim(3))
        assertTrue(once.claim(4))
    }

    @Test
    fun `every scan goes through rescan so the loading indicator can never be skipped`() {
        val app = src("GitApp.kt")
        val outside = app.substringBefore("suspend fun rescan()")
        assertFalse("no scan may bypass state.rescan()", outside.contains("scanToUi(withContext"))
        assertTrue(app.contains("scanning = true"))
        assertTrue("the flag is cleared even on cancellation", app.substringAfter("suspend fun rescan()").substringBefore("}\n    }").contains("finally"))
    }

    @Test
    fun `the probe runs at low priority`() {
        assertTrue(GitProbe.PROBE_SCRIPT.lines().first().contains("renice -n 15"))
    }

    // ------------------------------------------------------------- the card

    @Test
    fun `the mini card is only the summary - no repository list, no folders`() {
        val mini = src("GitMini.kt")
        assertTrue("it shows the shared header", mini.contains("GitSummaryHeader("))
        assertTrue("and the four numbers", mini.contains("GitSummaryStrip("))
        // No list machinery of any kind.
        listOf("RepoSnapshot.name", "forEach", "LazyColumn", "verticalScroll", "BackHeader", "DashboardPane", "chunked").forEach {
            assertFalse("the mini card must not render repositories ($it)", mini.contains(it))
        }
        // The maximized page uses the SAME header and strip, so the two always agree.
        val studio = src("GitStudio.kt")
        assertTrue(studio.contains("GitSummaryHeader("))
        assertTrue(studio.contains("GitSummaryStrip("))
    }

    @Test
    fun `the loading indicator shows while a scan runs`() {
        assertTrue(src("GitSummary.kt").contains("LoadingRule(active = scanning)"))
    }
}
