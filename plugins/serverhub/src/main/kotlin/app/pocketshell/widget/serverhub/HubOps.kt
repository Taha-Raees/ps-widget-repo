package app.pocketshell.widget.serverhub

import android.content.Context
import app.pocketshell.runtime.RuntimeStorage
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Everything the hub reads or writes on disk, and the only sockets it opens: the saved services, their logs,
 * "is this program installed", and the health probes. (Starting programs is GuestExec, in Guest.kt.)
 */
class HubFiles(val rootfs: File) {

    constructor(appContext: Context) : this(RuntimeStorage(appContext.noBackupFilesDir).rootfsDir)

    val home: File get() = File(rootfs, "root")
    private val configDir: File get() = File(home, ".config/pocketshell")
    private val servicesFile: File get() = File(configDir, "serverhub.txt")
    val logsDir: File get() = File(home, ".cache/pocketshell/hub")

    fun readServices(): List<Service> = try {
        if (servicesFile.isFile && servicesFile.length() < 128_000) Services.decode(servicesFile.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun writeServices(list: List<Service>) {
        configDir.mkdirs()
        val tmp = File(configDir, ".serverhub.tmp")
        tmp.writeText(Services.encode(list))
        Files.move(tmp.toPath(), servicesFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Guest path -> host file, only if it stays inside the rootfs. */
    fun hostPath(guestPath: String): File? {
        if (!Services.cwdOk(guestPath)) return null
        val f = File(rootfs, guestPath.removePrefix("/"))
        return if (f.canonicalPath.startsWith(rootfs.canonicalPath)) f else null
    }

    fun folderExists(guestPath: String): Boolean = hostPath(guestPath)?.isDirectory == true

    /** Create [guestDir] and write each of [files] into it unless a file of that name already exists. */
    fun prepareFolder(guestDir: String, files: Map<String, String>): String? {
        val dir = hostPath(guestDir) ?: return "That folder is not inside the Linux home"
        return try {
            dir.mkdirs()
            for ((name, text) in files) {
                require(Regex("^[A-Za-z0-9._-]{1,60}$").matches(name))
                val f = File(dir, name)
                if (!f.exists()) f.writeText(text)
            }
            null
        } catch (e: Exception) {
            "Could not create the folder: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private val pathDirs = listOf("usr/local/bin", "usr/bin", "bin", "usr/sbin", "sbin")

    private fun present(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Is [program] (an absolute guest path, or a name on the usual PATH) installed? */
    fun hasProgram(program: String): Boolean = when {
        program.startsWith("/") -> present(File(rootfs, program.removePrefix("/")))
        program.contains('/') -> false
        else -> pathDirs.any { present(File(rootfs, "$it/$program")) }
    }

    // ---- logs

    fun logFile(id: String): File = File(logsDir, "$id.log")

    /** Keep a log from growing without bound: past the cap, keep only the last quarter. */
    fun prepareLog(id: String) {
        logsDir.mkdirs()
        val f = logFile(id)
        try {
            if (f.isFile && f.length() > LogLines.MAX_LOG_BYTES) {
                val keep = (LogLines.MAX_LOG_BYTES / 4).toInt()
                val tail = java.io.RandomAccessFile(f, "r").use { raf -> raf.seek(f.length() - keep); ByteArray(keep).also { raf.readFully(it) } }
                f.writeBytes(tail)
            }
        } catch (_: Exception) {
            f.delete()
        }
    }

    fun appendLog(id: String, line: String) {
        runCatching { logsDir.mkdirs(); logFile(id).appendText(line + "\n") }
    }

    fun clearLog(id: String) { runCatching { logFile(id).delete() } }

    fun logTail(id: String, lines: Int = 200): List<String> = try {
        val f = logFile(id)
        if (!f.isFile) emptyList() else java.io.RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val start = (len - 64 * 1024).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt()).also { raf.readFully(it) }
            LogLines.tail(LogLines.stripAnsi(String(bytes, Charsets.UTF_8)), lines)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

object HubNet {

    /** One TCP connect: milliseconds on success, null on failure. */
    fun tcp(host: String, port: Int, timeoutMs: Int): Long? {
        val t0 = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - t0) / 1_000_000
        } catch (_: Throwable) {
            null
        }
    }

    /** The service's verdict: a TCP connect, then (HTTP check) one GET. Never throws. */
    fun health(s: Service): HealthReading {
        if (s.check == CheckKind.NONE) return Verdicts.classify(s.check, null, null, null, null)
        val tcp = tcp("127.0.0.1", s.port, 2_000)
        if (tcp == null || s.check == CheckKind.TCP) return Verdicts.classify(s.check, tcp, null, null, null)
        val t0 = System.nanoTime()
        return try {
            val c = URL("http://127.0.0.1:${s.port}${s.path}").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 2_500; c.readTimeout = 4_000
            c.setRequestProperty("User-Agent", "PocketShell-ServerHub/1")
            val code = c.responseCode
            c.disconnect()
            Verdicts.classify(s.check, tcp, code, (System.nanoTime() - t0) / 1_000_000, null)
        } catch (e: Throwable) {
            Verdicts.classify(s.check, tcp, null, null, e.message?.take(60) ?: e.javaClass.simpleName)
        }
    }

    /** The phone's Wi-Fi IPv4 address (first wlan/ap interface), or any non-loopback address, or null. */
    fun lanAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan") }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress } }
            .firstOrNull { !it.startsWith("169.254.") }
    } catch (_: Throwable) {
        null
    }

    /** Does something answer on [ip]:[port] - i.e. is the service reachable from the Wi-Fi? */
    fun reachable(ip: String, port: Int): Boolean = tcp(ip, port, 800) != null

    /** Everything the guest listens on, via the host's own verified probe. */
    fun listening(): List<ListeningRow> = try {
        app.pocketshell.widget.probe.ServerProbe().snapshot().map { ListeningRow(it.port, it.displayName, it.pid, null, null) }.sortedBy { it.port }
    } catch (_: Throwable) {
        emptyList()
    }
}
