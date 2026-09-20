package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OPERATION layer's contract, against a fake exec (no guest needed).
 * Pinned here:
 *
 *   1. ARGV DISCIPLINE: every operation is exactly ONE direct git exec —
 *      paths after `--`, names as bare elements, `-m` messages as single
 *      elements. No shell, no splicing, no second command.
 *   2. HONEST RESULTS: exit 0 → Ok with git's own summary; anything else
 *      → Failed with the tool's real stderr tail. Never a dressed-up lie.
 *   3. INPUT GATES: branch/ref names pass validRefName BEFORE any exec —
 *      leading dashes, whitespace and git's forbidden characters are
 *      refused without touching the guest.
 *   4. THE DIALOG MAP: destructive/risky verbs carry an OpConfirmation;
 *      quiet, reversible ones do not.
 */
class GitOpsTest {

    private class FakeExec(
        private val result: () -> ExecResult,
    ) : GitProbe.GuestExec {
        val argvs = mutableListOf<List<String>>()
        var calls = 0

        override fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult {
            calls += 1
            argvs.add(guestCommand)
            return result()
        }
    }

    private fun ok(stdout: String = "") = ExecResult(exitCode = 0, stdout = stdout, stderr = "")
    private fun fail(stderr: String) = ExecResult(exitCode = 1, stdout = "", stderr = stderr)

    // ------------------------------------------------------------ argv

    @Test
    fun `stage rides after the double dash`() {
        val fake = FakeExec { ok() }
        assertEquals("Staged src/a.kt", (GitOps(fake).stage("/r", "src/a.kt") as GitOps.OpResult.Ok).summary)
        assertEquals(listOf("git", "-C", "/r", "add", "--", "src/a.kt"), fake.argvs.single())
    }

    @Test
    fun `stageAll is git add -A`() {
        val fake = FakeExec { ok() }
        GitOps(fake).stageAll("/r")
        assertEquals(listOf("git", "-C", "/r", "add", "-A"), fake.argvs.single())
    }

    @Test
    fun `unstage uses restore staged and unstageAll uses plain reset`() {
        val fake = FakeExec { ok() }
        val ops = GitOps(fake)
        ops.unstage("/r", "src/a.kt")
        ops.unstageAll("/r")
        assertEquals(listOf("git", "-C", "/r", "restore", "--staged", "--", "src/a.kt"), fake.argvs[0])
        assertEquals(listOf("git", "-C", "/r", "reset"), fake.argvs[1])
    }

    @Test
    fun `discard is restore on one path - the confirmed destructive verb`() {
        val fake = FakeExec { ok() }
        GitOps(fake).discard("/r", "src/a.kt")
        assertEquals(listOf("git", "-C", "/r", "restore", "--", "src/a.kt"), fake.argvs.single())
    }

    @Test
    fun `commit rides as a single -m element and echoes git's own summary line`() {
        val fake = FakeExec { ok("[main 4f2ac1b] Add the thing\n 1 file changed\n") }
        val result = GitOps(fake).commit("/r", "Add the thing")
        assertEquals(listOf("git", "-C", "/r", "commit", "-m", "Add the thing"), fake.argvs.single())
        assertEquals("[main 4f2ac1b] Add the thing", (result as GitOps.OpResult.Ok).summary)
    }

    @Test
    fun `an empty commit message is refused before any exec`() {
        val fake = FakeExec { ok() }
        assertTrue(GitOps(fake).commit("/r", "   ") is GitOps.OpResult.Failed)
        assertEquals(0, fake.calls)
    }

