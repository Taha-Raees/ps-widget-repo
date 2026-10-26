package app.pocketshell.widget.sync

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * END TO END: the real generated job script, real busybox tar and gzip, real
 * AES-GCM, real sha256 — only the guest "/" is a temp directory. Backup on one
 * "device", restore on another, with the tampering and failure cases between.
 */
class BackupEngineE2ETest {

    private val devices = ArrayList<FakeDevice>()
    private val stores = ArrayList<File>()
    private val none = CancelToken()
    private val quiet: (Progress) -> Unit = {}

    @Before
    fun requireTools() {
        assumeTrue("needs /bin/sh, tar and gzip", Tools.canRunPipelines)
    }

    @After
    fun cleanup() {
        devices.forEach { it.cleanup() }
        stores.forEach { it.deleteRecursively() }
    }

    private fun device(name: String = "Old Phone"): FakeDevice = FakeDevice(name).populate().also { devices += it }

    private fun store(): Pair<File, FileBackupStorage> = tempStorage().also { stores += it.first }

    private fun backup(
        dev: FakeDevice, storage: BackupStorage, options: BackupOptions = BackupOptions(),
        pass: String? = null, engine: BackupEngine = dev.engine(),
    ): BackupOutcome = engine.backup(BackupRequest(options, pass?.toCharArray(), zstdInstalled = false, storage = storage), none, quiet)

    private fun done(o: BackupOutcome): BackupOutcome.Done {
        assertTrue("expected Done but was $o", o is BackupOutcome.Done)
        return o as BackupOutcome.Done
    }

    private fun members(dir: File, name: String): List<String> = listArchive(File(dir, name))

    // ============================================================ backup

    @Test
    fun `a backup writes one portable file plus its sidecar and leaves nothing behind`() {
        val dev = device()
        val (dir, storage) = store()
        val d = done(backup(dev, storage))

        assertEquals(setOf(d.record.name, "${d.record.name}.json"), dir.list()!!.toSet())
        val bytes = File(dir, d.record.name).readBytes()
        val header = (PsbakHeader.parse(bytes) as PsbakHeader.Parsed.Ok).header
        assertEquals(BackupCodec.GZIP, header.codec)
        assertFalse(header.encrypted)
        assertEquals(bytes.size.toLong(), d.record.sizeBytes)
        // The sidecar is a faithful, decodable description of the stored file.
        val side = BackupCodecs.decodeRecord(File(dir, "${d.record.name}.json").readText())!!
        assertEquals(d.record, side)
        assertEquals(4, side.manifest.packages)
        assertEquals(setOf(BackupScope.HOME, BackupScope.PACKAGES, BackupScope.APP_SETTINGS), side.manifest.scopes.toSet())
        assertNotNull(side.arch)
        assertEquals("debian 13.7", side.alpine)
        // No staged files remain in the guest tmp.
        assertTrue(File(dev.root, "tmp/psbak").list().orEmpty().isEmpty())
        assertFalse(File(dev.root, "tmp/psbak-meta").exists())
    }

    @Test
    fun `the archive holds the home with spaces, symlinks and meta - and leaves regenerable weight and keys out`() {
        val dev = device()
        val (dir, storage) = store()
        val name = done(backup(dev, storage)).record.name
        val m = members(dir, name)

        assertTrue(m.contains("root/proj/src/a.kt"))
        assertTrue(m.contains("root/dir with space/file.txt"))
        assertTrue(m.contains("root/abs-link"))
        assertTrue(m.contains("root/proj/src/rel-link"))
        assertTrue(m.contains("root/.gradle/init.d/keep.gradle"))
        assertTrue(m.contains("tmp/psbak-meta/manifest.json"))
        assertTrue(m.contains("tmp/psbak-meta/pkg-manual"))
        assertTrue(m.contains("tmp/psbak-meta/system.txt"))
        // Presets: node_modules, gradle caches and ~/.cache are not carried.
        assertTrue(m.none { it.contains("node_modules") })
        assertTrue(m.none { it.contains(".gradle/caches") })
        assertTrue(m.none { it.startsWith("root/.cache") })
        // Unencrypted: key files and .env are left out by default.
        assertTrue(m.none { it.startsWith("root/.ssh") })
        assertTrue(m.none { it.endsWith(".env") })
        // Optional trees were not chosen.
        assertTrue(m.none { it.startsWith("usr/local") })
    }

