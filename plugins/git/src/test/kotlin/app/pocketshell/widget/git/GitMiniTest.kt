package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mini card's pure model, the on-screen geometry, and the pins that keep the Home-lag fix in place. */
class GitMiniTest {

    private fun status(dirty: Boolean = false, ahead: Int? = null, behind: Int? = null) = GitStatusParser.RepoStatus(
        branch = "main", detached = false, noCommits = false, upstream = "origin/main", upstreamGone = false,
        ahead = ahead, behind = behind,
        entries = if (dirty) listOf(GitStatusParser.PorcelainEntry(' ', 'M', "a.kt", null)) else emptyList(),
    )

    private fun repo(name: String, st: GitStatusParser.RepoStatus?) =
        RepoSnapshot(path = "/root/$name", name = name, status = st, error = if (st == null) "git exited with 128" else null)

    // ---------------------------------------------------------------- model

    @Test
    fun `attention comes first - unreadable, dirty, ahead or behind, then the quiet ones, each alphabetical`() {
        val repos = listOf(
            repo("zeta", status()),
            repo("alpha", status()),
            repo("ahead", status(ahead = 3)),
            repo("dirty-b", status(dirty = true)),
            repo("broken", null),
            repo("dirty-a", status(dirty = true)),
        )
        assertEquals(
            listOf("broken", "dirty-a", "dirty-b", "ahead", "alpha", "zeta"),
            MiniModel.order(repos).map { it.name },
        )
    }

    @Test
    fun `what needs attention is exactly unreadable, uncommitted, unpushed or unpulled`() {
        assertTrue(MiniModel.needsAttention(repo("a", null)))
        assertTrue(MiniModel.needsAttention(repo("a", status(dirty = true))))
        assertTrue(MiniModel.needsAttention(repo("a", status(ahead = 1))))
        assertTrue(MiniModel.needsAttention(repo("a", status(behind = 2))))
        assertFalse(MiniModel.needsAttention(repo("a", status())))
        assertFalse(MiniModel.needsAttention(repo("a", status(ahead = 0, behind = 0))))
    }

    @Test
    fun `rows that fit follow the card height, never below one nor above the repository count`() {
        assertEquals(1, MiniModel.rowsThatFit(40f, roomy = false, total = 9))
        assertEquals(9, MiniModel.rowsThatFit(2000f, roomy = false, total = 9))
        assertEquals(2, MiniModel.rowsThatFit(2000f, roomy = false, total = 2))
        assertEquals(1, MiniModel.rowsThatFit(0f, roomy = true, total = 0))
        // The same height holds fewer two-line ROOMY rows than one-line COMPACT rows.
        assertTrue(MiniModel.rowsThatFit(260f, roomy = true, total = 9) < MiniModel.rowsThatFit(260f, roomy = false, total = 9))
        val fits = (100..500 step 25).map { MiniModel.rowsThatFit(it.toFloat(), roomy = true, total = 20) }
        assertEquals(fits.sorted(), fits)
    }

    @Test
    fun `the footer states facts and the remainder`() {
        val repos = listOf(repo("a", status(dirty = true, ahead = 2)), repo("b", status(behind = 1)), repo("c", status()))
        assertEquals("3 repos · 1 dirty · ↑2 to push · ↓1 to pull", MiniModel.footer(repos, hidden = 0))
        assertEquals("3 repos · 1 dirty · ↑2 to push · ↓1 to pull · +4 more", MiniModel.footer(repos, hidden = 4))
        assertEquals("1 repo", MiniModel.footer(listOf(repo("only", status())), hidden = 0))
        assertFalse(MiniModel.footer(repos, 0).lowercase().let { "safe" in it || "protected" in it })
    }

    // ------------------------------------------------------------ visibility

    @Test
    fun `a card fully inside the window is fully visible`() {
        assertEquals(1f, Visibility.visibleFraction(0f, 100f, 400f, 300f, 1080f, 2400f), 0.0001f)
        assertTrue(Visibility.isOnScreen(0f, 100f, 400f, 300f, 1080f, 2400f))
    }

    @Test
    fun `a pager page parked to the right is off screen, a half-swiped one still counts`() {
        // Page 3 of a pager sits one window-width to the right of the visible page.
        assertFalse(Visibility.isOnScreen(1080f, 100f, 1480f, 300f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(-1080f, 100f, -680f, 300f, 1080f, 2400f))
        // Half-swiped in: 50% visible.
        assertEquals(0.5f, Visibility.visibleFraction(540f, 100f, 1080f + 540f, 300f, 1080f, 2400f), 0.01f)
        assertTrue(Visibility.isOnScreen(540f, 100f, 1620f, 300f, 1080f, 2400f))
    }

    @Test
    fun `a card scrolled above or below the window stops counting`() {
        assertFalse(Visibility.isOnScreen(0f, -600f, 400f, -10f, 1080f, 2400f))
        assertFalse(Visibility.isOnScreen(0f, 2500f, 400f, 2700f, 1080f, 2400f))
        // Only its bottom edge peeking in: under the threshold.
        assertFalse(Visibility.isOnScreen(0f, -190f, 400f, 10f, 1080f, 2400f))
    }

    @Test
    fun `degenerate rectangles and windows are never visible`() {
        assertEquals(0f, Visibility.visibleFraction(10f, 10f, 10f, 50f, 1080f, 2400f), 0f)
        assertEquals(0f, Visibility.visibleFraction(0f, 0f, 100f, 100f, 0f, 2400f), 0f)
    }

    // -------------------------------------------------- the lag fix stays put

    private fun src(name: String): String {
        val f = listOf("src/main/kotlin/app/pocketshell/widget/git/$name", "plugins/git/src/main/kotlin/app/pocketshell/widget/git/$name")
            .map { java.io.File(it) }.firstOrNull { it.isFile }
        org.junit.Assume.assumeTrue("source not found on this runner: $name", f != null)
        return f!!.readText()
    }

    @Test
    fun `the probe tick loop runs only while the card is on screen`() {
        val app = src("GitApp.kt")
        val loop = app.substring(app.indexOf("LaunchedEffect(context.runtimeState)"), app.indexOf("// The manual refresh control"))
        assertTrue("the loop must collect the on-screen flag", loop.contains("snapshotFlow { onScreen.value }"))
        assertTrue("and the card must report its visibility", app.contains("trackOnScreen(onScreen)"))
        // The delay/tick lives INSIDE the on-screen collector, so leaving the screen cancels it.
        assertTrue(loop.indexOf("snapshotFlow { onScreen.value }") < loop.indexOf("delay(GitProbe.TICK_MS)"))
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
    fun `the probe runs at low priority and rescans no more often than every 45 seconds`() {
        assertTrue(GitProbe.PROBE_SCRIPT.lines().first().contains("renice -n 15"))
        assertTrue(GitProbe.AUTO_RESCAN_MS >= 45_000L)
    }

    @Test
    fun `the card shows a loading indicator while it scans`() {
        val mini = src("GitMini.kt")
        assertTrue(mini.contains("LoadingRule(active = scanning)"))
        assertTrue(src("GitStudio.kt").contains("LoadingRule(active = scanning)"))
    }
}
