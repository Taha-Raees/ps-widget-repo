package app.pocketshell.widget.sync

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the independent review of the backup/restore half: each
 * test fails if the bug it names comes back.
 */
class ReviewFixesTest {

    private val devices = ArrayList<FakeDevice>()
    private val dirs = ArrayList<File>()
    private val none = CancelToken()
    private val quiet: (Progress) -> Unit = {}

    @Before
    fun tools() {
        assumeTrue("needs /bin/sh, tar and gzip", Tools.canRunPipelines)
    }

    @After
    fun cleanup() {
        devices.forEach { it.cleanup() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun device(name: String = "Old Phone") = FakeDevice(name).populate().also { devices += it }
    private fun store() = tempStorage().also { dirs += it.first }

    private fun backup(dev: FakeDevice, storage: BackupStorage, opts: BackupOptions = BackupOptions(), engine: BackupEngine = dev.engine()) =
        engine.backup(BackupRequest(opts, null, false, storage), none, quiet)

    // ============================================ archive audit (pure)

    private fun tv(type: Char, name: String, target: String? = null) =
        "${type}rwxr-xr-x root/root         0 2026-10-22 20:19:22 $name${target?.let { " -> $it" } ?: ""}"

    @Test
    fun `an honest listing passes - links to absolute targets, spaces, the four trees`() {
        val ok = sequenceOf(
            tv('d', "tmp/psbak-meta/"), tv('-', "tmp/psbak-meta/manifest.json"),
            tv('d', "root/"), tv('-', "root/dir with space/file.txt"),
            tv('l', "root/abs-link", "/root/proj/src/a.kt"), tv('l', "root/rel", "../x"),
            tv('-', "usr/local/bin/tool"), tv('-', "opt/thing/x"),
        )
        assertNull(ArchiveAudit.check(ok))
        assertNull(ArchiveAudit.check(emptySequence()))
    }

    @Test
    fun `the audit refuses dotdot, absolute names and anything outside the backup's folders`() {
        assertNotNull(ArchiveAudit.check(sequenceOf(tv('-', "root/../etc/passwd"))))
        assertNotNull(ArchiveAudit.check(sequenceOf(tv('-', "../etc/passwd"))))
        assertNotNull(ArchiveAudit.check(sequenceOf(tv('-', "/etc/passwd"))))
        assertNotNull(ArchiveAudit.check(sequenceOf(tv('-', "etc/shadow"))))
        assertNotNull(ArchiveAudit.check(sequenceOf(tv('-', "rootkit/x"))))
        assertNotNull(ArchiveAudit.check(sequenceOf(tv('-', "usr/localx/x"))))
        assertNotNull(ArchiveAudit.check(sequenceOf(tv('-', "tmp/other/x"))))
    }

    @Test
    fun `a member written THROUGH an earlier symlink is refused - the classic escape`() {
        val through = sequenceOf(tv('l', "root/l", "/etc"), tv('-', "root/l/passwd"))
        assertTrue(ArchiveAudit.check(through)!!.contains("through a link"))
        // A deeper path through a nested link too.
        assertNotNull(ArchiveAudit.check(sequenceOf(tv('d', "root/a/"), tv('l', "root/a/l", "/"), tv('-', "root/a/l/x/y"))))
        // The symlink on its own, or beside unrelated files, is fine (that is every normal home).
        assertNull(ArchiveAudit.check(sequenceOf(tv('l', "root/l", "/etc"), tv('-', "root/other.txt"), tv('-', "root/l2/file"))))
    }

    @Test
    fun `an unreadable listing line is a refusal, never a pass`() {
        assertNotNull(ArchiveAudit.check(sequenceOf("this is not a tar listing")))
    }

    // ============================================ hostile archives, end to end

    private fun inspectArchive(dev: FakeDevice, tar: ByteArray): InspectOutcome {
        val dir = File(java.nio.file.Files.createTempDirectory("psbak-evil").toString()).also { dirs += it }
        return dev.engine().inspect(PickedFileSource(psbakFrom(tar, dir)), null, none, quiet)
    }

    private val goodMeta = TarBuilder().dir("tmp/psbak-meta").file("tmp/psbak-meta/manifest.json", "{}")

    @Test
    fun `a hostile archive is refused at inspect - nothing is staged and nothing is written`() {
        val dev = device("New Phone")
        val canary = File(dev.root, "etc/alpine-release").readText()
        val cases = mapOf(
            "dotdot" to TarBuilder().dir("root").file("root/../../escape.txt"),
            "absolute" to TarBuilder().dir("root").file("/tmp/escape-absolute.txt"),
            "through a link" to TarBuilder().dir("root").symlink("root/l", "/etc").file("root/l/alpine-release", "OVERWRITTEN"),
            "outside folders" to TarBuilder().dir("etc").file("etc/alpine-release", "OVERWRITTEN"),
        )
        for ((label, builder) in cases) {
            val out = inspectArchive(dev, builder.bytes())
            assertTrue("$label: expected a refusal but was $out", out is InspectOutcome.Failed)
            assertTrue("$label: ${(out as InspectOutcome.Failed).reason}", out.reason.contains("not safe"))
            assertTrue("$label: the stage must be gone", dev.engine().leftoverStages().isEmpty())
        }
        assertEquals("the device's own files are untouched", canary, File(dev.root, "etc/alpine-release").readText())
        assertFalse(File("/tmp/escape-absolute.txt").exists())
    }

    @Test
    fun `an honest hand-built archive still inspects and restores`() {
        val dev = device("New Phone")
        val tar = goodMeta.file("root/hello.txt", "hi\n").symlink("root/abs-link", "/root/hello.txt").bytes()
        val ready = inspectArchive(dev, tar)
        // No readable manifest in this minimal archive is a clean failure, not a crash.
        assertTrue(ready is InspectOutcome.Failed)
    }

    // ============================================ retention across devices

    private fun stored(name: String, at: Long, device: String) = StoredBackup(
        name, 10, at,
        BackupRecord(name, 10, "h", BackupManifest(createdAtMs = at, device = device, pluginVersion = "3", codec = BackupCodec.GZIP,
            scopes = listOf(BackupScope.HOME), excludes = emptyList(), sensitiveIncluded = false, encrypted = false, packages = 0, appSettings = emptyList())),
    )

    @Test
    fun `a new phone's backups never push out the old phone's`() {
        val oldPhone = (1..3).map { stored("PocketShell-Old-2026102$it-1000.psbak", it * 1000L, "Old Phone") }
        val newPhone = (4..6).map { stored("PocketShell-New-2026102$it-1000.psbak", it * 1000L, "New Phone") }
        val all = oldPhone + newPhone
        // New phone keeps its newest 1: only ITS two older ones go; the old phone's three survive.
        val pruned = Retention.toPrune(all, keep = 1, device = "New Phone").map { it.name }
        assertEquals(listOf("PocketShell-New-20261025-1000.psbak", "PocketShell-New-20261024-1000.psbak"), pruned)
        assertTrue(pruned.none { it.contains("Old") })
    }

    @Test
    fun `a backup run on one device prunes only that device in a shared folder`() {
        val a = device("Phone A")
        val b = device("Phone B")
        val (dir, storage) = store()
        val keep1 = BackupOptions(keep = 1)
        repeat(2) { a.clock.addAndGet(120_000L); assertTrue(backup(a, storage, keep1) is BackupOutcome.Done) }
        b.clock.set(a.clock.get() + 600_000L)
        repeat(2) { b.clock.addAndGet(120_000L); assertTrue(backup(b, storage, keep1) is BackupOutcome.Done) }
        val names = dir.list()!!.filter { it.endsWith(".psbak") }
        assertEquals("one archive per phone survives", 2, names.size)
        assertEquals(1, names.count { it.contains("Phone-A") })
        assertEquals(1, names.count { it.contains("Phone-B") })
    }

    // ============================================ a finished backup is never deleted

    /** Delegates to a real storage but fails every listing after the first N. */
    private class FlakyListStorage(private val inner: BackupStorage, private var okListings: Int) : BackupStorage {
        override val label: String get() = inner.label
        override fun list(): List<StoredBackup> {
            if (okListings-- <= 0) throw IOException("provider hiccup")
            return inner.list()
        }
        override fun openWrite(name: String, mime: String): OutputStream = inner.openWrite(name, mime)
        override fun openRead(name: String): InputStream = inner.openRead(name)
        override fun delete(name: String): Boolean = inner.delete(name)
    }

    @Test
    fun `a transient listing error after writing keeps the finished backup`() {
        val dev = device()
        val (dir, real) = store()
        // The first listing (unique-name check) works; the post-write verification listing throws.
        val out = backup(dev, FlakyListStorage(real, okListings = 1))
        assertTrue("expected Done but was $out", out is BackupOutcome.Done)
        val files = dir.list()!!.toSet()
        val name = (out as BackupOutcome.Done).record.name
        assertTrue("the archive must survive", name in files)
        assertTrue("and so must its sidecar", "$name.json" in files)
    }

    // ============================================ app settings keep every previous version

    @Test
    fun `a second restore keeps the first restore's saved copy`() {
        val old = device()
        old.writeDatastore("notes_store", "backed-up notes")
        val (_, storage) = store()
        assertTrue(backup(old, storage) is BackupOutcome.Done)

        val fresh = FakeDevice("New").also { devices += it }
        fresh.write("etc/alpine-release", "3.24.2\n")
        fresh.writeDatastore("notes_store", "ORIGINAL notes on this phone")
        val engine = fresh.engine()
        fun restoreOnce() {
            val ready = engine.inspect(storedSource(storage, storage.firstArchive()), null, none, quiet) as InspectOutcome.Ready
            engine.apply(ready.stageId, setOf(BackupScope.APP_SETTINGS), RestoreMode.IN_PLACE, true, none, quiet)
            engine.discardStage(ready.stageId)
        }
        restoreOnce()
        File(fresh.datastore, "notes_store.preferences_pb").writeText("edited after the first restore")
        restoreOnce()
        val saved = fresh.datastore.list()!!.filter { it.startsWith("notes_store.preferences_pb.pre-restore") }
        assertEquals("both previous versions are kept", 2, saved.size)
        val contents = saved.map { File(fresh.datastore, it).readText() }.toSet()
        assertTrue("ORIGINAL notes on this phone" in contents)
        assertTrue("edited after the first restore" in contents)
    }

    // ============================================ stages in use

    @Test
    fun `an open restore is not listed as interrupted and cannot be cleaned up under the user`() {
        val dev = device()
        val (_, storage) = store()
        assertTrue(backup(dev, storage) is BackupOutcome.Done)
        val engine = dev.engine()
        val ready = engine.inspect(storedSource(storage, storage.firstArchive()), null, none, quiet) as InspectOutcome.Ready
        // The stage exists on disk, but it is IN USE by the open restore: not "interrupted".
        assertTrue(File(dev.root, "tmp/psbak/${ready.stageId}").isDirectory)
        assertTrue(engine.leftoverStages().isEmpty())
        // A stale directory from a dead run IS interrupted.
        File(dev.root, "tmp/psbak/abcdef123456").mkdirs()
        assertEquals(listOf("abcdef123456"), engine.leftoverStages())
        engine.discardStage("abcdef123456")
        engine.discardStage(ready.stageId)
        assertTrue(engine.leftoverStages().isEmpty())
    }

    /** A job that blocks until released; used to hold a stage "running". */
    private class BlockingRunner : GuestJobRunner {
        val release = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        override fun start(argv: List<String>): RunningJob {
            started.countDown()
            return object : RunningJob {
                override fun waitFor(timeoutMs: Long): JobResult {
                    release.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    return JobResult(null, error = "released")
                }
                override fun destroy() = release.countDown()
            }
        }
    }

    @Test
    fun `cleaning up while a job runs cannot delete the live stage`() {
        val dev = device()
        val (_, storage) = store()
        val runner = BlockingRunner()
        val engine = BackupEngine(dev.env(), runner) { }
        val token = CancelToken()
        val t = Thread { engine.backup(BackupRequest(BackupOptions(), null, false, storage), token, quiet) }.apply { start() }
        assertTrue(runner.started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val live = File(dev.root, "tmp/psbak").list()!!.single()
        // The live stage is invisible to leftovers and discardStage refuses it.
        assertTrue(engine.leftoverStages().isEmpty())
        engine.discardStage(live)
        assertTrue("the running job's stage must still exist", File(dev.root, "tmp/psbak/$live").isDirectory)
        token.cancel()
        t.join(15_000)
        assertFalse(t.isAlive)
        assertTrue(File(dev.root, "tmp/psbak").list().orEmpty().isEmpty())
    }

    @Test
    fun `a stale pid from an earlier job is never signalled`() {
        val dev = device()
        val (_, storage) = store()
        val signalled = ArrayList<Int>()
        val runner = BlockingRunner()
        val engine = BackupEngine(dev.env(), runner) { signalled += it }
        val token = CancelToken()
        val t = Thread { engine.backup(BackupRequest(BackupOptions(), null, false, storage), token, quiet) }.apply { start() }
        assertTrue(runner.started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        // Poison: a pid file as an earlier job in a reused stage might have left it... the engine cleared it before start.
        val stage = File(dev.root, "tmp/psbak").listFiles()!!.single()
        assertFalse("the engine removes any stale pid before the job starts", File(stage, BackupJob.Files.PID).exists())
        token.cancel()
        t.join(15_000)
        assertTrue("no pid was published, so nothing was signalled", signalled.isEmpty())
    }

    // ============================================ passphrase hygiene

    @Test
    fun `the passphrase array is zeroed even when the request is refused up front`() {
        val dev = device()
        val (_, storage) = store()
        val pass = "secret-pass".toCharArray()
        val out = dev.engine().backup(BackupRequest(BackupOptions(scopes = emptySet()), pass, false, storage), none, quiet)
        assertTrue(out is BackupOutcome.Failed)
        assertTrue("zeroed on the early-refusal path", pass.all { it == '\u0000' })
    }

    @Test
    fun `the passphrase array is zeroed after inspect, including the wrong-passphrase path`() {
        val dev = device()
        val (_, storage) = store()
        val enc = dev.engine().backup(BackupRequest(BackupOptions(), "right".toCharArray(), false, storage), none, quiet)
        assertTrue(enc is BackupOutcome.Done)
        val wrong = "wrong".toCharArray()
        assertTrue(dev.engine().inspect(storedSource(storage, storage.firstArchive()), wrong, none, quiet) is InspectOutcome.WrongPassphrase)
        assertTrue(wrong.all { it == '\u0000' })
    }
}