    @Test
    fun `optional trees are carried when chosen, and a missing one is skipped not fatal`() {
        val dev = device()
        val (dir, storage) = store()
        val opts = BackupOptions(scopes = setOf(BackupScope.HOME, BackupScope.USR_LOCAL, BackupScope.OPT))
        val d = done(backup(dev, storage, opts))
        assertTrue(members(dir, d.record.name).contains("usr/local/bin/mytool"))
        // /opt does not exist on this device: the backup still succeeds and says what it skipped.
        assertEquals(listOf("opt"), d.skippedPaths)
    }

    @Test
    fun `app settings are captured from the allow-list only`() {
        val dev = device()
        dev.writeDatastore("notes_store", "my notes")
        dev.writeDatastore("settings", "prefs")
        dev.writeDatastore("plugin_consents", "GRANTS")
        dev.writeDatastore("widget_catalog", "SHA-PINS")
        dev.writeDatastore("home_widgets", "layout")
        val (dir, storage) = store()
        val d = done(backup(dev, storage))
        assertEquals(listOf("notes_store", "settings"), d.record.manifest.appSettings)
        val m = members(dir, d.record.name)
        assertTrue(m.contains("tmp/psbak-meta/appdata/notes_store.preferences_pb"))
        assertTrue(m.contains("tmp/psbak-meta/appdata/settings.preferences_pb"))
        assertTrue("security grants and machine state must never travel", m.none { it.contains("plugin_consents") || it.contains("widget_catalog") || it.contains("home_widgets") })
    }

    @Test
    fun `scopes off means those parts are absent`() {
        val dev = device()
        dev.writeDatastore("notes_store", "my notes")
        val (dir, storage) = store()
        val d = done(backup(dev, storage, BackupOptions(scopes = setOf(BackupScope.HOME))))
        val m = members(dir, d.record.name)
        assertTrue(m.none { it.contains("pkg-manual") })
        assertTrue(m.none { it.contains("appdata") })
        assertEquals(0, d.record.manifest.packages)
    }

    @Test
    fun `hostile exclude text is only data - nothing is ever interpreted`() {
        val dev = device()
        val (_, storage) = store()
        val canary = File(dev.root, "pwned")
        val opts = BackupOptions(extraExcludes = listOf("\$(touch ${canary.path})", "`touch ${canary.path}`", "a; touch ${canary.path}", "'\"quotes\"'"))
        done(backup(dev, storage, opts))
        assertFalse("a shell must never have evaluated an exclude pattern", canary.exists())
    }

    // ============================================================ encryption

    @Test
    fun `an encrypted backup carries keys, hides everything, and wants its passphrase`() {
        val dev = device()
        val (dir, storage) = store()
        val d = done(backup(dev, storage, pass = "correct horse"))
        val bytes = File(dir, d.record.name).readBytes()
        val header = (PsbakHeader.parse(bytes) as PsbakHeader.Parsed.Ok).header
        assertTrue(header.encrypted)
        assertTrue(d.record.manifest.encrypted)
        assertTrue("encrypted backups include keys and credentials", d.record.manifest.sensitiveIncluded)
        val asText = String(bytes, Charsets.ISO_8859_1)
        assertFalse(asText.contains("PRIVATE-KEY-MATERIAL"))
        assertFalse(asText.contains("a.kt"))

        val source = storedSource(storage, storage.firstArchive())
        assertTrue(dev.engine().inspect(source, null, none, quiet) is InspectOutcome.NeedsPassphrase)
        assertTrue(dev.engine().inspect(source, "wrong".toCharArray(), none, quiet) is InspectOutcome.WrongPassphrase)
        val ok = dev.engine().inspect(source, "correct horse".toCharArray(), none, quiet)
        assertTrue("expected Ready but was $ok", ok is InspectOutcome.Ready)
        dev.engine().discardStage((ok as InspectOutcome.Ready).stageId)
    }

    @Test
    fun `a wrong passphrase leaves no staged data behind`() {
        val dev = device()
        val (_, storage) = store()
        done(backup(dev, storage, pass = "right"))
        dev.engine().inspect(storedSource(storage, storage.firstArchive()), "wrong".toCharArray(), none, quiet)
        assertTrue(dev.engine().leftoverStages().isEmpty())
    }

    // ============================================================ restore

