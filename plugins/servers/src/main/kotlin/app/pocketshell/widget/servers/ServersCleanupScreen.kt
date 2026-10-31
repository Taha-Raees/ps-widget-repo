package app.pocketshell.widget.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.widget.servers.ServersOps.Signal

/**
 * CLEAN UP: the rules, what is idle right now, what is kept, and what the monitor did.
 * Presentation only - the decisions live in [Cleanup] and the state holder.
 */
@Composable
internal fun CleanupBody(state: ServersState, actions: ServersActions) {
    val settings = state.cleanup
    val candidates = state.candidates
    Column(modifier = Modifier.fillMaxWidth()) {
        MonoText(
            text = "Stops what is idle, has no terminal and uses a lot of memory. Nothing happens until you turn a rule on.",
            color = HomeTokens.textDim,
            maxLines = 4,
            modifier = Modifier.padding(top = 8.dp),
        )
        MonoText(
            text = if (settings.watching) "WATCHING · checks every minute" else "RULES ARE OFF · nothing is stopped",
            color = if (settings.watching) HomeTokens.accent else HomeTokens.textDim,
            fontSize = 11,
            modifier = Modifier.padding(top = 6.dp),
        )

        for (rule in listOf(CleanupRule.BUILDS, CleanupRule.HEAVY)) RuleBlock(rule, settings.rule(rule)) { next -> state.updateCleanup { it.with(rule, next) } }

        CustomRulesBlock(state)
        BudgetGuardBlock(state)
        LimitsBlock(state)

        SectionLabel("IDLE NOW", Modifier.padding(top = 18.dp, bottom = 4.dp))
        Rule()
        if (candidates.isEmpty()) {
            MonoText(
                text = "Nothing is idle for long enough. Idle means no CPU use at all, including everything below it.",
                color = HomeTokens.textDim,
                maxLines = 3,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        val ready = candidates.filter { it.due }
        if (ready.size > 1) {
            ActionButton("STOP ALL ${ready.size} READY · ${ServersPresentation.formatBytes(ready.sumOf { it.footprintBytes })}") { state.cleanNowPending = true }
        }
        candidates.forEach { c -> CandidateRow(c, onStop = { actions.onSignalProcess(c.entry, Signal.TERM) }, onKeep = { state.keep(c.entry) }) }

        SectionLabel("KEEP RUNNING", Modifier.padding(top = 18.dp, bottom = 4.dp))
        Rule()
        NameEntry("ADD A PROGRAM TO KEEP") { name -> state.updateCleanup { s -> if (CleanupSettingsCodec.validKeep(name) && s.keep.size < CleanupSettingsCodec.MAX_KEEP) s.copy(keep = s.keep + name) else s } }
        if (settings.keep.isNotEmpty()) {
            settings.keep.sorted().forEach { name ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoText(text = name, modifier = Modifier.weight(1f))
                    ActionButton("REMOVE") { state.unkeep(name) }
                }
            }
        }

        if (state.freedBytes > 0) {
            MonoText(text = "FREED THIS SESSION · ${ServersPresentation.formatBytes(state.freedBytes)}", color = HomeTokens.accent, fontSize = 11, modifier = Modifier.padding(top = 18.dp))
        }
        if (state.cleanupLog.isNotEmpty()) {
            SectionLabel("RECENT", Modifier.padding(top = 18.dp, bottom = 4.dp))
            Rule()
            state.cleanupLog.forEach { entry ->
                MonoText(
                    text = "${Cleanup.duration((state.procAtMs - entry.atMs).coerceAtLeast(0))} ago · ${entry.text}",
                    color = if (entry.ok) HomeTokens.textPrimary else HomeTokens.textDim,
                    maxLines = 3,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        MonoText(
            text = CleanupText.NEVER_TOUCHED,
            color = HomeTokens.textDim,
            fontSize = 10,
            maxLines = 8,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

/** A name field that adds on ADD and clears itself. */
@Composable
internal fun NameEntry(placeholder: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { SearchField(value = text, onChange = { text = it }, placeholder = placeholder) }
        ActionButton("ADD") {
            val name = text.trim().lowercase()
            if (name.isNotEmpty()) onAdd(name)
            text = ""
        }
    }
}

/** Rules for programs the user names: Android Studio's daemon, a test runner, a browser left open overnight. */
@Composable
private fun CustomRulesBlock(state: ServersState) {
    val custom = state.cleanup.custom
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        SectionLabel("YOUR RULES")
        Rule(Modifier.padding(top = 4.dp))
        MonoText(
            text = "Name a program, as it appears in these lists, and choose when it may be stopped. The safety guards still apply.",
            color = HomeTokens.textDim,
            maxLines = 3,
            modifier = Modifier.padding(top = 6.dp),
        )
        NameEntry("PROGRAM NAME, E.G. FIREFOX-ESR") { name -> state.updateCleanup { it.addCustom(name) } }
        custom.forEach { rule ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                MonoText(text = rule.name, modifier = Modifier.weight(1f))
                ActionButton("REMOVE") { state.updateCleanup { s -> s.copy(custom = s.custom - rule) } }
            }
            RuleChoices(CleanupRule.CUSTOM, rule.settings) { next -> state.updateCleanup { it.withCustom(rule.name, next) } }
        }
    }
}

/** Stops the count reaching the point where Android starts killing, which is what ends the desktop. */
@Composable
private fun BudgetGuardBlock(state: ServersState) {
    val s = state.cleanup
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        SectionLabel("PROCESS BUDGET GUARD")
        Rule(Modifier.padding(top = 4.dp))
        MonoText(
            text = "Android may end the desktop above ${ProcessBudgetFacts.LIMIT} processes. Auto stops one idle program at a time at this count, sooner than its rule would. Only what the rules above could stop anyway.",
            color = HomeTokens.textDim,
            maxLines = 5,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
        ChoiceRow("WHEN REACHED", listOf(CleanupMode.OFF, CleanupMode.AUTO).map { it.label to it }, s.budgetMode) { m -> state.updateCleanup { it.copy(budgetMode = m) } }
        ChoiceRow("AT PROCESSES", CleanupSettingsCodec.BUDGET_AT_CHOICES.map { "$it" to it }, s.budgetAt) { n -> state.updateCleanup { it.copy(budgetAt = n) } }
    }
}

@Composable
private fun LimitsBlock(state: ServersState) {
    val s = state.cleanup
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        SectionLabel("HOW AUTO BEHAVES")
        Rule(Modifier.padding(top = 4.dp))
        ChoiceRow("AT MOST", CleanupSettingsCodec.PER_SWEEP_CHOICES.map { "$it / check" to it }, s.perSweep) { n -> state.updateCleanup { it.copy(perSweep = n) } }
        ChoiceRow(
            "ONLY WHEN FREE",
            CleanupSettingsCodec.LOW_MEMORY_CHOICES.map { (if (it == 0) "always" else "under $it %") to it },
            s.lowMemoryPercent,
        ) { n -> state.updateCleanup { it.copy(lowMemoryPercent = n) } }
    }
}

@Composable
private fun RuleBlock(rule: CleanupRule, s: RuleSettings, onChange: (RuleSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(rule.title.uppercase(), Modifier.weight(1f))
        }
        Rule(Modifier.padding(top = 4.dp))
        MonoText(text = rule.blurb, color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        RuleChoices(rule, s, onChange)
    }
}

@Composable
private fun RuleChoices(rule: CleanupRule, s: RuleSettings, onChange: (RuleSettings) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        ChoiceRow("WHEN IDLE", CleanupMode.entries.map { it.label to it }, s.mode) { onChange(s.copy(mode = it)) }
        ChoiceRow("IDLE FOR", CleanupSettingsCodec.idleChoices(rule).map { minutesLabel(it) to it }, s.idleMinutes) { onChange(s.copy(idleMinutes = it)) }
        ChoiceRow("USING AT LEAST", CleanupSettingsCodec.memChoices(rule).map { memLabel(it) to it }, s.minMemoryMb) { onChange(s.copy(minMemoryMb = it)) }
    }
}

private fun minutesLabel(m: Int) = if (m >= 60) "${m / 60} h" else "$m min"
private fun memLabel(mb: Int) = when { mb == 0 -> "any"; mb >= 1000 -> "${mb / 1000} GB"; else -> "$mb MB" }

/** A labelled row of text choices; the chosen one is accent with a rule under it. No chips, no boxes. */
@Composable
internal fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoText(text = label, color = HomeTokens.textDim, fontSize = 9, modifier = Modifier.width(96.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            options.forEach { (text, value) ->
                val on = value == selected
                Column(Modifier.clickable(role = Role.RadioButton, onClickLabel = text) { onSelect(value) }) {
                    MonoText(
                        text = text.uppercase(),
                        color = if (on) HomeTokens.accent else HomeTokens.textDim,
                        fontSize = 11,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                    Rule(Modifier.height(if (on) 2.dp else 1.dp))
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(c: CleanupCandidate, onStop: () -> Unit, onKeep: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindDot(c.entry.kind)
            MonoText(text = c.entry.label, modifier = Modifier.padding(start = 8.dp).weight(1f))
            MonoText(text = ProcPresentation.memory(c.entry), color = HomeTokens.textDim, fontSize = 11)
        }
        MonoText(
            text = CleanupText.status(c),
            color = if (c.due && c.mode != CleanupMode.OFF) HomeTokens.accent else HomeTokens.textDim,
            fontSize = 11,
            maxLines = 3,
            modifier = Modifier.padding(start = 14.dp, top = 2.dp),
        )
        Row(Modifier.padding(start = 6.dp)) {
            ActionButton("STOP", onStop)
            ActionButton("KEEP", onKeep)
        }
        Rule()
    }
}
