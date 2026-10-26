package app.pocketshell.widget.packages

/**
 * The pure half of Packages & Environment: the dpkg database, dependency graph, change plans (parsed from
 * `apt-get --simulate`), removal guards, search results, bundles and OS facts. No exec, no sockets, no Android.
 *
 * The dpkg status database (`/var/lib/dpkg/status`) is plain text, so what is installed is read straight
 * from the file - instantly and for free. Only changes and the repository index need apt itself.
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
    /** Essential, or Priority required: what dpkg itself treats as the base system. */
    val essential: Boolean = false,
)

object DpkgStatus {

    /**
     * Parse the status database: one stanza per blank-line-separated block of `Field: value` lines, with
     * continuation lines starting with a space. Only packages whose status is `install ok installed` count;
     * removed-but-configured (`deinstall ok config-files`) and half-installed entries do not.
     */
    fun parse(text: String): List<Pkg> {
        val out = ArrayList<Pkg>(512)
        val fields = HashMap<String, String>()
        var last: String? = null
        fun flush() {
            val name = fields["Package"]
            if (name != null && fields["Status"] == "install ok installed") {
                out += Pkg(
                    name = name,
                    version = fields["Version"].orEmpty(),
                    arch = fields["Architecture"].orEmpty(),
                    installedBytes = (fields["Installed-Size"]?.trim()?.toLongOrNull() ?: 0L) * 1024L,
                    description = fields["Description"].orEmpty(),
                    url = fields["Homepage"]?.takeIf { it.isNotBlank() },
                    origin = fields["Source"]?.substringBefore(' ')?.takeIf { it.isNotBlank() },
                    depends = depTokens(fields["Pre-Depends"]) + depTokens(fields["Depends"]),
                    provides = depTokens(fields["Provides"]),
                    essential = fields["Essential"] == "yes" || fields["Priority"] == "required",
                )
            }
            fields.clear(); last = null
        }
        for (line in text.lineSequence()) {
            if (line.isEmpty()) { flush(); continue }
            if (line[0] == ' ' || line[0] == '\t') { continue } // continuation (long descriptions, conffiles)
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            last = line.substring(0, colon)
            fields[last!!] = line.substring(colon + 1).trim()
        }
        flush()
        return out
    }

    /** `a (>= 1), b | c, d:any` -> every package name mentioned (alternatives included), versions and arch stripped. */
    fun depTokens(field: String?): List<String> = field.orEmpty().split(',', '|')
        .map { it.trim().substringBefore(' ').substringBefore('(').substringBefore(':').trim() }
        .filter { it.isNotEmpty() }
}

object ManualMarks {

    private val STANZA_SPLIT = Regex("\\n\\s*\\n")

    /** Names apt marked `Auto-Installed: 1` in `/var/lib/apt/extended_states` (pulled in as dependencies). */
    fun parseAuto(text: String): Set<String> = text.split(STANZA_SPLIT).mapNotNull { stanza ->
        var name: String? = null
        var auto = false
        for (line in stanza.lines()) {
            if (line.startsWith("Package:")) name = line.substringAfter(':').trim()
            if (line.startsWith("Auto-Installed:")) auto = line.substringAfter(':').trim() == "1"
        }
        name?.takeIf { auto }
    }.toSet()

    /**
     * The packages the user (or a tool) asked for by name, apt's "manual" set: installed, not marked
     * automatic, and not part of the base system the image came with.
     */
    fun added(installed: List<Pkg>, auto: Set<String>): Set<String> =
        installed.filter { it.name !in auto && !it.essential }.map { it.name }.toSet()
}

object Deps {

