package app.pocketshell.widget.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.probe.ServerInfo

/**
 * THE MAXIMIZED MODE — a task manager, not a stretched card.
 *
 * Same visual language as the rest of PocketShell (and the Git Studio):
 * flat, hairline-ruled, mono small-caps, accent text, a rule — not a pill —
 * marking the active item. What differs from the card is the product shape:
 * a title bar with the live summary, four sections behind real navigation
 * (a bottom bar on a phone, a side rail on a wide page), and a list/detail
 * split (two panes when wide).
 *
 *   OVERVIEW    the numbers, the device, what outlives the terminal, top memory/CPU
 *   SERVERS     listening endpoints: HTTP checks, response history, process facts
 *   BACKGROUND  detached / nohup processes — Gradle daemons, watchers, jobs
 *   SESSIONS    open terminals and tmux/screen keepers
 *
 * Presentation only: every number comes from [ServersState]'s scans and the
 * only mutation is the confirm-gated signal pipeline.
 */
internal enum class StudioTab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Outlined.Dashboard),
    SERVERS("Servers", Icons.Outlined.Dns),
    BACKGROUND("Background", Icons.Outlined.Memory),
    SESSIONS("Sessions", Icons.Outlined.Terminal),
}

@Composable
internal fun ServersStudio(
    state: ServersState,
    layout: ServersLayout,
    maxWidthDp: Float,
    servers: List<ServerInfo>,
    actions: ServersActions,
) {
    val rail = ServersLayout.usesSideRail(maxWidthDp)
    val procs = state.procView
    val nowMs = state.procAtMs

    fun selectTab(tab: StudioTab) {
        state.tab = tab
        state.clearSelection()
    }

    // Open a process wherever it lives: a listener on SERVERS, a session leader
    // on SESSIONS, a background process on BACKGROUND, anything else under its session.
    fun openEntry(entry: ProcEntry) {
        val port = entry.ports.firstOrNull()
        when {
            entry.kind == ProcKind.SERVER && port != null && servers.any { it.port == port } -> {
                state.tab = StudioTab.SERVERS
                state.selectedPid = null
                state.selectedPort = port
            }
            procs.background.any { it.pid == entry.pid } -> {
                state.tab = StudioTab.BACKGROUND
                state.selectedPort = null
                state.selectedPid = entry.pid
            }
            else -> {
                state.tab = StudioTab.SESSIONS
                state.selectedPort = null
                state.selectedPid = entry.pid
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StudioTopBar(state, servers, procs, actions)
        Rule()
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (rail) {
                StudioRail(state.tab, ::selectTab)
                Box(Modifier.width(1.dp).fillMaxHeight().background(HomeTokens.hairline))
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(start = if (rail) 12.dp else 0.dp),
            ) {
                val twoPane = ServersLayout.twoPane(maxWidth.value + if (rail) 100f else 0f)
                val selectedServer = state.selectedPort?.let { port -> servers.firstOrNull { it.port == port } }
                val selectedEntry = state.selectedPid?.let { procs.byPid[it] }
                when (state.tab) {
                    StudioTab.OVERVIEW -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        OverviewBody(
                            servers = servers,
                            procs = procs,
                            system = state.system,
                            nowMs = nowMs,
                            onTab = ::selectTab,
                            onOpenEntry = ::openEntry,
                        )
                        Spacer(Modifier.height(16.dp))
                        StateFooter(state)
                    }

                    StudioTab.SERVERS -> ListDetail(
                        twoPane = twoPane,
                        hasDetail = selectedServer != null,
                        placeholder = "Select a server to inspect its endpoint, response history and process.",
                        list = {
                            Dashboard(
                                ui = state.ui,
                                layout = layout,
                                servers = servers,
                                httpByPort = state.httpByPort,
                                beatsByPort = state.beatsByPort,
                                paused = state.paused,
                                query = state.query,
                                sort = state.sort,
                                lastScanAtMs = state.lastScanAtMs,
                                selectedPort = selectedServer?.port,
                                onQueryChange = { state.query = it },
                                onSortChange = { state.sort = it },
                                onSelect = { state.selectedPort = it },
                                onOpenLinuxShell = actions.onOpenLinuxShell,
                                onOpenDiagnostics = actions.onOpenDiagnostics,
                            )
                        },
                        detail = {
                            if (selectedServer != null) {
                                ServerDetail(
                                    server = selectedServer,
                                    layout = layout,
                                    http = state.httpByPort[selectedServer.port],
                                    beats = state.beatsByPort[selectedServer.port]?.beats().orEmpty(),
                                    facts = state.factsByPort[selectedServer.port],
                                    proc = selectedServer.pid?.let { procs.byPid[it] },
                                    onBack = { state.selectedPort = null },
                                    onOpenTerminal = actions.onOpenTerminal,
                                    onOpenCompanion = { actions.onOpenCompanion(selectedServer.port) },
                                    onSignal = { actions.onSignalServer(selectedServer, it) },
                                )
                            }
                        },
                    )

                    StudioTab.BACKGROUND -> ListDetail(
                        twoPane = twoPane,
                        hasDetail = selectedEntry != null,
                        placeholder = "Select a process to see its argv, memory, and whether it outlives the terminal.",
                        list = {
                            BackgroundList(
                                procs = procs,
                                nowMs = nowMs,
                                query = state.bgQuery,
                                sort = state.bgSort,
                                selectedPid = selectedEntry?.pid,
                                onQueryChange = { state.bgQuery = it },
                                onSortChange = { state.bgSort = it },
                                onOpen = { state.selectedPid = it.pid },
                            )
                        },
                        detail = { ProcDetailFor(selectedEntry, procs, nowMs, "Background", state, actions, ::openEntry) },
                    )

                    StudioTab.SESSIONS -> ListDetail(
                        twoPane = twoPane,
                        hasDetail = selectedEntry != null,
                        placeholder = "Select a session to see what runs below it.",
                        list = {
                            SessionsList(
                                procs = procs,
                                nowMs = nowMs,
                                selectedPid = selectedEntry?.pid,
                                onOpen = { state.selectedPid = it.pid },
                            )
                        },
                        detail = { ProcDetailFor(selectedEntry, procs, nowMs, "Sessions", state, actions, ::openEntry) },
                    )
                }
            }
        }
        if (!rail) StudioBottomBar(state.tab, ::selectTab)
    }
}

