package app.pocketshell.widget.git

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The black box: bounded breadcrumbs, a readable file, and a crash handler that observes without changing behaviour. */
class GitTrailTest {

    private var savedHandler: Thread.UncaughtExceptionHandler? = null
    private val dirs = ArrayList<File>()

    @Before
    fun setUp() {
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        GitTrail.resetForTest()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
        GitTrail.resetForTest()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun logFile(): File = Files.createTempDirectory("git-trail").toFile().also { dirs += it }.resolve("sub/trail.log")

    @Test
    fun `the trail keeps only the newest events and renders them oldest first`() {
        repeat(GitTrail.MAX + 25) { GitTrail.note("event $it", nowMs = 1_000L + it) }
        val lines = GitTrail.render().lines()
        assertEquals(GitTrail.MAX, lines.size)
        assertTrue(lines.first().endsWith("event 25"))
        assertTrue(lines.last().endsWith("event ${GitTrail.MAX + 24}"))
    }

    @Test
    fun `a note never throws, whatever it is given`() {
        GitTrail.note("")
        GitTrail.note("multi\nline\u0000text")
        GitTrail.note("x".repeat(100_000))
        val text = GitTrail.render()
        assertTrue(text.contains("text"))
        assertTrue("a huge message is recorded, not rejected", text.contains("x".repeat(1000)))
    }

    @Test
    fun `the file holds the breadcrumbs and creates its folder`() {
        val f = logFile()
        GitTrail.install(f)
        GitTrail.note("card composed")
        GitTrail.note("scan start")
        GitTrail.write(crashText = null)
        val text = f.readText()
        assertTrue(text.contains("card composed"))
        assertTrue(text.contains("scan start"))
        assertFalse("no crash section when nothing crashed", text.contains("UNCAUGHT EXCEPTION"))
    }

    @Test
    fun `the background writer flushes a change without being asked`() {
        val f = logFile()
        GitTrail.install(f)
        GitTrail.note("something happened")
        val deadline = System.currentTimeMillis() + 5_000
        while (!f.isFile && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the trail must reach the file within a flush interval or two", f.isFile && f.readText().contains("something happened"))
    }

    @Test
    fun `a crash writes its stack under the breadcrumbs and still reaches the previous handler`() {
        val seen = ArrayList<Pair<String, Throwable>>()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> seen += t.name to e }
        val f = logFile()
        GitTrail.install(f)
        GitTrail.note("on screen=true studio=false")
        GitTrail.note("scan start")

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler)
        val boom = IllegalStateException("boom in compose")
        handler!!.uncaughtException(Thread.currentThread(), boom)

        val text = f.readText()
        assertTrue(text.contains("scan start"))
        assertTrue(text.contains("--- UNCAUGHT EXCEPTION ---"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("boom in compose"))
        assertTrue("the stack trace itself is there", text.contains("GitTrailTest"))
        // The app's behaviour is unchanged: the previous handler still receives the exception.
        assertEquals(1, seen.size)
        assertSame(boom, seen.single().second)
    }

    @Test
    fun `installing twice does not stack handlers`() {
        val seen = ArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
        GitTrail.install(logFile())
        GitTrail.install(logFile())
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("once"))
        assertEquals("the previous handler is called exactly once", 1, seen.size)
    }

    @Test
    fun `an unwritable location never turns a crash into a different crash`() {
        val seen = ArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
        GitTrail.install(File("/proc/this/cannot/be/written/trail.log"))
        GitTrail.note("x")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("real crash"))
        assertEquals(listOf("real crash"), seen.map { it.message })
    }
}
