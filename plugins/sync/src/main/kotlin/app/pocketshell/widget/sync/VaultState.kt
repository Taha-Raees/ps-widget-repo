package app.pocketshell.widget.sync

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.util.concurrent.Executors
import app.pocketshell.runtime.RuntimeStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The backup half's persisted settings and its process-scoped state holder.
 *
 * Persisted ([VaultSettings], DataStore file `sync_vault`): WHERE backups go,
 * WHAT they contain, the encryption PREFERENCE, and the last backup's facts.
 * Never persisted: the passphrase. SAF grants are device-specific, so this
 * file is deliberately NOT part of a backup's app-settings allow-list.
 *
 * [VaultState] owns every long operation on its OWN scope (not a
 * LaunchedEffect): rotating the screen or leaving Home must never cancel — or
 * re-run — a backup or a restore. One operation at a time; every outcome is
 * an honest, human-readable fact.
 */

// ---------------------------------------------------------------- settings

@Serializable
enum class LocationKind { APP_STORAGE, SAF_TREE }

@Serializable
data class BackupLocation(val kind: LocationKind, val uri: String? = null, val label: String)

@Serializable
data class LastBackup(
    val name: String,
    val atMs: Long,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val codec: String,
    val locationLabel: String,
    val sensitiveIncluded: Boolean,
)

@Serializable
data class VaultSettings(
    val location: BackupLocation? = null,
    val options: BackupOptions = BackupOptions(),
    /** "I encrypt my backups" — the passphrase itself is asked for each time and never kept. */
    val encrypt: Boolean = false,
    val last: LastBackup? = null,
)

object VaultSettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(s: VaultSettings): String = json.encodeToString(VaultSettings.serializer(), s)

    /** Corrupt or absent → defaults (nothing is invented from a damaged store). */
    fun decode(raw: String?): VaultSettings = raw?.let {
        try {
            json.decodeFromString(VaultSettings.serializer(), it)
        } catch (_: Exception) {
            null
        }
    } ?: VaultSettings()
}

class VaultRepository(private val context: Context) {
    private val key = stringPreferencesKey("vault_settings")

    val settings: Flow<VaultSettings> = context.vaultStore.data.map { VaultSettingsCodec.decode(it[key]) }

    suspend fun update(op: (VaultSettings) -> VaultSettings) {
        context.vaultStore.edit { prefs -> prefs[key] = VaultSettingsCodec.encode(op(VaultSettingsCodec.decode(prefs[key]))) }
    }
}

private val Context.vaultStore by preferencesDataStore(name = "sync_vault")

// ------------------------------------------------------------------ UI state

/** A long operation as the UI shows it. */
internal sealed interface VaultRun {
    data object Idle : VaultRun
    data class Working(val what: String, val label: String, val bytes: Long = 0, val total: Long? = null) : VaultRun
    data class Finished(val ok: Boolean, val message: String) : VaultRun
}

/** The restore flow's state machine. */
internal sealed interface RestoreUi {
    data object Idle : RestoreUi
    data class NeedPassphrase(val source: BackupSource, val wrong: String?) : RestoreUi
    data class NeedTool(val source: BackupSource, val tool: String) : RestoreUi
    data class Working(val label: String, val bytes: Long = 0, val total: Long? = null) : RestoreUi
    data class Ready(val inspected: InspectOutcome.Ready, val source: BackupSource) : RestoreUi
    data class Done(val applied: ApplyOutcome.Applied, val stageId: String, val packages: Int) : RestoreUi
    data class Failed(val reason: String) : RestoreUi
}

