package app.pocketshell.widget.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.probe.ServerInfo

/**
 * The MINI mode: the card (COMPACT / ROOMY). It answers "what is running
 * right now" in one glance and never needs scrolling to get the answer:
 *
 *   Servers                                  2 up · 3 bg · 1 ses
 *   ● :3000  node server.js
 *   ▪ tmux                                      4h 2m
 *   ○ Gradle daemon 8.14.5                      1.2 GB
 *   4 OUTLIVE THE TERMINAL
 *
 * A filled dot is a server, a square is a session keeper, a ring is a
 * background process (KindDot). ROOMY adds each row's cwd and survival,
 * the HTTP chip and the response-beat strip. A tap opens an in-card
 * detail (facts + TERMINAL); stop / force-kill stay in the maximized tier.
 */

/** One line on the card — a listening server, or a guest process. */
internal sealed interface CardRow {
    data class Srv(val server: ServerInfo) : CardRow

    /** [jobs] is how many processes sit below a session keeper (null otherwise). */
    data class Proc(val entry: ProcEntry, val jobs: Int? = null) : CardRow
}

internal object CardRows {

    /** Listeners owned by build daemons / system helpers / keepers are IPC noise, not "my servers". */
    private val NOT_DEV_SERVER = setOf(ProcKind.BUILD, ProcKind.SYSTEM, ProcKind.MUX)

    fun devServers(servers: List<ServerInfo>, procs: ProcView): List<ServerInfo> =
        servers.filter { s ->
            val kind = s.pid?.let { procs.kindByPid[it] }
            kind == null || kind !in NOT_DEV_SERVER
        }.sortedBy { it.port }

    /**
     * Order is importance: servers, then session keepers (they hold work),
     * then background by memory footprint, system helpers last.
     */
    fun build(servers: List<ServerInfo>, procs: ProcView): List<CardRow> {
        val keepers = procs.sessions.filter { it.keeper }
        val (system, background) = procs.background.partition { it.kind == ProcKind.SYSTEM }
        return devServers(servers, procs).map { CardRow.Srv(it) } +
            keepers.map { CardRow.Proc(it.leader, jobs = it.descendants.size) } +
            background.sortedByDescending { it.rec.footprintBytes }.map { CardRow.Proc(it) } +
            system.sortedByDescending { it.rec.footprintBytes }.map { CardRow.Proc(it) }
    }

    private const val HEADER_DP = 34f
    private const val FOOTER_DP = 44f

    /**
     * Row height with its padding and divider: one text line when COMPACT
     * (6 + 16 + 6 + 1), name + cwd/survival + the beat strip when ROOMY. The
     * footer line also carries "+N more", so it needs no extra height.
     */
    fun rowDp(layout: ServersLayout): Float = if (layout.showsCwd) 52f else 30f

    /**
     * How many rows fit the card's real height — a card is not a fixed size,
     * and a clipped footer would hide the one line that matters. Always at
     * least one row, never more than the tier's cap.
     */
    fun rowsThatFit(heightDp: Float, layout: ServersLayout, extraFooterDp: Float = 0f): Int =
        ((heightDp - HEADER_DP - FOOTER_DP - extraFooterDp) / rowDp(layout)).toInt().coerceIn(1, layout.cardRows)

    /** The cleanup nudge line, when the card shows one, takes this much of the footer. */
    const val NUDGE_DP = 18f

    /** (servers up, background, sessions) — the headline counts. */
    fun counts(servers: List<ServerInfo>, procs: ProcView): Triple<Int, Int, Int> =
        Triple(devServers(servers, procs).size, procs.background.size, procs.sessions.size)
}

@Composable
internal fun CardOverview(
    ui: ServersUi,
    layout: ServersLayout,
    servers: List<ServerInfo>,
    procs: ProcView,
    nowMs: Long,
    httpByPort: Map<Int, HttpResult>,
    beatsByPort: Map<Int, BeatHistory>,
    onOpenServer: (ServerInfo) -> Unit,
    onOpenProcess: (ProcEntry) -> Unit,
    onOpenLinuxShell: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    /** The cleanup's one-line nudge ("2 IDLE · 1.4 GB CAN BE STOPPED"); null when there is none. */
    cleanupLine: String? = null,
) {
    val rows = CardRows.build(servers, procs)
    val (up, bg, ses) = CardRows.counts(servers, procs)
    val outlive = procs.outlive.size

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val shown = rows.take(CardRows.rowsThatFit(maxHeight.value, layout, if (cleanupLine != null) CardRows.NUDGE_DP else 0f))
    val hidden = rows.size - shown.size

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = "Processes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (rows.isNotEmpty()) {
                MonoText(text = ProcPresentation.summary(up, bg, ses), color = HomeTokens.textDim, fontSize = 11)
            }
        }
        Spacer(Modifier.height(6.dp))

        shown.forEachIndexed { index, row ->
            when (row) {
                is CardRow.Srv -> CardServerRow(row.server, layout, httpByPort, beatsByPort) { onOpenServer(row.server) }
                is CardRow.Proc -> CardProcRow(row, layout, nowMs) { onOpenProcess(row.entry) }
            }
            if (index != shown.lastIndex) HorizontalDivider(color = HomeTokens.hairline.copy(alpha = 0.6f))
        }
        if (rows.isEmpty()) Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(4.dp))

        val stateLine = when {
            ui is ServersUi.Probing -> "Looking…"
            ui is ServersUi.Unavailable -> "Linux not ready"
            ui is ServersUi.ProbeUnavailable -> "Probes unavailable"
            rows.isEmpty() -> "Nothing running"
            outlive > 0 -> "$outlive OUTLIVE THE TERMINAL"
            else -> "ALL TIED TO AN OPEN TERMINAL"
        } + if (hidden > 0) " · +$hidden more" else ""
        MonoText(
            text = stateLine,
            color = if (ui is ServersUi.Ready && outlive > 0) HomeTokens.accent else HomeTokens.textDim,
            fontSize = 11,
        )
        if (cleanupLine != null) {
            MonoText(text = cleanupLine, color = HomeTokens.accent, fontSize = 11, modifier = Modifier.padding(top = 2.dp))
        }
        if (ui is ServersUi.Ready && rows.isEmpty()) {
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
}

