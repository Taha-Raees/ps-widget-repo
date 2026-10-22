package app.pocketshell.widget.git

import app.pocketshell.packages.ExecResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE freeze: while a scan ran, the UI thread — reading the gate on every
 * recomposition and every tick — blocked on the lock the scan held for its
 * whole guest exec. Returning to the Git card froze Home until the scan ended,
 * and an ANR killed the app when it ran long. The gate must answer instantly
 * even in the middle of a scan.
 */
class GitProbeLockTest {

    private val okOutput = ExecResult(exitCode = 0, stdout = "@@GIT:2.54.0\n@@DONE\n", stderr = "")

    /** An exec that sits inside the scan until released — a slow guest. */
    private class SlowExec(private val result: ExecResult) : GitProbe.GuestExec {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun exec(guestCommand: List<String>, timeoutMs: Long): ExecResult {
            inside.countDown()
            release.await(30, TimeUnit.SECONDS)
            return result
        }
    }

    /** Runs [block] on its own thread and reports whether it finished within [ms] — i.e. did NOT block. */
    private fun finishesWithin(ms: Long, block: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        Thread { block(); done.countDown() }.apply { isDaemon = true; start() }
        return done.await(ms, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `the gate never blocks while a scan is running`() {
        val exec = SlowExec(okOutput)
        val probe = GitProbe(exec)
        val scan = Thread { probe.snapshot() }.apply { isDaemon = true; start() }
        assertTrue("the scan must be inside the exec", exec.inside.await(5, TimeUnit.SECONDS))

        // Everything the UI thread touches, mid-scan: each must answer at once.
        assertTrue("shouldFullScan blocked behind a running scan", finishesWithin(1_000) { probe.shouldFullScan(System.currentTimeMillis()) })
        assertTrue("lastScanAtMs blocked behind a running scan", finishesWithin(1_000) { probe.lastScanAtMs })
        assertTrue("cached blocked behind a running scan", finishesWithin(1_000) { probe.cached })
        assertTrue("invalidate blocked behind a running scan", finishesWithin(1_000) { probe.invalidate() })

        exec.release.countDown()
        scan.join(5_000)
        assertFalse(scan.isAlive)
    }

    @Test
    fun `scans are still serialized - two never run the guest at once`() {
        val exec = SlowExec(okOutput)
        val probe = GitProbe(exec)
        val first = Thread { probe.snapshot() }.apply { isDaemon = true; start() }
        assertTrue(exec.inside.await(5, TimeUnit.SECONDS))
        var secondRan = false
        val second = Thread { probe.snapshot(); secondRan = true }.apply { isDaemon = true; start() }
        Thread.sleep(300)
        assertFalse("the second scan must wait for the first", secondRan)
        exec.release.countDown()
        first.join(5_000)
        second.join(5_000)
        assertTrue(secondRan)
    }

    @Test
    fun `the gate and the cache still tell the truth after the scan`() {
        var now = 1_000L
        val probe = GitProbe(object : GitProbe.GuestExec {
            override fun exec(guestCommand: List<String>, timeoutMs: Long) = okOutput
        }) { now }
        assertTrue("the first tick always scans", probe.shouldFullScan(now))
        val r = probe.snapshot()
        assertTrue(r is ScanResult.Done)
        assertNotNull(probe.cached)
        assertEquals(1_000L, probe.lastScanAtMs)
        assertFalse(probe.shouldFullScan(now + GitProbe.AUTO_RESCAN_MS - 1))
        assertTrue(probe.shouldFullScan(now + GitProbe.AUTO_RESCAN_MS))
        probe.invalidate()
        assertTrue("invalidate forces the next tick to scan", probe.shouldFullScan(now))
    }
}
