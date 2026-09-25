package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's protocol parsing, honest degradation and idle gate — all
 * against a fake exec, no guest needed. The REAL exec path (proot spec +
 * background runner) is wired in GitApp and pinned by GitAppContractTest;
 * this suite pins WHAT the probe asks for and HOW it degrades — including
 * the M8.4.3 inspection sections (@@LOG / @@BRANCHES / @@REMOTES) and the
 * manual-only show/diff execs.
 */
class GitProbeTest {

    /** The %x1f field separator the probe's log format emits. */
    private val SEP = "\u001f"

    // --------------------------------------------------------- fixtures

    private val fullOutput = """
        @@GIT:2.34.1
        @@REPO:/root/project
        ## main...origin/main [ahead 1, behind 2]
         M src/a.kt
        ?? notes.txt
        @@RC:0
        @@LOG
        a1b2c3d${SEP}2 days ago${SEP}HEAD -> main${SEP}Alice Author${SEP}Add the thing
        e4f5a6b${SEP}3 hours ago${SEP}${SEP}Bob${SEP}Fix the pipe | in subjects
        @@BRANCHES
        main${'\t'}origin/main${'\t'}[ahead 1, behind 2]
        dev${'\t'}${'\t'}
        topic${'\t'}origin/topic${'\t'}[gone]
        @@REMOTES
        origin${'\t'}https://github.com/user/repo.git (fetch)
        origin${'\t'}https://github.com/user/repo.git (push)
        fork${'\t'}git@github.com:other/repo.git (fetch)
        @@REPO:/root/broken
        @@RC:128
        @@LOG
        deadbee${SEP}1 week ago${SEP}${SEP}Ghost${SEP}Written before the repo broke
        @@DONE
    """.trimIndent() + "\n"

    private class FakeExec(
        private val result: () -> ExecResult,
    ) : GitProbe.GuestExec {
        val argvs = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        var calls = 0

        override fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult {
            calls += 1
            argvs.add(guestCommand)
            timeouts.add(timeoutMs)
            return result()
        }
    }

    // -------------------------------------------------- protocol parse

    @Test
    fun `a full probe output parses version and repo blocks`() {
        val parsed = parseProbeOutput(fullOutput)
        assertNotNull(parsed)
        assertEquals("2.34.1", parsed!!.gitVersion)
        assertTrue(parsed.complete)
        assertEquals(2, parsed.repos.size)
        assertEquals("/root/project", parsed.repos[0].path)
        // the status region stays untouched porcelain — sections after
        // @@RC never leak into it
        assertEquals(listOf("## main...origin/main [ahead 1, behind 2]", " M src/a.kt", "?? notes.txt"), parsed.repos[0].lines)
        assertEquals(0, parsed.repos[0].rc)
        assertEquals("/root/broken", parsed.repos[1].path)
        assertEquals(128, parsed.repos[1].rc)
    }

    @Test
    fun `the per-repo inspection sections parse into their blocks`() {
        val parsed = parseProbeOutput(fullOutput)!!
        val project = parsed.repos[0]
        assertEquals(
            listOf(
                "a1b2c3d${SEP}2 days ago${SEP}HEAD -> main${SEP}Alice Author${SEP}Add the thing",
                "e4f5a6b${SEP}3 hours ago${SEP}${SEP}Bob${SEP}Fix the pipe | in subjects",
            ),
            project.logLines,
        )
        assertEquals(
            listOf(
                "main${'\t'}origin/main${'\t'}[ahead 1, behind 2]",
                "dev${'\t'}${'\t'}",
                "topic${'\t'}origin/topic${'\t'}[gone]",
            ),
            project.branchLines,
        )
        assertEquals(
            listOf(
                "origin${'\t'}https://github.com/user/repo.git (fetch)",
                "origin${'\t'}https://github.com/user/repo.git (push)",
                "fork${'\t'}git@github.com:other/repo.git (fetch)",
            ),
            project.remoteLines,
        )
    }

    @Test
    fun `a stream that ends before DONE is marked incomplete`() {
        val parsed = parseProbeOutput("@@GIT:2.34.1\n@@REPO:/root/x\n@@RC:0\n@@LOG\n")
        assertNotNull(parsed)
        assertFalse(parsed!!.complete)
    }

