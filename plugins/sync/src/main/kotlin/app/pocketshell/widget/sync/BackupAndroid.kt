package app.pocketshell.widget.sync

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.pocketshell.packages.ProcessBuilderGuestCommandRunner
import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.terminal.ShellEnvironment
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The Android half of backup/restore — thin adapters over platform APIs, kept
 * apart from the engine so the engine stays JVM-testable:
 *
 *   - [AndroidGuestJobRunner]   the host's non-PTY guest runner (via [GuestLaunch])
 *   - [AndroidProcessSignaller] cancel: SIGTERM the job's whole process tree
 *   - [SafBackupStorage]        a folder the user picked in the system picker
 *   - [SafPickedSource]         one file the user picked to restore from
 *
 * No permission is requested anywhere: the system picker's grant IS the
 * permission, scoped to exactly what the user chose, and persisted so the
 * same folder works next time ("update it from the same app").
 */

// ------------------------------------------------------------------ guest

/** Runs a guest job under the PACKAGE_OPERATION profile (tar/gzip/zstd/apt; no PTY, no prompt). */
internal class AndroidGuestJobRunner(private val appContext: Context) : GuestJobRunner {

    override fun start(argv: List<String>): RunningJob {
        val storage = RuntimeStorage(appContext.noBackupFilesDir)
        val spec = GuestLaunch.spec(
            nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
            rootfsDir = storage.rootfsDir,
            hostCwd = ShellEnvironment.homeDir(appContext),
            prootTmpDir = File(appContext.cacheDir, "proot-tmp").apply { mkdirs() },
            guestCommand = argv,
            profile = GuestExecutionProfile.PACKAGE_OPERATION,
        )
        val process = ProcessBuilderGuestCommandRunner().start(spec)
        return object : RunningJob {
            override fun waitFor(timeoutMs: Long): JobResult {
                val r = process.waitFor(timeoutMs)
                return JobResult(
                    exitCode = r.exitCode,
                    stdout = r.stdout,
                    stderrTail = r.stderr.lineSequence().lastOrNull { it.isNotBlank() },
                    error = r.error,
                )
            }

            override fun destroy() = process.destroy()
        }
    }

    companion object {
        /** The engine's view of the device: the rootfs is the guest's "/", the app's DataStore folder holds app settings. */
        fun engineFor(appContext: Context): BackupEngine {
            val storage = RuntimeStorage(appContext.noBackupFilesDir)
            val env = EngineEnv(
                rootfs = storage.rootfsDir,
                datastoreDir = File(appContext.filesDir, "datastore"),
                device = deviceLabel(),
                pluginVersion = SyncPlugin().pluginVersion,
            )
            return BackupEngine(env, AndroidGuestJobRunner(appContext), AndroidProcessSignaller())
        }

        fun deviceLabel(): String = try {
            listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifEmpty { "Android device" }
        } catch (_: Throwable) {
            "Android device"
        }
    }
}

/** The parent-of relation over pids, and the descendants it implies. Pure. */
internal object ProcTree {

    /** Every pid below [root] (excluding it), children before grandchildren; cycle-safe. */
    fun descendants(root: Int, parentOf: Map<Int, Int>): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        for ((pid, parent) in parentOf) children.getOrPut(parent) { ArrayList() }.add(pid)
        val out = ArrayList<Int>()
        val seen = HashSet<Int>().apply { add(root) }
        val queue = ArrayDeque<Int>().apply { add(root) }
        while (queue.isNotEmpty()) {
            for (child in children[queue.removeFirst()].orEmpty().sorted()) {
                if (seen.add(child)) {
                    out += child
                    queue.addLast(child)
                }
            }
        }
        return out
    }

    /** ppid from a /proc/<pid>/stat line (fields after the LAST ')'): state, ppid, … */
    fun parentOfStat(stat: String): Int? =
        stat.substringAfterLast(')', "").trim().split(' ').getOrNull(1)?.toIntOrNull()
}

/** Cancel from the host: the guest pid IS the host pid (no pid namespace), and we own the process. */
internal class AndroidProcessSignaller : ProcessSignaller {

    override fun terminateTree(rootPid: Int) {
        val parentOf = HashMap<Int, Int>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val ppid = try {
                ProcTree.parentOfStat(File("/proc/$pid/stat").readText())
            } catch (_: Exception) {
                null
            }
            if (ppid != null) parentOf[pid] = ppid
        }
        // Leaves first, so a parent cannot respawn what we are about to stop.
        val victims = ProcTree.descendants(rootPid, parentOf).reversed() + rootPid
        for (pid in victims) runCatching { android.os.Process.sendSignal(pid, SIGTERM) }
    }

    private companion object {
        const val SIGTERM = 15
    }
}

