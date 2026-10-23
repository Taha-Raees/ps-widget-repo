package app.pocketshell.widget.sync

import android.content.Context
import app.pocketshell.jobs.PluginJobs

/**
 * The host feature added after Sync 3.0 - background jobs - behind a guard.
 *
 * A plugin is downloaded separately from the app, so it can land on an app that predates a host feature. Calling a
 * missing class then fails with a [LinkageError]. Every call here catches exactly that and degrades to what worked
 * before: no background retention (a backup then ends if Android stops the app, as it always did).
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

    const val OWNER = "sync"
}
