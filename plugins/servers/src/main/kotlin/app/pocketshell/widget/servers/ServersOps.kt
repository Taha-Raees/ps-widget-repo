package app.pocketshell.widget.servers

import app.pocketshell.widget.probe.ServerInfo

/**
 * The ONE mutation the monitor offers — stopping a server — and the same
 * discipline GitOps established for the Git plugin's writes:
 *
 *   - REFUSE before any dialog when the evidence cannot support it (no
 *     verified owner pid — an owner-less endpoint has nothing to stop
 *     safely, and killing by port number is how the wrong process dies);
 *   - CONFIRM with the exact command before anything runs (SIGTERM first,
 *     never -9; the user's dev server deserves the graceful path);
 *   - the pid rides as a POSITIONAL argument, never interpolated into the
 *     script text;
 *   - the outcome is honest (exit code / stderr), and the follow-up scan
 *     is forced — no optimistic "stopped" state survives a kill that
 *     failed.
 *
 * The stop action exists ONLY in the maximized tier (ServersLayout.FULL):
 * the desktop-app context is where a task-manager action belongs; the mini
 * card stays glance-only by design.
 */
internal object ServersOps {

    /** A stop awaiting (or having earned) its confirm. */
    data class StopRequest(
        val port: Int,
        val pid: Int,
        val processName: String?,
    )

    /** Why the request would not run, stated before any dialog appears. */
    fun refusal(server: ServerInfo): String? = when {
        server.pid == null ->
            "no verified owner process — nothing to stop safely"
        else -> null
    }

    /** `kill <pid>` — SIGTERM, positional arg, no interpolation. */
    fun argv(pid: Int): List<String> =
        listOf("/bin/sh", "-c", "kill \"${'$'}1\"", "sh", pid.toString())

    /** The command line the confirm dialog shows the user, verbatim. */
    fun displayCommand(pid: Int): String = "kill $pid"

    /** The confirm wording a request earns once the refusal gate passed. */
    fun confirmSpec(req: StopRequest): ConfirmSpec = ConfirmSpec(
        title = "Stop :${req.port}?",
        body = buildString {
            append("Stop ")
            append(req.processName ?: "this server")
            append(" (pid ${req.pid}) listening on :${req.port}?")
            append(" Runs `kill ${req.pid}` in the Linux guest — SIGTERM, the graceful path.")
        },
        command = displayCommand(req.pid),
        confirmLabel = "STOP",
    )

    data class ConfirmSpec(
        val title: String,
        val body: String,
        val command: String,
        val confirmLabel: String,
    )

    /** One finished stop: what ran and what actually happened. */
    data class Outcome(
        val req: StopRequest,
        val argv: List<String>,
        val exitCode: Int?,
        val stderr: String?,
        val error: String?,
        val atMs: Long,
    ) {
        val ok: Boolean get() = exitCode == 0 && error == null

        /** The one-line fact the banner shows. */
        fun line(): String = when {
            error != null -> "not run — $error"
            exitCode == 0 -> "Stopped :${req.port} (kill ${req.pid})"
            exitCode != null -> "kill exited with $exitCode${stderr?.let { ": $it" } ?: ""}"
            else -> "kill failed${stderr?.let { ": $it" } ?: ""}"
        }
    }
}
