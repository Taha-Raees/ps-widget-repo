package app.pocketshell.widget.git

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme
import androidx.compose.foundation.clickable

/**
 * The DETAIL screens — each owns ONE artifact and earns its own page:
 *
 *   [GitDiffScreen]   one file's diff — working change or commit-side —
 *                     colored from the shared theme (accent adds, danger
 *                     deletes), with ◀ ▶ across the file list it came
 *                     from, and the change's own verbs (stage / unstage /
 *                     discard) as a compact action line.
 *   [GitCommitScreen] one commit's facts (--numstat): author, message,
 *                     changed files with real +/- counts; a file tap
 *                     opens that file's diff AT the commit.
 *   [GitViewerScreen] one worktree file, honestly capped (+ size, the
 *                     truncation fact, binary detection).
 *   [GitStashScreen]  one repo's stash: list, push (with label),
 *                     pop/drop behind confirmations.
 */
@Composable
internal fun GitDiffScreen(
    state: GitState,
    screen: GitScreen.Diff,
    layout: GitLayout,
) {
    val opQueue = remember { GitOpQueue() }
    val repoPath = screen.repoPath
    Column(modifier = Modifier.fillMaxSize()) {
        GitTopBar(
            title = screen.path,
            onBack = { state.pop() },
        ) {
            // ◀ ▶ across the file list this diff was opened from.
            GitStepIcon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                "Previous changed file",
                enabled = screen.index > 0,
            ) {
                state.replaceTop(screen.at(index = screen.index - 1))
            }
            GitStepIcon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                "Next changed file",
                enabled = screen.index < screen.siblings.lastIndex,
            ) {
                state.replaceTop(screen.at(index = screen.index + 1))
            }
        }
        Text(
            text = when {
                screen.atCommit != null -> "commit ${screen.atCommit}"
                screen.untracked -> "untracked"
                screen.staged -> "staged"
                else -> "worktree"
            },
            fontFamily = TerminalTheme.mono,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = HomeTokens.textDim,
        )
        when (val ui = state.diffUi) {
            DiffUi.Idle, DiffUi.Loading -> StateBody(line = "Loading diff…")
            DiffUi.Untracked -> StateBody(
                line = "Untracked file — nothing to diff until it is staged.",
                actionLabel = "Stage file",
                onAction = { opQueue.run(state, repoPath, OpKind.STAGE, screen.path) },
            )
            is DiffUi.Failed -> StateBody(line = "git diff failed", detail = ui.reason)
            is DiffUi.Done -> DiffBody(ui.text)
        }
        // The working change's verbs — a commit's diff has none (it is history).
        if (screen.atCommit == null) {
            Row(modifier = Modifier.padding(top = 2.dp)) {
                if (screen.staged) {
                    TextAction("UNSTAGE") { opQueue.run(state, repoPath, OpKind.UNSTAGE, screen.path) }
                } else if (!screen.untracked) {
                    TextAction("STAGE") { opQueue.run(state, repoPath, OpKind.STAGE, screen.path) }
                    TextAction("DISCARD") { opQueue.run(state, repoPath, OpKind.DISCARD, screen.path) }
                }
            }
        }
    }
    GitOpConfirmDialog(queue = opQueue, state = state, repoPath = repoPath)
}

/** The diff page for one sibling index — same repo, same list, new file. */
private fun GitScreen.Diff.at(index: Int): GitScreen.Diff = copy(path = siblings[index].path, staged = siblings[index].staged, untracked = siblings[index].untracked, index = index)

