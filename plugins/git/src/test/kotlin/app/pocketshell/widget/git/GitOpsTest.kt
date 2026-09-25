package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE mutation seam's rules, as executable facts: argv shapes, the refusal
 * table, the confirm policy (destructive ALWAYS asks) and the truthful
 * outcome. Every op the UI can request is covered here — if it is not in
 * this file's op list, it cannot run.
 */
class GitOpsTest {

    private val repo = "/root/project"

    // ------------------------------------------------------------ argv

    @Test
    fun `every op builds git -C argv lists - never a shell string`() {
        val cases = listOf(
            GitOp.Stage(listOf("a.kt", "b.kt")) to listOf("git", "-C", repo, "add", "--", "a.kt", "b.kt"),
            GitOp.StageAll to listOf("git", "-C", repo, "add", "-A"),
            GitOp.Unstage(listOf("a.kt")) to listOf("git", "-C", repo, "restore", "--staged", "--", "a.kt"),
            GitOp.UnstageAll(unbornHead = false) to listOf("git", "-C", repo, "restore", "--staged", "--", "."),
            GitOp.UnstageAll(unbornHead = true) to listOf("git", "-C", repo, "rm", "--cached", "-r", "-q", "--", "."),
            GitOp.DiscardFile("a.kt") to listOf("git", "-C", repo, "restore", "--worktree", "--", "a.kt"),
            GitOp.DiscardTrackedAll to listOf("git", "-C", repo, "restore", "--worktree", "--", "."),
            GitOp.DeleteUntracked("/root/project/x.txt") to listOf("rm", "-f", "--", "/root/project/x.txt"),
            GitOp.Commit("subject", "body") to listOf("git", "-C", repo, "commit", "-m", "subject", "-m", "body"),
            GitOp.Commit("s", amend = true) to listOf("git", "-C", repo, "commit", "--amend", "-m", "s"),
            GitOp.SwitchBranch("dev") to listOf("git", "-C", repo, "switch", "dev"),
            GitOp.CreateBranch("dev") to listOf("git", "-C", repo, "switch", "-c", "dev"),
            GitOp.CreateBranch("dev", "main") to listOf("git", "-C", repo, "switch", "-c", "dev", "main"),
            GitOp.RenameBranch("a", "b") to listOf("git", "-C", repo, "branch", "-m", "a", "b"),
            GitOp.DeleteBranch("dev") to listOf("git", "-C", repo, "branch", "-d", "dev"),
            GitOp.DeleteBranch("dev", force = true) to listOf("git", "-C", repo, "branch", "-D", "dev"),
            GitOp.MergeBranch("dev") to listOf("git", "-C", repo, "merge", "--no-edit", "dev"),
            GitOp.RebaseOnto("main") to listOf("git", "-C", repo, "rebase", "main"),
            GitOp.CherryPick("a1b2c3d") to listOf("git", "-C", repo, "cherry-pick", "a1b2c3d"),
            GitOp.Revert("a1b2c3d") to listOf("git", "-C", repo, "revert", "--no-edit", "a1b2c3d"),
            GitOp.Reset("HEAD~1", GitOp.ResetMode.MIXED) to listOf("git", "-C", repo, "reset", "--mixed", "HEAD~1"),
            GitOp.CheckoutCommit("a1b2c3d") to listOf("git", "-C", repo, "switch", "--detach", "a1b2c3d"),
            GitOp.TagCreate("v1.0") to listOf("git", "-C", repo, "tag", "v1.0"),
            GitOp.TagCreate("v1.0", "a1b2c3d") to listOf("git", "-C", repo, "tag", "v1.0", "a1b2c3d"),
            GitOp.TagDelete("v1.0") to listOf("git", "-C", repo, "tag", "-d", "v1.0"),
            GitOp.Fetch() to listOf("git", "-C", repo, "fetch"),
            GitOp.Fetch("origin") to listOf("git", "-C", repo, "fetch", "origin"),
            GitOp.Pull() to listOf("git", "-C", repo, "pull", "--ff-only"),
            GitOp.Pull("origin", "main") to listOf("git", "-C", repo, "pull", "--ff-only", "origin", "main"),
            GitOp.Push(setUpstream = true, remote = "origin", branch = "dev") to
                listOf("git", "-C", repo, "push", "-u", "origin", "dev"),
            GitOp.Push("origin", "dev") to listOf("git", "-C", repo, "push", "origin", "dev"),
            GitOp.StashPush(message = "wip") to listOf("git", "-C", repo, "stash", "push", "-u", "-m", "wip"),
            GitOp.StashPop(0) to listOf("git", "-C", repo, "stash", "pop", "stash@{0}"),
            GitOp.StashDrop(1) to listOf("git", "-C", repo, "stash", "drop", "stash@{1}"),
        )
        cases.forEach { (op, expected) -> assertEquals("$op", expected, op.argv(repo)) }
    }

