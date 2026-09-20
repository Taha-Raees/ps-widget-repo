package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult

/**
 * The GIT application's OPERATION layer — the M9 redesign's one deliberate
 * expansion of the old read-only card: explicit, user-initiated git
 * mutations (stage / unstage / discard / commit / branch work / network
 * sync / stash), each ONE bounded guest exec on the sanctioned path.
 *
 * The discipline that makes this safe in a Home card:
 *
 *   - NOTHING here runs on its own — every call is one user action (a tap
 *     on a confirmed control). There is no retry, no polling, no batch.
 *   - Every argv is DIRECT (execve elements, never a shell): paths ride
 *     after `--`, branch names after client-side refname validation
 *     ([validRefName] — a name starting with "-" or carrying git's
 *     forbidden characters is refused BEFORE any exec), commit messages
 *     and stash labels ride as single `-m` elements. Guest text can never
 *     become an option or a second command.
 *   - Every result is HONEST: exit 0 → [OpResult.Ok] with git's own
 *     summary line; anything else → [OpResult.Failed] with the tool's real
 *     stderr tail. The UI never dresses a failure up as success, and the
 *     caller refreshes the snapshot afterwards so the next render comes
 *     from git, not from the op's word.
 *
 * Destructive ops (discard, branch delete, merge, pull, push, stash
 * pop/drop) are offered by the UI only behind explicit confirmations —
 * that gating is pinned by GitAppContractTest.
 */
internal class GitOps(private val exec: GitProbe.GuestExec) {

    /** One operation's honest outcome. */
    sealed interface OpResult {
        /** Success, with git's own one-line summary (or the verb echoed). */
        data class Ok(val summary: String) : OpResult

        /** Failure, with the tool's real reason — never paraphrased into a lie. */
        data class Failed(val reason: String) : OpResult
    }