@Composable
private fun ProcDetailFor(
    entry: ProcEntry?,
    procs: ProcView,
    nowMs: Long,
    backLabel: String,
    state: ServersState,
    actions: ServersActions,
    onOpenEntry: (ProcEntry) -> Unit,
) {
    if (entry == null) return
    ProcDetail(
        entry = entry,
        procs = procs,
        nowMs = nowMs,
        backLabel = backLabel,
        onBack = { state.selectedPid = null },
        onOpenTerminal = actions.onOpenTerminal,
        onSignal = { actions.onSignalProcess(entry, it) },
        onOpenEntry = onOpenEntry,
    )
}

/** List on the left and detail on the right when wide; one at a time on a phone. */
@Composable
private fun ListDetail(
    twoPane: Boolean,
    hasDetail: Boolean,
    placeholder: String,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    if (twoPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { list() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(HomeTokens.hairline))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp)) {
                if (hasDetail) detail() else DetailPlaceholder(placeholder)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (hasDetail) detail() else list()
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ----------------------------------------------------------------- chrome

@Composable
private fun StudioIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = HomeTokens.accent, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun StudioTopBar(state: ServersState, servers: List<ServerInfo>, procs: ProcView, actions: ServersActions) {
    val (up, bg, ses) = CardRows.counts(servers, procs)
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(bottom = 6.dp)) {
            Text(
                text = "Servers",
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
            )
            val facts = listOfNotNull(
                ProcPresentation.summary(up, bg, ses).takeIf { state.ui is ServersUi.Ready },
                if (state.paused) "paused" else null,
            )
            if (facts.isNotEmpty()) MonoText(text = facts.joinToString(" · "), color = HomeTokens.textDim, fontSize = 11)
        }
        StudioIconButton(
            if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
            if (state.paused) "Resume auto-refresh" else "Pause auto-refresh",
            actions.onTogglePause,
        )
        StudioIconButton(Icons.Outlined.Refresh, "Refresh now", actions.onRefresh)
        StudioIconButton(Icons.Outlined.Terminal, "Open terminal", actions.onOpenTerminal)
    }
}

/** The honest state line + checked-at stamp, at the foot of the overview. */
@Composable
private fun StateFooter(state: ServersState) {
    val ageSec = if (state.lastScanAtMs == 0L) null else (System.currentTimeMillis() - state.lastScanAtMs) / 1000.0
    val line = when {
        state.ui is ServersUi.Probing -> "Looking…"
        state.ui is ServersUi.Unavailable -> "Linux not ready"
        state.ui is ServersUi.ProbeUnavailable -> "Probes unavailable"
        state.paused -> "Paused — showing the last scan"
        else -> "Checked " + (ageSec?.let { ServersPresentation.formatUptime(it) } ?: "—") + " ago"
    }
    MonoText(text = line, color = HomeTokens.textDim, fontSize = 10)
}

/** The active item carries a 2dp accent rule (top on the bar, left on the rail) — never a pill. */
@Composable
private fun NavItem(tab: StudioTab, selected: Boolean, ruleOnTop: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (selected) HomeTokens.accent else HomeTokens.textDim
    Box(
        modifier = modifier
            .heightIn(min = 60.dp)
            .clickable(role = Role.Tab, onClickLabel = "Show ${tab.label}") { onClick() },
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(if (ruleOnTop) Alignment.TopCenter else Alignment.CenterStart)
                    .then(if (ruleOnTop) Modifier.fillMaxWidth().height(2.dp) else Modifier.fillMaxHeight().width(3.dp))
                    .background(HomeTokens.accent),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(imageVector = tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            MonoText(
                text = tab.label.uppercase(),
                color = tint,
                fontSize = 9,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun StudioBottomBar(tab: StudioTab, onTab: (StudioTab) -> Unit) {
    Column {
        Rule()
        Row(modifier = Modifier.fillMaxWidth()) {
            StudioTab.entries.forEach { t -> NavItem(t, t == tab, ruleOnTop = true, modifier = Modifier.weight(1f)) { onTab(t) } }
        }
    }
}

@Composable
private fun StudioRail(tab: StudioTab, onTab: (StudioTab) -> Unit) {
    Column(modifier = Modifier.width(88.dp).fillMaxHeight()) {
        StudioTab.entries.forEach { t -> NavItem(t, t == tab, ruleOnTop = false, modifier = Modifier.fillMaxWidth()) { onTab(t) } }
    }
}
