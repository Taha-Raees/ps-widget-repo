package app.pocketshell.widget.git

/**
 * THE mutation seam. Every state-changing git command this application can
 * run is described here, as an ARGV LIST — never a shell string, so a path
 * or ref can only ever be execve bytes, not syntax. This is the only file
 * in the module that names a mutating git verb, which is what makes the
 * read-only probe provable: the dashboard's batched scan and the manual
 * read execs stay read-only (pinned by GitAppContractTest), and anything
 * that changes the index, the worktree or the refs comes through [GitOp].
 *
 * Three facts travel WITH each op instead of being re-derived by the UI:
 *
 *   - [GitOp.network] — remote contact: a longer timeout and network wording;
 *   - [GitOp.destructive] — git cannot restore what it removes (a discarded
 *     worktree change, a force-deleted branch, a dropped stash, a rebase);
 *   - [GitOps.confirm] — the confirm policy: destructive ops ALWAYS ask, and
 *     so do the reversible-but-consequential ones (merge, pull, push, pop).
 *
 * Truthfulness rule for every caller: an op is reported as done only when
 * git exits 0 ([GitOpOutcome.success]); the UI follows a mutation with a
 * forced rescan and shows git's own words, never an optimistic guess.
 */
internal sealed interface GitOp {

    /** Machine word for the family (used for grouping and analytics-free logs). */
    val kind: String

    /** True when the op contacts a remote (longer timeout, network wording). */
    val network: Boolean get() = false

    /** True when running it can destroy work git cannot bring back. */
    val destructive: Boolean get() = false

    /** The exact argv to run (`git -C <repo> …`). Never a shell string. */
    fun argv(repoPath: String): List<String>

    // ------------------------------------------------------------- staging

    /** `git add -- <paths>` — stage the given paths, whatever their state. */
    data class Stage(val paths: List<String>) : GitOp {
        override val kind = "stage"
        override fun argv(repoPath: String) = base(repoPath, "add", "--", *paths.toTypedArray())
    }

    /** `git add -A` — stage every change in the repository (index = worktree). */
    data object StageAll : GitOp {
        override val kind = "stage-all"
        override fun argv(repoPath: String) = base(repoPath, "add", "-A")
    }

    /** `git restore --staged -- <paths>` — return paths to the index's HEAD state. */
    data class Unstage(val paths: List<String>) : GitOp {
        override val kind = "unstage"
        override fun argv(repoPath: String) = base(repoPath, "restore", "--staged", "--", *paths.toTypedArray())
    }

    /**
     * Unstage everything. A repository with no commits yet has no HEAD to
     * restore from (`git restore --staged` fails with "could not resolve
     * 'HEAD'"), so there the honest equivalent is removing the entries from
     * the index only — `git rm --cached -r`, which never touches the files.
     */
    data class UnstageAll(val unbornHead: Boolean) : GitOp {
        override val kind = "unstage-all"
        override fun argv(repoPath: String) = if (unbornHead) {
            base(repoPath, "rm", "--cached", "-r", "-q", "--", ".")
        } else {
            base(repoPath, "restore", "--staged", "--", ".")
        }
    }

    // ------------------------------------------------------------ worktree

    /** `git restore --worktree -- <path>` — discard a tracked path's edits. */
    data class DiscardFile(val path: String) : GitOp {
        override val kind = "discard"
        override val destructive = true
        override fun argv(repoPath: String) = base(repoPath, "restore", "--worktree", "--", path)
    }

    /** `git restore --worktree -- .` — discard every TRACKED worktree change. */
    data object DiscardTrackedAll : GitOp {
        override val kind = "discard-all"
        override val destructive = true
        override fun argv(repoPath: String) = base(repoPath, "restore", "--worktree", "--", ".")
    }

    /**
     * Delete an UNTRACKED file the user explicitly named. Git has no verb
     * for this (the file is not in the index), so it is a plain `rm` — hence
     * the absolute guest path requirement and the destructive mark.
     */
    data class DeleteUntracked(val absolutePath: String) : GitOp {
        override val kind = "delete-untracked"
        override val destructive = true
        override fun argv(repoPath: String) = listOf("rm", "-f", "--", absolutePath)
    }

