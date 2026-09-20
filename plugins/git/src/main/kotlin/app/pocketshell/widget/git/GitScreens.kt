package app.pocketshell.widget.git

/**
 * The M9 redesign's INFORMATION ARCHITECTURE — the model behind a real
 * small-screen Git application instead of one giant card scroll:
 *
 *   HOME (the launch surface: what repos matter, is anything dirty)
 *    └── REPO (a focused repository surface with five tabs)
 *         ├── CHANGES   → DIFF (file) → stage/unstage/discard
 *         ├── HISTORY   → COMMIT (files) → DIFF (at commit)
 *         ├── BRANCHES  → checkout / create / merge / delete dialogs
 *         ├── FILES     → navigate dirs → VIEWER (file preview)
 *         └── REMOTES   → fetch / pull / push (confirmed)
 *    plus STASH (a repo's stash list, pushed from the repo menu)
 *
 * [stack] in [GitState] is the navigation stack the application owns —
 * Home is the implicit root, every push appends, back pops the innermost
 * screen, and only a pop from the root's back handler leaves the card.
 * Screens are data: surviving swipe-away/rotation comes free from the
 * process-scoped HomeAppStateStore slot, exactly like the old detail page.
 */
internal sealed interface GitScreen {

    /** One open repository surface (its active tab lives in [GitState]). */
    data class Repo(val path: String) : GitScreen

    /**
     * One open diff. [siblings] carries the file list the diff was opened
     * from (Changes' rows, or a commit's files) with this file's index —
     * the ◀ ▶ navigation between changed files without going back.
     */
    data class Diff(
        val repoPath: String,
        val path: String,
        /** True = the index side (`--cached`); false = the worktree side. */
        val staged: Boolean,
        /** Untracked paths have no diff — the page states that, no exec. */
        val untracked: Boolean,
        /** Non-null = this diff belongs to that commit (`git show <hash> -- path`). */
        val atCommit: String?,
        val siblings: List<Sibling>,
        val index: Int,
    ) : GitScreen

    /** One open commit page (`git show --numstat`). */
    data class Commit(val repoPath: String, val hash: String) : GitScreen

    /** One open file preview (the Files tab's read-only viewer). */
    data class Viewer(val repoPath: String, val path: String) : GitScreen

    /** One open stash surface (list + stash/pop/drop). */
    data class Stash(val repoPath: String) : GitScreen

    /** One changed file in the list a diff was opened from. */
    data class Sibling(val path: String, val staged: Boolean, val untracked: Boolean)
}

/** The five repository tabs — each a focused screen, never one long scroll. */
internal enum class RepoTab { CHANGES, HISTORY, BRANCHES, FILES, REMOTES }

/** The git operations the UI can ask for (each = one confirmed tap = one exec). */
internal enum class OpKind {
    STAGE, STAGE_ALL, UNSTAGE, UNSTAGE_ALL, DISCARD,
    COMMIT, CHECKOUT, CREATE_BRANCH, DELETE_BRANCH, MERGE,
    FETCH, PULL, PUSH,
    STASH_PUSH, STASH_POP, STASH_DROP,
}

/** One queued operation: kind + repo + its argument (path/branch/message/index). */
internal data class OpReq(val kind: OpKind, val repoPath: String, val arg: String?, val serial: Int)

/** The History tab's state for one repository: accumulated pages + honesty flags. */
internal data class HistoryUi(
    val entries: List<LogEntry> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val nextSkip: Int get() = entries.size
}

/** The Files tab's state for one repository: the open directory + its listing. */
internal data class FilesUi(
    /** Absolute guest directory path — the listing's source of truth. */
    val dir: String,
    val entries: List<DirEntry>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** The application's screen state (probe-driven, never invented). */
internal sealed interface GitUi {
    data object Probing : GitUi
    data object Unavailable : GitUi
    data class ProbeFailed(val reason: String) : GitUi
    data class Ready(val snapshot: GitSnapshot) : GitUi
}

internal fun scanToUi(result: ScanResult): GitUi = when (result) {
    is ScanResult.Done -> GitUi.Ready(result.snapshot)
    is ScanResult.Failed -> GitUi.ProbeFailed(result.reason)
}

/** The commit page's state: loading, the parsed facts, or the real error. */
internal sealed interface CommitUi {
    data object Idle : CommitUi
    data object Loading : CommitUi
    data class Done(val detail: CommitDetail) : CommitUi
    data class Failed(val reason: String) : CommitUi
}

/** The diff page's state: loading, the capped text, or an honest absence. */
internal sealed interface DiffUi {
    data object Idle : DiffUi
    data object Loading : DiffUi
    data class Done(val text: DiffText) : DiffUi

    /** An untracked file has no diff — stated, never faked with output. */
    data object Untracked : DiffUi
    data class Failed(val reason: String) : DiffUi
}

/** The viewer page's state. */
internal sealed interface ViewerUi {
    data object Idle : ViewerUi
    data object Loading : ViewerUi
    data class Done(val page: TextPage) : ViewerUi
    data class Failed(val reason: String) : ViewerUi
}

/** The stash screen's state. */
internal sealed interface StashUi {
    data object Idle : StashUi
    data object Loading : StashUi
    data class Done(val entries: List<StashEntry>) : StashUi
    data class Failed(val reason: String) : StashUi
}

/**
 * What a confirmation dialog says for one operation — PURE, JVM-tested.
 * Destructive or risky operations are never sent without one; quiet,
 * reversible operations return null (no dialog). The wording names the
 * git verb and the real consequence, never a vague "are you sure".
 */
internal data class OpConfirmation(
    val title: String,
    val body: String,
    val confirmLabel: String,
    /** Destructive confirmations render their button in the theme's danger tone. */
    val destructive: Boolean,
)

/** One screen-held pending operation: the verb, its argument, its dialog. */
internal data class PendingConfirm(val kind: OpKind, val arg: String?, val confirmation: OpConfirmation)

internal fun opConfirmation(kind: OpKind, arg: String?): OpConfirmation? = when (kind) {
    OpKind.DISCARD -> OpConfirmation(
        title = "Discard changes?",
        body = "git restore -- $arg — uncommitted changes to this file will be lost.",
        confirmLabel = "Discard",
        destructive = true,
    )
    OpKind.DELETE_BRANCH -> OpConfirmation(
        title = "Delete branch?",
        body = "git branch -d $arg — git refuses if it is not merged; the commits stay.",
        confirmLabel = "Delete",
        destructive = true,
    )
    OpKind.MERGE -> OpConfirmation(
        title = "Merge branch?",
        body = "git merge $arg — on conflicts the Changes tab shows them; " +
            "resolve in a terminal or abort with git merge --abort.",
        confirmLabel = "Merge",
        destructive = false,
    )
    OpKind.PULL -> OpConfirmation(
        title = "Pull?",
        body = "git pull — fetches and integrates the upstream branch into this one.",
        confirmLabel = "Pull",
        destructive = false,
    )
    OpKind.PUSH -> OpConfirmation(
        title = "Push?",
        body = "git push — publishes this branch's commits to its upstream.",
        confirmLabel = "Push",
        destructive = false,
    )
    OpKind.STASH_POP -> OpConfirmation(
        title = "Apply stash?",
        body = "git stash pop — applies this entry onto the working tree and drops it; " +
            "it may conflict with current changes.",
        confirmLabel = "Pop",
        destructive = false,
    )
    OpKind.STASH_DROP -> OpConfirmation(
        title = "Drop stash?",
        body = "git stash drop — this stashed snapshot will be gone.",
        confirmLabel = "Drop",
        destructive = true,
    )
    else -> null
}
