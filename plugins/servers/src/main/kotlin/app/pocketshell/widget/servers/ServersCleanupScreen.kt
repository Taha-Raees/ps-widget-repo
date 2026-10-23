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

        for (rule in CleanupRule.entries) RuleBlock(rule, settings.rule(rule)) { next -> state.updateCleanup { it.with(rule, next) } }

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
        candidates.forEach { c -> CandidateRow(c, onStop = { actions.onSignalProcess(c.entry, Signal.TERM) }, onKeep = { state.keep(c.entry) }) }

        if (settings.keep.isNotEmpty()) {
            SectionLabel("KEEP RUNNING", Modifier.padding(top = 18.dp, bottom = 4.dp))
            Rule()
            settings.keep.sorted().forEach { name ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoText(text = name, modifier = Modifier.weight(1f))
                    ActionButton("REMOVE") { state.unkeep(name) }
                }
            }
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

@Composable
private fun RuleBlock(rule: CleanupRule, s: RuleSettings, onChange: (RuleSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(rule.title.uppercase(), Modifier.weight(1f))
        }
        Rule(Modifier.padding(top = 4.dp))
        MonoText(text = rule.blurb, color = HomeTokens.textDim, maxLines = 3, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        ChoiceRow("WHEN IDLE", CleanupMode.entries.map { it.label to it }, s.mode) { onChange(s.copy(mode = it)) }
        ChoiceRow("IDLE FOR", CleanupSettingsCodec.idleChoices(rule).map { minutesLabel(it) to it }, s.idleMinutes) { onChange(s.copy(idleMinutes = it)) }
        ChoiceRow("USING AT LEAST", CleanupSettingsCodec.memChoices(rule).map { memLabel(it) to it }, s.minMemoryMb) { onChange(s.copy(minMemoryMb = it)) }
    }
}

private fun minutesLabel(m: Int) = if (m >= 60) "${m / 60} h" else "$m min"
private fun memLabel(mb: Int) = when { mb == 0 -> "any"; mb >= 1000 -> "${mb / 1000} GB"; else -> "$mb MB" }

/** A labelled row of text choices; the chosen one is accent with a rule under it. No chips, no boxes. */
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
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
