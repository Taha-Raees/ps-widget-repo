package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * M8.4 — the SYNC/BACKUP application's structural contract, read from the
 * sources that ship (the established technique: the JVM suite has no
 * device runner, so boundaries are pinned by reading what ships).
 *
 * Pinned here:
 *   1. THEME: only the shared HomeTokens/TerminalTheme — no hardcoded
 *      colors, no theme-name literals.
 *   2. IN-CARD NAVIGATION + STATE (M8.4.2): the card's state — screen
 *      ui, navigation, ticks, probe (its idle-gate memory IS the cache)
 *      and the form draft — lives in the process-scoped holder reached
 *      through stateStore.forApp; the card owns its BackHandler; actions
 *      through the WidgetNav seam only.
 *   3. EXEC DISCIPLINE: the sanctioned guest-exec path only; user specs
 *      ride as argv/positional parameters — never spliced into a shell
 *      string; dry-run flags present; no PTY session spawn.
 *   4. SECRET SAFETY: no password/key literals anywhere in the package;
 *      the persisted model has no credential field.
 *   5. HONESTY: the UI vocabulary never claims user data is backed up,
 *      protected or verified — a profile is a record, not a backup; the
 *      real run is RUN NOW and it is additive-only ("never deletes");
 *      the stale "the card previews; it never copies" claim is gone.
 *   6. UX DISCIPLINE: compact icon actions announce themselves; form
 *      validation waits for the first SAVE attempt (no premature
 *      validation on a fresh form).
 */
class SyncAppContractTest {

    // ------------------------------------------------------------ helpers

    // The sources live in THIS module now (M8.5): the unit-test working
    // directory is the module dir; the fallback covers repo-root runs.
    private fun syncDir(): File =
        File("src/main/kotlin/app/pocketshell/widget/sync")
            .takeIf { it.isDirectory }
            ?: File("plugins/sync/src/main/kotlin/app/pocketshell/widget/sync")

