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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens

/**
 * The GitHub layer's screens: the workspace's GitHub tab and one pull
 * request's page. Everything here is OPTIONAL by design — the tab renders
 * four honest states (no GitHub remote / gh not installed / not signed in
 * / a real failure) before it renders a single list, and the ops all ride
 * the same confirmed pipeline as every other mutation.
 */

// ----------------------------------------------------------- github tab

@Composable
internal fun GitGitHubTab(
    repo: RepoSnapshot,
    state: GitState,
    onOp: (GitOp) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val slug = GitHost.githubSlug(repo.remotes)
    if (slug == null) {
        StateLine("No GitHub remote detected — this tab follows the repository's origin.")
        return
    }
    MonoText(
        text = slug,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )

    val slot = state.hostStatus
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { _ -> ReadResult.Done(state.host.status()) }

    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> StateLine("Checking gh…")
        is ReadUi.Failed -> FailedRead("gh detection failed", ui.reason)
        is ReadUi.Done -> {
            when (val status = ui.value) {
                GitHost.HostStatus.NotInstalled -> {
                    StateLine("gh is not installed in the guest — install it to see pull requests, issues and runs here.")
                    Row {
                        TextAction("LINUX", onClick = onOpenTerminal)
                    }
                }
                GitHost.HostStatus.NotAuthed -> {
                    StateLine("gh is installed but not signed in — run gh auth login in a terminal, then refresh.")
                    Row {
                        TextAction("TERMINAL", onClick = onOpenTerminal)
                    }
                }
                is GitHost.HostStatus.Failed -> FailedRead("gh failed", status.reason)
                GitHost.HostStatus.Ready -> {
                    PrsSection(repo, slug, state, onOp, onOpenPr = { number ->
                        state.open(Screen.PrPage(repoPath = repo.path, slug = slug, number = number))
                    })
                    IssuesSection(repo, slug, state, onOp)
                    RunsSection(repo, slug, state)
                    ReleasesSection(repo, slug, state)
                    NotesSection(repo, slug, state)
                }
            }
        }
    }
}

// ------------------------------------------------------------ PR lists

