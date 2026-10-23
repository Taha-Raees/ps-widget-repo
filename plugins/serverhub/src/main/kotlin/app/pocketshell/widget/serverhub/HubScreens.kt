package app.pocketshell.widget.serverhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.WidgetNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The maximized page: three tabs. Pure presentation - every action is a call on [HubState]. */

internal val HubTab.spec: TabSpec
    get() = when (this) {
        HubTab.SERVICES -> TabSpec(name, label, Icons.Outlined.Dns)
        HubTab.PORTS -> TabSpec(name, label, Icons.Outlined.Cloud)
        HubTab.NEW -> TabSpec(name, label, Icons.Outlined.Add)
    }

internal fun statusLine(lanIp: String?, loaded: Boolean): String = when {
    !loaded -> "reading…"
    lanIp != null -> "Wi-Fi $lanIp"
    else -> "no Wi-Fi - local only"
}

internal fun summaryItems(services: List<Service>?, live: Map<String, Live>): List<StripItem> {
    val list = services.orEmpty()
    val running = list.count { live[it.id]?.phase == Phase.RUNNING }
    val up = list.count { live[it.id]?.health?.health.let { h -> h == Health.UP || h == Health.SLOW } }
    return listOf(
        StripItem("${list.size}", "services"),
        StripItem("$running", "running", hot = running > 0),
        StripItem("$up/${list.count { it.check != CheckKind.NONE }}", "healthy", hot = up > 0),
        StripItem("${list.count { it.wifi }}", "on wi-fi"),
    )
}

internal fun hintFor(services: List<Service>?): String? = when {
    services == null -> null
    services.isEmpty() -> "No services yet - open Server Hub and tap New to start a website or API."
    else -> null
}

private fun dotColor(l: Live): Color = when (l.phase) {
    Phase.RUNNING -> when (l.health?.health) {
        Health.DOWN, Health.ERROR -> HomeTokens.danger
        Health.SLOW -> HomeTokens.accent
        else -> HomeTokens.runningGreen
    }
    Phase.STARTING, Phase.STOPPING -> HomeTokens.accent
    Phase.CRASHED -> HomeTokens.danger
    Phase.STOPPED -> HomeTokens.hairline
}

@Composable
internal fun HubStudio(state: HubState, sideRail: Boolean, nav: WidgetNav) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var form by remember { mutableStateOf<FormRequest?>(null) }
    AppShell(
        title = HubApp.spec.name,
        subtitle = statusLine(state.lanIp, state.services != null),
        tabs = HubTab.entries.map { it.spec },
        selected = state.tab.name,
        onSelect = { key -> state.tab = HubTab.valueOf(key); state.detail = null },
        sideRail = sideRail,
        busy = state.loading || state.scanningPorts || state.live.values.any { it.phase == Phase.STARTING || it.phase == Phase.STOPPING || it.checking },
        banner = state.banner,
        onDismissBanner = { state.dismissBanner() },
        strip = summaryItems(state.services, state.live).mapIndexed { i, item -> item.copy(onClick = { state.tab = if (i == 3) HubTab.PORTS else HubTab.SERVICES; state.detail = null }) },
        onTerminal = { nav.openTerminal() },
        onRefresh = { state.reload() },
        refreshEnabled = !state.loading,
    ) {
        when (state.tab) {
            HubTab.SERVICES -> ServicesTab(state, nav, onForm = { form = it }, onConfirm = { confirm = it })
            HubTab.PORTS -> PortsTab(state, nav)
            HubTab.NEW -> NewTab(state, onForm = { form = it })
        }
    }
    form?.let { f -> ServiceFormDialog(f, state, onDismiss = { form = null }) }
    confirm?.let { c -> ConfirmDialog(c.title, c.message, c.label, c.word, onConfirm = c.action, onDismiss = { confirm = null }) }
}

/** What the form edits: an existing service, a new one from a template, or a blank custom one. */
internal data class FormRequest(val existing: Service?, val template: Template?)

// ================================================================ SERVICES

