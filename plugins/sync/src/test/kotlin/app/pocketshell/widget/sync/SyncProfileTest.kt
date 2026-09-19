package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8.4 — the SYNC/BACKUP model + store codec tests: the JSON round trip,
 * the corrupt-record honesty (decode NEVER invents profiles), the
 * validation gate (the leading-dash injection guard) and the pure
 * classification helpers.
 */
class SyncProfileTest {

    private fun profile(
        id: String = "p1",
        backend: SyncBackend = SyncBackend.RSYNC,
        source: String = "/root/project",
        destination: String = "/mnt/backup",
        lastRunMs: Long? = null,
    ) = SyncProfile(
        id = id,
        backend = backend,
        source = source,
        destination = destination,
        createdAtMs = 1_700_000_000_000,
        lastRunMs = lastRunMs,
    )

    // ------------------------------------------------------- round trip

    @Test
    fun `a profile survives the JSON round trip byte-for-byte in shape`() {
        val original = listOf(
            profile(),
            profile(
                id = "p2",
                backend = SyncBackend.RCLONE,
                source = "/root/photos",
                destination = "user@host:/srv/photos",
                lastRunMs = 1_700_000_100_000,
            ),
        )
        val decoded = SyncStoreCodec.decode(SyncStoreCodec.encode(original))
        // p2 carries lastRunMs without a result — the exact shape the
        // M8_4_1 writer left behind — so decode migrates it to a recorded
        // success (see the migration test below); p1 stays never-run.
        assertEquals(
            listOf(
                original[0],
                original[1].copy(lastResult = SyncProfiles.RESULT_OK, lastExit = 0),
            ),
            decoded,
        )
    }

    @Test
    fun `an empty list round trips as an empty list`() {
        val decoded = SyncStoreCodec.decode(SyncStoreCodec.encode(emptyList()))
        assertTrue(decoded.isEmpty())
    }

    // ---------------------------------------------------------- honesty

    @Test
    fun `a null record decodes to empty - no seeded profiles`() {
        assertTrue(SyncStoreCodec.decode(null).isEmpty())
    }

    @Test
    fun `a corrupt record decodes to empty - never invented profiles`() {
        assertTrue(SyncStoreCodec.decode("not json at all {{{").isEmpty())
        assertTrue(SyncStoreCodec.decode("[]trailing").isEmpty())
        assertTrue(SyncStoreCodec.decode("\"a string\"").isEmpty())
    }

    @Test
    fun `records with blank specs are dropped - they cannot be probed honestly`() {
        val raw = SyncStoreCodec.encode(
            listOf(
                profile(id = "good"),
                profile(id = "blank-src", source = "   "),
                profile(id = "blank-dst", destination = ""),
            ),
        )
        val decoded = SyncStoreCodec.decode(raw)
        assertEquals(listOf("good"), decoded.map { it.id })
    }

    @Test
    fun `records with leading-dash specs are dropped at decode too`() {
        // Refused at creation, but a record that arrives with one (hand edit,
        // future writer bug) must not reach the exec layer either.
        val raw = SyncStoreCodec.encode(
            listOf(
                profile(id = "dash", source = "--delete"),
                profile(id = "ok"),
            ),
        )
        val decoded = SyncStoreCodec.decode(raw)
        assertEquals(listOf("ok"), decoded.map { it.id })
    }

    @Test
    fun `duplicate ids dedupe first-wins and the list is capped`() {
        val raw = SyncStoreCodec.encode(
            listOf(
                profile(id = "a", source = "/one"),
                profile(id = "a", source = "/two"),
                profile(id = "b"),
            ),
        )
        val decoded = SyncStoreCodec.decode(raw)
        assertEquals(listOf("a", "b"), decoded.map { it.id })
        assertEquals("/one", decoded.first { it.id == "a" }.source)

        val overCap = (1..(SyncProfiles.MAX_PROFILES + 3)).map { profile(id = "p$it") }
        assertEquals(SyncProfiles.MAX_PROFILES, SyncStoreCodec.decode(SyncStoreCodec.encode(overCap)).size)
    }