    @Test
    fun `a repo block cut short before its exit code has a null rc`() {
        val parsed = parseProbeOutput("@@GIT:2.34.1\n@@REPO:/root/x\n## main\n@@DONE\n")
        assertNotNull(parsed)
        assertNull(parsed!!.repos.single().rc)
    }

    @Test
    fun `an absent git binary is the empty version marker`() {
        val parsed = parseProbeOutput("@@GIT:\n@@DONE\n")
        assertNotNull(parsed)
        assertNull(parsed!!.gitVersion)
        assertTrue(parsed.repos.isEmpty())
        assertTrue(parsed.complete)
    }

    @Test
    fun `output without the version marker is not data`() {
        assertNull(parseProbeOutput("some random stdout\n"))
        assertNull(parseProbeOutput(""))
    }

    @Test
    fun `stray lines between RC and the first section marker are dropped - not data`() {
        val parsed = parseProbeOutput(
            "@@GIT:2.34.1\n@@REPO:/root/x\n## main\n@@RC:0\ngarbage line\n@@LOG\na1b2c3d${SEP}now${SEP}${SEP}A${SEP}S\n@@DONE\n",
        )
        assertNotNull(parsed)
        assertTrue(parsed!!.repos.single().lines.all { it.startsWith("##") })
        assertEquals(1, parsed.repos.single().logLines.size)
    }

    // ------------------------------------------------ block parsing

    @Test
    fun `the log block keeps a separator inside the subject as one field`() {
        val log = parseLogBlock(
            listOf(
                "a1b2c3d${SEP}2 days ago${SEP}HEAD -> main${SEP}Alice${SEP}Add the thing",
                "e4f5a6b${SEP}3 hours ago${SEP}${SEP}Bob${SEP}Fix: a ${SEP}b | c",
                "1111111${SEP}now${SEP}${SEP}Cara${SEP}",
            ),
        )
        assertEquals(3, log.size)
        assertEquals("a1b2c3d", log[0].hash)
        assertEquals("Alice", log[0].author)
        assertEquals("2 days ago", log[0].relativeTime)
        assertEquals("HEAD -> main", log[0].refs)
        assertEquals("Add the thing", log[0].subject)
        assertEquals("Fix: a ${SEP}b | c", log[1].subject)
        assertNull(log[1].refs)
        assertEquals("", log[2].subject) // an empty subject is honest, not malformed
    }

    @Test
    fun `malformed log lines are skipped - the script owns the bound`() {
        val lines = mutableListOf("no separator here", "a${SEP}b", "ok01234${SEP}1 day ago${SEP}${SEP}A${SEP}Real")
        repeat(7) { i -> lines += "capped0$i${SEP}now${SEP}${SEP}A${SEP}Filler $i" }
        val log = parseLogBlock(lines)
        assertEquals(8, log.size) // every well-formed row parses; no parser-side cap
        assertEquals("ok01234", log[0].hash)
        assertEquals("Real", log[0].subject)
        assertEquals("capped00", log[1].hash)
    }

    @Test
    fun `the branch block parses upstream and tracking state`() {
        val branches = parseBranchBlock(
            listOf(
                "main${'\t'}origin/main${'\t'}[ahead 2, behind 1]",
                "dev${'\t'}${'\t'}",
                "topic${'\t'}origin/topic${'\t'}[gone]",
                "local-only${'\t'} ${'\t'}", // whitespace around an empty upstream still parses to null
            ),
        )
        assertEquals(4, branches.size)
        assertEquals("main", branches[0].name)
        assertEquals("origin/main", branches[0].upstream)
        assertEquals(2, branches[0].ahead)
        assertEquals(1, branches[0].behind)
        assertFalse(branches[0].gone)
        assertEquals("dev", branches[1].name)
        assertNull(branches[1].upstream)
        assertNull(branches[1].ahead)
        assertEquals("topic", branches[2].name)
        assertTrue(branches[2].gone)
        assertEquals("local-only", branches[3].name)
        assertNull(branches[3].upstream)
    }