@Composable
private fun ServicesTab(state: HubState, nav: WidgetNav, onForm: (FormRequest) -> Unit, onConfirm: (Confirm) -> Unit) {
    val context = LocalContext.current
    val list = state.services
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionText("New", onClick = { state.tab = HubTab.NEW })
        ActionText("Check running", enabled = state.runningCount() > 0) { state.checkAll() }
        ActionText("Stop all", danger = true, enabled = state.runningCount() > 0) { state.stopAll() }
    }
    if (list == null) { MonoText("Reading…", color = HomeTokens.textDim, modifier = Modifier.padding(top = 8.dp)); return }
    if (list.isEmpty()) {
        MonoText("No services yet.", modifier = Modifier.padding(top = 12.dp))
        MonoText("Open NEW to start a website, a JSON API, Redis or your own command.", color = HomeTokens.textDim, maxLines = 3)
    }
    MonoText("Services run while PocketShell runs; a notification keeps them alive in the background and offers STOP.", color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
    list.forEach { s ->
        val l = state.liveOf(s.id)
        val open = state.detail == s.id
        val local = Links.local(s)
        val lan = Links.lan(s, state.lanIp)
        ListRow(
            dot = dotColor(l),
            title = s.name,
            right = if (l.phase == Phase.RUNNING && l.health != null && l.health.health != Health.UNKNOWN) "${l.phase.label} · ${l.health.health.label}" else l.phase.label,
            lines = listOfNotNull(
                (if (s.port != 0) ":${s.port}  " else "") + s.command,
                l.note,
                l.health?.takeIf { l.phase == Phase.RUNNING }?.let { h -> h.detail + (h.millis?.let { " · $it ms" } ?: "") },
                if (l.phase == Phase.RUNNING) local else null,
            ),
            onClick = { state.detail = if (open) null else s.id },
            lineColor = if (l.phase == Phase.CRASHED) HomeTokens.danger else HomeTokens.textDim,
        )
        if (!open) return@forEach
        val running = l.phase == Phase.RUNNING || l.phase == Phase.STARTING
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (running) ActionText("Stop") { state.stop(s.id) } else ActionText("Start", enabled = l.phase != Phase.STOPPING) { state.start(s.id) }
            ActionText("Restart", enabled = l.phase == Phase.RUNNING) { state.restart(s.id) }
            ActionText(if (l.checking) "Checking…" else "Check", enabled = l.phase == Phase.RUNNING && !l.checking) { state.check(s.id) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (local != null && Links.isWeb(s)) ActionText("Open", enabled = l.phase == Phase.RUNNING) { nav.openCompanion(local) }
            if (local != null) ActionText("Copy link") { copyToClipboard(context, "link", local); state.banner = Banner("Copied $local", false) }
            if (lan != null && s.wifi) ActionText("Copy Wi-Fi link") { copyToClipboard(context, "link", lan); state.banner = Banner("Copied $lan", false) }
        }
        if (s.wifi) {
            MonoText(
                when {
                    state.lanIp == null -> "Meant for the Wi-Fi, but there is no Wi-Fi address now."
                    l.wifiOk == true -> "Reachable from other devices at $lan - no password unless the service has one."
                    l.wifiOk == false -> "Not reachable on the Wi-Fi. The command must bind 0.0.0.0, not 127.0.0.1."
                    else -> "Tap CHECK to test the Wi-Fi link $lan."
                },
                color = if (l.wifiOk == false) HomeTokens.danger else HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ActionText(if (state.detail == "log:${s.id}") "Hide log" else "Log") { state.detail = if (state.detail == "log:${s.id}") s.id else "log:${s.id}" }
            ActionText("Edit", enabled = !running) { onForm(FormRequest(s, null)) }
            ActionText("Delete", danger = true) {
                onConfirm(Confirm("Delete ${s.name}?", "The service is stopped and removed from this list. Its folder and files stay.", "Delete") { state.delete(s.id) })
            }
        }
    }
    // The log of the service whose log is open sits at the end of the list so it can use the full width.
    val logId = state.detail?.removePrefix("log:")?.takeIf { state.detail?.startsWith("log:") == true }
    logId?.let { id -> state.serviceById(id)?.let { LogPanel(state, it) } }
}

@Composable
private fun LogPanel(state: HubState, s: Service) {
    var lines by remember(s.id) { mutableStateOf(state.logTail(s.id)) }
    var follow by remember(s.id) { mutableStateOf(false) }
    LaunchedEffect(s.id, follow) {
        lines = withContext(Dispatchers.IO) { state.logTail(s.id) }
        // Following is the one place a loop is allowed: it exists only while the log is open AND the user switched it on.
        while (follow) {
            delay(1_500)
            lines = withContext(Dispatchers.IO) { state.logTail(s.id) }
        }
    }
    SectionLabel("LOG · ${s.name}")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionText("Refresh") { lines = state.logTail(s.id) }
        ActionText(if (follow) "Following" else "Follow") { follow = !follow }
        ActionText("Clear", danger = true) { state.clearLog(s.id); lines = emptyList() }
    }
    if (lines.isEmpty()) MonoText("The log is empty. Output of the program appears here once it starts.", color = HomeTokens.textDim, maxLines = 3)
    lines.takeLast(80).forEach { line ->
        MonoText(
            line, fontSize = 10.sp, maxLines = 3,
            color = when (LogLines.level(line)) { LogLines.Level.ERROR -> HomeTokens.danger; LogLines.Level.WARN -> HomeTokens.accent; LogLines.Level.INFO -> HomeTokens.textPrimary },
        )
    }
}

