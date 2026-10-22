package app.pocketshell.widget.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens

/**
 * The maximized task manager's process screens — BACKGROUND, SESSIONS, the
 * OVERVIEW's process panels and the process detail. Flat by design: hairline
 * rules, mono small-caps labels, accent text, a 3dp STATE RULE on a row's
 * left edge (accent = it outlives the terminal, danger = zombie/stopped) —
 * no filled cards, no rounded surfaces.
 */

// ----------------------------------------------------------- shared facts

/** The fact lines a process detail shares between the card and the maximized page. */
@Composable
internal fun ProcFactRows(entry: ProcEntry, nowMs: Long, full: Boolean) {
    val r = entry.rec
    KeyValue("PID", r.pid.toString())
    KeyValue("UPTIME", ProcPresentation.age(r, nowMs))
    KeyValue("CPU", ServersPresentation.formatCpu(entry.cpuPercent))
    KeyValue("MEMORY", ProcPresentation.memory(entry))
    ProcPresentation.memorySplit(entry)?.let { KeyValue("", it, maxLines = 2, valueColor = HomeTokens.textDim) }
    if (entry.ports.isNotEmpty()) KeyValue("LISTENS", entry.ports.joinToString(" ") { ":$it" })
    KeyValue(
        "SURVIVES",
        (if (entry.survival == Survival.ATTACHED) "NO — " else "YES — ") + entry.survival.explanation,
        maxLines = 3,
        valueColor = if (entry.survival == Survival.ATTACHED) HomeTokens.textPrimary else HomeTokens.accent,
    )
    if (entry.survival != Survival.ATTACHED && !entry.reparented) {
        KeyValue("NOTE", "still a child of pid ${r.ppid}; it is not daemonized, so it is not counted as outliving the terminal", maxLines = 3, valueColor = HomeTokens.textDim)
    }
    r.cwd?.let { KeyValue("CWD", it, maxLines = 2) }
    if (full) {
        KeyValue("PPID", r.ppid.toString())
        KeyValue("STATE", stateName(r.state))
        KeyValue("THREADS", r.threads.toString())
        KeyValue("TERMINAL", ProcPresentation.ttyName(r.tty))
        KeyValue("SESSION", r.sid.toString())
        r.argv?.let { KeyValue("ARGV", it, maxLines = 8) }
    }
}

private fun stateName(c: Char): String = when (c) {
    'R' -> "running"
    'S' -> "sleeping"
    'D' -> "waiting (disk)"
    'T', 't' -> "stopped"
    'Z' -> "zombie"
    'I' -> "idle"
    else -> c.toString()
}

// -------------------------------------------------------------------- rows

/** The state rule colour: accent = outlives the terminal, danger = zombie/stopped, else the hairline. */
@Composable
private fun ruleColor(e: ProcEntry) = when {
    e.rec.state == 'Z' || e.rec.state == 'T' -> HomeTokens.danger
    e.daemonized -> HomeTokens.accent
    else -> HomeTokens.hairline
}

@Composable
private fun RuledRow(rule: androidx.compose.ui.graphics.Color, selected: Boolean, onOpen: () -> Unit, label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable(role = Role.Button, onClickLabel = label) { onOpen() },
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) HomeTokens.accent else rule))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 8.dp)) { content() }
        }
        Rule()
    }
}

