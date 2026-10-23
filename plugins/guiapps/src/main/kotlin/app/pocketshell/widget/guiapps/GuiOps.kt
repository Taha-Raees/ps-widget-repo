package app.pocketshell.widget.guiapps

import android.content.Context
import app.pocketshell.runtime.RuntimeStorage
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Everything that touches the disk, the network or the apk database for this app. The ONLY file with
 * sockets (GuestExec in Guest.kt is the only one with processes) - a contract test pins both.
 */

/** A desktop launcher found in the guest, with the libc its program is built for. */
data class DesktopApp(val entry: DesktopEntry, val flavour: Flavour)

/** What the guest looks like right now. All of it comes from small local file reads. */
data class Snapshot(
    val apk: Map<String, String>,
    val images: List<InstalledImage>,
    val desktop: List<DesktopApp>,
    val sessionReady: Boolean,
    val glibcReady: Boolean,
)

class GuiFiles(val rootfs: File) {

    constructor(appContext: Context) : this(RuntimeStorage(appContext.noBackupFilesDir).rootfsDir)

    val home: File get() = File(rootfs, "root")
    val appsDir: File get() = File(home, "Applications")
    private val configDir: File get() = File(home, ".config/pocketshell")
    private val registry: File get() = File(configDir, "gui-apps.txt")
    val logsDir: File get() = File(home, ".cache/pocketshell/gui")

    fun logFile(id: String): File = File(logsDir, "$id.log")

    // ---- registry of downloaded (glibc) apps