    // -------------------------------------------------------------- commit

    /**
     * `git commit -m <subject> [-m <body>]` — commit what is staged now.
     * With [amend], `--amend` rewrites the tip commit in place: history
     * that exists only locally, so it is destructive by policy (pushed
     * history must not be amended — git will still refuse a non-ff push,
     * which is the honest backstop).
     */
    data class Commit(
        val subject: String,
        val body: String = "",
        val amend: Boolean = false,
    ) : GitOp {
        override val kind = "commit"
        override val destructive: Boolean get() = amend
        override fun argv(repoPath: String): List<String> {
            val argv = base(repoPath, "commit")
            val withAmend = if (amend) argv + listOf("--amend") else argv
            val withSubject = withAmend + listOf("-m", subject)
            return if (body.isBlank()) withSubject else withSubject + listOf("-m", body)
        }
    }

    // -------------------------------------------------------- history edits

    /** `git cherry-pick <hash>` — apply one commit's change on top. */
    data class CherryPick(val hash: String) : GitOp {
        override val kind = "cherry-pick"
        override fun argv(repoPath: String) = base(repoPath, "cherry-pick", hash)
    }

    /** `git revert --no-edit <hash>` — a new commit undoing one commit. */
    data class Revert(val hash: String) : GitOp {
        override val kind = "revert"
        override fun argv(repoPath: String) = base(repoPath, "revert", "--no-edit", hash)
    }

    /** The reset modes — SOFT/MIXED keep the work; HARD throws it away. */
    enum class ResetMode(val flag: String, val destructive: Boolean) {
        SOFT("--soft", false),
        MIXED("--mixed", false),
        HARD("--hard", true),
    }

    /**
     * `git reset --<mode> <ref>` — move the branch. SOFT/MIXED keep every
     * byte of work (the index/worktree split changes); HARD discards both
     * for everything after <ref> — destructive, always confirmed.
     */
    data class Reset(val ref: String, val mode: ResetMode) : GitOp {
        override val kind = "reset"
        override val destructive: Boolean get() = mode.destructive
        override fun argv(repoPath: String) = base(repoPath, "reset", mode.flag, ref)
    }

    /** `git switch --detach <hash>` — check a commit out (detached HEAD). */
    data class CheckoutCommit(val hash: String) : GitOp {
        override val kind = "checkout-commit"
        override fun argv(repoPath: String) = base(repoPath, "switch", "--detach", hash)
    }

    // ----------------------------------------------------------------- tags

    /** `git tag <name> [<hash>]` — a lightweight tag at HEAD (or <hash>). */
    data class TagCreate(val name: String, val hash: String? = null) : GitOp {
        override val kind = "tag-create"
        override fun argv(repoPath: String): List<String> {
            val argv = base(repoPath, "tag", name)
            return if (hash.isNullOrBlank()) argv else argv + listOf(hash)
        }
    }

    /** `git tag -d <name>` — delete a tag (the commit survives). */
    data class TagDelete(val name: String) : GitOp {
        override val kind = "tag-delete"
        override val destructive = true
        override fun argv(repoPath: String) = base(repoPath, "tag", "-d", name)
    }

    // ------------------------------------------------------------ branches

    /** `git switch <name>` — check out an existing branch (`git` refuses a dirty collision). */
    data class SwitchBranch(val name: String) : GitOp {
        override val kind = "switch"
        override fun argv(repoPath: String) = base(repoPath, "switch", name)
    }

    /** `git switch -c <name> [<base>]` — create a branch and check it out. */
    data class CreateBranch(val name: String, val base: String? = null) : GitOp {
        override val kind = "create-branch"
        override fun argv(repoPath: String): List<String> {
            val argv = base(repoPath, "switch", "-c", name)
            return if (base.isNullOrBlank()) argv else argv + listOf(base)
        }
    }

    /** `git branch -m <from> <to>` — rename a local branch. */
    data class RenameBranch(val from: String, val to: String) : GitOp {
        override val kind = "rename-branch"
        override fun argv(repoPath: String) = base(repoPath, "branch", "-m", from, to)
    }

