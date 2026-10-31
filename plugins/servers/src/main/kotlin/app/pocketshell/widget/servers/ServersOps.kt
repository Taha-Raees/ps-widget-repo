package app.pocketshell.widget.servers

import app.pocketshell.widget.probe.ServerInfo

/**
 * The ONE mutation the monitor offers — signalling a process (stop it, or
 * force-kill it) — under the same discipline GitOps established for the Git
 * plugin's writes:
 *
 *   - REFUSE before any dialog when the evidence cannot support it (no
 *     verified owner pid, or a pid that is not a plain guest process —
 *     killing by port number is how the wrong process dies);
 *   - CONFIRM with the exact command before anything runs (SIGTERM is the
 *     default; SIGKILL is a separate, explicitly-worded choice);
 *   - the pid and the signal ride as POSITIONAL arguments, never
 *     interpolated into the script text; the signal comes from a closed enum;
 *   - the outcome is honest (exit code / stderr), and the follow-up scan
 *     is forced — no optimistic "stopped" state survives a kill that
 *     failed.
 *
 * The actions exist ONLY in the maximized tier (ServersLayout.FULL): the
 * desktop-app context is where a task-manager action belongs; the mini
 * card stays glance-only by design.
 */
internal object ServersOps {

    /** The closed set of signals — never user text. */
    enum class Signal(val sigName: String, val confirmLabel: String, val past: String) {
        TERM("TERM", "STOP", "Stopped"),
        KILL("KILL", "FORCE KILL", "Killed"),
        CONT("CONT", "RESUME", "Resumed"),
    }

    /** A signal awaiting (or having earned) its confirm. */
    data class StopRequest(
        val pid: Int,
        val processName: String?,
        /** The listening port when the target was chosen as a server; null for processes. */
        val port: Int? = null,
        val signal: Signal = Signal.TERM,
        /** The target is a terminal's shell — signalling it ends that terminal session. */
        val endsTerminal: Boolean = false,
        /** The target's identity (stat starttime) at request time; re-checked right before exec. */
        val startTicks: Long? = null,
    ) {
        /** What the user calls it: ":3000", or the process name, or the pid. */
        val subject: String get() = port?.let { ":$it" } ?: processName ?: "pid $pid"
    }

    /** Why the request would not run, stated before any dialog appears. */
    fun refusal(pid: Int?): String? = when {
        pid == null -> "no verified owner process — nothing to stop safely"
        pid <= 1 -> "pid $pid is not a guest process — refusing to signal it"
        else -> null
    }

    fun refusal(server: ServerInfo): String? = refusal(server.pid)

    /** `kill -s <SIG> <pid>` — pid and signal positional, no interpolation. */
    fun argv(pid: Int, signal: Signal = Signal.TERM): List<String> =
        listOf("/bin/sh", "-c", "kill -s \"${'$'}2\" \"${'$'}1\"", "sh", pid.toString(), signal.sigName)

    /** The command line the confirm dialog shows the user, verbatim. */
    fun displayCommand(pid: Int, signal: Signal = Signal.TERM): String = "kill -s ${signal.sigName} $pid"

    /** The confirm wording a request earns once the refusal gate passed. */
    fun confirmSpec(req: StopRequest): ConfirmSpec = ConfirmSpec(
        title = when (req.signal) {
            Signal.TERM -> "Stop ${req.subject}?"
            Signal.KILL -> "Force-kill ${req.subject}?"
            Signal.CONT -> "Resume ${req.subject}?"
        },
        body = buildString {
            append(
                when (req.signal) {
                    Signal.TERM -> "Stop "
                    Signal.KILL -> "Force-kill "
                    Signal.CONT -> "Resume "
                },
            )
            append(req.processName ?: "this process")
            append(" (pid ${req.pid})")
            if (req.port != null) append(" listening on :${req.port}")
            append("? Runs `${displayCommand(req.pid, req.signal)}` in the Linux guest — ")
            append(
                when (req.signal) {
                    Signal.TERM -> "SIGTERM, the graceful path."
                    Signal.KILL -> "SIGKILL cannot be caught: the process gets no chance to clean up or save."
                    Signal.CONT -> "SIGCONT, it carries on from where it was frozen."
                },
            )
            if (req.endsTerminal) append(" This is a terminal's shell — that terminal session ends.")
        },
        command = displayCommand(req.pid, req.signal),
        confirmLabel = req.signal.confirmLabel,
    )

    data class ConfirmSpec(
        val title: String,
        val body: String,
        val command: String,
        val confirmLabel: String,
    )

    /** One finished signal: what ran and what actually happened. */
    data class Outcome(
        val req: StopRequest,
        val argv: List<String>,
        val exitCode: Int?,
        val stderr: String?,
        val error: String?,
        val atMs: Long,
        /**
         * Set by the follow-up scan: the signal was delivered (kill exited 0) but the
         * same process is still there — it may be shutting down, or ignoring the signal.
         */
        val stillRunning: Boolean = false,
    ) {
        val ok: Boolean get() = exitCode == 0 && error == null

        /** The one-line fact the banner shows. */
        fun line(): String = when {
            error != null -> "not run — $error"
            exitCode == 0 && stillRunning ->
                "Sent ${req.signal.sigName} to ${req.subject} (${displayCommand(req.pid, req.signal)}) — still running" +
                    if (req.signal == Signal.TERM) "; a graceful shutdown can take a moment, or it ignores TERM" else ""
            exitCode == 0 -> "${req.signal.past} ${req.subject} (${displayCommand(req.pid, req.signal)})"
            exitCode != null -> "kill exited with $exitCode${stderr?.let { ": $it" } ?: ""}"
            else -> "kill failed${stderr?.let { ": $it" } ?: ""}"
        }
    }
}