    @Test
    fun `branch verbs are checkout -b, branch -d and checkout`() {
        val fake = FakeExec { ok() }
        val ops = GitOps(fake)
        ops.createBranch("/r", "feature/x")
        ops.checkout("/r", "main")
        ops.deleteBranch("/r", "topic")
        ops.merge("/r", "topic")
        assertEquals(listOf("git", "-C", "/r", "checkout", "-b", "feature/x"), fake.argvs[0])
        assertEquals(listOf("git", "-C", "/r", "checkout", "main"), fake.argvs[1])
        assertEquals(listOf("git", "-C", "/r", "branch", "-d", "topic"), fake.argvs[2])
        assertEquals(listOf("git", "-C", "/r", "merge", "topic"), fake.argvs[3])
    }

    @Test
    fun `network verbs are the plain git commands`() {
        val fake = FakeExec { ok() }
        val ops = GitOps(fake)
        ops.fetch("/r")
        ops.pull("/r")
        ops.push("/r")
        assertEquals(listOf("git", "-C", "/r", "fetch"), fake.argvs[0])
        assertEquals(listOf("git", "-C", "/r", "pull"), fake.argvs[1])
        assertEquals(listOf("git", "-C", "/r", "push"), fake.argvs[2])
    }

    @Test
    fun `stash verbs address entries by index`() {
        val fake = FakeExec { ok("Dropped refs/stash@{0}\n") }
        val ops = GitOps(fake)
        ops.stashPush("/r", "wip label")
        ops.stashPush("/r", "  ")
        ops.stashPop("/r", 0)
        ops.stashDrop("/r", 1)
        assertEquals(listOf("git", "-C", "/r", "stash", "push", "-m", "wip label"), fake.argvs[0])
        assertEquals(listOf("git", "-C", "/r", "stash", "push"), fake.argvs[1])
        assertEquals(listOf("git", "-C", "/r", "stash", "pop", "stash@{0}"), fake.argvs[2])
        assertEquals(listOf("git", "-C", "/r", "stash", "drop", "stash@{1}"), fake.argvs[3])
    }

    // --------------------------------------------------- honest results

    @Test
    fun `a failing op carries the tool's real stderr tail`() {
        val fake = FakeExec { fail("error: pathspec 'nope' did not match any file(s)\n") }
        val result = GitOps(fake).checkout("/r", "nope")
        assertEquals(
            "error: pathspec 'nope' did not match any file(s)",
            (result as GitOps.OpResult.Failed).reason,
        )
    }

    @Test
    fun `an exec failure without stderr states the exit code`() {
        val fake = FakeExec { ExecResult(exitCode = 128, stdout = "", stderr = "") }
        val result = GitOps(fake).push("/r")
        assertEquals("git exited with 128", (result as GitOps.OpResult.Failed).reason)
    }

    @Test
    fun `a thrown exec degrades to a failure, never a crash`() {
        val fake = FakeExec { throw IllegalStateException("guest gone") }
        assertEquals("guest gone", (GitOps(fake).fetch("/r") as GitOps.OpResult.Failed).reason)
    }

    // ------------------------------------------------- the refname gate

    @Test
    fun `ordinary branch names pass the refname gate`() {
        listOf("main", "feature/x", "release-1.2", "v_2", "topic.name").forEach {
            assertTrue("should pass: $it", GitOps.validRefName(it))
        }
    }

    @Test
    fun `refnames that could become options or break refs are refused`() {
        listOf(
            "-rf",          // option injection
            "-",
            "a b",          // whitespace
            "a..b",         // range
            "a~1",
            "a^2",
            "a:b",
            "a?b",
            "a*b",
            "a[b",
            "a\\b",
            "/leading",
            "trailing/",
            "a//b",
            "ends.lock",
            "ends.",
            "@",
        ).forEach {
            assertFalse("should be refused: \"$it\"", GitOps.validRefName(it))
        }
        // And the gate runs BEFORE the exec: a refused name never reaches git.
        val fake = FakeExec { ok() }
        assertTrue(GitOps(fake).checkout("/r", "-rf") is GitOps.OpResult.Failed)
        assertEquals(0, fake.calls)
    }

    // ------------------------------------------------ the executor + banner

