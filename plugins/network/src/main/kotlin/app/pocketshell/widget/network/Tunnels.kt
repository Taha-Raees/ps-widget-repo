package app.pocketshell.widget.network

enum class TunnelKind(val flag: String, val label: String) {
    /** `-L`: a port on this phone reaches a service the remote side can see. */
    LOCAL("-L", "LOCAL"),

    /** `-R`: a port on the remote host reaches a service on this phone. */
    REMOTE("-R", "REMOTE"),

    /** `-D`: a SOCKS proxy on this phone that exits through the host. */
    DYNAMIC("-D", "SOCKS"),
}

/** A saved port forward. */
data class Tunnel(
    val id: String,
    val name: String,
    val kind: TunnelKind,
    /** A host alias from `~/.ssh/config`, or a plain `user@host`. */
    val via: String,
    /** The port opened (on this phone for LOCAL/DYNAMIC, on the remote host for REMOTE). */
    val bindPort: Int,
    /** LOCAL: where the remote side connects to. REMOTE: where on this phone it lands. Unused for DYNAMIC. */
    val destHost: String = "localhost",
    val destPort: Int = 0,
)

object Tunnels {

    private val ID = Regex("^[a-z0-9]{4,16}$")
    private val NAME = Regex("^[^|\\n\\r\\u0000]{1,40}$")
    private val VIA = Regex("^([A-Za-z0-9_][A-Za-z0-9._-]{0,63}@)?[A-Za-z0-9][A-Za-z0-9._-]{0,252}$")
    private val DEST = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,252}$")

    /** Ports a normal app may open. Anything lower cannot be bound without root. */
    const val MIN_BIND_PORT = 1024

    fun problem(t: Tunnel): String? = when {
        !ID.matches(t.id) -> "Internal: bad id"
        !NAME.matches(t.name) -> "Name: 1–40 characters, no | or line breaks"
        !VIA.matches(t.via) -> "Via: a host name from your config, or user@host"
        t.kind != TunnelKind.REMOTE && t.bindPort !in MIN_BIND_PORT..65535 -> "Local port: $MIN_BIND_PORT–65535"
        t.kind == TunnelKind.REMOTE && t.bindPort !in 1..65535 -> "Remote port: 1–65535"
        t.kind != TunnelKind.DYNAMIC && !DEST.matches(t.destHost) -> "Destination: a name or address"
        t.kind != TunnelKind.DYNAMIC && t.destPort !in 1..65535 -> "Destination port: 1–65535"
        else -> null
    }

    /** The `-L`/`-R`/`-D` spec. Local binds are ALWAYS loopback: only this phone can use them. */
    fun forwardSpec(t: Tunnel): String = when (t.kind) {
        TunnelKind.LOCAL -> "127.0.0.1:${t.bindPort}:${t.destHost}:${t.destPort}"
        TunnelKind.REMOTE -> "127.0.0.1:${t.bindPort}:${t.destHost}:${t.destPort}"
        TunnelKind.DYNAMIC -> "127.0.0.1:${t.bindPort}"
    }

    /** The ssh argv (no shell involved). */
    fun sshArgv(t: Tunnel): List<String> = listOf(
        "ssh", "-N",
        "-o", "BatchMode=yes",
        "-o", "ExitOnForwardFailure=yes",
        "-o", "ServerAliveInterval=30",
        "-o", "ServerAliveCountMax=3",
        "-o", "ConnectTimeout=10",
        "-o", "StrictHostKeyChecking=yes",
        t.kind.flag, forwardSpec(t),
        t.via,
    )

    /** What a person would type — for display only, never executed. */
    fun display(t: Tunnel): String = (sshArgv(t).filterIndexed { i, _ -> i != 0 }).joinToString(" ").let { "ssh $it" }

    fun describe(t: Tunnel): String = when (t.kind) {
        TunnelKind.LOCAL -> "localhost:${t.bindPort} → ${t.destHost}:${t.destPort} via ${t.via}"
        TunnelKind.REMOTE -> "${t.via} :${t.bindPort} → ${t.destHost}:${t.destPort} here"
        TunnelKind.DYNAMIC -> "SOCKS localhost:${t.bindPort} via ${t.via}"
    }

    /** The local URL worth opening for this tunnel, if it has one (LOCAL web-ish destinations). */
    fun openUrl(t: Tunnel): String? =
        if (t.kind == TunnelKind.LOCAL && t.destPort in WEB_PORTS) "http://127.0.0.1:${t.bindPort}" else null

    private val WEB_PORTS = setOf(80, 443, 3000, 3001, 4000, 5000, 5173, 8000, 8080, 8081, 8443, 8888, 9000)

    /**
     * Wrap [argv] so the process writes its own pid first, then BECOMES [argv] (exec keeps the pid):
     * the plugin can then find and stop exactly this process. Every variable travels as a positional
     * argument — the script text is a constant.
     */
    fun withPidFile(pidFile: String, argv: List<String>): List<String> =
        listOf("sh", "-c", "echo \$\$ > \"\$1\" && shift && exec \"\$@\"", "network-job", pidFile) + argv

    // ------------------------------------------------------------- storage

    /** One tunnel per line: `id|name|kind|via|bindPort|destHost|destPort`. Damaged lines are skipped. */
    fun encode(list: List<Tunnel>): String =
        list.filter { problem(it) == null }.joinToString("") {
            "${it.id}|${it.name}|${it.kind.name}|${it.via}|${it.bindPort}|${it.destHost}|${it.destPort}\n"
        }

    fun decode(text: String?): List<Tunnel> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 7) return@mapNotNull null
        val kind = TunnelKind.entries.firstOrNull { it.name == p[2] } ?: return@mapNotNull null
        val t = Tunnel(p[0], p[1], kind, p[3], p[4].toIntOrNull() ?: return@mapNotNull null, p[5], p[6].toIntOrNull() ?: return@mapNotNull null)
        t.takeIf { problem(it) == null }
    }

    private val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun newId(random: java.util.Random = java.security.SecureRandom()): String =
        buildString { repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}

/** Where a tunnel is right now. */
sealed interface TunnelState {
    data object Stopped : TunnelState
    data object Starting : TunnelState
    data class Up(val sinceMs: Long) : TunnelState

    /** It ran and ended (or never came up). [outcome] says why. */
    data class Dropped(val outcome: SshOutcome) : TunnelState
}