    @Test
    fun `unknown json keys are ignored - older app reads newer store`() {
        val raw =
            """[{"id":"p1","backend":"RSYNC","source":"/a","destination":"/b","createdAtMs":1,"futureField":42}]"""
        val decoded = SyncStoreCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("/a", decoded[0].source)
    }

    // ------------------------------------------------------- validation

    @Test
    fun `the validation gate refuses leading-dash specs - the argv option guard`() {
        assertNull(SyncProfiles.validate("/root/project", "/mnt/backup"))
        assertEquals(
            "paths must not start with \"-\"",
            SyncProfiles.validate("-oProxyCommand=evil", "/mnt/backup"),
        )
        assertEquals(
            "paths must not start with \"-\"",
            SyncProfiles.validate("/root/project", "--dry-run"),
        )
    }

    @Test
    fun `the validation gate refuses blanks and identical specs`() {
        assertEquals("source is required", SyncProfiles.validate("  ", "/mnt/backup"))
        assertEquals("destination is required", SyncProfiles.validate("/root", ""))
        assertEquals(
            "source and destination are the same path",
            SyncProfiles.validate("/root", "/root"),
        )
    }

    @Test
    fun `the validation gate enforces the length cap`() {
        val long = "a".repeat(SyncProfiles.SPEC_MAX_LENGTH + 1)
        assertEquals(
            "paths are limited to ${SyncProfiles.SPEC_MAX_LENGTH} characters",
            SyncProfiles.validate(long, "/mnt/backup"),
        )
        assertNull(SyncProfiles.validate("a".repeat(SyncProfiles.SPEC_MAX_LENGTH), "/mnt/backup"))
    }

    // ---------------------------------------------------- classification

    @Test
    fun `remote detection - colon before any slash means remote`() {
        assertTrue(SyncProfiles.isRemote("user@host:/srv/backup"))
        assertTrue(SyncProfiles.isRemote("host:rel/path"))
        assertTrue(SyncProfiles.isRemote("gdrive:photos"))
        assertTrue(SyncProfiles.isRemote(":sftp,host=h:/path"))
        org.junit.Assert.assertFalse(SyncProfiles.isRemote("/mnt/backup"))
        org.junit.Assert.assertFalse(SyncProfiles.isRemote("/root/deep/er:name"))
        org.junit.Assert.assertFalse(SyncProfiles.isRemote("relative/path"))
    }

    @Test
    fun `guest home displays as tilde - everything else verbatim`() {
        assertEquals("~/project", SyncProfiles.displayPath("/root/project"))
        assertEquals("~", SyncProfiles.displayPath("/root"))
        assertEquals("/mnt/backup", SyncProfiles.displayPath("/mnt/backup"))
        assertEquals("user@host:/srv", SyncProfiles.displayPath("user@host:/srv"))
    }

    @Test
    fun `the overview line joins source and destination`() {
        assertEquals(
            "~/project → /mnt/backup",
            SyncProfiles.line(profile(source = "/root/project", destination = "/mnt/backup")),
        )
    }

    // ----------------------------------------------------------- upsert

    @Test
    fun `upsert replaces by id and caps the list`() {
        val list = listOf(profile("a"), profile("b"))
        val updated = SyncProfiles.upsert(list, profile("b", source = "/new"))
        assertEquals(2, updated.size)
        assertEquals("/new", updated.first { it.id == "b" }.source)

        val full = (1..SyncProfiles.MAX_PROFILES).map { profile("p$it") }
        val added = SyncProfiles.upsert(full, profile("new"))
        assertEquals(SyncProfiles.MAX_PROFILES, added.size)
        assertEquals("new", added.last().id)
        assertEquals("p2", added.first().id)
    }

    // --------------------------------------------- run history (M8.4.3)

