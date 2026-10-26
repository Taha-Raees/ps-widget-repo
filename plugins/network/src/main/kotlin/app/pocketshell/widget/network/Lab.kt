package app.pocketshell.widget.network

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * The pure half of the Home Lab tools: addressing, DNS wire format, ping / traceroute output,
 * port lists, server health and remote vitals. No sockets, no exec, no Android — JVM-tested.
 * (The sockets and processes live in NetworkOps.kt.)
 */

// ====================================================================== addressing

object LabNet {

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun ipv4ToInt(ip: String): Int? {
        val m = IPV4.matchEntire(ip.trim()) ?: return null
        var n = 0
        for (i in 1..4) {
            val part = m.groupValues[i].toInt()
            if (part > 255) return null
            n = (n shl 8) or part
        }
        return n
    }

    fun intToIpv4(n: Int): String = "${n ushr 24 and 255}.${n ushr 16 and 255}.${n ushr 8 and 255}.${n and 255}"

    /** A sweep never covers more than a /24 (254 addresses): bigger networks are scanned around this phone. */
    const val MAX_SWEEP_HOSTS = 254

    /** The addresses to probe around [ip]/[prefix], without the network, broadcast and own address. */
    fun sweepTargets(ip: String, prefix: Int): List<String> {
        val me = ipv4ToInt(ip) ?: return emptyList()
        if (prefix !in 8..30) return emptyList()
        val effective = maxOf(prefix, 24)
        val mask = if (effective == 0) 0 else (-1 shl (32 - effective))
        val base = me and mask
        val size = 1 shl (32 - effective)
        return (1 until size - 1).map { base + it }.filter { it != me }.map { intToIpv4(it) }
    }

    /** A hostname or IPv4 address a lookup / ping / check may be pointed at. Never shell text. */
    fun validHost(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty() || h.length > 253 || h.startsWith("-")) return false
        if (IPV4.matches(h)) return ipv4ToInt(h) != null
        return h.split('.').all { it.isNotEmpty() && it.length < 64 && Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$").matches(it) }
    }
}

// ====================================================================== ports

object PortSpec {

    const val MAX_PORTS = 100

    val COMMON = listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 631, 1883, 3000, 3306, 3389, 5000, 5432, 5900, 6379, 8000, 8080, 8123, 8443, 9000)

    /** The ports the discovery sweep looks at on every live host. */
    val DISCOVERY = listOf(22, 53, 80, 443, 445, 631, 8006, 8080, 8123, 32400)

    sealed interface Parsed {
        data class Ports(val ports: List<Int>) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    /** `22, 80, 8000-8010` → sorted unique ports; at most [MAX_PORTS]. */
    fun parse(text: String): Parsed {
        val out = sortedSetOf<Int>()
        for (raw in text.split(',', ' ', ';')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val range = part.split('-')
            if (range.size > 2) return Parsed.Bad("\"$part\" is not a port or a range")
            val a = range[0].toIntOrNull()
            val b = if (range.size == 2) range[1].toIntOrNull() else a
            if (a == null || b == null) return Parsed.Bad("\"$part\" is not a port or a range")
            if (a !in 1..65535 || b !in 1..65535) return Parsed.Bad("Ports are 1 to 65535")
            if (b < a) return Parsed.Bad("\"$part\" runs backwards")
            if (b - a >= MAX_PORTS) return Parsed.Bad("At most $MAX_PORTS ports at a time")
            for (p in a..b) out += p
            if (out.size > MAX_PORTS) return Parsed.Bad("At most $MAX_PORTS ports at a time")
        }
        return if (out.isEmpty()) Parsed.Bad("Give at least one port") else Parsed.Ports(out.toList())
    }

    fun serviceName(port: Int): String? = when (port) {
        21 -> "ftp"; 22 -> "ssh"; 23 -> "telnet"; 25 -> "smtp"; 53 -> "dns"; 80 -> "http"
        110 -> "pop3"; 139 -> "netbios"; 143 -> "imap"; 443 -> "https"; 445 -> "smb"; 631 -> "ipp"
        1883 -> "mqtt"; 3000 -> "dev server"; 3306 -> "mysql"; 3389 -> "rdp"; 5000 -> "http"
        5432 -> "postgres"; 5900 -> "vnc"; 6379 -> "redis"; 8000 -> "http"; 8006 -> "proxmox"
        8080 -> "http-alt"; 8123 -> "home assistant"; 8443 -> "https-alt"; 9000 -> "http"; 32400 -> "plex"
        else -> null
    }

    /** A best guess at what a device is, from its open ports. A hint, never a fact. */
    fun guess(open: Set<Int>): String = when {
        8123 in open -> "Home Assistant"
        8006 in open -> "Proxmox"
        32400 in open -> "Plex server"
        631 in open || 9100 in open -> "Printer"
        53 in open && (80 in open || 443 in open) -> "Router"
        445 in open -> "File server"
        22 in open && (80 in open || 443 in open || 8080 in open) -> "Server"
        22 in open -> "SSH host"
        80 in open || 443 in open || 8080 in open -> "Web device"
        53 in open -> "DNS server"
        open.isEmpty() -> "Device"
        else -> "Device"
    }
}

