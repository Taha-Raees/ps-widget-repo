package app.pocketshell.widget.servers

/**
 * "Claim a tick exactly once" — the guard that keeps a restarted effect from
 * re-running what it already started.
 *
 * The state holders outlive the composition (leaving Home, swiping the page
 * away), but a LaunchedEffect keyed on a tick RESTARTS when the composition
 * returns — with the old, non-zero tick. Without a claim, simply coming back
 * to the card re-ran the last refresh / run / preview. Tick 0 means nothing
 * was launched; [reset] is for holders that zero their ticks on purpose.
 */
internal class RunOnce {
    private var last = 0

    fun claim(tick: Int): Boolean {
        if (tick == last) return false
        last = tick
        return true
    }

    fun reset() {
        last = 0
    }
}
