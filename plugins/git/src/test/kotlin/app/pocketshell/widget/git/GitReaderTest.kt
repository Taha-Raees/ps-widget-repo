package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-demand reader: pinned argv lists, honest failure classification
 * (a dead guest, a git failure and an unparseable answer are three
 * DIFFERENT states) and the bounded shapes every screen renders.
 */
class GitReaderTest {

    private class FakeExec(
        private val result: (argv: List<String>) -> ExecResult,
    ) : GitProbe.GuestExec {
        val argvs = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        var calls = 0

        override fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult {
            calls += 1
            argvs.add(guestCommand)
            timeouts.add(timeoutMs)
            return result(guestCommand)
        }
    }

    private fun reader(fake: FakeExec) = GitReader(fake)
    private val SEP = "\u001f"

    // --------------------------------------------------------- history

    @Test
    fun `history execs a bounded log with the fixed field format`() {
        val stdout = "a1b2c3d${SEP}2 days ago${SEP}HEAD -> main${SEP}Alice${SEP}Add the thing\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val result = reader(fake).history("/root/project", 30)
        assertEquals(
            listOf("git", "-C", "/root/project", "log", "-n", "30", "--pretty=format:${GitReader.LOG_FORMAT}"),
            fake.argvs.single(),
        )
        val commits = (result as ReadResult.Done).value
        assertEquals(1, commits.size)
        assertEquals("a1b2c3d", commits[0].hash)
        assertEquals("HEAD -> main", commits[0].refs)
        assertEquals("Alice", commits[0].author)
    }

