package app.pocketshell.widget.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The GIT application's structural contract (source-reading pins in the
 * established HomeWidgetContractTest style — the JVM suite has no device
 * runner, so boundaries are pinned by reading what ships). Scope: the
 * widget/git sources only.
 *
 * Pinned here:
 *   1. THEME INDEPENDENCE: every UI file reads the shared tokens — no
 *      hardcoded colors, no theme-name literals, no canvas-tone text pair.
 *   2. THE GUEST EXEC PATH: the exec closure lives ONLY in GitApp and
 *      rides the sanctioned non-PTY launch spec + background runner; no
 *      own-hand process-table reading, no direct process spawning.
 *   3. THE READ-ONLY PROBE: the dashboard script carries exactly the read
 *      commands, bounded in the script itself.
 *   4. THE MUTATION SEAM: write verbs exist as exec'd argv ONLY in
 *      GitOps.kt; the UI cannot exec, and every op runs through the
 *      runner (re-validated) followed by a forced rescan + read bump.
 *   5. IN-CARD NAVIGATION: the stack lives in the process-scoped store;
 *      back pops the innermost screen; actions ride the one WidgetNav seam.
 *   6. PURITY: the probe/parser/rules layer has no android dependencies.
 *   7. STATE OWNERSHIP: stateStore.forApp; the resume edge is cache-first
 *      and gate-governed; refresh is a named icon; reads are gen-guarded.
 */
class GitAppContractTest {

    // ------------------------------------------------------------ helpers

    private fun gitSource(relative: String): String {
        val file = listOf(
            "src/main/kotlin/app/pocketshell/widget/git/$relative",
            "plugins/git/src/main/kotlin/app/pocketshell/widget/git/$relative",
        ).map { File(it) }.firstOrNull { it.isFile }
        assumeTrue("source not found on this runner: $relative", file != null)
        return file!!.readText()
    }

    private fun uiSources(): List<Pair<String, String>> =
        listOf("GitApp.kt", "GitComponents.kt", "GitScreens.kt", "GitDetails.kt", "GitHostUi.kt")
            .map { it to gitSource(it) }

    private fun ruleSources(): List<String> =
        listOf("GitProbe.kt", "GitStatusParser.kt", "GitPresentation.kt", "GitReads.kt",
            "GitOps.kt", "GitFiles.kt", "GitDiffParser.kt", "GitQuoted.kt", "GitLayout.kt",
            "GitHost.kt")

    /** Comments + string CONTENTS stripped — structural tokens only. */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '"' -> {
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    i++
                    out.append("\"\"")
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun stringLiterals(source: String): List<String> {
        val literals = mutableListOf<String>()
        var i = 0
        while (i < source.length) {
            when {
                source[i] == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                }
                source[i] == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                source[i] == '"' -> {
                    val start = i
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    i++
                    literals += source.substring(start + 1, i - 1)
                }
                else -> i++
            }
        }
        return literals
    }

    // -------------------------------------- 1. theme independence

    @Test
    fun `every UI file follows the shared theme - no private theme`() {
        // Tokens are read COLLECTIVELY (GitApp is the wiring object; the
        // composables carry the pixels) — the banned things are per-file.
        val allCode = uiSources().joinToString("\n") { stripCommentsAndStrings(it.second) }
        assertTrue("the UI must read the shared tokens", allCode.contains("HomeTokens."))
        assertTrue("the UI must read the theme typeface", allCode.contains("TerminalTheme."))
        uiSources().forEach { (name, source) ->
            val code = stripCommentsAndStrings(source)
            assertFalse(
                "$name: no canvas-tone text pair (the card is a chrome-surface citizen)",
                code.contains("onHero"),
            )
            assertFalse(
                "$name: no hardcoded colors — the selected theme is the only palette",
                Regex("""Color\(0x""").containsMatchIn(code),
            )
            assertFalse(
                "$name: no theme-name literals (Aurora is ONE theme, never a dependency)",
                stringLiterals(source).any { it.contains("Aurora", ignoreCase = true) },
            )
        }
        (ruleSources() + "GitPlugin.kt").forEach { name ->
            assertFalse(
                "$name: no hardcoded colors in the rules layer either",
                Regex("""Color\(0x""").containsMatchIn(stripCommentsAndStrings(gitSource(name))),
            )
        }
    }