    @Test
    fun `new phone - choose the file, preview it, restore in place - everything comes back exactly`() {
        val old = device("Old Phone")
        old.writeDatastore("notes_store", "my notes")
        val (dir, storage) = store()
        val name = done(backup(old, storage, pass = "pw")).record.name

        // A brand-new device: only a bare Debian, and a picked file (no sidecar available).
        val fresh = FakeDevice("New Phone").also { devices += it }
        fresh.write("etc/debian_version", "13.7\n")
        fresh.write("root/.profile", "default profile\n")
        val engine = fresh.engine()

        val ready = engine.inspect(PickedFileSource(File(dir, name)), "pw".toCharArray(), none, quiet) as InspectOutcome.Ready
        assertEquals(listOf("base-files", "bash", "git", "openjdk-21-jdk-headless"), ready.packages)
        assertEquals(listOf("notes_store"), ready.appSettings)
        assertEquals("Old Phone", ready.manifest.device)
        assertTrue(ready.compatibility.ok)
        // INSPECT wrote nothing into the user's data.
        assertFalse(File(fresh.root, "root/proj").exists())

        val applied = engine.apply(ready.stageId, setOf(BackupScope.HOME, BackupScope.APP_SETTINGS), RestoreMode.IN_PLACE, restoreAppSettings = true, none, quiet) as ApplyOutcome.Applied
        assertEquals("/", applied.destination)
        assertEquals("fun main() = println(\"hello\")\n", File(fresh.root, "root/proj/src/a.kt").readText())
        assertEquals("spaced\n", File(fresh.root, "root/dir with space/file.txt").readText())
        assertEquals("PRIVATE-KEY-MATERIAL\n", File(fresh.root, "root/.ssh/id_ed25519").readText())
        assertTrue("the executable bit survives", File(fresh.root, "root/proj/src/a.kt").canExecute())
        assertEquals("/root/proj/src/a.kt", Files.readSymbolicLink(File(fresh.root, "root/abs-link").toPath()).toString())
        assertEquals("a.kt", Files.readSymbolicLink(File(fresh.root, "root/proj/src/rel-link").toPath()).toString())
        // Files already on the new phone and not in the backup are left alone.
        assertEquals("default profile\n", File(fresh.root, "root/.profile").readText())
        // Regenerable weight never came back; neither did anything excluded.
        assertFalse(File(fresh.root, "root/proj/node_modules").exists())
        // App settings were applied and the restart is flagged.
        assertEquals(listOf("notes_store"), applied.appSettingsRestored)
        assertTrue(applied.needsRestart)
        assertEquals("my notes", File(fresh.datastore, "notes_store.preferences_pb").readText())
    }

    @Test
    fun `restoring into a side folder leaves the live files untouched`() {
        val dev = device()
        val (_, storage) = store()
        done(backup(dev, storage))
        File(dev.root, "root/proj/src/a.kt").writeText("EDITED AFTER BACKUP\n")

        val engine = dev.engine()
        val ready = engine.inspect(storedSource(storage, storage.firstArchive()), null, none, quiet) as InspectOutcome.Ready
        val applied = engine.apply(ready.stageId, setOf(BackupScope.HOME), RestoreMode.SIDE_FOLDER, false, none, quiet) as ApplyOutcome.Applied
        assertTrue(applied.destination.startsWith("/root/restore-"))
        assertEquals("EDITED AFTER BACKUP\n", File(dev.root, "root/proj/src/a.kt").readText())
        assertEquals("fun main() = println(\"hello\")\n", File(dev.root, "${applied.destination}/root/proj/src/a.kt").readText())
    }

    @Test
    fun `an in-place restore overwrites the same paths and keeps everything else`() {
        val dev = device()
        val (_, storage) = store()
        done(backup(dev, storage))
        File(dev.root, "root/proj/src/a.kt").writeText("BROKEN\n")
        dev.write("root/new-file.txt", "made after the backup\n")

        val engine = dev.engine()
        val ready = engine.inspect(storedSource(storage, storage.firstArchive()), null, none, quiet) as InspectOutcome.Ready
        engine.apply(ready.stageId, setOf(BackupScope.HOME), RestoreMode.IN_PLACE, false, none, quiet)
        assertEquals("fun main() = println(\"hello\")\n", File(dev.root, "root/proj/src/a.kt").readText())
        assertEquals("made after the backup\n", File(dev.root, "root/new-file.txt").readText())
    }