// ====================================================================== DNS

data class DnsRecord(val type: String, val value: String, val ttl: Long)

sealed interface DnsLookup {
    data class Records(val records: List<DnsRecord>, val truncated: Boolean) : DnsLookup
    data class Error(val rcode: Int) : DnsLookup
    data object Malformed : DnsLookup
}

object Dns {

    val TYPES: Map<String, Int> = linkedMapOf("A" to 1, "AAAA" to 28, "MX" to 15, "TXT" to 16, "NS" to 2, "CNAME" to 5, "PTR" to 12)

    private val NAMES = TYPES.entries.associate { it.value to it.key }

    /** A DNS question for [name] of record type [type] (recursion desired). */
    fun query(name: String, id: Int, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xff); out.write(id and 0xff)
        out.write(0x01); out.write(0x00)
        out.write(0); out.write(1)
        repeat(6) { out.write(0) }
        for (label in name.trimEnd('.').split('.')) {
            require(label.isNotEmpty() && label.length < 64) { "bad DNS label" }
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(type shr 8 and 0xff); out.write(type and 0xff)
        out.write(0); out.write(1)
        return out.toByteArray()
    }

    /** A DNS-over-TCP frame: the 2-byte length, then the message. */
    fun tcpFrame(message: ByteArray): ByteArray = byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message

    /** `192.168.1.5` → `5.1.168.192.in-addr.arpa`; null when it is not an IPv4 address. */
    fun reverseName(ip: String): String? {
        LabNet.ipv4ToInt(ip) ?: return null
        return ip.trim().split('.').reversed().joinToString(".") + ".in-addr.arpa"
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff shl 8) or (b[at + 1].toInt() and 0xff)

    /** Read a (possibly compressed) name at [start]; returns the name and the offset after it. */
    private fun readName(b: ByteArray, start: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var pos = start
        var next = -1
        var jumps = 0
        while (true) {
            if (pos >= b.size) return null
            val len = b[pos].toInt() and 0xff
            when {
                len == 0 -> { if (next < 0) next = pos + 1; break }
                len and 0xc0 == 0xc0 -> {
                    if (pos + 1 >= b.size || ++jumps > 16) return null
                    if (next < 0) next = pos + 2
                    pos = ((len and 0x3f) shl 8) or (b[pos + 1].toInt() and 0xff)
                }
                len and 0xc0 != 0 -> return null
                else -> {
                    if (pos + 1 + len > b.size) return null
                    labels += String(b, pos + 1, len, Charsets.US_ASCII)
                    pos += 1 + len
                }
            }
        }
        return (if (labels.isEmpty()) "." else labels.joinToString(".")) to next
    }

