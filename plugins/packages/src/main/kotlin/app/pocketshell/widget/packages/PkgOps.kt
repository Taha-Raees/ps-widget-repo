package app.pocketshell.widget.packages

import android.content.Context
import app.pocketshell.runtime.RuntimeStorage
import java.io.File

/**
 * Everything that reads the Linux environment from disk. (Running `apk` itself is GuestExec, in Guest.kt.)
 * Reading the installed database straight from its text file costs no process and no network.
 */

data class EnvFacts(
    val osName: String,
    val osVersion: String,
    val arch: String,
    val kernel: String,
    val appVersion: String?,
    val glibc: String?,
    val devtools: String?,
    val repos: List<Repos.Repo>,
    val indexAgeMs: Long?,
)

data class Inventory(val installed: List<Pkg>, val world: Set<String>, val requiredBy: Map<String, List<String>>, val cacheBytes: Long) {
    val totalBytes: Long get() = installed.sumOf { it.installedBytes }
    val byName: Map<String, Pkg> by lazy { installed.associateBy { it.name } }
}

class PkgFiles(val rootfs: File) {

    constructor(appContext: Context) : this(RuntimeStorage(appContext.noBackupFilesDir).rootfsDir)

    private fun text(rel: String, max: Long = 1_000_000): String = try {
        val f = File(rootfs, rel)
        if (f.isFile && f.length() < max) f.readText() else ""
    } catch (_: Exception) {
        ""
    }

    private val cacheDir: File get() = File(rootfs, "etc/apk/cache")

    fun inventory(): Inventory {
        val installed = ApkDb.parse(text("lib/apk/db/installed", 60_000_000))
        return Inventory(installed, World.parse(text("etc/apk/world")), Deps.requiredBy(installed), cacheBytes())
    }

    /** Bytes held by downloaded package files (`*.apk`) in the apk cache. Index files are not counted. */
    fun cacheBytes(): Long = try {
        cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }.orEmpty().sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }

    /** Delete downloaded `*.apk` files only (apk fetches them again if needed); the repository index stays. Returns bytes freed. */
    fun clearCache(): Long {
        var freed = 0L
        cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }.orEmpty().forEach { f ->
            val n = f.length()
            if (f.delete()) freed += n
        }
        return freed
    }

    fun facts(): EnvFacts {
        val os = OsRelease.parse(text("etc/os-release"))
        val release = text("etc/alpine-release").trim()
        val kernel = try { File("/proc/version").readText().split(' ').take(3).joinToString(" ") } catch (_: Exception) { "" }
        val index = cacheDir.listFiles { f -> f.isFile && f.name.startsWith("APKINDEX") }.orEmpty().maxOfOrNull { it.lastModified() }
        return EnvFacts(
            osName = os["NAME"] ?: "Linux",
            osVersion = release.ifEmpty { os["VERSION_ID"].orEmpty() },
            arch = text("etc/apk/arch").trim().ifEmpty { "aarch64" },
            kernel = kernel,
            appVersion = text("etc/pocketshell/app-version").lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            glibc = text("etc/pocketshell/glibc-runtime.status").lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            devtools = text("etc/pocketshell/devtools.status").lines().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            repos = Repos.parse(text("etc/apk/repositories")),
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
