package app.pocketshell.widget.packages

/**
 * The pure half of Packages & Environment: the apk database, dependency graph, change plans (parsed from
 * `apk --simulate`), removal guards, search results, bundles and OS facts. No exec, no sockets, no Android.
 *
 * The apk installed database (`/lib/apk/db/installed`) is plain text, so what is installed is read straight
 * from the file - instantly and for free. Only changes and the repository index need `apk` itself.
 */

data class Pkg(
    val name: String,
    val version: String,
    val arch: String,
    val installedBytes: Long,
    val description: String,
    val url: String?,
    val origin: String?,
    val depends: List<String>,
    val provides: List<String>,
)

object ApkDb {

    /** Parse the installed database: one record per blank-line-separated block of `X:value` lines. */
    fun parse(text: String): List<Pkg> {
        val out = ArrayList<Pkg>(512)
        var name: String? = null; var version = ""; var arch = ""; var size = 0L
        var desc = ""; var url: String? = null; var origin: String? = null
        var deps: List<String> = emptyList(); var provides: List<String> = emptyList()
        fun flush() {
            name?.let { out += Pkg(it, version, arch, size, desc, url, origin, deps, provides) }
            name = null; version = ""; arch = ""; size = 0L; desc = ""; url = null; origin = null; deps = emptyList(); provides = emptyList()
        }
        for (line in text.lineSequence()) {
            if (line.isEmpty()) { flush(); continue }
            if (line.length < 2 || line[1] != ':') continue
            val v = line.substring(2)
            when (line[0]) {
                'P' -> name = v
                'V' -> version = v
                'A' -> arch = v
                'I' -> size = v.toLongOrNull() ?: 0L
                'T' -> desc = v
                'U' -> url = v.takeIf { it.isNotBlank() }
                'o' -> origin = v.takeIf { it.isNotBlank() }
                'D' -> deps = v.split(' ').filter { it.isNotBlank() }
                'p' -> provides = v.split(' ').filter { it.isNotBlank() }
            }
        }
        flush()
        return out
    }
}

object World {

    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*")

    /** The packages the user (or a tool) asked for by name: `/etc/apk/world`, constraints and pins stripped. */
    fun parse(text: String): Set<String> = text.lines().mapNotNull { NAME.find(it.trim())?.value }.toSet()
}

object Deps {

    /** The package name a dependency token points at, with `so:`/`cmd:`/`pc:` virtuals resolved through [providers]. */
    private fun target(token: String, providers: Map<String, String>): String? {
        if (token.startsWith("!")) return null
        val key = token.takeWhile { it !in "<>=~" }
        return providers[key]
    }

    /** package -> sorted names of installed packages that depend on it (directly). */
    fun requiredBy(pkgs: List<Pkg>): Map<String, List<String>> {
        val providers = HashMap<String, String>(pkgs.size * 3)
        for (p in pkgs) {
            providers.putIfAbsent(p.name, p.name)
            for (v in p.provides) providers.putIfAbsent(v.takeWhile { it !in "<>=~" }, p.name)
        }
        val rev = HashMap<String, MutableSet<String>>()
        for (p in pkgs) for (d in p.depends) {
            val t = target(d, providers) ?: continue
            if (t != p.name) rev.getOrPut(t) { sortedSetOf() }.add(p.name)
        }
        return rev.mapValues { it.value.toList() }
    }
}

enum class Scope(val label: String) { ADDED("Added by you"), ALL("Everything") }

object PkgFilter {

    fun apply(pkgs: List<Pkg>, world: Set<String>, scope: Scope, query: String): List<Pkg> {
        val q = query.trim().lowercase()
        return pkgs.filter { p ->
            (scope == Scope.ALL || p.name in world) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.description.lowercase().contains(q))
        }.sortedBy { it.name }
    }
}

// ==================================================================== plans

enum class ChangeKind { INSTALL, UPGRADE, DOWNGRADE, REINSTALL, PURGE }

data class Change(val kind: ChangeKind, val name: String, val version: String, val from: String? = null)

data class Plan(val changes: List<Change>, val resultMiB: Double?, val resultPackages: Int?) {
    val installs get() = changes.filter { it.kind == ChangeKind.INSTALL }
    val upgrades get() = changes.filter { it.kind == ChangeKind.UPGRADE }
    val removals get() = changes.filter { it.kind == ChangeKind.PURGE }
    val isEmpty get() = changes.isEmpty()

