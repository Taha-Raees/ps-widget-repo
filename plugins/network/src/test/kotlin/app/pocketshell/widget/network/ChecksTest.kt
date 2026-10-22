package app.pocketshell.widget.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {

    @Test
    fun `the verdict follows the facts, most fundamental first`() {
        assertEquals(Verdict.OFFLINE, Checks.verdict(false, null, null, null))
        assertEquals(Verdict.OFFLINE, Checks.verdict(false, true, true, 204))
        assertEquals(Verdict.NO_INTERNET, Checks.verdict(true, false, true, 204))
        assertEquals(Verdict.DNS_BROKEN, Checks.verdict(true, true, false, 204))
        assertEquals(Verdict.CAPTIVE, Checks.verdict(true, true, true, 302))
        assertEquals(Verdict.CAPTIVE, Checks.verdict(true, true, true, 200))
        assertEquals(Verdict.ONLINE, Checks.verdict(true, true, true, 204))
        assertEquals("a skipped probe does not block online", Verdict.ONLINE, Checks.verdict(true, true, true, null))
        assertEquals("nothing proves the internet works", Verdict.NO_INTERNET, Checks.verdict(true, null, null, null))
    }

    @Test
    fun `dns query is a well formed question`() {
        val q = Checks.dnsQuery("example.com", 0x1234)
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(0x01, q[2].toInt()); assertEquals(0x00, q[3].toInt()) // RD set
        assertEquals(1, q[5].toInt())                                      // QDCOUNT
        // 7"example" 3"com" 0, type A, class IN
        val name = q.copyOfRange(12, q.size)
        assertEquals(7, name[0].toInt())
        assertEquals("example", String(name, 1, 7))
        assertEquals(3, name[8].toInt())
        assertEquals("com", String(name, 9, 3))
        assertEquals(0, name[12].toInt())
        assertEquals(listOf(0, 1, 0, 1), name.copyOfRange(13, 17).map { it.toInt() })
        assertEquals(12 + 17, q.size)
    }

    @Test
    fun `bad names cannot make a bad packet`() {
        for (n in listOf("", ".", "a..b", "x".repeat(64) + ".com")) {
            val failed = try { Checks.dnsQuery(n, 1); false } catch (_: IllegalArgumentException) { true }
            assertTrue("must refuse '$n'", failed)
        }
    }

    private fun resp(id: Int, flags: Int, answers: Int): ByteArray {
        val b = ByteArray(12)
        b[0] = (id shr 8).toByte(); b[1] = id.toByte()
        b[2] = (flags shr 8).toByte(); b[3] = flags.toByte()
        b[6] = (answers shr 8).toByte(); b[7] = answers.toByte()
        return b
    }

    @Test
    fun `responses are parsed by id, response bit, rcode and answer count`() {
        assertEquals(Checks.DnsAnswer.Answered(2), Checks.parseDns(resp(0x1234, 0x8180, 2), 0x1234))
        assertEquals(Checks.DnsAnswer.Answered(0), Checks.parseDns(resp(0x1234, 0x8180, 0), 0x1234))
        assertEquals(Checks.DnsAnswer.Error(3), Checks.parseDns(resp(0x1234, 0x8183, 0), 0x1234))
        assertEquals("a reply to a different question is not ours", Checks.DnsAnswer.Malformed, Checks.parseDns(resp(0x9999, 0x8180, 1), 0x1234))
        assertEquals("a query is not a response", Checks.DnsAnswer.Malformed, Checks.parseDns(resp(0x1234, 0x0100, 1), 0x1234))
        assertEquals(Checks.DnsAnswer.Malformed, Checks.parseDns(ByteArray(5), 0x1234))
        assertEquals("name does not exist", Checks.rcodeName(3))
    }

    @Test
    fun `nameservers are the ipv4 ones in order, without duplicates`() {
        val conf = "# managed by PocketShell\nnameserver 62.109.121.17\nnameserver 62.109.121.18\nnameserver 2a02:3018:0:40ff::aaaa\nnameserver 62.109.121.17\nsearch lan\nnameserver  8.8.8.8 \n"
        assertEquals(listOf("62.109.121.17", "62.109.121.18", "8.8.8.8"), Checks.nameservers(conf))
        assertTrue(Checks.nameservers("").isEmpty())
    }
}