    @Test
    fun `scope selection restores only what was ticked`() {
        val dev = device()
        val (_, storage) = store()
        done(backup(dev, storage, BackupOptions(scopes = setOf(BackupScope.HOME, BackupScope.USR_LOCAL))))
        File(dev.root, "root").deleteRecursively()
        File(dev.root, "usr").deleteRecursively()

        val engine = dev.engine()
        val ready = engine.inspect(storedSource(storage, storage.firstArchive()), null, none, quiet) as InspectOutcome.Ready
        engine.apply(ready.stageId, setOf(BackupScope.USR_LOCAL), RestoreMode.IN_PLACE, false, none, quiet)
        assertTrue(File(dev.root, "usr/local/bin/mytool").isFile)
        assertFalse("home was not ticked", File(dev.root, "root/proj").exists())
    }

    @Test
    fun `app settings restore keeps the previous file and never writes outside the allow-list`() {
        val old = device()
        old.writeDatastore("notes_store", "backed-up notes")
        val (_, storage) = store()
        done(backup(old, storage))

        val fresh = FakeDevice("New").also { devices += it }
        fresh.write("etc/debian_version", "13.7\n")
        fresh.writeDatastore("notes_store", "notes already on this phone")
        fresh.writeDatastore("plugin_consents", "THIS PHONE'S GRANTS")
        val engine = fresh.engine()
        val ready = engine.inspect(storedSource(storage, storage.firstArchive()), null, none, quiet) as InspectOutcome.Ready
        engine.apply(ready.stageId, setOf(BackupScope.APP_SETTINGS), RestoreMode.IN_PLACE, restoreAppSettings = true, none, quiet)

        assertEquals("backed-up notes", File(fresh.datastore, "notes_store.preferences_pb").readText())
        assertEquals("notes already on this phone", File(fresh.datastore, "notes_store.preferences_pb.pre-restore").readText())
        assertEquals("grants on this phone are never touched", "THIS PHONE'S GRANTS", File(fresh.datastore, "plugin_consents.preferences_pb").readText())
    }

    @Test
    fun `app settings stay untouched unless the user ticked them`() {
        val old = device()
        old.writeDatastore("notes_store", "backed-up notes")
        val (_, storage) = store()
        done(backup(old, storage))
        val fresh = FakeDevice("New").also { devices += it }
        fresh.write("etc/debian_version", "13.7\n")
        val engine = fresh.engine()
        val ready = engine.inspect(storedSource(storage, storage.firstArchive()), null, none, quiet) as InspectOutcome.Ready
        val applied = engine.apply(ready.stageId, setOf(BackupScope.HOME, BackupScope.APP_SETTINGS), RestoreMode.IN_PLACE, restoreAppSettings = false, none, quiet) as ApplyOutcome.Applied
        assertTrue(applied.appSettingsRestored.isEmpty())
        assertFalse(applied.needsRestart)
        assertFalse(File(fresh.datastore, "notes_store.preferences_pb").exists())
    }

    // ============================================================ integrity

    @Test
    fun `a file damaged in transfer is caught by its checksum before anything is unpacked`() {
        val dev = device()
        val (dir, storage) = store()
        val name = done(backup(dev, storage)).record.name
        val f = File(dir, name)
        val bytes = f.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()
        f.writeBytes(bytes)

        val out = dev.engine().inspect(storedSource(storage, storage.firstArchive()), null, none, quiet)
        assertTrue("expected a checksum failure but was $out", out is InspectOutcome.Failed && out.reason.contains("checksum"))
        assertTrue(dev.engine().leftoverStages().isEmpty())
    }

    @Test
    fun `without a sidecar a corrupt archive is still caught by the decompressor`() {
        val dev = device()
        val (dir, storage) = store()
        val name = done(backup(dev, storage)).record.name
        val f = File(dir, name)
        f.writeBytes(f.readBytes().copyOf((f.length() * 0.6).toInt())) // cut off the tail
        val out = dev.engine().inspect(PickedFileSource(f), null, none, quiet)
        assertTrue("expected failure but was $out", out is InspectOutcome.Failed)
        assertTrue(dev.engine().leftoverStages().isEmpty())
    }