    /** How the on-disk size moves, given the size before ([beforeBytes]). Null when apk printed no total. */
    fun deltaBytes(beforeBytes: Long): Long? = resultMiB?.let { ((it * 1_048_576.0) - beforeBytes).toLong() }
}

object PlanParse {

    private val LINE = Regex("^\\((\\d+)/(\\d+)\\)\\s+(Installing|Upgrading|Downgrading|Reinstalling|Purging)\\s+(\\S+)\\s+\\((.+)\\)\\s*$")
    private val TOTAL = Regex("^OK:\\s+([\\d.]+)\\s+(MiB|KiB|GiB|B)\\s+in\\s+(\\d+)\\s+packages?")

    fun parse(output: String): Plan {
        val changes = ArrayList<Change>()
        var mib: Double? = null
        var count: Int? = null
        for (raw in output.lines()) {
            val line = raw.trim()
            LINE.matchEntire(line)?.let { m ->
                val verb = m.groupValues[3]
                val name = m.groupValues[4]
                val detail = m.groupValues[5]
                val kind = when (verb) {
                    "Installing" -> ChangeKind.INSTALL; "Upgrading" -> ChangeKind.UPGRADE; "Downgrading" -> ChangeKind.DOWNGRADE
                    "Reinstalling" -> ChangeKind.REINSTALL; else -> ChangeKind.PURGE
                }
                if (" -> " in detail) changes += Change(kind, name, detail.substringAfter(" -> "), detail.substringBefore(" -> "))
                else changes += Change(kind, name, detail)
                return@let
            }
            TOTAL.matchEntire(line)?.let { m ->
                val n = m.groupValues[1].toDoubleOrNull()
                mib = when (m.groupValues[2]) { "GiB" -> n?.times(1024); "MiB" -> n; "KiB" -> n?.div(1024); else -> n?.div(1_048_576) }
                count = m.groupValues[3].toIntOrNull()
            }
        }
        return Plan(changes, mib, count)
    }
}

/** `apk del X` never cascades: when others still need X it only drops it from the "added by you" list. */
object NotRemoved {

    private val HEADER = "following packages are not removed due to"
    private val ENTRY = Regex("^\\s+(\\S+):\\s*(.*)$")

    /** package -> the installed packages that still need it. Empty when apk removed everything asked. */
    fun parse(output: String): Map<String, List<String>> {
        val lines = output.lines()
        val start = lines.indexOfFirst { HEADER in it }
        if (start < 0) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in lines.drop(start + 1)) {
            if (line.isBlank() || line.startsWith("OK:")) break
            val m = ENTRY.matchEntire(line)
            if (m != null) {
                current = out.getOrPut(m.groupValues[1]) { ArrayList() }
                current += m.groupValues[2].split(' ').filter { it.isNotBlank() }
            } else if (line.first().isWhitespace()) {
                current?.addAll(line.trim().split(' ').filter { it.isNotBlank() })
            }
        }
        return out
    }
}

/** The exact argv for each apk call. Names are validated here, so nothing else can reach apk's option parser. */
object ApkCmd {

    const val APK = "/sbin/apk"
    private val BASE = listOf(APK, "--no-progress")

    private fun names(list: List<String>): List<String> {
        require(list.all { PkgNames.valid(it) }) { "bad package name" }
        return list
    }

    fun add(pkgs: List<String>, simulate: Boolean, upgrade: Boolean = false) =
        BASE + "add" + listOfNotNull("--simulate".takeIf { simulate }, "-u".takeIf { upgrade }) + names(pkgs)

    fun del(pkgs: List<String>, simulate: Boolean) = BASE + "del" + listOfNotNull("--simulate".takeIf { simulate }) + names(pkgs)

    fun upgradeAll(simulate: Boolean) = BASE + "upgrade" + listOfNotNull("--simulate".takeIf { simulate })

    val update = BASE + "update"
    val listUpgradable = BASE + listOf("list", "-u")
    val fix = BASE + "fix"

    fun search(query: String): List<String> {
        require(query.trim().length >= 2 && Regex("^[A-Za-z0-9 ._+-]{2,60}$").matches(query.trim())) { "bad search" }
        return BASE + listOf("search", "-v", "-d", query.trim())
    }