    /**
     * `git branch -d|-D <name>` — delete a local branch. The safe form (`-d`)
     * makes git REFUSE to delete a branch whose commits are not merged
     * anywhere, so it is not destructive; the force form is, and is only
     * offered after the safe form reported that refusal.
     */
    data class DeleteBranch(val name: String, val force: Boolean = false) : GitOp {
        override val kind = "delete-branch"
        override val destructive = force
        override fun argv(repoPath: String) =
            base(repoPath, "branch", if (force) "-D" else "-d", name)
    }

    /** `git merge --no-edit <name>` — merge a branch into the checked-out one. */
    data class MergeBranch(val name: String) : GitOp {
        override val kind = "merge"
        override fun argv(repoPath: String) = base(repoPath, "merge", "--no-edit", name)
    }

    /**
     * `git rebase <name>` — replay the current branch's local commits onto
     * another ref. Rewrites history: destructive by policy, always confirmed.
     */
    data class RebaseOnto(val name: String) : GitOp {
        override val kind = "rebase"
        override val destructive = true
        override fun argv(repoPath: String) = base(repoPath, "rebase", name)
    }

    // ------------------------------------------------------------- remotes

    /** `git fetch [<remote>]` — update remote-tracking refs, nothing local. */
    data class Fetch(val remote: String? = null) : GitOp {
        override val kind = "fetch"
        override val network = true
        override fun argv(repoPath: String): List<String> {
            val argv = base(repoPath, "fetch")
            return if (remote.isNullOrBlank()) argv else argv + listOf(remote)
        }
    }

    /**
     * `git pull --ff-only [<remote> <branch>]` — fast-forward only, so a
     * pull can never create a merge commit behind the user's back; when the
     * histories diverge git fails, which is the honest answer.
     */
    data class Pull(val remote: String? = null, val branch: String? = null) : GitOp {
        override val kind = "pull"
        override val network = true
        override fun argv(repoPath: String): List<String> {
            val argv = base(repoPath, "pull", "--ff-only")
            if (remote.isNullOrBlank()) return argv
            return if (branch.isNullOrBlank()) argv + listOf(remote) else argv + listOf(remote, branch)
        }
    }

    /** `git push [-u <remote> <branch>]` — publish the current branch. */
    data class Push(
        val remote: String? = null,
        val branch: String? = null,
        val setUpstream: Boolean = false,
    ) : GitOp {
        override val kind = "push"
        override val network = true
        override fun argv(repoPath: String): List<String> {
            if (setUpstream && !remote.isNullOrBlank() && !branch.isNullOrBlank()) {
                return base(repoPath, "push", "-u", remote, branch)
            }
            val argv = base(repoPath, "push")
            if (remote.isNullOrBlank()) return argv
            return if (branch.isNullOrBlank()) argv + listOf(remote) else argv + listOf(remote, branch)
        }
    }

    // --------------------------------------------------------------- stash

    /** `git stash push [-u] [-m <message>]` — shelve the pending changes. */
    data class StashPush(
        val message: String? = null,
        val includeUntracked: Boolean = true,
    ) : GitOp {
        override val kind = "stash-push"
        override fun argv(repoPath: String): List<String> {
            val argv = base(repoPath, "stash", "push")
            val withFlag = if (includeUntracked) argv + listOf("-u") else argv
            return if (message.isNullOrBlank()) withFlag else withFlag + listOf("-m", message)
        }
    }

    /** `git stash pop stash@{N}` — reapply a stash and drop it when clean. */
    data class StashPop(val index: Int) : GitOp {
        override val kind = "stash-pop"
        override fun argv(repoPath: String) = base(repoPath, "stash", "pop", "stash@{$index}")
    }

    /** `git stash drop stash@{N}` — throw a stash away (unrecoverable). */
    data class StashDrop(val index: Int) : GitOp {
        override val kind = "stash-drop"
        override val destructive = true
        override fun argv(repoPath: String) = base(repoPath, "stash", "drop", "stash@{$index}")
    }
}

/** `git -C <repo> …` — the one shape every op shares. */
private fun base(repoPath: String, vararg args: String): List<String> =
    listOf("git", "-C", repoPath) + args

/**
 * The vocabulary, the validation and the CONFIRM POLICY for [GitOp]s — pure
 * data, JVM-tested (GitOpsTest), so "destructive operations ask first" is a
 * property of the code rather than a habit of the UI.
 */