@Composable
private fun PrsSection(
    repo: RepoSnapshot,
    slug: String,
    state: GitState,
    onOp: (GitOp) -> Unit,
    onOpenPr: (Int) -> Unit,
) {
    val slot = state.prs
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { _ -> state.host.pullRequests(slug) }

    var creating by remember(repo.path) { mutableStateOf(false) }
    if (creating) {
        CommitDialog(
            headline = "New pull request",
            subjectLabel = "Title",
            bodyLabel = "Description (optional)",
            confirmWord = "OPEN PR",
            onCommit = { title, body, _ ->
                creating = false
                val head = repo.status?.branch
                if (head != null) {
                    onOp(GitOp.GhPrCreate(slug = slug, head = head, title = title, body = body))
                }
            },
            onDismiss = { creating = false },
        )
    }

    GitSection("PULL REQUESTS")
    Row {
        TextAction("NEW PR", onClick = { creating = true })
    }
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> FailedRead("gh pr list failed", ui.reason)
        is ReadUi.Done -> {
            val prs = ui.value
            if (prs.isEmpty()) {
                StateLine("No open pull requests.")
            } else {
                prs.forEach { pr ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Pull request #${pr.number}") {
                                onOpenPr(pr.number)
                            }
                            .padding(vertical = 2.dp),
                    ) {
                        MonoText(text = "#${pr.number}", color = HomeTokens.accent, modifier = Modifier.width(48.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            MonoText(text = pr.title)
                            MonoText(
                                text = "${pr.author} · ${pr.head}",
                                color = HomeTokens.textDim,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------- issue lists

@Composable
private fun IssuesSection(repo: RepoSnapshot, slug: String, state: GitState, onOp: (GitOp) -> Unit) {
    val slot = state.issues
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { _ -> state.host.issues(slug) }

    var creating by remember(repo.path) { mutableStateOf(false) }
    var sheetIssue by remember(repo.path) { mutableStateOf<GitHost.IssueSummary?>(null) }
    if (creating) {
        CommitDialog(
            headline = "New issue",
            subjectLabel = "Title",
            bodyLabel = "Description (optional)",
            confirmWord = "OPEN ISSUE",
            onCommit = { title, body, _ ->
                creating = false
                onOp(GitOp.GhIssueCreate(slug = slug, title = title, body = body))
            },
            onDismiss = { creating = false },
        )
    }
    sheetIssue?.let { issue ->
        ActionSheet(
            title = "#${issue.number} ${issue.title}",
            actions = if (issue.state == "OPEN") {
                listOf("Close issue" to { onOp(GitOp.GhIssueClose(slug = slug, number = issue.number)) })
            } else {
                listOf("Already ${issue.state.lowercase()}" to {})
            },
            onDismiss = { sheetIssue = null },
        )
    }

    GitSection("ISSUES")
    Row {
        TextAction("NEW ISSUE", onClick = { creating = true })
    }
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> FailedRead("gh issue list failed", ui.reason)
        is ReadUi.Done -> {
            val issues = ui.value
            if (issues.isEmpty()) {
                StateLine("No issues.")
            } else {
                issues.forEach { issue ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Issue #${issue.number}") {
                                sheetIssue = issue
                            }
                            .padding(vertical = 2.dp),
                    ) {
                        MonoText(text = "#${issue.number}", color = HomeTokens.textDim, modifier = Modifier.width(48.dp))
                        MonoText(text = issue.title, modifier = Modifier.weight(1f))
                        MonoText(
                            text = issue.state.lowercase(),
                            color = if (issue.state == "OPEN") HomeTokens.accent else HomeTokens.textDim,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------- runs / releases

@Composable
private fun RunsSection(repo: RepoSnapshot, slug: String, state: GitState) {
    val slot = state.runs
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { _ -> state.host.runs(slug) }
    GitSection("ACTIONS")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> StateLine("No Actions runs (or the repository has no workflows).")
        is ReadUi.Done -> {
            val runs = ui.value
            if (runs.isEmpty()) {
                StateLine("No workflow runs.")
            } else {
                runs.forEach { run ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        MonoText(
                            text = run.glyph.toString(),
                            color = if (run.glyph == '✓') {
                                HomeTokens.runningGreen
                            } else if (run.glyph == '✗') {
                                HomeTokens.danger
                            } else {
                                HomeTokens.textDim
                            },
                            modifier = Modifier.width(16.dp),
                        )
                        MonoText(text = run.title, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        MonoText(text = run.workflow, color = HomeTokens.textDim, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleasesSection(repo: RepoSnapshot, slug: String, state: GitState) {
    val slot = state.releases
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { _ -> state.host.releases(slug) }
    GitSection("RELEASES")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> StateLine("No releases.")
        is ReadUi.Done -> {
            val releases = ui.value
            if (releases.isEmpty()) {
                StateLine("No releases.")
            } else {
                releases.forEach { release ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        MonoText(text = release.tag, modifier = Modifier.width(96.dp))
                        MonoText(
                            text = "${release.name} · ${release.date}",
                            color = HomeTokens.textDim,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesSection(repo: RepoSnapshot, slug: String, state: GitState) {
    val slot = state.notes
    LaunchedEffect(repo.path, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repo.path || slot.servedEpoch < state.readEpoch) {
            slot.open(RepoReq(repoPath = repo.path, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { _ -> state.host.notifications() }
    GitSection("NOTIFICATIONS")
    when (val ui = slot.ui) {
        ReadUi.Idle, ReadUi.Loading -> Unit
        is ReadUi.Failed -> StateLine("Notifications unavailable.")
        is ReadUi.Done -> {
            val notes = ui.value
            if (notes.isEmpty()) {
                StateLine("Nothing unread.")
            } else {
                notes.forEach { note ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        MonoText(text = note.title, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        MonoText(text = note.repo, color = HomeTokens.textDim, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ PR page

@Composable
internal fun GitPrScreen(
    state: GitState,
    repoPath: String,
    slug: String,
    number: Int,
    onBack: () -> Unit,
    onOp: (GitOp) -> Unit,
) {
    val slot = state.prDetail
    LaunchedEffect(number, state.readEpoch) {
        val cur = slot.req
        if (cur == null || cur.repoPath != repoPath || cur.number != number || slot.servedEpoch < state.readEpoch) {
            slot.open(PrReq(repoPath = repoPath, slug = slug, number = number, serial = state.navSerial))
        }
    }
    ReadEffect(slot = slot, epoch = state.readEpoch) { req ->
        state.host.prDetail(req.slug, req.number)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BackHeader(title = "Git", onBack = onBack)
        when (val ui = slot.ui) {
            ReadUi.Idle, ReadUi.Loading -> StateLine("Loading pull request…")
            is ReadUi.Failed -> FailedRead("gh pr view failed", ui.reason)
            is ReadUi.Done -> {
                val pr = ui.value
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText(text = "#${pr.number}", color = HomeTokens.accent, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    MonoText(
                        text = pr.state.uppercase(),
                        color = if (pr.state == "OPEN") HomeTokens.accent else HomeTokens.textDim,
                        fontSize = 10.sp,
                    )
                }
                MonoText(
                    text = pr.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp),
                )
                LabelRow("AUTHOR", pr.author)
                LabelRow("BRANCH", "${pr.head} → ${pr.base}")
                if (pr.reviewDecision != "-") LabelRow("REVIEW", pr.reviewDecision)
                LabelRow("MERGEABLE", pr.mergeable)
                if (pr.body != null) {
                    GitSection("DESCRIPTION")
                    MonoText(
                        text = pr.body,
                        color = HomeTokens.textDim,
                        fontSize = 11.sp,
                        maxLines = 20,
                    )
                }
                if (pr.state == "OPEN") {
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextAction("MERGE", onClick = { onOp(GitOp.GhPrMerge(slug = slug, number = number)) })
                        TextAction(
                            "SQUASH MERGE",
                            onClick = { onOp(GitOp.GhPrMerge(slug = slug, number = number, squash = true)) },
                        )
                    }
                }
            }
        }
    }
}
