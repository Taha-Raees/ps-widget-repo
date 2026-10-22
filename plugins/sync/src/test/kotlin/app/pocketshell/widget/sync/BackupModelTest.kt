package app.pocketshell.widget.sync

import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure backup model: plans, header, codecs, naming, retention, compatibility. */
class BackupModelTest {

    private val utc: ZoneId = ZoneOffset.UTC

    // ------------------------------------------------------------------ plan

    @Test
    fun `defaults back up home, packages and app settings - not the optional trees`() {
        val o = BackupOptions()
        assertEquals(setOf(BackupScope.HOME, BackupScope.PACKAGES, BackupScope.APP_SETTINGS), o.scopes)
        assertEquals(listOf("tmp/psbak-meta", "root"), BackupPlan.includeList(o))
    }

    @Test
    fun `the include list always carries the meta folder and only chosen trees`() {
        val o = BackupOptions(scopes = setOf(BackupScope.HOME, BackupScope.USR_LOCAL, BackupScope.OPT))
        assertEquals(listOf("tmp/psbak-meta", "root", "usr/local", "opt"), BackupPlan.includeList(o))
        assertEquals(listOf("tmp/psbak-meta"), BackupPlan.includeList(BackupOptions(scopes = setOf(BackupScope.PACKAGES))))
    }

