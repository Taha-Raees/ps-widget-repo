package app.pocketshell.widget.git

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A black box for the Git card: the last few things it did, written to a file
 * that survives the process, plus the stack trace if the process dies of an
 * uncaught exception.
 *
 * Why it exists: the host records nothing about a crash (no uncaught-exception
 * handler, no exit reasons), so "Git crashes the app when I scroll fast" could
 * not be diagnosed — it might be an exception, an ANR, or a low-memory kill,
 * and each has a different fix. The trail turns that into something readable:
 *
 *   - BREADCRUMBS (a ring of the last [MAX] events: composed / disposed, on
 *     screen / off, scan start / end with its duration, refresh taps) are
 *     flushed to the file within ~0.5 s of any change. A kill the process never
 *     sees (ANR, low memory) leaves the trail up to that moment — and its
 *     modified time says when.
 *   - A CRASH appends the exception's full stack under the same breadcrumbs,
 *     then hands the exception on to whatever handler was installed before us:
 *     the app's behaviour is unchanged, only observed.
 *
 * Local file only: `<rootfs>/tmp/pocketshell-git-trail.log` (readable from the
 * Linux shell at /tmp/pocketshell-git-trail.log). Nothing is sent anywhere.
 */
internal object GitTrail {

    const val MAX = 60
    const val FLUSH_MS = 500L
    const val FILE_NAME = "pocketshell-git-trail.log"

    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null
    @Volatile private var dirty = false
    @Volatile private var installed = false

    /** Record one event. Cheap and never throws — safe to call from composition. */
    fun note(message: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            synchronized(lock) {
                if (ring.size >= MAX) ring.removeFirst()
                ring.addLast("${synchronized(stamp) { stamp.format(Date(nowMs)) }} $message")
            }
            dirty = true
        } catch (_: Throwable) {
            // diagnostics must never become the problem
        }
    }

    /** The trail as text, oldest first. */
    fun render(): String = synchronized(lock) { ring.joinToString("\n") }

    /**
     * Start recording into [logFile] and watch for uncaught exceptions. Idempotent.
     * The previous default handler is kept and ALWAYS called after ours.
     */
    fun install(logFile: File?) {
        if (installed) return
        installed = true
        file = logFile
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                note("CRASH on thread '${thread.name}': ${error.javaClass.name}: ${error.message}")
                write(crashText = error.stackTraceToString())
            } catch (_: Throwable) {
                // never mask the real crash
            }
            previous?.uncaughtException(thread, error)
        }
        Thread({
            while (true) {
                try {
                    Thread.sleep(FLUSH_MS)
                    if (dirty) write(crashText = null)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // keep the recorder alive
                }
            }
        }, "git-trail").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** Write the file now (tests, and the crash path). */
    internal fun write(crashText: String?) {
        val f = file ?: return
        dirty = false
        f.parentFile?.mkdirs()
        f.writeText(
            buildString {
                append("Git card trail — last ").append(MAX).append(" events, oldest first\n")
                append(render()).append('\n')
                if (crashText != null) append("\n--- UNCAUGHT EXCEPTION ---\n").append(crashText).append('\n')
            },
        )
    }

    /** Test seam: forget everything so one test cannot see another's events. */
    internal fun resetForTest() {
        synchronized(lock) { ring.clear() }
        dirty = false
        file = null
        installed = false
    }
}