internal object GitOps {

    /** A mutation that must be confirmed before it runs. */
    data class ConfirmSpec(val title: String, val body: String, val confirmWord: String)

    /** What the UI knows at confirm time, so the wording can be specific. */
    data class ConfirmContext(
        /** The branch the repository has checked out, when known. */
        val currentBranch: String? = null,
        /** How many files a bulk action covers, when the caller knows. */
        val affectedFiles: Int? = null,
        /** The upstream (or remote) the op targets, when known. */
        val upstream: String? = null,
    )

    /** Local (index / worktree / ref) mutations: a short, bounded git call. */
    const val LOCAL_TIMEOUT_MS = 20_000L

    /** Remote contact: the network is slower than the phone; still bounded. */
    const val NETWORK_TIMEOUT_MS = 90_000L

    /**
     * THE confirm policy. Destructive ops always ask; so do the reversible
     * ones whose consequences deserve a beat (merge, rebase, pull, push,
     * pop). Stage/unstage/commit/fetch/switch never ask — asking for
     * everything is how users learn to tap through dialogs.
     */
    fun confirm(op: GitOp, context: ConfirmContext = ConfirmContext()): ConfirmSpec? = when (op) {
        is GitOp.DiscardFile -> ConfirmSpec(
            title = "Discard changes?",
            body = "\"${op.path}\" returns to the version in the index. " +
                "Worktree edits that were never staged are lost — git cannot restore them.",
            confirmWord = "Discard",
        )
        GitOp.DiscardTrackedAll -> ConfirmSpec(
            title = "Discard every tracked change?",
            body = buildString {
                append(
                    if (context.affectedFiles != null) {
                        "${context.affectedFiles} tracked file(s) return to the version in the index."
                    } else {
                        "Every tracked file returns to the version in the index."
                    },
                )
                append(" Worktree edits are lost; untracked files are kept.")
            },
            confirmWord = "Discard all",
        )
        is GitOp.DeleteUntracked -> ConfirmSpec(
            title = "Delete untracked file?",
            body = "\"${op.absolutePath}\" is not in git, so nothing can bring it back.",
            confirmWord = "Delete",
        )
        is GitOp.DeleteBranch -> if (op.force) {
            ConfirmSpec(
                title = "Force delete \"${op.name}\"?",
                body = "Commits that exist only on this branch are lost — git cannot recover them. " +
                    "Force delete only when you know the work is gone.",
                confirmWord = "Force delete",
            )
        } else {
            ConfirmSpec(
                title = "Delete branch \"${op.name}\"?",
                body = "git refuses when the branch has unmerged commits, and says so — " +
                    "that refusal is the safe answer, not an error to work around.",
                confirmWord = "Delete",
            )
        }
        is GitOp.MergeBranch -> ConfirmSpec(
            title = "Merge \"${op.name}\"?",
            body = "Merges \"${op.name}\" into \"${context.currentBranch ?: "the current branch"}\". " +
                "A conflict stops the merge and is resolved in a terminal " +
                "(the Changes tab then shows the conflicted paths).",
            confirmWord = "Merge",
        )
        is GitOp.RebaseOnto -> ConfirmSpec(
            title = "Rebase onto \"${op.name}\"?",
            body = "Commits on \"${context.currentBranch ?: "the current branch"}\" are replayed on top of " +
                "\"${op.name}\" and get new hashes. A conflict stops the rebase half-way — " +
                "finishing it is terminal work (git rebase --continue).",
            confirmWord = "Rebase",
        )
        is GitOp.Pull -> ConfirmSpec(
            title = "Pull?",
            body = "Fast-forward only from \"${context.upstream ?: op.remote ?: "the upstream"}\". " +
                "Diverged histories fail instead of creating a merge commit.",
            confirmWord = "Pull",
        )
        is GitOp.Push -> ConfirmSpec(
            title = if (op.setUpstream) "Push and set upstream?" else "Push?",
            body = buildString {
                append("Publishes \"${context.currentBranch ?: "the current branch"}\" to ")
                append("\"${op.remote ?: context.upstream ?: "the remote"}\".")
                if (op.setUpstream) append(" The branch will start tracking it.")
            },
            confirmWord = "Push",
        )
        is GitOp.StashPop -> ConfirmSpec(
            title = "Pop stash@{${op.index}}?",
            body = "Reapplies the stash. When it applies cleanly the stash is dropped; " +
                "a conflict leaves it in place, which is the honest outcome.",
            confirmWord = "Pop",
        )
        is GitOp.StashDrop -> ConfirmSpec(
            title = "Drop stash@{${op.index}}?",
            body = "The stash entry is removed — an unreachable commit, not something a phone UI can restore.",
            confirmWord = "Drop",
        )
        is GitOp.Commit -> if (op.amend) {
            ConfirmSpec(
                title = "Amend the last commit?",
                body = "The tip commit is rewritten in place and gets a new hash. " +
                    "Amend local work only — a branch that was already pushed " +
                    "will refuse the next push until it is forced.",
                confirmWord = "Amend",
            )
        } else {
            null
        }
        is GitOp.Reset -> if (op.mode.destructive) {
            ConfirmSpec(
                title = "Hard reset to \"${op.ref}\"?",
                body = "The branch moves to \"${op.ref}\" and every change after it — " +
                    "staged AND worktree, tracked files — is discarded. " +
                    "git cannot bring committed-after-${op.ref} work back from a hard reset.",
                confirmWord = "Hard reset",
            )
        } else {
            null
        }
        is GitOp.CheckoutCommit -> ConfirmSpec(
            title = "Check out ${op.hash.take(7)}?",
            body = "The repository enters a DETACHED HEAD — you are no longer on a branch. " +
                "Commits made here are easy to lose; switch back to a branch to leave.",
            confirmWord = "Check out",
        )
        is GitOp.TagDelete -> ConfirmSpec(
            title = "Delete tag \"${op.name}\"?",
            body = "The tag reference is removed. Its commit stays; the name that pointed at it is gone.",
            confirmWord = "Delete tag",
        )
        else -> null
    }


