package app.pocketshell.widget.guiapps

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
 * GUI Apps - discover, install and launch desktop apps for the Linux guest, musl (Alpine packages) and glibc (AppImages).
 *
 * Cost policy: the card is a pure view of a few small local file reads, taken once after it has stayed on screen;
 * nothing polls; the network is touched only by an explicit install, update check or custom download.
 */
object GuiApp : HomeApplication() {

    const val GUI_PLUGIN_ID = "guiapps"

    /** A card must stay on screen this long before it reads anything: a fast scroll must cost nothing. */
    const val SETTLE_MS = 800L

    /** The card re-reads its small files at most this often. */
    const val STALE_MS = 5 * 60_000L

    override val spec = HomeAppSpec(
        id = GUI_PLUGIN_ID,
        name = "GUI Apps",
        summary = "Discover, install and launch desktop apps for the Linux guest - musl and glibc, arm64",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        val state = remember { context.stateStore.forApp(GUI_PLUGIN_ID) { GuiState(appContext) } }
        val onScreen = remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            snapshotFlow { onScreen.value to state.studio }
                .distinctUntilChanged()
                .collectLatest { (shown, studio) ->
                    if (!shown) return@collectLatest
                    delay(SETTLE_MS)
                    if (state.snapshot == null || studio || System.currentTimeMillis() - state.loadedAtMs > STALE_MS) state.reload()
                }
        }

        // A web app that finished starting asks the page (which owns the navigation) to open it.
        LaunchedEffect(state.openUrl) {
            state.openUrl?.let { url -> context.nav.openCompanion(url); state.openUrl = null }
        }

        BackHandler(enabled = state.detail != null) { state.detail = null }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().trackOnScreen(onScreen)) {
            val layout = Layout.from(maxWidth.value, maxHeight.value)
            SideEffect { state.studio = layout == Layout.FULL }
            CompositionLocalProvider(LocalFull provides (layout == Layout.FULL)) {
                if (context.runtimeState != RuntimeState.READY) {
                    NotReady(spec.name, onOpenDiagnostics = { context.nav.openDiagnostics() })
                } else if (layout == Layout.FULL) {
                    GuiStudio(state, sideRail = Layout.usesSideRail(maxWidth.value), nav = context.nav)
                } else {
                    MiniCard(
                        title = spec.name,
                        subtitle = statusLine(state.snapshot),
                        loading = state.loading,
                        strip = summaryItems(state),
                        hint = hintFor(state),
                        onTerminal = { context.nav.openTerminal() },
                        onRefresh = { state.reload() },
                    )
                }
            }
        }
    }
}