    @Test
    fun `the run-history fields all default to never run`() {
        val p = profile()
        assertNull(p.lastRunMs)
        assertNull(p.lastRunSummary)
        assertNull(p.lastResult)
        assertNull(p.lastExit)
        assertNull(p.lastStats)
    }

    @Test
    fun `an M8_4_1 store record migrates - a run without a result was an exit-0 run`() {
        // The M8.4.1 writer persisted lastRunMs/lastRunSummary on exit 0
        // ONLY, so this exact shape is a recorded success.
        val raw =
            """[{"id":"old","backend":"RSYNC","source":"/a","destination":"/b","createdAtMs":1,"lastRunMs":99,"lastRunSummary":"exit 0 · sent 1,235 bytes"}]"""
        val decoded = SyncStoreCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(99L, decoded[0].lastRunMs)
        assertEquals(SyncProfiles.RESULT_OK, decoded[0].lastResult)
        assertEquals(0, decoded[0].lastExit)
        assertNull(decoded[0].lastStats)
        assertEquals("exit 0 · sent 1,235 bytes", decoded[0].lastRunSummary)
    }

    @Test
    fun `a result without a run is not data - decode clears the orphans`() {
        val raw = SyncStoreCodec.encode(
            listOf(
                profile().copy(
                    lastResult = SyncProfiles.RESULT_OK,
                    lastExit = 0,
                    lastStats = "3 files · 1.2 MB",
                ),
            ),
        )
        val decoded = SyncStoreCodec.decode(raw)
        assertNull(decoded[0].lastRunMs)
        assertNull(decoded[0].lastResult)
        assertNull(decoded[0].lastExit)
        assertNull(decoded[0].lastStats)
    }

    @Test
    fun `full run records round trip - ok with stats and failed with its real exit`() {
        val ok = profile(id = "ok").copy(
            lastRunMs = 100L,
            lastRunSummary = "exit 0 · total size is 26,354,124  speedup is 21.33",
            lastResult = SyncProfiles.RESULT_OK,
            lastExit = 0,
            lastStats = "3 files · 1.2 MB",
        )
        val failed = profile(id = "failed").copy(
            lastRunMs = 200L,
            lastRunSummary = "exit 3 · directory not found",
            lastResult = SyncProfiles.RESULT_FAILED,
            lastExit = 3,
        )
        val decoded = SyncStoreCodec.decode(SyncStoreCodec.encode(listOf(ok, failed)))
        assertEquals(ok, decoded[0])
        assertEquals(failed, decoded[1])
        assertNull(decoded[1].lastStats)
    }

    @Test
    fun `status glyphs separate never run from ok from failed`() {
        assertEquals("○", SyncProfiles.statusGlyph(profile()))
        assertEquals(
            "✓",
            SyncProfiles.statusGlyph(profile().copy(lastRunMs = 1L, lastResult = SyncProfiles.RESULT_OK)),
        )
        assertEquals(
            "✕",
            SyncProfiles.statusGlyph(
                profile().copy(lastRunMs = 1L, lastResult = SyncProfiles.RESULT_FAILED, lastExit = 1),
            ),
        )
    }

    @Test
    fun `relativeTime boundaries - now, minutes, hours, days, then a short date`() {
        val now = 1_700_000_000_000L
        val minute = 60_000L
        val hour = 60L * minute
        val day = 24L * hour
        assertEquals("now", SyncProfiles.relativeTime(now, now - 59_999L))
        // Clock skew clamps to now — never a negative or invented past.
        assertEquals("now", SyncProfiles.relativeTime(now, now + 5_000L))
        assertEquals("1m", SyncProfiles.relativeTime(now, now - minute))
        assertEquals("5m", SyncProfiles.relativeTime(now, now - 5 * minute))
        assertEquals("59m", SyncProfiles.relativeTime(now, now - 59 * minute))
        assertEquals("1h", SyncProfiles.relativeTime(now, now - hour))
        assertEquals("2h", SyncProfiles.relativeTime(now, now - 2 * hour))
        assertEquals("23h", SyncProfiles.relativeTime(now, now - 23 * hour))
        assertEquals("1d", SyncProfiles.relativeTime(now, now - day))
        assertEquals("3d", SyncProfiles.relativeTime(now, now - 3 * day))
        assertEquals("6d", SyncProfiles.relativeTime(now, now - 6 * day))
        // Past a week: a short date, formatter-derived (same default TZ).
        val expected = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
            .format(java.util.Date(now - 8 * day))
        assertEquals(expected, SyncProfiles.relativeTime(now, now - 8 * day))
    }