    fun timeoutMs(op: GitOp): Long = if (op.network) NETWORK_TIMEOUT_MS else LOCAL_TIMEOUT_MS

    /**
     * Refuse an op whose arguments are unsafe or meaningless BEFORE anything
     * reaches the guest. Null = the op may run. Every message is user-facing
     * and says what is wrong with the request.
     */
    fun refusal(op: GitOp): String? = when (op) {
        is GitOp.Stage -> pathsRefusal(op.paths)
        is GitOp.Unstage -> pathsRefusal(op.paths)
        is GitOp.DiscardFile -> pathRefusal(op.path, allowAbsolute = false)
        is GitOp.DeleteUntracked -> pathRefusal(op.absolutePath, allowAbsolute = true)
        is GitOp.Commit -> commitRefusal(op)
        is GitOp.SwitchBranch -> refRefusal(op.name)
        is GitOp.CreateBranch -> refRefusal(op.name) ?: refRefusal(op.base)
        is GitOp.RenameBranch -> refRefusal(op.from) ?: refRefusal(op.to)
        is GitOp.DeleteBranch -> refRefusal(op.name)
        is GitOp.MergeBranch -> refRefusal(op.name)
        is GitOp.RebaseOnto -> refRefusal(op.name)
        is GitOp.Fetch -> remoteRefusal(op.remote)
        is GitOp.Pull -> remoteRefusal(op.remote) ?: refRefusal(op.branch)
        is GitOp.Push -> remoteRefusal(op.remote) ?: refRefusal(op.branch) ?: upstreamRefusal(op)
        is GitOp.StashPush -> if ((op.message?.length ?: 0) > STASH_MESSAGE_LIMIT) {
            "the stash message is longer than $STASH_MESSAGE_LIMIT characters"
        } else {
            null
        }
        is GitOp.StashPop -> indexRefusal(op.index)
        is GitOp.StashDrop -> indexRefusal(op.index)
        is GitOp.CherryPick -> hashRefusal(op.hash)
        is GitOp.Revert -> hashRefusal(op.hash)
        is GitOp.CheckoutCommit -> hashRefusal(op.hash)
        is GitOp.Reset -> revRefusal(op.ref)
        is GitOp.TagCreate -> refRefusal(op.name) ?: hashRefusalOrNull(op.hash)
        is GitOp.TagDelete -> refRefusal(op.name)
        GitOp.StageAll, GitOp.DiscardTrackedAll, is GitOp.UnstageAll -> null
    }

