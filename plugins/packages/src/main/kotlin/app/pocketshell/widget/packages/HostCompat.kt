package app.pocketshell.widget.packages

import android.content.Context
import app.pocketshell.jobs.PluginJobs
import app.pocketshell.widget.WidgetNav

/**
 * The two host features added after this plugin first shipped, behind guards.
 *
 * A plugin is downloaded separately from the app, so it can land on an app that predates a host feature. Calling a
 * missing class or method then fails with a [LinkageError] (`NoClassDefFoundError`, `NoSuchMethodError`,
 * `AbstractMethodError`). Every call here catches exactly that and degrades to what worked before: no background
 * retention, and the command copied to the clipboard instead of typed.
 */
internal object HostCompat {

    /** Run [block]; on an older host (a [LinkageError]) or any failure, return [fallback]. */
    internal fun <T> safely(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: LinkageError) {
        fallback
    } catch (_: Exception) {
        fallback
    }

    /** Ask the app to stay alive for this work. Returns a job id, or -1 when refused / unsupported. */
    fun beginJob(context: Context, label: String, expiresAfterMs: Long? = null, onStop: (() -> Unit)? = null): Long =
        safely(-1L) { PluginJobs.begin(context, OWNER, label, expiresAfterMs, onStop) }

    fun endJob(context: Context, id: Long) {
        if (id > 0) safely(Unit) { PluginJobs.end(context, id) }
    }

    /**
     * Open the terminal and type [text] at its prompt. True when the host typed it; false when the host is too old
     * (the terminal is opened and the caller should fall back to the clipboard).
     */
    fun typeInTerminal(nav: WidgetNav, text: String): Boolean = try {
        nav.openTerminalWith(text)
        true
    } catch (_: LinkageError) {
        nav.openTerminal()
        false
    }

    const val OWNER = "packages"
}
