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

    // --------------------------------------------------------- fixtures

    private val fullOutput = """
        @@GIT:2.34.1
        @@REPO:/root/project
        ## main...origin/main [ahead 1, behind 2]
         M src/a.kt
        ?? notes.txt
        @@RC:0
        @@LOG
        a1b2c3d|Alice Author|2 days ago|Add the thing
        e4f5a6b|Bob|3 hours ago|Fix the pipe | in subjects
        @@BRANCHES
        main|origin/main|[ahead 1, behind 2]
        dev||
        topic|origin/topic|[gone]
        @@REMOTES
        origin${'\t'}https://github.com/user/repo.git (fetch)
        origin${'\t'}https://github.com/user/repo.git (push)
        fork${'\t'}git@github.com:other/repo.git (fetch)
        @@REPO:/root/broken
        @@RC:128
        @@LOG
        deadbee|Ghost|1 week ago|Written before the repo broke
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
            listOf("a1b2c3d|Alice Author|2 days ago|Add the thing", "e4f5a6b|Bob|3 hours ago|Fix the pipe | in subjects"),
            project.logLines,
        )
        assertEquals(
            listOf("main|origin/main|[ahead 1, behind 2]", "dev||", "topic|origin/topic|[gone]"),
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
            "@@GIT:2.34.1\n@@REPO:/root/x\n## main\n@@RC:0\ngarbage line\n@@LOG\na1b2c3d|A|now|S\n@@DONE\n",
        )
        assertNotNull(parsed)
        assertTrue(parsed!!.repos.single().lines.all { it.startsWith("##") })
        assertEquals(1, parsed.repos.single().logLines.size)
    }

    // ------------------------------------------------ block parsing

    @Test
    fun `the log block keeps a pipe inside the subject as one field`() {
        val log = parseLogBlock(
            listOf(
                "a1b2c3d|Alice|2 days ago|Add the thing",
                "e4f5a6b|Bob|3 hours ago|Fix: a | b | c",
                "1111111|Cara|now|",
            ),
        )
        assertEquals(3, log.size)
        assertEquals("a1b2c3d", log[0].hash)
        assertEquals("Alice", log[0].author)
        assertEquals("2 days ago", log[0].relativeTime)
        assertEquals("Add the thing", log[0].subject)
        assertEquals("Fix: a | b | c", log[1].subject)
        assertEquals("", log[2].subject) // an empty subject is honest, not malformed
    }

    @Test
    fun `malformed log lines are skipped and the block caps at five`() {
        val lines = mutableListOf("no pipes here", "a|b|c", "ok01234|A|1 day ago|Real")
        repeat(7) { i -> lines += "capped0$i|A|now|Filler $i" }
        val log = parseLogBlock(lines)
        assertEquals(GitProbe.LOG_MAX_ENTRIES, log.size)
        assertEquals("ok01234", log[0].hash)
        assertEquals("Real", log[0].subject)
        assertEquals("capped00", log[1].hash)
    }

    @Test
    fun `the branch block parses upstream and tracking state`() {
        val branches = parseBranchBlock(
            listOf(
                "main|origin/main|[ahead 2, behind 1]",
                "dev||",
                "topic|origin/topic|[gone]",
                "local-only| |", // whitespace around an empty upstream still parses to null
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
    fun `the branch block caps at twelve and skips malformed lines`() {
        val lines = mutableListOf("junk line without pipes")
        repeat(15) { i -> lines += "branch-$i|origin/branch-$i|[ahead 1]" }
        val branches = parseBranchBlock(lines)
        assertEquals(GitProbe.BRANCH_MAX_ENTRIES, branches.size)
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
        assertEquals("https://github.com/user/repo.git", remotes[0].url)
        assertEquals("fork", remotes[1].name)
        assertEquals("git@github.com:other/repo.git", remotes[1].url)
    }

    @Test
    fun `the remote block caps at six remotes and skips junk lines`() {
        val lines = mutableListOf("just-a-name-no-url")
        repeat(8) { i ->
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

    // --------------------------------------------- manual show / diff

    private val showOutput = """
        a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0
        Alice Author <alice@example.com>
        Thu Sep 18 12:00:00 2026 +0500
        2 days ago
        Add the thing

        The body paragraph.

        @@NUMSTAT@@

        4${'\t'}2${'\t'}src/Main.kt
        1${'\t'}1${'\t'}src/Other.kt
        -${'\t'}-${'\t'}logo.png
    """.trimIndent() + "\n"

    @Test
    fun `showCommit execs the pinned read-only argv and parses the numstat block`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = showOutput, stderr = "") }
        val result = GitProbe(fake).showCommit("/root/project", "a1b2c3d")
        val detail = (result as CommitResult.Done).detail
        assertEquals(
            listOf(
                "git", "-C", "/root/project",
                "show", "--numstat",
                "--pretty=format:%H%n%an <%ae>%n%ad%n%ar%n%s%n%b%n@@NUMSTAT@@%n",
                "a1b2c3d",
            ),
            fake.argvs.single(),
        )
        assertEquals(GitProbe.MANUAL_TIMEOUT_MS, fake.timeouts.single())
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", detail.fullHash)
        assertEquals("Alice Author", detail.author)
        assertEquals("alice@example.com", detail.email)
        assertEquals("Thu Sep 18 12:00:00 2026 +0500", detail.dateText)
        assertEquals("2 days ago", detail.relativeDate)
        assertEquals("Add the thing", detail.subject)
        assertEquals(listOf("The body paragraph."), detail.body)
        assertEquals(3, detail.files.size)
        assertEquals(0, detail.hiddenFiles)
        assertEquals(CommitFile("src/Main.kt", added = "4", deleted = "2"), detail.files[0])
        assertEquals(CommitFile("logo.png", added = "-", deleted = "-"), detail.files[2])
    }

    @Test
    fun `showCommit caps the file list with an honest hidden count`() {
        val stats = (1..65).joinToString("\n") { "1${'\t'}0${'\t'}file-$it.kt" }
        val raw = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0\nA <a@b.c>\nnow\nnow\nSubject\n\n@@NUMSTAT@@\n$stats\n"
        val detail = parseShowOutput(raw)!!
        assertEquals(GitProbe.COMMIT_FILES_MAX, detail.files.size)
        assertEquals(5, detail.hiddenFiles)
    }

    @Test
    fun `a non-hex hash is refused before any exec`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "", stderr = "") }
        val result = GitProbe(fake).showCommit("/root/project", "-rm -rf /")
        assertTrue(result is CommitResult.Failed)
        assertEquals(0, fake.calls) // nothing reached the guest
    }

    @Test
    fun `a failed show carries the tool's real stderr tail`() {
        val fake = FakeExec { ExecResult(exitCode = 128, stdout = "", stderr = "fatal: bad object a1b2c3d\n") }
        val result = GitProbe(fake).showCommit("/root/project", "a1b2c3d")
        assertEquals("fatal: bad object a1b2c3d", (result as CommitResult.Failed).reason)
    }

    @Test
    fun `unrecognizable show output is a failure - never invented facts`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "not a show block\n", stderr = "") }
        val result = GitProbe(fake).showCommit("/root/project", "a1b2c3d")
        assertTrue((result as CommitResult.Failed).reason.contains("unrecognized"))
    }

    // ------------------------------------------- history pages / files

    @Test
    fun `logPage execs the paged argv and detects hasMore by the extra entry`() {
        val page = (1..21).joinToString("\n") { "h$it|A|now|Subject $it" }
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = page, stderr = "") }
        val result = GitProbe(fake).logPage("/root/project", skip = 20, count = 20)
        assertEquals(
            listOf(
                "git", "-C", "/root/project",
                "log", "--skip=20", "-n", "21",
                "--pretty=format:%h|%an|%ar|%s",
            ),
            fake.argvs.single(),
        )
        val logPage = (result as LogPageResult.Done).page
        assertEquals(20, logPage.entries.size)
        assertTrue(logPage.hasMore)
        assertEquals("Subject 1", logPage.entries.first().subject)
    }

    @Test
    fun `a short page means the history ended`() {
        val page = (1..5).joinToString("\n") { "h$it|A|now|S$it" }
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = page, stderr = "") }
        val page1 = (GitProbe(fake).logPage("/root/project", skip = 0, count = 20) as LogPageResult.Done).page
        assertEquals(5, page1.entries.size)
        assertFalse(page1.hasMore)
    }

    @Test
    fun `listDir execs the script with the dir as argv and parses the entries`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "d\tsrc\nf\tREADME.md\n@@LS-OK\n", stderr = "") }
        val result = GitProbe(fake).listDir("/root/project/sub")
        assertEquals(
            listOf("/bin/sh", "-c", GitProbe.LIST_DIR_SCRIPT, "sh", "/root/project/sub"),
            fake.argvs.single(),
        )
        val entries = (result as ListDirResult.Done).entries
        assertEquals(2, entries.size)
        assertEquals(DirEntry("src", isDir = true), entries[0])
        assertEquals(DirEntry("README.md", isDir = false), entries[1])
    }

    @Test
    fun `a listDir without the completion marker is a failure - never a short listing`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "d\tsrc\n", stderr = "") }
        val result = GitProbe(fake).listDir("/root/project")
        assertTrue(result is ListDirResult.Failed)
    }

    @Test
    fun `readHead execs the preview script and reports size, truncation, binary`() {
        val head = (1..201).joinToString("\n") { "line $it" } + "\n"
        val out = "@@SIZE:2048\n@@TEXT\n$head"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = out, stderr = "") }
        val result = GitProbe(fake).readHead("/root/project/README.md")
        assertEquals(
            listOf("/bin/sh", "-c", GitProbe.READ_HEAD_SCRIPT, "sh", "/root/project/README.md"),
            fake.argvs.single(),
        )
        val page = (result as ReadHeadResult.Done).page
        assertEquals(2048L, page.sizeBytes)
        assertEquals(GitProbe.PREVIEW_MAX_LINES, page.lines.size)
        assertTrue(page.truncated)
        assertFalse(page.isBinary)
    }

    @Test
    fun `a NUL byte in the preview is binary - never mojibake`() {
        val out = "@@SIZE:10\n@@TEXT\nabc\u0000def\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = out, stderr = "") }
        val page = (GitProbe(fake).readHead("/root/f") as ReadHeadResult.Done).page
        assertTrue(page.isBinary)
    }

    @Test
    fun `a missing file previews as a failure with its real reason`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "@@MISS\n", stderr = "") }
        val result = GitProbe(fake).readHead("/root/nope")
        assertEquals("not a readable file", (result as ReadHeadResult.Failed).reason)
    }

    @Test
    fun `stashList execs the read-only argv and parses index + subject`() {
        val out = "stash@{0}|WIP on main: abc1234 half done\nstash@{1}|label here\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = out, stderr = "") }
        val result = GitProbe(fake).stashList("/root/project")
        assertEquals(
            listOf("git", "-C", "/root/project", "stash", "list", "--format=%gd|%gs"),
            fake.argvs.single(),
        )
        val entries = (result as StashListResult.Done).entries
        assertEquals(2, entries.size)
        assertEquals(StashEntry(0, "WIP on main: abc1234 half done"), entries[0])
        assertEquals(StashEntry(1, "label here"), entries[1])
    }

    @Test
    fun `remoteBranches lists remote refnames without the HEAD pointer`() {
        val out = "origin/main\norigin/HEAD\norigin/dev\nfork/main\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = out, stderr = "") }
        val result = GitProbe(fake).remoteBranches("/root/project")
        assertEquals(
            listOf("git", "-C", "/root/project", "branch", "-r", "--format=%(refname:short)"),
            fake.argvs.single(),
        )
        assertEquals(listOf("origin/main", "origin/dev", "fork/main"), (result as RemoteBranchesResult.Done).names)
    }

    @Test
    fun `commitFileDiff execs the show argv for one path`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "+line\n", stderr = "") }
        val result = GitProbe(fake).commitFileDiff("/root/project", "a1b2c3d", "src/a.kt")
        assertEquals(
            listOf("git", "-C", "/root/project", "show", "a1b2c3d", "--", "src/a.kt"),
            fake.argvs.single(),
        )
        assertTrue(result is DiffResult.Done)
    }

    @Test
    fun `diffFile execs the worktree diff argv for an unstaged side`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "@@ -1 +1 @@\n-old\n+new\n", stderr = "") }
        val result = GitProbe(fake).diffFile("/root/project", "src/a.kt", staged = false)
        assertEquals(
            listOf("git", "-C", "/root/project", "diff", "--", "src/a.kt"),
            fake.argvs.single(),
        )
        val text = (result as DiffResult.Done).text
        assertEquals(3, text.lines.size)
        assertEquals(0, text.hidden)
    }

    @Test
    fun `diffFile execs the cached diff argv for a staged side`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "+line\n", stderr = "") }
        val result = GitProbe(fake).diffFile("/root/project", "src/a.kt", staged = true)
        assertEquals(
            listOf("git", "-C", "/root/project", "diff", "--cached", "--", "src/a.kt"),
            fake.argvs.single(),
        )
        assertTrue(result is DiffResult.Done)
    }

    @Test
    fun `a diff caps at four hundred lines and two hundred characters`() {
        val long = "x".repeat(300)
        val stdout = (1..405).joinToString("\n") { i -> if (i == 7) long else "line $i" } + "\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val text = (GitProbe(fake).diffFile("/root/project", "f.kt", staged = false) as DiffResult.Done).text
        assertEquals(GitPresentation.DIFF_MAX_LINES, text.lines.size)
        assertEquals(5, text.hidden)
        assertEquals("x".repeat(GitPresentation.MAX_LINE_CHARS) + "…", text.lines[6])
    }

    @Test
    fun `a failed diff carries the tool's real stderr tail`() {
        val fake = FakeExec { ExecResult(exitCode = 128, stdout = "", stderr = "fatal: not a git repository\n") }
        val result = GitProbe(fake).diffFile("/root/project", "f.kt", staged = false)
        assertEquals("fatal: not a git repository", (result as DiffResult.Failed).reason)
    }

    @Test
    fun `a thrown manual exec degrades to a failure, never a crash`() {
        val fake = FakeExec { throw IllegalStateException("guest gone") }
        val result = GitProbe(fake).diffFile("/root/project", "f.kt", staged = true)
        assertEquals("guest gone", (result as DiffResult.Failed).reason)
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
