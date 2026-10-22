package app.pocketshell.widget.git

/**
 * The responsive contract — same geometry as ServersLayout (the card
 * dimensions are identical, so the device-derived thresholds carry over):
 *
 *   COMPACT  a phone card: one-line rows, no paths, no status header.
 *   ROOMY    a large card: paths, sublines, the status header, scroll.
 *   FULL     the maximized page (GitStudio.kt — its own full-screen shell): the workstation — enough height for the
 *            repository workspace's tab bar and its content together, and
 *            (on wide pages) for the dashboard's list to sit beside the
 *            workspace.
 *
 * The SAME composable is the card and the maximized page (the host's
 * container transform); only the information hierarchy changes, never a
 * scaled layout. Pure + JVM-tested (GitLayoutTest).
 */
internal enum class GitLayout(
    val showsPath: Boolean,
    val showsStatusHeader: Boolean,
) {
    COMPACT(showsPath = false, showsStatusHeader = false),
    ROOMY(showsPath = true, showsStatusHeader = true),
    FULL(showsPath = true, showsStatusHeader = true);

    /** FULL renders the workspace chrome (tab bar); the cards do not. */
    val isWorkspace: Boolean get() = this == FULL

    companion object {
        const val ROOMY_MIN_WIDTH_DP = 420f
        const val ROOMY_MIN_HEIGHT_DP = 200f

        /** A maximized page is never this short; a card never gets this tall. */
        const val FULL_MIN_HEIGHT_DP = 400f

        /** Wide enough for the dashboard list beside the workspace. */
        const val WORKSPACE_MIN_WIDTH_DP = 600f

        const val DETAIL_MAX_ENTRIES_COMPACT = 8
        const val DETAIL_MAX_ENTRIES_ROOMY = 24

        /** Narrowest page the Studio shell is laid out for (a small phone). */
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
        fun from(widthDp: Float, heightDp: Float): GitLayout = when {
            widthDp >= FULL_MIN_WIDTH_DP && heightDp >= FULL_MIN_HEIGHT_DP -> FULL
            widthDp >= WORKSPACE_MIN_WIDTH_DP && heightDp >= FULL_LANDSCAPE_MIN_HEIGHT_DP -> FULL
            widthDp >= ROOMY_MIN_WIDTH_DP && heightDp >= ROOMY_MIN_HEIGHT_DP -> ROOMY
            else -> COMPACT
        }
    }
}