    // -------------------------------------- 2. the guest exec path

    @Test
    fun `the exec closure lives only in GitApp on the sanctioned guest path`() {
        val app = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "specs must come from the ONE launch builder",
            app.contains("RuntimeProcessLauncher.buildLaunchSpec"),
        )
        assertTrue(
            "the minimal device-proven profile is the exec's mount configuration",
            app.contains("GuestExecutionProfile.PACKAGE_OPERATION"),
        )
        assertTrue(
            "execution rides the shared background guest runner",
            app.contains("ProcessBuilderGuestCommandRunner"),
        )
        // No other file in the module builds a launch spec or spawns anything.
        (ruleSources() + listOf("GitScreens.kt", "GitDetails.kt", "GitComponents.kt")).forEach { name ->
            val code = stripCommentsAndStrings(gitSource(name))
            val banned = listOf(
                "/proc", "cmdline", "Runtime.getRuntime", "java.lang.ProcessBuilder",
                "Runtime.exec", "libproot", "buildLaunchSpec", "ProcessBuilderGuestCommandRunner",
            )
            val found = banned.filter { code.contains(it) }
            assertTrue("$name must stay off the exec path; found: $found", found.isEmpty())
        }
    }

    // -------------------------------------- 3. the read-only probe

    /**
     * The probe script is a Kotlin RAW string, which the literal scanner
     * cannot extract — locate it by its markers instead.
     */
    private fun probeScriptLiteral(): String? {
        val source = gitSource("GitProbe.kt")
        val startMarker = "PROBE_SCRIPT = \"\"\""
        val start = source.indexOf(startMarker)
        if (start < 0) return null
        val bodyStart = start + startMarker.length
        val end = source.indexOf("\"\"\"", bodyStart)
        if (end < 0) return null
        return source.substring(bodyStart, end)
    }

    @Test
    fun `the probe script is strictly read-only git`() {
        val script = probeScriptLiteral()
        assertNotNull("the probe script must ship in GitProbe.kt", script)
        assertTrue("binary presence check", script!!.contains("command -v git"))
        assertTrue("version probe", script.contains("git --version"))
        assertTrue(
            "discovery is a shallow find for real checkouts",
            script.contains("*/.git/HEAD") && script.contains("-maxdepth"),
        )
        assertTrue(
            "status is the stable porcelain form (asked per discovered repo)",
            script.contains("status --porcelain=v1 -b"),
        )
        assertTrue("recent history is a bounded log", script.contains("log -5"))
        assertTrue("branches are listed read-only", script.contains("branch --format"))
        assertTrue("remotes are listed read-only", script.contains("remote -v"))
        // exactly two head-capped listings: branches (24) and remotes (20)
        assertTrue(
            "branch and remote listings are head-capped in the script",
            Regex("head -n \\d+").findAll(script).count() == 2,
        )
        val banned = listOf(
            " add", " rm ", "commit", "push", "pull", "merge", "rebase",
            "checkout", "reset", "stash", "clean", "clone", "init", "config", " mv ",
        )
        val found = banned.filter { script.contains(it) }
        assertTrue("the dashboard probe never mutates a repository; found: $found", found.isEmpty())
    }

    // -------------------------------------- 4. the mutation seam

    @Test
    fun `write verbs are exec'd only through GitOps argv lists`() {
        // GitOps.kt must name its verbs as argv elements...
        val opsLiterals = stringLiterals(gitSource("GitOps.kt"))
        listOf("add", "commit", "merge", "rebase", "reset", "revert", "cherry-pick", "tag", "stash")
            .forEach { verb ->
                assertTrue("GitOps.kt must define \"$verb\"", verb in opsLiterals)
            }
        // ...and the READ layers must never exec one.
        listOf("GitProbe.kt", "GitReads.kt").forEach { name ->
            val literals = stringLiterals(gitSource(name))
            val bannedVerbs = setOf(
                "add", "commit", "push", "pull", "merge", "rebase", "checkout",
                "reset", "clean", "clone", "init", "apply", "rm", "mv", "tag", "pop", "drop",
            )
            val found = literals.filter { it in bannedVerbs }
            assertTrue("$name execs read verbs only; found: $found", found.isEmpty())
        }
        // The UI cannot exec anything: no argv lists, no exec calls.
        listOf("GitScreens.kt", "GitDetails.kt", "GitComponents.kt").forEach { name ->
            val code = stripCommentsAndStrings(gitSource(name))
            assertFalse("$name must not build argv lists", code.contains("listOf(\"git\""))
            assertFalse("$name must not call exec", Regex("""\.exec\(""").containsMatchIn(code))
        }
    }

    @Test
    fun `every mutation is followed by a forced rescan and a read bump`() {
        val app = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue("the op rides the runner", app.contains("state.ops.run("))
        assertTrue(
            "the probe is invalidated after every op",
            app.contains("state.probe.invalidate()"),
        )
        assertTrue(
            "the read epoch bumps after every op - deeper screens re-read",
            app.contains("state.readEpoch++"),
        )
        assertTrue(
            "the rescan follows the op in the same effect",
            Regex(
                """state\.ui = scanToUi\(withContext\(Dispatchers\.IO\) \{ state\.probe\.snapshot\(\) \}\)""",
            ).containsMatchIn(app),
        )
    }

    // -------------------------------------- 5. in-card navigation

    @Test
    fun `the application owns its stack and its own back`() {
        val app = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "state must live in the process-scoped store",
            app.contains("stateStore.forApp"),
        )
        assertTrue(
            "back pops the innermost screen; only an empty stack reaches Home",
            app.contains("BackHandler(enabled = state.stack.isNotEmpty())"),
        )
        assertTrue(app.contains("nav.openTerminal()"))
        assertTrue(app.contains("nav.openLinuxShell()"))
        assertTrue(app.contains("nav.openDiagnostics()"))
        val banned = listOf("startActivity", "Intent(")
        val found = banned.filter { app.contains(it) }
        assertTrue("no second navigation mechanism; found: $found", found.isEmpty())
    }

    // -------------------------------------- 6. the rules layer stays pure

    @Test
    fun `the probe parser and rules layers have no android dependencies`() {
        ruleSources().forEach { name ->
            val code = stripCommentsAndStrings(gitSource(name))
            val banned = listOf("android.", "androidx")
            val found = banned.filter { code.contains(it) }
            assertTrue("$name must stay JVM-pure; found: $found", found.isEmpty())
        }
        assertEquals(
            "the registry id must match the application's spec id",
            GitApp.GIT_ID,
            GitApp.spec.id,
        )
    }

    // -------------------- 7. state ownership + the named refresh

    @Test
    fun `state comes from the process-scoped store and refresh is a named icon`() {
        val app = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "the probe and screen state must live in the shared store",
            app.contains("stateStore.forApp"),
        )
        val literals = stringLiterals(gitSource("GitComponents.kt"))
        assertTrue(
            "the refresh icon needs its accessibility name for BOTH " +
                "contentDescription and onClickLabel",
            literals.count { it == "Refresh repositories" } >= 2,
        )
    }

    @Test
    fun `returning to the card renders the cache and gates any rescan`() {
        val app = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "the resume edge must check for a cached Ready snapshot before scanning",
            app.contains("val hasCache = state.ui is GitUi.Ready"),
        )
        assertTrue(
            "the resume edge scan must be gated by the SAME staleness gate " +
                "as the tick loop (no guest exec on every re-entry)",
            Regex(
                """if \(!hasCache \|\| state\.probe\.shouldFullScan\(System\.currentTimeMillis\(\)\)\)""",
            ).containsMatchIn(app),
        )
    }

    @Test
    fun `reads are serial-guarded - a superseded exec can never land`() {
        val components = stripCommentsAndStrings(gitSource("GitComponents.kt"))
        assertTrue(
            "the read effect captures the slot generation before its exec",
            components.contains("val gen = slot.gen"),
        )
        assertTrue(
            "the result lands only while its generation is current",
            components.contains("if (slot.gen == gen)"),
        )
    }
}