    @Test
    fun `a file that is not a backup is refused plainly`() {
        val dev = device()
        val junk = File.createTempFile("junk", ".zip").apply { writeText("PK this is not a psbak at all") ; deleteOnExit() }
        val out = dev.engine().inspect(PickedFileSource(junk), null, none, quiet)
        assertTrue(out is InspectOutcome.Failed && out.reason.contains("not a PocketShell backup"))
        assertTrue(dev.engine().inspect(PickedFileSource(File.createTempFile("empty", ".psbak").also { it.deleteOnExit() }), null, none, quiet) is InspectOutcome.Failed)
    }

    @Test
    fun `verify matches an intact file and flags a changed one`() {
        val dev = device()
        val (dir, storage) = store()
        val d = done(backup(dev, storage))
        val engine = dev.engine()
        val stored = storage.firstArchive()
        assertEquals(VerifyOutcome.Match(d.record.sizeBytes), engine.verify(storage, stored, none, quiet))
        File(dir, d.record.name).appendBytes(byteArrayOf(1))
        assertTrue(engine.verify(storage, storage.firstArchive(), none, quiet) is VerifyOutcome.Mismatch)
        assertEquals(VerifyOutcome.NoRecord, engine.verify(storage, stored.copy(record = null), none, quiet))
    }

    // ============================================================ retention

    @Test
    fun `retention keeps the newest N, deletes archive and sidecar together, and never a foreign file`() {
        val dev = device()
        val (dir, storage) = store()
        File(dir, "holiday-photos.psbak").writeText("not ours")
        File(dir, "notes.txt").writeText("not ours")
        val opts = BackupOptions(keep = 2)
        val names = (1..4).map {
            dev.clock.addAndGet(120_000L)
            done(backup(dev, storage, opts)).record.name
        }
        val left = dir.list()!!.toSet()
        assertEquals(setOf(names[2], names[3], "${names[2]}.json", "${names[3]}.json", "holiday-photos.psbak", "notes.txt"), left)
    }

    @Test
    fun `two backups in the same minute both survive under distinct names`() {
        val dev = device()
        val (dir, storage) = store()
        val a = done(backup(dev, storage)).record.name
        val b = done(backup(dev, storage)).record.name
        assertTrue(a != b)
        assertTrue(BackupNames.isOurs(a) && BackupNames.isOurs(b))
        assertEquals(4, dir.list()!!.size)
    }

    // ============================================================ failure / cancel

    @Test
    fun `a failing job reports why, leaves no partial file and cleans its stage`() {
        assumeFalse("this test needs a machine without zstd", Tools.have("zstd"))
        val dev = device()
        val (dir, storage) = store()
        val out = dev.engine().backup(BackupRequest(BackupOptions(), null, zstdInstalled = true, storage = storage), none, quiet)
        assertTrue("expected Failed but was $out", out is BackupOutcome.Failed)
        assertTrue((out as BackupOutcome.Failed).reason.isNotBlank())
        assertTrue("no partial archive or sidecar", dir.list()!!.isEmpty())
        assertTrue(dev.engine().leftoverStages().isEmpty())
    }

