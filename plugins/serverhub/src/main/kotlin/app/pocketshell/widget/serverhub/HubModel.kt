package app.pocketshell.widget.serverhub

/**
 * The pure half of the Server Hub: what a service is, how its command line is read, quick-start templates,
 * health verdicts, restart decisions, links and log lines. No exec, no sockets, no Android - JVM-tested.
 */

enum class CheckKind(val label: String) { NONE("No check"), TCP("Port"), HTTP("HTTP") }

enum class RestartMode(val label: String) { NEVER("Never"), ON_CRASH("On crash") }

/**
 * A service the user runs from the phone. [command] is a command line run WITHOUT a shell (see [CommandLine]);
 * [port] 0 means "no port"; [wifi] records that it is meant to be reachable from other devices on the Wi-Fi.
 */
data class Service(
    val id: String,
    val name: String,
    val command: String,
    val cwd: String,
    val port: Int,
    val check: CheckKind,
    val path: String,
    val restart: RestartMode,
    val wifi: Boolean,
)

// ==================================================================== command lines

object CommandLine {

    sealed interface Parsed {
        data class Ok(val argv: List<String>) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    private val SHELL_OPERATORS = setOf('|', '&', ';', '>', '<', '`')

    /**
     * Split like a shell would for plain words, quotes and backslashes - but run WITHOUT a shell. Unquoted
     * `| & ; > < ` $(` are refused with a hint, instead of being silently passed to the program as text.
     */
    fun parse(text: String): Parsed {
        if (text.length > 600) return Parsed.Bad("The command is too long")
        if (text.any { it < ' ' && it != '\t' }) return Parsed.Bad("The command has a line break or control character")
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var started = false
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote == '\'' -> if (c == '\'') quote = null else cur.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = null
                    c == '\\' && i + 1 < text.length && text[i + 1] in "\"\\$`" -> { cur.append(text[i + 1]); i++ }
                    c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '{' || text[i + 1] == '(') -> return Parsed.Bad(VARIABLE_HINT)
                    else -> cur.append(c)
                }
                c == '\'' || c == '"' -> { quote = c; started = true }
                c == '\\' && i + 1 < text.length -> { cur.append(text[i + 1]); started = true; i++ }
                c == '$' && i + 1 < text.length && text[i + 1] == '(' -> return Parsed.Bad(SHELL_HINT)
                c == '$' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '{') -> return Parsed.Bad(VARIABLE_HINT)
                c in SHELL_OPERATORS -> return Parsed.Bad(SHELL_HINT)
                c.isWhitespace() -> if (started) { out += cur.toString(); cur.setLength(0); started = false }
                else -> { cur.append(c); started = true }
            }
            i++
        }
        if (quote != null) return Parsed.Bad("A quote is never closed")
        if (started) out += cur.toString()
        if (out.isEmpty()) return Parsed.Bad("Write a command")
        if (out.first().startsWith("-")) return Parsed.Bad("The command must start with a program")
        return Parsed.Ok(out)
    }

    const val SHELL_HINT = "No pipes, redirects or && here. To use them write:  sh -c 'first | second'"
    const val VARIABLE_HINT = "Variables like \$HOME are not expanded here - write the full path, or use  sh -c '...'"
}

// ==================================================================== validation & storage

object Services {

    private val ID = Regex("^[a-z0-9]{8}$")
    private val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun newId(random: java.util.Random = java.security.SecureRandom()): String = buildString { repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Why this service cannot be saved, or null. */
    fun problem(s: Service): String? = when {
        !ID.matches(s.id) -> "bad id"
        s.name.isBlank() || s.name.length > 40 || s.name.any { it == '|' || it < ' ' } -> "Name: 1 to 40 characters, no | or line breaks"
        CommandLine.parse(s.command) is CommandLine.Parsed.Bad -> (CommandLine.parse(s.command) as CommandLine.Parsed.Bad).reason
        s.command.contains('\n') -> "The command has a line break"
        !cwdOk(s.cwd) -> "Folder: a full path such as /root/site"
        s.port !in 0..65535 -> "Port: 0 to 65535"
        s.port == 0 && s.check != CheckKind.NONE -> "A check needs a port"
        s.check == CheckKind.HTTP && (!s.path.startsWith("/") || s.path.length > 100 || s.path.any { it <= ' ' || it == '|' || it == '#' }) -> "Path: starts with /, no spaces"
        s.wifi && s.port == 0 -> "Wi-Fi sharing needs a port"
        else -> null
    }

    fun cwdOk(cwd: String): Boolean =
        cwd.startsWith("/") && cwd.length in 1..200 && cwd.none { it < ' ' || it == '|' } && cwd.split('/').none { it == ".." }

    fun argv(s: Service): List<String>? = (CommandLine.parse(s.command) as? CommandLine.Parsed.Ok)?.argv

    /** `id|name|cwd|port|check|path|restart|wifi|command` per line; the command is last, so it may hold `|`. */
    fun encode(list: List<Service>): String = list.filter { problem(it) == null }.joinToString("") {
        "${it.id}|${it.name}|${it.cwd}|${it.port}|${it.check.name}|${it.path}|${it.restart.name}|${it.wifi}|${it.command}\n"
    }

    fun decode(text: String?): List<Service> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|', limit = 9)
        if (p.size != 9) return@mapNotNull null
        val check = CheckKind.entries.firstOrNull { it.name == p[4] } ?: return@mapNotNull null
        val restart = RestartMode.entries.firstOrNull { it.name == p[6] } ?: return@mapNotNull null
        Service(p[0], p[1], p[8], p[2], p[3].toIntOrNull() ?: return@mapNotNull null, check, p[5], restart, p[7] == "true").takeIf { problem(it) == null }
    }
}

