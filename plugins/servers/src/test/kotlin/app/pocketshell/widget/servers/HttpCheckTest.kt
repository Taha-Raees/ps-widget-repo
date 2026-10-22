package app.pocketshell.widget.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HTTP check's classification + the session beat ring. */
class HttpCheckTest {

    @Test
    fun `bands follow the status page convention`() {
        assertEquals(HttpBand.OK, HttpChecker.band(200))
        assertEquals(HttpBand.OK, HttpChecker.band(204))
        assertEquals(HttpBand.REDIRECT, HttpChecker.band(301))
        assertEquals(HttpBand.REDIRECT, HttpChecker.band(302))
        assertEquals(HttpBand.CLIENT_ERROR, HttpChecker.band(404))
        assertEquals(HttpBand.SERVER_ERROR, HttpChecker.band(500))
        assertEquals(HttpBand.SERVER_ERROR, HttpChecker.band(503))
    }

    @Test
    fun `implausible codes classify to nothing`() {
        assertNull(HttpChecker.band(99))
        assertNull(HttpChecker.band(600))
        assertNull(HttpChecker.band(0))
    }

    @Test
    fun `out of range ports fail before any connection`() {
        val result = HttpChecker.check(port = 0)
        assertTrue(result is HttpResult.Failed)
    }

    @Test
    fun `the ring caps and evicts the oldest`() {
        val history = BeatHistory(cap = 3)
        fun beat(ok: Boolean) = BeatHistory.Beat(ok = ok, code = 200, latencyMs = 1, atMs = 0)
        history.record(beat(true))
        history.record(beat(true))
        history.record(beat(false))
        history.record(beat(true))
        val beats = history.beats()
        assertEquals(3, beats.size)
        // The FIRST check was evicted; what survives is [ok, failed, ok].
        assertTrue(beats[0].ok)
        assertFalse(beats[1].ok)
        assertTrue(beats[2].ok)
    }

    @Test
    fun `uptimeFraction is null before any check`() {
        assertNull(BeatHistory().uptimeFraction())
    }

    @Test
    fun `uptimeFraction is the ok share`() {
        val history = BeatHistory()
        fun beat(ok: Boolean) = BeatHistory.Beat(ok = ok, code = if (ok) 200 else 500, latencyMs = 1, atMs = 0)
        history.record(beat(true))
        history.record(beat(true))
        history.record(beat(true))
        history.record(beat(false))
        assertEquals(0.75f, history.uptimeFraction()!!, 0.001f)
    }
}
