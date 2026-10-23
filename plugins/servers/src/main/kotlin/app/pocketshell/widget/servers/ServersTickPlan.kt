package app.pocketshell.widget.servers

/**
 * What one monitor tick refreshes - decided in one pure place so every page sees fresh data and the rule is tested.
 *
 * Why it exists (audit of Processes 2.1.x):
 *  1. The host probe's idle gate says "pid set unchanged and nothing was listening -> skip". A dev server starts its
 *     process FIRST and binds its port seconds later (compile, bundle, warm up), so the late listener was invisible
 *     until some other process started or the user tapped refresh. After the pid set changes we therefore keep
 *     doing full scans for a settling window, and recheck periodically while the page is open.
 *  2. A tick that only refreshed processes left the SYSTEM strip (load, memory) and the "scanned Ns ago" label frozen,
 *     because both were written only by a full scan. A process pass on the maximized page now refreshes both.
 */
internal object TickPlan {

    /** Ticks (5 s each) of full scans after the pid set changed - long enough for a slow dev server to bind. */
    const val SETTLE_TICKS = 12

    /** While the maximized page is open, a full recheck at least this often even when nothing seems to change (60 s). */
    const val RECHECK_TICKS_DETAILED = 12

    /** The card rechecks less often (3 min): it is a glance, and a full scan costs more than a process pass. */
    const val RECHECK_TICKS_CARD = 36

    data class Inputs(
        val firstScan: Boolean,
        /** The host probe's own gate ([ServersProbeWrapper.shouldFullScan]). */
        val hostGateDue: Boolean,
        val pidSetChanged: Boolean,
        /** The maximized page is showing (it needs every tick's CPU/memory). */
        val detailed: Boolean,
        val ticksSincePidChange: Int,
        val ticksSinceFullScan: Int,
        val tickCount: Int,
        /** Every Nth tick the process list refreshes even on a quiet card. */
        val slowTicks: Int = ServersApp.PROC_SLOW_TICKS,
    )

    data class Plan(
        /** Servers + HTTP checks + processes + system strip. */
        val fullScan: Boolean,
        /** At least the process list. */
        val processes: Boolean,
        /** The system strip (load / memory / uptime / disk). */
        val system: Boolean,
    )

    fun decide(i: Inputs): Plan {
        val recheck = if (i.detailed) RECHECK_TICKS_DETAILED else RECHECK_TICKS_CARD
        val full = i.firstScan || i.hostGateDue || i.ticksSincePidChange < SETTLE_TICKS || i.ticksSinceFullScan >= recheck
        val processes = full || i.detailed || i.pidSetChanged || (i.slowTicks > 0 && i.tickCount % i.slowTicks == 0)
        val system = full || (i.detailed && processes)
        return Plan(fullScan = full, processes = processes, system = system)
    }
}
