package app.pocketshell.widget.serverhub

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
 * Server Hub - start and manage web servers, dashboards, local APIs and self-hosted services from the phone:
 * ports, logs, health, restart controls and access links.
 *
 * Cost policy: the card is a pure view of one small saved file and the in-memory state of what is running.
 * Nothing polls: health is read once a service has started and when you ask; the log follows only while it is
 * open and switched on.
 */
object HubApp : HomeApplication() {

    const val HUB_PLUGIN_ID = "serverhub"

    /** A card must stay on screen this long before it reads anything: a fast scroll must cost nothing. */
    const val SETTLE_MS = 800L

    /** The card re-reads its small file at most this often. */
    const val STALE_MS = 5 * 60_000L

    override val spec = HomeAppSpec(
        id = HUB_PLUGIN_ID,
        name = "Server Hub",
        summary = "Start and manage web servers, local APIs and self-hosted services - ports, logs, health, restart, links",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        val state = remember { context.stateStore.forApp(HUB_PLUGIN_ID) { HubState(appContext) } }
        val onScreen = remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            snapshotFlow { onScreen.value to state.studio }
                .distinctUntilChanged()
                .collectLatest { (shown, studio) ->
                    if (!shown) return@collectLatest
                    delay(SETTLE_MS)
                    if (state.services == null || studio || System.currentTimeMillis() - state.loadedAtMs > STALE_MS) state.reload()
                }
        }

        BackHandler(enabled = state.detail != null) { state.detail = null }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().trackOnScreen(onScreen)) {
            val layout = Layout.from(maxWidth.value, maxHeight.value)
            SideEffect { state.studio = layout == Layout.FULL }
            CompositionLocalProvider(LocalFull provides (layout == Layout.FULL)) {
                if (context.runtimeState != RuntimeState.READY) {
                    NotReady(spec.name, onOpenDiagnostics = { context.nav.openDiagnostics() })
                } else if (layout == Layout.FULL) {
                    HubStudio(state, sideRail = Layout.usesSideRail(maxWidth.value), nav = context.nav)
                } else {
                    MiniCard(
                        title = spec.name,
                        subtitle = statusLine(state.lanIp, state.services != null),
                        loading = state.loading,
                        strip = summaryItems(state.services, state.live),
                        hint = hintFor(state.services),
                        onTerminal = { context.nav.openTerminal() },
                        onRefresh = { state.reload() },
                    )
                }
            }
        }
    }
}
