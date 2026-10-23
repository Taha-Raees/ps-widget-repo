package app.pocketshell.widget.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.WidgetNav
import kotlinx.coroutines.delay

/** Which modal (if any) the page is showing. */
private sealed interface Modal {
    data class Host(val initial: SshHost?) : Modal
    data class TunnelForm(val initial: Tunnel?) : Modal
    data object NewKey : Modal
    data class Confirm(val title: String, val message: String, val label: String, val word: String?, val action: () -> Unit) : Modal
}

@Composable
internal fun NotReady(onOpenDiagnostics: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Network", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = HomeTokens.textPrimary, modifier = Modifier.padding(top = 4.dp))
        MonoText(text = "Linux is not ready yet", color = HomeTokens.textDim, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics", color = HomeTokens.accent) }
    }
}

@Composable
internal fun NetworkStudio(state: NetworkState, sideRail: Boolean, nav: WidgetNav) {
    var modal by remember { mutableStateOf<Modal?>(null) }
    val busy = state.loading || state.busy != null || state.checking || state.testing.isNotEmpty()

    Row(Modifier.fillMaxSize()) {
        if (sideRail) {
            Column(Modifier.width(132.dp).fillMaxHeight().padding(top = 8.dp)) {
                NetTab.entries.forEach { tab -> NavItem(tab, state.tab == tab) { state.tab = tab; state.detail = null } }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(HomeTokens.hairline))
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (sideRail) 12.dp else 0.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Network", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = HomeTokens.textPrimary)
                    MonoText(text = statusLine(state.local?.ifaces), color = HomeTokens.textDim, fontSize = 10.sp)
                }
                IconAction(Icons.Outlined.Terminal, "Open terminal", onClick = { nav.openTerminal() })
                IconAction(Icons.Outlined.Refresh, "Refresh", enabled = !state.loading, onClick = { state.reloadLocal() })
            }
            LoadingRule(active = busy)
            state.banner?.let { b ->
                Row(Modifier.fillMaxWidth().clickable { state.dismissBanner() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoText(text = b.text, color = if (b.bad) HomeTokens.danger else HomeTokens.runningGreen, maxLines = 3, modifier = Modifier.weight(1f))
                    MonoText(text = "OK", color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Hairline()
            }
            StatStrip(
                summaryItems(state.local, state.tunnelStates, state.share).mapIndexed { i, item ->
                    item.copy(onClick = { state.tab = listOf(NetTab.HOSTS, NetTab.KEYS, NetTab.TUNNELS, NetTab.SHARE)[i]; state.detail = null })
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                    when (state.tab) {
                        NetTab.HOSTS -> HostsTab(state, nav, onModal = { modal = it })
                        NetTab.KEYS -> KeysTab(state, onModal = { modal = it })
                        NetTab.TUNNELS -> TunnelsTab(state, nav, onModal = { modal = it })
                        NetTab.SHARE -> ShareTab(state, nav)
                        NetTab.CHECKS -> ChecksTab(state)
                    }
                }
            }
            if (!sideRail) {
                Hairline()
                Row(Modifier.fillMaxWidth()) {
                    NetTab.entries.forEach { tab -> Box(Modifier.weight(1f)) { NavItem(tab, state.tab == tab, compact = true) { state.tab = tab; state.detail = null } } }
                }
            }
        }
    }

    ShowModal(modal, state, onDismiss = { modal = null })
    state.pendingTrust?.let { TrustDialog(it, onConfirm = { state.confirmTrust() }, onDismiss = { state.dismissTrust() }) }
}

@Composable
private fun ShowModal(modal: Modal?, state: NetworkState, onDismiss: () -> Unit) {
    val local = state.local
    when (modal) {
        null -> Unit
        is Modal.Host -> HostFormDialog(modal.initial, local?.keys.orEmpty(), onSave = { h, done -> state.saveHost(h, done) }, onDismiss = onDismiss)
        is Modal.TunnelForm -> TunnelFormDialog(modal.initial, local?.config?.hosts?.map { it.alias }.orEmpty(), onSave = { t, done -> state.saveTunnel(t, done) }, onDismiss = onDismiss)
        Modal.NewKey -> KeyFormDialog(local?.keys?.map { it.name }?.toSet().orEmpty(), "me@phone", onCreate = { n, c, done -> state.createKey(n, c, done) }, onDismiss = onDismiss)
        is Modal.Confirm -> ConfirmDialog(modal.title, modal.message, modal.label, modal.word, onConfirm = modal.action, onDismiss = onDismiss)
    }
}

@Composable
private fun NavItem(tab: NetTab, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val color = if (selected) HomeTokens.accent else HomeTokens.textDim
    if (compact) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Tab, onClickLabel = "Show ${tab.label}") { onClick() }.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(tab.icon, tab.label, tint = color, modifier = Modifier.height(22.dp))
            MonoText(text = tab.label.uppercase(), color = color, fontSize = 9.sp)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Tab, onClickLabel = "Show ${tab.label}") { onClick() }.padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(tab.icon, tab.label, tint = color, modifier = Modifier.height(22.dp))
            MonoText(text = tab.label.uppercase(), color = color, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

// ================================================================ HOSTS

@Composable
private fun HostsTab(state: NetworkState, nav: WidgetNav, onModal: (Modal) -> Unit) {
    val context = LocalContext.current
    val local = state.local
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionText("Add host") { onModal(Modal.Host(null)) }
        ActionText("Check reachability", enabled = !local?.config?.hosts.isNullOrEmpty()) { state.probeHosts() }
    }
    val hosts = local?.config?.hosts.orEmpty()
    if (local == null) MonoText("Reading ~/.ssh/config…", color = HomeTokens.textDim, modifier = Modifier.padding(top = 8.dp))
    else if (hosts.isEmpty()) {
        MonoText("No hosts yet.", color = HomeTokens.textPrimary, modifier = Modifier.padding(top = 12.dp))
        MonoText("Add one and connect with a tap — it is saved in ~/.ssh/config, where ssh itself reads it.", color = HomeTokens.textDim, maxLines = 3)
    }
    hosts.forEach { h ->
        val open = state.detail == h.alias
        Hairline()
        Column(Modifier.fillMaxWidth().clickable { state.detail = if (open) null else h.alias }.padding(vertical = if (LocalNetFull.current) 10.dp else 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReachDot(state.reach[h.alias])
                MonoText(h.alias, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                MonoText(reachLabel(state.reach[h.alias]), color = HomeTokens.textDim, fontSize = 10.sp)
            }
            MonoText(h.summary, color = HomeTokens.textDim, modifier = Modifier.padding(start = 16.dp))
            state.hostTests[h.alias]?.let { OutcomeLine(it, Modifier.padding(start = 16.dp, top = 2.dp)) }
            if (h.alias in state.testing) MonoText("testing…", color = HomeTokens.textDim, modifier = Modifier.padding(start = 16.dp))
        }
        if (open) {
            val outcome = state.hostTests[h.alias]
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionText("Connect") {
                    // The app types it at the prompt (you press Enter); an older app only opens the terminal, so copy as a fallback.
                    val typed = HostCompat.typeInTerminal(nav, "ssh ${h.alias}")
                    if (!typed) copyToClipboard(context, "ssh", "ssh ${h.alias}")
                    state.banner = Banner(if (typed) "Typed at the prompt: ssh ${h.alias} — press Enter" else "Copied: ssh ${h.alias} — paste it in the terminal", false)
                }
                ActionText("Test", enabled = h.alias !in state.testing) { state.testHost(h.alias) }
                ActionText("Trust key", enabled = "trust:${h.alias}" !in state.testing && h.proxyJump == null) { state.requestTrust(h) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionText("Edit") { onModal(Modal.Host(h)) }
                ActionText("Delete", danger = true) {
                    onModal(Modal.Confirm("Remove ${h.alias}?", "This deletes its entry from ~/.ssh/config. A copy of the original file is kept as config.pre-network.", "Remove", null) { state.deleteHost(h.alias) })
                }
            }
            if (outcome == SshOutcome.HostKeyUnknown) MonoText("Tap TRUST KEY to check and accept this host's fingerprint.", color = HomeTokens.textDim, maxLines = 2, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            if (outcome == SshOutcome.AuthFailed) MonoText("The host answered but not with this key — see KEYS to test or add one.", color = HomeTokens.textDim, maxLines = 2, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
        }
    }
    local?.config?.readOnly?.takeIf { it.isNotEmpty() }?.let { others ->
        SectionLabel("OTHER ENTRIES (EDIT IN THE TERMINAL)")
        others.forEach { MonoText((if (it.isMatch) "Match " else "Host ") + it.patterns.joinToString(" "), color = HomeTokens.textDim, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

@Composable
private fun ReachDot(r: HostReach?) {
    StatusDot(
        when (r) {
            is HostReach.Up -> HomeTokens.runningGreen
            HostReach.Down -> HomeTokens.danger
            else -> HomeTokens.hairline
        },
    )
}

private fun reachLabel(r: HostReach?): String = when (r) {
    is HostReach.Up -> "${r.ms} ms"
    HostReach.Down -> "no answer"
    HostReach.Checking -> "checking…"
    HostReach.ViaJump -> "via jump"
    HostReach.Unknown, null -> ""
}

@Composable
private fun OutcomeLine(outcome: SshOutcome, modifier: Modifier = Modifier) {
    val color = when {
        outcome is SshOutcome.Ok -> HomeTokens.runningGreen
        outcome == SshOutcome.HostKeyChanged -> HomeTokens.danger
        outcome.reachable -> HomeTokens.accent
        else -> HomeTokens.danger
    }
    MonoText(outcome.message(), color = color, maxLines = 3, modifier = modifier)
}

// ================================================================= KEYS

@Composable
private fun KeysTab(state: NetworkState, onModal: (Modal) -> Unit) {
    val context = LocalContext.current
    val keys = state.local?.keys.orEmpty()
    Row(Modifier.padding(top = 6.dp)) { ActionText("Create key", enabled = state.busy == null) { onModal(Modal.NewKey) } }
    if (state.local == null) MonoText("Reading ~/.ssh…", color = HomeTokens.textDim)
    else if (keys.isEmpty()) {
        MonoText("No SSH keys yet.", modifier = Modifier.padding(top = 12.dp))
        MonoText("A key lets you log in to servers and GitHub without typing a password.", color = HomeTokens.textDim, maxLines = 3)
    }
    keys.forEach { k ->
        val open = state.detail == k.name
        Hairline()
        Column(Modifier.fillMaxWidth().clickable { state.detail = if (open) null else k.name }.padding(vertical = if (LocalNetFull.current) 10.dp else 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(k.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                MonoText("${k.type}${k.bits?.let { " $it" } ?: ""}", color = HomeTokens.textDim, fontSize = 10.sp)
            }
            MonoText(k.fingerprint, color = HomeTokens.textDim, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (k.comment.isNotEmpty()) MonoText(k.comment, color = HomeTokens.textDim, fontSize = 10.sp)
                if (!k.hasPrivate) Tag("public only", hot = true)
                if (k.encrypted) Tag("passphrase")
                if (k.permissionsTooOpen) Tag("open permissions", hot = true)
            }
            state.keyTests[k.name]?.let { (host, outcome) ->
                Row {
                    MonoText("$host: ", color = HomeTokens.textDim)
                    OutcomeLine(outcome)
                }
            }
        }
        if (open) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionText("Copy public key") { copyToClipboard(context, "public key", k.publicLine); state.banner = Banner("Public key copied — paste it into GitHub → Settings → SSH keys", false) }
            }
            val testable = k.hasPrivate && !k.encrypted
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionText("Test with GitHub", enabled = testable && "key:${k.name}:github.com" !in state.testing) { state.testKeyWith(k.name, "github.com") }
                ActionText("Test with GitLab", enabled = testable && "key:${k.name}:gitlab.com" !in state.testing) { state.testKeyWith(k.name, "gitlab.com") }
            }
            if (k.encrypted) MonoText("Protected by a passphrase: tests cannot type it. Load it in a terminal with ssh-add.", color = HomeTokens.textDim, maxLines = 3)
            val t = state.keyTests[k.name]
            if (t != null && t.second == SshOutcome.HostKeyUnknown) {
                ActionText("Trust ${t.first}") { state.requestTrustForName(t.first) }
            }
            if (t != null && t.second == SshOutcome.AuthFailed) MonoText("${t.first} does not know this key yet — copy the public key and add it to your account there.", color = HomeTokens.textDim, maxLines = 3)
            Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
                ActionText("Delete", danger = true) {
                    onModal(Modal.Confirm("Delete ${k.name}?", "This permanently removes the private and public key from this phone. Servers that trust it will no longer let you in, and it cannot be recovered.", "Delete", k.name) { state.deleteKey(k.name) })
                }
            }
        }
    }
}

// ============================================================== TUNNELS

@Composable
private fun TunnelsTab(state: NetworkState, nav: WidgetNav, onModal: (Modal) -> Unit) {
    val tunnels = state.local?.tunnels.orEmpty()
    Row(Modifier.padding(top = 6.dp)) { ActionText("Add tunnel") { onModal(Modal.TunnelForm(null)) } }
    MonoText("A tunnel forwards a port over ssh. It runs only while PocketShell is open.", color = HomeTokens.textDim, maxLines = 2)
    if (state.local != null && tunnels.isEmpty()) {
        MonoText("No tunnels yet.", modifier = Modifier.padding(top = 12.dp))
        MonoText("Example: reach a remote database on localhost:5432.", color = HomeTokens.textDim, maxLines = 2)
    }
    tunnels.forEach { t ->
        val st = state.tunnelStates[t.id] ?: TunnelState.Stopped
        val open = state.detail == t.id
        Hairline()
        Column(Modifier.fillMaxWidth().clickable { state.detail = if (open) null else t.id }.padding(vertical = if (LocalNetFull.current) 10.dp else 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(when (st) { is TunnelState.Up -> HomeTokens.runningGreen; is TunnelState.Dropped -> HomeTokens.danger; else -> HomeTokens.hairline })
                MonoText(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).weight(1f))
                MonoText(t.kind.label, color = HomeTokens.textDim, fontSize = 10.sp)
            }
            MonoText(Tunnels.describe(t), color = HomeTokens.textDim, maxLines = 2, modifier = Modifier.padding(start = 16.dp))
            when (st) {
                TunnelState.Stopped -> Unit
                TunnelState.Starting -> MonoText("starting…", color = HomeTokens.textDim, modifier = Modifier.padding(start = 16.dp))
                is TunnelState.Up -> MonoText("up", color = HomeTokens.runningGreen, modifier = Modifier.padding(start = 16.dp))
                is TunnelState.Dropped -> OutcomeLine(st.outcome, Modifier.padding(start = 16.dp))
            }
        }
        if (open) {
            val running = st is TunnelState.Up || st == TunnelState.Starting
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (running) ActionText("Stop") { state.stopTunnel(t.id) } else ActionText("Start") { state.startTunnel(t) }
                Tunnels.openUrl(t)?.let { url -> ActionText("Open", enabled = st is TunnelState.Up) { nav.openCompanion(url) } }
                ActionText("Edit", enabled = !running) { onModal(Modal.TunnelForm(t)) }
                ActionText("Delete", danger = true) {
                    onModal(Modal.Confirm("Delete ${t.name}?", "The saved tunnel is removed${if (running) " and stopped" else ""}.", "Delete", null) { state.deleteTunnel(t.id) })
                }
            }
            if (st is TunnelState.Dropped && st.outcome == SshOutcome.HostKeyUnknown) MonoText("Trust the host first (HOSTS → Trust key).", color = HomeTokens.textDim, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

// ================================================================ SHARE

@Composable
private fun ShareTab(state: NetworkState, nav: WidgetNav) {
    val context = LocalContext.current
    val lan = remember(state.local) { ShareGuard.lanAddresses((state.local?.ifaces).orEmpty().map { it.name to it.ip }) }
    var dir by remember { mutableStateOf("/root/") }
    var port by remember { mutableStateOf("8000") }
    var minutes by remember { mutableStateOf(ShareGuard.DEFAULT_MIN) }
    var ip by remember(lan) { mutableStateOf(lan.firstOrNull()?.second.orEmpty()) }

    SectionLabel("SHARE A FOLDER")
    when (val sh = state.share) {
        is ShareState.Running -> {
            val now by produceState(System.currentTimeMillis(), sh.endsAtMs) { while (true) { value = System.currentTimeMillis(); delay(1_000) } }
            val left = ((sh.endsAtMs - now) / 1000).coerceAtLeast(0)
            MonoText("SHARING ${sh.dir}", color = HomeTokens.runningGreen)
            MonoText(sh.url, fontSize = 16.sp, color = HomeTokens.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
            MonoText("Anyone on this Wi-Fi can read these files — no password. Stops in %d:%02d.".format(left / 60, left % 60), color = HomeTokens.textDim, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ActionText("Copy link") { copyToClipboard(context, "link", sh.url); state.banner = Banner("Link copied", false) }
                ActionText("Stop sharing", danger = true) { state.stopShare() }
            }
        }
        ShareState.Starting -> MonoText("Starting…", color = HomeTokens.textDim)
        else -> {
            if (lan.isEmpty()) MonoText("Not on Wi-Fi — connect to a network to share.", color = HomeTokens.textDim)
            Field("Folder (full path)", dir, { dir = it }, hint = "/root/site")
            Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, number = true)
            MonoText("STOPS AFTER", color = HomeTokens.textDim, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
            Choices(ShareGuard.DURATIONS_MIN.map { "$it min" to it }, minutes) { minutes = it }
            if (lan.size > 1) {
                MonoText("ON", color = HomeTokens.textDim, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
                Choices(lan.map { "${it.first} ${it.second}" to it.second }, ip) { ip = it }
            }
            (state.share as? ShareState.Failed)?.let { MonoText(it.reason, color = HomeTokens.danger, maxLines = 3, modifier = Modifier.padding(top = 6.dp)) }
            Row { ActionText("Start sharing", enabled = lan.isNotEmpty()) { state.startShare(dir.trim(), ip, port.toIntOrNull() ?: 0, minutes) } }
            MonoText("Plain HTTP, read-only, on your Wi-Fi address only. Folders holding keys or secrets are refused.", color = HomeTokens.textDim, maxLines = 3)
        }
    }

    SectionLabel("LISTENING ON THIS PHONE")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { ActionText("Look for servers") { state.refreshListening() } }
    val found = state.listening
    if (found == null) MonoText("Finds dev servers running in Linux so you can open them.", color = HomeTokens.textDim, maxLines = 2)
    else if (found.isEmpty()) MonoText("Nothing is listening.", color = HomeTokens.textDim)
    found?.forEach { s ->
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MonoText(":${s.port}  ${s.name}", fontWeight = FontWeight.SemiBold)
                MonoText(
                    when (s.lanReachable) { true -> "also reachable on your Wi-Fi"; false -> "this phone only"; null -> "no Wi-Fi to test" },
                    color = if (s.lanReachable == true) HomeTokens.accent else HomeTokens.textDim, fontSize = 10.sp,
                )
            }
            ActionText("Open") { nav.openCompanion("http://127.0.0.1:${s.port}") }
        }
    }
}

// =============================================================== CHECKS

@Composable
private fun ChecksTab(state: NetworkState) {
    Row(Modifier.padding(top = 6.dp)) { ActionText(if (state.checking) "Checking…" else "Run checks", enabled = !state.checking) { state.runChecks() } }
    val res = state.checks
    if (res == null) {
        MonoText("Tests the connection, your Linux DNS servers and the internet — a few seconds.", color = HomeTokens.textDim, maxLines = 3)
        return
    }
    val (verdict, lines) = res
    Text(verdict.headline, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (verdict == Verdict.ONLINE) HomeTokens.runningGreen else HomeTokens.danger, modifier = Modifier.padding(top = 8.dp))
    MonoText(verdict.advice, color = HomeTokens.textDim, maxLines = 4, modifier = Modifier.padding(bottom = 6.dp))
    lines.forEach { l ->
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            MonoText(
                when (l.state) { CheckState.OK -> "●"; CheckState.WARN -> "▲"; CheckState.FAIL -> "✕"; CheckState.SKIPPED -> "○" },
                color = when (l.state) { CheckState.OK -> HomeTokens.runningGreen; CheckState.WARN -> HomeTokens.accent; CheckState.FAIL -> HomeTokens.danger; CheckState.SKIPPED -> HomeTokens.textDim },
            )
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                MonoText(l.label, fontWeight = FontWeight.SemiBold)
                MonoText(l.detail, color = HomeTokens.textDim, maxLines = 3)
            }
            l.millis?.let { MonoText("$it ms", color = HomeTokens.textDim, fontSize = 10.sp) }
        }
    }
}
