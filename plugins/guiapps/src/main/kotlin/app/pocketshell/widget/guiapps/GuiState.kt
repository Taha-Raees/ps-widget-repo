package app.pocketshell.widget.guiapps

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class GuiTab(val label: String) { DISCOVER("Discover"), MINE("My apps"), SETUP("Setup") }

/** What an install / update / removal is doing right now. */
sealed interface OpState {
    data class Working(val text: String, val fraction: Float? = null) : OpState
    data class Failed(val reason: String) : OpState
}

enum class Phase { STARTING, RUNNING, EXITED, FAILED }

data class RunInfo(val phase: Phase, val startedAtMs: Long, val detail: String? = null)

/**
 * The ONE holder for the GUI App Library. It outlives composition, so:
 *  - work runs on its own process-scoped [scope], never a LaunchedEffect - a page swipe can neither
 *    cancel nor re-run an install or a launch;
 *  - every action is single-flight per app (and one apk operation at a time) and clears itself in a `finally`;
 *  - nothing polls: the card reads a few small files once it has settled on screen.
 */
class GuiState(context: Context) {

    private val appContext = context.applicationContext
    val files = GuiFiles(appContext)
    private val exec = GuestExec(appContext, "guijobs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var tab by mutableStateOf(GuiTab.DISCOVER)
    var studio by mutableStateOf(false)
    var query by mutableStateOf("")
    var runtimeFilter by mutableStateOf<Runtime?>(null)
    var categoryFilter by mutableStateOf<GuiCategory?>(null)

    /** The row (app id) expanded in the current tab; Back collapses it. */
    var detail by mutableStateOf<String?>(null)

    @Volatile var loadedAtMs = 0L
    var snapshot by mutableStateOf<Snapshot?>(null)
    var loading by mutableStateOf(false)
    var banner by mutableStateOf<Banner?>(null)
    var custom by mutableStateOf<List<CatalogApp>>(emptyList())

    var ops by mutableStateOf<Map<String, OpState>>(emptyMap())
    var running by mutableStateOf<Map<String, RunInfo>>(emptyMap())
    var apkUpdates by mutableStateOf<Map<String, String>>(emptyMap())
    var imageUpdates by mutableStateOf<Map<String, String>>(emptyMap())
    var checkingUpdates by mutableStateOf(false)

    /** A web app that is ready: the page opens it in the companion browser and clears this. */
    var openUrl by mutableStateOf<String?>(null)

    private val live = ConcurrentHashMap<String, LiveProcess>()
    private val jobIds = ConcurrentHashMap<String, Long>()
    private val loadGuard = AtomicBoolean(false)
    private val apkGuard = AtomicBoolean(false)
    private val updateGuard = AtomicBoolean(false)
    private val cancelled = ConcurrentHashMap<String, Boolean>()

    val catalog: List<CatalogApp> get() = GuiCatalog.all + custom

    fun appById(id: String): CatalogApp? = catalog.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ reading

    /** Re-read the small local files (apk db, registry, .desktop files, session socket). */
    fun reload() {
        if (!loadGuard.compareAndSet(false, true)) return
        loading = true
        scope.launch {
            try { reloadNow() } catch (e: Throwable) {
                banner = Banner("Could not read the Linux files: ${e.message ?: e.javaClass.simpleName}", bad = true)
            } finally {
                loading = false
                loadGuard.set(false)
            }
        }
    }

    private fun reloadNow() {
        custom = readCustom()
        snapshot = files.snapshot()
        loadedAtMs = System.currentTimeMillis()
    }

    private fun readCustom(): List<CatalogApp> = try {
        val f = File(files.home, ".config/pocketshell/gui-custom.txt")
        if (f.isFile && f.length() < 64_000) CustomApps.decode(f.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeCustom(list: List<CatalogApp>) {
        val dir = File(files.home, ".config/pocketshell").apply { mkdirs() }
        File(dir, "gui-custom.txt").writeText(CustomApps.encode(list))
    }

    fun isInstalled(app: CatalogApp): Boolean {
        val s = snapshot ?: return false
        return when (app.runtime) {
            Runtime.MUSL -> s.apk.containsKey(app.apk)
            Runtime.GLIBC -> s.images.any { it.id == app.id && File(files.home, it.relPath).isFile }
        }
    }

    fun installedVersion(app: CatalogApp): String? {
        val s = snapshot ?: return null
        return if (app.runtime == Runtime.MUSL) s.apk[app.apk] else s.images.firstOrNull { it.id == app.id }?.version
    }

    fun installedCount(): Int = catalog.count { isInstalled(it) }

    /** Launchers in the guest that are not one of the catalog's apps (installed some other way). */
    fun otherLaunchers(): List<DesktopApp> {
        val s = snapshot ?: return emptyList()
        val known = catalog.filter { isInstalled(it) }.map { it.exec.lowercase() }.toSet()
        return s.desktop.filter { d ->
            val base = d.entry.command.first().substringAfterLast('/').lowercase()
            base !in known && !known.any { base.startsWith(it) }
        }
    }

    // ------------------------------------------------------------------ install

    fun install(app: CatalogApp) {
        if (app.id in ops && ops[app.id] is OpState.Working) return
        if (app.runtime == Runtime.MUSL && !apkGuard.compareAndSet(false, true)) {
            banner = Banner("Another package operation is running - wait for it to finish", bad = true)
            return
        }
        setOp(app.id, OpState.Working("Starting…"))
        cancelled.remove(app.id)
        scope.launch {
            val job = HostCompat.beginJob(appContext, "Installing ${app.name}")
            try {
                val ok = if (app.runtime == Runtime.MUSL) installApk(app) else installImage(app)
                if (ok) { clearOp(app.id); banner = Banner("${app.name} is installed", bad = false) }
            } catch (e: Throwable) {
                setOp(app.id, OpState.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                HostCompat.endJob(appContext, job)
                if (app.runtime == Runtime.MUSL) apkGuard.set(false)
                runCatching { reloadNow() }
            }
        }
    }

    private fun installApk(app: CatalogApp): Boolean {
        val pkgs = listOf(app.apk!!) + app.extraApk
        val add = listOf("/sbin/apk", "--no-progress", "add") + pkgs
        setOp(app.id, OpState.Working("Installing ${pkgs.joinToString(" ")}…"))
        var r = exec.run(add, APK_TIMEOUT_MS, apk = true)
        if (!r.ok && ApkOutput.needsUpdate(r.stdout + r.stderr)) {
            setOp(app.id, OpState.Working("Refreshing the package index…"))
            exec.run(listOf("/sbin/apk", "--no-progress", "update"), 5 * 60_000L, apk = true)
            setOp(app.id, OpState.Working("Installing ${pkgs.joinToString(" ")}…"))
            r = exec.run(add, APK_TIMEOUT_MS, apk = true)
        }
        if (!r.ok) { setOp(app.id, OpState.Failed(ApkOutput.reason(r.stdout, r.stderr, r.error))); return false }
        return true
    }

    private fun installImage(app: CatalogApp): Boolean {
        val dir = files.appDir(app.id) ?: run { setOp(app.id, OpState.Failed("bad app id")); return false }
        val asset: ReleaseAsset = if (app.directUrl != null) {
            val name = CustomApp.fileName(app.directUrl) ?: run { setOp(app.id, OpState.Failed("bad link")); return false }
            ReleaseAsset("custom", name, app.directUrl, 0L, null)
        } else {
            setOp(app.id, OpState.Working("Looking up the latest release…"))
            when (val f = GuiNet.latest(app.repo!!, app.asset!!)) {
                is GuiNet.Fetch.Failed -> { setOp(app.id, OpState.Failed(f.reason)); return false }
                is GuiNet.Fetch.Ok -> f.asset
            }
        }
        dir.mkdirs()
        val dest = File(dir, asset.name)
        setOp(app.id, OpState.Working("Downloading ${asset.name}…", 0f))
        val result = GuiNet.download(asset.url, dest, asset.size, asset.sha256, cancelled = { cancelled[app.id] == true }) { got, total ->
            val frac = if (total > 0) got.toFloat() / total else null
            setOp(app.id, OpState.Working("Downloading ${asset.name}  ${got / 1_048_576} MB" + (if (total > 0) " of ${total / 1_048_576} MB" else ""), frac))
        }
        when (result) {
            GuiNet.Download.Cancelled -> { clearOp(app.id); return false }
            is GuiNet.Download.Failed -> { setOp(app.id, OpState.Failed(result.reason)); return false }
            GuiNet.Download.Done -> Unit
        }
        val relPath: String
        val file: String
        if (app.delivery == Delivery.TARBALL_WEB) {
            setOp(app.id, OpState.Working("Unpacking…"))
            val target = File(dir, "app").apply { deleteRecursively(); mkdirs() }
            val r = exec.run(listOf("tar", "xzf", "/root/Applications/${app.id}/${asset.name}", "-C", "/root/Applications/${app.id}/${target.name}", "--strip-components=1"), 20 * 60_000L)
            dest.delete()
            if (!r.ok) { setOp(app.id, OpState.Failed("could not unpack: " + r.tail(2))); return false }
            file = app.exec
            relPath = "Applications/${app.id}/app/bin/${app.exec}"
            if (!File(files.home, relPath).isFile) { setOp(app.id, OpState.Failed("the archive does not contain bin/${app.exec}")); return false }
        } else {
            dest.setExecutable(true, false)
            dir.listFiles { f -> f.isFile && f.name != asset.name }?.forEach { it.delete() }
            file = asset.name
            relPath = InstalledImages.relPath(app.id, asset.name)
        }
        files.writeImages(files.readImages().filter { it.id != app.id } + InstalledImage(app.id, file, asset.tag, relPath))
        if (app.directUrl != null) writeCustom((readCustom().filter { it.id != app.id }) + app)
        return true
    }

    fun cancelInstall(id: String) { cancelled[id] = true }

    // ---------------------------------------------------------------- uninstall

    fun uninstall(app: CatalogApp) {
        if (ops[app.id] is OpState.Working) return
        if (app.runtime == Runtime.MUSL && !apkGuard.compareAndSet(false, true)) {
            banner = Banner("Another package operation is running - wait for it to finish", bad = true)
            return
        }
        stop(app.id)
        setOp(app.id, OpState.Working("Removing…"))
        scope.launch {
            try {
                if (app.runtime == Runtime.MUSL) {
                    val r = exec.run(listOf("/sbin/apk", "--no-progress", "del", app.apk!!), APK_TIMEOUT_MS, apk = true)
                    if (!r.ok) { setOp(app.id, OpState.Failed(ApkOutput.reason(r.stdout, r.stderr, r.error))); return@launch }
                } else {
                    if (!files.deleteAppDir(app.id)) { setOp(app.id, OpState.Failed("could not delete the app folder")); return@launch }
                    files.writeImages(files.readImages().filter { it.id != app.id })
                    if (app.directUrl != null) writeCustom(readCustom().filter { it.id != app.id })
                }
                clearOp(app.id)
                banner = Banner("${app.name} was removed", bad = false)
            } catch (e: Throwable) {
                setOp(app.id, OpState.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                if (app.runtime == Runtime.MUSL) apkGuard.set(false)
                runCatching { reloadNow() }
            }
        }
    }

    // ------------------------------------------------------------------ updates

    /** Which installed apps have a newer version: apk's own list, and the latest GitHub release for downloaded ones. */
    fun checkUpdates() {
        if (!updateGuard.compareAndSet(false, true)) return
        checkingUpdates = true
        scope.launch {
            try {
                val snap = files.snapshot()
                val r = exec.run(listOf("/sbin/apk", "--no-progress", "list", "-u"), 3 * 60_000L, apk = true)
                val upgradable = if (r.ok) ApkOutput.upgradable(r.stdout) else emptyMap()
                apkUpdates = catalog.filter { it.runtime == Runtime.MUSL && it.apk in snap.apk }.mapNotNull { a ->
                    upgradable[a.apk]?.let { a.id to it }
                }.toMap()
                val newer = HashMap<String, String>()
                for (img in snap.images) {
                    val app = appById(img.id) ?: continue
                    if (app.repo == null) continue
                    val f = GuiNet.latest(app.repo, app.asset!!)
                    if (f is GuiNet.Fetch.Ok && f.asset.tag != img.version) newer[img.id] = f.asset.tag
                }
                imageUpdates = newer
                val n = apkUpdates.size + newer.size
                banner = Banner(if (n == 0) "Everything is up to date" else "$n update${if (n == 1) "" else "s"} available - see My apps", bad = false)
                snapshot = snap
            } catch (e: Throwable) {
                banner = Banner("Update check failed: ${e.message ?: e.javaClass.simpleName}", bad = true)
            } finally {
                checkingUpdates = false
                updateGuard.set(false)
            }
        }
    }

    fun hasUpdate(app: CatalogApp): String? = if (app.runtime == Runtime.MUSL) apkUpdates[app.id] else imageUpdates[app.id]

    /** Update = the same install path again (apk upgrades in place with `add -u`; an image re-downloads the latest). */
    fun update(app: CatalogApp) {
        if (app.runtime == Runtime.GLIBC) { install(app); return }
        if (ops[app.id] is OpState.Working) return
        if (!apkGuard.compareAndSet(false, true)) { banner = Banner("Another package operation is running", bad = true); return }
        setOp(app.id, OpState.Working("Updating…"))
        scope.launch {
            try {
                val r = exec.run(listOf("/sbin/apk", "--no-progress", "add", "-u", app.apk!!), APK_TIMEOUT_MS, apk = true)
                if (r.ok) { clearOp(app.id); apkUpdates = apkUpdates - app.id; banner = Banner("${app.name} is up to date", bad = false) }
                else setOp(app.id, OpState.Failed(ApkOutput.reason(r.stdout, r.stderr, r.error)))
            } finally {
                apkGuard.set(false)
                runCatching { reloadNow() }
            }
        }
    }

    // ------------------------------------------------------------------- launch

    private fun session() = GuiSession.env(files.hasXauthority())

    fun launch(app: CatalogApp) {
        val path = if (app.runtime == Runtime.GLIBC) {
            val img = snapshot?.images?.firstOrNull { it.id == app.id } ?: run { banner = Banner("${app.name} is not installed", bad = true); return }
            "/root/${img.relPath}"
        } else null
        start(app.id, app.name, LaunchPlan.forCatalog(app, path, session()), glibc = app.runtime == Runtime.GLIBC, web = app.webPort)
    }

    fun launch(d: DesktopApp) {
        start("desktop-" + d.entry.fileId.take(40), d.entry.name, LaunchPlan.forDesktop(d.entry, session()), glibc = d.flavour == Flavour.GLIBC, web = null)
    }

    fun launchId(d: DesktopApp) = "desktop-" + d.entry.fileId.take(40)

    private fun start(id: String, name: String, argv: List<String>, glibc: Boolean, web: Int?) {
        if (!id.matches(Regex("^[a-z0-9._-]{1,60}$", RegexOption.IGNORE_CASE))) { banner = Banner("This launcher cannot be started from here", bad = true); return }
        val current = running[id]
        if (current != null && (current.phase == Phase.STARTING || current.phase == Phase.RUNNING)) return
        if (!files.sessionReady() && web == null) {
            banner = Banner("The desktop session is not running - the window may not appear. Start the PocketShell desktop first.", bad = true)
        }
        val startedAt = System.currentTimeMillis()
        running = running + (id to RunInfo(Phase.STARTING, startedAt))
        scope.launch {
            val proc = try {
                files.logsDir.mkdirs()
                files.logFile(id).delete()
                exec.start(id, argv, "/root/.cache/pocketshell/gui/$id.log", cwd = "/root")
            } catch (e: Throwable) {
                running = running + (id to RunInfo(Phase.FAILED, startedAt, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            live[id] = proc
            jobIds[id] = HostCompat.beginJob(appContext, "GUI · $name") { stop(id) }
            // The waiter: when the program ends, say why.
            launch {
                val r = proc.waitForExit()
                live.remove(id)
                jobIds.remove(id)?.let { HostCompat.endJob(appContext, it) }
                val tail = files.logTail(id)
                val early = System.currentTimeMillis() - startedAt < 20_000
                val failed = !proc.wasStopped && ((r.exitCode ?: 0) != 0 || early)
                val why = if (proc.wasStopped) null else LaunchDiagnosis.diagnose(tail + "\n" + r.stderr, r.exitCode, glibc)
                running = running + (id to RunInfo(
                    if (failed) Phase.FAILED else Phase.EXITED, startedAt,
                    if (proc.wasStopped) "stopped" else why ?: if (failed) "exited with code ${r.exitCode ?: "?"}" else "closed",
                ))
            }
            if (web != null) {
                val deadline = System.currentTimeMillis() + 45_000
                while (System.currentTimeMillis() < deadline && live[id] === proc) {
                    if (GuiNet.portOpen(web)) { running = running + (id to RunInfo(Phase.RUNNING, startedAt)); openUrl = "http://127.0.0.1:$web/"; return@launch }
                    delay(700)
                }
                if (live[id] === proc) { banner = Banner("$name did not start listening on port $web - see its log", bad = true) }
            } else {
                // A window app counts as running once it has stayed alive for a moment.
                delay(3_000)
                if (live[id] === proc && proc.isAlive()) running = running + (id to RunInfo(Phase.RUNNING, startedAt))
            }
        }
    }

    fun stop(id: String) {
        live[id]?.let { p -> scope.launch { p.stop(); delay(2_500); if (live[id] === p) p.kill() } }
    }

    fun logOf(id: String): String = files.logTail(id, 40)

    fun isRunning(id: String): Boolean = running[id]?.phase.let { it == Phase.STARTING || it == Phase.RUNNING }

    fun runningCount(): Int = running.values.count { it.phase == Phase.STARTING || it.phase == Phase.RUNNING }

    // ------------------------------------------------------------------- custom

    fun addCustom(name: String, url: String, onDone: (String?) -> Unit) {
        CustomApp.problem(name, url)?.let { onDone(it); return }
        val app = CustomApps.make(CustomApp.id(name), name, url.trim())
        if (GuiCatalog.byId(app.id) != null) { onDone("That name is taken"); return }
        custom = custom.filter { it.id != app.id } + app
        onDone(null)
        install(app)
    }

    // --------------------------------------------------------------------- misc

    private fun setOp(id: String, s: OpState) { ops = ops + (id to s) }
    private fun clearOp(id: String) { ops = ops - id }
    fun dismissOp(id: String) { clearOp(id) }
    fun dismissBanner() { banner = null }

    companion object {
        const val APK_TIMEOUT_MS = 30 * 60_000L
    }
}