    fun parse(response: ByteArray, expectedId: Int): DnsLookup {
        if (response.size < 12) return DnsLookup.Malformed
        if (u16(response, 0) != expectedId) return DnsLookup.Malformed
        val flags = u16(response, 2)
        if (flags and 0x8000 == 0) return DnsLookup.Malformed
        val rcode = flags and 0xf
        if (rcode != 0) return DnsLookup.Error(rcode)
        val truncated = flags and 0x0200 != 0
        val qd = u16(response, 4)
        val an = u16(response, 6)
        var pos = 12
        repeat(qd) {
            val (_, after) = readName(response, pos) ?: return DnsLookup.Malformed
            pos = after + 4
            if (pos > response.size) return DnsLookup.Malformed
        }
        val records = ArrayList<DnsRecord>()
        repeat(an) {
            val (_, after) = readName(response, pos) ?: return DnsLookup.Malformed
            if (after + 10 > response.size) return DnsLookup.Malformed
            val type = u16(response, after)
            val ttl = (u16(response, after + 4).toLong() shl 16) or u16(response, after + 6).toLong()
            val rdlen = u16(response, after + 8)
            val rd = after + 10
            if (rd + rdlen > response.size) return DnsLookup.Malformed
            val label = NAMES[type]
            if (label != null) {
                val value = when (type) {
                    1 -> if (rdlen == 4) LabNet.intToIpv4(((response[rd].toInt() and 0xff) shl 24) or ((response[rd + 1].toInt() and 0xff) shl 16) or ((response[rd + 2].toInt() and 0xff) shl 8) or (response[rd + 3].toInt() and 0xff)) else null
                    28 -> if (rdlen == 16) InetAddress.getByAddress(response.copyOfRange(rd, rd + 16)).hostAddress else null
                    2, 5, 12 -> readName(response, rd)?.first
                    15 -> if (rdlen >= 3) readName(response, rd + 2)?.first?.let { "${u16(response, rd)} $it" } else null
                    16 -> txt(response, rd, rdlen)
                    else -> null
                }
                if (value != null) records += DnsRecord(label, value, ttl)
            }
            pos = rd + rdlen
        }
        return DnsLookup.Records(records, truncated)
    }

    private fun txt(b: ByteArray, start: Int, len: Int): String {
        val sb = StringBuilder()
        var p = start
        while (p < start + len) {
            val n = b[p].toInt() and 0xff
            if (p + 1 + n > start + len) break
            sb.append(String(b, p + 1, n, Charsets.UTF_8))
            p += 1 + n
        }
        return "\"${sb.toString().filter { it >= ' ' }}\""
    }
}

// ====================================================================== ping

data class PingStats(val sent: Int, val received: Int, val minMs: Double?, val avgMs: Double?, val maxMs: Double?) {
    val lossPercent: Int get() = if (sent == 0) 100 else ((sent - received) * 100) / sent
}

sealed interface PingOutcome {
    data class Done(val stats: PingStats, val via: String) : PingOutcome
    /** `ping` could not run here (no raw-socket permission, or not installed). */
    data class CannotRun(val reason: String) : PingOutcome
}

object PingParse {

    private val COUNTS = Regex("(\\d+) packets transmitted, (\\d+)(?: packets)? received")
    private val RTT = Regex("=\\s*([\\d.]+)/([\\d.]+)/([\\d.]+)")

    /** Parse the summary of busybox / iputils `ping`. Null when there is no summary at all. */
    fun parse(output: String): PingStats? {
        val c = COUNTS.find(output) ?: return null
        val r = RTT.find(output)
        return PingStats(
            sent = c.groupValues[1].toInt(),
            received = c.groupValues[2].toInt(),
            minMs = r?.groupValues?.get(1)?.toDoubleOrNull(),
            avgMs = r?.groupValues?.get(2)?.toDoubleOrNull(),
            maxMs = r?.groupValues?.get(3)?.toDoubleOrNull(),
        )
    }

