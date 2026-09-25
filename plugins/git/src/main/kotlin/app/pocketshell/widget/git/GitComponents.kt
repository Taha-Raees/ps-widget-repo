package app.pocketshell.widget.git

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Git application's shared controls: the row vocabulary every screen
 * speaks (sections, file/commit/branch/remote rows), the honest states,
 * the tab bar, and the op pipeline's two surfaces (confirm dialog, result
 * banner). Everything reads the shared theme tokens — no application owns
 * a color — and every control is sized for a thumb, not a cursor.
 */

/** Mono text at the app's row size — the ONE text style rows use. */
@Composable
internal fun MonoText(
    text: String,
    color: androidx.compose.ui.graphics.Color = HomeTokens.textPrimary,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        fontFamily = TerminalTheme.mono,
        fontSize = fontSize,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        fontWeight = fontWeight,
    )
}

/** A section label — git's vocabulary, small caps, dim. */
@Composable
internal fun GitSection(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        fontFamily = TerminalTheme.mono,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = HomeTokens.textDim,
        modifier = modifier.padding(top = 6.dp),
    )
}

/** The in-card back header — the ONLY back is the application's own. */
@Composable
internal fun BackHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Back") { onBack() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(text = "←", color = HomeTokens.accent, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        MonoText(text = title, color = HomeTokens.textDim, fontSize = 11.sp)
    }
}

/** The one refresh control: compact icon-first, named for accessibility. */
@Composable
internal fun RefreshButton(onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(role = Role.Button, onClickLabel = "Refresh repositories") { onRefresh() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "Refresh repositories",
            tint = HomeTokens.accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A compact text action (the card budget allows a 32dp row). */
@Composable
internal fun TextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.height(32.dp)) {
        Text(
            label,
            fontFamily = TerminalTheme.mono,
            fontSize = 11.sp,
            color = if (enabled) HomeTokens.accent else HomeTokens.textDim,
        )
    }
}

/**
 * One status letter's color — the theme's semantic tokens, never raw
 * colors: added is the running green, removed is danger, a change or a
 * rename is the accent, untracked is dim.
 */
internal fun statusColor(letter: Char): androidx.compose.ui.graphics.Color = when (letter) {
    'A' -> HomeTokens.runningGreen
    'D' -> HomeTokens.danger
    'U' -> HomeTokens.danger
    '?' -> HomeTokens.textDim
    else -> HomeTokens.accent
}

/** One changed file: the status letter (colored) + the display path. */
@Composable
internal fun FileRow(
    letter: Char,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onActions: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "Show $label") { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(
            text = letter.toString(),
            color = statusColor(letter),
            modifier = Modifier.width(16.dp),
        )
        MonoText(text = label, modifier = Modifier.padding(start = 6.dp).weight(1f))
        if (onActions != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(role = Role.Button, onClickLabel = "Actions for $label") { onActions() },
                contentAlignment = Alignment.Center,
            ) {
                MonoText(text = "⋯", color = HomeTokens.textDim, fontSize = 14.sp)
            }
        }
    }
}

/** One commit: `hash  subject`, author · time as the subline. */
@Composable
internal fun CommitRow(entry: LogEntry, roomy: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Show commit ${entry.hash}") { onOpen() }
            .padding(vertical = 2.dp),
    ) {
        MonoText(text = entry.hash, color = HomeTokens.textDim)
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            MonoText(text = entry.subject)
            if (roomy) {
                MonoText(
                    text = "${entry.author} · ${entry.relativeTime}",
                    color = HomeTokens.textDim,
                    fontSize = 10.sp,
                )
            }
        }
        if (entry.refs != null) {
            MonoText(text = entry.refs, color = HomeTokens.accent, fontSize = 10.sp)
        }
    }
}

/** One local branch: the current one first-class, upstream + glyphs. */
@Composable
internal fun BranchRow(branch: Branch, isCurrent: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "Branch ${branch.name}") { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(
            text = if (isCurrent) "*" else "",
            color = HomeTokens.accent,
            modifier = Modifier.width(12.dp),
        )
        MonoText(text = branch.name, modifier = Modifier.weight(1f, fill = false))
        if (branch.upstream != null) {
            Spacer(Modifier.width(6.dp))
            MonoText(
                text = "→ ${branch.upstream}${if (branch.gone) " (gone)" else ""}",
                color = HomeTokens.textDim,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        val glyphs = GitPresentation.trackingGlyphs(ahead = branch.ahead, behind = branch.behind)
        if (glyphs.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            MonoText(text = glyphs, color = HomeTokens.accent, fontSize = 10.sp)
        }
    }
}

/** One remote: name + the sanitized, shortened URL. */
@Composable
internal fun RemoteRow(remote: Remote) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        MonoText(text = remote.name, modifier = Modifier.width(64.dp))
        MonoText(
            text = remote.fetchUrl?.let { GitPresentation.displayUrl(it) } ?: "—",
            color = HomeTokens.textDim,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The workspace tab bar — folder-tab look, the active tab carries the rule. */
@Composable
internal fun TabBar(tabs: List<RepoTab>, selected: RepoTab, onSelect: (RepoTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab == selected
            Column(
                modifier = Modifier
                    .clickable(role = Role.Tab, onClickLabel = "Show ${tab.label}") { onSelect(tab) }
                    .padding(horizontal = 2.dp),
            ) {
                MonoText(
                    text = tab.label.uppercase(),
                    color = if (active) HomeTokens.accent else HomeTokens.textDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (active) HomeTokens.accent else HomeTokens.hairline),
                )
            }
        }
    }
}

