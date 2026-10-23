package app.pocketshell.widget.network

/**
 * Serving a folder over HTTP to the local network is the one thing here that
 * makes the phone reachable by OTHER devices, so it is fenced in:
 *
 *   - it refuses folders that are, or contain, things nobody means to publish
 *     (`/`, the whole home, `/etc`, `.ssh`, `.gnupg`, `.aws`, `.env`, private keys);
 *   - it binds to one Wi-Fi address only, never `0.0.0.0`;
 *   - it ALWAYS has an end time.
 */
object ShareGuard {

    /** Guest paths that are never served, whatever they contain. */
    private val FORBIDDEN_ROOTS = setOf("/", "/root", "/etc", "/proc", "/sys", "/dev", "/var", "/usr", "/bin", "/sbin", "/lib", "/run", "/tmp")

    private val SENSITIVE_NAMES = setOf(".ssh", ".gnupg", ".aws", ".netrc", ".git-credentials", ".docker", ".kube", ".npmrc", ".pypirc")

    val DURATIONS_MIN = listOf(5, 15, 60)
    const val DEFAULT_MIN = 15
    const val MAX_PORT = 65535
    const val MIN_PORT = 1024

    fun isSensitiveName(name: String): Boolean {
        val n = name.lowercase()
        return n in SENSITIVE_NAMES || n == ".env" || n.startsWith(".env.") || n.endsWith(".env") ||
            n.startsWith("id_") || n.endsWith(".pem") || n.endsWith(".key") || n == "credentials.json" || n == "service-account.json"
    }

    /** Normalise a user-typed guest path ("/root/x/", "x/../y") — null when it is not an absolute path. */
    fun normalize(path: String): String? {
        if (!path.startsWith("/") || '\u0000' in path || '\n' in path) return null
        val parts = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * A reason NOT to serve [guestDir], or null when it is acceptable. [entryNames] are the names found
     * inside the folder's subtree (a bounded scan done by the caller) — enough to spot a buried `.ssh`.
     */
    fun refusal(guestDir: String, entryNames: Collection<String>, scanTruncated: Boolean = false): String? {
        val dir = normalize(guestDir) ?: return "Give a full path, like /root/site"
        if (dir in FORBIDDEN_ROOTS) return "$dir is too broad to share — pick a specific project folder"
        if (dir.split('/').any { isSensitiveName(it) }) return "That folder is private"
        entryNames.firstOrNull { isSensitiveName(it) }?.let { return "It contains \"$it\" — move what you want to share into its own folder" }
        if (scanTruncated) return "That folder is too large to check — pick a smaller one"
        return null
    }

    /** Private-range IPv4 addresses worth offering (Wi-Fi / hotspot). Loopback and link-local are not. */
    fun lanAddresses(candidates: List<Pair<String, String>>): List<Pair<String, String>> =
        candidates.filter { (_, ip) -> isPrivateIpv4(ip) }

    fun isPrivateIpv4(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    fun portProblem(port: Int): String? = if (port !in MIN_PORT..MAX_PORT) "Port: $MIN_PORT–$MAX_PORT" else null

    fun url(ip: String, port: Int) = "http://$ip:$port/"
}