    /** A rev argument (branch, tag, hash, or `~`/`^`-suffixed), not a ref name. */
    private fun revRefusal(rev: String): String? = when {
        rev.isBlank() -> "the revision is empty"
        rev.contains('\u0000') -> "the revision contains a NUL byte"
        rev.startsWith("-") -> "\"$rev\" starts with '-', which git reads as an option"
        rev.any { it.code < 0x20 || it.code == 0x7f } -> "\"$rev\" contains a control character"
        rev.any { it == ' ' } -> "\"$rev\" contains a space"
        else -> null
    }

    private fun hashRefusalOrNull(hash: String?): String? = hash?.let { hashRefusal(it) }

    private fun commitRefusal(op: GitOp.Commit): String? = when {
        op.subject.isBlank() -> "a commit needs a message"
        op.subject.length > COMMIT_SUBJECT_LIMIT ->
            "the commit message is longer than $COMMIT_SUBJECT_LIMIT characters"
        op.body.length > COMMIT_BODY_LIMIT ->
            "the commit body is longer than $COMMIT_BODY_LIMIT characters"
        op.subject.contains('\u0000') || op.body.contains('\u0000') -> "the message contains a NUL byte"
        else -> null
    }

    private fun indexRefusal(index: Int): String? =
        if (index < 0) "a stash index is never negative" else null

    private fun pathsRefusal(paths: List<String>): String? {
        if (paths.isEmpty()) return "no path was selected"
        for (path in paths) pathRefusal(path, allowAbsolute = false)?.let { return it }
        return null
    }

    /**
     * A path reaches git as ONE argv element, so the only dangers are the ones
     * git itself treats specially: an empty path, a NUL byte (which cannot
     * survive execve) and a "../" escape out of the repository.
     */
    private fun pathRefusal(path: String, allowAbsolute: Boolean): String? = when {
        path.isEmpty() -> "the path is empty"
        path.contains('\u0000') -> "the path contains a NUL byte"
        path.split('/').any { it == ".." } -> "\"$path\" escapes the repository"
        !allowAbsolute && path.startsWith("/") ->
            "\"$path\" is absolute; git paths are repository-relative"
        allowAbsolute && !path.startsWith("/") ->
            "\"$path\" must be an absolute guest path"
        else -> null
    }