    // -------------------------------------------------------- refusals

    @Test
    fun `refusals catch unsafe paths before any exec`() {
        assertNotNull(GitOps.refusal(GitOp.Stage(emptyList())))
        assertNotNull(GitOps.refusal(GitOp.Stage(listOf("a/../b.kt"))))
        assertNotNull(GitOps.refusal(GitOp.Stage(listOf("/etc/passwd"))))
        assertNull(GitOps.refusal(GitOp.Stage(listOf("src/a.kt"))))
        // the one op that REQUIRES an absolute path refuses a relative one
        assertNotNull(GitOps.refusal(GitOp.DeleteUntracked("x.txt")))
        assertNull(GitOps.refusal(GitOp.DeleteUntracked("/root/project/x.txt")))
    }

    @Test
    fun `refusals enforce git ref-name rules`() {
        assertNull(GitOps.refusal(GitOp.SwitchBranch("feature/x")))
        assertNotNull(GitOps.refusal(GitOp.SwitchBranch("feature x")))
        assertNotNull(GitOps.refusal(GitOp.SwitchBranch("-x")))
        assertNotNull(GitOps.refusal(GitOp.SwitchBranch("a..b")))
        assertNotNull(GitOps.refusal(GitOp.SwitchBranch("v@{1}")))
        assertNotNull(GitOps.refusal(GitOp.SwitchBranch("x/")))
        assertNotNull(GitOps.refusal(GitOp.SwitchBranch("x.lock")))
        assertNotNull(GitOps.refusal(GitOp.SwitchBranch(".hidden")))
    }

    @Test
    fun `reset takes revs that are not ref names`() {
        assertNull(GitOps.refusal(GitOp.Reset("HEAD~1", GitOp.ResetMode.SOFT)))
        assertNull(GitOps.refusal(GitOp.Reset("a1b2c3d", GitOp.ResetMode.HARD)))
        assertNotNull(GitOps.refusal(GitOp.Reset("-web", GitOp.ResetMode.HARD)))
        assertNotNull(GitOps.refusal(GitOp.Reset("", GitOp.ResetMode.HARD)))
    }

    @Test
    fun `commit and stash message limits refuse before any exec`() {
        assertNotNull(GitOps.refusal(GitOp.Commit(" ")))
        assertNotNull(GitOps.refusal(GitOp.Commit("x".repeat(201))))
        assertNull(GitOps.refusal(GitOp.Commit("x".repeat(200))))
        assertNotNull(GitOps.refusal(GitOp.StashPush(message = "x".repeat(201))))
        assertNotNull(GitOps.refusal(GitOp.Push(setUpstream = true, remote = "origin")))
    }

    @Test
    fun `a bad hash is refused with no exec`() {
        assertNotNull(GitOps.hashRefusal("rm -rf"))
        assertNull(GitOps.hashRefusal("a1b2c3d"))
    }

    // --------------------------------------------------- confirm policy

    @Test
    fun `every destructive op always asks - without exception`() {
        val destructive = listOf<GitOp>(
            GitOp.DiscardFile("a.kt"),
            GitOp.DiscardTrackedAll,
            GitOp.DeleteUntracked("/root/x"),
            GitOp.DeleteBranch("dev", force = true),
            GitOp.StashDrop(0),
            GitOp.Commit("s", amend = true),
            GitOp.Reset("HEAD~1", GitOp.ResetMode.HARD),
            GitOp.CheckoutCommit("a1b2c3d"),
            GitOp.TagDelete("v1"),
        )
        destructive.forEach { op ->
            assertNotNull("${op::class.simpleName} must confirm", GitOps.confirm(op))
        }
    }

