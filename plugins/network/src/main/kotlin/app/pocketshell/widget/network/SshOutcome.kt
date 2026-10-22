package app.pocketshell.widget.network

/**
 * What a BatchMode `ssh` run told us, in words a person can act on.
 *
 * The raw material is only the exit code and stderr, so this is a pure
 * classifier. The wording of OpenSSH's errors is its public face and has been
 * stable for years; the unit tests pin real messages captured on a device.
 */
sealed interface SshOutcome {
    /** Logged in (or, for services like GitHub, authenticated but refused a shell). [greeting] is the service's own line. */
    data class Ok(val greeting: String?) : SshOutcome

    /** The host answered and the key was not accepted. */
    data object AuthFailed : SshOutcome

    /** First contact: no fingerprint stored yet. */
    data object HostKeyUnknown : SshOutcome

    /** The stored fingerprint does NOT match — a changed server or someone in the middle. */
    data object HostKeyChanged : SshOutcome

    data object DnsFailed : SshOutcome
    data object Refused : SshOutcome
    data object TimedOut : SshOutcome
    data object NoRoute : SshOutcome
    data object PortInUse : SshOutcome

    /** ssh itself could not run in the plugin's sandbox (missing binary, no network access). */
    data class SandboxProblem(val detail: String) : SshOutcome

    data class Other(val detail: String) : SshOutcome

    companion object {

        fun classify(exitCode: Int, stderr: String, stdout: String = ""): SshOutcome {
            val text = stderr
            val lower = text.lowercase()
            return when {
                "remote host identification has changed" in lower -> HostKeyChanged
                "host key verification failed" in lower && "requested strict checking" in lower -> HostKeyUnknown
                "no ed25519 host key is known" in lower || "no rsa host key is known" in lower ||
                    "no ecdsa host key is known" in lower -> HostKeyUnknown
                "host key verification failed" in lower -> HostKeyChanged
                "address already in use" in lower || "cannot listen to port" in lower -> PortInUse
                "could not resolve hostname" in lower || "name does not resolve" in lower ||
                    "temporary failure in name resolution" in lower -> DnsFailed
                "connection refused" in lower -> Refused
                "timed out" in lower -> TimedOut
                "no route to host" in lower || "network is unreachable" in lower -> NoRoute
                "permission denied" in lower -> AuthFailed
                "not found" in lower && ("ssh" in lower) && exitCode == 127 -> SandboxProblem(lastLine(text))
                exitCode == 0 -> Ok(greeting(stdout, text))
                // GitHub / GitLab authenticate you and then close the session with a non-zero code.
                "successfully authenticated" in lower || "welcome to gitlab" in lower -> Ok(greeting(stdout, text))
                else -> Other(lastLine(text).ifEmpty { "ssh exited with code $exitCode" })
            }
        }

        private fun greeting(stdout: String, stderr: String): String? =
            (stdout + "\n" + stderr).lines().map { it.trim() }
                .firstOrNull { it.startsWith("Hi ") || it.startsWith("Welcome to") }

        private fun lastLine(s: String): String = s.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().take(200)
    }
}

/** One sentence for each outcome — what happened and what to do. */
fun SshOutcome.message(): String = when (this) {
    is SshOutcome.Ok -> greeting ?: "Connected"
    SshOutcome.AuthFailed -> "Reached the host, but it did not accept this key"
    SshOutcome.HostKeyUnknown -> "New host — its fingerprint is not trusted yet"
    SshOutcome.HostKeyChanged -> "WARNING: the host's fingerprint changed — do not connect until you know why"
    SshOutcome.DnsFailed -> "That name does not resolve — check the spelling or your DNS"
    SshOutcome.Refused -> "Nothing is listening on that port"
    SshOutcome.TimedOut -> "No answer — the host is down, filtered, or the port is wrong"
    SshOutcome.NoRoute -> "No route to the host — check your connection"
    SshOutcome.PortInUse -> "That local port is already in use"
    is SshOutcome.SandboxProblem -> "ssh could not run here: $detail"
    is SshOutcome.Other -> detail
}

/** Does this outcome mean the host itself is reachable (an answer, even a refusal to log in)? */
val SshOutcome.reachable: Boolean
    get() = when (this) {
        is SshOutcome.Ok, SshOutcome.AuthFailed, SshOutcome.HostKeyUnknown, SshOutcome.HostKeyChanged -> true
        else -> false
    }
