package app.pocketshell.widget.guiapps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/**
 * The shared UI kit of the PocketShell flat style (hairlines, mono small-caps, accent text),
 * plus the one app shell every tab-based plugin in this repo uses. Presentation only: nothing here
 * reads a file, opens a socket or runs a process.
 */

/** True on the maximized page: the same rows, larger type and thumb-sized spacing. */
internal val LocalFull = compositionLocalOf { false }

private const val FULL_TEXT_SCALE = 1.15f

/** The responsive contract (same geometry as the other plugins): decided by HEIGHT first. */
internal enum class Layout {
    CARD, FULL;

    companion object {
        const val FULL_MIN_WIDTH_DP = 320f
        const val FULL_MIN_HEIGHT_DP = 400f
        const val RAIL_MIN_WIDTH_DP = 600f
        const val LANDSCAPE_MIN_HEIGHT_DP = 300f

        fun from(widthDp: Float, heightDp: Float): Layout = when {
            widthDp >= FULL_MIN_WIDTH_DP && heightDp >= FULL_MIN_HEIGHT_DP -> FULL
            widthDp >= RAIL_MIN_WIDTH_DP && heightDp >= LANDSCAPE_MIN_HEIGHT_DP -> FULL
            else -> CARD
        }

        fun usesSideRail(widthDp: Float): Boolean = widthDp >= RAIL_MIN_WIDTH_DP
    }
}

/** A one-line message under the header; tap to dismiss. */
data class Banner(val text: String, val bad: Boolean)

@Composable
internal fun MonoText(
    text: String,
    color: Color = HomeTokens.textPrimary,
    fontSize: TextUnit = 11.sp,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        fontFamily = TerminalTheme.mono,
        fontSize = if (LocalFull.current) fontSize * FULL_TEXT_SCALE else fontSize,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        fontWeight = fontWeight,
    )
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(HomeTokens.hairline))
}

@Composable
internal fun SectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        fontFamily = TerminalTheme.mono,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = HomeTokens.textDim,
        modifier = modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** A thin indeterminate accent line while [active], the plain hairline otherwise (same height). */
@Composable
internal fun LoadingRule(active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(2.dp)) {
        if (active) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = HomeTokens.accent,
                trackColor = HomeTokens.hairline,
            )
        } else {
            Box(Modifier.fillMaxWidth().height(1.dp).background(HomeTokens.hairline).align(Alignment.BottomStart))
        }
    }
}

/** A determinate rule: [fraction] 0..1 of the width in accent. */
@Composable
internal fun ProgressRule(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(HomeTokens.hairline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(HomeTokens.accent))
    }
}

@Composable
internal fun IconAction(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) HomeTokens.accent else HomeTokens.textDim,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** A flat text action: small caps, accent colour, a thumb-sized hit area. */
@Composable
internal fun ActionText(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    MonoText(
        text = label.uppercase(),
        color = when {
            !enabled -> HomeTokens.textDim
            danger -> HomeTokens.danger
            else -> HomeTokens.accent
        },
        fontSize = 11.sp,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { onClick() }
            .padding(horizontal = 4.dp, vertical = if (LocalFull.current) 12.dp else 8.dp),
    )
}

/** A small label tag in text only (no rounded chip): `[GLIBC]`. */
@Composable
internal fun Tag(text: String, hot: Boolean = false) {
    MonoText(text = text.uppercase(), color = if (hot) HomeTokens.accent else HomeTokens.textDim, fontSize = 9.sp)
}

@Composable
internal fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

/** A row of segmented choices (`ALL · MUSL · GLIBC`); the chosen one is accent. */
@Composable
internal fun <T> Choices(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (label, value) ->
            val on = value == selected
            Column(modifier = Modifier.clickable(role = Role.RadioButton, onClickLabel = label) { onSelect(value) }) {
                MonoText(
                    text = label.uppercase(),
                    color = if (on) HomeTokens.accent else HomeTokens.textDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                )
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) HomeTokens.accent else HomeTokens.hairline))
            }
        }
    }
}

internal data class StripItem(val value: String, val label: String, val hot: Boolean = false, val onClick: (() -> Unit)? = null)

/**
 * A flat row of big numbers over small-caps labels, ruled above and below. Dividers are drawn
 * behind each cell (no IntrinsicSize measurement), so the strip costs one plain layout pass.
 */
@Composable
internal fun StatStrip(items: List<StripItem>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(modifier = Modifier.fillMaxWidth()) {
            val line = HomeTokens.hairline
            items.forEachIndexed { i, item ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .drawBehind { if (i > 0) drawLine(line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx()) }
                        .then(
                            if (item.onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Show ${item.label}") { item.onClick.invoke() } else Modifier,
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = item.value,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (item.hot) HomeTokens.accent else HomeTokens.textPrimary,
                        maxLines = 1,
                    )
                    MonoText(text = item.label.uppercase(), color = HomeTokens.textDim, fontSize = 9.sp)
                }
            }
        }
        Hairline()
    }
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

// ------------------------------------------------------------------ forms