    private fun syncSources(): List<Pair<String, String>> {
        val dir = syncDir()
        assumeTrue("sync source dir not found on this runner", dir.isDirectory)
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.name to it.readText() }
            .toList()
    }

    private fun source(name: String): String =
        syncSources().firstOrNull { it.first == name }
            ?.second
            ?: error("source not found on this runner: $name")

    /** Comments + string CONTENTS stripped — structural tokens only. */
    private fun stripCommentsAndStrings(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '/' && i + 1 < code.length && code[i + 1] == '*' -> {
                    i = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                }
                c == '/' && i + 1 < code.length && code[i + 1] == '/' -> {
                    while (i < code.length && code[i] != '\n') i++
                }
                c == '"' -> {
                    i++
                    while (i < code.length && code[i] != '"') {
                        if (code[i] == '\\') i++
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

    private fun stringLiterals(code: String): List<String> {
        val literals = mutableListOf<String>()
        var i = 0
        while (i < code.length) {
            if (code[i] == '"') {
                val start = i + 1
                var j = start
                while (j < code.length && code[j] != '"') {
                    if (code[j] == '\\') j++
                    j++
                }
                literals += code.substring(start, j.coerceAtMost(code.length))
                i = j + 1
            } else {
                i++
            }
        }
        return literals
    }

    // ------------------------------------------------------- 1. theme only

    @Test
    fun `the Sync application follows the shared theme - no private palette`() {
        val code = stripCommentsAndStrings(source("SyncApp.kt"))
        assertTrue("must read the shared tokens", code.contains("HomeTokens."))
        assertTrue("must read the theme typeface", code.contains("TerminalTheme."))
        assertFalse(
            "no hardcoded colors — the selected theme is the only palette",
            Regex("""Color\(0x""").containsMatchIn(code),
        )
        assertFalse(
            "no theme-name literals (a theme is ONE option, never a dependency)",
            stringLiterals(source("SyncApp.kt")).any { it.contains("Aurora", ignoreCase = true) },
        )
    }

    // ------------------------------------------------- 2. in-card navigation

    @Test
    fun `card state lives in the process-scoped holder and the card owns its back`() {
        val code = stripCommentsAndStrings(source("SyncApp.kt"))
        assertTrue(
            "state comes from the M8.4.2 store, not per-composition remember",
            code.contains("stateStore.forApp"),
        )
        assertTrue(
            "the holder owns the probe — its idle-gate memory IS the cache",
            code.contains("class SyncState("),
        )
        assertTrue(
            "transient arming state stays saveable (rotation, round-trips)",
            code.contains("rememberSaveable"),
        )
        assertTrue(
            "back inside the card returns to the overview before leaving Home",
            Regex("""BackHandler\(enabled = """).containsMatchIn(code),
        )
        assertTrue("the terminal is where real runs happen", code.contains("nav.openTerminal()"))
        assertTrue("the guest is where sync tools live", code.contains("nav.openLinuxShell()"))
    }

    @Test
    fun `the form validates only after the first save attempt`() {
        val code = stripCommentsAndStrings(source("SyncApp.kt"))
        assertTrue(
            "the form tracks a submitted flag in the holder",
            code.contains("formSubmitted"),
        )
        assertTrue(
            "the validation message renders only when the submitted flag is set",
            code.contains("formSubmitted && problem != null"),
        )
        assertFalse(
            "SAVE stays tappable while invalid — the tap surfaces the error",
            code.contains("enabled = problem == null"),
        )
    }

    @Test
    fun `no source still claims the card never copies`() {
        syncSources().forEach { (name, text) ->
            assertFalse(
                "$name carries the stale pre-RUN-NOW claim — RUN NOW copies (additively)",
                text.lowercase().contains("never copies"),
            )
        }
    }

    @Test
    fun `primary actions are compact icon controls that announce themselves`() {
        val app = source("SyncApp.kt")
        assertTrue(
            "the overview's new-profile affordance is the + icon",
            app.contains("contentDescription = \"New profile\""),
        )
        assertTrue(
            "RUN NOW's play icon announces itself",
            app.contains("contentDescription = \"Run profile now\""),
        )
        assertTrue(
            "the dry-run preview control names itself for the reader",
            app.contains("\"Preview dry run\""),
        )
        assertFalse(
            "the old word button is gone",
            app.contains("+ NEW PROFILE"),
        )
    }

    // ---------------------------------------------------- 3. exec discipline

    @Test
    fun `the only exec path is the sanctioned non-PTY guest runner`() {
        val all = syncSources().joinToString("\n") { it.second }
        val code = stripCommentsAndStrings(all)
        assertTrue(
            "guest commands go through the sanctioned launcher",
            code.contains("RuntimeProcessLauncher.buildLaunchSpec"),
        )
        assertTrue(
            "guest commands run through the background guest runner",
            code.contains("ProcessBuilderGuestCommandRunner"),
        )
        val banned = listOf(
            "Runtime.getRuntime", "libproot", "su ", "/dev/ptmx", "openpty", "ptySession",
        )
        val found = banned.filter { code.contains(it) }
        assertTrue("no second exec surface; found: $found", found.isEmpty())
    }

    @Test
    fun `user specs ride as argv - never spliced into a shell string`() {
        // These pins read the RAW source: the string-literal contents are
        // the point here (the stripper would blank them).
        val raw = source("SyncProbe.kt")
        // The script is a fixed constant passed with an argv placeholder arg.
        assertTrue(raw.contains("\"/bin/sh\""))
        assertTrue(raw.contains("\"-c\""))
        assertTrue(raw.contains("PROBE_SCRIPT"))
        // Positional parameters in the raw template (${D}1 / ${D}2 in source).
        assertTrue("specs must arrive as \$1/\$2 positional parameters", raw.contains("{D}1"))
        assertTrue(raw.contains("{D}2"))
        // No Kotlin string-building of shell command lines from user data:
        // no quote-adjacent + concatenation anywhere in the package.
        syncSources().forEach { (name, text) ->
            val violations = Regex("""["']\s*\+\s*\w+\s*\+""").findAll(text).toList()
            assertTrue(
                "$name must not build shell strings by concatenation; found: " +
                    violations.map { it.value },
                violations.isEmpty(),
            )
        }
    }

    @Test
    fun `the run is additive-only - the destructive modes are unreachable`() {
        val raw = source("SyncProbe.kt")
        // The dry-run preview flags are unchanged.
        assertTrue("rsync previews use -n", raw.contains("\"-n\""))
        assertTrue("rsync previews itemize", raw.contains("\"--itemize-changes\""))
        assertTrue("rclone previews dry-run", raw.contains("\"--dry-run\""))
        assertTrue("rclone previews report to stdout", raw.contains("\"--combined\""))
        // The RUN path (RUN NOW) is additive-only by construction:
        // rsync archives with stats and gets no delete flag; rclone runs
        // "copy", never "sync" (which deletes extraneous files).
        assertTrue("rsync run reports stats1", raw.contains("\"--info=stats1\""))
        assertFalse(
            "--delete must never appear anywhere in the probe source",
            raw.contains("--delete"),
        )
        // And the UI names the actions honestly.
        val app = source("SyncApp.kt")
        assertTrue(app.contains("DRY RUN"))
        assertTrue("a RUN NOW control exists", app.contains("\"RUN NOW\""))
        assertTrue(app.contains("never deletes"))
    }

    // ------------------------------------------------------ 4. secret safety

    @Test
    fun `no source carries password or key literals`() {
        syncSources().forEach { (name, text) ->
            val banned = listOf(
                "password", "passwd", "secret", "api_key", "apikey",
                "token", "private_key", "id_rsa", "id_ed25519", "id_ecdsa",
            )
            val found = stringLiterals(text)
                .flatMap { literal -> banned.filter { literal.lowercase().contains(it) } }
            assertTrue("$name must never carry credential literals; found: $found", found.isEmpty())
        }
    }

    @Test
    fun `the persisted model has no credential field to fill`() {
        val model = stripCommentsAndStrings(source("SyncProfile.kt"))
        assertTrue(model.contains("val source"))
        assertTrue(model.contains("val destination"))
        val banned = listOf("password", "secret", "keyPath", "credential", "token")
        val found = banned.filter { model.lowercase().contains(it) }
        assertTrue("the profile model must have no credential surface; found: $found", found.isEmpty())
    }

    // ------------------------------------------------------------ 5. honesty

    @Test
    fun `the UI never claims user data is backed up or safe`() {
        val banned = listOf(
            "backed up", "backup complete", "is safe", "protected",
            "verified backup", "backup succeeded",
        )
        syncSources().forEach { (name, text) ->
            val literals = stringLiterals(text).map { it.lowercase() }
            val found = literals.flatMap { literal -> banned.filter { literal.contains(it) } }
            assertTrue("$name invented a backup claim: $found", found.isEmpty())
        }
    }

    @Test
    fun `the run fields default to never-run and the model documents why`() {
        val p = SyncProfile("id", SyncBackend.RSYNC, "/a", "/b", createdAtMs = 1L)
        assertNull(p.lastRunMs)
        assertNull(p.lastRunSummary)
        assertNull(p.lastResult)
        assertNull(p.lastExit)
        assertNull(p.lastStats)
    }

    @Test
    fun `every finished run is recorded and verify is a holder-only check`() {
        val app = source("SyncApp.kt")
        assertTrue(
            "the record decision is the one pure function, fed the real exit",
            app.contains("runRecord(profile, result, SyncRepository.now())"),
        )
        assertTrue(
            "VERIFY is a named, announced control",
            app.contains("contentDescription = \"Verify destination\""),
        )
        val model = source("SyncProfile.kt")
        assertTrue("the record carries the result word", model.contains("val lastResult"))
        assertTrue("the record carries the real exit code", model.contains("val lastExit"))
        assertTrue("the record carries the parsed stats", model.contains("val lastStats"))
        assertFalse(
            "the store model has no verify surface — a check is not a run",
            model.contains("verify"),
        )
    }

    @Test
    fun `the empty state is actionable and states the additive contract`() {
        val app = source("SyncApp.kt")
        assertTrue("the empty state names the fact at title weight", app.contains("No backup profiles"))
        assertTrue(
            "the one supporting line says what RUN NOW does",
            app.contains("RUN NOW copies new and updated files"),
        )
        assertTrue(app.contains("never deletes"))
    }

    // -------------------------------------------------------------- spec

    @Test
    fun `the spec carries the registry identity`() {
        assertEquals(SyncApp.SYNC_ID, SyncApp.spec.id)
        assertEquals("sync", SyncApp.spec.id)
        assertEquals("Sync", SyncApp.spec.name)
        assertTrue(SyncApp.spec.summary.isNotBlank())
    }
}