/** One background process: name + kind, the numbers, cwd + survival. */
@Composable
internal fun ProcRow(entry: ProcEntry, nowMs: Long, selected: Boolean, onOpen: () -> Unit) {
    RuledRow(ruleColor(entry), selected, onOpen, "Process details ${entry.label}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindDot(entry.kind)
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) HomeTokens.accent else HomeTokens.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            SectionLabel(entry.kind.label)
        }
        MonoText(
            text = listOfNotNull(
                "PID ${entry.pid}",
                ProcPresentation.memory(entry),
                ProcPresentation.age(entry.rec, nowMs),
                entry.cpuPercent?.let { "CPU ${ServersPresentation.formatCpu(it)}" },
            ).joinToString(" · "),
            color = HomeTokens.textDim,
            fontSize = 11,
            modifier = Modifier.padding(top = 4.dp),
        )
        ProcPresentation.memorySplit(entry)?.let { MonoText(text = it, color = HomeTokens.textDim, fontSize = 10) }
        Row(modifier = Modifier.padding(top = 2.dp)) {
            MonoText(text = entry.rec.cwd ?: "", color = HomeTokens.textDim, fontSize = 10, modifier = Modifier.weight(1f))
            if (entry.survival != Survival.ATTACHED) {
                MonoText(
                    text = if (entry.daemonized) "OUTLIVES TERMINAL" else entry.survival.label,
                    color = if (entry.daemonized) HomeTokens.accent else HomeTokens.textDim,
                    fontSize = 9,
                )
            }
        }
    }
}

