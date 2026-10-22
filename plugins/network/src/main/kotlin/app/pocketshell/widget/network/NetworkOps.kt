package app.pocketshell.widget.network

import android.content.Context
import app.pocketshell.packages.GuestProcess
import app.pocketshell.packages.ProcessBuilderGuestCommandRunner
import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.terminal.ShellEnvironment
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

/**
 * Everything that touches the system — the ONLY file that runs guest commands or opens sockets.
 * (A contract test pins this: UI and state never exec.) Pure decisions live in the model files.
 */

/** The result of a finished guest command. */
data class ExecResult(val exitCode: Int?, val stdout: String, val stderr: String, val error: String?)

/** A guest process that is still running (a tunnel, a share). */
class LiveProcess(private val handle: GuestProcess, val pidFile: File) {
    @Volatile private var stopped = false

    /** Block until it exits (on a worker thread). */
    fun waitForExit(): ExecResult {
        val r = handle.waitFor(null)
        return ExecResult(r.exitCode, r.stdout, r.stderr, r.error)
    }

    val wasStopped: Boolean get() = stopped

    fun isAlive(): Boolean = pid()?.let { File("/proc/$it").exists() } ?: true

    fun pid(): Int? = try { pidFile.readText().trim().toIntOrNull() } catch (_: Exception) { null }

    /** Ask it to end: SIGTERM the real process, then tear down the wrapper. */
    fun stop() {
        stopped = true
        pid()?.let { runCatching { android.os.Process.sendSignal(it, 15) } }
        runCatching { handle.destroy() }
    }
}

class GuestExec(private val appContext: Context, private val files: NetFiles) {

    private fun spec(argv: List<String>) = GuestLaunch.spec(
        nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
        rootfsDir = RuntimeStorage(appContext.noBackupFilesDir).rootfsDir,
        hostCwd = ShellEnvironment.homeDir(appContext),
        prootTmpDir = File(appContext.cacheDir, "proot-tmp").apply { mkdirs() },
        guestCommand = argv,
        profile = GuestExecutionProfile.PACKAGE_OPERATION,
    )

    /** Run to completion (or [timeoutMs]); never throws. */
    fun run(argv: List<String>, timeoutMs: Long): ExecResult = try {
        val r = ProcessBuilderGuestCommandRunner().start(spec(argv)).waitFor(timeoutMs)
        ExecResult(r.exitCode, r.stdout, r.stderr, r.error)
    } catch (e: Throwable) {
        ExecResult(null, "", "", e.message ?: e.javaClass.simpleName)
    }

    /** Start a long-lived process whose pid lands in a file (see [Tunnels.withPidFile]). */
    fun start(id: String, argv: List<String>): LiveProcess {
        files.jobsDir.mkdirs()
        val pidFile = File(files.jobsDir, "$id.pid")
        pidFile.delete()
        val guestPidFile = "/tmp/netjobs/$id.pid"
        val handle = ProcessBuilderGuestCommandRunner().start(spec(Tunnels.withPidFile(guestPidFile, argv)))
        return LiveProcess(handle, pidFile)
    }

    // ------------------------------------------------------------- the jobs

    private val BATCH = listOf("-o", "BatchMode=yes", "-o", "ConnectTimeout=6", "-o", "StrictHostKeyChecking=yes")

    /** Log in to [target] with no command and classify the answer. */
    fun testHost(target: String): SshOutcome {
        val r = run(listOf("ssh") + BATCH + listOf(target, "exit"), 20_000)
        if (r.error != null && r.exitCode == null) return classifyFailureToRun(r)
        return SshOutcome.classify(r.exitCode ?: -1, r.stderr, r.stdout)
    }

    /** `ssh -T git@host` with one specific key — "does GitHub accept my key?". */
    fun testService(user: String, host: String, keyName: String): SshOutcome {
        val key = "/root/.ssh/$keyName"
        val r = run(listOf("ssh") + BATCH + listOf("-i", key, "-o", "IdentitiesOnly=yes", "-T", "$user@$host"), 20_000)
        if (r.error != null && r.exitCode == null) return classifyFailureToRun(r)
        return SshOutcome.classify(r.exitCode ?: -1, r.stderr, r.stdout)
    }

    private fun classifyFailureToRun(r: ExecResult): SshOutcome =
        if (r.error!!.contains("did not finish")) SshOutcome.TimedOut else SshOutcome.SandboxProblem(r.error)