    private fun run(argv: List<String>, okSummary: (String) -> String): OpResult = try {
        val out = exec.exec(argv, OP_TIMEOUT_MS)
        when {
            out.error != null -> OpResult.Failed(out.error!!)
            out.exitCode != 0 -> OpResult.Failed(stderrTail(out) ?: "git exited with ${out.exitCode}")
            else -> OpResult.Ok(okSummary(out.stdout.trim()))
        }
    } catch (t: Throwable) {
        OpResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    private fun stderrTail(out: ExecResult): String? =
        out.stderr.lineSequence().lastOrNull { it.isNotBlank() }

    // ------------------------------------------------------------ index

    /** `git add -- <path>` — one path (or one directory) onto the index. */
    fun stage(repoPath: String, path: String): OpResult =
        run(listOf("git", "-C", repoPath, "add", "--", path)) { "Staged $path" }

    /** `git add -A` — every change and untracked path, the stage-all verb. */
    fun stageAll(repoPath: String): OpResult =
        run(listOf("git", "-C", repoPath, "add", "-A")) { "Staged all changes" }

    /** `git restore --staged -- <path>` — one path back out of the index. */
    fun unstage(repoPath: String, path: String): OpResult =
        run(listOf("git", "-C", repoPath, "restore", "--staged", "--", path)) { "Unstaged $path" }

    /** `git reset` — the whole index back to HEAD, worktree untouched. */
    fun unstageAll(repoPath: String): OpResult =
        run(listOf("git", "-C", repoPath, "reset")) { "Unstaged everything" }

    /**
     * `git restore -- <path>` — throw one tracked path's worktree changes
     * away. DESTRUCTIVE (uncommitted work is lost): the UI confirms first;
     * untracked paths are never offered this (git refuses, and rm is not
     * this app's verb).
     */
    fun discard(repoPath: String, path: String): OpResult =
        run(listOf("git", "-C", repoPath, "restore", "--", path)) { "Discarded changes to $path" }

    // ----------------------------------------------------------- commit

    /**
     * `git commit -m <message>` — the staged index under one message that
     * rides as a single argv element. Empty staged set → git's own refusal,
     * shown verbatim.
     */
    fun commit(repoPath: String, message: String): OpResult {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return OpResult.Failed("Commit message is empty")
        return run(listOf("git", "-C", repoPath, "commit", "-m", trimmed)) { stdout ->
            stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: "Committed"
        }
    }

    // ---------------------------------------------------------- branches

    /** `git checkout <name>` — also the DWIM path for remote branch names. */
    fun checkout(repoPath: String, branch: String): OpResult {
        if (!validRefName(branch)) return OpResult.Failed("Not a valid branch name: $branch")
        return run(listOf("git", "-C", repoPath, "checkout", branch)) { "Switched to $branch" }
    }

    /** `git checkout -b <name>` — create AND switch (the useful create). */
    fun createBranch(repoPath: String, name: String): OpResult {
        if (!validRefName(name)) return OpResult.Failed("Not a valid branch name: $name")
        return run(listOf("git", "-C", repoPath, "checkout", "-b", name)) { "Created $name" }
    }

    /** `git branch -d <name>` — refuses unmerged branches; -D is not ours. */
    fun deleteBranch(repoPath: String, name: String): OpResult {
        if (!validRefName(name)) return OpResult.Failed("Not a valid branch name: $name")
        return run(listOf("git", "-C", repoPath, "branch", "-d", name)) { "Deleted $name" }
    }

    /** `git merge <name>` — conflicts surface honestly via the refresh. */
    fun merge(repoPath: String, name: String): OpResult {
        if (!validRefName(name)) return OpResult.Failed("Not a valid branch name: $name")
        return run(listOf("git", "-C", repoPath, "merge", name)) { stdout ->
            stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: "Merged $name"
        }
    }

    // ----------------------------------------------------------- network

    /** `git fetch` — update remote-tracking refs; no working-tree change. */
    fun fetch(repoPath: String): OpResult =
        run(listOf("git", "-C", repoPath, "fetch")) { "Fetched" }

    /** `git pull` — fetch + integrate; conflicts surface honestly. */
    fun pull(repoPath: String): OpResult =
        run(listOf("git", "-C", repoPath, "pull")) { stdout ->
            stdout.lineSequence().lastOrNull { it.isNotBlank() } ?: "Pulled"
        }

    /** `git push` — the current branch to its upstream. */
    fun push(repoPath: String): OpResult =
        run(listOf("git", "-C", repoPath, "push")) { "Pushed" }

    // ------------------------------------------------------------- stash

    /** `git stash push -m <label>` — staged + unstaged work, stashed. */
    fun stashPush(repoPath: String, label: String?): OpResult {
        val argv = mutableListOf("git", "-C", repoPath, "stash", "push")
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isNotEmpty()) argv += listOf("-m", trimmed)
        return run(argv) { "Stashed working changes" }
    }

    /** `git stash pop stash@{<index>}` — apply and drop one stash entry. */
    fun stashPop(repoPath: String, index: Int): OpResult =
        run(listOf("git", "-C", repoPath, "stash", "pop", "stash@{$index}")) { stdout ->
            stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: "Stash applied"
        }

    /** `git stash drop stash@{<index>}` — discard one stash entry. */
    fun stashDrop(repoPath: String, index: Int): OpResult =
        run(listOf("git", "-C", repoPath, "stash", "drop", "stash@{$index}")) { stdout ->
            stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: "Stash dropped"
        }

    companion object {

        /** A local commit/checkout is quick; a cold fetch over mobile data is not. */
        const val OP_TIMEOUT_MS = 30_000L

        /**
         * Client-side refname gate for USER-TYPED names (the probe's own
         * branch output never re-enters as a name unchecked — but both go
         * through here anyway): git's practical rules — no leading dash
         * (option injection), no whitespace, no `~^:?*[\` or control
         * characters, no `..`, no leading/trailing slash, no trailing
         * `.lock` or `.`, not exactly `@`.
         */
        fun validRefName(name: String): Boolean {
            if (name.isEmpty() || name.length > 200) return false
            if (name.startsWith("-") || name.startsWith("/") || name.endsWith("/")) return false
            if (name.endsWith(".lock") || name.endsWith(".")) return false
            if (name == "@") return false
            if (name.contains("..") || name.contains("@{")) return false
            if (name.any { it.isWhitespace() || it in "~^:?*[\\" || it.code < 0x20 }) return false
            // A component is only a component when it is not empty ("a//b").
            return name.split('/').none { it.isEmpty() }
        }
    }
}
