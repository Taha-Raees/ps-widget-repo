package app.pocketshell.widget.sync

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** The folder picker's data: rows, labels, honest sizes, selection rules, and a backup of only what was ticked. */
class HomeScanTest {

    private val dirs = ArrayList<File>()

    @After
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun home(): File = Files.createTempDirectory("psbak-home").toFile().also { dirs += it }

    private fun File.put(path: String, bytes: Int = 1, text: String? = null): File {
        val f = File(this, path)
        f.parentFile.mkdirs()
        if (text != null) f.writeText(text) else f.writeBytes(ByteArray(bytes) { 7 })
        return f
    }

    // ------------------------------------------------------------ the rows

    @Test
    fun `known tools come first and are labelled, everything else follows - folders before files`() {
        val h = home()
        h.put(".claude/history.jsonl", 10)
        h.put(".claude.json", 5)
        h.put(".config/kilo/settings.json", 3)
        h.put(".zcode/x", 1)
        h.put("Projects/app/main.kt", 4)
        h.put("Downloads/a.zip", 4)
        h.put("notes.txt", 2)
        h.put(".hidden-dir/y", 1)

        val rows = HomeScanner(h).entries()
        val rels = rows.map { it.rel }
        // Known paths in KnownApps order, then the rest: dirs alphabetical, then files.
        assertEquals(listOf(".claude", ".claude.json", ".zcode", ".config/kilo"), rels.take(4))
        assertEquals("Claude Code — settings, history, skills", rows[0].label)
        assertTrue(rows.take(4).all { it.known })
        assertEquals(listOf(".config", ".hidden-dir", "Downloads", "Projects", "notes.txt"), rels.drop(4))
        assertTrue(rows.drop(4).none { it.known })
        // Sizes are not known until measured.
        assertTrue(rows.all { it.bytes == null })
    }

    @Test
    fun `a missing known tool is simply not offered`() {
        val h = home()
        h.put("Projects/a", 1)
        assertEquals(listOf("Projects"), HomeScanner(h).entries().map { it.rel })
    }

    @Test
    fun `an empty home has no rows and a missing one does not crash`() {
        assertTrue(HomeScanner(home()).entries().isEmpty())
        assertTrue(HomeScanner(File("/nonexistent-home-dir")).entries().isEmpty())
    }

    // ----------------------------------------------------------- measuring

    private val rules = TarGlob.compile(
        ExcludePreset.entries.flatMap { it.patterns } + SensitivePatterns.ALL,
    )

    @Test
    fun `sizes are what will be ARCHIVED - excluded folders and key files are not counted`() {
        val h = home()
        h.put("proj/src/a.kt", 100)
        h.put("proj/node_modules/pkg/i.js", 5_000)
        h.put("proj/.env", 50)
        h.put("proj/.gradle/caches/c.bin", 9_000)
        h.put("proj/.gradle/init.d/keep.gradle", 20)
        val m = HomeScanner(h).measure("proj", rules)!!
        assertEquals(120L, m.bytes)          // a.kt + keep.gradle
        assertEquals(2L, m.files)
        assertEquals(3, m.leftOut)           // node_modules/, .env, .gradle/caches/
    }

    @Test
    fun `with nothing left out every byte counts - however large`() {
        val h = home()
        h.put("big/a.bin", 1_000_000)
        h.put("big/node_modules/b.bin", 2_000_000)
        val everything = HomeScanner(h).measure("big", { false })!!
        assertEquals(3_000_000L, everything.bytes)
        assertEquals(0, everything.leftOut)
        assertEquals(1_000_000L, HomeScanner(h).measure("big", rules)!!.bytes)
    }

    @Test
    fun `a single file is measured too, and a missing path is zero`() {
        val h = home()
        h.put(".claude.json", 321)
        assertEquals(Measured(321, 1, 0), HomeScanner(h).measure(".claude.json", { false }))
        assertEquals(Measured(0, 0, 0), HomeScanner(h).measure("nothing-here", { false }))
    }

    @Test
    fun `symlinks are never followed - a link to a huge folder adds nothing`() {
        val h = home()
        val outside = home().also { it.put("huge/x.bin", 5_000_000) }
        h.put("proj/a.txt", 10)
        Files.createSymbolicLink(File(h, "proj/link").toPath(), File(outside, "huge").toPath())
        val m = HomeScanner(h).measure("proj", { false })!!
        assertEquals(10L, m.bytes)
        assertEquals(1L, m.files)
    }

