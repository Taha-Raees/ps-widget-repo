package app.pocketshell.widget.network

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A read-only HTTP file server for ONE folder, written for the case "let my laptop grab these files".
 * It runs inside the app, so sharing needs no python and no guest command.
 *
 * It is deliberately small and deliberately strict, because it is the one thing here that other
 * devices can reach:
 *   - GET and HEAD only; HTTP/1.0 semantics (one request per connection, then close);
 *   - a path can never leave the folder: percent-decoding, `..`, backslashes and NUL are refused and the
 *     resolved real path must still be inside the root (so a symlink pointing out is a 404);
 *   - dotfiles and dot-folders are never listed and never served (`.env`, `.ssh`, `.git`…), as are
 *     other names [ShareGuard.isSensitiveName] knows;
 *   - bounded everything: request size, header count, read timeout, concurrent connections.
 */
class ShareServer(private val root: File, private val bindIp: String, private val port: Int) {

    private val canonicalRoot: File = root.canonicalFile
    private var server: ServerSocket? = null
    private val open = Collections.synchronizedSet(HashSet<Socket>())
    private val pool = ThreadPoolExecutor(2, MAX_WORKERS, 10, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUE)) { r -> Thread(r, "share-http").apply { isDaemon = true } }

    @Volatile var served: Int = 0
        private set

    /** The port actually bound (useful when [port] is 0, in tests). */
    val boundPort: Int get() = server?.localPort ?: -1

    /** Bind and start accepting. Throws if the address or port is unavailable. */
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName(bindIp), port), 16)
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                try {
                    open += c
                    pool.execute { try { handle(c) } finally { open -= c; runCatching { c.close() } } }
                } catch (_: RejectedExecutionException) {
                    open -= c
                    runCatching { c.getOutputStream().write(BUSY); c.close() }
                }
            }
        }, "share-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
    }

    // ------------------------------------------------------------- one request

    private fun handle(c: Socket) {
        c.soTimeout = READ_TIMEOUT_MS
        val out = c.getOutputStream()
        try {
            val req = readRequest(BufferedInputStream(c.getInputStream())) ?: return respond(out, 400, "Bad request", head = false)
            val head = req.method == "HEAD"
            if (req.method != "GET" && !head) return respond(out, 405, "Method not allowed", head = false, extra = "Allow: GET, HEAD\r\n")
            when (val r = Paths.resolve(canonicalRoot, req.target)) {
                Paths.Resolved.Bad -> respond(out, 400, "Bad request", head)
                Paths.Resolved.NotFound -> respond(out, 404, "Not found", head)
                is Paths.Resolved.Dir -> {
                    if (!req.target.substringBefore('?').endsWith("/")) {
                        // A folder without its trailing slash: relative links in the listing need it.
                        val loc = req.target.substringBefore('?') + "/"
                        return respond(out, 301, "Moved", head, extra = "Location: ${Paths.encodeSegments(loc)}\r\n")
                    }
                    val index = File(r.dir, "index.html")
                    if (index.isFile && Paths.servable(canonicalRoot, index)) sendFile(out, index, head)
                    else sendBytes(out, 200, "text/html; charset=utf-8", Paths.listing(canonicalRoot, r.dir).toByteArray(Charsets.UTF_8), head)
                }
                is Paths.Resolved.FileOk -> sendFile(out, r.file, head)
            }
            served++
        } catch (_: SocketTimeoutException) {
            // a client that stops talking is simply dropped
        } catch (_: IOException) {
            // the peer went away
        }
    }

    private class Request(val method: String, val target: String)

    private fun readRequest(input: InputStream): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        var headers = 0
        var total = first.length
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            total += line.length
            if (++headers > MAX_HEADERS || total > MAX_REQUEST_BYTES) return null
        }
        return Request(parts[0], parts[1])
    }

    /** One CRLF/LF line, at most [MAX_LINE] bytes; null on EOF or overflow. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= MAX_LINE) return null
            sb.append(b.toChar())
        }
    }

    private fun sendFile(out: OutputStream, file: File, head: Boolean) {
        val len = file.length()
        out.write(headers(200, "OK", Paths.contentType(file.name), len).toByteArray())
        if (head) return
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        out.flush()
    }

    private fun sendBytes(out: OutputStream, code: Int, type: String, body: ByteArray, head: Boolean) {
        out.write(headers(code, reason(code), type, body.size.toLong()).toByteArray())
        if (!head) out.write(body)
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, text: String, head: Boolean, extra: String = "") {
        val body = "<!doctype html><meta charset=utf-8><title>$code</title><p>$code $text</p>".toByteArray()
        out.write(headers(code, text, "text/html; charset=utf-8", body.size.toLong(), extra).toByteArray())
        if (!head) out.write(body)
        out.flush()
    }

    private fun headers(code: Int, reason: String, type: String, length: Long, extra: String = ""): String =
        "HTTP/1.0 $code $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n$extra\r\n"

    private fun reason(code: Int) = when (code) { 200 -> "OK"; 301 -> "Moved"; 404 -> "Not found"; else -> "OK" }

    // ---------------------------------------------------------------- the rules (pure; tested)

    internal object Paths {

        sealed interface Resolved {
            data object Bad : Resolved
            data object NotFound : Resolved
            data class Dir(val dir: File) : Resolved
            data class FileOk(val file: File) : Resolved
        }

        /** A URL target → what to serve. Never returns anything outside [root] and never a dotfile. */
        fun resolve(root: File, target: String): Resolved {
            val rawPath = target.substringBefore('?').substringBefore('#')
            if (!rawPath.startsWith("/")) return Resolved.Bad
            if ('\\' in rawPath || '\u0000' in rawPath) return Resolved.Bad
            val decoded = try {
                URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                return Resolved.Bad
            }
            if ('\u0000' in decoded || '\\' in decoded || decoded.any { it < ' ' }) return Resolved.Bad
            val segments = decoded.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == ".." || it == "." }) return Resolved.Bad
            if (segments.any { it.startsWith(".") || ShareGuard.isSensitiveName(it) }) return Resolved.NotFound
            val f = segments.fold(root) { dir, seg -> File(dir, seg) }
            if (!f.exists()) return Resolved.NotFound
            if (!servable(root, f)) return Resolved.NotFound
            return if (f.isDirectory) Resolved.Dir(f.canonicalFile) else Resolved.FileOk(f.canonicalFile)
        }

        /** The REAL path must still be inside the root (a symlink out is a 404) and not a hidden/sensitive name. */
        fun servable(root: File, f: File): Boolean {
            val real = try { f.canonicalFile } catch (_: IOException) { return false }
            val rootPath = root.canonicalPath
            val p = real.path
            if (p != rootPath && !p.startsWith(rootPath + File.separator)) return false
            val rel = p.removePrefix(rootPath).split(File.separatorChar).filter { it.isNotEmpty() }
            return rel.none { it.startsWith(".") || ShareGuard.isSensitiveName(it) }
        }

        fun encodeSegments(path: String): String =
            path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        fun listing(root: File, dir: File): String {
            val rel = dir.canonicalPath.removePrefix(root.canonicalPath).ifEmpty { "/" }
            val kids = (dir.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") && !ShareGuard.isSensitiveName(it.name) && servable(root, it) }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            return buildString {
                append("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">")
                append("<title>").append(escapeHtml(rel)).append("</title>")
                append("<style>body{font:15px/1.6 system-ui,sans-serif;margin:24px auto;max-width:720px;padding:0 16px}a{text-decoration:none}li{list-style:none;padding:2px 0}small{color:#888;margin-left:8px}</style>")
                append("<h3>").append(escapeHtml(rel)).append("</h3><ul>")
                if (dir.canonicalPath != root.canonicalPath) append("<li><a href=\"../\">../</a></li>")
                for (k in kids) {
                    val name = k.name + if (k.isDirectory) "/" else ""
                    append("<li><a href=\"").append(escapeHtml(encodeSegments(name))).append("\">").append(escapeHtml(name)).append("</a>")
                    if (k.isFile) append("<small>").append(size(k.length())).append("</small>")
                    append("</li>")
                }
                if (kids.isEmpty()) append("<li><small>empty</small></li>")
                append("</ul>")
            }
        }

        fun size(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "${b / 1024} KB"
            b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
            else -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
        }

        private val TYPES = mapOf(
            "html" to "text/html; charset=utf-8", "htm" to "text/html; charset=utf-8", "css" to "text/css; charset=utf-8",
            "js" to "text/javascript; charset=utf-8", "mjs" to "text/javascript; charset=utf-8", "json" to "application/json",
            "txt" to "text/plain; charset=utf-8", "md" to "text/plain; charset=utf-8", "log" to "text/plain; charset=utf-8",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
            "svg" to "image/svg+xml", "ico" to "image/x-icon", "pdf" to "application/pdf", "mp4" to "video/mp4", "webm" to "video/webm",
            "mp3" to "audio/mpeg", "wav" to "audio/wav", "zip" to "application/zip", "wasm" to "application/wasm",
        )

        fun contentType(name: String): String = TYPES[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    }

    companion object {
        const val MAX_WORKERS = 4
        const val MAX_QUEUE = 8
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_LINE = 8_192
        const val MAX_HEADERS = 64
        const val MAX_REQUEST_BYTES = 16_384
        private val BUSY = "HTTP/1.0 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
    }
}