    fun readImages(): List<InstalledImage> = try {
        if (registry.isFile && registry.length() < 64_000) InstalledImages.decode(registry.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun writeImages(list: List<InstalledImage>) {
        configDir.mkdirs()
        val tmp = File(configDir, ".gui-apps.tmp")
        tmp.writeText(InstalledImages.encode(list))
        Files.move(tmp.toPath(), registry.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** The folder an app's files live in; null if the id would leave `~/Applications`. */
    fun appDir(id: String): File? {
        if (!id.matches(Regex("^[a-z0-9-]{1,40}$"))) return null
        val d = File(appsDir, id)
        return if (d.canonicalPath.startsWith(appsDir.canonicalPath + File.separator) || !d.exists()) d else null
    }

    /** Remove an app's folder. Refuses anything outside `~/Applications/<id>`. */
    fun deleteAppDir(id: String): Boolean {
        val d = appDir(id) ?: return false
        if (!d.exists()) return true
        if (!d.canonicalPath.startsWith(appsDir.canonicalPath + File.separator)) return false
        return d.deleteRecursively()
    }

    // ---- the apk database

    /** name -> version of every installed apk package (the installed db is plain text). */
    fun readApk(): Map<String, String> = try {
        val db = File(rootfs, "lib/apk/db/installed")
        if (db.isFile && db.length() < 40_000_000) ApkDb.installed(db.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    // ---- desktop session & runtimes

    fun sessionReady(): Boolean = File(rootfs, GuiSession.SOCKET_RELATIVE).exists()
    fun hasXauthority(): Boolean = File(rootfs, GuiSession.XAUTHORITY_RELATIVE).isFile
    /** The loader is a symlink into the guest's own tree: look at the link itself, not where it points on the host. */
    fun glibcReady(): Boolean = Files.exists(File(rootfs, "lib/ld-linux-aarch64.so.1").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

    /** Launchers installed in the guest, deduplicated by file id, with their libc. Bounded. */
    fun scanDesktop(): List<DesktopApp> {
        val optDirs = File(rootfs, "opt").listFiles { f -> f.isDirectory }.orEmpty().map { File(it, "applications") }
        val dirs = listOf(File(rootfs, "usr/share/applications"), File(rootfs, "usr/local/share/applications"), File(home, ".local/share/applications")) + optDirs
        val seen = HashSet<String>()
        val out = ArrayList<DesktopApp>()
        for (dir in dirs) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") && f.length() < 64_000 }?.sortedBy { it.name }.orEmpty()
            for (f in files.take(600)) {
                if (!seen.add(f.name)) continue
                val entry = try { DesktopFile.parse(f.name, f.readText()) } catch (_: Exception) { null } ?: continue
                out += DesktopApp(entry, flavourOf(entry.command.first()))
            }
        }
        return out.sortedBy { it.entry.name.lowercase() }
    }

    private val pathDirs = listOf("usr/local/bin", "usr/bin", "bin", "usr/sbin", "sbin")

    /** The host file a guest program name resolves to (absolute path, or searched on the usual PATH). */
    fun resolve(program: String): File? {
        val start = when {
            program.startsWith("/") -> File(rootfs, program.removePrefix("/"))
            program.contains('/') -> return null
            else -> pathDirs.map { File(rootfs, "$it/$program") }.firstOrNull { Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) } ?: return null
        }
        return follow(start)
    }

    /** Follow symlinks the way the guest would: an absolute target is re-rooted under the rootfs. At most 8 hops. */
    private fun follow(first: File): File? {
        var f = first
        repeat(9) {
            val p = f.toPath()
            if (!Files.isSymbolicLink(p)) return f.takeIf { it.isFile }
            val target = Files.readSymbolicLink(p).toString()
            f = if (target.startsWith("/")) File(rootfs, target.removePrefix("/")) else File(f.parentFile, target).let { File(it.toPath().normalize().toString()) }
        }
        return null
    }

    fun flavourOf(program: String): Flavour {
        val f = resolve(program) ?: return Flavour.UNKNOWN
        return try {
            f.inputStream().use { Elf.flavour(it.readNBytes(65_536)) }
        } catch (_: Exception) {
            Flavour.UNKNOWN
        }
    }

    fun snapshot(): Snapshot = Snapshot(readApk(), readImages(), scanDesktop(), sessionReady(), glibcReady())

    /** The last [maxLines] lines of an app's log (bounded read from the end). */
    fun logTail(id: String, maxLines: Int = 25): String = try {
        val f = logFile(id)
        if (!f.isFile) "" else java.io.RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val start = (len - 16_384).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt()).also { raf.readFully(it) }
            String(bytes, Charsets.UTF_8).lines().takeLast(maxLines).joinToString("\n")
        }
    } catch (_: Exception) {
        ""
    }
}

/** The installed database of apk, read directly: `P:` name, `V:` version, `I:` installed size, `T:` description. */
object ApkDb {

    fun installed(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var name: String? = null
        for (line in text.lineSequence()) {
            when {
                line.startsWith("P:") -> name = line.substring(2)
                line.startsWith("V:") -> { name?.let { out[it] = line.substring(2) }; name = null }
            }
        }
        return out
    }
}

/** Sockets: GitHub's release answer, the download, and "is this port open yet". */
object GuiNet {

    private const val UA = "PocketShell-GUIApps/1"

    sealed interface Fetch {
        data class Ok(val asset: ReleaseAsset) : Fetch
        data class Failed(val reason: String) : Fetch
    }

    /** The latest release of [repo] and the asset matching [pattern]. */
    fun latest(repo: String, pattern: String): Fetch {
        if (!repo.matches(Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$"))) return Fetch.Failed("bad repository")
        return try {
            val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code == 403 || code == 429) return Fetch.Failed("GitHub is rate-limiting this network - try again in a few minutes")
            if (code != 200) return Fetch.Failed("GitHub answered $code")
            val body = c.inputStream.use { it.readNBytes(MiniJson.MAX_CHARS) }.toString(Charsets.UTF_8)
            c.disconnect()
            Releases.pickAsset(body, pattern)?.let { Fetch.Ok(it) } ?: Fetch.Failed("no arm64 file in the latest release")
        } catch (e: Throwable) {
            Fetch.Failed("could not reach GitHub: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    sealed interface Download {
        data object Done : Download
        data object Cancelled : Download
        data class Failed(val reason: String) : Download
    }

    /**
     * Stream [url] into [dest] (via a `.part` file). Checks free space, the size cap and, when given, the
     * SHA-256 - a mismatch deletes the file. [onProgress] gets (bytes so far, total or -1).
     */
    fun download(url: String, dest: File, expectedSize: Long, expectedSha: String?, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): Download {
        if (!url.startsWith("https://")) return Download.Failed("only https downloads are allowed")
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            dest.parentFile.mkdirs()
            val need = if (expectedSize > 0) expectedSize else 200L * 1024 * 1024
            if (dest.parentFile.usableSpace < need + 300L * 1024 * 1024) return Download.Failed("not enough free space (need about ${(need + 300L * 1024 * 1024) / 1_048_576} MB)")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) return Download.Failed("the server answered ${c.responseCode}")
            val total = c.contentLengthLong
            if (total > Assets.MAX_BYTES || expectedSize > Assets.MAX_BYTES) return Download.Failed("the file is larger than the 1.5 GB limit")
            val md = MessageDigest.getInstance("SHA-256")
            var read = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) { part.delete(); return Download.Cancelled }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n)
                        read += n
                        if (read > Assets.MAX_BYTES) { part.delete(); return Download.Failed("the file is larger than the 1.5 GB limit") }
                        onProgress(read, total)
                    }
                }
            }
            if (expectedSize > 0 && read != expectedSize) { part.delete(); return Download.Failed("incomplete download ($read of $expectedSize bytes)") }
            if (expectedSha != null) {
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (got != expectedSha) { part.delete(); return Download.Failed("the file does not match the SHA-256 the release published - it was deleted") }
            }
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return Download.Done
        } catch (e: Throwable) {
            part.delete()
            return Download.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** True once something accepts connections on 127.0.0.1:[port]. */
    fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
        true
    } catch (_: Throwable) {
        false
    }
}
