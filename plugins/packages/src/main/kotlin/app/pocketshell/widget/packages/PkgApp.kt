package app.pocketshell.widget.packages

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
 * Packages & Environment - add, update and remove apk packages and CLI tool bundles, and look after the
 * Linux environment (repositories, cache, repair).
 *
 * Cost policy: the card is a pure view of two small local file reads (the installed database and the world file),
 * taken once after it has stayed on screen. Nothing polls. `apk` runs only on a tap, and every change is simulated
 * and confirmed first.
 */
object PkgApp : HomeApplication() {

    const val PKG_PLUGIN_ID = "packages"

    /** A card must stay on screen this long before it reads anything: a fast scroll must cost nothing. */
    const val SETTLE_MS = 800L

    /** The card re-reads its small files at most this often. */
    const val STALE_MS = 5 * 60_000L

    override val spec = HomeAppSpec(
        id = PKG_PLUGIN_ID,
        name = "Packages",
        summary = "Add, update and remove Linux packages and CLI tools; repositories, cache and repair",
    )

    @Composable
    override fun Content(context: HomeAppContext) {
        val appContext = LocalContext.current.applicationContext
        val state = remember { context.stateStore.forApp(PKG_PLUGIN_ID) { PkgState(appContext) } }
        val onScreen = remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            snapshotFlow { onScreen.value to state.studio }
                .distinctUntilChanged()
                .collectLatest { (shown, studio) ->
                    if (!shown) return@collectLatest
                    delay(SETTLE_MS)
                    if (state.inventory == null || studio || System.currentTimeMillis() - state.loadedAtMs > STALE_MS) state.reload()
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
                    PkgStudio(state, sideRail = Layout.usesSideRail(maxWidth.value), nav = context.nav)
                } else {
                    MiniCard(
                        title = spec.name,
                        subtitle = statusLine(state.facts),
                        loading = state.loading,
                        strip = summaryItems(state.inventory, state.updates),
                        hint = hintFor(state.inventory),
                        onTerminal = { context.nav.openTerminal() },
                        onRefresh = { state.reload() },
                    )
                }
            }
        }
    }
}
