package app.pocketshell.widget.sync

import app.pocketshell.packages.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8.4 — the probe layer: the batched script's parse protocol, the argv
 * discipline (user specs ride as positional parameters / direct argv —
 * NEVER interpolated into a shell string), the honest failures, and the
 * idle gate.
 */
class SyncProbeTest {

    private fun exec(ok: Boolean = true, stdout: String = "", stderr: String = "") =
        ExecResult(exitCode = if (ok) 0 else 1, stdout = stdout, stderr = stderr)

    private class RecordingExec(var result: ExecResult) : SyncProbe.GuestExec {
        val argvs = ArrayList<List<String>>()
        val timeouts = ArrayList<Long>()

        override fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult {
            argvs += guestCommand
            timeouts += timeoutMs
            return result
        }
    }

    private fun probe(exec: SyncProbe.GuestExec) = SyncProbe(exec)

    private fun sampleProfile(
        id: String = "p1",
        backend: SyncBackend = SyncBackend.RSYNC,
        source: String = "/root/project",
        destination: String = "/mnt/backup",
        excludes: List<String> = emptyList(),
    ) = SyncProfile(id, backend, source, destination, createdAtMs = 1L, excludes = excludes)

    // ------------------------------------------------------- parse: backends

    @Test
    fun `both backends present - paths and versions parsed`() {
        val out = parseProbeOutput(
            """
            @@RSYNC:/usr/bin/rsync
            @@RSYNCV:3.5.0
            @@RCLONE:/usr/bin/rclone
            @@RCLONEV:v1.74.1
            @@DONE
            """.trimIndent(),
        )
        assertNotNull(out)
        out!!
        assertTrue(out.complete)
        assertEquals("/usr/bin/rsync", out.rsync.path)
        assertEquals("3.5.0", out.rsync.version)
        assertEquals("/usr/bin/rclone", out.rclone.path)
        assertEquals("v1.74.1", out.rclone.version)
    }

    @Test
    fun `both backends absent - honest nulls, still a complete probe`() {
        val out = parseProbeOutput("@@RSYNC:\n@@RCLONE:\n@@DONE\n")
        assertNotNull(out)
        out!!
        assertTrue(out.complete)
        assertNull(out.rsync.path)
        assertNull(out.rsync.version)
        assertNull(out.rclone.path)
    }

    @Test
    fun `unrecognized output is not data`() {
        assertNull(parseProbeOutput("rsync  version 3.5.0  protocol version 32"))
        assertNull(parseProbeOutput(""))
    }

    @Test
    fun `a stream cut before @@DONE is marked incomplete - output not trusted`() {
        val out = parseProbeOutput("@@RSYNC:/usr/bin/rsync\n@@RCLONE:\n@@PAIR\n@@SRC:ok\n")
        assertNotNull(out)
        assertFalse(out!!.complete)
    }

    // ------------------------------------------------------- parse: pairs

    @Test
    fun `path pairs come back in request order with remote specs unstated`() {
        val out = parseProbeOutput(
            """
            @@RSYNC:/usr/bin/rsync
            @@RSYNCV:3.5.0
            @@RCLONE:
            @@PAIR
            @@SRC:ok
            @@DST:missing
            @@PAIR
            @@SRC:remote
            @@DST:remote
            @@DONE
            """.trimIndent(),
        )
        assertNotNull(out)
        out!!
        assertEquals(2, out.pairs.size)
        assertEquals(true, out.pairs[0].sourceExists)
        assertEquals(false, out.pairs[0].destinationExists)
        // Remote specs are never stat-ed: the existence answer is "unknown".
        assertNull(out.pairs[1].sourceExists)
        assertNull(out.pairs[1].destinationExists)
        assertTrue(out.pairs[1].sourceRemote)
    }

    @Test
    fun `an interrupted pair block is dropped - not half-trusted`() {
        val out = parseProbeOutput(
            "@@RSYNC:\n@@RCLONE:\n@@PAIR\n@@SRC:ok\n@@DONE\n",
        )
        assertNotNull(out)
        assertEquals(0, out!!.pairs.size)
    }

    // ------------------------------------------------- snapshot + argv shape