    @Test
    fun `the branch block parses every well-formed row - the script owns the bound`() {
        val lines = mutableListOf("junk line without tabs")
        repeat(15) { i -> lines += "branch-$i${'\t'}origin/branch-$i${'\t'}[ahead 1]" }
        val branches = parseBranchBlock(lines)
        assertEquals(15, branches.size)
        assertEquals("branch-0", branches[0].name)
    }

    @Test
    fun `the remote block keeps the first fetch URL per name and ignores push`() {
        val remotes = parseRemoteBlock(
            listOf(
                "origin\thttps://github.com/user/repo.git (fetch)",
                "origin\thttps://elsewhere.org/user/repo.git (push)",
                "origin\thttps://should-not-win.org/repo.git (fetch)",
                "fork\tgit@github.com:other/repo.git (fetch)",
            ),
        )
        assertEquals(2, remotes.size)
        assertEquals("origin", remotes[0].name)
        assertEquals("https://github.com/user/repo.git", remotes[0].fetchUrl)
        assertEquals("https://elsewhere.org/user/repo.git", remotes[0].pushUrl)
        assertTrue(remotes[0].pushUrlDiffers)
        assertEquals("fork", remotes[1].name)
        assertEquals("git@github.com:other/repo.git", remotes[1].fetchUrl)
        assertNull(remotes[1].pushUrl)
        assertFalse(remotes[1].pushUrlDiffers)
    }

    @Test
    fun `the remote block caps at the probe bound and skips junk lines`() {
        val lines = mutableListOf("just-a-name-no-url")
        repeat(12) { i ->
            lines += "remote-$i\thttps://host-$i/repo.git (fetch)"
            lines += "remote-$i\thttps://host-$i/repo.git (push)"
        }
        val remotes = parseRemoteBlock(lines)
        assertEquals(GitProbe.REMOTE_MAX_ENTRIES, remotes.size)
        assertEquals("remote-0", remotes[0].name)
    }

    // --------------------------------------------------- snapshot path