internal class VaultState(private val appContext: Context, private val repository: VaultRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var current: Job? = null

    /**
     * One token PER operation kind: the backup/check/install job and the restore
     * flow are independent, so cancelling or closing one can never silently
     * cancel the other (the restore screen's CANCEL must not stop a backup).
     */
    private var jobCancel: CancelToken = CancelToken()
    private var restoreCancel: CancelToken = CancelToken()

    /** Bumped when a restore begins or is closed: a late result for a closed screen is discarded, never shown. */
    private var restoreGen = 0

    /** The staged (decrypted) copy of the backup being restored, independent of what the screen shows. */
    private var restoreStage: String? = null
    private val engine: BackupEngine by lazy { AndroidGuestJobRunner.engineFor(appContext) }

    // ---- persisted + probed facts
    var settings by mutableStateOf(VaultSettings())
    var loaded by mutableStateOf(false)
    var zstdInstalled by mutableStateOf<Boolean?>(null)
    var listing by mutableStateOf<List<StoredBackup>>(emptyList())
    var listingError by mutableStateOf<String?>(null)
    var leftovers by mutableStateOf<List<String>>(emptyList())

    // ---- what the user is doing
    var job by mutableStateOf<VaultRun>(VaultRun.Idle)
    var restore by mutableStateOf<RestoreUi>(RestoreUi.Idle)
    var reinstall by mutableStateOf<VaultRun>(VaultRun.Idle)
    var tab by mutableStateOf(StudioTab.BACKUP)

    /** True while the studio is composed — lets the profile screens' Back stand down on the other tabs. */
    var studioVisible by mutableStateOf(false)

    // ---- restore choices (reset with each new restore)
    var restoreScopes by mutableStateOf<Set<BackupScope>>(emptySet())
    var restoreMode by mutableStateOf(RestoreMode.SIDE_FOLDER)
    var restoreAppSettings by mutableStateOf(false)
    var confirmText by mutableStateOf("")

    val busy: Boolean get() = current?.isActive == true

    // ------------------------------------------------------------- home folders

    /** The picker's rows; sizes fill in as they are measured (null = still measuring). */
    var homeRows by mutableStateOf<List<HomeRow>>(emptyList())
    var homeScanning by mutableStateOf(false)
    var homeError by mutableStateOf<String?>(null)

    private var homeJob: Job? = null
    private var homeDebounce: Job? = null

    /**
     * Sizes are measured on ONE low-priority thread of their own: walking a big
     * home is disk-heavy, and it must never compete with the UI (the same lesson
     * as the Git probe that made Home stutter).
     */
    private val sizerScope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { r ->
            Thread(r, "psbak-sizer").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
        }.asCoroutineDispatcher(),
    )

    private fun homeDir(): File = File(RuntimeStorage(appContext.noBackupFilesDir).rootfsDir, "root")

    /** (Re)list `~` and measure every row against the CURRENT leave-out rules. */
    fun refreshHome() {
        homeJob?.cancel()
        val matcher = TarGlob.compile(BackupPlan.excludeList(settings.options, settings.encrypt))
        homeScanning = true
        homeError = null
        homeJob = sizerScope.launch {
            try {
                val scanner = HomeScanner(homeDir())
                var rows = scanner.entries()
                // Keep rows the user added by hand (paths that are not top-level entries).
                val extra = settings.options.homeItems.filter { p -> rows.none { it.rel == p } && HomePaths.validate(p) == null }
                    .map { HomeRow(it, KnownApps.label(it), isDir = File(homeDir(), it).isDirectory) }
                rows = rows + extra
                homeRows = rows
                for (i in rows.indices) {
                    if (!isActive) return@launch
                    val r = rows[i]
                    val m = scanner.measure(r.rel, matcher) { !isActive } ?: return@launch
                    rows = rows.toMutableList().also { it[i] = r.copy(bytes = m.bytes, files = m.files, leftOut = m.leftOut) }
                    homeRows = rows
                }
            } catch (e: Exception) {
                homeError = e.message ?: "could not read the home folder"
            } finally {
                homeScanning = false
            }
        }
    }

    /** A leave-out setting changed: re-measure shortly, once, however fast the user taps. */
    fun scheduleHomeRefresh() {
        homeDebounce?.cancel()
        homeDebounce = scope.launch {
            delay(700)
            refreshHome()
        }
    }

    fun setHomeMode(mode: HomeMode) {
        update { cur ->
            var o = cur.options.copy(homeMode = mode)
            // First time choosing folders: start from everything ticked, so the user unticks what they do not want.
            if (mode == HomeMode.CHOSEN && o.homeItems.isEmpty()) {
                o = o.copy(homeItems = HomeSelection.normalize(homeRows.map { it.rel }.toSet()))
            }
            cur.copy(options = o)
        }
    }

    fun toggleHomeItem(rel: String) {
        update { cur -> cur.copy(options = cur.options.copy(homeItems = HomeSelection.toggle(cur.options.homeItems, rel))) }
    }

    fun selectAllHome() {
        update { cur -> cur.copy(options = cur.options.copy(homeItems = HomeSelection.normalize(homeRows.map { it.rel }.toSet()))) }
    }

    fun selectNoHome() {
        update { cur -> cur.copy(options = cur.options.copy(homeItems = emptySet())) }
    }

    /** Add a path the list does not show (e.g. `.config/kilo`); returns the problem, or null when added. */
    fun addHomePath(rel: String): String? {
        val path = rel.trim().trimEnd('/')
        HomePaths.validate(path)?.let { return it }
        if (!File(homeDir(), path).exists()) return "~/$path does not exist"
        update { cur -> cur.copy(options = cur.options.copy(homeMode = HomeMode.CHOSEN, homeItems = HomeSelection.normalize(cur.options.homeItems + path))) }
        refreshHome()
        return null
    }

    // ----------------------------------------------------------------- loading

    fun applyLoaded(s: VaultSettings) {
        settings = s
        loaded = true
    }

    /** Everything that needs the guest: tools, the location listing, interrupted stages. */
    fun refreshAll() {
        refreshTools()
        refreshListing()
        refreshLeftovers()
    }

    private fun launch(block: suspend () -> Unit) {
        current = scope.launch { block() }
    }

    fun update(op: (VaultSettings) -> VaultSettings) {
        settings = op(settings)
        scope.launch { repository.update(op) }
    }

    // ----------------------------------------------------------------- storage

    fun storage(): BackupStorage? = storageFor(settings.location)

    private fun storageFor(location: BackupLocation?): BackupStorage? = when (location?.kind) {
        null -> null
        LocationKind.APP_STORAGE -> appContext.getExternalFilesDir(null)?.let { FileBackupStorage(File(it, "backups"), location.label) }
        LocationKind.SAF_TREE -> location.uri?.let { SafBackupStorage(appContext.contentResolver, Uri.parse(it), location.label) }
    }

    fun useAppStorage() {
        update { it.copy(location = BackupLocation(LocationKind.APP_STORAGE, null, "App storage")) }
        refreshListing()
    }

    /** The user picked a folder in the system picker: keep the grant, remember the folder. */
    fun useFolder(tree: Uri) {
        SafAccess.persist(appContext.contentResolver, tree)
        scope.launch {
            val label = SafAccess.treeLabel(appContext.contentResolver, tree)
            update { it.copy(location = BackupLocation(LocationKind.SAF_TREE, tree.toString(), label)) }
            refreshListing()
        }
    }

    fun refreshListing() {
        val st = storage()
        if (st == null) {
            listing = emptyList()
            listingError = null
            return
        }
        scope.launch {
            try {
                listing = st.list()
                listingError = null
            } catch (e: Exception) {
                listing = emptyList()
                listingError = e.message ?: "the location cannot be read"
            }
        }
    }

    fun refreshTools() {
        scope.launch { zstdInstalled = engine.hasTool("zstd") }
    }

    fun refreshLeftovers() {
        scope.launch { leftovers = engine.leftoverStages() }
    }

    fun discardLeftovers() {
        if (busy) return // a live job's stage is not "interrupted" — the engine also refuses, this is the first line
        scope.launch {
            engine.leftoverStages().forEach { engine.discardStage(it) }
            leftovers = emptyList()
        }
    }

    // ------------------------------------------------------------------ backup

    /** Starts a backup. [passphrase] non-null = encrypted; it is zeroed by the engine. */
    fun startBackup(passphrase: CharArray?) {
        if (busy) return
        val st = storage() ?: run { job = VaultRun.Finished(false, "choose where backups go first"); return }
        val token = CancelToken().also { jobCancel = it }
        val request = BackupRequest(settings.options, passphrase, zstdInstalled == true, st)
        job = VaultRun.Working("backup", "preparing")
        launch {
            val outcome = engine.backup(request, token) { p -> job = VaultRun.Working("backup", p.label, p.bytes, p.total) }
            when (outcome) {
                is BackupOutcome.Done -> {
                    val r = outcome.record
                    update {
                        it.copy(
                            last = LastBackup(
                                r.name, r.manifest.createdAtMs, r.sizeBytes, r.manifest.encrypted,
                                r.manifest.codec.label, st.label, r.manifest.sensitiveIncluded,
                            ),
                        )
                    }
                    val extra = buildList {
                        if (outcome.pruned.isNotEmpty()) add("${outcome.pruned.size} older removed")
                        if (outcome.skippedPaths.isNotEmpty()) add("skipped ${outcome.skippedPaths.joinToString()} (not present)")
                    }.joinToString(" · ")
                    job = VaultRun.Finished(true, listOf("Backup written", formatSize(r.sizeBytes), extra).filter { it.isNotEmpty() }.joinToString(" · "))
                    refreshListing()
                }
                is BackupOutcome.Failed -> job = VaultRun.Finished(false, outcome.reason)
                BackupOutcome.Cancelled -> job = VaultRun.Finished(false, "cancelled — nothing was kept")
            }
        }
    }

    fun verify(backup: StoredBackup) {
        if (busy) return
        val st = storage() ?: return
        val token = CancelToken().also { jobCancel = it }
        job = VaultRun.Working("check", "checking", 0, backup.sizeBytes)
        launch {
            job = when (val o = engine.verify(st, backup, token) { p -> job = VaultRun.Working("check", p.label, p.bytes, p.total) }) {
                is VerifyOutcome.Match -> VaultRun.Finished(true, "${backup.name}: checksum matches (${formatSize(o.sizeBytes)})")
                is VerifyOutcome.Mismatch -> VaultRun.Finished(false, "${backup.name}: checksum differs — the file was changed or damaged")
                VerifyOutcome.NoRecord -> VaultRun.Finished(false, "${backup.name}: no checksum record to compare with")
                is VerifyOutcome.Failed -> VaultRun.Finished(false, o.reason)
                VerifyOutcome.Cancelled -> VaultRun.Finished(false, "cancelled")
            }
        }
    }

    fun delete(backup: StoredBackup) {
        if (!BackupNames.isOurs(backup.name)) return // never remove a file this app did not write
        val st = storage() ?: return
        scope.launch {
            runCatching { st.delete(backup.name) }
            runCatching { st.delete(BackupNames.sidecarName(backup.name)) }
            refreshListing()
        }
    }

    fun installZstd() {
        if (busy) return
        val token = CancelToken().also { jobCancel = it }
        job = VaultRun.Working("zstd", "installing zstd")
        launch {
            job = when (val o = engine.installZstd(token) { p -> job = VaultRun.Working("zstd", p.label) }) {
                is StepOutcome.Done -> VaultRun.Finished(true, "zstd installed")
                is StepOutcome.Failed -> VaultRun.Finished(false, "could not install zstd: ${o.reason}")
                StepOutcome.Cancelled -> VaultRun.Finished(false, "cancelled")
            }
            refreshTools()
        }
    }

    /** Cancels the running backup / check / install — never the restore flow. */
    fun cancelCurrent() {
        jobCancel.cancel()
    }

    fun dismissRun() {
        if (!busy) job = VaultRun.Idle
    }

    // ----------------------------------------------------------------- restore

    fun chooseFromListing(backup: StoredBackup) {
        val st = storage() ?: return
        beginRestore(StoredSource(st, backup), null)
    }

    /** The user picked a file: resolve its name and size on IO (a provider query), then inspect it. */
    fun chooseFile(uri: Uri) {
        if (busy) return
        scope.launch {
            SafAccess.persist(appContext.contentResolver, uri)
            beginRestore(SafPickedSource(appContext.contentResolver, uri).resolve(), null)
        }
    }

    /** Continue after a passphrase prompt (or an installed tool). */
    fun continueRestore(source: BackupSource, passphrase: CharArray?) = beginRestore(source, passphrase)

    private fun beginRestore(source: BackupSource, passphrase: CharArray?) {
        if (busy) return
        // A new choice replaces whatever was staged: the previous decrypted copy must not linger.
        dropStage()
        val token = CancelToken().also { restoreCancel = it }
        val gen = ++restoreGen
        restore = RestoreUi.Working("reading")
        launch {
            val outcome = engine.inspect(source, passphrase, token) { p ->
                if (gen == restoreGen) restore = RestoreUi.Working(p.label, p.bytes, p.total)
            }
            if (gen != restoreGen) {
                // The screen was closed while this ran: keep nothing.
                if (outcome is InspectOutcome.Ready) engine.discardStage(outcome.stageId)
                return@launch
            }
            restore = when (outcome) {
                is InspectOutcome.Ready -> {
                    restoreStage = outcome.stageId
                    // Packages are their own step; app settings only when the file carries some.
                    restoreScopes = outcome.manifest.scopes.filter { sc ->
                        when (sc) {
                            BackupScope.PACKAGES -> false
                            BackupScope.APP_SETTINGS -> outcome.appSettings.isNotEmpty()
                            else -> true
                        }
                    }.toSet()
                    restoreMode = RestoreMode.SIDE_FOLDER
                    restoreAppSettings = false
                    confirmText = ""
                    reinstall = VaultRun.Idle
                    RestoreUi.Ready(outcome, source)
                }
                is InspectOutcome.NeedsPassphrase -> RestoreUi.NeedPassphrase(source, null)
                is InspectOutcome.WrongPassphrase -> RestoreUi.NeedPassphrase(source, outcome.reason)
                is InspectOutcome.NeedsTool -> RestoreUi.NeedTool(source, outcome.tool)
                is InspectOutcome.Failed -> RestoreUi.Failed(outcome.reason)
                InspectOutcome.Cancelled -> RestoreUi.Idle
            }
        }
    }

    fun applyRestore() {
        val ready = (restore as? RestoreUi.Ready) ?: return
        if (busy) return
        if (restoreMode == RestoreMode.IN_PLACE && !RestorePlan.confirmationOk(confirmText)) return
        val token = CancelToken().also { restoreCancel = it }
        val gen = ++restoreGen
        val stageId = ready.inspected.stageId
        val scopes = restoreScopes
        val mode = restoreMode
        val app = restoreAppSettings
        restore = RestoreUi.Working("restoring")
        launch {
            val outcome = engine.apply(stageId, scopes, mode, app, token) { p ->
                if (gen == restoreGen) restore = RestoreUi.Working(p.label, p.bytes, p.total)
            }
            if (gen != restoreGen) return@launch
            restore = when (outcome) {
                is ApplyOutcome.Applied -> RestoreUi.Done(outcome, stageId, ready.inspected.packages.size)
                is ApplyOutcome.Failed -> {
                    dropStage()
                    RestoreUi.Failed("${outcome.reason} — some files may already have been restored")
                }
                ApplyOutcome.Cancelled -> {
                    // Cancelling mid-extraction can leave files written: say so, and do not offer a blind re-apply.
                    dropStage()
                    RestoreUi.Failed("Restore cancelled — some files may already have been restored. Choose the file again to restore it properly.")
                }
            }
        }
    }

    fun reinstallPackages(stageId: String) {
        if (busy) return
        val token = CancelToken().also { jobCancel = it }
        reinstall = VaultRun.Working("packages", "installing")
        launch {
            reinstall = when (val o = engine.reinstallPackages(stageId, token) { p -> reinstall = VaultRun.Working("packages", p.label) }) {
                is StepOutcome.Done -> VaultRun.Finished(true, "packages installed${o.detail?.let { " ($it)" } ?: ""}")
                is StepOutcome.Failed -> VaultRun.Finished(false, "package install failed: ${o.reason}")
                StepOutcome.Cancelled -> VaultRun.Finished(false, "cancelled")
            }
        }
    }

    /**
     * Leave the restore flow: cancels the RESTORE's own step (never a backup),
     * discards the staged decrypted copy and invalidates any late result.
     */
    fun closeRestore() {
        restoreCancel.cancel()
        restoreGen++
        restore = RestoreUi.Idle
        reinstall = VaultRun.Idle
        dropStage()
    }

    private fun dropStage() {
        val stage = restoreStage ?: return
        restoreStage = null
        scope.launch { engine.discardStage(stage) }
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