    fun info(pkg: String) = BASE + listOf("info", "-a") + names(listOf(pkg))
}

// ==================================================================== guards

object PkgNames {

    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,80}$")

    fun valid(name: String): Boolean = VALID.matches(name)

    const val MAX_AT_ONCE = 16

    /** `git, curl  jq` -> names; null when anything is not a package name or there are too many. */
    fun parseList(text: String): List<String>? {
        val names = text.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty() || names.size > MAX_AT_ONCE || names.any { !valid(it) }) return null
        return names
    }
}

object RemovalGuard {

    /** What Linux itself stands on. Removing any of these would leave a phone with no working shell or package manager. */
    val PROTECTED = setOf(
        "alpine-base", "alpine-baselayout", "alpine-baselayout-data", "alpine-keys", "alpine-release", "apk-tools",
        "busybox", "busybox-binsh", "musl", "musl-utils", "ssl_client", "libc-utils", "ca-certificates-bundle", "scanelf",
        "openrc", "bash", "sudo", "openssh", "openssh-client-default", "libssl3", "libcrypto3", "zlib",
    )

    const val LARGE_REMOVAL = 12
    const val MAX_REMOVAL = 80

    /** A plain-words refusal, or null when the removal may go ahead (possibly after a stronger confirmation). */
    fun check(plan: Plan): String? {
        val hit = plan.removals.map { it.name }.firstOrNull { it in PROTECTED }
        if (hit != null) return "That would also remove $hit, which Linux needs to work."
        if (plan.removals.size > MAX_REMOVAL) return "That would remove ${plan.removals.size} packages - too many to do from here."
        return null
    }

    fun needsTypedConfirm(plan: Plan): Boolean = plan.removals.size >= LARGE_REMOVAL
}

// ==================================================================== search & updates

data class SearchHit(val name: String, val version: String, val description: String)

object SearchParse {

    const val MAX_HITS = 80

    /** `name-1.2.3-r0 - description` lines; exact name first, then prefix matches, then the rest. */
    fun parse(output: String, query: String): List<SearchHit> {
        val q = query.trim().lowercase()
        val hits = output.lines().mapNotNull { line ->
            val nv = line.substringBefore(" - ").trim()
            val (name, version) = ApkNames.split(nv) ?: return@mapNotNull null
            SearchHit(name, version, line.substringAfter(" - ", "").trim())
        }
        return hits.sortedWith(compareBy({ if (it.name.lowercase() == q) 0 else if (it.name.lowercase().startsWith(q)) 1 else 2 }, { it.name.length }, { it.name })).take(MAX_HITS)
    }
}

object ApkNames {

    private val NV = Regex("^(.+)-([^-]+-r\\d+)$")

    /** `py3-foo-1.2.3-r0` -> (`py3-foo`, `1.2.3-r0`). */
    fun split(nameVersion: String): Pair<String, String>? = NV.matchEntire(nameVersion.trim())?.let { it.groupValues[1] to it.groupValues[2] }
}

data class Upgradable(val name: String, val from: String, val to: String)

object UpgradeParse {

    /** `apk list -u`: `new-1.2-r0 aarch64 {origin} (lic) [upgradable from: old-1.1-r0]`. */
    fun parse(output: String): List<Upgradable> = output.lines().mapNotNull { line ->
        if ("[upgradable from:" !in line) return@mapNotNull null
        val (name, to) = ApkNames.split(line.substringBefore(' ')) ?: return@mapNotNull null
        val from = line.substringAfter("[upgradable from:").substringBefore(']').trim()
        Upgradable(name, ApkNames.split(from)?.second ?: from, to)
    }.sortedBy { it.name }
}

object ApkErrors {

    /** One line a person can read from a failed apk run. */
    fun reason(stdout: String, stderr: String, error: String?): String {
        val all = (stderr + "\n" + stdout).lines().map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            all.any { it.contains("lock", true) && it.contains("database", true) } -> "Another package operation is running. Wait for it, then try again."
            all.any { it.contains("unable to select packages", true) } ->
                all.firstOrNull { it.contains("no such package", true) || it.contains("unsatisfiable", true) }?.take(200) ?: "No such package - update the index and try again."
            all.any { it.contains("temporary error", true) || it.contains("network", true) && it.contains("error", true) } -> "The repository could not be reached. Check the connection."
            all.lastOrNull { it.startsWith("ERROR") } != null -> all.last { it.startsWith("ERROR") }.take(200)
            all.isNotEmpty() -> all.last().take(200)
            else -> error ?: "apk failed without a message"
        }
    }

    fun needsUpdate(output: String): Boolean {
        val t = output.lowercase()
        return "no such package" in t || "unable to select packages" in t && "apkindex" !in t || "temporary error" in t
    }
}