    @Test
    fun `reversible-but-consequential ops ask too`() {
        listOf<GitOp>(
            GitOp.MergeBranch("dev"),
            GitOp.RebaseOnto("main"),
            GitOp.Pull("origin", "main"),
            GitOp.Push("origin", "main"),
            GitOp.StashPop(0),
            GitOp.DeleteBranch("dev"),
        ).forEach { op ->
            assertNotNull("${op::class.simpleName} must confirm", GitOps.confirm(op))
        }
    }

    @Test
    fun `the routine ops never ask - asking for everything trains tap-through`() {
        listOf<GitOp>(
            GitOp.Stage(listOf("a.kt")),
            GitOp.StageAll,
            GitOp.Unstage(listOf("a.kt")),
            GitOp.UnstageAll(unbornHead = false),
            GitOp.Commit("s"),
            GitOp.Fetch(),
            GitOp.SwitchBranch("dev"),
            GitOp.CreateBranch("dev"),
            GitOp.RenameBranch("a", "b"),
            GitOp.TagCreate("v1"),
            GitOp.StashPush(),
            GitOp.Reset("HEAD~1", GitOp.ResetMode.SOFT),
            GitOp.Reset("HEAD~1", GitOp.ResetMode.MIXED),
        ).forEach { op ->
            assertNull("${op::class.simpleName} must not confirm", GitOps.confirm(op))
        }
    }

    @Test
    fun `confirm wording names the specific branch and target`() {
        val spec = GitOps.confirm(
            GitOp.MergeBranch("dev"),
            GitOps.ConfirmContext(currentBranch = "main"),
        )!!
        assertTrue(spec.body.contains("dev"))
        assertTrue(spec.body.contains("main"))

        val push = GitOps.confirm(
            GitOp.Push(remote = "origin", branch = "main"),
            GitOps.ConfirmContext(currentBranch = "main", upstream = "origin/main"),
        )!!
        assertTrue(push.body.contains("origin"))
    }

    // --------------------------------------------------------- timings

    @Test
    fun `network ops get the long timeout - local ops the short one`() {
        assertEquals(GitOps.NETWORK_TIMEOUT_MS, GitOps.timeoutMs(GitOp.Push()))
        assertEquals(GitOps.NETWORK_TIMEOUT_MS, GitOps.timeoutMs(GitOp.Fetch("origin")))
        assertEquals(GitOps.LOCAL_TIMEOUT_MS, GitOps.timeoutMs(GitOp.StageAll))
        assertEquals(GitOps.LOCAL_TIMEOUT_MS, GitOps.timeoutMs(GitOp.Commit("s")))
    }

    // -------------------------------------------------------- outcomes

    @Test
    fun `an outcome is successful only when exec succeeded and git exited zero`() {
        val op = GitOp.Stage(listOf("a.kt"))
        val argv = op.argv(repo)
        assertFalse(GitOpOutcome(op, argv, 1, "", "fatal: x", null, 0L).success)
        assertFalse(GitOpOutcome(op, argv, null, "", "", "timeout", 0L).success)
        assertTrue(GitOpOutcome(op, argv, 0, "ok", "", null, 0L).success)
    }

    @Test
    fun `a failed outcome headlines git's stderr - a success its stdout`() {
        val op = GitOp.Stage(listOf("a.kt"))
        val argv = op.argv(repo)
        val failed = GitOpOutcome(op, argv, 128, "", "fatal: not a repository\n", null, 0L)
        assertEquals("fatal: not a repository", failed.headline)
        val ok = GitOpOutcome(op, argv, 0, "staged\n", "", null, 0L)
        assertEquals("staged", ok.headline)
    }

    @Test
    fun `detail is capped with a real hidden count`() {
        val op = GitOp.Stage(listOf("a.kt"))
        val stdout = (1..10).joinToString("\n") { "line $it" } + "\n"
        val outcome = GitOpOutcome(op, op.argv(repo), 0, stdout, "", null, 0L)
        assertEquals(GitOpOutcome.DETAIL_MAX_LINES, outcome.detail.size)
        assertEquals(6, outcome.hiddenDetailLines)
    }

    @Test
    fun `display commands quote only what needs quoting`() {
        assertEquals(
            "git -C /root/project add -- \"my file.kt\"",
            GitOps.displayCommand(listOf("git", "-C", "/root/project", "add", "--", "my file.kt")),
        )
        assertEquals("git -C /r status", GitOps.displayCommand(listOf("git", "-C", "/r", "status")))
    }
}
