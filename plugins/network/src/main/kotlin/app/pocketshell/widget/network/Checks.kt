package app.pocketshell.widget.network

import java.io.ByteArrayOutputStream

/** One line of the CHECKS tab. */
data class CheckLine(
    val id: String,
    val label: String,
    val state: CheckState,
    val detail: String,
    val millis: Long? = null,
)

enum class CheckState { OK, WARN, FAIL, SKIPPED }

enum class Verdict(val headline: String, val advice: String) {
    ONLINE("Online", "The internet and DNS both work."),
    CAPTIVE("Captive portal", "Connected, but a login page is in the way — open a browser and sign in."),
    DNS_BROKEN("DNS is broken", "You can reach the internet by address, but names do not resolve. The Linux resolvers may be wrong."),
    NO_INTERNET("No internet", "You have a local address but nothing beyond it answers."),
    OFFLINE("Offline", "No network connection on this phone."),
}

object Checks {

    /**
     * The one-word answer, from the facts. [hasAddress] = a usable local address exists;
     * [reachIp] = a TCP connect to a public IP worked; [dnsOk] = at least one resolver answered;
     * [portalProbe] = HTTP status of the Android connectivity probe (204 = open internet).
     */
    fun verdict(hasAddress: Boolean, reachIp: Boolean?, dnsOk: Boolean?, portalProbe: Int?): Verdict = when {
        !hasAddress -> Verdict.OFFLINE
        reachIp == false -> Verdict.NO_INTERNET
        dnsOk == false -> Verdict.DNS_BROKEN
        portalProbe != null && portalProbe != 204 -> Verdict.CAPTIVE
        reachIp == true -> Verdict.ONLINE
        else -> Verdict.NO_INTERNET
    }

    // ---------------------------------------------------------------- DNS

    /** A minimal DNS A-record query for [name]. */
    fun dnsQuery(name: String, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xff); out.write(id and 0xff)
        out.write(0x01); out.write(0x00) // recursion desired
        out.write(0); out.write(1) // one question
        out.write(0); out.write(0); out.write(0); out.write(0); out.write(0); out.write(0)
        for (label in name.split('.')) {
            require(label.isNotEmpty() && label.length < 64) { "bad DNS label" }
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(0); out.write(1) // type A
        out.write(0); out.write(1) // class IN
        return out.toByteArray()
    }

    sealed interface DnsAnswer {
        data class Answered(val answers: Int) : DnsAnswer
        data class Error(val rcode: Int) : DnsAnswer
        data object Malformed : DnsAnswer
    }

    /** Parse just the header: matching id, a response, RCODE, answer count. */
    fun parseDns(response: ByteArray, expectedId: Int): DnsAnswer {
        if (response.size < 12) return DnsAnswer.Malformed
        val id = (response[0].toInt() and 0xff shl 8) or (response[1].toInt() and 0xff)
        if (id != expectedId) return DnsAnswer.Malformed
        val flags = (response[2].toInt() and 0xff shl 8) or (response[3].toInt() and 0xff)
        if (flags and 0x8000 == 0) return DnsAnswer.Malformed
        val rcode = flags and 0xf
        val answers = (response[6].toInt() and 0xff shl 8) or (response[7].toInt() and 0xff)
        return if (rcode != 0) DnsAnswer.Error(rcode) else DnsAnswer.Answered(answers)
    }

    fun rcodeName(rcode: Int) = when (rcode) {
        1 -> "format error"
        2 -> "server failure"
        3 -> "name does not exist"
        5 -> "refused"
        else -> "error $rcode"
    }

    /** IPv4 nameservers from a resolv.conf (IPv6 ones are skipped: the check speaks IPv4 UDP). */
    fun nameservers(resolvConf: String): List<String> = resolvConf.lines()
        .map { it.trim() }
        .filter { it.startsWith("nameserver") }
        .mapNotNull { it.removePrefix("nameserver").trim().takeIf { ip -> ip.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) } }
        .distinct()
}
