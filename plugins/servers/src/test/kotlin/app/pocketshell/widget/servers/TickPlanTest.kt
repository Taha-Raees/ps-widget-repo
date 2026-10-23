package app.pocketshell.widget.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TickPlanTest {

    private fun inputs(
        firstScan: Boolean = false, hostGateDue: Boolean = false, pidSetChanged: Boolean = false, detailed: Boolean = false,
        sincePid: Int = 100, sinceFull: Int = 0, tick: Int = 1,
    ) = TickPlan.Inputs(firstScan, hostGateDue, pidSetChanged, detailed, sincePid, sinceFull, tick)

    @Test
    fun `the first tick always scans everything`() {
        val p = TickPlan.decide(inputs(firstScan = true))
        assertTrue(p.fullScan && p.processes && p.system)
    }

    @Test
    fun `the host gate asking for a scan means a full scan`() {
        assertTrue(TickPlan.decide(inputs(hostGateDue = true)).fullScan)
    }

    @Test
    fun `a late-binding dev server is found - full scans continue for the settling window after the pid set changes`() {
        // The process appeared (pid set changed) but did not listen yet; the host gate then says 'not due' forever.
        for (since in 0 until TickPlan.SETTLE_TICKS) {
            assertTrue("tick $since after the change must still be a full scan", TickPlan.decide(inputs(sincePid = since, hostGateDue = false)).fullScan)
        }
        assertFalse("after the window a quiet card stops paying for full scans", TickPlan.decide(inputs(sincePid = TickPlan.SETTLE_TICKS)).fullScan)
    }

    @Test
    fun `an open page rechecks periodically even when nothing seems to change`() {
        assertFalse(TickPlan.decide(inputs(detailed = true, sinceFull = TickPlan.RECHECK_TICKS_DETAILED - 1)).fullScan)
        assertTrue(TickPlan.decide(inputs(detailed = true, sinceFull = TickPlan.RECHECK_TICKS_DETAILED)).fullScan)
        // the card is slower
        assertFalse(TickPlan.decide(inputs(detailed = false, sinceFull = TickPlan.RECHECK_TICKS_DETAILED)).fullScan)
        assertTrue(TickPlan.decide(inputs(detailed = false, sinceFull = TickPlan.RECHECK_TICKS_CARD)).fullScan)
    }

    @Test
    fun `the maximized page refreshes processes AND the system strip on every tick, even without a full scan`() {
        val p = TickPlan.decide(inputs(detailed = true, sinceFull = 3))
        assertFalse(p.fullScan)
        assertTrue("processes must move every tick on the page", p.processes)
        assertTrue("the system strip used to freeze here", p.system)
    }

    @Test
    fun `a quiet card does the minimum`() {
        val p = TickPlan.decide(inputs(detailed = false, tick = 1, sinceFull = 2))
        assertEquals(TickPlan.Plan(fullScan = false, processes = false, system = false), p)
    }

    @Test
    fun `a quiet card still refreshes its process list every Nth tick and when the pid set changes`() {
        assertTrue(TickPlan.decide(inputs(tick = ServersApp.PROC_SLOW_TICKS)).processes)
        assertFalse(TickPlan.decide(inputs(tick = ServersApp.PROC_SLOW_TICKS + 1)).processes)
        assertTrue(TickPlan.decide(inputs(pidSetChanged = true, sincePid = 50)).processes)
    }

    @Test
    fun `the settling window and rechecks are sane`() {
        assertTrue(TickPlan.SETTLE_TICKS * ServersApp.TICK_MS >= 30_000L)
        assertTrue(TickPlan.RECHECK_TICKS_CARD > TickPlan.RECHECK_TICKS_DETAILED)
    }
}
