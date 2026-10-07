package app.pocketshell.widget.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
internal fun KeyValue(label: String, value: String) {
    Row {
        MonoText(text = label, color = HomeTokens.textDim, fontSize = 10, modifier = Modifier.width(84.dp))
        MonoText(text = value, fontSize = 10)
    }
}

/** The maximized dashboard's search field. */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = {
            Text(
                "search port, process, path…",
                fontFamily = TerminalTheme.mono,
                fontSize = 12.sp,
                color = HomeTokens.textDim,
            )
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = TerminalTheme.mono,
            color = HomeTokens.textPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
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
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