    @Test
    fun `the probe passes profile specs as positional argv - never into the script`() {
        val recorder = RecordingExec(exec(stdout = "@@RSYNC:\n@@RCLONE:\n@@DONE\n"))
        val profiles = listOf(
            sampleProfile(source = "/root/my dir/x", destination = "user@host:/a b"),
            sampleProfile(id = "p2", source = "/root/'; rm -rf /", destination = "/mnt/b"),
        )
        probe(recorder).snapshot(profiles)

        val argv = recorder.argvs.single()
        // The script is a FIXED constant: no profile bytes anywhere in it.
        assertEquals("/bin/sh", argv[0])
        assertEquals("-c", argv[1])
        assertEquals(SyncProbe.PROBE_SCRIPT, argv[2])
        assertEquals("sh", argv[3])
        // The specs follow verbatim, in (source, destination) order.
        assertEquals("/root/my dir/x", argv[4])
        assertEquals("user@host:/a b", argv[5])
        assertEquals("/root/'; rm -rf /", argv[6])
        assertEquals("/mnt/b", argv[7])
        assertEquals(SyncProbe.PROBE_TIMEOUT_MS, recorder.timeouts.single())
    }

    @Test
    fun `a failed exec is a Failed probe - never an empty result`() {
        val p = probe(RecordingExec(exec(ok = false, stderr = "proot: fatal")))
        val result = p.snapshot(listOf(sampleProfile()))
        assertTrue(result is ProbeResult.Failed)
        assertEquals("proot: fatal", (result as ProbeResult.Failed).reason)
    }

    @Test
    fun `a timeout error is a Failed probe`() {
        val p = probe(
            RecordingExec(ExecResult(exitCode = null, stdout = "", stderr = "", error = "timed out")),
        )
        val result = p.snapshot(emptyList())
        assertTrue(result is ProbeResult.Failed)
    }

    @Test
    fun `a pair-count mismatch (truncation) is a Failed probe`() {
        val recorder = RecordingExec(exec(stdout = "@@RSYNC:\n@@RCLONE:\n@@PAIR\n@@SRC:ok\n@@DST:ok\n@@DONE\n"))
        val result = probe(recorder).snapshot(
            listOf(sampleProfile(), sampleProfile(id = "p2")),
        )
        assertTrue(result is ProbeResult.Failed)
    }

    // ----------------------------------------------------------- idle gate

    @Test
    fun `the idle gate - first call scans, too-soon calls do not`() {
        val time = longArrayOf(1_000L)
        val p = SyncProbe(RecordingExec(exec())) { time[0] }
        assertTrue(p.shouldFullScan(time[0])) // never scanned
        p.snapshot(emptyList()) // stamps lastScanAtMs from the injected clock
        time[0] += 1_000
        assertFalse(p.shouldFullScan(time[0]))
        time[0] += SyncProbe.AUTO_RESCAN_MS
        assertTrue(p.shouldFullScan(time[0]))
    }

    // ------------------------------------------------------------- dry run

    @Test
    fun `rsync dry run - exact argv and parsed preview`() {
        val recorder = RecordingExec(exec(stdout = "sending incremental file list\n>f+++++++++ a.txt\n"))
        val p = probe(recorder)
        val result = p.dryRun(
            sampleProfile(source = "/root/my dir", destination = "/mnt/backup"),
            rsyncPath = "/usr/bin/rsync",
            rclonePath = null,
        )
        val argv = recorder.argvs.single()
        assertEquals(
            listOf("/usr/bin/rsync", "-n", "--itemize-changes", "--", "/root/my dir", "/mnt/backup"),
            argv,
        )
        assertEquals(SyncProbe.DRY_RUN_TIMEOUT_MS, recorder.timeouts.single())
        assertTrue(result is DryRunResult.Done)
        assertEquals(1, (result as DryRunResult.Done).preview.newCount)
    }

    @Test
    fun `rclone dry run - exact argv and parsed preview`() {
        val recorder = RecordingExec(exec(stdout = "+ a.txt\n= b.txt\n"))
        val p = probe(recorder)
        val result = p.dryRun(
            sampleProfile(backend = SyncBackend.RCLONE, source = "/root/src", destination = "gdrive:backup"),
            rsyncPath = null,
            rclonePath = "/usr/bin/rclone",
        )
        assertEquals(
            listOf("/usr/bin/rclone", "sync", "/root/src", "gdrive:backup", "--dry-run", "--combined", "-"),
            recorder.argvs.single(),
        )
        assertTrue(result is DryRunResult.Done)
        val preview = (result as DryRunResult.Done).preview
        assertEquals(1, preview.newCount)
        assertEquals(1, preview.unchangedCount)
    }

