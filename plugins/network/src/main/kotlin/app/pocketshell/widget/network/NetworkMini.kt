package app.pocketshell.widget.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens

/** The one line under the title: where this phone is on the network. */
internal fun statusLine(ifaces: List<Iface>?): String = when {
    ifaces == null -> "reading…"
    ifaces.isEmpty() -> "offline"
    else -> ifaces.sortedByDescending { it.isWifi }.first().let { "${it.name} ${it.ip}" } +
        if (ifaces.size > 1) " · +${ifaces.size - 1}" else ""
}

/** The four numbers the card and the maximized home share. */
internal fun summaryItems(local: LocalSnapshot?, states: Map<String, TunnelState>, share: ShareState): List<StripItem> {
    val tunnels = local?.tunnels.orEmpty()
    val up = tunnels.count { states[it.id] is TunnelState.Up }
    return listOf(
        StripItem("${local?.config?.hosts?.size ?: 0}", "hosts"),
        StripItem("${local?.lab?.size ?: 0}", "lab"),
        StripItem("$up/${tunnels.size}", "tunnels up", hot = up > 0),
        StripItem(if (share is ShareState.Running) "ON" else "off", "sharing", hot = share is ShareState.Running),
    )
}

/** First-run guidance, in the order a new user needs it. Null when there is nothing to suggest. */
internal fun hintFor(local: LocalSnapshot?): String? = when {
    local == null -> null
    local.keys.isEmpty() && local.config.hosts.isEmpty() -> "No keys or hosts yet — open Network to create a key."
    local.keys.isEmpty() -> "No SSH key yet — create one to log in without a password."
    local.config.hosts.isEmpty() -> "No saved hosts — add one to connect in a tap."
    else -> null
}

/**
 * MINI: a pure view of the cached local snapshot. No network traffic, no exec, no list —
 * the heavy things live in the maximized page.
 */
@Composable
internal fun NetworkMiniCard(
    local: LocalSnapshot?,
    loading: Boolean,
    tunnelStates: Map<String, TunnelState>,
    share: ShareState,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = NetworkApp.spec.name,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HomeTokens.textPrimary,
                )
                MonoText(text = statusLine(local?.ifaces), color = HomeTokens.textDim, fontSize = 10.sp)
            }
            IconAction(Icons.Outlined.Terminal, "Open terminal", onClick = onOpenTerminal)
            IconAction(Icons.Outlined.Refresh, "Refresh network", enabled = !loading, onClick = onRefresh)
        }
        LoadingRule(active = loading)
        StatStrip(summaryItems(local, tunnelStates, share), modifier = Modifier.padding(top = 8.dp))
        hintFor(local)?.let { MonoText(text = it, color = HomeTokens.textDim, maxLines = 2, modifier = Modifier.padding(top = 8.dp)) }
    }
}
