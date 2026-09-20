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
 * runner, so boundaries are pinned by reading what ships). M9 REDESIGN
 * edition: the card is now a navigation-stack application WITH staged
 * git operations, so the old read-only-everything pins are replaced by
 * the new layer discipline. Scope: the widget/git sources only.
 *
 * Pinned here:
 *   1. THEME INDEPENDENCE: only the shared PocketShell tokens across
 *      EVERY source file — no hardcoded colors, no theme-name literals.
 *   2. THE GUEST EXEC PATH: everything goes ONLY through the sanctioned
 *      non-PTY launch spec + background runner; no own-hand process
 *      reading, no direct spawning, in probe AND ops.
 *   3. THE SCAN STAYS READ-ONLY: the batched probe script and the probe's
 *      inspection argv carry exactly the read verbs; mutating verbs exist
 *      in exactly ONE source file — GitOps.kt — behind confirmations.
 *   4. DESTRUCTIVE OPERATIONS ARE CONFIRMED: the op queue consults the
 *      dialog map, dirty checkouts get their warning, and the UI can
 *      only fire ops through the queue.
 *   5. NAVIGATION: the application owns a stack in the process-scoped
 *      store; back pops the innermost screen; only the root's back
 *      leaves the card; actions ride the one WidgetNav seam.
 *   6. THE DATA LAYER STAYS PURE (no android deps in probe/parser/
 *      presentation/ops/model).
 *   7. ONE TAP = ONE EXEC for inspections, served caches gated by the
 *      data tick, results guarded by the navigation serial.
 *   8. TRUTHFUL STATE: an op's real failure is rendered; a successful
 *      mutation re-scans before more UI claims.
 */
class GitAppContractTest {

    // ------------------------------------------------------------ helpers

    private val sources = listOf(
        "GitApp.kt", "GitPlugin.kt", "GitProbe.kt", "GitStatusParser.kt",
        "GitPresentation.kt", "GitScreens.kt", "GitChrome.kt",
        "GitHomeScreen.kt", "GitRepoScreen.kt", "GitDetailScreens.kt",
        "GitOps.kt",
    )

    private fun gitSource(relative: String): String {
        // The sources live in THIS module now (M8.5): the unit-test working
        // directory is the module dir; the second form covers repo-root runs.
        val file = listOf(
            "src/main/kotlin/app/pocketshell/widget/git/$relative",
            "plugins/git/src/main/kotlin/app/pocketshell/widget/git/$relative",
        ).map { File(it) }.firstOrNull { it.isFile }
        assumeTrue("source not found on this runner: $relative", file != null)
        return file!!.readText()
    }

