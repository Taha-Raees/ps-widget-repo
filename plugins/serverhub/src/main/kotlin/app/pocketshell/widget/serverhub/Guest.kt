package app.pocketshell.widget.serverhub

import android.content.Context
import app.pocketshell.packages.GuestProcess
import app.pocketshell.packages.ProcessBuilderGuestCommandRunner
import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeStorage
import app.pocketshell.terminal.ShellEnvironment
import java.io.File

/**
 * Everything that runs in the Linux guest - the ONLY file (with GuestLaunch.kt) that starts a process.
 * A contract test pins this: UI and state never exec. Pure decisions live in the model files.
 */

/** The result of a finished guest command. */
data class ExecResult(val exitCode: Int?, val stdout: String, val stderr: String, val error: String?) {
    val ok: Boolean get() = exitCode == 0
    fun tail(maxLines: Int = 6): String =
        (stderr.ifBlank { stdout }.ifBlank { error.orEmpty() }).lines().filter { it.isNotBlank() }.takeLast(maxLines).joinToString("\n")
}

/** A guest process that is still running (an app, a server). */
class LiveProcess(private val handle: GuestProcess, val pidFile: File) {
    @Volatile private var stopped = false

    /** Block until it exits (on a worker thread). */
    fun waitForExit(): ExecResult {
        val r = handle.waitFor(null)
        return ExecResult(r.exitCode, r.stdout, r.stderr, r.error)
    }

    val wasStopped: Boolean get() = stopped

    fun pid(): Int? = try { pidFile.readText().trim().toIntOrNull() } catch (_: Exception) { null }

    fun isAlive(): Boolean = pid()?.let { File("/proc/$it").exists() } ?: true

    /** Ask it to end: SIGTERM the real process and everything it started, then tear down the wrapper. */
    fun stop() {
        stopped = true
        pid()?.let { root ->
            val victims = ProcTree.descendants(root, ProcTree.readTable()) + root
            victims.reversed().forEach { runCatching { android.os.Process.sendSignal(it, 15) } }
        }
        runCatching { handle.destroy() }
    }

    /** The hard stop, for a process that ignored [stop]. */
    fun kill() {
        pid()?.let { root ->
            (ProcTree.descendants(root, ProcTree.readTable()) + root).forEach { runCatching { android.os.Process.sendSignal(it, 9) } }
        }
        runCatching { handle.destroy() }
    }
}

class GuestExec(private val appContext: Context, private val rootfs: File, private val jobsName: String) {

    constructor(appContext: Context, jobsName: String) : this(appContext, RuntimeStorage(appContext.noBackupFilesDir).rootfsDir, jobsName)

    /** Host-side folder that the guest sees as `/tmp/<jobsName>`. */
    val jobsDir: File get() = File(rootfs, "tmp/$jobsName")

    private fun spec(argv: List<String>, withApkCache: Boolean, cwd: String?) = GuestLaunch.spec(
        nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
        rootfsDir = rootfs,
        hostCwd = ShellEnvironment.homeDir(appContext),
        prootTmpDir = File(appContext.cacheDir, "proot-tmp").apply { mkdirs() },
        guestCommand = argv,
        profile = GuestExecutionProfile.PACKAGE_OPERATION,
        apkCacheDir = if (withApkCache) HostCompat.safely<File?>(null) { app.pocketshell.packages.PackageGateway.apkCacheDir(RuntimeStorage(appContext.noBackupFilesDir)) } else null,
        guestCwd = cwd,
    )

    /** Run to completion (or [timeoutMs]); never throws. [apk] = give the launch the package cache the host's own apk uses. */
    fun run(argv: List<String>, timeoutMs: Long, apk: Boolean = false, cwd: String? = null): ExecResult = try {
        val r = ProcessBuilderGuestCommandRunner().start(spec(argv, apk, cwd)).waitFor(timeoutMs)
        ExecResult(r.exitCode, r.stdout, r.stderr, r.error)
    } catch (e: Throwable) {
        ExecResult(null, "", "", e.message ?: e.javaClass.simpleName)
    }

    /**
     * Start a long-lived process. Its pid lands in `/tmp/<jobsName>/<id>.pid`; with [logGuestPath] its
     * stdout+stderr are appended to that guest file; with [cwd] it starts in that guest folder. The wrapper
     * script is constant text and every variable travels as a positional argument.
     */
    fun start(id: String, argv: List<String>, logGuestPath: String? = null, cwd: String? = null): LiveProcess {
        jobsDir.mkdirs()
        val pidFile = File(jobsDir, "$id.pid")
        pidFile.delete()
        val handle = ProcessBuilderGuestCommandRunner().start(spec(wrap("/tmp/$jobsName/$id.pid", logGuestPath, argv, cwd), false, cwd))
        return LiveProcess(handle, pidFile)
    }

    companion object {
        /**
         * The job wrapper: `sh -c '<constant script>' job <pidfile> [<logfile>] [<cwd>] argv...`. The script
         * writes the shell's pid (which `exec` then hands to the program), optionally sends stdout+stderr to the
         * log and enters the folder, then `exec`s the program. The script text depends only on WHICH of log / cwd
         * are present; every value travels as a positional argument.
         */
        fun wrap(pidFile: String, logFile: String?, argv: List<String>, cwd: String? = null): List<String> {
            val params = arrayListOf(pidFile)
            val script = buildString {
                append("echo \$\$ > \"\$1\"; ")
                if (logFile != null) { params += logFile; append("exec >>\"\$${params.size}\" 2>&1; ") }
                if (cwd != null) { params += cwd; append("cd \"\$${params.size}\" || exit 126; ") }
                append("shift ${params.size}; exec \"\$@\"")
            }
            return listOf("sh", "-c", script, "job") + params + argv
        }

        /** `env K=V ... argv` with names checked; values are single argv entries, never shell text. */
        fun withEnv(env: Map<String, String>, argv: List<String>): List<String> {
            env.keys.forEach { require(Regex("^[A-Z_][A-Z0-9_]*$").matches(it)) { "bad environment name" } }
            return if (env.isEmpty()) argv else listOf("env") + env.map { "${it.key}=${it.value}" } + argv
        }
    }
}

/** The process table, read host-side (no exec): enough to stop a whole tree. */
object ProcTree {

    /** `pid (comm) S ppid ...` - [comm] may hold spaces and parentheses, so parse from the LAST ')'. */
    fun parseStat(line: String): Pair<Int, Int>? {
        val close = line.lastIndexOf(')')
        if (close < 0) return null
        val pid = line.substringBefore(' ').toIntOrNull() ?: return null
        val rest = line.substring(close + 1).trim().split(' ')
        val ppid = rest.getOrNull(1)?.toIntOrNull() ?: return null
        return pid to ppid
    }

    /** pid -> ppid for every readable process. */
    fun readTable(proc: File = File("/proc")): Map<Int, Int> = try {
        proc.listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
            try { parseStat(File(dir, "stat").readText()) } catch (_: Exception) { null }
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /** All descendants of [root], children before grandchildren. */
    fun descendants(root: Int, table: Map<Int, Int>): List<Int> {
        val out = ArrayList<Int>()
        val seen = HashSet<Int>().apply { add(root) }
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            val next = table.filter { (pid, ppid) -> ppid in frontier && pid !in seen }.keys.toList()
            seen += next
            out += next
            frontier = next
        }
        return out
    }
}
