package app.pocketshell.widget.sync

import app.pocketshell.packages.ExecResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe's idle gate is read on the UI thread every tick: it must never wait on a scan that is running. */
class SyncProbeLockTest {

    private class SlowExec : SyncProbe.GuestExec {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult {
            inside.countDown()
            release.await(30, TimeUnit.SECONDS)
            return ExecResult(exitCode = 0, stdout = "", stderr = "")
        }
    }

    private fun finishesWithin(ms: Long, block: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        Thread { block(); done.countDown() }.apply { isDaemon = true; start() }
        return done.await(ms, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `the gate never blocks while a scan is running`() {
        val exec = SlowExec()
        val probe = SyncProbe(exec)
        val scan = Thread { probe.snapshot(emptyList()) }.apply { isDaemon = true; start() }
        assertTrue(exec.inside.await(5, TimeUnit.SECONDS))
        assertTrue("shouldFullScan blocked behind a running scan", finishesWithin(1_000) { probe.shouldFullScan(System.currentTimeMillis()) })
        exec.release.countDown()
        scan.join(5_000)
        assertFalse(scan.isAlive)
    }

    @Test
    fun `scans stay serialized`() {
        val exec = SlowExec()
        val probe = SyncProbe(exec)
        val first = Thread { probe.snapshot(emptyList()) }.apply { isDaemon = true; start() }
        assertTrue(exec.inside.await(5, TimeUnit.SECONDS))
        var secondRan = false
        val second = Thread { probe.snapshot(emptyList()); secondRan = true }.apply { isDaemon = true; start() }
        Thread.sleep(300)
        assertFalse("the second scan waits for the first", secondRan)
        exec.release.countDown()
        first.join(5_000); second.join(5_000)
        assertTrue(secondRan)
    }
}
