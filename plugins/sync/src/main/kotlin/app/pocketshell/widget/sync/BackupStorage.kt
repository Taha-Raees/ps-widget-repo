package app.pocketshell.widget.sync

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where backups live — one small seam, three kinds of place:
 *
 *   - a folder the user picked through the system picker (SAF; see
 *     BackupAndroid.kt) — the flagship: survives uninstall, reachable from a
 *     new phone;
 *   - the app's external-files folder ([FileBackupStorage]) — no permission,
 *     but Android deletes it when PocketShell is uninstalled, so the UI says so;
 *   - a plain directory in the tests.
 *
 * Every call is BLOCKING (callers run on Dispatchers.IO). A backup is the
 * ARCHIVE plus a tiny plaintext SIDECAR (`NAME.psbak.json`); the sidecar is
 * written last, so its presence means "this backup finished" — an archive
 * without one is an interrupted write and is never trusted or pruned.
 */
interface BackupStorage {

    /** Human label of the place ("Download/PocketShell", "App storage"). */
    val label: String

    /** The backups here: archives with their sidecar record when a valid one exists. */
    fun list(): List<StoredBackup>

    /** Create a NEW file [name] and open it for writing; the caller closes the stream. */
    fun openWrite(name: String, mime: String): OutputStream

    fun openRead(name: String): InputStream

    /** Delete [name]; false when it was not there. */
    fun delete(name: String): Boolean
}

/** A restore source: a file chosen from a location listing or from the system picker. */
interface BackupSource {
    val name: String

    /** Null when the provider does not say. */
    val sizeBytes: Long?

    /** The sidecar record when the source came from a listing; null for a lone picked file. */
    val record: BackupRecord?

    fun open(): InputStream
}

/** A source that is one of a [BackupStorage]'s own backups. */
class StoredSource(private val storage: BackupStorage, private val backup: StoredBackup) : BackupSource {
    override val name: String get() = backup.name
    override val sizeBytes: Long? get() = backup.sizeBytes.takeIf { it > 0 }
    override val record: BackupRecord? get() = backup.record
    override fun open(): InputStream = storage.openRead(backup.name)
}

/** A plain directory — app storage and the unit tests. */
class FileBackupStorage(private val dir: File, override val label: String) : BackupStorage {

    override fun list(): List<StoredBackup> {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        return files.filter { it.name.endsWith(".${BackupNames.EXTENSION}") }
            .map { f ->
                val sidecar = File(dir, BackupNames.sidecarName(f.name))
                val record = if (sidecar.isFile && sidecar.length() <= MAX_SIDECAR_BYTES) {
                    BackupCodecs.decodeRecord(sidecar.readText())
                } else {
                    null
                }
                StoredBackup(f.name, f.length(), f.lastModified(), record)
            }
            .sortedByDescending { it.record?.manifest?.createdAtMs ?: it.modifiedAtMs }
    }

    override fun openWrite(name: String, mime: String): OutputStream {
        require(isPlainName(name)) { "not a plain file name" }
        dir.mkdirs()
        val target = File(dir, name)
        if (target.exists()) throw java.io.IOException("$name already exists")
        return target.outputStream().buffered()
    }

    override fun openRead(name: String): InputStream {
        require(isPlainName(name)) { "not a plain file name" }
        val f = File(dir, name)
        if (!f.isFile) throw FileNotFoundException(name)
        return f.inputStream()
    }

    override fun delete(name: String): Boolean {
        require(isPlainName(name)) { "not a plain file name" }
        return File(dir, name).delete()
    }

    private fun isPlainName(name: String) =
        name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".." && !name.contains('\u0000')

    private companion object {
        const val MAX_SIDECAR_BYTES = 256 * 1024L
    }
}