    @Test
    fun `an empty request is refused before any work`() {
        val dev = device()
        val (dir, storage) = store()
        val runner = LocalShellRunner(dev.root)
        val out = dev.engine(runner).backup(BackupRequest(BackupOptions(scopes = emptySet()), null, false, storage), none, quiet)
        assertTrue(out is BackupOutcome.Failed)
        assertTrue(runner.started.isEmpty())
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun `an empty passphrase is refused rather than producing an unprotected file`() {
        val dev = device()
        val (dir, storage) = store()
        val out = dev.engine().backup(BackupRequest(BackupOptions(), CharArray(0), false, storage), none, quiet)
        assertTrue(out is BackupOutcome.Failed)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun `too little free space stops a backup up front`() {
        val dev = device()
        val (_, storage) = store()
        val env = dev.env(minFree = Long.MAX_VALUE)
        val out = BackupEngine(env, LocalShellRunner(dev.root)) { }.backup(BackupRequest(BackupOptions(), null, false, storage), none, quiet)
        assertTrue(out is BackupOutcome.Failed && out.reason.contains("free space"))
    }

    /** A job that never finishes on its own — it ends only when destroyed. */
    private class HangingRunner : GuestJobRunner {
        val destroyed = CountDownLatch(1)
        override fun start(argv: List<String>): RunningJob = object : RunningJob {
            override fun waitFor(timeoutMs: Long): JobResult {
                destroyed.await(timeoutMs, TimeUnit.MILLISECONDS)
                return JobResult(null, error = "terminated")
            }

            override fun destroy() = destroyed.countDown()
        }
    }

    @Test
    fun `cancel stops the job, signals its process tree, writes nothing and cleans up`() {
        val dev = device()
        val (dir, storage) = store()
        val runner = HangingRunner()
        val signalled = ArrayList<Int>()
        val engine = BackupEngine(dev.env(), runner) { signalled += it }
        val token = CancelToken()
        var calls = 0
        val out = engine.backup(BackupRequest(BackupOptions(), null, false, storage), token) { _ ->
            // Call 1 is "preparing"; call 2 is the first poll of the running job: it
            // has published its pid by now. The user cancels on call 3.
            calls++
            if (calls == 2) {
                File(dev.root, "tmp/psbak").listFiles()!!.first().let { File(it, BackupJob.Files.PID).writeText("4242") }
            }
            if (calls >= 3) token.cancel()
        }
        assertEquals(BackupOutcome.Cancelled, out)
        assertTrue("the job was destroyed", runner.destroyed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(4242), signalled)
        assertTrue(dir.list()!!.isEmpty())
        assertTrue(File(dev.root, "tmp/psbak").list().orEmpty().isEmpty())
    }

    @Test
    fun `leftover stages from an interrupted run are listed and can be discarded`() {
        val dev = device()
        File(dev.root, "tmp/psbak/abcdef123456").mkdirs()
        File(dev.root, "tmp/psbak/abcdef123456/payload").writeText("partial")
        File(dev.root, "tmp/psbak/not a valid id").mkdirs()
        val engine = dev.engine()
        assertEquals(listOf("abcdef123456"), engine.leftoverStages())
        engine.discardStage("abcdef123456")
        assertTrue(engine.leftoverStages().isEmpty())
        // An id that is not the app's own shape can never address a path.
        engine.discardStage("../../etc")
        assertTrue(File(dev.root, "etc/debian_version").isFile)
    }

    // ============================================================ packages

    @Test
    fun `reinstall hands the guest only the sanitized package list`() {
        val dev = device()
        val (_, storage) = store()
        done(backup(dev, storage))
        val engine = dev.engine()
        val ready = engine.inspect(storedSource(storage, storage.firstArchive()), null, none, quiet) as InspectOutcome.Ready
        // Poison the staged list the way a hostile archive might.
        val worldFile = File(dev.root, "tmp/psbak/${ready.stageId}/inspect/tmp/psbak-meta/pkg-manual")
        worldFile.writeText("bash\n\$(reboot)\n-evil\ngit\n;rm -rf /\n")

        val fake = object : GuestJobRunner {
            val seen = ArrayList<List<String>>()
            override fun start(argv: List<String>): RunningJob {
                seen += argv
                val stage = File(dev.root, argv[3].removePrefix("/"))
                File(stage, BackupJob.Files.EXIT).writeText("0")
                return object : RunningJob {
                    override fun waitFor(timeoutMs: Long) = JobResult(0)
                    override fun destroy() {}
                }
            }
        }
        val out = BackupEngine(dev.env(), fake) { }.reinstallPackages(ready.stageId, none, quiet)
        assertTrue(out is StepOutcome.Done)
        assertEquals("bash\ngit\n", File(dev.root, "tmp/psbak/${ready.stageId}/world.txt").readText())
        assertEquals(listOf("/bin/sh", "/tmp/psbak/${ready.stageId}/job.sh", "reinstall", "/tmp/psbak/${ready.stageId}"), fake.seen.single())
    }

    // ============================================================ script safety

    @Test
    fun `the job command line is constants plus the stage path - no user text, no shell string`() {
        val argv = BackupJob.argv(BackupJob.Op.BACKUP, BackupJob.guestStage("abcdef123456"))
        assertEquals(listOf("/bin/sh", "/tmp/psbak/abcdef123456/job.sh", "backup", "/tmp/psbak/abcdef123456"), argv)
        assertTrue(BackupJob.isValidStageId("abcdef123456"))
        listOf("", "ABC", "../x", "a/b", "short", "x".repeat(40), "with space1").forEach { assertFalse(it, BackupJob.isValidStageId(it)) }
        assertFalse("the script's dollar placeholder must be fully substituted", BackupJob.SCRIPT.contains('§'))
    }
}
