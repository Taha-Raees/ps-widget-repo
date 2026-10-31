package app.pocketshell.widget.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.servers.ServersOps.Signal

/**
 * HEALTH: the process budget, what is misbehaving, and what runs per app, with the settings for what counts as a problem.
 * Presentation only - the decisions live in [Health] and the state holder. Flat like every other page.
 */
@Composable
internal fun HealthBody(state: ServersState, actions: ServersActions, onOpenEntry: (ProcEntry) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BudgetMeter(state.budget)

        SectionLabel("NEEDS A LOOK", Modifier.padding(top = 18.dp, bottom = 4.dp))
        Rule()
        when {
            !state.cleanup.watchHealth -> Dim("Watching is off. Nothing here is checked.")
            state.findings.isEmpty() -> Dim("Nothing is misbehaving.")
        }
        state.findings.forEach { FindingRow(it, state, actions) }

        SectionLabel("APPS · ${state.groups.size}", Modifier.padding(top = 18.dp, bottom = 4.dp))
        Rule()
        if (state.groups.isEmpty()) Dim("Nothing running yet.")
        state.groups.forEach { AppRow(it, state, actions, onOpenEntry) }

        HealthSettings(state)

        if (state.cleanupLog.isNotEmpty()) {
            SectionLabel("RECENT", Modifier.padding(top = 18.dp, bottom = 4.dp))
            Rule()
            state.cleanupLog.take(5).forEach { entry ->
                MonoText(
                    text = entry.text,
                    color = if (entry.ok) HomeTokens.textPrimary else HomeTokens.textDim,
                    maxLines = 3,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Dim(text: String) {
    MonoText(text = text, color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
private fun levelColor(level: BudgetLevel): Color = when (level) {
    BudgetLevel.OK -> HomeTokens.accent
    BudgetLevel.WARN, BudgetLevel.CRITICAL -> HomeTokens.danger
}

@Composable
private fun BudgetMeter(budget: ProcessBudgetFacts) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("PROCESS BUDGET", Modifier.weight(1f))
            MonoText(text = "${budget.count} / ${ProcessBudgetFacts.LIMIT}", color = levelColor(budget.level))
        }
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).background(HomeTokens.hairline)) {
            Box(Modifier.fillMaxWidth(budget.fraction).height(3.dp).background(levelColor(budget.level)))
        }
        MonoText(
            text = "Android may kill the oldest process when all apps together pass ${ProcessBudgetFacts.LIMIT}, which can end the Linux desktop. Only this app's are counted here.",
            color = HomeTokens.textDim,
            fontSize = 10,
            maxLines = 4,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun FindingRow(f: Finding, state: ServersState, actions: ServersActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        MonoText(
            text = f.title,
            color = if (f.severity >= Severity.WARNING) HomeTokens.danger else HomeTokens.textPrimary,
            maxLines = 2,
        )
        MonoText(text = f.detail, color = HomeTokens.textDim, fontSize = 11, maxLines = 5, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            val g = f.group
            if (f.kind == FindingKind.STOPPED) ActionButton("RESUME") { f.stopped.firstOrNull()?.let { actions.onSignalProcess(it, Signal.CONT) } }
            if (g != null && !g.desktop) {
                if (f.kind == FindingKind.RUNAWAY) ActionButton(if (g.lowPriority) "RESTORE" else "LOWER PRIORITY") { state.setPriority(g, !g.lowPriority) }
                ActionButton("STOP") { actions.onSignalProcess(g.root, Signal.TERM) }
            }
            f.family?.let { family -> ActionButton("IGNORE") { state.quiet(family) } }
        }
        Rule()
    }
}

@Composable
private fun AppRow(g: ProcGroup, state: ServersState, actions: ServersActions, onOpenEntry: (ProcEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open ${g.label}") { onOpenEntry(g.root) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindDot(g.root.kind)
            MonoText(text = g.label, modifier = Modifier.padding(start = 8.dp).weight(1f))
            MonoText(text = ServersPresentation.formatBytes(g.footprintBytes), color = HomeTokens.textDim, fontSize = 11)
        }
        MonoText(
            text = listOfNotNull(
                "${g.count} process${if (g.count == 1) "" else "es"}",
                "CPU ${ServersPresentation.formatCpu(g.cpuPercent)}",
                "LOW PRIORITY".takeIf { g.lowPriority },
            ).joinToString(" · "),
            color = HomeTokens.textDim,
            fontSize = 11,
            modifier = Modifier.padding(start = 14.dp, top = 2.dp),
        )
        if (!g.desktop) {
            Row(Modifier.padding(start = 6.dp).horizontalScroll(rememberScrollState())) {
                ActionButton(if (g.lowPriority) "RESTORE" else "LOWER PRIORITY") { state.setPriority(g, !g.lowPriority) }
                ActionButton("STOP") { actions.onSignalProcess(g.root, Signal.TERM) }
                ActionButton("ADD RULE") { state.updateCleanup { it.addCustom(g.family) }; state.tab = StudioTab.CLEANUP }
            }
        }
        Rule()
    }
}

@Composable
private fun HealthSettings(state: ServersState) {
    val s = state.cleanup
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        SectionLabel("WHAT COUNTS AS A PROBLEM")
        Rule(Modifier.padding(top = 4.dp))
        ChoiceRow("WATCHING", listOf("On" to true, "Off" to false), s.watchHealth) { v -> state.updateCleanup { it.copy(watchHealth = v) } }
        ChoiceRow("PROCESSES", CleanupSettingsCodec.SWARM_CHOICES.map { "$it+" to it }, s.swarmAt) { v -> state.updateCleanup { it.copy(swarmAt = v) } }
        ChoiceRow("CPU OVER", CleanupSettingsCodec.CPU_AT_CHOICES.map { "$it %" to it }, s.cpuAtPercent) { v -> state.updateCleanup { it.copy(cpuAtPercent = v) } }
        ChoiceRow("CPU FOR", CleanupSettingsCodec.CPU_MINUTES_CHOICES.map { "$it min" to it }, s.cpuMinutes) { v -> state.updateCleanup { it.copy(cpuMinutes = v) } }
        ChoiceRow(
            "MEMORY OVER",
            CleanupSettingsCodec.HOG_CHOICES.map { "${it / 1000} GB" to it },
            s.hogMb,
        ) { v -> state.updateCleanup { it.copy(hogMb = v) } }
        ChoiceRow("HIGH CPU", listOf("Tell me" to false, "Lower priority" to true), s.autoCalm) { v -> state.updateCleanup { it.copy(autoCalm = v) } }

        if (s.quiet.isNotEmpty()) {
            SectionLabel("IGNORED", Modifier.padding(top = 18.dp, bottom = 4.dp))
            Rule()
            s.quiet.sorted().forEach { name ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoText(text = name, modifier = Modifier.weight(1f))
                    ActionButton("REMOVE") { state.unquiet(name) }
                }
            }
        }
    }
}
