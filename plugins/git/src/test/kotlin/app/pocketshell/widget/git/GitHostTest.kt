package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GitHub provider seam, against fixture gh output: slug detection from
 * remote URLs, the presence/auth protocol, TSV row parsing, the PR detail
 * script's sentinel shape — and the honest failures in between.
 */
class GitHostTest {

    private class FakeExec(
        private val result: (argv: List<String>) -> ExecResult,
    ) : GitProbe.GuestExec {
        val argvs = mutableListOf<List<String>>()
        var calls = 0

        override fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult {
            calls += 1
            argvs.add(guestCommand)
            return result(guestCommand)
        }
    }

    // ------------------------------------------------------- slug facts

    @Test
    fun `the slug comes from the first GitHub remote - both URL shapes`() {
        assertEquals(
            "user/repo",
            GitHost.githubSlug(listOf(remote("https://github.com/user/repo.git"))),
        )
        assertEquals(
            "user/repo",
            GitHost.githubSlug(listOf(remote("git@github.com:user/repo.git"))),
        )
        assertEquals(
            "user/repo",
            GitHost.githubSlug(listOf(remote("ssh://git@github.com/user/repo.git"))),
        )
    }

    @Test
    fun `non-GitHub remotes yield no slug - the tab stays honest`() {
        assertNull(GitHost.githubSlug(listOf(remote("git@gitlab.com:user/repo.git"))))
        assertNull(GitHost.githubSlug(listOf(remote("/srv/local/repo"))))
        assertNull(GitHost.githubSlug(emptyList()))
        // a GitHub-shaped path that is not owner/repo is refused, not guessed
        assertNull(GitHost.githubSlug(listOf(remote("https://github.com/user/repo/extra.git"))))
    }

    private fun remote(url: String) = Remote(name = "origin", fetchUrl = url, pushUrl = url)

    // -------------------------------------------------- status protocol

    @Test
    fun `detection distinguishes absent unauthed and ready`() {
        fun detect(stdout: String): GitHost.HostStatus {
            val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
            return GitHost(fake).status()
        }
        assertEquals(GitHost.HostStatus.NotInstalled, detect("@@GH:no\n"))
        assertEquals(GitHost.HostStatus.NotAuthed, detect("@@GH:unauthed\n"))
        assertEquals(GitHost.HostStatus.Ready, detect("@@GH:authed\n"))
    }

    @Test
    fun `a dead gh detection is a failure with the exec error`() {
        val fake = FakeExec { ExecResult(exitCode = null, stdout = "", stderr = "", error = "guest gone") }
        assertEquals(GitHost.HostStatus.Failed("guest gone"), GitHost(fake).status())
    }

    // -------------------------------------------------------- list rows

    @Test
    fun `pull requests parse the jq tsv rows`() {
        // gh's @tsv escapes tabs INSIDE fields ("a\tb"), so raw tabs only
        // ever separate fields — the splitter can rely on that.
        val stdout = "12\tfeature/x\talice\tAdd the thing\n7\tmain\tbob\tFix: a\\tb\n"
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = stdout, stderr = "") }
        val prs = GitHost(fake).pullRequests("user/repo").let { it as ReadResult.Done }.value
        assertEquals(2, prs.size)
        assertEquals(12, prs[0].number)
        assertEquals("feature/x", prs[0].head)
        assertEquals("alice", prs[0].author)
        assertEquals("Add the thing", prs[0].title)
        // the escaped tab arrives as the two characters jq printed
        assertEquals("Fix: a\\tb", prs[1].title)
        assertTrue(
            "the gh argv carries the slug and a --limit",
            fake.argvs.single().contains("user/repo") && fake.argvs.single().contains("--limit"),
        )
    }

    @Test
    fun `issues runs and releases parse their rows`() {
        val fake = FakeExec { ExecResult(exitCode = 0, stdout = "3\tOPEN\tcarol\tIt breaks\n", stderr = "") }
        val issues = GitHost(fake).issues("user/repo").let { it as ReadResult.Done }.value
        assertEquals(3, issues[0].number)
        assertEquals("OPEN", issues[0].state)

        val fakeRuns = FakeExec {
            ExecResult(exitCode = 0, stdout = "991\tcompleted\tsuccess\tCI\tBuild the app\n", stderr = "")
        }
        val runs = GitHost(fakeRuns).runs("user/repo").let { it as ReadResult.Done }.value
        assertEquals(991L, runs[0].id)
        assertEquals('✓', runs[0].glyph)

        val fakeRunsBad = FakeExec {
            ExecResult(exitCode = 0, stdout = "992\tin_progress\tpending\tCI\tBuild\n", stderr = "")
        }
        assertEquals('•', GitHost(fakeRunsBad).runs("user/repo").let { it as ReadResult.Done }.value[0].glyph)

        val fakeRel = FakeExec {
            ExecResult(exitCode = 0, stdout = "v2.0.0\tSecond release\t2026-09-01\n", stderr = "")
        }
        val releases = GitHost(fakeRel).releases("user/repo").let { it as ReadResult.Done }.value
        assertEquals("v2.0.0", releases[0].tag)
    }

    @Test
    fun `a gh failure carries the stderr tail`() {
        val fake = FakeExec { ExecResult(exitCode = 4, stdout = "", stderr = "gh: Not Found (HTTP 404)\n") }
        val result = GitHost(fake).pullRequests("user/repo")
        assertEquals("gh: Not Found (HTTP 404)", (result as ReadResult.Failed).reason)
    }

    // ------------------------------------------------------- pr detail

    @Test
    fun `the pr detail exec passes slug and number as positional arguments`() {
        val fake = FakeExec {
            ExecResult(
                exitCode = 0,
                stdout = "12\tOPEN\talice\tfeature/x\tmain\t-\tMERGEABLE\tT\n" +
                    GitHost.BODY_SENTINEL + "\nbody\n",
                stderr = "",
            )
        }
        val detail = GitHost(fake).prDetail("user/repo", 12).let { it as ReadResult.Done }.value
        assertEquals(12, detail.number)
        val argv = fake.argvs.single()
        assertEquals(listOf("/bin/sh", "-c", GitHost.PR_DETAIL_SCRIPT, "sh", "user/repo", "12"), argv)
    }

    @Test
    fun `parsePrDetail reads the tsv header and the free body`() {
        val lines = listOf(
            "12\tOPEN\talice\tfeature/x\tmain\tAPPROVED\tMERGEABLE\tAdd the thing",
            GitHost.BODY_SENTINEL,
            "A longer body.",
            "Second line.",
        )
        val detail = GitHost.parsePrDetail(lines)!!
        assertEquals(12, detail.number)
        assertEquals("OPEN", detail.state)
        assertEquals("alice", detail.author)
        assertEquals("feature/x", detail.head)
        assertEquals("main", detail.base)
        assertEquals("APPROVED", detail.reviewDecision)
        assertEquals("MERGEABLE", detail.mergeable)
        assertEquals("Add the thing", detail.title)
        assertEquals("A longer body.\nSecond line.", detail.body)
    }

    @Test
    fun `a pr without a body parses to null - never an invented empty`() {
        val lines = listOf("5\tMERGED\tbob\tdev\tmain\t-\tMERGEABLE\tFix it", GitHost.BODY_SENTINEL, "")
        val detail = GitHost.parsePrDetail(lines)!!
        assertNull(detail.body)
    }

    @Test
    fun `unrecognizable pr output is a failure`() {
        val lines = listOf("not a header at all")
        assertNull(GitHost.parsePrDetail(lines))
    }
}
