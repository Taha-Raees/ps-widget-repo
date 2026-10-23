package app.pocketshell.widget.network

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class NetTab(val label: String) { LAB("Lab"), TOOLS("Tools"), HOSTS("Hosts"), KEYS("Keys"), TUNNELS("Tunnels"), SHARE("Share") }

/** What the local files say right now (no network involved). */
data class LocalSnapshot(
    val config: ParsedConfig,
    val keys: List<KeyInfo>,
    val tunnels: List<Tunnel>,
    val ifaces: List<Iface>,
    val lab: List<LabServer> = emptyList(),
)

sealed interface HostReach {
    data object Unknown : HostReach
    data object Checking : HostReach
    data class Up(val ms: Long) : HostReach
    data object Down : HostReach

    /** Reached through a jump host: a direct connect would say nothing. */
    data object ViaJump : HostReach
}

sealed interface ShareState {
    data object Off : ShareState
    data object Starting : ShareState
    data class Running(val url: String, val dir: String, val endsAtMs: Long, val ip: String, val port: Int) : ShareState
    data class Failed(val reason: String) : ShareState
}

data class Banner(val text: String, val bad: Boolean)

/** A host-key trust question waiting for the user's yes. */
data class TrustRequest(val label: String, val host: String, val lines: List<String>, val prints: List<String>, val retestAlias: String?)

/** What the TOOLS tab last produced (one result at a time). */
sealed interface ToolResult {
    data class DnsResult(val name: String, val type: String, val server: String, val lookup: DnsLookup?, val millis: Long?) : ToolResult
    data class PingResult(val host: String, val outcome: PingOutcome) : ToolResult
    data class TraceResult(val host: String, val hops: List<Hop>?, val tool: String?) : ToolResult
    data class PortsResult(val host: String, val results: Map<Int, NetFacts.Probe>) : ToolResult
}

data class ListeningServer(val port: Int, val name: String, val lanReachable: Boolean?)

/**
 * The ONE holder for the Network app. It outlives composition (leaving Home, rotation), so:
 *  - work runs on its own process-scoped [scope], never on a LaunchedEffect — a page swipe can neither
 *    cancel nor re-run an operation;
 *  - every action is single-flight (busy flags / AtomicBoolean) and clears itself in a `finally`;
 *  - no lock is held across I/O on any path the main thread reads.
 */
class NetworkState(context: Context) {

    private val appContext = context.applicationContext
    val files = NetFiles(appContext)
    private val exec = GuestExec(appContext, files)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var tab by mutableStateOf(NetTab.LAB)

    /** The row (host alias / key name / tunnel id) expanded in the current tab; Back collapses it. */
    var detail by mutableStateOf<String?>(null)

    /** When the local files were last read (the mini card re-reads rarely). */
    @Volatile var loadedAtMs = 0L
    var studio by mutableStateOf(false)
    var local by mutableStateOf<LocalSnapshot?>(null)
    var loading by mutableStateOf(false)
    var banner by mutableStateOf<Banner?>(null)

    var reach by mutableStateOf<Map<String, HostReach>>(emptyMap())
    var hostTests by mutableStateOf<Map<String, SshOutcome>>(emptyMap())
    var testing by mutableStateOf<Set<String>>(emptySet())
    var keyTests by mutableStateOf<Map<String, Pair<String, SshOutcome>>>(emptyMap())
    var pendingTrust by mutableStateOf<TrustRequest?>(null)

