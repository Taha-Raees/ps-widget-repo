package app.pocketshell.widget.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/** True on the maximized page: the same rows, larger type and thumb-sized spacing. */
internal val LocalNetFull = compositionLocalOf { false }

private const val FULL_TEXT_SCALE = 1.15f

/** The responsive contract (same geometry as the other plugins): decided by HEIGHT first. */
internal enum class NetLayout {
    CARD, FULL;

    companion object {
        const val FULL_MIN_WIDTH_DP = 320f
        const val FULL_MIN_HEIGHT_DP = 400f
        const val RAIL_MIN_WIDTH_DP = 600f
        const val LANDSCAPE_MIN_HEIGHT_DP = 300f

        fun from(widthDp: Float, heightDp: Float): NetLayout = when {
            widthDp >= FULL_MIN_WIDTH_DP && heightDp >= FULL_MIN_HEIGHT_DP -> FULL
            widthDp >= RAIL_MIN_WIDTH_DP && heightDp >= LANDSCAPE_MIN_HEIGHT_DP -> FULL
            else -> CARD
        }

        fun usesSideRail(widthDp: Float): Boolean = widthDp >= RAIL_MIN_WIDTH_DP
    }
}

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
        fontSize = if (LocalNetFull.current) fontSize * FULL_TEXT_SCALE else fontSize,
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
            .padding(horizontal = 4.dp, vertical = if (LocalNetFull.current) 12.dp else 8.dp),
    )
}

/** A small label tag in text only (no rounded chip): `[PASSPHRASE]`. */
@Composable
internal fun Tag(text: String, hot: Boolean = false) {
    MonoText(text = text.uppercase(), color = if (hot) HomeTokens.danger else HomeTokens.textDim, fontSize = 9.sp)
}

@Composable
internal fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

/** A row of segmented choices (`5 MIN · 15 MIN · 60 MIN`); the chosen one is accent. */
@Composable
internal fun <T> Choices(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (label, value) ->
            val on = value == selected
            Column(
                modifier = Modifier.clickable(role = Role.RadioButton, onClickLabel = label) { onSelect(value) },
            ) {
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

internal val NetTab.icon: ImageVector
    get() = when (this) {
        NetTab.LAB -> Icons.Outlined.Dashboard
        NetTab.TOOLS -> Icons.Outlined.Search
        NetTab.HOSTS -> Icons.Outlined.Dns
        NetTab.KEYS -> Icons.Outlined.VpnKey
        NetTab.TUNNELS -> Icons.Outlined.SwapHoriz
        NetTab.SHARE -> Icons.Outlined.Share
    }

internal fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