// ==================================================================== bundles

/** A named set of command-line tools, installed in one go. Every package name is checked against the aarch64 index. */
data class Bundle(val id: String, val name: String, val summary: String, val packages: List<String>)

object Bundles {
    val all = listOf(
        Bundle("essentials", "Essentials", "Everyday terminal tools", listOf("git", "curl", "wget", "openssh", "nano", "vim", "tmux", "htop", "jq", "ripgrep", "fd", "less", "tree", "rsync", "unzip", "zip", "file")),
        Bundle("shell", "Nicer shell", "A friendlier prompt and finders", listOf("zsh", "fish", "fzf", "bat", "eza", "ncdu", "btop", "neovim", "lazygit", "tig")),
        Bundle("python", "Python", "Interpreter, pip, venv, uv", listOf("python3", "py3-pip", "py3-virtualenv", "python3-dev", "pipx", "uv")),
        Bundle("node", "Node.js", "Node, npm, yarn, pnpm", listOf("nodejs", "npm", "yarn", "pnpm")),
        Bundle("go", "Go", "The Go toolchain", listOf("go")),
        Bundle("rust", "Rust", "rustc and cargo", listOf("rust", "cargo")),
        Bundle("java", "Java 21", "OpenJDK, Maven, Gradle", listOf("openjdk21-jdk", "maven", "gradle")),
        Bundle("cbuild", "C / C++ build", "Compilers and build systems", listOf("build-base", "cmake", "make", "meson", "pkgconf", "autoconf", "automake", "gdb")),
        Bundle("netdiag", "Network tools", "nmap, dig, mtr, tracepath, tcpdump, socat", listOf("nmap", "bind-tools", "mtr", "iputils-tracepath", "tcpdump", "socat", "netcat-openbsd", "iproute2", "iperf3", "whois")),
        Bundle("servers", "Self-hosting", "nginx, caddy, redis, sqlite", listOf("nginx", "caddy", "redis", "sqlite", "supervisor")),
        Bundle("media", "Media CLI", "ffmpeg, ImageMagick, yt-dlp", listOf("ffmpeg", "imagemagick", "yt-dlp", "exiftool")),
        Bundle("vpn", "VPN & mesh", "WireGuard, OpenVPN, Tailscale", listOf("wireguard-tools", "openvpn", "tailscale")),
        Bundle("devops", "DevOps", "kubectl, helm, k9s, ansible, GitHub CLI", listOf("kubectl", "helm", "k9s", "ansible", "github-cli", "glab")),
        Bundle("debug", "Debugging", "strace, ltrace, lsof, gdb", listOf("strace", "ltrace", "lsof", "gdb", "iotop")),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Which of the bundle's packages are missing, given the installed names. */
    fun missing(b: Bundle, installed: Set<String>): List<String> = b.packages.filter { it !in installed }
}

// ==================================================================== facts

object OsRelease {

    fun parse(text: String): Map<String, String> = text.lines().mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq <= 0 || line.startsWith("#")) return@mapNotNull null
        line.substring(0, eq).trim() to line.substring(eq + 1).trim().trim('"')
    }.toMap()
}

object Fmt {

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024L * 1024 -> "%.0f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1_048_576.0)
        else -> "%.2f GB".format(n / 1_073_741_824.0)
    }

    fun signed(n: Long): String = (if (n >= 0) "+" else "-") + bytes(kotlin.math.abs(n))
}

/** Repository lines: a URL per line, `#` for disabled. */
object Repos {

    data class Repo(val url: String, val enabled: Boolean) {
        val label: String get() = url.removePrefix("https://").removePrefix("http://")
        val secure: Boolean get() = url.startsWith("https://")
    }

    fun parse(text: String): List<Repo> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
        val disabled = line.startsWith("#")
        val body = line.removePrefix("#").trim()
        if (body.startsWith("http://") || body.startsWith("https://") || body.startsWith("/")) Repo(body, !disabled) else null
    }
}