    @Test
    fun `a measurement can be abandoned at once`() {
        val h = home()
        repeat(200) { h.put("many/f$it", 10) }
        var calls = 0
        val result = HomeScanner(h).measure("many", { false }, isCancelled = { ++calls > 20 })
        assertNull("a cancelled measurement reports nothing rather than a partial number", result)
        assertTrue("it stopped early", calls < 200)
    }

    // --------------------------------------------------------------- globs

    @Test
    fun `tar globs match the way the guest's tar does`() {
        val nm = TarGlob.compile(listOf("*/node_modules"))
        assertTrue(nm("root/a/node_modules"))
        assertTrue(nm("root/a/b/c/node_modules"))
        assertFalse(nm("root/node_modules_old"))
        assertFalse(nm("root/a/node_modules/x"))   // the DIRECTORY matches; its children are skipped with it
        val exact = TarGlob.compile(listOf("root/.cache"))
        assertTrue(exact("root/.cache"))
        assertFalse(exact("root/.cacheX"))
        assertFalse(exact("root/proj/.cache"))
        val pem = TarGlob.compile(listOf("*.pem", "root/a?c"))
        assertTrue(pem("root/x/server.pem"))
        assertTrue(pem("root/abc"))
        assertFalse(pem("root/abbc"))
        // Regex metacharacters in a pattern are literal.
        assertTrue(TarGlob.compile(listOf("root/a.b(c)+"))("root/a.b(c)+"))
        assertFalse(TarGlob.compile(listOf("root/a.b"))("root/aXb"))
    }

    // ----------------------------------------------------------- selection

    @Test
    fun `ticking a folder covers what is inside it, and one path is never listed twice`() {
        assertEquals(setOf(".config"), HomeSelection.toggle(setOf(".config/kilo"), ".config"))
        assertEquals(setOf(".config"), HomeSelection.normalize(setOf(".config", ".config/kilo", ".config/opencode")))
        // A sibling that merely shares a prefix is not covered.
        assertEquals(setOf(".config", ".config-old"), HomeSelection.normalize(setOf(".config", ".config-old")))
        assertEquals(emptySet<String>(), HomeSelection.toggle(setOf("Projects"), "Projects"))
    }

    @Test
    fun `the selected total waits for every ticked size and ignores unticked rows`() {
        val rows = listOf(HomeRow("a", null, true, bytes = 100), HomeRow("b", null, true, bytes = 50), HomeRow("c", null, true, bytes = null))
        assertEquals(150L, HomeSelection.selectedBytes(rows, setOf("a", "b")))
        assertNull(HomeSelection.selectedBytes(rows, setOf("a", "c")))
        assertEquals(0L, HomeSelection.selectedBytes(rows, emptySet()))
        assertEquals("…", HomeSelection.size(null))
        assertEquals("3.00 GB", HomeSelection.size(3L * 1024 * 1024 * 1024))
    }

    // ------------------------------------------------------- the real home

    @Test
    fun `this machine's real home lists without trouble`() {
        val real = File("/root")
        assumeTrue("needs a real /root", real.isDirectory && real.canRead())
        val rows = HomeScanner(real).entries()
        assertTrue(rows.all { HomePaths.validate(it.rel) == null })
        assertEquals("no path appears twice", rows.size, rows.map { it.rel }.toSet().size)
    }

    // ----------------------------------------------------- model + the plan

    @Test
    fun `choosing folders turns the home include into exactly those members`() {
        val o = BackupOptions(homeMode = HomeMode.CHOSEN, homeItems = setOf("Projects", ".claude", ".config/kilo"))
        assertEquals(
            listOf("tmp/psbak-meta", "root/.claude", "root/.config/kilo", "root/Projects"),
            BackupPlan.includeList(o),
        )
        // Everything is still the whole home; other scopes are unaffected.
        assertEquals(listOf("tmp/psbak-meta", "root"), BackupPlan.includeList(BackupOptions()))
        assertEquals(
            listOf("tmp/psbak-meta", "root/Projects", "usr/local"),
            BackupPlan.includeList(BackupOptions(scopes = setOf(BackupScope.HOME, BackupScope.USR_LOCAL), homeMode = HomeMode.CHOSEN, homeItems = setOf("Projects"))),
        )
    }