// =================================================================== PORTS

@Composable
private fun PortsTab(state: HubState, nav: WidgetNav) {
    val context = LocalContext.current
    Row(Modifier.padding(top = 6.dp)) { ActionText(if (state.scanningPorts) "Scanning…" else "Scan ports", enabled = !state.scanningPorts) { state.scanPorts() } }
    val ports = state.ports
    if (ports == null) { MonoText("Lists everything listening inside Linux, and whether other devices on your Wi-Fi can reach it.", color = HomeTokens.textDim, maxLines = 3); return }
    if (ports.isEmpty()) MonoText("Nothing is listening.", color = HomeTokens.textDim, modifier = Modifier.padding(top = 8.dp))
    ports.forEach { r ->
        val mine = r.hubServiceId?.let { state.serviceById(it) }
        val open = state.detail == "port:${r.port}"
        ListRow(
            dot = if (r.wifi == true) HomeTokens.accent else HomeTokens.runningGreen,
            title = ":${r.port}  ${r.name}",
            right = if (mine != null) "hub" else null,
            lines = listOfNotNull(mine?.let { "service: ${it.name}" }, when (r.wifi) { true -> "reachable on your Wi-Fi"; false -> "this phone only"; null -> "no Wi-Fi to test" }, r.pid?.let { "pid $it" }),
            onClick = { state.detail = if (open) null else "port:${r.port}" },
        )
        if (open) Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ActionText("Open") { nav.openCompanion("http://127.0.0.1:${r.port}/") }
            ActionText("Copy link") { copyToClipboard(context, "link", "http://127.0.0.1:${r.port}/"); state.banner = Banner("Copied", false) }
            if (r.wifi == true && state.lanIp != null) ActionText("Copy Wi-Fi link") { copyToClipboard(context, "link", "http://${state.lanIp}:${r.port}/"); state.banner = Banner("Copied", false) }
        }
    }
    if (ports.any { it.wifi == true }) MonoText("Ports marked Wi-Fi accept connections from other devices on this network. Check that is what you want.", color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
}

// ====================================================================== NEW

@Composable
private fun NewTab(state: HubState, onForm: (FormRequest) -> Unit) {
    SectionLabel("START SOMETHING")
    MonoText("Pick a starting point. The folder and its starter files are created for you; everything binds to this phone only unless you choose Wi-Fi.", color = HomeTokens.textDim, maxLines = 3)
    Templates.all.forEach { t ->
        val ready = t.needs.all { state.files.hasProgram(it) }
        ListRow(
            dot = null, title = t.name, right = if (t.id == "custom") null else if (ready) "ready" else "needs ${t.packages.firstOrNull() ?: "a program"}",
            lines = listOf(t.summary), onClick = { onForm(FormRequest(null, t)) },
        )
    }
}