    @Test
    fun `the history window never grows past the cap`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "", stderr = "") }
        reader(fake).history("/root/project", 10_000)
        assertEquals(
            (GitReader.MAX_HISTORY_WINDOW).toString(),
            fake.argvs.single()[fake.argvs.single().indexOf("-n") + 1],
        )
    }

    // --------------------------------------------------- remote branches

    @Test
    fun `remote branches are capped with a real total`() {
        val lines = (0 until 45).joinToString("\n") { "origin/b$it\torigin/b$it\t" } + "\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = lines, stderr = "") }
        val result = reader(fake).remoteBranches("/root/project")
        val bounded = (result as ReadResult.Done).value
        assertEquals(GitReader.REMOTE_BRANCH_CAP, bounded.items.size)
        assertEquals(45, bounded.total)
        assertEquals(5, bounded.hidden)
    }

    // ---------------------------------------------------------- stashes

    @Test
    fun `stash entries parse the ref and subject`() {
        val fake = FakeExec {
            ExecResult(exitCode = 0, stdout = "stash@{0}${SEP}WIP on main: a1b2c3 x\nstash@{1}${SEP}wip again\n", stderr = "")
        }
        val result = reader(fake).stashes("/root/project")
        val stashes = (result as ReadResult.Done).value
        assertEquals(2, stashes.items.size)
        assertEquals(0, stashes.items[0].index)
        assertEquals("WIP on main: a1b2c3 x", stashes.items[0].subject)
        assertTrue(!stashes.items[0].dropped)
    }

    // ------------------------------------------------------------ files

    @Test
    fun `files execs the one script and parses the total marker plus rows`() {
        val stdout = "@@FILES:3\nsrc/a.kt\nsrc/b.kt\ndocs/readme.md\n@@END\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val result = reader(fake).files("/root/project")
        assertEquals(listOf("/bin/sh", "-c", GitReader.FILES_SCRIPT, "sh", "/root/project"), fake.argvs.single())
        val files = (result as ReadResult.Done).value
        assertEquals(3, files.total)
        assertEquals(listOf("src/a.kt", "src/b.kt", "docs/readme.md"), files.paths)
        assertEquals(0, files.hidden)
    }

    @Test
    fun `a truncated file stream still reports git's real total`() {
        val stdout = "@@FILES:100\na.kt\nb.kt\n" // no @@END
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val files = (reader(fake).files("/root/project") as ReadResult.Done).value
        assertEquals(2, files.paths.size)
        assertEquals(100, files.total)
        assertEquals(98, files.hidden)
    }

    // ---------------------------------------------------- commit detail

    @Test
    fun `commit detail execs show with the sentinel format and parses both blocks`() {
        val stdout = buildString {
            // git's real shape: the %b field starts right after its separator;
            // a MULTI-LINE body continues on following lines
            append("a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4${SEP}Alice <a@b.c>${SEP}now${SEP}HEAD -> main${SEP}Subject${SEP}Body line.\n")
            append("Body line two.\n")
            append(GitReader.FILES_SENTINEL).append('\n')
            append("M\tsrc/a.kt\n")
            append("R100\told.kt\tnew.kt\n")
        }
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val result = reader(fake).commitDetail("/root/project", "a1b2c3d")
        assertEquals(
            listOf(
                "git", "-C", "/root/project", "show",
                "--name-status", "--first-parent",
                "--pretty=format:${GitReader.COMMIT_FORMAT}",
                "a1b2c3d",
            ),
            fake.argvs.single(),
        )
        val detail = (result as ReadResult.Done).value
        assertEquals("a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4", detail.fullHash)
        assertEquals("Alice", detail.author)
        assertEquals("a@b.c", detail.email)
        assertEquals("HEAD -> main", detail.refs)
        assertEquals("Subject", detail.subject)
        assertEquals("Body line.\nBody line two.", detail.body)
        assertEquals(2, detail.files.size)
        assertEquals("src/a.kt", detail.files[0].path)
        assertEquals('M', detail.files[0].letter)
        assertEquals("old.kt", detail.files[1].oldPath)
        assertEquals("new.kt", detail.files[1].path)
        assertEquals('R', detail.files[1].letter)
    }

    @Test
    fun `a non-hash is refused before any exec`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "", stderr = "") }
        val result = reader(fake).commitDetail("/root/project", "-rm -rf /")
        assertTrue(result is ReadResult.Failed)
        assertEquals(0, fake.calls)
    }

    // ------------------------------------------------------------- diff

    @Test
    fun `worktree index and commit diffs each exec their pinned argv`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "", stderr = "") }
        val r = reader(fake)
        r.diff("/root/project", DiffTarget.Worktree("a.kt"))
        r.diff("/root/project", DiffTarget.Index("a.kt"))
        r.diff("/root/project", DiffTarget.Commit("a1b2c3d", "a.kt"))
        r.diff("/root/project", DiffTarget.Commit("a1b2c3d", null))
        assertEquals(
            listOf(
                listOf("git", "-C", "/root/project", "diff", "--", "a.kt"),
                listOf("git", "-C", "/root/project", "diff", "--cached", "--", "a.kt"),
                listOf(
                    "git", "-C", "/root/project", "show",
                    "--format=", "--patch", "--first-parent", "a1b2c3d", "--", "a.kt",
                ),
                listOf(
                    "git", "-C", "/root/project", "show",
                    "--format=", "--patch", "--first-parent", "a1b2c3d",
                ),
            ),
            fake.argvs,
        )
    }

    @Test
    fun `a diff parses through the diff parser with real line numbers`() {
        val stdout = "@@ -1,2 +1,2 @@\n-old\n+new\n context\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val text = (reader(fake).diff("/root/project", DiffTarget.Worktree("a.kt")) as ReadResult.Done).value
        assertEquals(0, text.hidden)
        val lines = text.parsed.files.single().hunks.single().lines
        assertEquals("old", lines[1].text)
        assertEquals(1, lines[1].oldLine)
        assertEquals("new", lines[2].text)
        assertEquals(1, lines[2].newLine)
    }

    // ------------------------------------------------------ deep reads

    @Test
    fun `blame execs the pinned argv and caps the page`() {
        val stdout = (1 until 12).joinToString("\n") { "hash$it (A 2026-01-01 $it) line $it" } + "\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val page = (reader(fake).blame("/root/project", "src/a.kt", maxLines = 10) as ReadResult.Done).value
        assertEquals(listOf("git", "-C", "/root/project", "blame", "-l", "--", "src/a.kt"), fake.argvs.single())
        assertEquals(10, page.lines.size)
        assertEquals(1, page.hidden)
    }

    @Test
    fun `file history execs log for the path`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "", stderr = "") }
        reader(fake).fileHistory("/root/project", "src/a.kt", limit = 30)
        assertEquals(
            listOf(
                "git", "-C", "/root/project", "log", "-n", "30",
                "--pretty=format:${GitReader.LOG_FORMAT}", "--", "src/a.kt",
            ),
            fake.argvs.single(),
        )
    }

    @Test
    fun `reflog rows parse hash and subject`() {
        val fake = FakeExec {
            ExecResult(exitCode = 0, stdout = "a1b2c3d\tcommit: add the thing\ne4f5a6b\tcheckout: moving to main\n", stderr = "")
        }
        val entries = (reader(fake).reflog("/root/project") as ReadResult.Done).value
        assertEquals(2, entries.size)
        assertEquals("a1b2c3d", entries[0].hash)
        assertEquals("commit: add the thing", entries[0].subject)
    }

    @Test
    fun `tags parse name hash and date with a bounded total`() {
        val lines = (0 until 45).joinToString("\n") { "v1.$it\tb1b2b3b\t2026-09-0${it % 10}" } + "\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = lines, stderr = "") }
        val tags = (reader(fake).tags("/root/project") as ReadResult.Done).value
        assertEquals(GitReader.TAG_CAP, tags.items.size)
        assertEquals(45, tags.total)
        assertEquals("v1.0", tags.items[0].name)
    }

    @Test
    fun `graph execs the graph log and caps the page`() {
        val stdout = "* a1b2c3d add x\n|\n* e4f5a6b fix y\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val page = (reader(fake).graph("/root/project") as ReadResult.Done).value
        assertEquals(
            listOf("git", "-C", "/root/project", "log", "-n", "60", "--graph", "--pretty=format:%h%d %s"),
            fake.argvs.single(),
        )
        assertEquals(3, page.lines.size)
    }

    @Test
    fun `worktrees parse the porcelain blocks`() {
        val stdout = "worktree /root/project\n" +
            "HEAD a1b2c3da1b2c3da1b2c3da1b2c3da1b2c3da1b2c3d\n" +
            "branch refs/heads/main\n" +
            "\n" +
            "worktree /root/wt-hotfix\n" +
            "HEAD e4f5a6be4f5a6be4f5a6be4f5a6be4f5a6be4f5a6b\n" +
            "branch refs/heads/hotfix\n" +
            "\n" +
            "worktree /root/bare.git\n" +
            "bare\n"
        val trees = parseWorktrees(stdout.lineSequence().toList(), cap = 12)
        assertEquals(3, trees.size)
        assertEquals("/root/project", trees[0].path)
        assertEquals("main", trees[0].branch)
        assertEquals("hotfix", trees[1].branch)
        assertTrue(trees[2].bare)
        assertNull(trees[2].branch)
    }

    @Test
    fun `submodule rows parse status path and describe`() {
        val stdout = " 12345678901234567890123456789012345678901 libs/old (v1.0)\n" +
            "-0987654321098765432109876543210987654321 libs/new\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val subs = (reader(fake).submodules("/root/project") as ReadResult.Done).value
        assertEquals(2, subs.size)
        assertTrue(subs[0].initialized)
        assertEquals("libs/old", subs[0].path)
        assertEquals("v1.0", subs[0].describe)
        assertFalse(subs[1].initialized)
        assertEquals("libs/new", subs[1].path)
    }

    @Test
    fun `identity execs the one script and parses both keys`() {
        val stdout = "@@NAME:Alice\n@@EMAIL:a@b.c\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val identity = (reader(fake).identity("/root/project") as ReadResult.Done).value
        assertEquals(listOf("/bin/sh", "-c", GitReader.IDENTITY_SCRIPT, "sh", "/root/project"), fake.argvs.single())
        assertEquals("Alice", identity.name)
        assertEquals("a@b.c", identity.email)
        assertTrue(identity.complete)
    }

    @Test
    fun `sparse checkout failure is the honest not-using answer`() {
        val fake = FakeExec { ExecResult(exitCode = 1, stdout = "", stderr = "fatal: this worktree is not sparse\n") }
        val result = reader(fake).sparseCheckout("/root/project")
        assertEquals("fatal: this worktree is not sparse", (result as ReadResult.Failed).reason)
    }

    // ----------------------------------------------------- failures

    @Test
    fun `a git failure carries the stderr tail - never dressed as empty`() {
        val fake = FakeExec { ExecResult(exitCode = 128, stdout = "", stderr = "fatal: bad object\n") }
        val result = reader(fake).history("/root/project", 30)
        assertEquals("fatal: bad object", (result as ReadResult.Failed).reason)
    }

    @Test
    fun `a dead exec is a failure with the exec error`() {
        val fake = FakeExec { ExecResult(exitCode = null, stdout = "", stderr = "", error = "guest gone") }
        val result = reader(fake).history("/root/project", 30)
        assertEquals("guest gone", (result as ReadResult.Failed).reason)
    }

    @Test
    fun `a thrown exec is a failure, never a crash`() {
        val fake = FakeExec { throw IllegalStateException("proot missing") }
        val result = reader(fake).history("/root/project", 30)
        assertEquals("proot missing", (result as ReadResult.Failed).reason)
    }

    @Test
    fun `unparseable output is a failure - never invented data`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "garbage\n", stderr = "") }
        val result = reader(fake).commitDetail("/root/project", "a1b2c3d")
        assertTrue((result as ReadResult.Failed).reason.contains("unrecognized"))
    }
}
