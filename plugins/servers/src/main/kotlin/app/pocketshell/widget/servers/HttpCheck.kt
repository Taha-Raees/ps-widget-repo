package app.pocketshell.widget.servers

import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP health check — the user-reviewed monitor staple (Uptime Kuma's
 * heartbeat model: per-check status + response time, kept as a history
 * strip). It rides the SAME kernel fact the probe's verification does:
 * guest and app share one network namespace, so an app-side GET to
 * 127.0.0.1:P reaches the guest's server — no curl/wget dependency, no
 * guest exec, no new failure mode.
 *
 * Discipline:
 *   - GET (not HEAD): dev servers answer GET the way a browser would;
 *     some reject HEAD with 405 and the check would lie.
 *   - The body is drained only up to [BODY_DRAIN_BYTES], then closed —
 *     a check must never pull a whole stream from the user's server.
 *   - Redirects are REPORTED (3xx chips), never followed: the widget
 *     shows what the endpoint answers, not where it points.
 *   - Every failure is a reason string, never a guess: refused / timed
 *     out / malformed stay distinct — the same three-honest-states rule
 *     the rest of the widget system speaks.
 *
 * The connection code is device-only; the classification and the beat
 * history are pure + JVM-tested.
 */

internal sealed interface HttpResult {

    /** The endpoint answered HTTP: code, latency, identity headers. */
    data class Answered(
        val code: Int,
        val latencyMs: Long,
        val server: String?,
        val contentType: String?,
    ) : HttpResult

    /** The endpoint did not answer: refused, timed out, malformed. */
    data class Failed(val reason: String) : HttpResult
}

internal object HttpChecker {

    const val CONNECT_TIMEOUT_MS = 1_500
    const val READ_TIMEOUT_MS = 2_500

    /** The check never reads more of the body than this. */
    const val BODY_DRAIN_BYTES = 16_384

    const val USER_AGENT = "PocketShell-Servers/2.0 (widget health check)"

    /**
     * One GET to the loopback endpoint. Runs on the caller's IO context.
     * Latency measures connect→headers (the number a status page shows).
     */
    fun check(
        port: Int,
        host: String = "127.0.0.1",
        path: String = "/",
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpResult {
        if (port !in 1..65535) return HttpResult.Failed("port out of range")
        val connection = try {
            val url = URL("http://$host:$port$path")
            url.openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            return HttpResult.Failed(t.message ?: t.javaClass.simpleName)
        }
        return try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.requestMethod = "GET"
            val started = System.currentTimeMillis()
            val code = connection.responseCode
            val latency = System.currentTimeMillis() - started
            val server = connection.headerFields["Server"]?.firstOrNull()
            val contentType = connection.headerFields["Content-Type"]?.firstOrNull()
            // Drain a bounded slice so the server's send buffer empties
            // and the socket closes cleanly; errors here never undo the
            // answered fact above.
            try {
                connection.inputStream.use { input ->
                    val buf = ByteArray(4_096)
                    var read = 0
                    while (read < BODY_DRAIN_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                    }
                }
            } catch (_: Exception) {
            }
            HttpResult.Answered(
                code = code,
                latencyMs = latency,
                server = server,
                contentType = contentType,
            )
        } catch (t: Throwable) {
            HttpResult.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The chip band an answered code renders as. Null = the code is not a
     * plausible HTTP status — an answered-but-garbage fact renders as a
     * failure, never as a colored success.
     */
    fun band(code: Int): HttpBand? = when (code) {
        in 200..299 -> HttpBand.OK
        in 300..399 -> HttpBand.REDIRECT
        in 400..499 -> HttpBand.CLIENT_ERROR
        in 500..599 -> HttpBand.SERVER_ERROR
        else -> null
    }
}

internal enum class HttpBand {
    OK,
    REDIRECT,
    CLIENT_ERROR,
    SERVER_ERROR,
}

/**
 * One endpoint's session history: the ring of recent checks the beat
 * strip renders. In-memory BY DESIGN — the strip says "this session";
 * a restart of the app starts a fresh history instead of persisting a
 * story the widget can no longer stand behind. Pure + JVM-tested.
 */
internal class BeatHistory(private val cap: Int = BEAT_CAP) {

    data class Beat(
        val ok: Boolean,
        /** The HTTP code; null when the check failed before an answer. */
        val code: Int?,
        val latencyMs: Long,
        val atMs: Long,
    )

    private val ring = ArrayDeque<Beat>()

    /** Record one check, evicting the oldest past [cap]. */
    fun record(beat: Beat) {
        ring.addLast(beat)
        while (ring.size > cap) ring.removeFirst()
    }

    /** The recorded beats, oldest first. */
    fun beats(): List<Beat> = ring.toList()

    /** Checks recorded in the current session's window. */
    fun count(): Int = ring.size

    /**
     * The session uptime fraction (ok / recorded), or null when nothing
     * has been recorded — "no data yet" is honest, "100%" is not.
     */
    fun uptimeFraction(): Float? =
        if (ring.isEmpty()) null else ring.count { it.ok }.toFloat() / ring.size

    companion object {
        /** ~40 beats ≈ 3⅓ minutes at the 5 s tick — one strip's width. */
        const val BEAT_CAP = 40
    }
}