/** A labelled single-line field in the app's mono type. */
@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, hint: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = TerminalTheme.mono, fontSize = 11.sp) },
        placeholder = hint?.let { { Text(it, fontFamily = TerminalTheme.mono, fontSize = 12.sp, color = HomeTokens.textDim) } },
        singleLine = true,
        textStyle = TextStyle(fontFamily = TerminalTheme.mono, fontSize = 13.sp, color = HomeTokens.textPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
internal fun ErrorLine(error: String?) {
    if (error != null) MonoText(text = error, color = HomeTokens.danger, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
}

/** A destructive confirmation; with [typeToConfirm] the user must type that word. */
@Composable
internal fun ConfirmDialog(title: String, message: String, confirmLabel: String, typeToConfirm: String? = null, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val ready = typeToConfirm == null || typed.trim() == typeToConfirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, fontSize = 13.sp)
                if (typeToConfirm != null) Field("Type \"$typeToConfirm\" to confirm", typed, { typed = it })
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (ready) HomeTokens.danger else HomeTokens.textDim)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}

/** A confirmation request the page shows as a [ConfirmDialog]. */
internal data class Confirm(val title: String, val message: String, val label: String, val word: String? = null, val action: () -> Unit)

// ------------------------------------------------------------------ shell

internal data class TabSpec(val key: String, val label: String, val icon: ImageVector)

@Composable
internal fun NotReady(title: String, onOpenDiagnostics: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = HomeTokens.textPrimary, modifier = Modifier.padding(top = 4.dp))
        MonoText(text = "Linux is not ready yet", color = HomeTokens.textDim, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics", color = HomeTokens.accent) }
    }
}

/** The MINI card: title, one status line, a stat strip and an optional hint. A pure view. */
@Composable
internal fun MiniCard(
    title: String,
    subtitle: String,
    loading: Boolean,
    strip: List<StripItem>,
    hint: String?,
    onTerminal: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = HomeTokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MonoText(text = subtitle, color = HomeTokens.textDim, fontSize = 10.sp)
            }
            IconAction(Icons.Outlined.Terminal, "Open terminal", onClick = onTerminal)
            IconAction(Icons.Outlined.Refresh, "Refresh $title", enabled = !loading, onClick = onRefresh)
        }
        LoadingRule(active = loading)
        StatStrip(strip, modifier = Modifier.padding(top = 8.dp))
        hint?.let { MonoText(text = it, color = HomeTokens.textDim, maxLines = 2, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/**
 * The MAXIMIZED page: header (title, status, terminal, refresh), a busy rule, a dismissible banner,
 * the stat strip, the scrolling tab body, and the tab bar (bottom bar, or a side rail when wide).
 */
@Composable
internal fun AppShell(
    title: String,
    subtitle: String,
    tabs: List<TabSpec>,
    selected: String,
    onSelect: (String) -> Unit,
    sideRail: Boolean,
    busy: Boolean,
    banner: Banner?,
    onDismissBanner: () -> Unit,
    strip: List<StripItem>?,
    onTerminal: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        if (sideRail) {
            Column(Modifier.width(132.dp).fillMaxHeight().padding(top = 8.dp)) {
                tabs.forEach { tab -> NavItem(tab, tab.key == selected, compact = false) { onSelect(tab.key) } }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(HomeTokens.hairline))
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (sideRail) 12.dp else 0.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = HomeTokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MonoText(text = subtitle, color = HomeTokens.textDim, fontSize = 10.sp)
                }
                IconAction(Icons.Outlined.Terminal, "Open terminal", onClick = onTerminal)
                IconAction(Icons.Outlined.Refresh, "Refresh", enabled = refreshEnabled, onClick = onRefresh)
            }
            LoadingRule(active = busy)
            banner?.let { b ->
                Row(Modifier.fillMaxWidth().clickable { onDismissBanner() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MonoText(text = b.text, color = if (b.bad) HomeTokens.danger else HomeTokens.runningGreen, maxLines = 3, modifier = Modifier.weight(1f))
                    MonoText(text = "OK", color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Hairline()
            }
            strip?.let { StatStrip(it, modifier = Modifier.padding(top = 4.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp), content = content)
            }
            if (!sideRail) {
                Hairline()
                Row(Modifier.fillMaxWidth()) {
                    tabs.forEach { tab -> Box(Modifier.weight(1f)) { NavItem(tab, tab.key == selected, compact = true) { onSelect(tab.key) } } }
                }
            }
        }
    }
}

@Composable
private fun NavItem(tab: TabSpec, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val color = if (selected) HomeTokens.accent else HomeTokens.textDim
    if (compact) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Tab, onClickLabel = "Show ${tab.label}") { onClick() }.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(tab.icon, tab.label, tint = color, modifier = Modifier.height(22.dp))
            MonoText(text = tab.label.uppercase(), color = color, fontSize = 9.sp)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Tab, onClickLabel = "Show ${tab.label}") { onClick() }.padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(tab.icon, tab.label, tint = color, modifier = Modifier.height(22.dp))
            MonoText(text = tab.label.uppercase(), color = color, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** A list row: hairline above, tappable, with a leading status dot, title, right-side tag and detail lines. */
@Composable
internal fun ListRow(
    dot: Color?,
    title: String,
    right: String?,
    lines: List<String>,
    onClick: () -> Unit,
    lineColor: Color = HomeTokens.textDim,
) {
    Hairline()
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = if (LocalFull.current) 10.dp else 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            dot?.let { StatusDot(it) }
            MonoText(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = if (dot != null) 8.dp else 0.dp).weight(1f))
            right?.let { MonoText(it, color = HomeTokens.textDim, fontSize = 10.sp) }
        }
        lines.forEach { MonoText(it, color = lineColor, maxLines = 2, modifier = Modifier.padding(start = if (dot != null) 16.dp else 0.dp)) }
    }
}
