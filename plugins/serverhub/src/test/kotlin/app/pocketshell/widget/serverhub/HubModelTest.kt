package app.pocketshell.widget.serverhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubModelTest {

    private fun ok(text: String) = (CommandLine.parse(text) as CommandLine.Parsed.Ok).argv
    private fun bad(text: String) = (CommandLine.parse(text) as CommandLine.Parsed.Bad).reason

    // ------------------------------------------------------------ command line

    @Test fun plainWordsAndQuotes() {
        assertEquals(listOf("python3", "-m", "http.server", "8000"), ok("python3 -m http.server 8000"))
        assertEquals(listOf("node", "my app.js", "--name=a b"), ok("node 'my app.js' \"--name=a b\""))
        assertEquals(listOf("echo", "a\"b", "c d"), ok("echo a\\\"b c\\ d"))
        assertEquals(listOf("sh", "-c", "a | b && c"), ok("sh -c 'a | b && c'"))
        assertEquals(listOf("x", ""), ok("x ''"))
        assertEquals(listOf("a", "b"), ok("  a \t b  "))
    }

    @Test fun shellOperatorsAreRefusedWithAHint() {
        for (cmd in listOf("a | b", "a && b", "a ; b", "a > out", "a < in", "a `b`", "a \$(b)")) assertTrue(cmd, bad(cmd).contains("sh -c"))
    }

    @Test fun variablesAreNotSilentlyLiteral() {
        assertTrue(bad("ls \$HOME").contains("not expanded"))
        assertTrue(bad("ls \"\$HOME/x\"").contains("not expanded"))
        assertTrue(bad("ls \${HOME}").contains("not expanded"))
        assertEquals(listOf("echo", "\$HOME"), ok("echo '\$HOME'"))
        assertEquals(listOf("echo", "cost \$5"), ok("echo 'cost \$5'"))
    }

    @Test fun otherBadCommands() {
        assertEquals("Write a command", bad(""))
        assertEquals("Write a command", bad("   "))
        assertTrue(bad("echo 'oops").contains("quote"))
        assertTrue(bad("echo \"oops").contains("quote"))
        assertTrue(bad("-rf /").contains("program"))
        assertTrue(bad("a\nb").contains("line break"))
        assertTrue(bad("x".repeat(601)).contains("too long"))
    }

    // ---------------------------------------------------------------- services

    private fun svc() = Service("abcd1234", "Docs", "python3 -m http.server 8000 --bind 127.0.0.1", "/root/site", 8000, CheckKind.HTTP, "/", RestartMode.ON_CRASH, false)

    @Test fun serviceRoundTripKeepsPipesInTheCommand() {
        val s = svc().copy(command = "sh -c 'a | b'")
        val back = Services.decode(Services.encode(listOf(s, svc().copy(id = "zzzz9999", name = "Two"))))
        assertEquals(2, back.size)
        assertEquals("sh -c 'a | b'", back[0].command)
    }

    @Test fun damagedLinesAreSkipped() {
        val text = "garbage\nabcd1234|n|/root|x|TCP|/|NEVER|false|cmd\n" + Services.encode(listOf(svc()))
        assertEquals(1, Services.decode(text).size)
    }

    @Test fun serviceValidation() {
        assertNull(Services.problem(svc()))
        assertNotNull(Services.problem(svc().copy(name = "a|b")))
        assertNotNull(Services.problem(svc().copy(name = "")))
        assertNotNull(Services.problem(svc().copy(command = "a | b")))
        assertNotNull(Services.problem(svc().copy(cwd = "relative/dir")))
        assertNotNull(Services.problem(svc().copy(cwd = "/root/../etc")))
        assertNotNull(Services.problem(svc().copy(port = 70000)))
        assertNotNull(Services.problem(svc().copy(port = 0)))
        assertNull(Services.problem(svc().copy(port = 0, check = CheckKind.NONE)))
        assertNotNull(Services.problem(svc().copy(path = "no-slash")))
        assertNotNull(Services.problem(svc().copy(port = 0, check = CheckKind.NONE, wifi = true)))
        assertNotNull(Services.problem(svc().copy(id = "BAD")))
    }

    // --------------------------------------------------------------- templates

    @Test fun templatesFillSafely() {
        val t = Templates.byId("static-python")!!
        assertEquals("python3 -m http.server 9000 --bind 127.0.0.1 --directory /root/my-site", Templates.fill(t, 9000, "/root/my-site"))
        assertNull("a space would add an argument", Templates.fill(t, 9000, "/root/my site"))
        assertNull(Templates.fill(t, 9000, "/root/x;rm"))
        assertNull(Templates.fill(t, 0, "/root/x"))
        assertNull(Templates.fill(t, 9000, "/root/../x"))
    }

    @Test fun everyTemplateProducesAValidService() {
        val ids = Templates.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (t in Templates.all.filter { it.id != "custom" }) {
            val cmd = Templates.fill(t, t.port, t.dir)!!
            val s = Service(Services.newId(), t.name, cmd, t.dir, t.port, t.check, t.path, RestartMode.NEVER, false)
            assertNull("${t.id}: ${Services.problem(s)}", Services.problem(s))
            assertTrue("${t.id} binds to loopback by default", cmd.contains("127.0.0.1") || t.id == "json-api")
            assertTrue("${t.id} needs something", t.needs.isNotEmpty())
        }
    }

    @Test fun jsonApiTemplateBindsToLoopback() {
        val api = Templates.byId("json-api")!!
        assertTrue(api.files.getValue("api.py").contains("('127.0.0.1', port)"))
    }

    // ------------------------------------------------------------------ health

    @Test fun healthVerdicts() {
        assertEquals(Health.UNKNOWN, Verdicts.classify(CheckKind.NONE, null, null, null, null).health)
        assertEquals(Health.DOWN, Verdicts.classify(CheckKind.TCP, null, null, null, null).health)
        assertEquals(Health.UP, Verdicts.classify(CheckKind.TCP, 4, null, null, null).health)
        assertEquals(Health.SLOW, Verdicts.classify(CheckKind.TCP, 4000, null, null, null).health)
        assertEquals(Health.ERROR, Verdicts.classify(CheckKind.HTTP, 4, null, null, "timed out").health)
        assertEquals(Health.ERROR, Verdicts.classify(CheckKind.HTTP, 4, 502, 30, null).health)
        assertEquals(Health.UP, Verdicts.classify(CheckKind.HTTP, 4, 404, 30, null).health)
        assertEquals(Health.SLOW, Verdicts.classify(CheckKind.HTTP, 4, 200, 2500, null).health)
    }

    // ----------------------------------------------------------------- restart

    @Test fun restartDecisions() {
        val now = 1_000_000L
        assertEquals(RestartPolicy.Decision.None, RestartPolicy.decide(RestartMode.NEVER, 1, false, emptyList(), now))
        assertEquals(RestartPolicy.Decision.None, RestartPolicy.decide(RestartMode.ON_CRASH, 0, false, emptyList(), now))
        assertEquals(RestartPolicy.Decision.None, RestartPolicy.decide(RestartMode.ON_CRASH, 137, true, emptyList(), now))
        assertEquals(RestartPolicy.Decision.Restart(1_000, 1), RestartPolicy.decide(RestartMode.ON_CRASH, 1, false, emptyList(), now))
        assertEquals(RestartPolicy.Decision.Restart(3_000, 2), RestartPolicy.decide(RestartMode.ON_CRASH, 1, false, listOf(now - 10_000), now))
        assertEquals(RestartPolicy.Decision.Restart(8_000, 3), RestartPolicy.decide(RestartMode.ON_CRASH, 1, false, listOf(now - 10_000, now - 5_000), now))
        assertTrue(RestartPolicy.decide(RestartMode.ON_CRASH, 1, false, listOf(now - 30_000, now - 20_000, now - 10_000), now) is RestartPolicy.Decision.GiveUp)
    }

    @Test fun oldRestartsAgeOutOfTheCrashLoopWindow() {
        val now = 10_000_000L
        val old = listOf(now - 6 * 60_000, now - 7 * 60_000, now - 8 * 60_000)
        assertEquals(RestartPolicy.Decision.Restart(1_000, 1), RestartPolicy.decide(RestartMode.ON_CRASH, 1, false, old, now))
    }

    // ------------------------------------------------------------------- links

    @Test fun links() {
        val s = svc()
        assertEquals("http://127.0.0.1:8000/", Links.local(s))
        assertEquals("http://192.168.1.5:8000/", Links.lan(s, "192.168.1.5"))
        assertNull(Links.lan(s, null))
        assertEquals("http://127.0.0.1:8001/health", Links.local(s.copy(port = 8001, path = "/health")))
        assertEquals("http://127.0.0.1:6379/", Links.local(s.copy(port = 6379, check = CheckKind.TCP)))
        assertNull(Links.local(s.copy(port = 0, check = CheckKind.NONE)))
        assertTrue(Links.isWeb(s)); assertFalse(Links.isWeb(s.copy(check = CheckKind.TCP)))
    }

    // -------------------------------------------------------------------- logs

    @Test fun logLines() {
        assertEquals(LogLines.Level.ERROR, LogLines.level("Traceback (most recent call last):"))
        assertEquals(LogLines.Level.ERROR, LogLines.level("connect failed: Connection refused"))
        assertEquals(LogLines.Level.WARN, LogLines.level("DeprecationWarning: x"))
        assertEquals(LogLines.Level.WARN, LogLines.level("WARN something"))
        assertEquals(LogLines.Level.INFO, LogLines.level("GET / 200"))
        assertEquals(listOf("b", "c"), LogLines.tail("a\n\nb\n\nc\n", 2))
        assertEquals(401, LogLines.tail("x".repeat(1000), 5, 400)[0].length)
        assertEquals("red text", LogLines.stripAnsi("\u001B[31mred\u001B[0m text"))
    }
}