    /**
     * A reference (branch/remote) name, checked against the rules
     * `git check-ref-format` enforces, so a refusal is always a real reason
     * and never a silent no-op: control characters, spaces and the reserved
     * set `~^:?*[\` are illegal, as are "..", "@{", "//", a leading dash and
     * the ".lock" / trailing-slash / leading-dot segment shapes.
     */
    fun refRefusal(name: String?): String? {
        if (name == null) return null
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "the name is empty"
            trimmed != name -> "\"$name\" has leading or trailing whitespace"
            name.startsWith("-") -> "\"$name\" starts with '-', which git reads as an option"
            name == "@" -> "\"@\" is not a usable name"
            name.any { it.code < 0x20 || it.code == 0x7f } -> "\"$name\" contains a control character"
            name.any { it == ' ' } -> "\"$name\" contains a space"
            name.any { it in "~^:?*[\\" } -> "\"$name\" contains a character git reserves (~ ^ : ? * [ \\)"
            name.contains("..") -> "\"$name\" contains \"..\""
            name.contains("@{") -> "\"$name\" contains \"@{\""
            name.contains("//") -> "\"$name\" contains \"//\""
            name.endsWith("/") -> "\"$name\" ends with '/'"
            name.endsWith(".") -> "\"$name\" ends with '.'"
            name.endsWith(".lock") -> "\"$name\" ends with \".lock\""
            name.split('/').any { it.startsWith(".") } -> "\"$name\" has a path segment starting with '.'"
            else -> null
        }
    }

    /** A remote name: same rules, and a plain name is what git expects. */
    private fun remoteRefusal(remote: String?): String? {
        if (remote == null) return null
        if (remote.isBlank()) return "the remote name is blank"
        return refRefusal(remote)
    }

    /** `push -u` needs BOTH halves, otherwise git guesses (and guesses wrong). */
    private fun upstreamRefusal(op: GitOp.Push): String? =
        if (op.setUpstream && (op.remote.isNullOrBlank() || op.branch.isNullOrBlank())) {
            "setting an upstream needs both a remote and a branch"
        } else {
            null
        }

    /** A commit object name as git prints it, or null when it is not one. */
    fun hashRefusal(hash: String): String? =
        if (HASH_RE.matches(hash.trim())) null else "\"$hash\" is not a git object name"

    private val HASH_RE = Regex("""[0-9a-fA-F]{4,40}""")

    /**
     * The command as a human reads it — for the confirm dialog, the result
     * banner and the "copy the command" escape hatch. Elements are quoted
     * only when they would otherwise be ambiguous, and the quoting is
     * display-only (the real exec is always an argv list).
     */
    fun displayCommand(argv: List<String>): String = argv.joinToString(" ") { quote(it) }

    private fun quote(element: String): String {
        val needsQuotes = element.isEmpty() ||
            element.any { it == ' ' || it == '\t' || it == '"' || it == '\'' || it == '\\' || it == '$' }
        if (!needsQuotes) return element
        val escaped = buildString(element.length + 2) {
            for (c in element) {
                if (c == '"' || c == '\\' || c == '$') append('\\')
                append(c)
            }
        }
        return "\"$escaped\""
    }

    /** Longest accepted commit subject — a phone field, not a changelog. */
    const val COMMIT_SUBJECT_LIMIT = 200

    /** Longest accepted commit body. */
    const val COMMIT_BODY_LIMIT = 8000

    /** Longest accepted stash message. */
    const val STASH_MESSAGE_LIMIT = 200

    /** Short verb for a button, a row action or a banner. */
    fun label(op: GitOp): String = when (op) {
        is GitOp.Stage -> "Stage"
        GitOp.StageAll -> "Stage all"
        is GitOp.Unstage -> "Unstage"
        is GitOp.UnstageAll -> "Unstage all"
        is GitOp.DiscardFile -> "Discard"
        GitOp.DiscardTrackedAll -> "Discard all"
        is GitOp.DeleteUntracked -> "Delete"
        is GitOp.Commit -> "Commit"
        is GitOp.SwitchBranch -> "Switch"
        is GitOp.CreateBranch -> "Create branch"
        is GitOp.RenameBranch -> "Rename"
        is GitOp.DeleteBranch -> if (op.force) "Force delete" else "Delete branch"
        is GitOp.MergeBranch -> "Merge"
        is GitOp.RebaseOnto -> "Rebase"
        is GitOp.Fetch -> "Fetch"
        is GitOp.Pull -> "Pull"
        is GitOp.Push -> if (op.setUpstream) "Push & track" else "Push"
        is GitOp.StashPush -> "Stash changes"
        is GitOp.StashPop -> "Pop stash"
        is GitOp.StashDrop -> "Drop stash"
        is GitOp.CherryPick -> "Cherry-pick"
        is GitOp.Revert -> "Revert"
        is GitOp.Reset -> when (op.mode) {
            GitOp.ResetMode.SOFT -> "Reset (soft)"
            GitOp.ResetMode.MIXED -> "Reset (mixed)"
            GitOp.ResetMode.HARD -> "Hard reset"
        }
        is GitOp.CheckoutCommit -> "Check out"
        is GitOp.TagCreate -> "Tag"
        is GitOp.TagDelete -> "Delete tag"
    }

    /** What a running op says (present tense — the work really is in flight). */
    fun runningLabel(op: GitOp): String = when (op) {
        is GitOp.Stage -> "Staging…"
        GitOp.StageAll -> "Staging all…"
        is GitOp.Unstage -> "Unstaging…"
        is GitOp.UnstageAll -> "Unstaging all…"
        is GitOp.DiscardFile -> "Discarding…"
        GitOp.DiscardTrackedAll -> "Discarding…"
        is GitOp.DeleteUntracked -> "Deleting…"
        is GitOp.Commit -> "Committing…"
        is GitOp.SwitchBranch -> "Switching…"
        is GitOp.CreateBranch -> "Creating…"
        is GitOp.RenameBranch -> "Renaming…"
        is GitOp.DeleteBranch -> "Deleting…"
        is GitOp.MergeBranch -> "Merging…"
        is GitOp.RebaseOnto -> "Rebasing…"
        is GitOp.Fetch -> "Fetching…"
        is GitOp.Pull -> "Pulling…"
        is GitOp.Push -> "Pushing…"
        is GitOp.StashPush -> "Stashing…"
        is GitOp.StashPop -> "Popping…"
        is GitOp.StashDrop -> "Dropping…"
        is GitOp.CherryPick -> "Cherry-picking…"
        is GitOp.Revert -> "Reverting…"
        is GitOp.Reset -> "Resetting…"
        is GitOp.CheckoutCommit -> "Checking out…"
        is GitOp.TagCreate -> "Tagging…"
        is GitOp.TagDelete -> "Deleting tag…"
    }
}