    /** Does the output say ping is not allowed / not installed (as opposed to "the host did not answer")? */
    fun cannotRun(output: String): String? {
        val t = output.lowercase()
        return when {
            "operation not permitted" in t || "permission denied" in t || "must be root" in t || "raw socket" in t || "socket: " in t && "denied" in t ->
                "ping needs raw-socket permission, which this Linux environment does not have"
            "not found" in t || "no such file" in t -> "ping is not installed (apt-get install iputils-ping)"
            else -> null
        }
    }

    /** Fold timings of plain TCP connects (the fallback) into the same shape. */
    fun fromTcp(samples: List<Long?>): PingStats {
        val ok = samples.filterNotNull()
        return PingStats(
            sent = samples.size,
            received = ok.size,
            minMs = ok.minOrNull()?.toDouble(),
            avgMs = if (ok.isEmpty()) null else ok.average(),
            maxMs = ok.maxOrNull()?.toDouble(),
        )
    }
}

// ====================================================================== traceroute

data class Hop(val n: Int, val addr: String?, val ms: Double?)

object TraceParse {

    private val IP = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3})")
    private val MS = Regex("([\\d.]+)\\s*ms")
    private val TRACEROUTE = Regex("^\\s*(\\d+)\\s+(.*)$")
    private val TRACEPATH = Regex("^\\s*(\\d+)\\??:\\s+(.*)$")

    /** Understand both `traceroute -n` (busybox / classic) and `tracepath` lines. */
    fun parse(output: String): List<Hop> {
        val hops = ArrayList<Hop>()
        for (line in output.lines()) {
            val m = TRACEPATH.matchEntire(line) ?: TRACEROUTE.matchEntire(line) ?: continue
            val n = m.groupValues[1].toInt()
            val rest = m.groupValues[2]
            if ("pmtu" in rest && IP.find(rest) == null) continue
            if (hops.any { it.n == n }) continue
            val noReply = rest.trim().let { it.startsWith("*") || it.startsWith("no reply") }
            val addr = if (noReply) null else IP.find(rest)?.groupValues?.get(1)
            val ms = if (noReply) null else MS.find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
            hops += Hop(n, addr, ms)
        }
        return hops.sortedBy { it.n }
    }
}

// ====================================================================== servers & health

enum class LabKind(val label: String, val defaultPort: Int) {
    TCP("TCP port", 22), HTTP("HTTP", 80), HTTPS("HTTPS", 443)
}

/** A server the user wants to keep an eye on. [sshAlias] (a saved host) adds load / memory / disk. */
data class LabServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val kind: LabKind,
    val path: String = "/",
    val sshAlias: String = "",
)

object LabServers {

    private val ALIAS = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun problem(s: LabServer): String? = when {
        s.name.isBlank() || s.name.length > 40 || s.name.any { it == '|' || it < ' ' } -> "Name: 1 to 40 characters, no | or line breaks"
        !LabNet.validHost(s.host) -> "Host: a hostname or an IPv4 address"
        s.port !in 1..65535 -> "Port: 1 to 65535"
        s.kind != LabKind.TCP && (!s.path.startsWith("/") || s.path.length > 100 || s.path.any { it <= ' ' || it == '|' || it == '#' }) ->
            "Path: starts with /, no spaces"
        s.sshAlias.isNotEmpty() && !ALIAS.matches(s.sshAlias) -> "SSH host: a saved alias"
        else -> null
    }

    fun url(s: LabServer): String? {
        val scheme = when (s.kind) { LabKind.HTTP -> "http"; LabKind.HTTPS -> "https"; LabKind.TCP -> return null }
        val port = if (s.port == s.kind.defaultPort) "" else ":${s.port}"
        return "$scheme://${s.host}$port${s.path}"
    }

