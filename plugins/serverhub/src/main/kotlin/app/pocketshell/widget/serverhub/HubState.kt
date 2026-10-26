package app.pocketshell.widget.serverhub

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class HubTab(val label: String) { SERVICES("Services"), PORTS("Ports"), NEW("New") }

/** What a service is doing right now (not saved). */
data class Live(
    val phase: Phase = Phase.STOPPED,
    val pid: Int? = null,
    val startedAtMs: Long? = null,
    val restarts: Int = 0,
    val note: String? = null,
    val health: HealthReading? = null,
    val wifiOk: Boolean? = null,
    val checking: Boolean = false,
)

/**
 * The ONE holder for the Server Hub. It outlives composition, so every start, stop, restart and probe runs
 * on its own scope - a page swipe can neither cancel nor re-run them. Each action is single-flight per service
 * and clears its flags in a `finally`. While a service runs, a host job keeps PocketShell alive in the background.
 * Nothing polls: health is read when a service has started, and on request.
 */
class HubState(context: Context) {

    private val appContext = context.applicationContext
    val files = HubFiles(appContext)
    private val exec = GuestExec(appContext, "hubjobs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var tab by mutableStateOf(HubTab.SERVICES)
    var studio by mutableStateOf(false)
    var detail by mutableStateOf<String?>(null)

    @Volatile var loadedAtMs = 0L
    var services by mutableStateOf<List<Service>?>(null)
    var loading by mutableStateOf(false)
    var banner by mutableStateOf<Banner?>(null)

    var live by mutableStateOf<Map<String, Live>>(emptyMap())
    var lanIp by mutableStateOf<String?>(null)
    var ports by mutableStateOf<List<ListeningRow>?>(null)
    var scanningPorts by mutableStateOf(false)

    private val procs = ConcurrentHashMap<String, LiveProcess>()
    private val jobIds = ConcurrentHashMap<String, Long>()
    private val userStopped = ConcurrentHashMap<String, Boolean>()
    private val restartTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val loadGuard = AtomicBoolean(false)
    private val portGuard = AtomicBoolean(false)

    // ------------------------------------------------------------------ reading

    fun reload() {
        if (!loadGuard.compareAndSet(false, true)) return
        loading = true
        scope.launch {
            try { reloadNow() } catch (e: Throwable) {
                banner = Banner("Could not read the saved services: ${e.message ?: e.javaClass.simpleName}", bad = true)
            } finally {
                loading = false
                loadGuard.set(false)
            }
        }
    }

    private fun reloadNow() {
        services = files.readServices()
        lanIp = HubNet.lanAddress()
        loadedAtMs = System.currentTimeMillis()
    }

    fun serviceById(id: String) = services?.firstOrNull { it.id == id }

    fun liveOf(id: String): Live = live[id] ?: Live()

    private fun setLive(id: String, f: (Live) -> Live) { live = live + (id to f(liveOf(id))) }

    fun runningCount(): Int = live.values.count { it.phase == Phase.RUNNING || it.phase == Phase.STARTING }

    // ------------------------------------------------------------------- saving

    fun save(s: Service, onDone: (String?) -> Unit) {
        Services.problem(s)?.let { onDone(it); return }
        scope.launch {
            try {
                files.writeServices(((files.readServices().filter { it.id != s.id }) + s).sortedBy { it.name.lowercase() })
                reloadNow()
                onDone(null)
            } catch (e: Throwable) {
                onDone("Could not save: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** A new service from a template: make the folder and its starter files, then save. */
    fun createFromTemplate(t: Template, name: String, port: Int, dir: String, wifi: Boolean, restart: RestartMode, onDone: (String?) -> Unit) {
        val command = Templates.fill(t, port, dir) ?: run { onDone("Folder and port: no spaces or special characters in the folder"); return }
        val s = Service(Services.newId(), name.trim(), command, dir, port, t.check, t.path, restart, wifi && port != 0)
        Services.problem(s)?.let { onDone(it); return }
        if (services.orEmpty().any { it.port != 0 && it.port == port }) { onDone("Port $port is already used by another service"); return }
        scope.launch {
            files.prepareFolder(dir, t.files)?.let { onDone(it); return@launch }
            save(s, onDone)
        }
    }

    fun delete(id: String) {
        stop(id)
        scope.launch {
            files.writeServices(files.readServices().filter { it.id != id })
            live = live - id
            reloadNow()
            banner = Banner("Service removed. Its folder and log were kept.", bad = false)
        }
    }

    // ----------------------------------------------------------------- lifecycle

    fun start(id: String) {
        val s = serviceById(id) ?: return
        val phase = liveOf(id).phase
        if (phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.STOPPING) return
        val argv = Services.argv(s) ?: run { banner = Banner("The command is not valid any more - edit the service", bad = true); return }
        if (!files.hasProgram(argv.first())) {
            val hint = Templates.all.firstOrNull { t -> argv.first() in t.needs }?.packages?.takeIf { it.isNotEmpty() }?.let { " - install it: apt-get install ${it.joinToString(" ")}" }.orEmpty()
            banner = Banner("${argv.first()} is not installed$hint", bad = true)
            return
        }
        if (!files.folderExists(s.cwd)) { banner = Banner("The folder ${s.cwd} does not exist", bad = true); return }
        if (s.port != 0 && HubNet.tcp("127.0.0.1", s.port, 300) != null) {
            banner = Banner("Port ${s.port} is already in use by something else - see the Ports tab", bad = true)
            return
        }
        userStopped.remove(id)
        restartTimes.remove(id)
        setLive(id) { Live(phase = Phase.STARTING, startedAtMs = System.currentTimeMillis(), restarts = 0) }
        scope.launch { spawn(s, argv) }
    }

    private suspend fun spawn(s: Service, argv: List<String>) {
        files.prepareLog(s.id)
        files.appendLog(s.id, "--- started ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}: ${s.command}")
        val proc = try {
            exec.start(s.id, argv, "/root/.cache/pocketshell/hub/${s.id}.log", cwd = s.cwd)
        } catch (e: Throwable) {
            setLive(s.id) { it.copy(phase = Phase.CRASHED, note = e.message ?: e.javaClass.simpleName) }
            return
        }
        procs[s.id] = proc
        jobIds[s.id]?.let { HostCompat.endJob(appContext, it) }
        jobIds[s.id] = HostCompat.beginJob(appContext, "Server · ${s.name}") { stop(s.id) }

        // The waiter: when the process ends, decide what happens next.
        scope.launch {
            val r = proc.waitForExit()
            if (procs[s.id] === proc) procs.remove(s.id)
            jobIds.remove(s.id)?.let { HostCompat.endJob(appContext, it) }
            val stopped = proc.wasStopped || userStopped[s.id] == true
            val now = System.currentTimeMillis()
            val times = restartTimes.getOrPut(s.id) { java.util.Collections.synchronizedList(ArrayList()) }
            val decision = RestartPolicy.decide(s.restart, r.exitCode, stopped, times.toList(), now)
            val why = lastProblem(s.id)
            when (decision) {
                is RestartPolicy.Decision.Restart -> {
                    times += now
                    setLive(s.id) { it.copy(phase = Phase.STARTING, pid = null, note = "exited (${r.exitCode ?: "?"}) - restarting in ${decision.afterMs / 1000}s, attempt ${decision.attempt}", restarts = it.restarts + 1, health = null) }
                    delay(decision.afterMs)
                    if (userStopped[s.id] != true && procs[s.id] == null) spawn(s, argv)
                    else setLive(s.id) { it.copy(phase = Phase.STOPPED, note = "stopped") }
                }
                is RestartPolicy.Decision.GiveUp -> setLive(s.id) { it.copy(phase = Phase.CRASHED, pid = null, note = decision.reason + (why?.let { w -> " - $w" } ?: ""), health = null) }
                RestartPolicy.Decision.None -> setLive(s.id) {
                    if (stopped || r.exitCode == 0) it.copy(phase = Phase.STOPPED, pid = null, note = if (stopped) "stopped" else "finished", health = null)
                    else it.copy(phase = Phase.CRASHED, pid = null, note = "exited with code ${r.exitCode ?: "?"}" + (why?.let { w -> " - $w" } ?: ""), health = null)
                }
            }
        }

        // The detector: running once the port answers (or, with no port, once it has stayed alive a moment).
        if (s.port != 0) {
            val deadline = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < deadline && procs[s.id] === proc) {
                if (HubNet.tcp("127.0.0.1", s.port, 400) != null) {
                    setLive(s.id) { it.copy(phase = Phase.RUNNING, pid = proc.pid(), note = null) }
                    check(s.id)
                    return
                }
                delay(500)
            }
            if (procs[s.id] === proc) {
                setLive(s.id) { it.copy(phase = Phase.RUNNING, pid = proc.pid(), note = "started, but nothing listens on port ${s.port} yet", health = HealthReading(Health.DOWN, "nothing is listening on the port")) }
            }
        } else {
            delay(1_500)
            if (procs[s.id] === proc && proc.isAlive()) setLive(s.id) { it.copy(phase = Phase.RUNNING, pid = proc.pid(), note = null) }
        }
    }

    /** The most telling recent log line (an error if there is one), for the "why did it stop" note. */
    private fun lastProblem(id: String): String? {
        val tail = files.logTail(id, 30)
        return (tail.lastOrNull { LogLines.level(it) == LogLines.Level.ERROR } ?: tail.lastOrNull())?.trim()?.take(120)
    }

    fun stop(id: String) {
        val p = procs[id] ?: run {
            // nothing running: make sure a pending automatic restart is cancelled
            userStopped[id] = true
            if (liveOf(id).phase == Phase.STARTING) setLive(id) { it.copy(phase = Phase.STOPPED, note = "stopped") }
            return
        }
        userStopped[id] = true
        setLive(id) { it.copy(phase = Phase.STOPPING, note = null) }
        scope.launch { p.stop(); delay(3_000); if (procs[id] === p) p.kill() }
    }

    fun restart(id: String) {
        val p = procs[id]
        if (p == null) { start(id); return }
        userStopped[id] = true
        setLive(id) { it.copy(phase = Phase.STOPPING, note = "restarting…") }
        scope.launch {
            p.stop(); delay(2_000); if (procs[id] === p) p.kill()
            var waited = 0
            while (procs[id] != null && waited < 20) { delay(250); waited++ }
            if (procs[id] == null) { setLive(id) { it.copy(phase = Phase.STOPPED) }; start(id) }
        }
    }

    fun stopAll() { procs.keys.toList().forEach { stop(it) } }

    // -------------------------------------------------------------------- health

    fun check(id: String) {
        val s = serviceById(id) ?: return
        if (liveOf(id).checking) return
        setLive(id) { it.copy(checking = true) }
        scope.launch {
            try {
                val reading = HubNet.health(s)
                val ip = HubNet.lanAddress()
                lanIp = ip
                val wifiOk = if (s.wifi && ip != null && s.port != 0) HubNet.reachable(ip, s.port) else null
                setLive(id) { it.copy(health = reading, wifiOk = wifiOk) }
            } finally {
                setLive(id) { it.copy(checking = false) }
            }
        }
    }

    fun checkAll() { services.orEmpty().filter { liveOf(it.id).phase == Phase.RUNNING }.forEach { check(it.id) } }

    // --------------------------------------------------------------------- ports

    fun scanPorts() {
        if (!portGuard.compareAndSet(false, true)) return
        scanningPorts = true
        scope.launch {
            try {
                val lan = HubNet.lanAddress()
                lanIp = lan
                val mine = services.orEmpty().filter { it.port != 0 }.associateBy { it.port }
                ports = HubNet.listening().map { r ->
                    r.copy(hubServiceId = mine[r.port]?.id, wifi = lan?.let { ip -> HubNet.reachable(ip, r.port) })
                }
            } finally {
                scanningPorts = false
                portGuard.set(false)
            }
        }
    }

    // ---------------------------------------------------------------------- misc

    fun logTail(id: String) = files.logTail(id)
    fun clearLog(id: String) = files.clearLog(id)
    fun dismissBanner() { banner = null }
}