    @Test
    fun `the executor dispatches every kind to its single verb`() {
        val fake = FakeExec { ok() }
        val ops = GitOps(fake)
        val kinds = listOf(
            OpKind.STAGE to OpReq(OpKind.STAGE, "/r", "a.kt", 0),
            OpKind.STAGE_ALL to OpReq(OpKind.STAGE_ALL, "/r", null, 0),
            OpKind.UNSTAGE to OpReq(OpKind.UNSTAGE, "/r", "a.kt", 0),
            OpKind.UNSTAGE_ALL to OpReq(OpKind.UNSTAGE_ALL, "/r", null, 0),
            OpKind.DISCARD to OpReq(OpKind.DISCARD, "/r", "a.kt", 0),
            OpKind.COMMIT to OpReq(OpKind.COMMIT, "/r", "msg", 0),
            OpKind.CHECKOUT to OpReq(OpKind.CHECKOUT, "/r", "main", 0),
            OpKind.CREATE_BRANCH to OpReq(OpKind.CREATE_BRANCH, "/r", "dev", 0),
            OpKind.DELETE_BRANCH to OpReq(OpKind.DELETE_BRANCH, "/r", "dev", 0),
            OpKind.MERGE to OpReq(OpKind.MERGE, "/r", "dev", 0),
            OpKind.FETCH to OpReq(OpKind.FETCH, "/r", null, 0),
            OpKind.PULL to OpReq(OpKind.PULL, "/r", null, 0),
            OpKind.PUSH to OpReq(OpKind.PUSH, "/r", null, 0),
            OpKind.STASH_PUSH to OpReq(OpKind.STASH_PUSH, "/r", "label", 0),
            OpKind.STASH_POP to OpReq(OpKind.STASH_POP, "/r", "0", 0),
            OpKind.STASH_DROP to OpReq(OpKind.STASH_DROP, "/r", "0", 0),
        )
        kinds.forEach { (kind, req) ->
            assertTrue("$kind must succeed", executeOp(ops, req) is GitOps.OpResult.Ok)
        }
        assertEquals(kinds.size, fake.calls)
    }

    @Test
    fun `the running banner names the exact command`() {
        assertEquals(
            "git add -- src/a.kt",
            describeOp(OpReq(OpKind.STAGE, "/r", "src/a.kt", 0)),
        )
        assertEquals("git stash pop stash@{2}", describeOp(OpReq(OpKind.STASH_POP, "/r", "2", 0)))
        assertNull(opConfirmation(OpKind.STAGE, "a.kt"))
    }

    // -------------------------------------------------- the dialog map

    @Test
    fun `destructive and risky verbs require a confirmation`() {
        listOf(
            OpKind.DISCARD to "a.kt",
            OpKind.DELETE_BRANCH to "dev",
            OpKind.MERGE to "dev",
            OpKind.PULL to null,
            OpKind.PUSH to null,
            OpKind.STASH_POP to "0",
            OpKind.STASH_DROP to "0",
        ).forEach { (kind, arg) ->
            assertTrue("$kind must carry a confirmation", opConfirmation(kind, arg) != null)
        }
        // And the destructive ones are marked destructive.
        assertTrue(opConfirmation(OpKind.DISCARD, "a.kt")!!.destructive)
        assertTrue(opConfirmation(OpKind.DELETE_BRANCH, "dev")!!.destructive)
        assertTrue(opConfirmation(OpKind.STASH_DROP, "0")!!.destructive)
    }

    @Test
    fun `quiet reversible verbs have no dialog`() {
        listOf(
            OpKind.STAGE to "a.kt",
            OpKind.STAGE_ALL to null,
            OpKind.UNSTAGE to "a.kt",
            OpKind.UNSTAGE_ALL to null,
            OpKind.COMMIT to "msg",
            OpKind.FETCH to null,
            OpKind.STASH_PUSH to "label",
        ).forEach { (kind, arg) ->
            assertNull("$kind must not require a dialog", opConfirmation(kind, arg))
        }
    }
}