// ==================================================================== templates

/** A quick start. `{PORT}` and `{DIR}` in [command] are filled from the form; [files] are written into the folder when missing. */
data class Template(
    val id: String,
    val name: String,
    val summary: String,
    val command: String,
    val port: Int,
    val dir: String,
    val check: CheckKind,
    val path: String = "/",
    /** The programs the command needs, and the Alpine packages that provide them. */
    val needs: List<String> = emptyList(),
    val packages: List<String> = emptyList(),
    val files: Map<String, String> = emptyMap(),
    val wifi: Boolean = false,
)

object Templates {

    private const val INDEX_HTML = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
        "<title>My phone server</title><style>body{font:16px system-ui;max-width:32rem;margin:3rem auto;padding:0 1rem}</style>" +
        "<h1>Hello from my phone</h1><p>This page is served by PocketShell's Server Hub. Edit <code>index.html</code> in this folder.</p>\n"

    private const val API_PY = "#!/usr/bin/env python3\n" +
        "# A tiny local JSON API using only the standard library. Edit the routes below.\n" +
        "import json, sys, time\n" +
        "from http.server import BaseHTTPRequestHandler, HTTPServer\n\n" +
        "ROUTES = {\n" +
        "    '/': lambda: {'ok': True, 'service': 'phone-api', 'time': time.time()},\n" +
        "    '/health': lambda: {'status': 'up'},\n" +
        "}\n\n" +
        "class H(BaseHTTPRequestHandler):\n" +
        "    def do_GET(self):\n" +
        "        route = ROUTES.get(self.path.split('?')[0])\n" +
        "        body = json.dumps(route() if route else {'error': 'not found'}).encode()\n" +
        "        self.send_response(200 if route else 404)\n" +
        "        self.send_header('Content-Type', 'application/json')\n" +
        "        self.send_header('Content-Length', str(len(body)))\n" +
        "        self.end_headers()\n" +
        "        self.wfile.write(body)\n\n" +
        "    def log_message(self, fmt, *args):\n" +
        "        sys.stderr.write('%s %s\\n' % (self.address_string(), fmt % args))\n\n" +
        "port = int(sys.argv[1]) if len(sys.argv) > 1 else 8001\n" +
        "HTTPServer(('127.0.0.1', port), H).serve_forever()\n"

    val all = listOf(
        Template("static-python", "Static website", "Serve a folder with Python's built-in server", "python3 -m http.server {PORT} --bind 127.0.0.1 --directory {DIR}", 8000, "/root/site", CheckKind.HTTP, needs = listOf("python3"), packages = listOf("python3"), files = mapOf("index.html" to INDEX_HTML)),
        Template("static-caddy", "Static website (Caddy)", "Fast file server with automatic content types", "caddy file-server --listen 127.0.0.1:{PORT} --root {DIR}", 8080, "/root/site", CheckKind.HTTP, needs = listOf("caddy"), packages = listOf("caddy"), files = mapOf("index.html" to INDEX_HTML)),
        Template("json-api", "Local JSON API", "A tiny Python API you can extend (standard library only)", "python3 {DIR}/api.py {PORT}", 8001, "/root/api", CheckKind.HTTP, needs = listOf("python3"), packages = listOf("python3"), files = mapOf("api.py" to API_PY)),
        Template("node-static", "Static website (Node)", "npx http-server - downloads on first start", "npx --yes http-server {DIR} -p {PORT} -a 127.0.0.1", 8081, "/root/site", CheckKind.HTTP, needs = listOf("npx"), packages = listOf("nodejs", "npm"), files = mapOf("index.html" to INDEX_HTML)),
        Template("redis", "Redis", "In-memory database, local only", "redis-server --port {PORT} --bind 127.0.0.1 --dir {DIR}", 6379, "/root/redis", CheckKind.TCP, needs = listOf("redis-server"), packages = listOf("redis")),
        Template("code-server", "VS Code in the browser", "code-server from GUI Apps (install it there first)", "/root/Applications/code-server/app/bin/code-server --bind-addr 127.0.0.1:{PORT} --auth none --disable-telemetry {DIR}", 8082, "/root", CheckKind.HTTP, path = "/healthz", needs = listOf("/root/Applications/code-server/app/bin/code-server")),
        Template("custom", "Your own command", "Anything that listens on a port - or does not", "", 0, "/root", CheckKind.NONE),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** The command with `{PORT}` and `{DIR}` filled; values are checked first so they cannot add arguments. */
    fun fill(t: Template, port: Int, dir: String): String? {
        if (port !in 1..65535 && t.port != 0) return null
        if (!Services.cwdOk(dir) || dir.any { it.isWhitespace() || it in "'\"\\\$`&;<>*?" }) return null
        return t.command.replace("{PORT}", port.toString()).replace("{DIR}", dir)
    }
}

// ==================================================================== health

enum class Health(val label: String) { UP("up"), SLOW("slow"), ERROR("error"), DOWN("down"), UNKNOWN("not checked") }

data class HealthReading(val health: Health, val detail: String, val millis: Long? = null)

object Verdicts {