    /** One server per line: `id|name|host|port|kind|path|sshAlias`. Damaged lines are skipped. */
    fun encode(list: List<LabServer>): String = list.filter { problem(it) == null }.joinToString("") {
        "${it.id}|${it.name}|${it.host}|${it.port}|${it.kind.name}|${it.path}|${it.sshAlias}\n"
    }

    fun decode(text: String?): List<LabServer> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 7) return@mapNotNull null
        val kind = LabKind.entries.firstOrNull { it.name == p[4] } ?: return@mapNotNull null
        LabServer(p[0], p[1], p[2], p[3].toIntOrNull() ?: return@mapNotNull null, kind, p[5], p[6]).takeIf { problem(it) == null }
    }

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun newId(random: java.util.Random = java.security.SecureRandom()): String =
        buildString { repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}

enum class Health(val label: String) { UP("up"), SLOW("slow"), ERROR("error"), DOWN("down"), UNKNOWN("not checked") }

data class HealthReading(val health: Health, val detail: String, val millis: Long? = null)

object LabHealth {

    const val SLOW_MS = 1_500L

    /** The verdict from the facts: [tcpMs] (null = no connect), [httpStatus] (null = not an HTTP check / no reply). */
    fun classify(kind: LabKind, tcpMs: Long?, httpStatus: Int?, httpMs: Long?, error: String?): HealthReading = when {
        tcpMs == null -> HealthReading(Health.DOWN, error ?: "nothing is listening / no answer")
        kind == LabKind.TCP -> HealthReading(if (tcpMs > SLOW_MS) Health.SLOW else Health.UP, "port open", tcpMs)
        httpStatus == null -> HealthReading(Health.ERROR, error ?: "port open, but no HTTP answer", tcpMs)
        httpStatus >= 500 -> HealthReading(Health.ERROR, "HTTP $httpStatus", httpMs)
        (httpMs ?: tcpMs) > SLOW_MS -> HealthReading(Health.SLOW, "HTTP $httpStatus", httpMs)
        else -> HealthReading(Health.UP, "HTTP $httpStatus", httpMs ?: tcpMs)
    }

    fun summary(readings: Collection<HealthReading?>): String {
        val checked = readings.filterNotNull()
        if (checked.isEmpty()) return "not checked"
        val up = checked.count { it.health == Health.UP || it.health == Health.SLOW }
        return "$up/${checked.size} up"
    }
}

// ====================================================================== remote vitals

data class Vitals(val load1: Double?, val memUsedPercent: Int?, val diskUsedPercent: Int?, val uptime: String?)

object VitalsParse {

    /** The constant script run on the server over ssh. The command is fixed text; nothing is interpolated. */
    const val REMOTE_SCRIPT = "cat /proc/loadavg; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; df -P / | tail -1; uptime -p 2>/dev/null || uptime"

    fun parse(output: String): Vitals? {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val load = lines.firstOrNull { Regex("^\\d+\\.\\d+ \\d+\\.\\d+ \\d+\\.\\d+ ").containsMatchIn(it) }?.substringBefore(' ')?.toDoubleOrNull()
        val total = lines.firstOrNull { it.startsWith("MemTotal:") }?.let { Regex("(\\d+)").find(it)?.value?.toLongOrNull() }
        val avail = lines.firstOrNull { it.startsWith("MemAvailable:") }?.let { Regex("(\\d+)").find(it)?.value?.toLongOrNull() }
        val mem = if (total != null && avail != null && total > 0) (((total - avail) * 100) / total).toInt().coerceIn(0, 100) else null
        val disk = lines.firstOrNull { Regex("\\s\\d+%\\s").containsMatchIn(it) && it.contains('/') }
            ?.let { Regex("\\s(\\d+)%\\s").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val up = lines.lastOrNull { it.startsWith("up ") || it.contains(" up ") }
        if (load == null && mem == null && disk == null) return null
        return Vitals(load, mem, disk, up)
    }
}
