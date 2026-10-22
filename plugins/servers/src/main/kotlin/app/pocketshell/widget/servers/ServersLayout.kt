package app.pocketshell.widget.servers

/**
 * The responsive contract — the same geometry the Git workstation and the
 * v1 manifest renderer derive from (the card dimensions are identical, so
 * the device-derived thresholds carry over):
 *
 *   COMPACT  a phone card: the at-a-glance answer — status dot, "N up",
 *            one-line verified rows, the honest state line. No cwd, no
 *            HTTP chips, no history. Everything needed immediately.
 *   ROOMY    a large card: adds the per-server cwd line, the HTTP status
 *            chip + latency and the session beat strip. Still glanceable.
 *   FULL     the maximized page: the desktop-class task manager
 *            (ServersStudio.kt) — OVERVIEW / SERVERS / BACKGROUND /
 *            SESSIONS behind a bottom bar (a side rail when wide), device
 *            health, search, sort, response history, process facts, and
 *            the stop / force-kill actions. On wide pages the list sits
 *            beside the detail — the two-pane workstation shape.
 *
 * The SAME composable is the card and the maximized page (the host's
 * container transform); only the information hierarchy changes, never a
 * scaled layout. Pure + JVM-tested (ServersLayoutTest).
 */
internal enum class ServersLayout(
    val showsCwd: Boolean,
    val showsStatusHeader: Boolean,
    val showsHttpChip: Boolean,
    val showsBeats: Boolean,
    val showsSearch: Boolean,
    val showsSystemStrip: Boolean,
    val showsProcessFacts: Boolean,
    val showsStopAction: Boolean,
    /** How many rows the card shows before "+N more" (FULL scrolls, so unbounded). */
    val cardRows: Int,
) {
    COMPACT(
        showsCwd = false,
        showsStatusHeader = false,
        showsHttpChip = false,
        showsBeats = false,
        showsSearch = false,
        showsSystemStrip = false,
        showsProcessFacts = false,
        showsStopAction = false,
        cardRows = 4,
    ),
    ROOMY(
        showsCwd = true,
        showsStatusHeader = true,
        showsHttpChip = true,
        showsBeats = true,
        showsSearch = false,
        showsSystemStrip = false,
        showsProcessFacts = false,
        showsStopAction = false,
        cardRows = 7,
    ),
    FULL(
        showsCwd = true,
        showsStatusHeader = true,
        showsHttpChip = true,
        showsBeats = true,
        showsSearch = true,
        showsSystemStrip = true,
        showsProcessFacts = true,
        showsStopAction = true,
        cardRows = Int.MAX_VALUE,
    );

    /** FULL renders the monitor chrome (system strip, search); cards do not. */
    val isMonitor: Boolean get() = this == FULL

    companion object {
        const val ROOMY_MIN_WIDTH_DP = 420f
        const val ROOMY_MIN_HEIGHT_DP = 200f

        /** A maximized page is never this short; a card never gets this tall. */
        const val FULL_MIN_HEIGHT_DP = 400f

        /** Wide enough for the server list to sit beside the detail page. */
        const val WORKSPACE_MIN_WIDTH_DP = 600f

        /** Narrowest page the task-manager shell is laid out for (a small phone). */
        const val FULL_MIN_WIDTH_DP = 320f

        /** A landscape phone page: wide, but under [FULL_MIN_HEIGHT_DP] tall. */
        const val FULL_LANDSCAPE_MIN_HEIGHT_DP = 300f

        /** Side navigation rail instead of the bottom bar from this width. */
        fun usesSideRail(widthDp: Float): Boolean = widthDp >= WORKSPACE_MIN_WIDTH_DP

        /**
         * FULL is decided by HEIGHT first: a portrait phone page is only
         * ~410dp wide (under [ROOMY_MIN_WIDTH_DP]) yet ~800dp tall, and a
         * card is never that tall — so width alone must not demote the
         * maximized page to the card hierarchy.
         */
        fun from(widthDp: Float, heightDp: Float): ServersLayout = when {
            widthDp >= FULL_MIN_WIDTH_DP && heightDp >= FULL_MIN_HEIGHT_DP -> FULL
            widthDp >= WORKSPACE_MIN_WIDTH_DP && heightDp >= FULL_LANDSCAPE_MIN_HEIGHT_DP -> FULL
            widthDp >= ROOMY_MIN_WIDTH_DP && heightDp >= ROOMY_MIN_HEIGHT_DP -> ROOMY
            else -> COMPACT
        }

        /** The two-pane dashboard: list beside detail, never on a phone. */
        fun twoPane(widthDp: Float): Boolean = widthDp >= WORKSPACE_MIN_WIDTH_DP
    }
}
