package app.pocketshell.widget.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.pocketshell.ui.home.HomeTokens

/**
 * The Git application's drill-down screens: ONE bounded read each, an
 * honest loading/failed state, and every number rendered exactly as git
 * reported it (line numbers are the hunks' own cursors — never re-numbered
 * around a truncation cut).
 */

// ------------------------------------------------------------ diff screen

@Composable
internal fun GitDiffScreen(
    state: GitState,
    repoPath: String,
    target: DiffTarget,
    title: String,
    onBack: () -> Unit,
) {
    val slot = state.diff
    LaunchedEffect(target, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repoPath || cur.target != target || slot.servedEpoch < state.readEpoch) {
            slot.open(DiffReq(repoPath = repoPath, target = target, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req ->
        state.reader.diff(req.repoPath, req.target)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BackHeader(title = "Git", onBack = onBack)
        MonoText(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        MonoText(
            text = when (target) {
                is DiffTarget.Worktree -> "WORKTREE vs INDEX"
                is DiffTarget.Index -> "INDEX vs HEAD"
                is DiffTarget.Commit -> "COMMIT ${target.hash.take(7)}${if (target.path != null) " · ${target.path}" else ""}"
            },
            color = HomeTokens.textDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        when (val ui = slot.ui) {
            ReadUi.Idle, ReadUi.Loading -> StateLine("Loading diff…")
            is ReadUi.Failed -> FailedRead("git diff failed", ui.reason)
            is ReadUi.Done -> DiffBody(ui.value)
        }
    }
}

@Composable
private fun DiffBody(text: DiffText) {
    val parsed = text.parsed
    if (parsed.isEmpty) {
        StateLine("(no textual diff)")
        return
    }
    parsed.files.forEach { file ->
        GitSection(file.displayPath ?: "(unknown path)")
        val facts = mutableListOf<String>()
        if (file.newFile) facts += "new file"
        if (file.deletedFile) facts += "deleted"
        file.renamedFrom?.let { facts += "renamed from $it" }
        if (file.binary) facts += "binary"
        if (facts.isNotEmpty()) {
            MonoText(text = facts.joinToString(" · "), color = HomeTokens.textDim, fontSize = 10.sp)
        }
        file.meta.forEach { line -> MonoText(text = line, color = HomeTokens.textDim, fontSize = 10.sp) }
        val adds = file.additions
        val dels = file.deletions
        if (adds > 0 || dels > 0) {
            MonoText(
                text = "+$adds −$dels",
                color = HomeTokens.textDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        file.hunks.forEach { hunk ->
            MonoText(
                text = hunk.header,
                color = HomeTokens.accent,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
            hunk.lines.forEach { line ->
                if (line.kind == GitDiffParser.Kind.HUNK) return@forEach
                val number = buildString {
                    line.oldLine?.let { append("%4d ".format(it)) }
                    line.newLine?.let { append("%4d ".format(it)) }
                    if (line.oldLine == null && line.newLine == null) append("           ")
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    MonoText(
                        text = number,
                        color = HomeTokens.textDim,
                        fontSize = 9.sp,
                        modifier = Modifier.width(52.dp),
                    )
                    MonoText(
                        text = line.text,
                        color = when (line.kind) {
                            GitDiffParser.Kind.ADD -> HomeTokens.runningGreen
                            GitDiffParser.Kind.DEL -> HomeTokens.danger
                            GitDiffParser.Kind.CONTEXT -> HomeTokens.textPrimary
                            GitDiffParser.Kind.NO_NEWLINE -> HomeTokens.textDim
                            else -> HomeTokens.textDim
                        },
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
    if (text.hidden > 0) {
        MonoText(
            text = "+${text.hidden} more diff lines truncated",
            color = HomeTokens.textDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------- commit screen

@Composable
internal fun GitCommitScreen(
    state: GitState,
    repoPath: String,
    hash: String,
    onBack: () -> Unit,
    onOpenDiff: (DiffTarget, String) -> Unit,
) {
    val slot = state.commit
    LaunchedEffect(hash, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repoPath || cur.hash != hash || slot.servedEpoch < state.readEpoch) {
            slot.open(CommitReq(repoPath = repoPath, hash = hash, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req ->
        state.reader.commitDetail(req.repoPath, req.hash)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BackHeader(title = "Git", onBack = onBack)
        when (val ui = slot.ui) {
            ReadUi.Idle, ReadUi.Loading -> StateLine("Loading commit…")
            is ReadUi.Failed -> FailedRead("git show failed", ui.reason)
            is ReadUi.Done -> {
                val detail = ui.value
                LabelRow("HASH", detail.fullHash)
                LabelRow(
                    "AUTHOR",
                    if (detail.email != null) "${detail.author} <${detail.email}>" else detail.author,
                )
                LabelRow("DATE", detail.relativeDate)
                if (detail.refs != null) LabelRow("REFS", detail.refs)
                MonoText(
                    text = detail.subject,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (detail.body != null) {
                    MonoText(
                        text = detail.body,
                        color = HomeTokens.textDim,
                        fontSize = 11.sp,
                        maxLines = 12,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (detail.files.isNotEmpty()) {
                    GitSection("FILES")
                    detail.files.forEach { file ->
                        val label = if (file.oldPath != null) "${file.oldPath} -> ${file.path}" else file.path
                        FileRow(
                            letter = file.letter,
                            label = label,
                            onClick = { onOpenDiff(DiffTarget.Commit(hash = hash, path = file.path), file.path) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        TextAction("VIEW FULL PATCH", onClick = {
                            onOpenDiff(DiffTarget.Commit(hash = hash, path = null), "commit ${hash.take(7)}")
                        })
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Row {
                        TextAction("VIEW FULL PATCH", onClick = {
                            onOpenDiff(DiffTarget.Commit(hash = hash, path = null), "commit ${hash.take(7)}")
                        })
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ file screen

@Composable
internal fun GitFileScreen(
    state: GitState,
    repoPath: String,
    path: String,
    onBack: () -> Unit,
    onOpenDiff: (DiffTarget, String) -> Unit,
    onOp: (GitOp) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    // The file's pending-change facts come from the dashboard snapshot —
    // no extra exec to know what kind of file this is.
    val snapshot = state.repo(repoPath)
    val entry = snapshot?.status?.entries?.firstOrNull { it.path == path }
    val untracked = entry?.untracked == true
    val letter = entry?.let { GitPresentation.statusLetter(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BackHeader(title = "Git", onBack = onBack)
        MonoText(text = GitFiles.fileName(path), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        MonoText(text = path, color = HomeTokens.textDim, fontSize = 10.sp)
        when (letter) {
            null -> StateLine("No pending change — the file matches the index.")
            else -> Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MonoText(text = "STATUS", color = HomeTokens.textDim, fontSize = 10.sp, modifier = Modifier.width(84.dp))
                MonoText(text = letter.toString(), color = statusColor(letter), fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                MonoText(
                    text = if (untracked) "untracked" else "pending change",
                    color = HomeTokens.textDim,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            TextAction("VIEW DIFF", onClick = { onOpenDiff(DiffTarget.Worktree(path), path) })
            if (untracked) {
                TextAction("STAGE", onClick = { onOp(GitOp.Stage(listOf(path))) })
                TextAction("DELETE", onClick = { onOp(GitOp.DeleteUntracked(absolutePath = "$repoPath/$path")) })
            }
        }
        Row {
            TextAction("TERMINAL", onClick = onOpenTerminal)
        }

        // This file's own history and blame — opt-in bounded reads.
        var showHistory by remember(path) { mutableStateOf(false) }
        var showBlame by remember(path) { mutableStateOf(false) }
        Spacer(Modifier.height(4.dp))
        Row {
            TextAction(if (showHistory) "HIDE HISTORY" else "FILE HISTORY", onClick = { showHistory = !showHistory })
            TextAction(if (showBlame) "HIDE BLAME" else "BLAME", onClick = { showBlame = !showBlame })
        }
        if (showHistory) {
            FileHistorySection(state, repoPath, path)
        }
        if (showBlame) {
            BlameSection(state, repoPath, path)
        }
    }
}

@Composable
private fun FileHistorySection(state: GitState, repoPath: String, path: String) {
    val slot = state.fileHistory
    LaunchedEffect(path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repoPath || cur.path != path || slot.servedEpoch < state.readEpoch) {
            slot.open(FilePathReq(repoPath = repoPath, path = path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req ->
        state.reader.fileHistory(req.repoPath, req.path, limit = 30)
    }
    GitSection("HISTORY")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> StateLine("Reading…")
        is ReadUi.Failed -> FailedRead("git log failed", ui.reason)
        is ReadUi.Done -> {
            val commits = ui.value
            if (commits.isEmpty()) {
                StateLine("No commits touch this file.")
            } else {
                commits.forEach { entry ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        MonoText(text = entry.hash, color = HomeTokens.textDim, modifier = Modifier.width(64.dp))
                        MonoText(text = entry.subject, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlameSection(state: GitState, repoPath: String, path: String) {
    val slot = state.blame
    LaunchedEffect(path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repoPath || cur.path != path || slot.servedEpoch < state.readEpoch) {
            slot.open(FilePathReq(repoPath = repoPath, path = path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req ->
        state.reader.blame(req.repoPath, req.path)
    }
    GitSection("BLAME")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> StateLine("Reading…")
        is ReadUi.Failed -> FailedRead("git blame failed", ui.reason)
        is ReadUi.Done -> {
            ui.value.lines.forEach { line ->
                MonoText(text = line, fontSize = 9.sp, maxLines = 1)
            }
            CapFooter(hidden = ui.value.hidden, what = "blame lines")
        }
    }
}