/** The colored, bounded, two-axis-scrollable diff body. */
@Composable
private fun DiffBody(text: DiffText) {
    if (text.lines.isEmpty()) {
        StateBody(line = "(no textual diff)")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(top = 2.dp),
    ) {
        text.lines.forEach { line ->
            val kind = GitPresentation.diffLineKind(line)
            Text(
                text = line,
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = when (kind) {
                    GitPresentation.DiffLineKind.ADD -> HomeTokens.accent
                    GitPresentation.DiffLineKind.DEL -> HomeTokens.danger
                    GitPresentation.DiffLineKind.HUNK,
                    GitPresentation.DiffLineKind.META,
                    -> HomeTokens.textDim
                    GitPresentation.DiffLineKind.CONTEXT -> HomeTokens.textPrimary
                },
                softWrap = false,
                maxLines = 1,
            )
        }
        if (text.hidden > 0) {
            Text(
                text = "+${text.hidden} more lines truncated",
                fontFamily = TerminalTheme.mono,
                fontSize = 10.sp,
                color = HomeTokens.textDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun GitStepIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (enabled) HomeTokens.accent else HomeTokens.textDim,
        modifier = Modifier
            .padding(2.dp)
            .width(26.dp)
            .clickable(role = Role.Button, onClickLabel = label, enabled = enabled) { onClick() },
    )
}

// ---------------------------------------------------------------- commit

@Composable
internal fun GitCommitScreen(
    state: GitState,
    screen: GitScreen.Commit,
    layout: GitLayout,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        GitTopBar(
            title = screen.hash,
            subtitle = "git show",
            onBack = { state.pop() },
        )
        when (val ui = state.commitUi) {
            CommitUi.Idle, CommitUi.Loading -> StateBody(line = "Loading commit…")
            is CommitUi.Failed -> StateBody(line = "git show failed", detail = ui.reason)
            is CommitUi.Done -> {
                val detail = ui.detail
                // The facts stay a FIXED header (a long message body is
                // capped, not scrolled); the changed-file list owns the
                // remaining space and is the only thing that scrolls.
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = detail.subject,
                        fontFamily = TerminalTheme.mono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HomeTokens.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = buildString {
                            append(detail.author)
                            if (detail.email != null) append(" <${detail.email}>")
                            append(" · ").append(detail.relativeDate)
                        },
                        fontFamily = TerminalTheme.mono,
                        fontSize = 10.sp,
                        color = HomeTokens.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.body.isNotEmpty()) {
                        val bodyLines = detail.body.take(COMMIT_BODY_MAX_LINES)
                        Text(
                            text = bodyLines.joinToString("\n"),
                            fontFamily = TerminalTheme.mono,
                            fontSize = 10.sp,
                            color = HomeTokens.textDim,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (detail.body.size > bodyLines.size) {
                            Text(
                                text = "+${detail.body.size - bodyLines.size} more body lines",
                                fontFamily = TerminalTheme.mono,
                                fontSize = 10.sp,
                                color = HomeTokens.textDim,
                            )
                        }
                    }
                    Text(
                        text = "FILES (${detail.files.size})",
                        fontFamily = TerminalTheme.mono,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        color = HomeTokens.textDim,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                val siblings = detail.files.map {
                    GitScreen.Sibling(it.path, staged = false, untracked = false)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    detail.files.forEachIndexed { fileIndex, file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Show diff for ${file.path}",
                                ) {
                                    state.push(
                                        GitScreen.Diff(
                                            repoPath = screen.repoPath,
                                            path = file.path,
                                            staged = false,
                                            untracked = false,
                                            atCommit = screen.hash,
                                            siblings = siblings,
                                            index = fileIndex,
                                        ),
                                    )
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = file.path,
                                fontFamily = TerminalTheme.mono,
                                fontSize = 10.sp,
                                color = HomeTokens.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = commitCounts(file),
                                fontFamily = TerminalTheme.mono,
                                fontSize = 10.sp,
                                color = HomeTokens.textDim,
                            )
                        }
                    }
                    if (detail.hiddenFiles > 0) {
                        Text(
                            text = "+${detail.hiddenFiles} more files truncated",
                            fontFamily = TerminalTheme.mono,
                            fontSize = 10.sp,
                            color = HomeTokens.textDim,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "+12 −3" for a text file, "bin" when git reports "-" (binary). */
internal fun commitCounts(file: CommitFile): String = when {
    file.added == "-" || file.deleted == "-" -> "bin"
    else -> "+${file.added} −${file.deleted}"
}

/** The commit page shows at most this many body lines before it summarizes. */
internal const val COMMIT_BODY_MAX_LINES = 10

// ---------------------------------------------------------------- viewer

@Composable
internal fun GitViewerScreen(state: GitState, screen: GitScreen.Viewer) {
    val appContext = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        GitTopBar(
            title = screen.path,
            subtitle = displayGuestRepoPath(screen.repoPath),
            onBack = { state.pop() },
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy path",
                tint = HomeTokens.accent,
                modifier = Modifier
                    .padding(2.dp)
                    .width(26.dp)
                    .clickable(role = Role.Button, onClickLabel = "Copy path") {
                        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("path", viewerFilePath(screen)),
                        )
                        state.opResult = GitOps.OpResult.Ok("Path copied — ${viewerFilePath(screen)}")
                    },
            )
        }
        when (val ui = state.viewerUi) {
            ViewerUi.Idle, ViewerUi.Loading -> StateBody(line = "Reading…")
            is ViewerUi.Failed -> StateBody(line = "Could not read file", detail = ui.reason)
            is ViewerUi.Done -> {
                val page = ui.page
                Text(
                    text = buildString {
                        if (page.sizeBytes != null) {
                            append(formatBytes(page.sizeBytes))
                            append(" · ")
                        }
                        if (page.isBinary) {
                            append("binary — not shown as text")
                        } else if (page.truncated) {
                            append("first ${page.lines.size} lines")
                        } else {
                            append("${page.lines.size} lines")
                        }
                    },
                    fontFamily = TerminalTheme.mono,
                    fontSize = 10.sp,
                    color = HomeTokens.textDim,
                    modifier = Modifier.padding(top = 1.dp),
                )
                if (!page.isBinary && page.lines.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        page.lines.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = TerminalTheme.mono,
                                fontSize = 10.sp,
                                color = HomeTokens.textPrimary,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Bytes the way a workstation says them (KB/MB, one decimal). */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

// ----------------------------------------------------------------- stash

@Composable
internal fun GitStashScreen(state: GitState, screen: GitScreen.Stash) {
    val opQueue = remember { GitOpQueue() }
    var labelDialog by remember { mutableStateOf(false) }
    var choicesFor by remember { mutableStateOf<StashEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        GitTopBar(
            title = "Stash",
            subtitle = displayGuestRepoPath(screen.repoPath),
            onBack = { state.pop() },
        ) {
            TextAction("STASH CHANGES") { labelDialog = true }
        }
        when (val ui = state.stashUi) {
            StashUi.Idle, StashUi.Loading -> StateBody(line = "Loading stash…")
            is StashUi.Failed -> StateBody(line = "git stash list failed", detail = ui.reason)
            is StashUi.Done -> {
                if (ui.entries.isEmpty()) {
                    StateBody(
                        line = "No stash entries",
                        detail = "STASH CHANGES puts staged + unstaged work away and cleans the tree.",
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ui.entries.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "Stash entry stash@{${entry.index}}",
                                    ) { choicesFor = entry }
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "@{${entry.index}}",
                                    fontFamily = TerminalTheme.mono,
                                    fontSize = 10.sp,
                                    color = HomeTokens.accent,
                                    modifier = Modifier.width(34.dp),
                                )
                                Text(
                                    text = entry.subject,
                                    fontFamily = TerminalTheme.mono,
                                    fontSize = 10.sp,
                                    color = HomeTokens.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    GitOpConfirmDialog(queue = opQueue, state = state, repoPath = screen.repoPath)
    if (labelDialog) {
        GitInput(
            title = "Stash working changes",
            confirmLabel = "Stash",
            placeholder = "label (optional)",
            onConfirm = { label ->
                labelDialog = false
                state.queueOp(OpKind.STASH_PUSH, screen.repoPath, label)
            },
            onDismiss = { labelDialog = false },
        )
    }
    val entry = choicesFor
    if (entry != null) {
        GitChoices(
            title = "stash@{${entry.index}}",
            subtitle = entry.subject,
            choices = listOf(
                "Pop (apply and drop)" to {
                    opQueue.run(state, screen.repoPath, OpKind.STASH_POP, entry.index.toString())
                },
                "Drop" to {
                    opQueue.run(state, screen.repoPath, OpKind.STASH_DROP, entry.index.toString())
                },
            ),
            onDismiss = { choicesFor = null },
        )
    }
}