    @Test
    fun `an absent backend fails honestly - nothing is spawned`() {
        val recorder = RecordingExec(exec())
        val p = probe(recorder)
        val result = p.dryRun(sampleProfile(), rsyncPath = null, rclonePath = null)
        assertTrue(result is DryRunResult.Failed)
        assertTrue((result as DryRunResult.Failed).reason.contains("not installed"))
        assertTrue(recorder.argvs.isEmpty())
    }

    @Test
    fun `a non-zero dry-run exit reports the tool's real stderr`() {
        val recorder = RecordingExec(exec(ok = false, stderr = "rsync: link_stat src failed\n"))
        val result = probe(recorder).dryRun(sampleProfile(), rsyncPath = "/usr/bin/rsync", rclonePath = null)
        assertTrue(result is DryRunResult.Failed)
        assertEquals("rsync: link_stat src failed", (result as DryRunResult.Failed).reason)
    }

    @Test
    fun `a failed rclone dry-run surfaces rclone's stderr too`() {
        val recorder = RecordingExec(exec(ok = false, stderr = "NOTICE: Config file not found\n"))
        val result = probe(recorder).dryRun(
            sampleProfile(backend = SyncBackend.RCLONE),
            rsyncPath = null,
            rclonePath = "/usr/bin/rclone",
        )
        assertTrue(result is DryRunResult.Failed)
        assertEquals("NOTICE: Config file not found", (result as DryRunResult.Failed).reason)
    }

    // ------------------------------------------------------- run now (M8.4.1)

    @Test
    fun `run now carries profile excludes - rsync and rclone shapes`() {
        val recorder = RecordingExec(exec(stdout = "x\n"))
        probe(recorder).runNow(
            sampleProfile(
                excludes = listOf(".cache", "node_modules"),
                destination = "/mnt/backup/Projects",
            ),
            rsyncPath = "/usr/bin/rsync",
            rclonePath = null,
        )
        assertEquals(
            listOf(
                "/usr/bin/rsync", "-a", "--info=stats1",
                "--exclude=.cache", "--exclude=node_modules",
                "--", "/root/project", "/mnt/backup/Projects",
            ),
            recorder.argvs.single(),
        )
        val rcloneRecorder = RecordingExec(exec(stdout = "Transferred: 0 B\n"))
        probe(rcloneRecorder).runNow(
            sampleProfile(
                backend = SyncBackend.RCLONE,
                destination = "gdrive:backup",
                excludes = listOf(".cache"),
            ),
            rsyncPath = null,
            rclonePath = "/usr/bin/rclone",
        )
        assertEquals(
            listOf("/usr/bin/rclone", "copy", "--exclude", ".cache", "/root/project", "gdrive:backup"),
            rcloneRecorder.argvs.single(),
        )
    }

    @Test
    fun `run now rsync - additive archive argv, no delete flag, bounded`() {
        val recorder = RecordingExec(
            exec(stdout = "sending incremental file list\ntotal size is 1,234  speedup is 1.00\n"),
        )
        val result = probe(recorder).runNow(
            sampleProfile(source = "/root/my dir", destination = "/mnt/backup"),
            rsyncPath = "/usr/bin/rsync",
            rclonePath = null,
        )
        assertEquals(
            listOf("/usr/bin/rsync", "-a", "--info=stats1", "--", "/root/my dir", "/mnt/backup"),
            recorder.argvs.single(),
        )
        assertEquals(SyncProbe.RUN_TIMEOUT_MS, recorder.timeouts.single())
        assertEquals(0, result.exitCode)
        assertTrue(result.summary.contains("exit 0"))
        // The summary's fact line is the tool's last real output line — the
        // busywork header is skipped, the number is not.
        assertTrue(result.summary.contains("total size is 1,234"))
        assertFalse(result.summary.contains("sending incremental"))
        // The additive contract, pinned at the argv level too.
        assertTrue(recorder.argvs.single().none { it.contains("delete") })
    }