    /**
     * Remove triple-quoted raw strings (the probe's shell scripts, regex
     * literals) BEFORE any quote-based scanning — a raw string's interior
     * quotes would otherwise flip the scanner's parity mid-file and
     * mis-extract every literal after it.
     */
    private fun stripRawStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            if (source.startsWith("\"\"\"", i)) {
                val end = source.indexOf("\"\"\"", i + 3)
                i = if (end < 0) source.length else end + 3
                out.append(" RAW \"\" RAW ")
            } else {
                out.append(source[i])
                i++
            }
        }
        return out.toString()
    }

    /** Comments + string CONTENTS stripped — structural tokens only. */
    private fun stripCommentsAndStrings(rawSource: String): String {
        val source = stripRawStrings(rawSource)
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

    private fun stringLiterals(rawSource: String): List<String> {
        val source = stripRawStrings(rawSource)
        val literals = mutableListOf<String>()
        var i = 0
        while (i < source.length) {
            if (source[i] == '"') {
                val start = i + 1
                var j = start
                while (j < source.length && source[j] != '"') {
                    if (source[j] == '\\') j++
                    j++
                }
                literals += source.substring(start, j.coerceAtMost(source.length))
                i = j + 1
            } else {
                i++
            }
        }
        return literals
    }

    // -------------------------------------- 1. theme independence

    @Test
    fun `every source follows the shared theme - no private colors`() {
        sources.forEach { name ->
            val code = stripCommentsAndStrings(gitSource(name))
            val literals = stringLiterals(gitSource(name))
            assertFalse(
                "$name: no hardcoded colors — the selected theme is the only palette",
                Regex("""Color\(0x""").containsMatchIn(code),
            )
            assertFalse(
                "$name: no theme-name literals (Aurora is ONE theme, never a dependency)",
                literals.any { it.contains("Aurora", ignoreCase = true) },
            )
        }
        // The M9 GitApp object is pure state+effects; the UI files carry
        // the tokens — the chrome is the contract's token reader.
        val chrome = stripCommentsAndStrings(gitSource("GitChrome.kt"))
        assertTrue("the chrome reads the shared tokens", chrome.contains("HomeTokens."))
        assertTrue("the chrome reads the theme typeface", chrome.contains("TerminalTheme."))
        assertFalse(
            "no canvas-tone text pair (the card is a chrome-surface citizen)",
            chrome.contains("onHero"),
        )
    }

    // -------------------------------------- 2. the guest exec path

    @Test
    fun `everything goes only through the sanctioned non-PTY guest exec`() {
        val app = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "specs must come from the ONE launch builder",
            app.contains("RuntimeProcessLauncher.buildLaunchSpec"),
        )
        assertTrue(
            "the minimal device-proven profile is the mount configuration",
            app.contains("GuestExecutionProfile.PACKAGE_OPERATION"),
        )
        assertTrue(
            "execution rides the shared background guest runner",
            app.contains("ProcessBuilderGuestCommandRunner"),
        )
        sources.forEach { name ->
            val code = stripCommentsAndStrings(gitSource(name))
            val banned = listOf(
                "/proc", "cmdline", "Runtime.getRuntime", "java.lang.ProcessBuilder",
                "Runtime.exec", "libproot", "startActivity", "Intent(",
            )
            val found = banned.filter { code.contains(it) }
            assertTrue(
                "$name must stay on the guest exec path only; found: $found",
                found.isEmpty(),
            )
        }
    }

    // -------------------------------------- 3. read-only probe, one ops file

    /**
     * The probe scan script is a Kotlin RAW string (triple-quoted), which
     * the single-quote literal scanner cannot extract — locate it by its
     * markers instead. The listDir/readHead scripts are also raw strings.
     */
    private fun rawScriptLiteral(source: String, marker: String): String? {
        val start = source.indexOf(marker)
        if (start < 0) return null
        val bodyStart = start + marker.length
        val end = source.indexOf("\"\"\"", bodyStart)
        if (end < 0) return null
        return source.substring(bodyStart, end)
    }

    @Test
    fun `the scan script is strictly read-only git`() {
        val script = rawScriptLiteral(gitSource("GitProbe.kt"), "PROBE_SCRIPT = \"\"\"")
        assertNotNull("the scan script must ship in GitProbe.kt", script)
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
        val banned = listOf(
            " add", " rm ", "commit", "push", "pull", "merge", "rebase",
            "checkout", "reset", "stash", "restore", "clean", "clone", "init", "config", " mv ",
        )
        val found = banned.filter { script.contains(it) }
        assertTrue(
            "the background scan never mutates a repository; found: $found",
            found.isEmpty(),
        )
    }

    @Test
    fun `the probe's manual inspections stay read-only verbs`() {
        val literals = stringLiterals(gitSource("GitProbe.kt"))
        assertTrue(
            "the commit page asks for show --numstat with the header format",
            literals.contains("show") &&
                literals.contains("--numstat") &&
                literals.contains("--pretty=format:%H%n%an <%ae>%n%ad%n%ar%n%s%n%b%n@@NUMSTAT@@%n"),
        )
        assertTrue(
            "working diffs ask for git diff with an explicit -- separator",
            literals.contains("diff") && literals.contains("--") && literals.contains("--cached"),
        )
        assertTrue(
            "history pages page the log read-only",
            literals.contains("log") && literals.any { it.startsWith("--skip=") },
        )
        assertTrue(
            "the stash listing is the read-only list verb",
            literals.contains("stash") && literals.contains("list") && literals.contains("--format=%gd|%gs"),
        )
        assertTrue(
            "remote branch names are a read-only listing",
            literals.contains("branch") && literals.contains("-r") &&
                literals.any { it.contains("%(refname:short)") },
        )
        // The same ban list as the scan script: the probe layer never mutates.
        val bannedElements = setOf(
            "add", "restore", "reset", "commit", "push", "pull", "merge",
            "rebase", "checkout", "clean", "clone", "init", "config", "rm", "mv",
        )
        val found = literals.filter { it in bannedElements }
        assertTrue(
            "no state-changing git verb may appear as a probe argv element; found: $found",
            found.isEmpty(),
        )
    }

    @Test
    fun `mutating verbs live in exactly one source file - GitOps`() {
        // Every OTHER file must be free of mutating argv elements as literals.
        // ("clean" is absent from this list — it is the status summary word
        // in GitStatusParser, not an exec'd verb there; the SCAN script ban
        // below still covers it.)
        sources.filter { it != "GitOps.kt" }.forEach { name ->
            val literals = stringLiterals(gitSource(name))
            val bannedElements = setOf(
                "add", "restore", "reset", "commit", "push", "pull", "merge",
                "rebase", "checkout", "clone", "init", "rm", "mv",
            )
            val found = literals.filter { it in bannedElements }
            assertTrue(
                "$name must not exec mutating verbs directly; found: $found",
                found.isEmpty(),
            )
        }
        // GitOps carries them — as argv literals, each call direct with the
        // repo pinned by -C.
        val opsLiterals = stringLiterals(gitSource("GitOps.kt"))
        listOf("add", "restore", "reset", "commit", "checkout", "branch", "merge", "fetch", "pull", "push", "stash")
            .forEach { verb ->
                assertTrue("GitOps must own the $verb verb", opsLiterals.contains(verb))
            }
    }

    // -------------------------------------- 4. confirmed destructive ops

    @Test
    fun `destructive operations pass the confirmation gate`() {
        // The pure dialog map: risky verbs carry one, quiet ones do not.
        assertNotNull(opConfirmation(OpKind.DISCARD, "a.kt"))
        assertNotNull(opConfirmation(OpKind.DELETE_BRANCH, "dev"))
        assertNotNull(opConfirmation(OpKind.MERGE, "dev"))
        assertNotNull(opConfirmation(OpKind.PULL, null))
        assertNotNull(opConfirmation(OpKind.PUSH, null))
        assertNotNull(opConfirmation(OpKind.STASH_POP, "0"))
        assertNotNull(opConfirmation(OpKind.STASH_DROP, "0"))
        assertNullMarker(opConfirmation(OpKind.STAGE, "a.kt"))
        assertNullMarker(opConfirmation(OpKind.COMMIT, "msg"))
        assertNullMarker(opConfirmation(OpKind.FETCH, null))

        // The queue is the UI's only path to ops, and it consults the map.
        val queueCode = stripCommentsAndStrings(gitSource("GitChrome.kt"))
        assertTrue(
            "the op queue consults the confirmation map",
            queueCode.contains("opConfirmation(kind, arg)"),
        )
        assertTrue(
            "a dirty checkout appends its warning",
            queueCode.contains("state.repoDirty(repoPath)"),
        )
        assertTrue(
            "the queue's confirmation renders through GitConfirm",
            queueCode.contains("GitConfirm("),
        )
        // The diff screen's destructive verb rides the same queue.
        val diffCode = stripCommentsAndStrings(gitSource("GitDetailScreens.kt"))
        assertTrue(
            "DISCARD is offered only through the confirming queue",
            diffCode.contains("opQueue.run(state, repoPath, OpKind.DISCARD"),
        )
    }

    /** JUnit has no assertNull-with-message; keep the intent readable. */
    private fun assertNullMarker(result: Any?) {
        org.junit.Assert.assertNull(result)
    }

    // -------------------------------------- 5. in-card navigation

    @Test
    fun `the application owns a navigation stack in the process-scoped store`() {
        val app = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "probe + ops + stack must live in the process-scoped store (M8.4.2)",
            app.contains("stateStore.forApp"),
        )
        assertTrue(
            "back inside the card pops the innermost screen; only the root's " +
                "back leaves Home",
            app.contains("BackHandler(enabled = top != null) { state.pop() }"),
        )
        assertTrue("the stack is pushed as values", app.contains("fun push(screen: GitScreen)"))
        assertTrue("prev/next replaces the top of the stack", app.contains("fun replaceTop(screen: GitScreen)"))
        // Actions ride ONLY the one navigation seam (lambda or reference).
        assertTrue(app.contains("nav.openTerminal()") || app.contains("nav::openTerminal"))
        assertTrue(app.contains("nav.openLinuxShell()") || app.contains("nav::openLinuxShell"))
        assertTrue(app.contains("nav.openDiagnostics()") || app.contains("nav::openDiagnostics"))
    }

    @Test
    fun `the repository surface owns its five tabs - not one scroll`() {
        val modelCode = stripCommentsAndStrings(gitSource("GitScreens.kt"))
        assertEquals(
            "the five focused tabs",
            listOf("CHANGES", "HISTORY", "BRANCHES", "FILES", "REMOTES"),
            RepoTab.entries.map { it.name },
        )
        val screenCode = stripCommentsAndStrings(gitSource("GitRepoScreen.kt"))
        assertTrue("the tab row renders", screenCode.contains("GitTabRow("))
        assertTrue(
            "tab selection is remembered per repository",
            screenCode.contains("state.setRepoTab(repoPath,"),
        )
        assertTrue(
            "a vanished repository degrades home",
            screenCode.contains("if (repo == null) state.pop()"),
        )
    }

    // -------------------------------------- 6. the data layer stays pure

    @Test
    fun `the data layer has no android dependencies`() {
        listOf(
            "GitProbe.kt", "GitStatusParser.kt", "GitPresentation.kt",
            "GitOps.kt", "GitScreens.kt",
        ).forEach { name ->
            val code = stripCommentsAndStrings(gitSource(name))
            val banned = listOf("android.", "androidx", "Context", "Composable")
            val found = banned.filter { code.contains(it) }
            assertTrue("$name must stay JVM-pure; found: $found", found.isEmpty())
        }
        assertEquals(
            "the registry id must match the application's spec id",
            GitApp.GIT_ID,
            GitApp.spec.id,
        )
    }

    // ------------------- 7. one tap one exec + guards

    @Test
    fun `inspections are one tap one exec and guarded against stale results`() {
        val code = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "the commit exec rides the probe's manual show path",
            code.contains("state.probe.showCommit"),
        )
        assertTrue(
            "the diff exec rides the probe's manual diff paths",
            code.contains("state.probe.diffFile") && code.contains("state.probe.commitFileDiff"),
        )
        assertTrue(
            "the viewer rides the probe's preview path",
            code.contains("state.probe.readHead"),
        )
        assertTrue(
            "the stash screen rides the probe's list path",
            code.contains("state.probe.stashList"),
        )
        assertTrue(
            "results land only under the navigation serial guard",
            Regex("""state\.navSerial == gen""").containsMatchIn(code),
        )
        assertTrue(
            "the last-viewed caches gate re-exec on back-and-return",
            code.contains("state.commitServed") &&
                code.contains("state.diffServed") &&
                code.contains("state.viewerServed"),
        )
        assertTrue(
            "page caches are valid only within one data generation",
            code.contains("state.servedAtTick == state.dataTick"),
        )
    }

    @Test
    fun `the operation engine is one queued op with an honest banner and a re-scan`() {
        val code = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "a second op is refused while one is in flight",
            code.contains("if (opRunning != null) return"),
        )
        assertTrue(
            "the op runs on the shared exec through the ops layer",
            code.contains("executeOp(state.ops, req)"),
        )
        assertTrue(
            "a successful mutation drops the repo's stale tab caches",
            code.contains("state.onMutationApplied(req.repoPath)"),
        )
        assertTrue(
            "a successful mutation re-scans before anything else renders",
            Regex(
                """if \(result is GitOps\.OpResult\.Ok\) \{[\s\S]*?state\.probe\.snapshot\(\)""",
            ).containsMatchIn(code),
        )
        // The banner renders the real result — ok and failure alike — and
        // GitApp mounts it under every screen.
        val chrome = stripCommentsAndStrings(gitSource("GitChrome.kt"))
        assertTrue(
            "the banner renders failures with their real reason",
            chrome.contains("OpResult.Failed") &&
                stringLiterals(gitSource("GitChrome.kt")).any { it.startsWith("✗ ") } &&
                stringLiterals(gitSource("GitChrome.kt")).any { it.startsWith("✓ ") },
        )
        assertTrue(
            "the banner is mounted for every screen",
            code.contains("OpBanner("),
        )
    }

    // ------------------- 8. a11y + the cache gate

    @Test
    fun `state comes from the process-scoped store and refresh is a named icon`() {
        val homeLiterals = stringLiterals(gitSource("GitHomeScreen.kt"))
        assertTrue(
            "the refresh icon carries its accessibility name (passed to BOTH " +
                "contentDescription and onClickLabel)",
            homeLiterals.count { it == "Refresh repositories" } >= 1,
        )
        val homeCode = stripCommentsAndStrings(gitSource("GitHomeScreen.kt"))
        assertTrue(
            "the name reaches both the contentDescription and the onClickLabel",
            homeCode.contains("contentDescription = label") &&
                homeCode.contains("onClickLabel = label"),
        )
        assertTrue(
            "the filter icon is named too",
            homeLiterals.any { it.contains("Filter") },
        )
    }

    @Test
    fun `returning to the card renders the cache and gates any rescan`() {
        val code = stripCommentsAndStrings(gitSource("GitApp.kt"))
        assertTrue(
            "the resume edge must check for a cached Ready snapshot before scanning",
            code.contains("val hasCache = state.ui is GitUi.Ready"),
        )
        assertTrue(
            "the resume edge scan must be gated by the SAME staleness gate " +
                "as the tick loop (no guest exec on every re-entry)",
            Regex(
                """if \(!hasCache \|\| state\.probe\.shouldFullScan\(System\.currentTimeMillis\(\)\)\)""",
            ).containsMatchIn(code),
        )
    }
}
