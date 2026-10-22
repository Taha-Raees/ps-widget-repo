package app.pocketshell.widget.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The /proc parsers, against fixtures shaped like the real files —
 * including the comm field that contains spaces and parens (the reason
 * the parser splits on the LAST ')').
 */
class ProcParsingTest {

    private val statFixture =
        "4321 (node server:comp) S 1 4321 4321 0 -1 4194560 " +
            "100 0 0 0 250 50 0 0 20 0 8 0 12345 234567890 5120 " +
            "18446744073709551615 0 0 0 0 0 0 0 0 0"

    @Test
    fun `parseStat reads ticks and rss after the parenthesised comm`() {
        val sample = ProcParsing.parseStat(pid = 4321, text = statFixture, atMs = 1_000L)
        assertNotNull(sample)
        sample!!
        assertEquals(4321, sample.pid)
        assertEquals(300L, sample.ticksTotal) // utime 250 + stime 50
        assertEquals(5120L * ProcParsing.PAGE_BYTES, sample.rssBytes)
        assertEquals(1_000L, sample.atMs)
    }

    @Test
    fun `parseStat rejects a shape without a comm tail`() {
        assertNull(ProcParsing.parseStat(1, "no parens here", 0L))
        assertNull(ProcParsing.parseStat(1, "1 () S", 0L))
    }

    @Test
    fun `cpuPercent is busy ticks over wall ticks`() {
        val prev = ProcessSample(pid = 1, ticksTotal = 300, rssBytes = 0, atMs = 0)
        val now = ProcessSample(pid = 1, ticksTotal = 400, rssBytes = 0, atMs = 1_000)
        // 100 ticks in 1 s at 100 Hz = exactly one core.
        assertEquals(100.0f, ProcParsing.cpuPercent(prev, now)!!, 0.01f)
    }

    @Test
    fun `cpuPercent exceeds one core for a threaded server`() {
        val prev = ProcessSample(pid = 1, ticksTotal = 0, rssBytes = 0, atMs = 0)
        val now = ProcessSample(pid = 1, ticksTotal = 250, rssBytes = 0, atMs = 1_000)
        assertEquals(250.0f, ProcParsing.cpuPercent(prev, now)!!, 0.01f)
    }

    @Test
    fun `cpuPercent refuses short intervals and backwards ticks`() {
        val base = ProcessSample(pid = 1, ticksTotal = 300, rssBytes = 0, atMs = 0)
        assertNull(ProcParsing.cpuPercent(base, base.copy(atMs = 100)))
        assertNull(ProcParsing.cpuPercent(base, base.copy(ticksTotal = 100, atMs = 1_000)))
    }

    @Test
    fun `parseArgv joins the NUL-separated command line`() {
        val argv = "node\u0000server.js\u0000--port\u00003000".toByteArray()
        assertEquals("node server.js --port 3000", ProcParsing.parseArgv(argv))
        assertNull(ProcParsing.parseArgv(ByteArray(0)))
        assertNull(ProcParsing.parseArgv("\u0000\u0000".toByteArray()))
    }

    @Test
    fun `parseLoadAvg reads the three averages`() {
        val load = SystemParsing.parseLoadAvg("0.52 0.58 0.59 1/467 12345")
        assertNotNull(load)
        assertEquals(0.52f, load!!.one, 0.001f)
        assertEquals(0.58f, load.five, 0.001f)
        assertEquals(0.59f, load.fifteen, 0.001f)
        assertNull(SystemParsing.parseLoadAvg("0.52"))
        assertNull(SystemParsing.parseLoadAvg("garbage"))
    }

    @Test
    fun `parseMemInfo needs MemTotal and MemAvailable`() {
        val mem = SystemParsing.parseMemInfo(
            """
            MemTotal:        4000000 kB
            MemFree:          100000 kB
            MemAvailable:    2600000 kB
            Buffers:           50000 kB
            """.trimIndent(),
        )
        assertNotNull(mem)
        assertEquals(4_000_000L, mem!!.totalKb)
        assertEquals(2_600_000L, mem.availableKb)
        assertEquals(1_400_000L, mem.usedKb)
        assertNull(SystemParsing.parseMemInfo("MemTotal: 4000000 kB\n"))
    }

    @Test
    fun `parseUptimeSeconds reads the first field`() {
        assertEquals(12_345.67, SystemParsing.parseUptimeSeconds("12345.67 45678.90")!!, 0.001)
        assertNull(SystemParsing.parseUptimeSeconds("later"))
    }
}
