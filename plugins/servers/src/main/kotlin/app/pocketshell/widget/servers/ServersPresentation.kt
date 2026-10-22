package app.pocketshell.widget.servers

import android.content.Context
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.widget.probe.ServerInfo

/**
 * The presentation rules: filter, sort, and the display formatting every
 * screen shares. Pure functions over the probe's facts — JVM-tested —
 * so a row can never show something the numbers do not say.
 */

internal enum class SortMode(val label: String) {
    PORT("PORT"),
    NAME("NAME"),
    LATENCY("LATENCY"),
    STATUS("STATUS");

    companion object {
        fun fromLabelOrNull(label: String): SortMode? = entries.firstOrNull { it.label == label }
    }
}

internal object ServersPresentation {

    // ---------------------------------------------------------- filtering

    /**
     * Case-insensitive substring over the three names a user thinks in:
     * the port, the process name, the cwd. A blank query filters nothing.
     */
    fun filter(servers: List<ServerInfo>, query: String): List<ServerInfo> {
        val q = query.trim()
        if (q.isEmpty()) return servers
        return servers.filter { server ->
            server.port.toString().contains(q) ||
                server.processName?.contains(q, ignoreCase = true) == true ||
                server.cwd?.contains(q, ignoreCase = true) == true
        }
    }

    // ------------------------------------------------------------- sorting

    /**
     * PORT   ascending port — the stable default.
     * NAME   process name (nulls last), then port.
     * LATENCY  last latency ascending; failures and unknowns last, then
     *          port (a down server is the interesting one — see STATUS).
     * STATUS down/failed first (they are why the user opened the page),
     *        then latency, then port.
     */
    fun sort(
        servers: List<ServerInfo>,
        mode: SortMode,
        lastLatencyMs: (Int) -> Long?,
        lastOk: (Int) -> Boolean?,
    ): List<ServerInfo> = when (mode) {
        SortMode.PORT -> servers.sortedBy { it.port }
        SortMode.NAME -> servers.sortedWith(
            compareBy<ServerInfo> { it.processName == null }
                .thenBy { it.processName?.lowercase() ?: "" }
                .thenBy { it.port },
        )
        SortMode.LATENCY -> servers.sortedWith(
            compareBy<ServerInfo> { lastLatencyMs(it.port) == null }
                .thenBy { lastLatencyMs(it.port) ?: Long.MAX_VALUE }
                .thenBy { it.port },
        )
        SortMode.STATUS -> servers.sortedWith(
            compareBy<ServerInfo> { lastOk(it.port) != false }
                .thenBy { lastLatencyMs(it.port) ?: Long.MAX_VALUE }
                .thenBy { it.port },
        )
    }

    // ---------------------------------------------------------- formatting

    /** kB → "84 MB" / "1.2 GB" (memory-scale, binary units like free(1)). */
    fun formatKb(kb: Long): String = formatBytes(kb * 1024)

    /** bytes → "84 MB" / "1.2 GB" — one decimal below 10, none above. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val kb = bytes / 1024.0
        return when {
            kb < 1024 -> "${kb.toLong()} KB"
            kb < 1024 * 1024 -> oneDecimal(kb / 1024) + " MB"
            else -> oneDecimal(kb / (1024 * 1024)) + " GB"
        }
    }

    private fun oneDecimal(v: Double): String {
        val rounded = (v * 10).toLong() / 10.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", rounded)
        }
    }

    /** seconds → "45s" / "2h 14m" / "3d 4h" — the strip's compact uptime. */
    fun formatUptime(seconds: Double): String {
        if (seconds < 0 || seconds.isNaN()) return "—"
        val total = seconds.toLong()
        val d = total / 86_400
        val h = (total % 86_400) / 3_600
        val m = (total % 3_600) / 60
        val s = total % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** loadavg entry → "0.52" (two decimals is what uptime(1) prints). */
    fun formatLoad(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

    /** fraction 0..1 → "43%"; null → "—". */
    fun formatFraction(frac: Float?): String =
        frac?.let { "${(it * 100).toLong()}%" } ?: "—"

    /** CPU% → "2.3%"; null → "—" (first interval has no answer yet). */
    fun formatCpu(pct: Float?): String =
        pct?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "—"

    /** latency → "12 ms"; null → "—". */
    fun formatLatency(ms: Long?): String = ms?.let { "$it ms" } ?: "—"

    // ---------------------------------------------------------- cwd display

    /**
     * The host path → friendly path mapping, the SAME rules as the v1
     * built-in app (rootfs-prefixed → guest path, app home → ~, else the
     * data-relative path). Pure core; the thin context wrapper reads the
     * real anchors once per composition.
     */
    fun displayCwdPaths(cwd: String, dataDir: String, rootfsPath: String?): String {
        val dataDirNorm = dataDir.removeSuffix("/")
        val rootfs = rootfsPath?.removeSuffix("/")
        if (!rootfs.isNullOrEmpty() && cwd.startsWith("$rootfs/")) {
            val guestPath = "/" + cwd.removePrefix("$rootfs/")
            return "$guestPath (guest)"
        }
        if (!rootfs.isNullOrEmpty() && cwd == rootfs) return "/ (guest root)"
        val home = "$dataDirNorm/files/home"
        return when {
            cwd == home -> "~ (Linux home)"
            cwd.startsWith("$home/") -> "~/" + cwd.removePrefix("$home/")
            else -> cwd.removePrefix("$dataDirNorm/")
        }
    }

    fun displayCwd(cwd: String, context: Context): String {
        val rootfsPath = try {
            RuntimeStorage(context.applicationContext.noBackupFilesDir).rootfsDir.path
        } catch (_: Exception) {
            null
        }
        return displayCwdPaths(
            cwd = cwd,
            dataDir = context.applicationInfo.dataDir ?: "",
            rootfsPath = rootfsPath,
        )
    }
}