/**
 * What actually happened. Truthfulness is structural here: [success] is
 * `exec error == null && exitCode == 0` — there is no way to construct a
 * "successful" outcome for a git that failed, and [headline]/[detail] carry
 * GIT'S OWN WORDS rather than a friendly paraphrase invented by the UI.
 */
internal data class GitOpOutcome(
    val op: GitOp,
    val argv: List<String>,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    /** The exec-layer failure (no process, timeout, refused request). */
    val error: String?,
    val atMs: Long,
) {
    val success: Boolean get() = error == null && exitCode == 0

    /** The command as a human reads it (display quoting only). */
    val command: String get() = GitOps.displayCommand(argv)

    /**
     * git's own last meaningful line — the summary for a success, the
     * reason for a failure. Null only when git said nothing at all, which
     * the UI states as "no output" instead of dressing it up.
     */
    val headline: String?
        get() {
            val stream = if (success) stdout.ifBlank { stderr } else stderr.ifBlank { stdout }
            return stream.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        }

    /** Up to [DETAIL_MAX_LINES] of git's output, for the expanded banner. */
    val detail: List<String>
        get() = (if (success) stdout else stderr.ifBlank { stdout })
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .take(DETAIL_MAX_LINES)
            .toList()

    /** How many output lines the banner is not showing — a real count. */
    val hiddenDetailLines: Int
        get() {
            val total = (if (success) stdout else stderr.ifBlank { stdout })
                .lineSequence()
                .count { it.isNotBlank() }
            return (total - DETAIL_MAX_LINES).coerceAtLeast(0)
        }

    companion object {
        /** Lines a result banner shows before it starts summarising. */
        const val DETAIL_MAX_LINES = 4
    }
}

/**
 * Runs one [GitOp] through the sanctioned guest exec seam and reports what
 * git said. Blocking (proot spawn + git) — callers wrap it in
 * Dispatchers.IO, exactly like the probe. It never touches the UI state:
 * a mutation may only ever produce an outcome, and the caller follows it
 * with a FORCED rescan so every pixel afterwards is git's own answer.
 */
internal class GitOpRunner(
    private val exec: GitProbe.GuestExec,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun run(op: GitOp, repoPath: String): GitOpOutcome {
        val argv = op.argv(repoPath)
        // Defense in depth: the UI validates before asking for a confirm,
        // and the runner refuses again rather than trusting its caller.
        GitOps.refusal(op)?.let { reason ->
            return GitOpOutcome(
                op = op,
                argv = argv,
                exitCode = null,
                stdout = "",
                stderr = "",
                error = reason,
                atMs = clock(),
            )
        }
        return try {
            val out = exec.exec(argv, GitOps.timeoutMs(op))
            GitOpOutcome(
                op = op,
                argv = argv,
                exitCode = out.exitCode,
                stdout = out.stdout,
                stderr = out.stderr,
                error = out.error,
                atMs = clock(),
            )
        } catch (t: Throwable) {
            GitOpOutcome(
                op = op,
                argv = argv,
                exitCode = null,
                stdout = "",
                stderr = "",
                error = t.message ?: t.javaClass.simpleName,
                atMs = clock(),
            )
        }
    }
}


