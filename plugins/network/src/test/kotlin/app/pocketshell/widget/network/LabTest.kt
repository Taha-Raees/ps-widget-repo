package app.pocketshell.widget.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabTest {

    // ------------------------------------------------------------ addressing

    @Test fun sweepCoversTheSlash24WithoutSelfNetworkOrBroadcast() {
        val t = LabNet.sweepTargets("192.168.1.20", 24)
        assertEquals(253, t.size)
        assertFalse("192.168.1.20" in t)
        assertFalse("192.168.1.0" in t)
        assertFalse("192.168.1.255" in t)
        assertEquals("192.168.1.1", t.first())
    }

    @Test fun biggerNetworksAreCappedToTheSlash24AroundThePhone() {
        val t = LabNet.sweepTargets("10.1.7.9", 16)
        assertEquals(253, t.size)
        assertTrue(t.all { it.startsWith("10.1.7.") })
    }

    @Test fun smallerSubnetsKeepTheirOwnSize() {
        val t = LabNet.sweepTargets("192.168.4.5", 29)
        assertEquals(listOf("192.168.4.1", "192.168.4.2", "192.168.4.3", "192.168.4.4", "192.168.4.6"), t)
    }

    @Test fun nonsenseAddressesYieldNothing() {
        assertTrue(LabNet.sweepTargets("not-an-ip", 24).isEmpty())
        assertTrue(LabNet.sweepTargets("192.168.1.2", 32).isEmpty())
        assertTrue(LabNet.sweepTargets("999.1.1.1", 24).isEmpty())
    }

    @Test fun hostValidationRefusesOptionsAndShellText() {
        assertTrue(LabNet.validHost("nas.local"))
        assertTrue(LabNet.validHost("192.168.1.10"))
        assertFalse(LabNet.validHost("-oProxyCommand=x"))
        assertFalse(LabNet.validHost("a b"))
        assertFalse(LabNet.validHost("host;rm -rf"))
        assertFalse(LabNet.validHost("300.1.1.1"))
        assertFalse(LabNet.validHost(""))
    }

    // ----------------------------------------------------------------- ports

    @Test fun portSpecParsesListsAndRanges() {
        val p = PortSpec.parse("80, 22, 8000-8002, 22") as PortSpec.Parsed.Ports
        assertEquals(listOf(22, 80, 8000, 8001, 8002), p.ports)
    }

    @Test fun portSpecRefusesBadInput() {
        assertTrue(PortSpec.parse("") is PortSpec.Parsed.Bad)
        assertTrue(PortSpec.parse("0") is PortSpec.Parsed.Bad)
        assertTrue(PortSpec.parse("70000") is PortSpec.Parsed.Bad)
        assertTrue(PortSpec.parse("90-80") is PortSpec.Parsed.Bad)
        assertTrue(PortSpec.parse("a-b") is PortSpec.Parsed.Bad)
        assertTrue(PortSpec.parse("1-65535") is PortSpec.Parsed.Bad)
    }

    @Test fun guessUsesOpenPorts() {
        assertEquals("Home Assistant", PortSpec.guess(setOf(8123, 22)))
        assertEquals("Router", PortSpec.guess(setOf(53, 80)))
        assertEquals("SSH host", PortSpec.guess(setOf(22)))
        assertEquals("Device", PortSpec.guess(emptySet()))
    }

    // ------------------------------------------------------------------- DNS

    /** A hand-built response: question example.com A, one compressed answer 93.184.216.34 ttl 300. */
    private fun response(rcode: Int = 0, flagsExtra: Int = 0): ByteArray {
        val q = Dns.query("example.com", 0x1234, 1)
        val header = q.copyOf(12)
        header[2] = (0x81 or (flagsExtra shr 8)).toByte()
        header[3] = (0x80 or rcode).toByte()
        header[7] = if (rcode == 0) 1 else 0
        val answer = byteArrayOf(
            0xc0.toByte(), 0x0c, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x01, 0x2c, 0x00, 0x04,
            93, 184.toByte(), 216.toByte(), 34,
        )
        return header + q.copyOfRange(12, q.size) + (if (rcode == 0) answer else ByteArray(0))
    }

    @Test fun dnsAnswerRoundTrip() {
        val r = Dns.parse(response(), 0x1234) as DnsLookup.Records
        assertEquals(listOf(DnsRecord("A", "93.184.216.34", 300)), r.records)
        assertFalse(r.truncated)
    }

    @Test fun dnsRefusesWrongIdAndShortPackets() {
        assertEquals(DnsLookup.Malformed, Dns.parse(response(), 0x4321))
        assertEquals(DnsLookup.Malformed, Dns.parse(ByteArray(5), 1))
    }

    @Test fun dnsReportsRcode() {
        assertEquals(DnsLookup.Error(3), Dns.parse(response(rcode = 3), 0x1234))
    }

    @Test fun dnsFlagsTruncation() {
        val r = Dns.parse(response(flagsExtra = 0x0200), 0x1234) as DnsLookup.Records
        assertTrue(r.truncated)
    }

    @Test fun dnsSurvivesGarbageAndPointerLoops() {
        val loop = response().also { it[12] = 0xc0.toByte(); it[13] = 0x0c }
        // A self-pointing name must be rejected, not looped on.
        assertEquals(DnsLookup.Malformed, Dns.parse(loop, 0x1234))
        val cut = response().copyOf(30)
        assertEquals(DnsLookup.Malformed, Dns.parse(cut, 0x1234))
    }

    @Test fun reverseNameAndFraming() {
        assertEquals("5.1.168.192.in-addr.arpa", Dns.reverseName("192.168.1.5"))
        assertNull(Dns.reverseName("nope"))
        val msg = Dns.query("a.b", 1, 28)
        val framed = Dns.tcpFrame(msg)
        assertEquals(msg.size, (framed[0].toInt() and 0xff shl 8) or (framed[1].toInt() and 0xff))
    }

    @Test fun badLabelsAreRefused() {
        val e = runCatching { Dns.query("a..b", 1, 1) }.exceptionOrNull()
        assertNotNull(e)
    }

    // ------------------------------------------------------------------ ping

    @Test fun parsesBusyboxPing() {
        val s = PingParse.parse(
            "PING 1.1.1.1 (1.1.1.1): 56 data bytes\n--- 1.1.1.1 ping statistics ---\n4 packets transmitted, 4 packets received, 0% packet loss\nround-trip min/avg/max = 10.1/12.2/15.3 ms\n",
        )!!
        assertEquals(4, s.sent); assertEquals(4, s.received)
        assertEquals(12.2, s.avgMs!!, 0.001)
        assertEquals(0, s.lossPercent)
    }

    @Test fun parsesIputilsPingWithLoss() {
        val s = PingParse.parse(
            "4 packets transmitted, 3 received, 25% packet loss, time 3004ms\nrtt min/avg/max/mdev = 9.1/10.0/11.9/1.0 ms\n",
        )!!
        assertEquals(3, s.received)
        assertEquals(25, s.lossPercent)
        assertEquals(11.9, s.maxMs!!, 0.001)
    }

    @Test fun pingWithoutSummaryIsNull() {
        assertNull(PingParse.parse("ping: bad address 'x'"))
    }

    @Test fun recognisesPingThatCannotRun() {
        assertNotNull(PingParse.cannotRun("ping: socket: Operation not permitted"))
        assertNotNull(PingParse.cannotRun("sh: ping: not found"))
        assertNull(PingParse.cannotRun("4 packets transmitted, 0 packets received, 100% packet loss"))
    }

    @Test fun tcpFallbackFoldsSamples() {
        val s = PingParse.fromTcp(listOf(10L, null, 20L, 30L))
        assertEquals(4, s.sent); assertEquals(3, s.received)
        assertEquals(20.0, s.avgMs!!, 0.001)
        assertEquals(25, s.lossPercent)
        assertNull(PingParse.fromTcp(listOf(null, null)).avgMs)
    }

    // ------------------------------------------------------------ traceroute

    @Test fun parsesTraceroute() {
        val hops = TraceParse.parse(
            "traceroute to 1.1.1.1 (1.1.1.1), 20 hops max, 46 byte packets\n 1  192.168.1.1  1.234 ms\n 2  * * *\n 3  10.0.0.1 (10.0.0.1)  5.1 ms  4.9 ms\n",
        )
        assertEquals(3, hops.size)
        assertEquals("192.168.1.1", hops[0].addr)
        assertNull(hops[1].addr)
        assertEquals(5.1, hops[2].ms!!, 0.001)
    }

    @Test fun parsesTracepath() {
        val hops = TraceParse.parse(" 1?: [LOCALHOST]                      pmtu 1500\n 1:  192.168.1.1                  0.531ms\n 2:  no reply\n 3:  10.0.0.1   5.1ms asymm  4\n")
        assertEquals(listOf(1, 2, 3), hops.map { it.n })
        assertEquals("192.168.1.1", hops[0].addr)
        assertNull(hops[1].addr)
        assertEquals("10.0.0.1", hops[2].addr)
    }

    // --------------------------------------------------------------- servers

    private fun server(kind: LabKind = LabKind.HTTP) = LabServer("abc12345", "Pi-hole", "192.168.1.2", 80, kind, "/admin", "pihole")

    @Test fun serverEncodeDecodeRoundTrip() {
        val list = listOf(server(), server(LabKind.TCP).copy(id = "zzz99999", path = "/", sshAlias = ""))
        assertEquals(list, LabServers.decode(LabServers.encode(list)))
    }

    @Test fun damagedServerLinesAreSkipped() {
        val text = "bad line\nabc|n|h|notaport|TCP|/|\n" + LabServers.encode(listOf(server()))
        assertEquals(1, LabServers.decode(text).size)
    }

    @Test fun serverProblems() {
        assertNull(LabServers.problem(server()))
        assertNotNull(LabServers.problem(server().copy(name = "a|b")))
        assertNotNull(LabServers.problem(server().copy(host = "-x")))
        assertNotNull(LabServers.problem(server().copy(port = 0)))
        assertNotNull(LabServers.problem(server().copy(path = "admin")))
        assertNotNull(LabServers.problem(server().copy(sshAlias = "a b")))
    }

    @Test fun serverUrl() {
        assertEquals("http://192.168.1.2/admin", LabServers.url(server()))
        assertEquals("https://nas:5001/", LabServers.url(server(LabKind.HTTPS).copy(host = "nas", port = 5001, path = "/")))
        assertNull(LabServers.url(server(LabKind.TCP)))
    }

    // ---------------------------------------------------------------- health

    @Test fun healthVerdicts() {
        assertEquals(Health.DOWN, LabHealth.classify(LabKind.HTTP, null, null, null, null).health)
        assertEquals(Health.UP, LabHealth.classify(LabKind.TCP, 5, null, null, null).health)
        assertEquals(Health.SLOW, LabHealth.classify(LabKind.TCP, 2_000, null, null, null).health)
        assertEquals(Health.ERROR, LabHealth.classify(LabKind.HTTP, 5, null, null, "timed out").health)
        assertEquals(Health.ERROR, LabHealth.classify(LabKind.HTTP, 5, 503, 20, null).health)
        assertEquals(Health.UP, LabHealth.classify(LabKind.HTTP, 5, 404, 20, null).health)
        assertEquals(Health.SLOW, LabHealth.classify(LabKind.HTTP, 5, 200, 3_000, null).health)
    }

    @Test fun healthSummary() {
        assertEquals("not checked", LabHealth.summary(listOf(null, null)))
        assertEquals("1/2 up", LabHealth.summary(listOf(HealthReading(Health.UP, ""), HealthReading(Health.DOWN, ""), null)))
    }

    // ---------------------------------------------------------------- vitals

    @Test fun vitalsParse() {
        val v = VitalsParse.parse(
            "0.42 0.30 0.25 1/312 4821\nMemTotal:        8000000 kB\nMemAvailable:    2000000 kB\n/dev/sda1 100 37 63 37% /\nup 3 days, 2 hours\n",
        )!!
        assertEquals(0.42, v.load1!!, 0.001)
        assertEquals(75, v.memUsedPercent)
        assertEquals(37, v.diskUsedPercent)
        assertEquals("up 3 days, 2 hours", v.uptime)
    }

    @Test fun vitalsOfNonsenseIsNull() {
        assertNull(VitalsParse.parse(""))
        assertNull(VitalsParse.parse("Permission denied (publickey)."))
    }

    @Test fun remoteScriptIsConstantText() {
        // No placeholder may ever be interpolated into the script that runs on the server.
        assertFalse(VitalsParse.REMOTE_SCRIPT.contains("$"))
    }
}