    @Test
    fun `agoText composes - now, Xm ago, Xh ago, Xd ago, bare date`() {
        val now = 1_700_000_000_000L
        assertEquals("now", SyncProfiles.agoText(now, now - 1_000L))
        assertEquals("5m ago", SyncProfiles.agoText(now, now - 5 * 60_000L))
        assertEquals("2h ago", SyncProfiles.agoText(now, now - 2 * 3_600_000L))
        assertEquals("3d ago", SyncProfiles.agoText(now, now - 3 * 86_400_000L))
        val expected = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
            .format(java.util.Date(now - 9L * 86_400_000L))
        assertEquals(expected, SyncProfiles.agoText(now, now - 9L * 86_400_000L))
    }

    @Test
    fun `the status line composes time, result and the real exit`() {
        val now = 1_700_000_000_000L
        assertEquals("never run", SyncProfiles.statusLine(profile(), now))
        assertEquals(
            "now · OK (exit 0)",
            SyncProfiles.statusLine(
                profile().copy(lastRunMs = now, lastResult = SyncProfiles.RESULT_OK, lastExit = 0),
                now,
            ),
        )
        assertEquals(
            "2h ago · OK (exit 0)",
            SyncProfiles.statusLine(
                profile().copy(lastRunMs = now - 2 * 3_600_000L, lastResult = SyncProfiles.RESULT_OK, lastExit = 0),
                now,
            ),
        )
        assertEquals(
            "3d ago · FAILED (exit 1)",
            SyncProfiles.statusLine(
                profile().copy(lastRunMs = now - 3 * 86_400_000L, lastResult = SyncProfiles.RESULT_FAILED, lastExit = 1),
                now,
            ),
        )
    }

    // ------------------------------------------------ runRecord (M8.4.3)

    @Test
    fun `a failed run is recorded too - real exit, no stats invented`() {
        val result = RunResult(
            exitCode = 1,
            summary = "exit 1 · rsync: change_dir \"/mnt\" failed",
            output = "rsync: change_dir \"/mnt\" failed: No such file or directory (2)\n",
        )
        val record = runRecord(profile(), result, atMs = 42L)!!
        assertEquals(42L, record.lastRunMs)
        assertEquals("exit 1 · rsync: change_dir \"/mnt\" failed", record.lastRunSummary)
        assertEquals(SyncProfiles.RESULT_FAILED, record.lastResult)
        assertEquals(1, record.lastExit)
        assertNull(record.lastStats)
    }

    @Test
    fun `an ok run records parsed stats from the tool's real output`() {
        val output = """
            sending incremental file list
            Number of regular files transferred: 3
            Total transferred file size: 1,234,567 bytes
            total size is 26,354,124  speedup is 21.33
        """.trimIndent()
        val result = RunResult(
            exitCode = 0,
            summary = "exit 0 · total size is 26,354,124  speedup is 21.33",
            output = output,
        )
        val record = runRecord(profile(), result, atMs = 7L)!!
        assertEquals(SyncProfiles.RESULT_OK, record.lastResult)
        assertEquals(0, record.lastExit)
        assertEquals("3 files · 1.2 MB", record.lastStats)
    }

    @Test
    fun `a run with no exit records nothing - absence stays absence`() {
        assertNull(
            runRecord(
                profile(),
                RunResult(exitCode = null, summary = "rsync is not installed (apk add rsync)"),
                atMs = 7L,
            ),
        )
    }
}