    @Test
    fun `snapshot asks for exactly the one batched script`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = fullOutput, stderr = "") }
        GitProbe(fake).snapshot()
        assertEquals(1, fake.calls)
        assertEquals(listOf("/bin/sh", "-c", GitProbe.PROBE_SCRIPT, "sh"), fake.argvs.single())
        assertEquals(GitProbe.SCAN_TIMEOUT_MS, fake.timeouts.single())
    }

    @Test
    fun `a healthy scan renders repositories with parsed status and inspection lists`() {
        val probe = GitProbe(FakeExec { ExecResult(exitCode = 0, stdout = fullOutput, stderr = "") })
        val result = probe.snapshot()
        val snapshot = (result as ScanResult.Done).snapshot
        assertEquals("2.34.1", snapshot.gitVersion)
        assertTrue(snapshot.hasGit)
        assertEquals(2, snapshot.repos.size)

        val project = snapshot.repos[0]
        assertEquals("project", project.name)
        assertNull(project.error)
        assertEquals("main", project.status!!.branch)
        assertEquals(1, project.status!!.ahead)
        assertEquals(2, project.status!!.behind)
        // one worktree change + one untracked path
        assertEquals(2, project.status!!.totalChanges)
        assertTrue(project.status!!.dirty)
        assertEquals(2, project.log.size)
        assertEquals("Add the thing", project.log[0].subject)
        assertEquals(3, project.branches.size)
        assertEquals(1, project.branches[0].ahead)
        assertEquals(2, project.branches[0].behind)
        assertEquals(2, project.remotes.size)
        assertEquals("origin", project.remotes[0].name)

        val broken = snapshot.repos[1]
        assertNull(broken.status)
        assertEquals("git exited with 128", broken.error)
        // the inspection sections degrade with the repo they belong to:
        // real lines pass through, a failed status never poisons them
        assertEquals(1, broken.log.size)
        assertEquals("deadbee", broken.log[0].hash)
        assertTrue(broken.branches.isEmpty())
        assertTrue(broken.remotes.isEmpty())
    }

    @Test
    fun `a probe output without the new sections leaves inspection lists empty - status intact`() {
        val probe = GitProbe(
            FakeExec {
                ExecResult(exitCode = 0, stdout = "@@GIT:2.34.1\n@@REPO:/root/x\n## main\n@@RC:0\n@@DONE\n", stderr = "")
            },
        )
        val snapshot = (probe.snapshot() as ScanResult.Done).snapshot
        val repo = snapshot.repos.single()
        assertEquals("main", repo.status!!.branch)
        assertTrue(repo.log.isEmpty())
        assertTrue(repo.branches.isEmpty())
        assertTrue(repo.remotes.isEmpty())
    }

    @Test
    fun `git absent degrades honestly - not to an empty repo list`() {
        val probe = GitProbe(FakeExec { ExecResult(exitCode = 0, stdout = "@@GIT:\n@@DONE\n", stderr = "") })
        val snapshot = (probe.snapshot() as ScanResult.Done).snapshot
        assertFalse(snapshot.hasGit)
        assertTrue(snapshot.repos.isEmpty())
    }

    @Test
    fun `a truncated scan is a failed probe, not partial data`() {
        val probe = GitProbe(FakeExec { ExecResult(exitCode = 0, stdout = "@@GIT:2.34.1\n@@REPO:/root/x\n", stderr = "") })
        val result = probe.snapshot()
        assertTrue(result is ScanResult.Failed)
        assertTrue((result as ScanResult.Failed).reason.contains("truncated"))
    }

    @Test
    fun `unrecognizable stdout is a failed probe`() {
        val probe = GitProbe(FakeExec { ExecResult(exitCode = 0, stdout = "bin/sh: syntax error\n", stderr = "") })
        assertTrue(probe.snapshot() is ScanResult.Failed)
    }

    @Test
    fun `a real exec failure carries the stderr reason`() {
        val probe = GitProbe(
            FakeExec { ExecResult(exitCode = 137, stdout = "", stderr = "guest process died\n") },
        )
        val result = probe.snapshot()
        assertTrue(result is ScanResult.Failed)
        assertEquals("guest process died", (result as ScanResult.Failed).reason)
    }

    @Test
    fun `a thrown exec becomes a failed probe, never a crash`() {
        val probe = GitProbe(FakeExec { throw IllegalStateException("proot missing") })
        val result = probe.snapshot()
        assertEquals("proot missing", (result as ScanResult.Failed).reason)
    }

    // ------------------------------------------------------- idle gate

    @Test
    fun `the first tick always scans`() {
        var now = 0L
        val probe = GitProbe(FakeExec { ExecResult(exitCode = 0, stdout = fullOutput, stderr = "") }, clock = { now })
        assertTrue(probe.shouldFullScan(now))
    }

    @Test
    fun `a tick right after a scan does nothing`() {
        var now = 1_000L
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = fullOutput, stderr = "") }
        val probe = GitProbe(fake, clock = { now })
        probe.snapshot()
        now += GitProbe.AUTO_RESCAN_MS - 1
        assertFalse(probe.shouldFullScan(now))
        assertEquals(1, fake.calls)
    }

    @Test
    fun `an idle open card rescans only after the full interval`() {
        var now = 1_000L
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = fullOutput, stderr = "") }
        val probe = GitProbe(fake, clock = { now })
        probe.snapshot()
        now += GitProbe.AUTO_RESCAN_MS
        assertTrue(probe.shouldFullScan(now))
        probe.snapshot()
        assertEquals(2, fake.calls)
    }

    @Test
    fun `the gate is the only thing a cheap tick costs`() {
        var now = 0L
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = fullOutput, stderr = "") }
        val probe = GitProbe(fake, clock = { now })
        probe.snapshot()
        repeat(10) {
            now += 1_999 // ticks that stay inside the idle window
            assertFalse(probe.shouldFullScan(now))
        }
        assertEquals(1, fake.calls)
    }

    // --------------------------------------------------- display paths

    @Test
    fun `guest home paths display with a tilde`() {
        assertEquals("~/Projects/app", displayGuestRepoPath("/root/Projects/app"))
        assertEquals("~/repo", displayGuestRepoPath("/root/repo"))
        assertEquals("~", displayGuestRepoPath("/root"))
    }

    @Test
    fun `paths outside the guest home pass through verbatim`() {
        assertEquals("/srv/repo", displayGuestRepoPath("/srv/repo"))
    }
}
