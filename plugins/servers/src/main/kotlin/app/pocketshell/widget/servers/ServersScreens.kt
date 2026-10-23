package app.pocketshell.widget.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.probe.ServerInfo
import kotlinx.coroutines.delay

/**
 * The Servers monitor's screens: ONE composition, three information
 * hierarchies. The card is the at-a-glance surface (COMPACT/ROOMY); the
 * maximized page is the monitor — dashboard and detail, two-pane on a
 * wide page. Every state the user can see is a fact one of the layers
 * produced; every honest state is stated, never substituted.
 */

/** Everything the screens may ask the host or the state to do — lambdas only, no state. */
internal class ServersActions(
    val onRefresh: () -> Unit,
    val onTogglePause: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onOpenLinuxShell: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onOpenCompanion: (Int) -> Unit,
    val onSignalServer: (ServerInfo, ServersOps.Signal) -> Unit,
    val onSignalProcess: (ProcEntry, ServersOps.Signal) -> Unit,
)

/** The whole application: the card (COMPACT/ROOMY) or the maximized task manager (FULL). */
@Composable
internal fun ServersScreens(
    state: ServersState,
    layout: ServersLayout,
    maxWidthDp: Float,
    actions: ServersActions,
) {
    val servers = (state.ui as? ServersUi.Ready)?.servers.orEmpty()
    when {
        layout.isMonitor -> ServersStudio(
            state = state,
            layout = layout,
            maxWidthDp = maxWidthDp,
            servers = servers,
            actions = actions,
        )
        else -> {
            val selectedServer = state.selectedPort?.let { port -> servers.firstOrNull { it.port == port } }
            val selectedEntry = state.selectedPid?.let { state.procView.byPid[it] }
            when {
                // The CARD: overview or an in-card detail — the one-card pattern.
                selectedServer != null -> CardDetail(
                    server = selectedServer,
                    proc = selectedServer.pid?.let { state.procView.byPid[it] },
                    nowMs = state.procAtMs,
                    onBack = { state.selectedPort = null },
                    onOpenTerminal = actions.onOpenTerminal,
                    onOpenCompanion = { actions.onOpenCompanion(selectedServer.port) },
                )
                selectedEntry != null -> CardProcDetail(
                    entry = selectedEntry,
                    procs = state.procView,
                    nowMs = state.procAtMs,
                    onBack = { state.selectedPid = null },
                    onOpenTerminal = actions.onOpenTerminal,
                )
                else -> CardOverview(
                    ui = state.ui,
                    layout = layout,
                    servers = servers,
                    procs = state.procView,
                    nowMs = state.procAtMs,
                    httpByPort = state.httpByPort,
                    beatsByPort = state.beatsByPort,
                    onOpenServer = { state.selectedPort = it.port },
                    onOpenProcess = { state.selectedPid = it.pid },
                    onOpenLinuxShell = actions.onOpenLinuxShell,
                    onOpenDiagnostics = actions.onOpenDiagnostics,
                    cleanupLine = CleanupText.cardLine(state.candidates),
                )
            }
        }
    }
}

// ------------------------------------------------------------- dashboard