/** One session: a terminal's shell or a session keeper. */
@Composable
internal fun SessionRow(session: Session, nowMs: Long, selected: Boolean, onOpen: () -> Unit) {
    val leader = session.leader
    RuledRow(if (session.keeper) HomeTokens.accent else HomeTokens.hairline, selected, onOpen, "Session details ${leader.label}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindDot(leader.kind)
            Spacer(Modifier.width(8.dp))
            Text(
                text = leader.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) HomeTokens.accent else HomeTokens.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            SectionLabel(if (session.keeper) "SURVIVES CLOSE" else ProcPresentation.ttyName(leader.rec.tty).uppercase())
        }
        MonoText(
            text = when {
                session.keeper -> "SESSION KEEPER · ${session.descendants.size} below"
                session.foreground != null -> "FOREGROUND  ${session.foreground}"
                else -> "${session.descendants.size} below"
            },
            color = if (session.keeper) HomeTokens.accent else HomeTokens.textPrimary,
            fontSize = 11,
            modifier = Modifier.padding(top = 4.dp),
        )
        MonoText(
            text = listOfNotNull(
                "PID ${leader.pid}",
                ProcPresentation.age(leader.rec, nowMs),
                ServersPresentation.formatBytes(session.descendants.sumOf { it.rec.footprintBytes } + leader.rec.footprintBytes),
                leader.rec.cwd,
            ).joinToString(" · "),
            color = HomeTokens.textDim,
            fontSize = 10,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ----------------------------------------------------------------- detail

/**
 * A process page: identity, the survive-the-terminal fact, every number,
 * the full argv, and (maximized) STOP / FORCE KILL. For a session leader it
 * also lists what runs below it.
 */
@Composable
internal fun ProcDetail(
    entry: ProcEntry,
    procs: ProcView,
    nowMs: Long,
    backLabel: String,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSignal: (ServersOps.Signal) -> Unit,
    onOpenEntry: (ProcEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Back to $backLabel") { onBack() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText(text = "←", color = HomeTokens.accent, fontSize = 14)
            Spacer(Modifier.width(8.dp))
            SectionLabel(backLabel.uppercase())
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindDot(entry.kind)
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.label,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = HomeTokens.textPrimary,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        MonoText(
            text = (if (entry.survival == Survival.ATTACHED) "ENDS WITH ITS TERMINAL" else "OUTLIVES THE TERMINAL") + " · ${entry.kind.label}",
            color = if (entry.survival == Survival.ATTACHED) HomeTokens.textDim else HomeTokens.accent,
            fontSize = 10,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Rule()
        Spacer(Modifier.height(8.dp))
        ProcFactRows(entry = entry, nowMs = nowMs, full = true)

        procs.sessions.firstOrNull { it.leader.pid == entry.pid }?.let { session ->
            session.foreground?.let { KeyValue("FOREGROUND", it, valueColor = HomeTokens.accent) }
            if (session.descendants.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("BELOW THIS ${if (session.keeper) "KEEPER" else "SHELL"} · ${session.descendants.size}")
                Spacer(Modifier.height(4.dp))
                session.descendants.forEach { child ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Open ${child.label}") { onOpenEntry(child) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindDot(child.kind)
                        Spacer(Modifier.width(8.dp))
                        MonoText(text = child.label, fontSize = 12, modifier = Modifier.weight(1f))
                        MonoText(
                            text = "${ProcPresentation.memory(child)} · ${ProcPresentation.age(child.rec, nowMs)}",
                            color = HomeTokens.textDim,
                            fontSize = 10,
                        )
                    }
                    Rule()
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            ActionButton("TERMINAL", onOpenTerminal)
            Spacer(Modifier.width(8.dp))
            ActionButton("STOP") { onSignal(ServersOps.Signal.TERM) }
            Spacer(Modifier.width(8.dp))
            ActionButton("FORCE KILL") { onSignal(ServersOps.Signal.KILL) }
        }
    }
}

// -------------------------------------------------------------- tab bodies

/** BACKGROUND: everything detached or nohup'd that is not a server or a session. */
@Composable
internal fun BackgroundList(
    procs: ProcView,
    nowMs: Long,
    query: String,
    sort: BgSort,
    selectedPid: Int?,
    onQueryChange: (String) -> Unit,
    onSortChange: (BgSort) -> Unit,
    onOpen: (ProcEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SearchField(value = query, onChange = onQueryChange, placeholder = "SEARCH NAME, PID, PATH, ARGV")
        SortRow(sort, onSortChange)
        val visible = ProcPresentation.sort(ProcPresentation.filter(procs.background, query), sort, nowMs)
        if (visible.isEmpty()) {
            MonoText(
                text = if (procs.background.isEmpty()) "No background processes" else "No process matches “${query.trim()}”",
                color = HomeTokens.textDim,
                fontSize = 11,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (procs.background.isEmpty()) {
                MonoText(
                    text = "Gradle daemons, watchers and anything started with nohup or setsid appear here.",
                    color = HomeTokens.textDim,
                    fontSize = 10,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        visible.forEach { entry -> ProcRow(entry, nowMs, entry.pid == selectedPid) { onOpen(entry) } }
    }
}

@Composable
private fun SortRow(sort: BgSort, onSortChange: (BgSort) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        BgSort.entries.forEach { mode ->
            MonoText(
                text = mode.label,
                color = if (mode == sort) HomeTokens.accent else HomeTokens.textDim,
                fontSize = 10,
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "Sort by ${mode.label}") { onSortChange(mode) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            )
        }
    }
}

/** SESSIONS: session keepers first (they survive), then open terminals. */
@Composable
internal fun SessionsList(
    procs: ProcView,
    nowMs: Long,
    selectedPid: Int?,
    onOpen: (ProcEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val keepers = procs.sessions.filter { it.keeper }
        val terminals = procs.sessions.filter { !it.keeper }
        if (procs.sessions.isEmpty()) {
            MonoText(text = "No sessions", color = HomeTokens.textDim, fontSize = 11, modifier = Modifier.padding(top = 12.dp))
            MonoText(
                text = "Open terminals and tmux/screen sessions appear here.",
                color = HomeTokens.textDim,
                fontSize = 10,
                maxLines = 2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (keepers.isNotEmpty()) {
            SectionLabel("SURVIVE A TERMINAL CLOSE", Modifier.padding(top = 12.dp, bottom = 4.dp))
            Rule()
            keepers.forEach { SessionRow(it, nowMs, it.leader.pid == selectedPid) { onOpen(it.leader) } }
        }
        if (terminals.isNotEmpty()) {
            SectionLabel("OPEN TERMINALS · END WHEN CLOSED", Modifier.padding(top = 12.dp, bottom = 4.dp))
            Rule()
            terminals.forEach { SessionRow(it, nowMs, it.leader.pid == selectedPid) { onOpen(it.leader) } }
        }
    }
}

/** OVERVIEW: the numbers, the device, and the three lists a user opens a task manager for. */
@Composable
internal fun OverviewBody(
    servers: List<app.pocketshell.widget.probe.ServerInfo>,
    procs: ProcView,
    system: SystemStrip?,
    nowMs: Long,
    onTab: (StudioTab) -> Unit,
    onOpenEntry: (ProcEntry) -> Unit,
) {
    val (up, bg, ses) = CardRows.counts(servers, procs)
    Column(modifier = Modifier.fillMaxWidth()) {
        StatStrip(
            items = listOf(
                StripItem("$up", "servers", hot = up > 0) { onTab(StudioTab.SERVERS) },
                StripItem("$bg", "background", hot = bg > 0) { onTab(StudioTab.BACKGROUND) },
                StripItem("$ses", "sessions", hot = ses > 0) { onTab(StudioTab.SESSIONS) },
                StripItem("${procs.outlive.size}", "outlive terminal", hot = procs.outlive.isNotEmpty()) { onTab(StudioTab.BACKGROUND) },
            ),
        )

        DeviceStrip(system)

        if (procs.outlive.isNotEmpty()) {
            SectionLabel("OUTLIVE THE TERMINAL", Modifier.padding(top = 16.dp, bottom = 4.dp))
            Rule()
            procs.outlive.sortedByDescending { it.rec.footprintBytes }.take(5).forEach { ProcRow(it, nowMs, false) { onOpenEntry(it) } }
        }

        val topMem = procs.topByMemory(5)
        if (topMem.isNotEmpty()) {
            SectionLabel("TOP MEMORY", Modifier.padding(top = 16.dp, bottom = 6.dp))
            val max = topMem.first().rec.footprintBytes.coerceAtLeast(1L)
            topMem.forEach { e ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open ${e.label}") { onOpenEntry(e) }
                        .padding(vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoText(text = e.label, fontSize = 12, modifier = Modifier.weight(1f))
                        MonoText(text = ProcPresentation.memory(e), color = HomeTokens.textDim, fontSize = 11)
                    }
                    // A flat bar: the share of the largest process, nothing decorative.
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .fillMaxWidth((e.rec.footprintBytes.toFloat() / max).coerceIn(0.02f, 1f))
                            .height(3.dp)
                            .background(HomeTokens.accent),
                    )
                }
            }
        }

        val topCpu = procs.topByCpu(3)
        if (topCpu.isNotEmpty()) {
            SectionLabel("TOP CPU", Modifier.padding(top = 16.dp, bottom = 4.dp))
            Rule()
            topCpu.forEach { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open ${e.label}") { onOpenEntry(e) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoText(text = e.label, fontSize = 12, modifier = Modifier.weight(1f))
                    MonoText(text = ServersPresentation.formatCpu(e.cpuPercent), color = HomeTokens.accent, fontSize = 11)
                }
                Rule()
            }
        }
        if (procs.all.isEmpty()) {
            MonoText(text = "Nothing running yet", color = HomeTokens.textDim, fontSize = 11, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

/** The device's load / memory / disk / uptime as one ruled strip. */
@Composable
internal fun DeviceStrip(system: SystemStrip?) {
    if (system == null) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SectionLabel("DEVICE · SHARED KERNEL")
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatChip(
                "LOAD",
                system.load?.let { ServersPresentation.formatLoad(it.one) + " " + ServersPresentation.formatLoad(it.five) + " " + ServersPresentation.formatLoad(it.fifteen) } ?: "—",
            )
            StatChip(
                "MEM",
                system.mem?.let { ServersPresentation.formatKb(it.usedKb) + " / " + ServersPresentation.formatKb(it.totalKb) } ?: "—",
            )
            StatChip("DISK", ServersPresentation.formatFraction(system.diskUsedFrac))
            StatChip("UP", system.uptimeSec?.let { ServersPresentation.formatUptime(it) } ?: "—")
        }
        Spacer(Modifier.height(8.dp))
        Rule()
    }
}
