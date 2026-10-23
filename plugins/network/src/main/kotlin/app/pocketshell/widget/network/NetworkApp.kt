package app.pocketshell.widget.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.pocketshell.runtime.RuntimeState
import app.pocketshell.widget.HomeAppContext
import app.pocketshell.widget.HomeAppSpec
import app.pocketshell.widget.HomeApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Network — a cockpit for ssh hosts, keys, port forwards, folder sharing and connectivity checks.
 *
 * Cost policy (the lesson of Git 3.3–3.4): the mini card is a pure view of a few small local
 * file reads, taken once after the card has stayed on screen; nothing polls; the network is touched
 * only by an explicit action in the maximized page.
 */
object NetworkApp : HomeApplication() {

    const val NETWORK_PLUGIN_ID = "network"

    /** A card must stay on screen this long before it reads anything: a fast scroll must cost nothing. */
    const val SETTLE_MS = 800L

    /** The mini card re-reads its small files at most this often. */
    const val STALE_MS = 5 * 60_000L

    override val spec = HomeAppSpec(
        id = NETWORK_PLUGIN_ID,
        name = "Network & Home Lab",
        summary = "Find devices, check servers, DNS, ping, traceroute and ports, plus SSH hosts, keys, tunnels and sharing",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        val state = remember { context.stateStore.forApp(NETWORK_PLUGIN_ID) { NetworkState(appContext) } }
        val onScreen = remember { mutableStateOf(false) }

        // Read the small local files once the card has SETTLED on screen (and again only when stale).
        LaunchedEffect(Unit) {
            snapshotFlow { onScreen.value to state.studio }
                .distinctUntilChanged()
                .collectLatest { (shown, studio) ->
                    if (!shown) return@collectLatest
                    delay(SETTLE_MS)
                    val loadedAt = state.loadedAtMs
                    if (state.local == null || studio || System.currentTimeMillis() - loadedAt > STALE_MS) state.reloadLocal()
                }
        }

        // The application owns its back: a detail screen closes before the page does.
        BackHandler(enabled = state.detail != null) { state.detail = null }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().trackOnScreen(onScreen)) {
            val layout = NetLayout.from(maxWidth.value, maxHeight.value)
            SideEffect { state.studio = layout == NetLayout.FULL }
            CompositionLocalProvider(LocalNetFull provides (layout == NetLayout.FULL)) {
                if (context.runtimeState != RuntimeState.READY) {
                    NotReady(onOpenDiagnostics = { context.nav.openDiagnostics() })
                } else if (layout == NetLayout.FULL) {
                    NetworkStudio(
                        state = state,
                        sideRail = NetLayout.usesSideRail(maxWidth.value),
                        nav = context.nav,
                    )
                } else {
                    NetworkMiniCard(
                        local = state.local,
                        loading = state.loading,
                        tunnelStates = state.tunnelStates,
                        share = state.share,
                        onRefresh = { state.reloadLocal() },
                        onOpenTerminal = { context.nav.openTerminal() },
                    )
                }
            }
        }
    }
}