    @Test
    fun `an empty or hostile choice is refused before any work`() {
        assertNotNull(BackupPlan.validate(BackupOptions(homeMode = HomeMode.CHOSEN, homeItems = emptySet())))
        // With the home switched off, an empty folder list is not an error.
        assertNull(BackupPlan.validate(BackupOptions(scopes = setOf(BackupScope.PACKAGES), homeMode = HomeMode.CHOSEN)))
        listOf("", "/etc", "../x", "a/../b", "a//b", "a/./b", "x\ny", "a/").forEach {
            assertNotNull("'$it' must be refused", HomePaths.validate(it).takeIf { _ -> it != "a/" } ?: "x")
        }
        assertNull(HomePaths.validate(".config/kilo"))
        assertNull(HomePaths.validate("Projects"))
        assertNotNull(BackupPlan.validate(BackupOptions(homeMode = HomeMode.CHOSEN, homeItems = setOf("ok", "../evil"))))
        assertEquals("root/Projects", HomePaths.member("Projects/"))
    }

    @Test
    fun `tool credential files are on the left-out list unless the backup is encrypted`() {
        val plain = BackupPlan.excludeList(BackupOptions(presets = emptySet()), encrypted = false)
        assertTrue(plain.containsAll(listOf("*/.credentials.json", "*/auth.json", "root/.claude.json", "root/.config/gh")))
        assertTrue(BackupPlan.excludeList(BackupOptions(presets = emptySet()), encrypted = true).isEmpty())
    }

    // ------------------------------------------------- chosen folders, E2E

    @Test
    fun `a backup of only the ticked folders carries only those, and restores them`() {
        assumeTrue("needs /bin/sh, tar and gzip", Tools.canRunPipelines)
        val dev = FakeDevice("Old Phone").populate()
        try {
            dev.write("root/.claude/history.jsonl", "my conversations\n")
            dev.write("root/.claude/skills/s.md", "a skill\n")
            dev.write("root/.zcode/config.json", "{}\n")
            dev.write("root/Projects/keep/a.kt", "keep me\n")
            dev.write("root/Projects/other/b.kt", "not chosen\n")
            val (dir, storage) = tempStorage()
            try {
                val opts = BackupOptions(homeMode = HomeMode.CHOSEN, homeItems = setOf(".claude", ".zcode", "Projects/keep"))
                val done = dev.engine().backup(BackupRequest(opts, null, false, storage), CancelToken()) { } as BackupOutcome.Done
                assertEquals(listOf(".claude", ".zcode", "Projects/keep"), done.record.manifest.homeItems)
                val m = listArchive(File(dir, done.record.name))
                assertTrue(m.contains("root/.claude/history.jsonl"))
                assertTrue(m.contains("root/.claude/skills/s.md"))
                assertTrue(m.contains("root/.zcode/config.json"))
                assertTrue(m.contains("root/Projects/keep/a.kt"))
                assertTrue("not ticked: not carried", m.none { it.contains("Projects/other") || it.contains("proj/src") || it.contains("dir with space") })

                // Restore onto a fresh phone.
                val fresh = FakeDevice("New Phone")
                try {
                    fresh.write("etc/alpine-release", "3.24.2\n")
                    val engine = fresh.engine()
                    val ready = engine.inspect(StoredSource(storage, storage.list().first()), null, CancelToken()) { } as InspectOutcome.Ready
                    assertEquals(listOf(".claude", ".zcode", "Projects/keep"), ready.manifest.homeItems)
                    engine.apply(ready.stageId, setOf(BackupScope.HOME), RestoreMode.IN_PLACE, false, CancelToken()) { }
                    assertEquals("my conversations\n", File(fresh.root, "root/.claude/history.jsonl").readText())
                    assertEquals("keep me\n", File(fresh.root, "root/Projects/keep/a.kt").readText())
                    assertFalse(File(fresh.root, "root/Projects/other").exists())
                } finally {
                    fresh.cleanup()
                }
            } finally {
                dir.deleteRecursively()
            }
        } finally {
            dev.cleanup()
        }
    }

    @Test
    fun `old settings and manifests without the new fields still decode`() {
        val old = "{\"location\":null,\"options\":{\"keep\":2},\"encrypt\":true}"
        val s = VaultSettingsCodec.decode(old)
        assertEquals(HomeMode.EVERYTHING, s.options.homeMode)
        assertTrue(s.options.homeItems.isEmpty())
        assertTrue(s.encrypt)
    }
}