    @Test
    fun `presets become tar exclude globs and are deduplicated`() {
        val ex = BackupPlan.excludeList(BackupOptions(), encrypted = true)
        assertTrue(ex.contains("*/node_modules"))
        assertTrue(ex.contains("*/.gradle/caches"))
        assertTrue(ex.contains("root/.cache"))
        assertEquals(ex.size, ex.distinct().size)
        // Presets off → nothing excluded for them.
        val none = BackupPlan.excludeList(BackupOptions(presets = emptySet()), encrypted = true)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `key and credential files stay out unless encrypted or explicitly included`() {
        val plain = BackupPlan.excludeList(BackupOptions(presets = emptySet()), encrypted = false)
        assertTrue(plain.containsAll(listOf("root/.ssh", "root/.gnupg", "*/.env", "*.pem", "*.key", "root/.netrc")))
        // Encrypted: they ride along.
        assertTrue(BackupPlan.excludeList(BackupOptions(presets = emptySet()), encrypted = true).isEmpty())
        // Explicit acknowledgement without encryption.
        assertTrue(BackupPlan.excludeList(BackupOptions(presets = emptySet(), includeSensitive = true), encrypted = false).isEmpty())
        assertTrue(BackupPlan.includesSensitive(BackupOptions(), encrypted = true))
        assertFalse(BackupPlan.includesSensitive(BackupOptions(), encrypted = false))
    }

    @Test
    fun `user excludes are appended and validated`() {
        val o = BackupOptions(presets = emptySet(), extraExcludes = listOf("root/big-data"))
        assertEquals(listOf("root/big-data"), BackupPlan.excludeList(o, encrypted = true))
        assertNull(BackupPlan.validateExclude("*/target"))
        assertNotNull(BackupPlan.validateExclude(""))
        assertNotNull(BackupPlan.validateExclude("a\nb"))
        assertNotNull(BackupPlan.validateExclude("root/../etc"))
        assertNotNull(BackupPlan.validateExclude("x".repeat(201)))
    }

    @Test
    fun `an empty or absurd request is refused before anything is written`() {
        assertNotNull(BackupPlan.validate(BackupOptions(scopes = emptySet())))
        assertNotNull(BackupPlan.validate(BackupOptions(keep = 0)))
        assertNotNull(BackupPlan.validate(BackupOptions(keep = 99)))
        assertNotNull(BackupPlan.validate(BackupOptions(extraExcludes = listOf("ok", ""))))
        assertNull(BackupPlan.validate(BackupOptions()))
    }

    // ---------------------------------------------------------------- header

    @Test
    fun `the header round-trips and is exactly eight bytes`() {
        BackupCodec.entries.forEach { codec ->
            listOf(true, false).forEach { enc ->
                val bytes = PsbakHeader(PsbakHeader.VERSION, enc, codec).encode()
                assertEquals(PsbakHeader.SIZE, bytes.size)
                assertEquals(PsbakHeader.Parsed.Ok(PsbakHeader(PsbakHeader.VERSION, enc, codec)), PsbakHeader.parse(bytes))
            }
        }
    }

    @Test
    fun `a foreign, short, newer or unknown-codec file is classified, never mis-read`() {
        assertEquals(PsbakHeader.Parsed.NotPsbak, PsbakHeader.parse(ByteArray(0)))
        assertEquals(PsbakHeader.Parsed.NotPsbak, PsbakHeader.parse("PK\u0003\u0004zip!".toByteArray()))
        assertEquals(PsbakHeader.Parsed.NotPsbak, PsbakHeader.parse("PSBAK".toByteArray()))
        val newer = PsbakHeader(PsbakHeader.VERSION, false, BackupCodec.ZSTD).encode().also { it[5] = 9 }
        assertEquals(PsbakHeader.Parsed.Newer(9), PsbakHeader.parse(newer))
        val odd = PsbakHeader(PsbakHeader.VERSION, false, BackupCodec.ZSTD).encode().also { it[7] = 77 }
        assertEquals(PsbakHeader.Parsed.UnknownCodec, PsbakHeader.parse(odd))
    }

    @Test
    fun `zstd is preferred when installed, gzip is the always-there fallback`() {
        assertEquals(BackupCodec.ZSTD, BackupCodec.preferred(true))
        assertEquals(BackupCodec.GZIP, BackupCodec.preferred(false))
        assertEquals(listOf("gz", "zst", "xz"), listOf(BackupCodec.GZIP, BackupCodec.ZSTD, BackupCodec.XZ).map { it.fileExt })
    }

    // -------------------------------------------------------------- manifest

    private fun manifest(at: Long = 1_000, enc: Boolean = false) = BackupManifest(
        createdAtMs = at, device = "Galaxy Flip", pluginVersion = "3.0.0", codec = BackupCodec.ZSTD,
        scopes = listOf(BackupScope.HOME, BackupScope.PACKAGES), excludes = listOf("*/node_modules"),
        sensitiveIncluded = enc, encrypted = enc, packages = 63, appSettings = listOf("notes_store"),
    )

    @Test
    fun `manifest and sidecar round-trip through JSON`() {
        val m = manifest()
        assertEquals(m, BackupCodecs.decodeManifest(BackupCodecs.encodeManifest(m)))
        val r = BackupRecord("PocketShell-Galaxy-Flip-20261022-1830.psbak", 123_456, "ab".repeat(32), m, arch = "aarch64", alpine = "3.24.2")
        assertEquals(r, BackupCodecs.decodeRecord(BackupCodecs.encodeRecord(r)))
    }

    @Test
    fun `corrupt or foreign JSON is not a record - never half-invented`() {
        assertNull(BackupCodecs.decodeRecord(null))
        assertNull(BackupCodecs.decodeRecord(""))
        assertNull(BackupCodecs.decodeRecord("{not json"))
        assertNull(BackupCodecs.decodeRecord("{\"hello\":1}"))
        assertNull(BackupCodecs.decodeManifest("[]"))
        // A newer writer's extra keys must not break an older reader.
        val m = BackupCodecs.encodeManifest(manifest()).dropLast(1) + ",\"futureField\":42}"
        assertNotNull(BackupCodecs.decodeManifest(m))
        // An empty name or checksum is not a usable record.
        val blank = BackupCodecs.encodeRecord(BackupRecord("x.psbak", 1, "h", manifest())).replace("\"h\"", "\"\"")
        assertNull(BackupCodecs.decodeRecord(blank))
    }

    // ---------------------------------------------------------------- naming

    @Test
    fun `file names are stable, sortable, and carry a safe device label`() {
        val at = 1_792_693_800_000L // 2026-10-22 18:30:00 UTC
        assertEquals("PocketShell-Galaxy-Z-Flip-20261022-1830.psbak", BackupNames.fileName("Galaxy Z Flip", at, utc))
        assertEquals("PocketShell-device-20261022-1830.psbak", BackupNames.fileName("   ", at, utc))
        assertEquals("PocketShell-device-20261022-1830.psbak", BackupNames.fileName("../../etc", at, utc).replace("etc", "device"))
        val evil = BackupNames.fileName("a/b\\c:d*e?f\"g<h>i|j", at, utc)
        assertTrue(BackupNames.isOurs(evil))
        assertFalse(evil.contains('/') || evil.contains('\\') || evil.contains(':'))
        assertTrue(BackupNames.deviceSlug("x".repeat(80)).length <= 24)
    }

    @Test
    fun `only names this app wrote are ours - retention and listing never touch anything else`() {
        assertTrue(BackupNames.isOurs("PocketShell-Pixel-8-20261022-1830.psbak"))
        assertFalse(BackupNames.isOurs("holiday-photos.psbak"))
        assertFalse(BackupNames.isOurs("PocketShell-Pixel-20261022-1830.zip"))
        assertFalse(BackupNames.isOurs("PocketShell-Pixel-20261022-1830.psbak.json"))
        assertFalse(BackupNames.isOurs("../PocketShell-Pixel-20261022-1830.psbak"))
        assertTrue(BackupNames.isSidecar("PocketShell-Pixel-20261022-1830.psbak.json"))
        assertFalse(BackupNames.isSidecar("notes.json"))
        assertEquals("a.psbak.json", BackupNames.sidecarName("a.psbak"))
    }

    @Test
    fun `the side restore folder is dated`() {
        assertEquals("restore-20261022-1830", BackupNames.sideFolder(1_792_693_800_000L, utc))
    }

    // ------------------------------------------------------------- retention

    private fun stored(name: String, at: Long, withRecord: Boolean = true) = StoredBackup(
        name = name, sizeBytes = 10, modifiedAtMs = at,
        record = if (withRecord) BackupRecord(name, 10, "h", manifest(at)) else null,
    )

    private fun n(i: Int) = "PocketShell-X-2026102$i-1000.psbak"

    @Test
    fun `retention keeps the newest N and prunes only the older ones`() {
        val all = (1..5).map { stored(n(it), it * 1000L) }
        assertEquals(listOf(n(2), n(1)), Retention.toPrune(all, keep = 3).map { it.name })
        assertTrue(Retention.toPrune(all, keep = 5).isEmpty())
        assertTrue(Retention.toPrune(all, keep = 20).isEmpty())
    }

    @Test
    fun `retention can never empty a location, never touches foreign files, never an unrecorded one`() {
        val all = listOf(stored(n(1), 1000), stored(n(2), 2000), stored("holiday.psbak", 0), stored(n(3), 3000, withRecord = false))
        // keep=0 is clamped to 1: the newest recorded backup always survives.
        assertEquals(listOf(n(1)), Retention.toPrune(all, keep = 0).map { it.name })
        // A file without a valid sidecar and a foreign file are never candidates.
        assertTrue(Retention.toPrune(all, keep = 0).none { it.name == "holiday.psbak" || it.name == n(3) })
    }

    @Test
    fun `a protected name is never pruned even when it is old`() {
        val all = (1..4).map { stored(n(it), it * 1000L) }
        assertEquals(listOf(n(1)), Retention.toPrune(all, keep = 2, protect = setOf(n(2))).map { it.name })
    }

    // --------------------------------------------------------------- restore

    @Test
    fun `restore scopes map to tar members and the destination follows the mode`() {
        assertEquals(listOf("root", "usr/local"), RestorePlan.members(setOf(BackupScope.HOME, BackupScope.USR_LOCAL, BackupScope.PACKAGES)))
        assertTrue(RestorePlan.members(setOf(BackupScope.PACKAGES, BackupScope.APP_SETTINGS)).isEmpty())
        assertEquals("/", RestorePlan.destination(RestoreMode.IN_PLACE, 0, utc))
        assertEquals("/root/restore-20261022-1830", RestorePlan.destination(RestoreMode.SIDE_FOLDER, 1_792_693_800_000L, utc))
        assertTrue(RestorePlan.confirmationOk("RESTORE"))
        assertTrue(RestorePlan.confirmationOk("  RESTORE "))
        assertFalse(RestorePlan.confirmationOk("restore"))
        assertFalse(RestorePlan.confirmationOk(""))
    }

    @Test
    fun `compatibility - a different architecture or a newer format blocks, a different alpine warns`() {
        val ok = Compatibility.check(BackupRecord("n", 1, "h", manifest(), arch = "aarch64", alpine = "3.24.2"), manifest(), "aarch64", "3.24.4")
        assertTrue(ok.ok)
        assertTrue(ok.warnings.isEmpty())
        val minor = Compatibility.check(BackupRecord("n", 1, "h", manifest(), arch = "aarch64", alpine = "3.22.1"), manifest(), "aarch64", "3.24.2")
        assertTrue(minor.ok)
        assertEquals(1, minor.warnings.size)
        val arch = Compatibility.check(BackupRecord("n", 1, "h", manifest(), arch = "x86_64"), manifest(), "aarch64", null)
        assertFalse(arch.ok)
        val newer = Compatibility.check(null, manifest().copy(format = 5), "aarch64", "3.24.2")
        assertFalse(newer.ok)
        // Unknown on either side is not an error — nothing is invented.
        assertTrue(Compatibility.check(null, manifest(), null, null).ok)
    }

    // -------------------------------------------------------------- packages

    @Test
    fun `the package list keeps valid entries in order and drops noise and anything odd`() {
        val world = "alpine-baselayout\n\n# comment\nbash\ngit>=2.40\n  openjdk21  \nbash\nbad name\n\$(rm -rf)\n-x\n;ls\ncurl@testing\n"
        assertEquals(listOf("alpine-baselayout", "bash", "git>=2.40", "openjdk21", "curl@testing"), ApkWorld.parse(world))
        assertTrue(ApkWorld.parse(null).isEmpty())
        assertTrue(ApkWorld.parse("").isEmpty())
    }

    @Test
    fun `app settings are an allow-list with no grants and no machine state`() {
        assertEquals(listOf("notes_store", "launchers", "settings", "sync_profiles"), AppSettingsFiles.NAMES)
        assertEquals("notes_store.preferences_pb", AppSettingsFiles.fileName("notes_store"))
        assertEquals("appdata/settings.preferences_pb", AppSettingsFiles.stagePath("settings"))
        val banned = listOf("plugin_consents", "pocketshell_plugin_revocations", "widget_catalog", "home_widgets", "notifications")
        assertTrue(banned.none { it in AppSettingsFiles.NAMES })
    }
}