/** The SERVERS tab's list: search, sort, the endpoint table, the honest state line. */
@Composable
internal fun Dashboard(
    ui: ServersUi,
    layout: ServersLayout,
    servers: List<ServerInfo>,
    httpByPort: Map<Int, HttpResult>,
    beatsByPort: Map<Int, BeatHistory>,
    paused: Boolean,
    query: String,
    sort: SortMode,
    lastScanAtMs: Long,
    selectedPort: Int?,
    onQueryChange: (String) -> Unit,
    onSortChange: (SortMode) -> Unit,
    onSelect: (Int) -> Unit,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search + sort — the maximized dashboard's controls.
        if (layout.showsSearch) {
            SearchField(value = query, onChange = onQueryChange)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SortMode.entries.forEach { mode ->
                    val active = mode == sort
                    MonoText(
                        text = mode.label,
                        color = if (active) HomeTokens.accent else HomeTokens.textDim,
                        fontSize = 10,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClickLabel = "Sort by ${mode.label}") {
                                onSortChange(mode)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        // Rows — the filtered, sorted endpoint table.
        val visible = ServersPresentation.sort(
            servers = ServersPresentation.filter(servers, query),
            mode = sort,
            lastLatencyMs = { port -> (httpByPort[port] as? HttpResult.Answered)?.latencyMs },
            lastOk = { port ->
                when (val r = httpByPort[port]) {
                    null -> null
                    is HttpResult.Answered -> when (HttpChecker.band(r.code)) {
                        HttpBand.OK, HttpBand.REDIRECT -> true
                        else -> false
                    }
                    is HttpResult.Failed -> false
                }
            },
        )
        if (visible.isNotEmpty()) {
            visible.forEachIndexed { index, server ->
                ServerRow(
                    server = server,
                    layout = layout,
                    http = httpByPort[server.port],
                    beats = beatsByPort[server.port]?.beats().orEmpty(),
                    selected = server.port == selectedPort,
                    onOpen = { onSelect(server.port) },
                )
                if (index != visible.lastIndex) {
                    HorizontalDivider(color = HomeTokens.hairline.copy(alpha = 0.6f))
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            MonoText(
                text = when {
                    servers.isEmpty() -> "Nothing listening"
                    else -> "No server matches “${query.trim()}”"
                },
                color = HomeTokens.textDim,
                fontSize = 11,
            )
        }

        Spacer(Modifier.height(10.dp))
        // The honest state line + the checked-at stamp.
        val ageSec = if (lastScanAtMs == 0L) null else (System.currentTimeMillis() - lastScanAtMs) / 1000.0
        val stateLine = when {
            ui is ServersUi.Probing -> "Looking…"
            ui is ServersUi.Unavailable -> "Linux not ready"
            ui is ServersUi.ProbeUnavailable -> "Probes unavailable"
            servers.isEmpty() -> "Nothing listening"
            paused -> "Paused — showing the last scan"
            else -> "Local listeners · checked " +
                (ageSec?.let { ServersPresentation.formatUptime(it) } ?: "—") + " ago"
        }
        MonoText(
            text = stateLine,
            color = if (servers.isNotEmpty() && ui is ServersUi.Ready) HomeTokens.accent else HomeTokens.textDim,
            fontSize = 10,
        )
        if (ui is ServersUi.Ready && servers.isEmpty()) {
            Text(
                text = "Start a server in a terminal — it appears here when it listens.",
                style = MaterialTheme.typography.bodySmall,
                color = HomeTokens.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
            ActionButton("OPEN LINUX", onOpenLinuxShell)
        }
        if (ui is ServersUi.Unavailable || ui is ServersUi.ProbeUnavailable) {
            ActionButton("DIAGNOSTICS", onOpenDiagnostics)
        }
    }
}

/** One endpoint row — the density adapts, the facts never shrink to lies. */
@Composable
private fun ServerRow(
    server: ServerInfo,
    layout: ServersLayout,
    http: HttpResult?,
    beats: List<BeatHistory.Beat>,
    selected: Boolean,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Server details :${server.port}") { onOpen() }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok = http !is HttpResult.Failed)
            Spacer(Modifier.width(8.dp))
            MonoText(
                text = (if (selected) "▸ " else "") + ":${server.port}  ${server.displayName}",
                color = if (selected) HomeTokens.accent else HomeTokens.textPrimary,
                fontSize = 12,
            )
            Spacer(Modifier.weight(1f))
            if (layout.showsHttpChip) {
                HttpChip(http)
                Spacer(Modifier.width(8.dp))
            }
            MonoText(
                text = ServersPresentation.formatLatency((http as? HttpResult.Answered)?.latencyMs),
                color = HomeTokens.textDim,
                fontSize = 10,
            )
        }
        if (layout.showsCwd) server.cwd?.let { cwd ->
            val ctx = LocalContext.current
            MonoText(
                text = ServersPresentation.displayCwd(cwd, ctx),
                color = HomeTokens.textDim,
                fontSize = 10,
                modifier = Modifier.padding(start = 14.dp, top = 1.dp),
            )
        }
        if (layout.showsBeats && beats.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                BeatStrip(beats = beats)
                Spacer(Modifier.width(8.dp))
                MonoText(
                    text = "${beats.count { it.ok }}/${beats.size} up",
                    color = HomeTokens.textDim,
                    fontSize = 9,
                )
            }
        }
    }
}

/** The HTTP status chip: the answered code in its band's color, or the failure. */
@Composable
internal fun HttpChip(http: HttpResult?) {
    val (text, color) = when (http) {
        null -> "—" to HomeTokens.textDim
        is HttpResult.Answered -> when (HttpChecker.band(http.code)) {
            HttpBand.OK -> "${http.code}" to HomeTokens.runningGreen
            HttpBand.REDIRECT -> "${http.code}" to HomeTokens.accent
            HttpBand.CLIENT_ERROR -> "${http.code}" to HomeTokens.accentBright
            HttpBand.SERVER_ERROR, null -> "${http.code}" to HomeTokens.danger
        }
        is HttpResult.Failed -> "ERR" to HomeTokens.danger
    }
    MonoText(
        text = text,
        color = color,
        fontSize = 10,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------- detail

@Composable
internal fun ServerDetail(
    server: ServerInfo,
    layout: ServersLayout,
    http: HttpResult?,
    beats: List<BeatHistory.Beat>,
    facts: ProcessFacts?,
    /** The process record for this listener's pid — carries the survive-the-terminal fact. */
    proc: ProcEntry?,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenCompanion: () -> Unit,
    onSignal: (ServersOps.Signal) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BackHeader(onBack = onBack)
        Spacer(Modifier.height(6.dp))
        MonoText(
            text = "${server.displayName} :${server.port}",
            fontSize = 16,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok = http !is HttpResult.Failed)
            Spacer(Modifier.width(8.dp))
            MonoText(
                text = when (http) {
                    is HttpResult.Answered -> "HTTP ${http.code} · ${ServersPresentation.formatLatency(http.latencyMs)}"
                    is HttpResult.Failed -> "NO ANSWER — ${http.reason}"
                    null -> "RUNNING (verified by connect)"
                },
                color = if (http is HttpResult.Failed) HomeTokens.danger else HomeTokens.runningGreen,
                fontSize = 10,
            )
        }
        Spacer(Modifier.height(6.dp))
        CopyUrlRow(port = server.port)
        (http as? HttpResult.Answered)?.let { answered ->
            Spacer(Modifier.height(4.dp))
            answered.server?.let { KeyValue("SERVER", it) }
            answered.contentType?.let { KeyValue("TYPE", it) }
        }

        // The session's response history — what a status page calls beats.
        if (beats.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SectionLabel("RESPONSES · THIS SESSION")
            Spacer(Modifier.height(4.dp))
            BeatStrip(beats = beats, barWidth = 4, maxBarHeight = 14)
            Spacer(Modifier.height(4.dp))
            val okCount = beats.count { it.ok }
            MonoText(
                text = "$okCount/${beats.size} checks answered OK · " +
                    ServersPresentation.formatFraction(okCount.toFloat() / beats.size) + " uptime",
                color = HomeTokens.textDim,
                fontSize = 10,
            )
        }

        // The htop-lite facts: the attributed owner, live.
        if (layout.showsProcessFacts) {
            Spacer(Modifier.height(10.dp))
            SectionLabel("PROCESS")
            Spacer(Modifier.height(4.dp))
            if (facts != null) {
                KeyValue("PID", facts.pid.toString())
                KeyValue("CPU", ServersPresentation.formatCpu(facts.cpuPercent))
                KeyValue("RSS", ServersPresentation.formatBytes(facts.rssBytes))
                facts.argv?.let { KeyValue("ARGV", it, maxLines = 6) }
            } else {
                MonoText(
                    text = server.pid?.let { "PID $it" } ?: "owner unknown",
                    color = HomeTokens.textDim,
                    fontSize = 10,
                )
            }
            proc?.let { p ->
                KeyValue(
                    "SURVIVES",
                    if (p.survival == Survival.ATTACHED) "NO — ${p.survival.explanation}" else "YES — ${p.survival.explanation}",
                    maxLines = 3,
                    valueColor = if (p.survival == Survival.ATTACHED) HomeTokens.textPrimary else HomeTokens.accent,
                )
                ProcPresentation.memorySplit(p)?.let { KeyValue("SWAP", it, maxLines = 2) }
            }
            server.cwd?.let { cwd ->
                KeyValue("CWD", ServersPresentation.displayCwd(cwd, LocalContext.current))
            }
            MonoText(
                text = when (server.attribution) {
                    ServerInfo.Attribution.ACCEPTOR_MATCH ->
                        "verified by connecting; this process accepted the connection"
                    ServerInfo.Attribution.CMDLINE_MATCH ->
                        "verified by connecting; this process names the port and owns sockets"
                    ServerInfo.Attribution.TABLE_MATCH ->
                        "verified by connecting; this process owns the listening socket"
                },
                color = HomeTokens.textDim,
                fontSize = 9,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row {
            ActionButton("TERMINAL", onOpenTerminal)
            Spacer(Modifier.width(8.dp))
            ActionButton("COMPANION", onOpenCompanion)
            if (layout.showsStopAction) {
                Spacer(Modifier.width(8.dp))
                ActionButton("STOP") { onSignal(ServersOps.Signal.TERM) }
                Spacer(Modifier.width(8.dp))
                ActionButton("FORCE KILL") { onSignal(ServersOps.Signal.KILL) }
            }
        }
    }
}

/** The endpoint URL with an in-place COPY affordance (clipboard + a stamp). */
@Composable
private fun CopyUrlRow(port: Int) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        MonoText(text = "http://127.0.0.1:$port/", color = HomeTokens.textPrimary, fontSize = 11)
        Spacer(Modifier.width(8.dp))
        MonoText(
            text = if (copied) "COPIED" else "COPY",
            color = if (copied) HomeTokens.runningGreen else HomeTokens.accent,
            fontSize = 10,
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Copy URL") {
                clipboard.setText(AnnotatedString("http://127.0.0.1:$port/"))
                copied = true
            },
        )
    }
}

/** The maximized dashboard's idle pane — what the detail side is for. */
@Composable
internal fun DetailPlaceholder(text: String = "Select a server to inspect its endpoint, response history and process.") {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionLabel("DETAIL")
        Spacer(Modifier.height(6.dp))
        MonoText(
            text = text,
            color = HomeTokens.textDim,
            fontSize = 11,
        )
    }
}