    const val SLOW_MS = 1_500L

    /** The verdict from the facts: [tcpMs] (null = nothing accepted the connection), [status]/[httpMs] for an HTTP check. */
    fun classify(kind: CheckKind, tcpMs: Long?, status: Int?, httpMs: Long?, error: String?): HealthReading = when {
        kind == CheckKind.NONE -> HealthReading(Health.UNKNOWN, "no check configured")
        tcpMs == null -> HealthReading(Health.DOWN, error ?: "nothing is listening on the port")
        kind == CheckKind.TCP -> HealthReading(if (tcpMs > SLOW_MS) Health.SLOW else Health.UP, "port open", tcpMs)
        status == null -> HealthReading(Health.ERROR, error ?: "port open, but no HTTP answer", tcpMs)
        status >= 500 -> HealthReading(Health.ERROR, "HTTP $status", httpMs)
        (httpMs ?: tcpMs) > SLOW_MS -> HealthReading(Health.SLOW, "HTTP $status", httpMs)
        else -> HealthReading(Health.UP, "HTTP $status", httpMs ?: tcpMs)
    }
}

/** Where a service is in its life. */
enum class Phase(val label: String) { STOPPED("stopped"), STARTING("starting"), RUNNING("running"), STOPPING("stopping"), CRASHED("crashed") }

// ==================================================================== restart policy

object RestartPolicy {

    const val WINDOW_MS = 5 * 60_000L
    const val MAX_RESTARTS = 3
    private val BACKOFF_MS = longArrayOf(1_000, 3_000, 8_000)

    sealed interface Decision {
        data class Restart(val afterMs: Long, val attempt: Int) : Decision
        data class GiveUp(val reason: String) : Decision
        data object None : Decision
    }

    /**
     * After a process exited: should it come back? [recentRestarts] are the times of earlier automatic restarts.
     * A stop the user asked for, a clean exit and a policy of NEVER all mean "no". A crash loop gives up.
     */
    fun decide(mode: RestartMode, exitCode: Int?, stoppedByUser: Boolean, recentRestarts: List<Long>, now: Long): Decision {
        if (mode == RestartMode.NEVER || stoppedByUser || exitCode == 0) return Decision.None
        val inWindow = recentRestarts.count { now - it < WINDOW_MS }
        if (inWindow >= MAX_RESTARTS) return Decision.GiveUp("crashed $MAX_RESTARTS times in ${WINDOW_MS / 60_000} minutes - not restarting again")
        return Decision.Restart(BACKOFF_MS[inWindow.coerceAtMost(BACKOFF_MS.size - 1)], inWindow + 1)
    }
}

// ==================================================================== links

object Links {

    fun local(s: Service): String? = url(s, "127.0.0.1")

    fun lan(s: Service, ip: String?): String? = if (ip == null) null else url(s, ip)

    private fun url(s: Service, host: String): String? {
        if (s.port == 0) return null
        return "http://$host:${s.port}${if (s.check == CheckKind.HTTP) s.path else "/"}"
    }

    /** A service is "web" if it makes sense to open it in a browser. */
    fun isWeb(s: Service) = s.port != 0 && s.check != CheckKind.TCP
}

// ==================================================================== logs

object LogLines {

    enum class Level { ERROR, WARN, INFO }

    private val ERR = Regex("(?i)\\b(error|fatal|exception|traceback|panic|failed|refused)\\b")
    private val WARN = Regex("(?i)(warn|deprecat)")

    fun level(line: String): Level = when {
        ERR.containsMatchIn(line) -> Level.ERROR
        WARN.containsMatchIn(line) -> Level.WARN
        else -> Level.INFO
    }

    /** The last [n] non-empty lines, each cut to [maxWidth] characters (a runaway line cannot flood the screen). */
    fun tail(text: String, n: Int = 200, maxWidth: Int = 400): List<String> =
        text.lines().filter { it.isNotBlank() }.takeLast(n).map { if (it.length > maxWidth) it.take(maxWidth) + "…" else it }

    /** ANSI colour codes would show as junk in the page. */
    fun stripAnsi(text: String): String = text.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")

    const val MAX_LOG_BYTES = 2L * 1024 * 1024
}

// ==================================================================== listening ports

/** A port something in the guest listens on, with whether this hub started it. */
data class ListeningRow(val port: Int, val name: String, val pid: Int?, val hubServiceId: String?, val wifi: Boolean?)
