package app.pocketshell.widget.packages

import android.content.Context
import app.pocketshell.runtime.RuntimeStorage
import java.io.File

/**
 * Everything that reads the Linux environment from disk. (Running apt itself is GuestExec, in Guest.kt.)
 * Reading the installed database straight from its text file costs no process and no network.
 */

data class EnvFacts(
    val osName: String,
    val osVersion: String,
    val arch: String,
    val kernel: String,
    val appVersion: String?,
    /** The guest's C library (Debian's libc6), e.g. "2.41-12+deb13u4". */
    val glibc: String?,
    val devtools: String?,
    val repos: List<Repos.Repo>,
    val indexAgeMs: Long?,
)

data class Inventory(val installed: List<Pkg>, val world: Set<String>, val requiredBy: Map<String, List<String>>, val cacheBytes: Long) {
    val totalBytes: Long get() = installed.sumOf { it.installedBytes }
    val byName: Map<String, Pkg> by lazy { installed.associateBy { it.name } }
    /** Essential / required packages: never removed from here. */
    val essential: Set<String> by lazy { installed.filter { it.essential }.map { it.name }.toSet() }
}

class PkgFiles(val rootfs: File) {

    constructor(appContext: Context) : this(RuntimeStorage(appContext.noBackupFilesDir).rootfsDir)

    private fun text(rel: String, max: Long = 1_000_000): String = try {
        val f = File(rootfs, rel)
        if (f.isFile && f.length() < max) f.readText() else ""
    } catch (_: Exception) {
        ""
    }

    /**
     * Downloaded `.deb` files: the host binds its package cache (`<noBackupFilesDir>/package-cache`, beside
     * `runtime/rootfs`) over the guest's /var/cache/apt/archives; the rootfs path holds them when it is not bound.
     */
    private val cacheDirs: List<File>
        get() = listOf(File(rootfs.parentFile?.parentFile, "package-cache"), File(rootfs, "var/cache/apt/archives"))

    private fun debs(): List<File> = cacheDirs.flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".deb") }.orEmpty().toList() }

    fun inventory(): Inventory {
        val installed = DpkgStatus.parse(text("var/lib/dpkg/status", 60_000_000))
        val auto = ManualMarks.parseAuto(text("var/lib/apt/extended_states", 20_000_000))
        return Inventory(installed, ManualMarks.added(installed, auto), Deps.requiredBy(installed), cacheBytes())
    }

    /** Bytes held by downloaded package files (`*.deb`) in the package cache. Index files are not counted. */
    fun cacheBytes(): Long = try {
        debs().sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }

    /** Delete downloaded `*.deb` files only (apt fetches them again if needed); the index stays. Returns bytes freed. */
    fun clearCache(): Long {
        var freed = 0L
        debs().filter { f -> f.isFile && f.name.endsWith(".deb") }.forEach { f ->
            val n = f.length()
            if (f.delete()) freed += n
        }
        return freed
    }

    fun facts(): EnvFacts {
        val os = OsRelease.parse(text("usr/lib/os-release").ifEmpty { text("etc/os-release") })
        val release = text("etc/debian_version").trim()
        val kernel = try { File("/proc/version").readText().split(' ').take(3).joinToString(" ") } catch (_: Exception) { "" }
        val lists = File(rootfs, "var/lib/apt/lists")
        val index = lists.listFiles { f -> f.isFile && f.name.endsWith("InRelease") }.orEmpty().maxOfOrNull { it.lastModified() }
        val sourcesDir = File(rootfs, "etc/apt/sources.list.d")
        val deb822 = sourcesDir.listFiles { f -> f.name.endsWith(".sources") }.orEmpty().joinToString("\n\n") { runCatching { it.readText() }.getOrDefault("") }
        val oneLine = text("etc/apt/sources.list") + "\n" +
            sourcesDir.listFiles { f -> f.name.endsWith(".list") }.orEmpty().joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        val libc = DpkgStatus.parse(text("var/lib/dpkg/status", 60_000_000)).firstOrNull { it.name == "libc6" }?.version
        return EnvFacts(
            osName = os["NAME"] ?: "Linux",
            osVersion = release.ifEmpty { os["VERSION_ID"].orEmpty() },
            arch = "arm64",
            kernel = kernel,
            appVersion = text("etc/pocketshell/app-version").lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            glibc = libc,
            devtools = text("etc/pocketshell/devtools.status").lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            repos = Repos.parse(deb822, oneLine),
            indexAgeMs = index?.let { System.currentTimeMillis() - it },
        )
    }
}

fun ageText(ms: Long?): String = when {
    ms == null -> "never"
    ms < 90_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    ms < 86_400_000 -> "${ms / 3_600_000} h ago"
    else -> "${ms / 86_400_000} days ago"
}
