package app.pocketshell.widget.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * The Servers monitor's shared controls — the row vocabulary every screen
 * speaks, the honest-state surfaces, and the two stop-pipeline dialogs.
 * Everything reads the shared theme tokens — no application owns a color —
 * and every control is sized for a thumb, not a cursor.
 */

/** Mono text at the app's row size — the ONE text style rows use. */
@Composable
internal fun MonoText(
    text: String,
    color: Color = HomeTokens.textPrimary,
    fontSize: Int = 12,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontFamily = TerminalTheme.mono,
        fontSize = fontSize.sp,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The small section label mono-caps rows sit under. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontFamily = TerminalTheme.mono,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        color = HomeTokens.textDim,
        modifier = modifier,
    )
}

/** The 6 dp status dot — the widget system's running/danger vocabulary. */
@Composable
internal fun StatusDot(ok: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(6.dp)
            .background(if (ok) HomeTokens.runningGreen else HomeTokens.danger, CircleShape),
    )
}

/**
 * The beat strip: one bar per recorded check, width-fixed, height and
 * color carrying the fact — green bars (ok) rise with latency, red bars
 * (failed) stay flat. A short history renders from the left; the strip
 * never invents beats to fill the width.
 */
@Composable
internal fun BeatStrip(
    beats: List<BeatHistory.Beat>,
    modifier: Modifier = Modifier,
    barWidth: Int = 3,
    maxBarHeight: Int = 10,
) {
    val maxLatency = beats.maxOfOrNull { it.latencyMs }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        beats.forEach { beat ->
            val height = when {
                !beat.ok -> 2.dp
                else -> (3.dp + maxBarHeight.dp * (beat.latencyMs.toFloat() / maxLatency))
                    .coerceAtMost((3 + maxBarHeight).dp)
            }
            Box(
                modifier = Modifier
                    .width(barWidth.dp)
                    .height(height)
                    .background(
                        if (beat.ok) HomeTokens.runningGreen else HomeTokens.danger,
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

/** One system-strip chip: label above, mono value below. */
@Composable
internal fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionLabel(label)
        MonoText(text = value, fontSize = 11)
    }
}

/** The detail page's label/value row — the htop-lite fact line. */
@Composable
internal fun KeyValue(label: String, value: String, maxLines: Int = 1, valueColor: Color = HomeTokens.textPrimary) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        MonoText(text = label, color = HomeTokens.textDim, fontSize = 10, modifier = Modifier.width(84.dp))
        MonoText(text = value, color = valueColor, fontSize = 10, maxLines = maxLines, modifier = Modifier.weight(1f))
    }
}

/** The search field — a plain underlined field, never an outlined box. */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String = "SEARCH PORT, PROCESS, PATH") {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = TerminalTheme.mono, fontSize = 13.sp, color = HomeTokens.textPrimary),
        cursorBrush = SolidColor(HomeTokens.accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Column {
                Box(modifier = Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) SectionLabel(placeholder)
                    inner()
                }
                Rule()
            }
        },
    )
}

/** A 1dp hairline — the app's divider; rows and sections are ruled, not boxed. */
@Composable
internal fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(HomeTokens.hairline))
}

/** One big number over a small-caps label. */
internal data class StripItem(
    val value: String,
    val label: String,
    val hot: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** A flat row of big numbers over small-caps labels, ruled above and below. */
@Composable
internal fun StatStrip(items: List<StripItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Rule()
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            items.forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(HomeTokens.hairline))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (item.onClick != null) {
                                Modifier.clickable(role = Role.Button, onClickLabel = "Show ${item.label}") { item.onClick.invoke() }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = item.value,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (item.hot) HomeTokens.accent else HomeTokens.textPrimary,
                    )
                    SectionLabel(item.label.uppercase())
                }
            }
        }
        Rule()
    }
}

/**
 * The row marker that says what a line IS without a word: a filled dot is a
 * server (green up / red failing), a ring is a background process, a small
 * square is a session. Flat, 8dp, theme tokens only.
 */
@Composable
internal fun KindDot(kind: ProcKind, ok: Boolean = true, modifier: Modifier = Modifier) {
    val color = if (ok) HomeTokens.accent else HomeTokens.danger
    when (kind) {
        ProcKind.SERVER -> Box(modifier.size(8.dp).background(if (ok) HomeTokens.runningGreen else HomeTokens.danger, CircleShape))
        ProcKind.MUX, ProcKind.SHELL -> Box(modifier.size(8.dp).background(color))
        else -> Box(modifier.size(8.dp).border(1.5.dp, color, CircleShape))
    }
}

/** The stop pipeline's confirm — the exact command, verbatim, before anything runs. */
@Composable
internal fun StopConfirmDialog(
    spec: ServersOps.ConfirmSpec,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                spec.title,
                color = HomeTokens.textPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Text(
                    spec.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeTokens.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                MonoText(text = spec.command, color = HomeTokens.accent, fontSize = 11)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(spec.confirmLabel, fontFamily = TerminalTheme.mono, color = HomeTokens.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = TerminalTheme.mono, color = HomeTokens.textDim)
            }
        },
    )
}

/** The stop pipeline's honest outcome banner; dismissed by tap. */
@Composable
internal fun OutcomeBanner(outcome: ServersOps.Outcome, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Dismiss result") { onDismiss() }
            .background(
                if (outcome.ok) {
                    HomeTokens.runningGreen.copy(alpha = 0.12f)
                } else {
                    HomeTokens.danger.copy(alpha = 0.12f)
                },
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(ok = outcome.ok)
        Spacer(Modifier.width(8.dp))
        MonoText(
            text = outcome.line(),
            color = if (outcome.ok) HomeTokens.textPrimary else HomeTokens.danger,
            fontSize = 11,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The small touch action the screens share (TERMINAL / COMPANION / …). */
@Composable
internal fun ActionButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(34.dp)) {
        Text(label, fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
    }
}

/** The header line a page shares: back arrow + the app's mono-caps name. */
@Composable
internal fun BackHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Back to Servers") { onBack() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(text = "←", color = HomeTokens.accent, fontSize = 14)
        Spacer(Modifier.width(8.dp))
        SectionLabel("SERVERS")
    }
}