    @Test
    fun `run now rclone - copy, never sync`() {
        val recorder = RecordingExec(exec(stdout = "Transferred: 0 B\n"))
        val result = probe(recorder).runNow(
            sampleProfile(backend = SyncBackend.RCLONE, destination = "gdrive:backup"),
            rsyncPath = null,
            rclonePath = "/usr/bin/rclone",
        )
        assertEquals(
            listOf("/usr/bin/rclone", "copy", "/root/project", "gdrive:backup"),
            recorder.argvs.single(),
        )
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `run now with an absent backend fails honestly - nothing is spawned`() {
        val recorder = RecordingExec(exec())
        val result = probe(recorder).runNow(sampleProfile(), rsyncPath = null, rclonePath = null)
        assertNull(result.exitCode)
        assertTrue(result.summary.contains("not installed"))
        assertTrue(recorder.argvs.isEmpty())
    }

    @Test
    fun `a non-zero run reports the exit and the tool's stderr`() {
        val recorder = RecordingExec(exec(ok = false, stderr = "rsync: change_dir \"/mnt\" failed\n"))
        val result = probe(recorder).runNow(sampleProfile(), rsyncPath = "/usr/bin/rsync", rclonePath = null)
        assertEquals(1, result.exitCode)
        assertTrue(result.summary.contains("exit 1"))
        assertTrue(result.summary.contains("rsync: change_dir"))
    }

    @Test
    fun `run now captures the tool's full output for the stats parsers`() {
        val recorder = RecordingExec(
            exec(
                stdout = "sending incremental file list\n" +
                    "Number of regular files transferred: 3\n" +
                    "Total transferred file size: 1,234,567 bytes\n" +
                    "total size is 26,354,124  speedup is 21.33\n",
            ),
        )
        val result = probe(recorder).runNow(sampleProfile(), rsyncPath = "/usr/bin/rsync", rclonePath = null)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Number of regular files transferred: 3"))
        assertTrue(result.output.contains("Total transferred file size: 1,234,567 bytes"))
        // A failed run's stderr rides along too — the raw evidence, always.
        val failed = probe(
            RecordingExec(exec(ok = false, stderr = "rsync: change_dir \"/mnt\" failed\n")),
        ).runNow(sampleProfile(), rsyncPath = "/usr/bin/rsync", rclonePath = null)
        assertTrue(failed.output.contains("rsync: change_dir"))
    }

    // -------------------------------------------------- prepare (M8.4.4)

    @Test
    fun `prepare creates a local destination with mkdir -p`() {
        val recorder = RecordingExec(exec())
        val result = probe(recorder).prepare(sampleProfile(destination = "/mnt/backup/Projects"))
        assertEquals(listOf("/bin/mkdir", "-p", "/mnt/backup/Projects"), recorder.argvs.single())
        assertEquals(0, result.exitCode)
        assertTrue(result.summary.contains("ready"))
    }

    @Test
    fun `prepare skips remote destinations - no mkdir on a remote spec`() {
        val recorder = RecordingExec(exec())
        val result = probe(recorder).prepare(
            sampleProfile(backend = SyncBackend.RCLONE, destination = "gdrive:backup"),
        )
        assertTrue(recorder.argvs.isEmpty())
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `a failed mkdir is the honest failure`() {
        val recorder = RecordingExec(exec(ok = false, stderr = "mkdir: bad path\n"))
        val result = probe(recorder).prepare(sampleProfile())
        assertEquals(1, result.exitCode)
        assertTrue(result.summary.contains("mkdir failed"))
        assertTrue(result.summary.contains("bad path"))
    }

    // -------------------------------------------------- install (M8.4.1)

    @Test
    fun `install backend - apk add exact argv and honest success`() {
        val recorder = RecordingExec(exec(stdout = "OK: 42 packages upgraded, 3 newly installed\n"))
        val result = probe(recorder).installBackend(SyncBackend.RSYNC)
        assertEquals(listOf("/sbin/apk", "add", "rsync"), recorder.argvs.single())
        assertEquals(SyncProbe.INSTALL_TIMEOUT_MS, recorder.timeouts.single())
        assertEquals(0, result.exitCode)
        assertTrue(result.summary.contains("installed"))
        assertTrue(result.summary.contains("OK: 42 packages"))
    }

    @Test
    fun `a failed install reports apk's real stderr`() {
        val recorder = RecordingExec(exec(ok = false, stderr = "ERROR: unable to select packages\n"))
        val result = probe(recorder).installBackend(SyncBackend.RCLONE)
        assertEquals(listOf("/sbin/apk", "add", "rclone"), recorder.argvs.single())
        assertEquals(1, result.exitCode)
        assertTrue(result.summary.contains("rclone install failed"))
        assertTrue(result.summary.contains("ERROR: unable to select packages"))
    }
}