// -------------------------------------------------------------------- SAF

internal object SafAccess {

    /** Keep the grant across restarts — the whole point of picking a folder once. */
    fun persist(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers grant for the session only; the folder still works until the app restarts.
        }
    }

    /** The folder's own display name ("Download", "Backups"), or a generic label. */
    fun treeLabel(resolver: ContentResolver, tree: Uri): String = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Chosen folder"
    } catch (_: Exception) {
        "Chosen folder"
    }

    /** True while the grant still lets us read the folder (revoked or deleted folders say false). */
    fun reachable(resolver: ContentResolver, tree: Uri): Boolean = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } ?: false
    } catch (_: Exception) {
        false
    }
}

/** A user-picked SAF folder as a [BackupStorage]. Blocking; never call on the main thread. */
internal class SafBackupStorage(
    private val resolver: ContentResolver,
    private val tree: Uri,
    override val label: String,
) : BackupStorage {

    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    private val parent: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)

    private data class Child(val docId: String, val name: String, val size: Long, val modified: Long, val isDir: Boolean)

    private fun children(): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<Child>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(
                    docId = c.getString(0),
                    name = c.getString(1) ?: continue,
                    size = if (c.isNull(2)) 0L else c.getLong(2),
                    modified = if (c.isNull(3)) 0L else c.getLong(3),
                    isDir = c.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        } ?: throw IOException("the folder cannot be read — it may have been removed or the permission revoked")
        return out
    }

    private fun docUri(child: Child): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.docId)

    /** Files only: a FOLDER named like a backup is never listed, read or deleted (delete would be recursive). */
    private fun find(name: String): Child? = children().firstOrNull { it.name == name && !it.isDir }

    override fun list(): List<StoredBackup> {
        val all = children().filter { !it.isDir }
        val byName = all.associateBy { it.name }
        return all.filter { it.name.endsWith(".${BackupNames.EXTENSION}") }
            .map { c ->
                val record = byName[BackupNames.sidecarName(c.name)]?.takeIf { it.size <= MAX_SIDECAR_BYTES }?.let { side ->
                    try {
                        resolver.openInputStream(docUri(side))?.use { BackupCodecs.decodeRecord(readLimited(it, MAX_SIDECAR_BYTES.toInt())) }
                    } catch (_: Exception) {
                        null
                    }
                }
                StoredBackup(c.name, c.size, c.modified, record)
            }
            .sortedByDescending { it.record?.manifest?.createdAtMs ?: it.modifiedAtMs }
    }

    override fun openWrite(name: String, mime: String): OutputStream {
        val created = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IOException("could not create $name in the chosen folder")
        return resolver.openOutputStream(created, "w") ?: throw IOException("could not open $name for writing")
    }

    override fun openRead(name: String): InputStream {
        val child = find(name) ?: throw FileNotFoundException(name)
        return resolver.openInputStream(docUri(child)) ?: throw IOException("could not open $name")
    }

    override fun delete(name: String): Boolean {
        val child = find(name) ?: return false
        return try {
            DocumentsContract.deleteDocument(resolver, docUri(child))
        } catch (_: Exception) {
            false
        }
    }

    /** At most [max] bytes as text — a provider that reports no size cannot make us read without bound. */
    private fun readLimited(input: InputStream, max: Int): String? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buf.size() <= max) {
            val n = input.read(chunk)
            if (n < 0) return buf.toByteArray().toString(Charsets.UTF_8)
            buf.write(chunk, 0, n)
        }
        return null // larger than any real sidecar: not one
    }

    private companion object {
        const val MAX_SIDECAR_BYTES = 256 * 1024L
    }
}

/** One file chosen with the system file picker, as a restore source (no sidecar is reachable). */
internal class SafPickedSource(private val resolver: ContentResolver, private val uri: Uri) : BackupSource {

    private val meta: Pair<String, Long?> by lazy {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    (c.getString(0) ?: "backup.psbak") to (if (c.isNull(1)) null else c.getLong(1))
                } else {
                    null
                }
            } ?: ("backup.psbak" to null)
        } catch (_: Exception) {
            "backup.psbak" to null
        }
    }

    /** Query the provider NOW (call on an IO thread) so reading [name]/[sizeBytes] later never blocks the UI. */
    fun resolve(): SafPickedSource = also { meta }

    override val name: String get() = meta.first
    override val sizeBytes: Long? get() = meta.second
    override val record: BackupRecord? get() = null
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("could not open the chosen file")
}