@Composable
private fun CardServerRow(
    server: ServerInfo,
    layout: ServersLayout,
    httpByPort: Map<Int, HttpResult>,
    beatsByPort: Map<Int, BeatHistory>,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Server details :${server.port}") { onOpen() }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindDot(ProcKind.SERVER, ok = httpByPort[server.port] !is HttpResult.Failed)
            Spacer(Modifier.width(8.dp))
            MonoText(text = ":${server.port}  ${server.displayName}", modifier = Modifier.weight(1f, fill = false))
            if (layout.showsHttpChip) {
                Spacer(Modifier.weight(1f))
                HttpChip(httpByPort[server.port])
            }
        }
        if (layout.showsCwd) server.cwd?.let { cwd ->
            MonoText(
                text = ServersPresentation.displayCwd(cwd, LocalContext.current),
                color = HomeTokens.textDim,
                fontSize = 10,
                modifier = Modifier.padding(start = 16.dp, top = 1.dp),
            )
        }
        if (layout.showsBeats) {
            val beats = beatsByPort[server.port]?.beats().orEmpty()
            if (beats.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                BeatStrip(beats = beats)
            }
        }
    }
}

@Composable
private fun CardProcRow(row: CardRow.Proc, layout: ServersLayout, nowMs: Long, onOpen: () -> Unit) {
    val e = row.entry
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Process details ${e.label}") { onOpen() }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindDot(e.kind)
            Spacer(Modifier.width(8.dp))
            MonoText(text = e.label, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            MonoText(
                text = when {
                    row.jobs != null -> if (layout.showsCwd) "${row.jobs} below · ${ProcPresentation.age(e.rec, nowMs)}"
                    else ProcPresentation.age(e.rec, nowMs)
                    layout.showsCwd -> ProcPresentation.facts(e, nowMs)
                    else -> ProcPresentation.memory(e)
                },
                color = HomeTokens.textDim,
                fontSize = 10,
            )
        }
        if (layout.showsCwd) {
            val detail = listOfNotNull(
                if (e.survival != Survival.ATTACHED) e.survival.label.lowercase() else null,
                e.rec.cwd,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                MonoText(text = detail, color = HomeTokens.textDim, fontSize = 10, modifier = Modifier.padding(start = 16.dp, top = 1.dp))
            }
        }
    }
}

/** The in-card detail of a listening server. */
@Composable
internal fun CardDetail(
    server: ServerInfo,
    proc: ProcEntry?,
    nowMs: Long,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenCompanion: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BackHeader(onBack = onBack)
        MonoText(
            text = "${server.displayName} :${server.port}",
            fontSize = 16,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok = true)
            Spacer(Modifier.width(8.dp))
            MonoText(text = "RUNNING", color = HomeTokens.runningGreen, fontSize = 10)
        }
        Spacer(Modifier.height(4.dp))
        MonoText(text = "127.0.0.1:${server.port}", fontSize = 12)
        server.cwd?.let { cwd ->
            MonoText(
                text = ServersPresentation.displayCwd(cwd, LocalContext.current),
                color = HomeTokens.textDim,
                fontSize = 10,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (server.pid != null) {
            MonoText(
                text = "PID ${server.pid} · ${server.processName ?: "unknown"}",
                color = HomeTokens.textDim,
                fontSize = 10,
            )
        }
        proc?.let { p ->
            MonoText(
                text = (if (p.survival == Survival.ATTACHED) "ENDS WITH ITS TERMINAL" else "OUTLIVES THE TERMINAL") +
                    " · ${ProcPresentation.facts(p, nowMs)}",
                color = if (p.survival == Survival.ATTACHED) HomeTokens.textDim else HomeTokens.accent,
                fontSize = 10,
                modifier = Modifier.padding(top = 2.dp),
            )
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
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
        Row {
            ActionButton("TERMINAL", onOpenTerminal)
            Spacer(Modifier.width(8.dp))
            ActionButton("COMPANION", onOpenCompanion)
        }
    }
}

/** The in-card detail of a background process or session. */
@Composable
internal fun CardProcDetail(
    entry: ProcEntry,
    procs: ProcView,
    nowMs: Long,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BackHeader(onBack = onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindDot(entry.kind)
            Spacer(Modifier.width(8.dp))
            MonoText(text = entry.label, fontSize = 16, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.height(2.dp))
        MonoText(
            text = (if (entry.survival == Survival.ATTACHED) "ENDS WITH ITS TERMINAL" else "OUTLIVES THE TERMINAL") +
                " · ${entry.kind.label}",
            color = if (entry.survival == Survival.ATTACHED) HomeTokens.textDim else HomeTokens.accent,
            fontSize = 10,
        )
        Spacer(Modifier.height(6.dp))
        ProcFactRows(entry = entry, nowMs = nowMs, full = false)
        procs.sessions.firstOrNull { it.leader.pid == entry.pid }?.let { session ->
            session.foreground?.let { KeyValue("FOREGROUND", it) }
            KeyValue("BELOW", "${session.descendants.size} process${if (session.descendants.size == 1) "" else "es"}")
        }
        Spacer(Modifier.height(4.dp))
        Row { ActionButton("TERMINAL", onOpenTerminal) }
    }
}