/** The honest "+N more" footer for a capped list. */
@Composable
internal fun CapFooter(hidden: Int, what: String = "entries") {
    if (hidden <= 0) return
    MonoText(
        text = "+$hidden more $what",
        color = HomeTokens.textDim,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** An honest empty/loading line. */
@Composable
internal fun StateLine(text: String, modifier: Modifier = Modifier) {
    MonoText(
        text = text,
        color = HomeTokens.textDim,
        fontSize = 11.sp,
        modifier = modifier.padding(top = 4.dp),
    )
}

/** A failed read: the reason, verbatim — never dressed up. */
@Composable
internal fun FailedRead(what: String, reason: String) {
    MonoText(text = what, color = HomeTokens.danger, modifier = Modifier.padding(top = 4.dp))
    MonoText(
        text = reason,
        color = HomeTokens.textDim,
        modifier = Modifier.padding(top = 2.dp),
        maxLines = 4,
    )
}

/**
 * The drives-a-read effect: one request = one bounded exec on IO, served
 * from the slot's last answer when the request (and the mutation epoch)
 * are unchanged. The gen guard means a superseded request's result never
 * lands; the coroutine dying with the screen abandons the exec's
 * write-back entirely.
 */
@Composable
internal fun <R, T> ReadEffect(
    slot: ReadSlot<R, T>,
    epoch: Int,
    load: (R) -> ReadResult<T>,
) {
    LaunchedEffect(slot.req, epoch) {
        val r = slot.req ?: return@LaunchedEffect
        if (slot.served == r && slot.servedEpoch == epoch && slot.ui is ReadUi.Done) {
            return@LaunchedEffect
        }
        val gen = slot.gen
        slot.ui = ReadUi.Loading
        val result = withContext(Dispatchers.IO) { load(r) }
        if (slot.gen == gen) {
            when (result) {
                is ReadResult.Done -> {
                    slot.ui = ReadUi.Done(result.value)
                    slot.served = r
                    slot.servedEpoch = epoch
                }
                is ReadResult.Failed -> slot.ui = ReadUi.Failed(result.reason)
            }
        }
    }
}

/** A dialog listing labeled actions (the long-press / overflow sheet). */
@Composable
internal fun ActionSheet(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = TerminalTheme.mono, color = HomeTokens.textDim)
            }
        },
        title = { MonoText(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                actions.forEach { (label, action) ->
                    TextButton(
                        onClick = { onDismiss(); action() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            label,
                            fontFamily = TerminalTheme.mono,
                            fontSize = 12.sp,
                            color = HomeTokens.textPrimary,
                        )
                    }
                }
            }
        },
    )
}

/** A text-prompt dialog (branch names, commit messages, tags…). */
@Composable
internal fun PromptDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmWord: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) {
                Text(confirmWord, fontFamily = TerminalTheme.mono, color = HomeTokens.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = TerminalTheme.mono, color = HomeTokens.textDim)
            }
        },
        title = { MonoText(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label, fontFamily = TerminalTheme.mono, fontSize = 11.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = TerminalTheme.mono,
                    fontSize = 12.sp,
                    color = HomeTokens.textPrimary,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/**
 * THE confirm dialog: the policy's face. A destructive op's confirm word
 * renders in danger red; the exact command it will run is always shown,
 * so "what exactly happens" is never a mystery.
 */
@Composable
internal fun OpConfirmDialog(
    spec: GitOps.ConfirmSpec,
    command: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    spec.confirmWord,
                    fontFamily = TerminalTheme.mono,
                    color = HomeTokens.danger,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = TerminalTheme.mono, color = HomeTokens.textDim)
            }
        },
        title = { MonoText(text = spec.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                MonoText(text = spec.body, color = HomeTokens.textDim, fontSize = 11.sp, maxLines = 6)
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = HomeTokens.surfaceRaised,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MonoText(
                        text = "$ $command",
                        color = HomeTokens.textPrimary,
                        fontSize = 10.sp,
                        maxLines = 2,
                        modifier = Modifier.padding(6.dp),
                    )
                }
            }
        },
    )
}

/**
 * The op's outcome banner: git's OWN words — the summary of a success,
 * the reason of a failure — plus the command that produced them. It stays
 * until dismissed; no toast dresses a mutation up as frictionless.
 */
@Composable
internal fun OpResultBanner(outcome: GitOpOutcome, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = HomeTokens.surfaceBanner,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (outcome.success) HomeTokens.hairline else HomeTokens.danger,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable(role = Role.Button, onClickLabel = "Dismiss result") { onDismiss() },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    text = if (outcome.success) "✓ ${GitOps.label(outcome.op)}" else "✗ ${GitOps.label(outcome.op)} failed",
                    color = if (outcome.success) HomeTokens.runningGreen else HomeTokens.danger,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                MonoText(text = "DISMISS", color = HomeTokens.textDim, fontSize = 10.sp)
            }
            outcome.headline?.let {
                MonoText(
                    text = it,
                    color = HomeTokens.textPrimary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (outcome.detail.size > 1 || outcome.hiddenDetailLines > 0) {
                MonoText(
                    text = GitOps.displayCommand(outcome.argv),
                    color = HomeTokens.textDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (outcome.hiddenDetailLines > 0) {
                    MonoText(
                        text = "+${outcome.hiddenDetailLines} more output lines",
                        color = HomeTokens.textDim,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