    /** `ssh-keyscan` → the host's public keys, each with the fingerprint a person can compare. */
    fun scanHostKeys(host: String, port: Int): List<Pair<String, String>>? {
        val r = run(listOf("ssh-keyscan", "-T", "6", "-p", port.toString(), "-t", "ed25519,ecdsa,rsa", host), 20_000)
        if (r.exitCode != 0 && r.stdout.isBlank()) return null
        return r.stdout.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
            val keyPart = line.substringAfter(' ', "")
            SshKeys.parsePublic(keyPart)?.let { line to "${it.type} ${it.fingerprint}" }
        }.ifEmpty { null }
    }

    /** Create an ed25519 key pair. The name was validated and the pair does not exist yet. */
    fun generateKey(name: String, comment: String): ExecResult =
        run(listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", comment, "-f", "/root/.ssh/$name"), 30_000)
}

// ============================================================ host-side facts

data class Iface(val name: String, val ip: String) {
    val isWifi: Boolean get() = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
}

object NetFacts {

    /** IPv4 addresses of the interfaces that are up (loopback excluded). Cheap; no network traffic. */
    fun interfaces(): List<Iface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { Iface(nic.name, it.hostAddress ?: "") } }
            .filter { it.ip.isNotEmpty() && !it.ip.startsWith("169.254.") }
    } catch (_: Throwable) {
        emptyList()
    }

    /** One TCP connect, as a kernel fact. Milliseconds on success, null on failure. */
    fun tcp(host: String, port: Int, timeoutMs: Int): Long? {
        val start = System.nanoTime()
        return try {
            Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Throwable) {
            null
        }
    }

    /** Is this port accepting connections on the given local address? */
    fun listening(ip: String, port: Int): Boolean = tcp(ip, port, 800) != null

    private fun dns(server: String, name: String): Pair<Checks.DnsAnswer, Long>? {
        val id = (System.nanoTime() and 0xffff).toInt()
        val start = System.nanoTime()
        return try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 1_500
                val q = Checks.dnsQuery(name, id)
                sock.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
                val buf = ByteArray(512)
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                Checks.parseDns(buf.copyOf(pkt.length), id) to (System.nanoTime() - start) / 1_000_000
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** The full diagnostic. Network traffic; call from IO and only when the user asks. */
    fun runChecks(resolvConf: String): Pair<Verdict, List<CheckLine>> {
        val lines = ArrayList<CheckLine>()
        val ifaces = interfaces()
        lines += if (ifaces.isEmpty()) CheckLine("addr", "Addresses", CheckState.FAIL, "no active network interface")
        else CheckLine("addr", "Addresses", CheckState.OK, ifaces.joinToString("  ") { "${it.name} ${it.ip}" })

        var reach: Boolean? = null
        if (ifaces.isNotEmpty()) {
            val ms = tcp("1.1.1.1", 443, 2_500)
            reach = ms != null
            lines += if (ms != null) CheckLine("reach", "Internet by address", CheckState.OK, "1.1.1.1:443 connected", ms)
            else CheckLine("reach", "Internet by address", CheckState.FAIL, "1.1.1.1:443 did not answer")
        } else {
            lines += CheckLine("reach", "Internet by address", CheckState.SKIPPED, "no address")
        }

        val servers = Checks.nameservers(resolvConf)
        var dnsOk: Boolean? = null
        if (servers.isEmpty()) {
            lines += CheckLine("dns", "Linux DNS", CheckState.WARN, "no IPv4 nameserver in /etc/resolv.conf")
        } else if (ifaces.isNotEmpty()) {
            var any = false
            for (s in servers.take(3)) {
                val r = dns(s, "example.com")
                val line = when (val a = r?.first) {
                    is Checks.DnsAnswer.Answered -> { any = any || a.answers > 0; CheckLine("dns-$s", "DNS $s", if (a.answers > 0) CheckState.OK else CheckState.WARN, if (a.answers > 0) "answered" else "answered with no records", r.second) }
                    is Checks.DnsAnswer.Error -> CheckLine("dns-$s", "DNS $s", CheckState.FAIL, Checks.rcodeName(a.rcode))
                    else -> CheckLine("dns-$s", "DNS $s", CheckState.FAIL, "no answer")
                }
                lines += line
            }
            dnsOk = any
        }

        var portal: Int? = null
        if (ifaces.isNotEmpty() && reach != false) {
            val t0 = System.nanoTime()
            try {
                val c = URL("http://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false; c.connectTimeout = 3_000; c.readTimeout = 3_000
                portal = c.responseCode
                c.disconnect()
                val ms = (System.nanoTime() - t0) / 1_000_000
                lines += if (portal == 204) CheckLine("web", "Web probe", CheckState.OK, "204 — open internet", ms)
                else CheckLine("web", "Web probe", CheckState.WARN, "$portal — a login page or filter is answering", ms)
            } catch (_: Throwable) {
                lines += CheckLine("web", "Web probe", if (dnsOk == false) CheckState.FAIL else CheckState.WARN, "could not fetch")
            }
        }
        return Checks.verdict(ifaces.isNotEmpty(), reach, dnsOk, portal) to lines
    }
}