// ================================================================== the form

@Composable
private fun ServiceFormDialog(req: FormRequest, state: HubState, onDismiss: () -> Unit) {
    val t = req.template
    val ex = req.existing
    val fromTemplate = ex == null && t != null && t.id != "custom"
    var name by remember { mutableStateOf(ex?.name ?: t?.name?.takeIf { it != "Your own command" }.orEmpty()) }
    var command by remember { mutableStateOf(ex?.command.orEmpty()) }
    var dir by remember { mutableStateOf(ex?.cwd ?: t?.dir ?: "/root") }
    var port by remember { mutableStateOf((ex?.port ?: t?.port)?.takeIf { it != 0 }?.toString().orEmpty()) }
    var check by remember { mutableStateOf(ex?.check ?: t?.check ?: CheckKind.NONE) }
    var path by remember { mutableStateOf(ex?.path ?: t?.path ?: "/") }
    var restart by remember { mutableStateOf(ex?.restart ?: RestartMode.NEVER) }
    var wifi by remember { mutableStateOf(ex?.wifi ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    val missing = t?.takeIf { fromTemplate }?.let { tt -> tt.needs.filter { !state.files.hasProgram(it) } }.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ex != null) "Edit ${ex.name}" else t?.name ?: "New service") },
        text = {
            Column {
                Field("Name", name, { name = it.take(40) }, hint = "My site")
                if (!fromTemplate) Field("Command (runs without a shell)", command, { command = it }, hint = "python3 -m http.server 8000")
                Field("Folder", dir, { dir = it.trim() }, hint = "/root/site")
                Field("Port (empty = none)", port, { port = it.filter(Char::isDigit).take(5) }, number = true, hint = "8000")
                if (!fromTemplate) {
                    MonoText("CHECK", color = HomeTokens.textDim, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
                    Choices(CheckKind.entries.map { it.label to it }, check) { check = it }
                    if (check == CheckKind.HTTP) Field("Path", path, { path = it.trim() }, hint = "/")
                }
                MonoText("IF IT CRASHES, RESTART", color = HomeTokens.textDim, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
                Choices(RestartMode.entries.map { it.label to it }, restart) { restart = it }
                MonoText("SHOW THE WI-FI LINK", color = HomeTokens.textDim, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
                Choices(listOf("No" to false, "Yes" to true), wifi) { wifi = it }
                if (wifi) MonoText("Other devices can only connect if the command listens on 0.0.0.0. Nothing here adds a password.", color = HomeTokens.textDim, maxLines = 3)
                if (missing.isNotEmpty()) MonoText("Not installed: ${missing.joinToString(", ")}${t?.packages?.takeIf { it.isNotEmpty() }?.let { " - install: apk add ${it.joinToString(" ")}" }.orEmpty()}", color = HomeTokens.accent, maxLines = 4, modifier = Modifier.padding(top = 8.dp))
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val portNum = port.toIntOrNull() ?: 0
                if (fromTemplate && t != null) {
                    state.createFromTemplate(t, name, portNum, dir, wifi, restart) { err -> if (err == null) { onDismiss(); state.tab = HubTab.SERVICES } else error = err }
                } else {
                    val s = Service(ex?.id ?: Services.newId(), name.trim(), command.trim(), dir, portNum, if (portNum == 0) CheckKind.NONE else check, path.ifEmpty { "/" }, restart, wifi && portNum != 0)
                    val problem = Services.problem(s)
                    if (problem != null) error = problem else state.save(s) { err -> if (err == null) { onDismiss(); state.tab = HubTab.SERVICES } else error = err }
                }
            }) { Text(if (ex != null) "Save" else "Create", color = HomeTokens.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}