    /** package -> sorted names of installed packages that depend on it (directly). */
    fun requiredBy(pkgs: List<Pkg>): Map<String, List<String>> {
        val providers = HashMap<String, String>(pkgs.size * 3)
        for (p in pkgs) {
            providers.putIfAbsent(p.name, p.name)
            for (v in p.provides) providers.putIfAbsent(v, p.name)
        }
        val rev = HashMap<String, MutableSet<String>>()
        for (p in pkgs) for (d in p.depends) {
            val t = providers[d] ?: continue
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

data class Plan(val changes: List<Change>, val deltaKnownBytes: Long?, val resultPackages: Int? = null) {
    val installs get() = changes.filter { it.kind == ChangeKind.INSTALL }
    val upgrades get() = changes.filter { it.kind == ChangeKind.UPGRADE }
    val removals get() = changes.filter { it.kind == ChangeKind.PURGE }
    val isEmpty get() = changes.isEmpty()

    /** How the on-disk size moves (apt reports the change itself). Null when apt printed no size line. */
    @Suppress("UNUSED_PARAMETER")
    fun deltaBytes(beforeBytes: Long): Long? = deltaKnownBytes
}

object PlanParse {

    // Inst name [old] (new suite [arch])   Remv name [old]   Purg name [old]
    private val INST = Regex("^Inst\\s+(\\S+)(?:\\s+\\[([^\\]]+)\\])?\\s+\\((\\S+)[^)]*\\).*$")
    private val REMV = Regex("^(?:Remv|Purg)\\s+(\\S+)(?:\\s+\\[([^\\]]+)\\])?.*$")
    // After this operation, 12.3 MB of additional disk space will be used. / ... 1,024 kB disk space will be freed.
    private val SIZE = Regex("^After this operation, ([\\d.,]+)\\s*(B|kB|MB|GB) (?:of additional )?disk space will be (used|freed)\\.?$")

    fun parse(output: String): Plan {
        val changes = ArrayList<Change>()
        var delta: Long? = null
        for (raw in output.lines()) {
            val line = raw.trim()
            INST.matchEntire(line)?.let { m ->
                val name = m.groupValues[1]
                val old = m.groupValues[2].takeIf { it.isNotEmpty() }
                val new = m.groupValues[3]
                changes += when {
                    old == null -> Change(ChangeKind.INSTALL, name, new)
                    old == new -> Change(ChangeKind.REINSTALL, name, new)
                    else -> Change(ChangeKind.UPGRADE, name, new, old)
                }
                return@let
            }
            REMV.matchEntire(line)?.let { m ->
                changes += Change(ChangeKind.PURGE, m.groupValues[1], m.groupValues[2])
                return@let
            }
            SIZE.matchEntire(line)?.let { m ->
                val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@let
                val bytes = when (m.groupValues[2]) { "GB" -> n * 1e9; "MB" -> n * 1e6; "kB" -> n * 1e3; else -> n }
                delta = (if (m.groupValues[3] == "freed") -bytes else bytes).toLong()
            }
        }
        // A downgrade is an Inst line too; apt says so in its summary ("N downgraded").
        return Plan(changes.distinctBy { it.kind to it.name }, delta)
    }
}

/**
 * apk's `del` never cascaded and listed what stayed; `apt-get remove` removes whatever depends on the
 * named packages, and the plan lists it as removals (which [RemovalGuard] checks). Nothing stays behind.
 */
object NotRemoved {
    fun parse(@Suppress("UNUSED_PARAMETER") output: String): Map<String, List<String>> = emptyMap()
}

/** The exact argv for each apt call. Names are validated here, so nothing else can reach apt's option parser. */
object AptCmd {

    const val APT_GET = "/usr/bin/apt-get"
    private val ENV = listOf("env", "DEBIAN_FRONTEND=noninteractive")
    private val BASE = ENV + listOf(APT_GET, "-q", "-y")

    private fun names(list: List<String>): List<String> {
        require(list.all { PkgNames.valid(it) }) { "bad package name" }
        return list
    }

    private fun sim(simulate: Boolean) = listOfNotNull("--simulate".takeIf { simulate })

    fun add(pkgs: List<String>, simulate: Boolean, upgrade: Boolean = false) =
        BASE + "install" + sim(simulate) + listOfNotNull("--only-upgrade".takeIf { upgrade }) + names(pkgs)

    /** `remove --autoremove`: like apk del, dependencies nothing else needs go too. */
    fun del(pkgs: List<String>, simulate: Boolean) = BASE + listOf("remove", "--autoremove") + sim(simulate) + names(pkgs)

    fun upgradeAll(simulate: Boolean) = BASE + listOf("upgrade", "--with-new-pkgs") + sim(simulate)

    val update = BASE + "update"
    val listUpgradable = ENV + listOf("/usr/bin/apt", "list", "--upgradable")

    /** Repair, in order: finish interrupted configuration, then let apt fix broken dependencies. */
    val fixConfigure = ENV + listOf("/usr/bin/dpkg", "--configure", "-a")
    val fix = BASE + listOf("install", "-f")

    fun search(query: String): List<String> {
        require(query.trim().length >= 2 && Regex("^[A-Za-z0-9 ._+-]{2,60}$").matches(query.trim())) { "bad search" }
        return ENV + listOf("/usr/bin/apt-cache", "search", "--", query.trim())
    }

    fun info(pkg: String) = ENV + listOf("/usr/bin/apt-cache", "show", "--no-all-versions") + names(listOf(pkg))
}

// ==================================================================== guards

object PkgNames {

    // Debian policy: lowercase letters, digits, + - . ; starting alphanumeric.
    private val VALID = Regex("^[a-z0-9][a-z0-9.+-]{0,80}$")

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

    /**
     * What Linux itself stands on. Removing any of these would leave a phone with no working shell or package
     * manager. The installed database adds every Essential / required package (see [check]).
     */
    val PROTECTED = setOf(
        "apt", "dpkg", "base-files", "base-passwd", "bash", "coreutils", "dash", "debianutils", "diffutils",
        "findutils", "grep", "gzip", "hostname", "init-system-helpers", "libc6", "libc-bin", "login", "mawk",
        "ncurses-base", "ncurses-bin", "perl-base", "sed", "sysvinit-utils", "tar", "util-linux",
        "debian-archive-keyring", "gpgv", "sqv", "ca-certificates", "libapt-pkg7.0", "libssl3t64", "zlib1g",
    )

    const val LARGE_REMOVAL = 12
    const val MAX_REMOVAL = 80

    /** A plain-words refusal, or null when the removal may go ahead (possibly after a stronger confirmation). */
    fun check(plan: Plan, essential: Set<String> = emptySet()): String? {
        val hit = plan.removals.map { it.name }.firstOrNull { it in PROTECTED || it in essential }
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

    /** `apt-cache search` lines: `name - description`; exact name first, then prefix matches, then the rest. */
    fun parse(output: String, query: String): List<SearchHit> {
        val q = query.trim().lowercase()
        val hits = output.lines().mapNotNull { line ->
            if (" - " !in line) return@mapNotNull null
            val name = line.substringBefore(" - ").trim()
            if (!PkgNames.valid(name)) return@mapNotNull null
            SearchHit(name, "", line.substringAfter(" - ").trim())
        }
        return hits.sortedWith(compareBy({ if (it.name.lowercase() == q) 0 else if (it.name.lowercase().startsWith(q)) 1 else 2 }, { it.name.length }, { it.name })).take(MAX_HITS)
    }
}

data class Upgradable(val name: String, val from: String, val to: String)

object UpgradeParse {

    private val LINE = Regex("^([a-z0-9][a-z0-9.+-]*)/\\S+\\s+(\\S+)\\s+\\S+\\s+\\[upgradable from: ([^\\]]+)\\]")

    /** `apt list --upgradable`: `name/suite new arch [upgradable from: old]` (after a `Listing...` line). */
    fun parse(output: String): List<Upgradable> = output.lines().mapNotNull { line ->
        val m = LINE.find(line.trim()) ?: return@mapNotNull null
        Upgradable(m.groupValues[1], m.groupValues[3].trim(), m.groupValues[2])
    }.sortedBy { it.name }
}

object AptErrors {

    /** One line a person can read from a failed apt run. */
    fun reason(stdout: String, stderr: String, error: String?): String {
        val all = (stderr + "\n" + stdout).lines().map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            all.any { it.contains("Could not get lock", true) || it.contains("Unable to acquire the dpkg frontend lock", true) } ->
                "Another package operation is running. Wait for it, then try again."
            all.any { it.contains("Unable to locate package", true) || it.contains("has no installation candidate", true) } ->
                all.first { it.contains("Unable to locate package", true) || it.contains("has no installation candidate", true) }.removePrefix("E: ").take(200)
            all.any { it.contains("Temporary failure resolving", true) || it.contains("Could not resolve", true) || it.contains("Network is unreachable", true) } ->
                "The repository could not be reached. Check the connection."
            all.lastOrNull { it.startsWith("E:") } != null -> all.last { it.startsWith("E:") }.removePrefix("E:").trim().take(200)
            all.isNotEmpty() -> all.last().take(200)
            else -> error ?: "apt failed without a message"
        }
    }

    /** The index is missing or stale: refreshing it and retrying may help. */
    fun needsUpdate(output: String): Boolean {
        val t = output.lowercase()
        return "unable to locate package" in t || "has no installation candidate" in t || "404  not found" in t || "404 not found" in t
    }
}

// ==================================================================== bundles

/** A named set of command-line tools, installed in one go. Every package name is checked against Debian 13's arm64 index. */
data class Bundle(val id: String, val name: String, val summary: String, val packages: List<String>)

object Bundles {
    val all = listOf(
        Bundle("essentials", "Essentials", "Everyday terminal tools", listOf("git", "curl", "wget", "openssh-client", "nano", "vim", "tmux", "htop", "jq", "ripgrep", "fd-find", "less", "tree", "rsync", "unzip", "zip", "file")),
        Bundle("shell", "Nicer shell", "A friendlier prompt and finders", listOf("zsh", "fish", "fzf", "bat", "eza", "ncdu", "btop", "neovim", "tig")),
        Bundle("python", "Python", "Interpreter, pip, venv, pipx", listOf("python3", "python3-pip", "python3-venv", "python3-dev", "pipx")),
        Bundle("node", "Node.js", "Node and npm (Debian)", listOf("nodejs", "npm")),
        Bundle("go", "Go", "The Go toolchain", listOf("golang-go")),
        Bundle("rust", "Rust", "rustc and cargo", listOf("rustc", "cargo")),
        Bundle("java", "Java 21", "OpenJDK, Maven, Gradle", listOf("openjdk-21-jdk-headless", "maven", "gradle")),
        Bundle("cbuild", "C / C++ build", "Compilers and build systems", listOf("build-essential", "cmake", "meson", "ninja-build", "pkgconf", "autoconf", "automake", "gdb")),
        Bundle("netdiag", "Network tools", "nmap, dig, mtr, tracepath, tcpdump, socat", listOf("nmap", "bind9-dnsutils", "mtr-tiny", "iputils-tracepath", "tcpdump", "socat", "netcat-openbsd", "iproute2", "iperf3", "whois")),
        Bundle("servers", "Self-hosting", "nginx, caddy, redis, sqlite", listOf("nginx", "caddy", "redis-server", "sqlite3", "supervisor")),
        Bundle("media", "Media CLI", "ffmpeg, ImageMagick, yt-dlp", listOf("ffmpeg", "imagemagick", "yt-dlp", "libimage-exiftool-perl")),
        Bundle("vpn", "VPN", "WireGuard and OpenVPN", listOf("wireguard-tools", "openvpn")),
        Bundle("devops", "DevOps", "ansible, GitHub CLI, GitLab CLI", listOf("ansible", "gh", "glab")),
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

/** apt sources: deb822 `.sources` stanzas (URIs + Suites, `Enabled: no` to disable) and one-line `.list` entries. */
object Repos {

    data class Repo(val url: String, val enabled: Boolean) {
        val label: String get() = url.removePrefix("https://").removePrefix("http://")
        val secure: Boolean get() = url.startsWith("https://")
    }

    /** [deb822] is the text of the `.sources` files, [oneLine] that of the `.list` files. */
    fun parse(deb822: String, oneLine: String = ""): List<Repo> {
        val out = ArrayList<Repo>()
        for (stanza in deb822.split(Regex("\\n\\s*\\n"))) {
            val f = stanza.lines().filter { !it.trimStart().startsWith("#") && ':' in it }
                .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            val uris = f["URIs"]?.split(' ')?.filter { it.isNotBlank() } ?: continue
            val suites = f["Suites"]?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
            val enabled = f["Enabled"]?.lowercase() != "no"
            for (u in uris) {
                if (suites.isEmpty()) out += Repo(u, enabled) else suites.forEach { out += Repo("${u.trimEnd('/')} $it", enabled) }
            }
        }
        for (line in oneLine.lines().map { it.trim() }) {
            val disabled = line.startsWith("#")
            val parts = line.removePrefix("#").trim().split(Regex("\\s+"))
            if (parts.size >= 3 && parts[0] == "deb") {
                val uriAt = if (parts[1].startsWith("[")) parts.indexOfFirst { it.startsWith("http") || it.startsWith("/") } else 1
                if (uriAt > 0 && uriAt + 1 < parts.size) out += Repo("${parts[uriAt].trimEnd('/')} ${parts[uriAt + 1]}", !disabled)
            }
        }
        return out
    }
}
