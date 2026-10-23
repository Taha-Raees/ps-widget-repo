package app.pocketshell.widget.packages

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class PkgTab(val label: String) { INSTALLED("Installed"), ADD("Add"), UPDATES("Updates"), SYSTEM("System") }

enum class ChangeOp { ADD, DEL, UPGRADE_NAMED, UPGRADE_ALL }

/** A change that was simulated and now waits for the user's yes. */
data class Pending(
    val title: String,
    val op: ChangeOp,
    val names: List<String>,
    val plan: Plan,
    val stays: Map<String, List<String>>,
    val refusal: String?,
    val typeWord: String?,
    val deltaBytes: Long?,
)

/** What the last real apk run printed. */
data class RunLog(val title: String, val ok: Boolean, val text: String)

/**
 * The ONE holder for Packages & Environment. It outlives composition, so work runs on its own scope; every
 * apk operation is single-flight (apk itself holds a database lock, and so does the host's own package screen).
 * Nothing runs without a tap, and nothing changes the system without a simulated preview and a confirm.
 */
class PkgState(context: Context) {

    private val appContext = context.applicationContext
    val files = PkgFiles(appContext)
    private val exec = GuestExec(appContext, "pkgjobs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var tab by mutableStateOf(PkgTab.INSTALLED)
    var studio by mutableStateOf(false)
    var detail by mutableStateOf<String?>(null)

    @Volatile var loadedAtMs = 0L
    var inventory by mutableStateOf<Inventory?>(null)
    var facts by mutableStateOf<EnvFacts?>(null)
    var loading by mutableStateOf(false)
    var banner by mutableStateOf<Banner?>(null)

    var showScope by mutableStateOf(Scope.ADDED)
    var filter by mutableStateOf("")

    var searchQuery by mutableStateOf("")
    var hits by mutableStateOf<List<SearchHit>?>(null)
    var addText by mutableStateOf("")
    var info by mutableStateOf<Pair<String, String>?>(null)

    var updates by mutableStateOf<List<Upgradable>?>(null)
    var updatesCheckedAtMs by mutableStateOf<Long?>(null)

    var busy by mutableStateOf<String?>(null)
    var pending by mutableStateOf<Pending?>(null)
    var lastRun by mutableStateOf<RunLog?>(null)

    private val loadGuard = AtomicBoolean(false)
    private val apkGuard = AtomicBoolean(false)

    // ------------------------------------------------------------------ reading

    /** Re-read the installed database and the environment files. Cheap: two small file reads, no process. */
    fun reload() {
        if (!loadGuard.compareAndSet(false, true)) return
        loading = true
        scope.launch {
            try { reloadNow() } catch (e: Throwable) {
                banner = Banner("Could not read the package database: ${e.message ?: e.javaClass.simpleName}", bad = true)
            } finally {
                loading = false
                loadGuard.set(false)
            }
        }
    }

    private fun reloadNow() {
        inventory = files.inventory()
        facts = files.facts()
        loadedAtMs = System.currentTimeMillis()
    }

    fun isInstalled(name: String): Boolean = inventory?.byName?.containsKey(name) == true

    fun visible(): List<Pkg> {
        val inv = inventory ?: return emptyList()
        return PkgFilter.apply(inv.installed, inv.world, showScope, filter)
    }

    fun upgradeFor(name: String): Upgradable? = updates?.firstOrNull { it.name == name }

    // ------------------------------------------------------------------ one apk at a time

    /** Run [block] as THE apk operation, with the app kept alive; false (and a message) when another one is running. */
    private fun exclusive(label: String, block: () -> Unit) {
        if (!apkGuard.compareAndSet(false, true)) {
            banner = Banner("Another package operation is running - wait for it to finish", bad = true)
            return
        }
        busy = label
        scope.launch {
            val job = HostCompat.beginJob(appContext, label)
            try {
                block()
            } catch (e: Throwable) {
                banner = Banner("$label failed: ${e.message ?: e.javaClass.simpleName}", bad = true)
            } finally {
                HostCompat.endJob(appContext, job)
                busy = null
                apkGuard.set(false)
                runCatching { reloadNow() }
            }
        }
    }

    private fun apk(argv: List<String>, timeoutMs: Long = 30 * 60_000L) = exec.run(argv, timeoutMs, apk = true)

    // ------------------------------------------------------------------- preview → apply

    private fun command(op: ChangeOp, names: List<String>, simulate: Boolean): List<String> = when (op) {
        ChangeOp.ADD -> ApkCmd.add(names, simulate)
        ChangeOp.DEL -> ApkCmd.del(names, simulate)
        ChangeOp.UPGRADE_NAMED -> ApkCmd.add(names, simulate, upgrade = true)
        ChangeOp.UPGRADE_ALL -> ApkCmd.upgradeAll(simulate)
    }

    /** Simulate a change (`apk --simulate`: nothing is touched), then ask. */
    fun preview(op: ChangeOp, names: List<String>, title: String) {
        if (op != ChangeOp.UPGRADE_ALL && names.isEmpty()) return
        if (names.any { !PkgNames.valid(it) }) { banner = Banner("That is not a package name", bad = true); return }
        exclusive("Checking what would change…") {
            val r = apk(command(op, names, simulate = true), 10 * 60_000L)
            var res = r
            if (!r.ok && op == ChangeOp.ADD && ApkErrors.needsUpdate(r.stdout + r.stderr)) {
                busy = "Refreshing the package index…"
                apk(ApkCmd.update, 5 * 60_000L)
                busy = "Checking what would change…"
                res = apk(command(op, names, simulate = true), 10 * 60_000L)
            }
            if (!res.ok) { banner = Banner(ApkErrors.reason(res.stdout, res.stderr, res.error), bad = true); return@exclusive }
            val plan = PlanParse.parse(res.stdout)
            val stays = NotRemoved.parse(res.stdout)
            val refusal = if (op == ChangeOp.DEL) RemovalGuard.check(plan) else null
            if (plan.isEmpty && stays.isEmpty()) { banner = Banner(if (op == ChangeOp.ADD) "Already installed - nothing to do" else "Nothing to change", bad = false); return@exclusive }
            val before = inventory?.totalBytes
            pending = Pending(
                title = title, op = op, names = names, plan = plan, stays = stays, refusal = refusal,
                typeWord = if (op == ChangeOp.DEL && RemovalGuard.needsTypedConfirm(plan)) "remove" else null,
                deltaBytes = before?.let { plan.deltaBytes(it) },
            )
        }
    }

    fun dismissPending() { pending = null }

    /** The real run of what was previewed. */
    fun applyPending() {
        val p = pending ?: return
        pending = null
        if (p.refusal != null || p.plan.isEmpty) return
        exclusive(p.title) {
            val r = apk(command(p.op, p.names, simulate = false))
            lastRun = RunLog(p.title, r.ok, r.tail(12))
            if (r.ok) {
                banner = Banner("${p.title} - done", bad = false)
                if (p.op == ChangeOp.UPGRADE_ALL) updates = emptyList()
                else if (p.op == ChangeOp.UPGRADE_NAMED) updates = updates?.filter { it.name !in p.names }
            } else {
                banner = Banner(ApkErrors.reason(r.stdout, r.stderr, r.error), bad = true)
            }
        }
    }

    fun install(names: List<String>) = preview(ChangeOp.ADD, names, "Install ${names.take(3).joinToString(", ")}" + if (names.size > 3) " +${names.size - 3}" else "")

    fun installBundle(b: Bundle) {
        val missing = Bundles.missing(b, inventory?.byName?.keys.orEmpty())
        if (missing.isEmpty()) { banner = Banner("${b.name} is already installed", bad = false); return }
        preview(ChangeOp.ADD, missing, "Install ${b.name}")
    }

    fun remove(name: String) = preview(ChangeOp.DEL, listOf(name), "Remove $name")
    fun upgrade(name: String) = preview(ChangeOp.UPGRADE_NAMED, listOf(name), "Update $name")
    fun upgradeAll() = preview(ChangeOp.UPGRADE_ALL, emptyList(), "Update everything")

    // ------------------------------------------------------------------- queries

    fun checkUpdates() {
        exclusive("Looking for updates…") {
            val u = apk(ApkCmd.update, 5 * 60_000L)
            if (!u.ok) { banner = Banner(ApkErrors.reason(u.stdout, u.stderr, u.error), bad = true); return@exclusive }
            val l = apk(ApkCmd.listUpgradable, 3 * 60_000L)
            if (!l.ok) { banner = Banner(ApkErrors.reason(l.stdout, l.stderr, l.error), bad = true); return@exclusive }
            val list = UpgradeParse.parse(l.stdout)
            updates = list
            updatesCheckedAtMs = System.currentTimeMillis()
            banner = Banner(if (list.isEmpty()) "Everything is up to date" else "${list.size} update${if (list.size == 1) "" else "s"} available", bad = false)
        }
    }

    fun search() {
        val q = searchQuery.trim()
        if (q.length < 2 || !Regex("^[A-Za-z0-9 ._+-]{2,60}$").matches(q)) { banner = Banner("Search for at least 2 letters or digits", bad = true); return }
        exclusive("Searching…") {
            val r = apk(ApkCmd.search(q), 3 * 60_000L)
            if (!r.ok && r.stdout.isBlank()) { banner = Banner(ApkErrors.reason(r.stdout, r.stderr, r.error), bad = true); return@exclusive }
            hits = SearchParse.parse(r.stdout, q)
        }
    }

    fun showInfo(name: String) {
        if (!PkgNames.valid(name)) return
        exclusive("Reading $name…") {
            val r = apk(ApkCmd.info(name), 2 * 60_000L)
            val shown = r.stdout.lines().filter { it.isNotBlank() }.joinToString("\n").ifBlank { r.tail(3) }
            info = name to shown.take(1_800)
        }
    }

    fun dismissInfo() { info = null }

    // -------------------------------------------------------------------- system

    fun refreshIndex() {
        exclusive("Refreshing the package index…") {
            val r = apk(ApkCmd.update, 5 * 60_000L)
            lastRun = RunLog("Refresh package index", r.ok, r.tail(6))
            banner = if (r.ok) Banner("Package index refreshed", bad = false) else Banner(ApkErrors.reason(r.stdout, r.stderr, r.error), bad = true)
        }
    }

    fun repair() {
        exclusive("Repairing packages…") {
            val r = apk(ApkCmd.fix, 20 * 60_000L)
            lastRun = RunLog("Repair packages (apk fix)", r.ok, r.tail(10))
            banner = if (r.ok) Banner("Repair finished", bad = false) else Banner(ApkErrors.reason(r.stdout, r.stderr, r.error), bad = true)
        }
    }

    /** Delete downloaded package files. Host-side file deletion, but never while apk is running. */
    fun clearCache() {
        exclusive("Clearing the package cache…") {
            val freed = files.clearCache()
            banner = Banner("Freed ${Fmt.bytes(freed)}", bad = false)
        }
    }

    fun dismissBanner() { banner = null }
    fun dismissRun() { lastRun = null }
}