    var tunnelStates by mutableStateOf<Map<String, TunnelState>>(emptyMap())
    var share by mutableStateOf<ShareState>(ShareState.Off)
    var listening by mutableStateOf<List<ListeningServer>?>(null)
    var checks by mutableStateOf<Pair<Verdict, List<CheckLine>>?>(null)
    var checking by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)

    // ---- Home Lab: devices on this network, saved servers and their health
    var devices by mutableStateOf<List<NetFacts.Found>?>(null)
    var scanning by mutableStateOf(false)
    var scanProgress by mutableStateOf<Pair<Int, Int>?>(null)
    var health by mutableStateOf<Map<String, HealthReading>>(emptyMap())
    var healthChecking by mutableStateOf<Set<String>>(emptySet())
    var vitals by mutableStateOf<Map<String, Vitals?>>(emptyMap())

    // ---- Tools: one run at a time
    var toolRunning by mutableStateOf<String?>(null)
    var toolResult by mutableStateOf<ToolResult?>(null)

    private val live = ConcurrentHashMap<String, LiveProcess>()
    private val scanGuard = AtomicBoolean(false)
    private val scanCancelled = AtomicBoolean(false)
    private val toolGuard = AtomicBoolean(false)

    /** Host job ids (see [HostCompat]): while one is registered the app stays alive in the background. */
    private val jobIds = ConcurrentHashMap<String, Long>()
    private val loadGuard = AtomicBoolean(false)
    private val reachGuard = AtomicBoolean(false)
    private val listenGuard = AtomicBoolean(false)

    private val serverProbe by lazy { app.pocketshell.widget.probe.ServerProbe() }

    // ------------------------------------------------------------------ local

    /** Re-read the files. Cheap (a few small reads); the only thing the mini card ever does. */
    fun reloadLocal() {
        if (!loadGuard.compareAndSet(false, true)) return
        loading = true
        scope.launch {
            try {
                val tunnels = files.readTunnels()
                local = LocalSnapshot(SshConfig.parse(files.readConfig()), files.listKeys(), tunnels, NetFacts.interfaces(), files.readLab())
                loadedAtMs = System.currentTimeMillis()
                // A tunnel with no runtime state is simply stopped.
                tunnelStates = tunnels.associate { it.id to (tunnelStates[it.id] ?: TunnelState.Stopped) }
            } catch (e: Throwable) {
                banner = Banner("Could not read your SSH files: ${e.message ?: e.javaClass.simpleName}", bad = true)
            } finally {
                loading = false
                loadGuard.set(false)
            }
        }
    }

    // ------------------------------------------------------------------ hosts

    /** Is each configured host reachable? Bounded: 6 at a time, 2 s each, on request only. */
    fun probeHosts() {
        val hosts = local?.config?.hosts ?: return
        if (!reachGuard.compareAndSet(false, true)) return
        reach = hosts.associate { it.alias to (if (it.proxyJump != null) HostReach.ViaJump else HostReach.Checking) }
        scope.launch {
            try {
                val gate = Semaphore(6)
                hosts.filter { it.proxyJump == null }.map { h ->
                    async {
                        gate.withPermit {
                            val ms = NetFacts.tcp(h.target, h.port ?: 22, 2_000)
                            reach = reach + (h.alias to (if (ms != null) HostReach.Up(ms) else HostReach.Down))
                        }
                    }
                }.forEach { it.await() }
            } finally {
                reachGuard.set(false)
            }
        }
    }

    fun testHost(alias: String) {
        if (alias in testing) return
        testing = testing + alias
        scope.launch {
            try {
                hostTests = hostTests + (alias to exec.testHost(alias))
            } finally {
                testing = testing - alias
            }
        }
    }

    /** Fetch the host's keys and ask the user to confirm the fingerprint before trusting them. */
    fun requestTrust(host: SshHost) {
        val key = "trust:${host.alias}"
        if (key in testing) return
        testing = testing + key
        scope.launch {
            try {
                val port = host.port ?: 22
                val found = exec.scanHostKeys(host.target, port)
                if (found == null) {
                    banner = Banner("Could not fetch ${host.target}'s keys — is it reachable?", bad = true)
                } else {
                    val lines = found.map { it.first }
                    val ok = files.acceptableKnownLines(lines)
                    if (ok.isEmpty()) banner = Banner("The host sent keys this app will not store", bad = true)
                    else pendingTrust = TrustRequest(host.alias, host.target, ok, found.map { it.second }, host.alias)
                }
            } finally {
                testing = testing - key
            }
        }
    }

    fun requestTrustForName(host: String, port: Int = 22) {
        scope.launch {
            val found = exec.scanHostKeys(host, port)
            if (found == null) banner = Banner("Could not fetch $host's keys", bad = true)
            else pendingTrust = TrustRequest(host, host, files.acceptableKnownLines(found.map { it.first }), found.map { it.second }, null)
        }
    }

    fun confirmTrust() {
        val req = pendingTrust ?: return
        pendingTrust = null
        scope.launch {
            try {
                files.appendKnownHosts(req.lines)
                banner = Banner("Trusted ${req.label}", bad = false)
                req.retestAlias?.let { testHost(it) }
            } catch (e: Throwable) {
                banner = Banner("Could not save the host key: ${e.message}", bad = true)
            }
        }
    }

    fun dismissTrust() { pendingTrust = null }

    fun saveHost(h: SshHost, onDone: (String?) -> Unit = {}) {
        scope.launch {
            val result = SshConfig.upsert(files.readConfig(), h)
            when (result) {
                is SshConfig.Edit.Refused -> onDone(result.reason)
                is SshConfig.Edit.Done -> try {
                    files.writeConfig(result.text)
                    reloadLocalNow()
                    onDone(null)
                } catch (e: Throwable) {
                    onDone("Could not write ~/.ssh/config: ${e.message}")
                }
            }
        }
    }

    fun deleteHost(alias: String) {
        scope.launch {
            when (val r = SshConfig.remove(files.readConfig(), alias)) {
                is SshConfig.Edit.Refused -> banner = Banner(r.reason, bad = true)
                is SshConfig.Edit.Done -> try {
                    files.writeConfig(r.text)
                    hostTests = hostTests - alias
                    reloadLocalNow()
                    banner = Banner("Removed $alias", bad = false)
                } catch (e: Throwable) {
                    banner = Banner("Could not write ~/.ssh/config: ${e.message}", bad = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------- keys

    fun createKey(name: String, comment: String, onDone: (String?) -> Unit) {
        if (busy != null) return
        if (!SshKeys.validName(name)) { onDone("Name: letters, digits, . _ -"); return }
        if (!Regex("^[A-Za-z0-9@._ -]{0,60}$").matches(comment)) { onDone("Comment: letters, digits, @ . _ - and spaces"); return }
        busy = "Creating key…"
        scope.launch {
            try {
                if (files.keyExists(name)) { onDone("A key named \"$name\" already exists"); return@launch }
                files.ensureSshDir()
                val r = exec.generateKey(name, comment)
                if (r.exitCode == 0 && files.keyExists(name)) {
                    reloadLocalNow()
                    banner = Banner("Created $name", bad = false)
                    onDone(null)
                } else {
                    onDone(r.error ?: r.stderr.lines().lastOrNull { it.isNotBlank() } ?: "ssh-keygen failed")
                }
            } finally {
                busy = null
            }
        }
    }

    fun deleteKey(name: String) {
        scope.launch {
            if (files.deleteKey(name)) {
                keyTests = keyTests - name
                reloadLocalNow()
                banner = Banner("Deleted $name", bad = false)
            } else {
                banner = Banner("Could not delete $name", bad = true)
            }
        }
    }

    /** Does [service] (github.com / gitlab.com) accept this key? */
    fun testKeyWith(keyName: String, host: String) {
        val id = "key:$keyName:$host"
        if (id in testing) return
        testing = testing + id
        scope.launch {
            try {
                keyTests = keyTests + (keyName to (host to exec.testService("git", host, keyName)))
            } finally {
                testing = testing - id
            }
        }
    }

    // ---------------------------------------------------------------- tunnels

    fun saveTunnel(t: Tunnel, onDone: (String?) -> Unit) {
        Tunnels.problem(t)?.let { onDone(it); return }
        scope.launch {
            try {
                val list = files.readTunnels().filter { it.id != t.id } + t
                files.writeTunnels(list.sortedBy { it.name.lowercase() })
                reloadLocalNow()
                onDone(null)
            } catch (e: Throwable) {
                onDone("Could not save: ${e.message}")
            }
        }
    }

    fun deleteTunnel(id: String) {
        stopTunnel(id)
        scope.launch {
            files.writeTunnels(files.readTunnels().filter { it.id != id })
            tunnelStates = tunnelStates - id
            reloadLocalNow()
        }
    }

    fun startTunnel(t: Tunnel) {
        if (Tunnels.problem(t) != null || live.containsKey(t.id)) return
        tunnelStates = tunnelStates + (t.id to TunnelState.Starting)
        scope.launch {
            val proc = try {
                exec.start(t.id, Tunnels.sshArgv(t))
            } catch (e: Throwable) {
                tunnelStates = tunnelStates + (t.id to TunnelState.Dropped(SshOutcome.SandboxProblem(e.message ?: e.javaClass.simpleName)))
                return@launch
            }
            live[t.id] = proc
            jobIds[t.id] = HostCompat.beginJob(appContext, "SSH tunnel · ${t.name}") { stopTunnel(t.id) }
            // The waiter: when the process ends, say why.
            launch {
                val r = proc.waitForExit()
                live.remove(t.id)
                jobIds.remove(t.id)?.let { HostCompat.endJob(appContext, it) }
                tunnelStates = tunnelStates + (t.id to if (proc.wasStopped) TunnelState.Stopped else TunnelState.Dropped(SshOutcome.classify(r.exitCode ?: -1, r.stderr.ifBlank { r.error.orEmpty() })))
            }
            // The detector: a local forward is up when its port accepts connections (a kernel fact).
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && live[t.id] === proc) {
                delay(400)
                val up = if (t.kind == TunnelKind.REMOTE) System.currentTimeMillis() > deadline - 12_000 else NetFacts.listening("127.0.0.1", t.bindPort)
                if (up && live[t.id] === proc) {
                    tunnelStates = tunnelStates + (t.id to TunnelState.Up(System.currentTimeMillis()))
                    return@launch
                }
            }
            if (live[t.id] === proc) {
                proc.stop()
                tunnelStates = tunnelStates + (t.id to TunnelState.Dropped(SshOutcome.TimedOut))
            }
        }
    }

    fun stopTunnel(id: String) {
        live[id]?.let { scope.launch { it.stop() } }
    }

    // ------------------------------------------------------------------ share

    private var shareServer: ShareServer? = null

    fun startShare(dir: String, ip: String, port: Int, minutes: Int) {
        if (share is ShareState.Starting || share is ShareState.Running) return
        share = ShareState.Starting
        scope.launch {
            try {
                val lan = ShareGuard.lanAddresses(NetFacts.interfaces().map { it.name to it.ip })
                if (lan.none { it.second == ip }) { share = ShareState.Failed("Not on Wi-Fi: no local network address to share on"); return@launch }
                ShareGuard.portProblem(port)?.let { share = ShareState.Failed(it); return@launch }
                if (minutes !in ShareGuard.DURATIONS_MIN) { share = ShareState.Failed("Choose a time limit"); return@launch }
                val host = files.hostPath(dir)
                if (host == null || !host.isDirectory) { share = ShareState.Failed("That folder does not exist"); return@launch }
                val (names, truncated) = files.scanNames(host)
                ShareGuard.refusal(dir, names, truncated)?.let { share = ShareState.Failed(it); return@launch }
                val norm = ShareGuard.normalize(dir)!!
                val server = ShareServer(host, ip, port)
                try {
                    server.start()
                } catch (e: java.io.IOException) {
                    share = ShareState.Failed(if (e is java.net.BindException) "Port $port is already in use" else (e.message ?: "Could not start the server"))
                    return@launch
                }
                shareServer = server
                val endsAt = System.currentTimeMillis() + minutes * 60_000L
                jobIds["share"] = HostCompat.beginJob(appContext, "Sharing · $norm", expiresAfterMs = minutes * 60_000L + 30_000L) { stopShare() }
                share = ShareState.Running(ShareGuard.url(ip, port), norm, endsAt, ip, port)
                launch {
                    while (shareServer === server && System.currentTimeMillis() < endsAt) delay(1_000)
                    if (shareServer === server) stopShare()
                }
            } catch (e: Throwable) {
                share = ShareState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun stopShare() {
        val server = shareServer ?: return
        shareServer = null
        server.stop()
        jobIds.remove("share")?.let { HostCompat.endJob(appContext, it) }
        share = ShareState.Off
    }

    // -------------------------------------------------------------- listening

    fun refreshListening() {
        if (!listenGuard.compareAndSet(false, true)) return
        scope.launch {
            try {
                val lan = ShareGuard.lanAddresses(NetFacts.interfaces().map { it.name to it.ip }).firstOrNull()?.second
                listening = serverProbe.snapshot().map {
                    ListeningServer(it.port, it.displayName, lan?.let { ip -> NetFacts.listening(ip, it.port) })
                }.sortedBy { it.port }
            } catch (_: Throwable) {
                listening = emptyList()
            } finally {
                listenGuard.set(false)
            }
        }
    }

    // ----------------------------------------------------------------- checks

    fun runChecks() {
        if (checking) return
        checking = true
        scope.launch {
            try {
                checks = NetFacts.runChecks(files.resolvConfText())
            } finally {
                checking = false
            }
        }
    }

    // -------------------------------------------------------------- home lab

    /** Sweep this phone's own subnet for live devices. Bounded, cancellable, on request only. */
    fun scanDevices() {
        if (!scanGuard.compareAndSet(false, true)) return
        scanCancelled.set(false)
        scanning = true
        scanProgress = null
        scope.launch {
            try {
                val iface = NetFacts.interfaces().sortedByDescending { it.isWifi }.firstOrNull { LabNet.sweepTargets(it.ip, it.prefix).isNotEmpty() }
                if (iface == null) {
                    devices = emptyList()
                    banner = Banner("No local network to scan - connect to Wi-Fi first", bad = true)
                    return@launch
                }
                val found = NetFacts.sweep(iface, cancelled = { scanCancelled.get() }, onProgress = { d, t -> scanProgress = d to t })
                devices = found
                // Names last, a few at a time: the system resolver is slow on a home network.
                val gate = Semaphore(8)
                val named = found.map { f -> async { gate.withPermit { f.copy(name = NetFacts.reverseName(f.ip)) } } }.map { it.await() }
                if (!scanCancelled.get()) devices = named
            } catch (e: Throwable) {
                banner = Banner("Scan failed: ${e.message ?: e.javaClass.simpleName}", bad = true)
            } finally {
                scanning = false
                scanProgress = null
                scanGuard.set(false)
            }
        }
    }

    fun cancelScan() { scanCancelled.set(true) }

    fun saveLabServer(s: LabServer, onDone: (String?) -> Unit) {
        LabServers.problem(s)?.let { onDone(it); return }
        scope.launch {
            try {
                files.writeLab((files.readLab().filter { it.id != s.id } + s).sortedBy { it.name.lowercase() })
                reloadLocalNow()
                onDone(null)
            } catch (e: Throwable) {
                onDone("Could not save: ${e.message}")
            }
        }
    }

    fun deleteLabServer(id: String) {
        scope.launch {
            files.writeLab(files.readLab().filter { it.id != id })
            health = health - id
            vitals = vitals - id
            reloadLocalNow()
        }
    }

    /** One health reading for [server]; with an ssh alias it also reads load, memory and disk. */
    fun checkServer(server: LabServer) {
        if (server.id in healthChecking) return
        healthChecking = healthChecking + server.id
        scope.launch {
            try {
                health = health + (server.id to NetFacts.health(server))
                if (server.sshAlias.isNotEmpty() && health[server.id]?.health != Health.DOWN) {
                    vitals = vitals + (server.id to exec.vitals(server.sshAlias))
                }
            } finally {
                healthChecking = healthChecking - server.id
            }
        }
    }

    fun checkAllServers() { local?.lab?.forEach { checkServer(it) } }

    // ------------------------------------------------------------------ tools

    private fun runTool(name: String, host: String?, block: suspend () -> ToolResult?) {
        if (host != null && !LabNet.validHost(host)) { banner = Banner("\"${host.take(40)}\" is not a hostname or IPv4 address", bad = true); return }
        if (!toolGuard.compareAndSet(false, true)) return
        toolRunning = name
        toolResult = null
        scope.launch {
            try {
                toolResult = block()
            } catch (e: Throwable) {
                banner = Banner("$name failed: ${e.message ?: e.javaClass.simpleName}", bad = true)
            } finally {
                toolRunning = null
                toolGuard.set(false)
            }
        }
    }

    fun runDns(name: String, typeName: String, server: String?) {
        val type = Dns.TYPES[typeName] ?: return
        val target = if (typeName == "PTR") Dns.reverseName(name) else name.trim().trimEnd('.')
        if (target == null) { banner = Banner("A PTR lookup needs an IPv4 address", bad = true); return }
        runTool("DNS", if (typeName == "PTR") null else target) {
            val resolver = server?.takeIf { it.isNotBlank() }
                ?: Checks.nameservers(files.resolvConfText()).firstOrNull()
                ?: "1.1.1.1"
            if (!LabNet.validHost(resolver) || LabNet.ipv4ToInt(resolver) == null) {
                banner = Banner("The DNS server must be an IPv4 address", bad = true)
                return@runTool null
            }
            val r = NetFacts.dnsLookup(resolver, target, type)
            ToolResult.DnsResult(name.trim(), typeName, resolver, r?.first, r?.second)
        }
    }

    fun runPing(host: String) = runTool("Ping", host.trim()) {
        val h = host.trim()
        when (val o = exec.ping(h)) {
            is PingOutcome.Done -> ToolResult.PingResult(h, o)
            is PingOutcome.CannotRun -> {
                // The fallback is honest about being a TCP connect, not ICMP.
                val port = listOf(443, 80, 22).firstOrNull { NetFacts.tcp(h, it, 1_500) != null }
                if (port == null) ToolResult.PingResult(h, o)
                else ToolResult.PingResult(h, PingOutcome.Done(PingParse.fromTcp((1..4).map { NetFacts.tcp(h, port, 2_000) }), "TCP connect to port $port (ICMP ping is not available here)"))
            }
        }
    }

    fun runTrace(host: String) = runTool("Traceroute", host.trim()) {
        val r = exec.trace(host.trim())
        ToolResult.TraceResult(host.trim(), r?.first, r?.second)
    }

    fun runPorts(host: String, spec: String) {
        val parsed = PortSpec.parse(spec)
        if (parsed is PortSpec.Parsed.Bad) { banner = Banner(parsed.reason, bad = true); return }
        val ports = (parsed as PortSpec.Parsed.Ports).ports
        runTool("Port check", host.trim()) { ToolResult.PortsResult(host.trim(), NetFacts.scanPorts(host.trim(), ports)) }
    }

    // ------------------------------------------------------------------- misc

    fun dismissBanner() { banner = null }

    /** Inline reload for use inside an already-running coroutine (no guard dance). */
    private fun reloadLocalNow() {
        val tunnels = files.readTunnels()
        local = LocalSnapshot(SshConfig.parse(files.readConfig()), files.listKeys(), tunnels, NetFacts.interfaces(), files.readLab())
        tunnelStates = tunnels.associate { it.id to (tunnelStates[it.id] ?: TunnelState.Stopped) }
    }
}
