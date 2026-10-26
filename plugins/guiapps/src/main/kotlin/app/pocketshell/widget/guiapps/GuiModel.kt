package app.pocketshell.widget.guiapps

/**
 * The pure half of the GUI App Library: the catalog, `.desktop` files, ELF flavour, launch plans and the
 * installed-app file format. No sockets, no exec, no Android - JVM-tested.
 *
 * Two ways an app arrives in the PocketShell guest (Debian 13, glibc):
 *  - DEB      - Debian packages (`apt-get install`), from the guest's own signed archive;
 *  - APPIMAGE - prebuilt arm64 AppImages from the projects' own releases, running on the guest's glibc.
 */

enum class Runtime(val label: String) { DEB("Debian"), APPIMAGE("AppImage") }

enum class GuiCategory(val label: String) {
    BROWSER("Browsers"), OFFICE("Office & reading"), GRAPHICS("Graphics"), MEDIA("Audio & video"),
    DEV("Develop"), TOOLS("Files & tools"), TERMINAL("Terminals"), COMMS("Mail & chat"), NOTES("Notes"),
}

/** How an APPIMAGE-runtime app is delivered. */
enum class Delivery { APPIMAGE, TARBALL_WEB }

/** One installable app. DEB apps carry a Debian [pkg]; APPIMAGE apps a GitHub [repo] + [asset] pattern. */
data class CatalogApp(
    val id: String,
    val name: String,
    val summary: String,
    val category: GuiCategory,
    val runtime: Runtime,
    val exec: String,
    val pkg: String? = null,
    val extraPkgs: List<String> = emptyList(),
    val repo: String? = null,
    val asset: String? = null,
    val delivery: Delivery = Delivery.APPIMAGE,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val heavy: Boolean = false,
    /** A web app (code-server): launch starts a server and opens this port in the companion browser. */
    val webPort: Int? = null,
    /** A user-added AppImage: the exact https link (no release lookup, no published checksum). */
    val directUrl: String? = null,
)

object GuiCatalog {

    /** Chromium / Electron need these in this guest (root, no GPU, small /dev/shm) - the same flags the host's own desktop wrapper uses. */
    val CHROMIUM_FLAGS = listOf("--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage")
    val ELECTRON_FLAGS = CHROMIUM_FLAGS + "--ozone-platform=wayland"

    private fun deb(id: String, name: String, cat: GuiCategory, exec: String, summary: String, pkg: String = id, heavy: Boolean = false, args: List<String> = emptyList(), extra: List<String> = emptyList()) =
        CatalogApp(id, name, summary, cat, Runtime.DEB, exec, pkg = pkg, extraPkgs = extra, heavy = heavy, args = args)

    private fun image(id: String, name: String, cat: GuiCategory, exec: String, summary: String, repo: String, asset: String, args: List<String> = emptyList(), heavy: Boolean = true) =
        CatalogApp(id, name, summary, cat, Runtime.APPIMAGE, exec, repo = repo, asset = asset, args = args, heavy = heavy)

    val all: List<CatalogApp> = listOf(
        // ---- Debian packages (names and binaries checked against Debian 13's arm64 Packages and Contents indexes;
        //      gvim, emacs and freecad are update-alternatives links their packages create)
        deb("firefox", "Firefox", GuiCategory.BROWSER, "firefox-esr", "The Mozilla browser (ESR)", pkg = "firefox-esr", heavy = true),
        deb("chromium", "Chromium", GuiCategory.BROWSER, "chromium", "Chromium browser", heavy = true, args = CHROMIUM_FLAGS),
        deb("falkon", "Falkon", GuiCategory.BROWSER, "falkon", "Light Qt web browser"),
        deb("epiphany", "Web (Epiphany)", GuiCategory.BROWSER, "epiphany-browser", "Simple GNOME browser", pkg = "epiphany-browser"),
        deb("qutebrowser", "qutebrowser", GuiCategory.BROWSER, "qutebrowser", "Keyboard-driven, vim-like browser"),
        deb("libreoffice", "LibreOffice", GuiCategory.OFFICE, "libreoffice", "Writer, Calc, Impress - the full office suite", heavy = true),
        deb("evince", "Evince", GuiCategory.OFFICE, "evince", "PDF and document viewer"),
        deb("zathura", "Zathura", GuiCategory.OFFICE, "zathura", "Minimal keyboard-driven document viewer", extra = listOf("zathura-pdf-poppler")),
        deb("okular", "Okular", GuiCategory.OFFICE, "okular", "KDE universal document viewer"),
        deb("scribus", "Scribus", GuiCategory.OFFICE, "scribus", "Desktop publishing"),
        deb("mousepad", "Mousepad", GuiCategory.DEV, "mousepad", "Simple, fast text editor"),
        deb("geany", "Geany", GuiCategory.DEV, "geany", "Fast, lightweight IDE"),
        deb("gnome-text-editor", "GNOME Text Editor", GuiCategory.DEV, "gnome-text-editor", "Modern GNOME text editor"),
        deb("kate", "Kate", GuiCategory.DEV, "kate", "KDE multi-document editor"),
        deb("gvim", "gVim", GuiCategory.DEV, "gvim", "Vim with a GTK window", pkg = "vim-gtk3"),
        deb("emacs-x11", "Emacs (GUI)", GuiCategory.DEV, "emacs", "Emacs with the graphical front end", pkg = "emacs-gtk", heavy = true),
        deb("gimp", "GIMP", GuiCategory.GRAPHICS, "gimp", "Image editor", heavy = true),
        deb("inkscape", "Inkscape", GuiCategory.GRAPHICS, "inkscape", "Vector drawing (SVG)", heavy = true),
        deb("krita", "Krita", GuiCategory.GRAPHICS, "krita", "Digital painting", heavy = true),
        deb("kolourpaint", "KolourPaint", GuiCategory.GRAPHICS, "kolourpaint", "Easy paint program"),
        deb("drawing", "Drawing", GuiCategory.GRAPHICS, "drawing", "Simple paint program"),
        deb("feh", "feh", GuiCategory.GRAPHICS, "feh", "Fast image viewer"),
        deb("ristretto", "Ristretto", GuiCategory.GRAPHICS, "ristretto", "Xfce image viewer"),
        deb("nomacs", "nomacs", GuiCategory.GRAPHICS, "nomacs", "Image viewer with browsing"),
        deb("gwenview", "Gwenview", GuiCategory.GRAPHICS, "gwenview", "KDE image viewer"),
        deb("darktable", "darktable", GuiCategory.GRAPHICS, "darktable", "Photo workflow and RAW developer", heavy = true),
        deb("rawtherapee", "RawTherapee", GuiCategory.GRAPHICS, "rawtherapee", "RAW image processing", heavy = true),
        deb("simple-scan", "Simple Scan", GuiCategory.GRAPHICS, "simple-scan", "Scan documents"),
        deb("blender", "Blender", GuiCategory.GRAPHICS, "blender", "3D creation suite", heavy = true),
        deb("freecad", "FreeCAD", GuiCategory.GRAPHICS, "freecad", "Parametric 3D modeler", heavy = true),
        deb("vlc", "VLC", GuiCategory.MEDIA, "vlc", "Plays almost any media", heavy = true),
        deb("mpv", "mpv", GuiCategory.MEDIA, "mpv", "Minimal video player", args = listOf("--force-window=yes", "--idle=yes")),
        deb("audacious", "Audacious", GuiCategory.MEDIA, "audacious", "Music player"),
        deb("audacity", "Audacity", GuiCategory.MEDIA, "audacity", "Multitrack audio editor"),
        deb("kdenlive", "Kdenlive", GuiCategory.MEDIA, "kdenlive", "Video editor", heavy = true),
        deb("shotcut", "Shotcut", GuiCategory.MEDIA, "shotcut", "Video editor", heavy = true),
        deb("obs-studio", "OBS Studio", GuiCategory.MEDIA, "obs", "Recording and streaming", heavy = true),
        deb("pcmanfm", "PCManFM", GuiCategory.TOOLS, "pcmanfm", "Lightweight file manager"),
        deb("thunar", "Thunar", GuiCategory.TOOLS, "thunar", "Xfce file manager"),
        deb("xarchiver", "Xarchiver", GuiCategory.TOOLS, "xarchiver", "Archive manager"),
        deb("galculator", "galculator", GuiCategory.TOOLS, "galculator", "Scientific calculator"),
        deb("gnome-calculator", "GNOME Calculator", GuiCategory.TOOLS, "gnome-calculator", "Calculator"),
        deb("keepassxc", "KeePassXC", GuiCategory.TOOLS, "keepassxc", "Password manager", pkg = "keepassxc-full"),
        deb("qbittorrent", "qBittorrent", GuiCategory.TOOLS, "qbittorrent", "BitTorrent client"),
        deb("transmission-gtk", "Transmission", GuiCategory.TOOLS, "transmission-gtk", "BitTorrent client"),
        deb("filezilla", "FileZilla", GuiCategory.TOOLS, "filezilla", "FTP / SFTP client"),
        deb("remmina", "Remmina", GuiCategory.TOOLS, "remmina", "Remote desktop client (RDP, VNC, SSH)"),
        deb("cutecom", "CuteCom", GuiCategory.TOOLS, "cutecom", "Serial terminal"),
        deb("xterm", "xterm", GuiCategory.TERMINAL, "xterm", "The classic X terminal"),
        deb("xfce4-terminal", "Xfce Terminal", GuiCategory.TERMINAL, "xfce4-terminal", "Modern terminal emulator"),
        deb("lxterminal", "LXTerminal", GuiCategory.TERMINAL, "lxterminal", "Light VTE terminal"),
        deb("foot", "foot", GuiCategory.TERMINAL, "foot", "Fast Wayland terminal"),
        deb("alacritty", "Alacritty", GuiCategory.TERMINAL, "alacritty", "GPU terminal emulator"),
        deb("sakura", "Sakura", GuiCategory.TERMINAL, "sakura", "Simple VTE terminal"),
        deb("thunderbird", "Thunderbird", GuiCategory.COMMS, "thunderbird", "Mozilla mail client", heavy = true),
        deb("claws-mail", "Claws Mail", GuiCategory.COMMS, "claws-mail", "Fast GTK mail client"),
        deb("pidgin", "Pidgin", GuiCategory.COMMS, "pidgin", "Multi-protocol chat"),
        deb("dino", "Dino", GuiCategory.COMMS, "dino", "Modern XMPP chat", pkg = "dino-im"),

        // ---- AppImages: prebuilt arm64 builds from the projects' own releases (a SHA-256 from the release is checked)
        image("vscodium", "VSCodium", GuiCategory.DEV, "VSCodium", "VS Code without telemetry (self-contained 'anylinux' build)", "VSCodium/vscodium", "^VSCodium-.*-anylinux-aarch64\\.AppImage$", GuiCatalog.ELECTRON_FLAGS),
        image("gimp-appimage", "GIMP + PhotoGIMP", GuiCategory.GRAPHICS, "GIMP", "GIMP 3 with the PhotoGIMP layout (self-contained build)", "pkgforge-dev/GIMP-and-PhotoGIMP-AppImage", "^GIMP-.*-anylinux-aarch64\\.AppImage$"),
        image("keepassxc-appimage", "KeePassXC (official)", GuiCategory.TOOLS, "KeePassXC", "Password manager, the project's own AppImage", "keepassxreboot/keepassxc", "-aarch64\\.AppImage$", heavy = false),
        image("obsidian", "Obsidian", GuiCategory.NOTES, "Obsidian", "Markdown knowledge base", "obsidianmd/obsidian-releases", "^Obsidian-.*-arm64\\.AppImage$", GuiCatalog.ELECTRON_FLAGS),
        image("logseq", "Logseq", GuiCategory.NOTES, "Logseq", "Outliner and knowledge graph", "logseq/logseq", "^Logseq-linux-arm64-.*\\.AppImage$", GuiCatalog.ELECTRON_FLAGS),
        image("zettlr", "Zettlr", GuiCategory.NOTES, "Zettlr", "Markdown editor for academics", "Zettlr/Zettlr", "-arm64\\.AppImage$", GuiCatalog.ELECTRON_FLAGS),
        image("marktext", "MarkText", GuiCategory.NOTES, "MarkText", "Live-preview Markdown editor", "marktext/marktext", "^marktext-linux-arm64-.*\\.AppImage$", GuiCatalog.ELECTRON_FLAGS),
        image("musescore", "MuseScore Studio", GuiCategory.MEDIA, "MuseScore", "Music notation", "musescore/MuseScore", "-aarch64\\.AppImage$"),
        image("strawberry", "Strawberry", GuiCategory.MEDIA, "strawberry", "Music collection player", "pkgforge-dev/strawberry-AppImage", "anylinux-aarch64\\.AppImage$"),
        image("deadbeef", "DeaDBeeF", GuiCategory.MEDIA, "deadbeef", "Modular music player", "pkgforge-dev/DeaDBeeF-AppImage", "anylinux-aarch64\\.AppImage$", heavy = false),
        image("clapper", "Clapper", GuiCategory.MEDIA, "clapper", "Simple GTK media player", "pkgforge-dev/Clapper-AppImage", "anylinux-aarch64\\.AppImage$"),
        CatalogApp(
            id = "code-server", name = "code-server", summary = "VS Code in your browser - opens in the companion browser",
            category = GuiCategory.DEV, runtime = Runtime.APPIMAGE, exec = "code-server", repo = "coder/code-server",
            asset = "-linux-arm64\\.tar\\.gz$", delivery = Delivery.TARBALL_WEB, heavy = true, webPort = 8080,
            args = listOf("--bind-addr", "127.0.0.1:8080", "--auth", "none", "--disable-telemetry"),
        ),
    )

    fun byId(id: String): CatalogApp? = all.firstOrNull { it.id == id }

    /** Filter by free text (name, summary, id), runtime and category. */
    fun search(query: String, runtime: Runtime?, category: GuiCategory?): List<CatalogApp> {
        val q = query.trim().lowercase()
        return all.filter { a ->
            (runtime == null || a.runtime == runtime) &&
                (category == null || a.category == category) &&
                (q.isEmpty() || a.name.lowercase().contains(q) || a.summary.lowercase().contains(q) || a.id.contains(q))
        }.sortedBy { it.name.lowercase() }
    }
}

// ==================================================================== release assets

object Assets {

    private val JUNK = listOf(".zsync", ".sha1", ".sha256", ".sha512", ".sig", ".asc", ".digest", ".blockmap", ".yml", ".txt")

    /** The asset to download: the first name matching [pattern], never a checksum / signature file; shortest name wins ties. */
    fun pick(names: List<String>, pattern: String): String? {
        val re = runCatching { Regex(pattern) }.getOrNull() ?: return null
        return names.filter { n -> re.containsMatchIn(n) && JUNK.none { n.lowercase().endsWith(it) } }
            .sortedWith(compareBy({ it.length }, { it }))
            .firstOrNull()
    }

    /** `sha256:ABCD...` -> 64 lowercase hex chars, or null when absent / malformed. */
    fun sha256(digest: String?): String? {
        val d = digest?.trim()?.lowercase() ?: return null
        if (!d.startsWith("sha256:")) return null
        return d.removePrefix("sha256:").takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
    }

    /** A file name safe to use under `~/Applications/<id>/`. */
    fun safeFileName(name: String): Boolean =
        Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,120}$").matches(name) && !name.contains("..")

    const val MAX_BYTES = 1_500L * 1024 * 1024
}

// ==================================================================== installed glibc apps

/** A GLIBC app this plugin downloaded: `id|file|version|relativePath` (relative to the Linux home). */
data class InstalledImage(val id: String, val file: String, val version: String, val relPath: String)

object InstalledImages {

    fun encode(list: List<InstalledImage>): String = list.joinToString("") { "${it.id}|${it.file}|${it.version}|${it.relPath}\n" }

    fun decode(text: String?): List<InstalledImage> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 4) return@mapNotNull null
        val ok = p[0].matches(Regex("^[a-z0-9-]{1,40}$")) && Assets.safeFileName(p[1]) && p[2].length in 1..60 &&
            p[3].startsWith("Applications/") && !p[3].contains("..") && p[3].none { it < ' ' || it == '|' }
        if (ok) InstalledImage(p[0], p[1], p[2], p[3]) else null
    }

    fun relPath(id: String, file: String) = "Applications/$id/$file"
}

// ==================================================================== .desktop files

/** What the guest says it can run: one `.desktop` file. */
data class DesktopEntry(
    val fileId: String,
    val name: String,
    val command: List<String>,
    val comment: String,
    val categories: List<String>,
    val icon: String?,
)

object DesktopFile {

    /** A launcher worth showing: an Application that is neither hidden nor a terminal-only tool. */
    fun parse(fileId: String, text: String): DesktopEntry? {
        var inEntry = false
        val kv = HashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEntry = line == "[Desktop Entry]"; continue }
            if (!inEntry || line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            // Localised keys (Name[de]) are skipped: the plain key is always present.
            if (key.contains('[')) continue
            kv.putIfAbsent(key, line.substring(eq + 1).trim())
        }
        if (kv["Type"] != "Application") return null
        if (kv["NoDisplay"].equals("true", true) || kv["Hidden"].equals("true", true) || kv["Terminal"].equals("true", true)) return null
        val name = kv["Name"]?.takeIf { it.isNotBlank() } ?: return null
        val command = ExecLine.tokens(kv["Exec"] ?: return null).takeIf { it.isNotEmpty() } ?: return null
        return DesktopEntry(
            fileId = fileId.removeSuffix(".desktop"),
            name = name.take(60),
            command = command,
            comment = kv["Comment"].orEmpty().take(120),
            categories = kv["Categories"].orEmpty().split(';').filter { it.isNotBlank() },
            icon = kv["Icon"]?.takeIf { it.isNotBlank() },
        )
    }
}

/** The `Exec=` mini-language of the Desktop Entry spec. */
object ExecLine {

    private val FIELD_CODES = setOf('f', 'F', 'u', 'U', 'd', 'D', 'n', 'N', 'i', 'c', 'k', 'v', 'm')

    /** Split into arguments honouring double quotes and backslash escapes; field codes (`%U`...) are dropped, `%%` is `%`. */
    fun tokens(exec: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuote = false
        var started = false
        var i = 0
        fun flush() { if (started) { out += cur.toString(); cur.setLength(0); started = false } }
        while (i < exec.length) {
            val c = exec[i]
            when {
                c == '\\' && i + 1 < exec.length -> { cur.append(exec[i + 1]); started = true; i++ }
                c == '"' -> { inQuote = !inQuote; started = true }
                c == '%' && i + 1 < exec.length -> {
                    val n = exec[i + 1]
                    if (n == '%') { cur.append('%'); started = true }
                    else if (n in FIELD_CODES) { /* dropped; an argument made only of a code vanishes */ }
                    else { cur.append(c); started = true; i-- }
                    i++
                }
                c.isWhitespace() && !inQuote -> flush()
                else -> { cur.append(c); started = true }
            }
            i++
        }
        flush()
        return out.filter { it.isNotEmpty() }
    }
}

// ==================================================================== ELF flavour

enum class Flavour(val label: String) { MUSL("musl"), GLIBC("glibc"), STATIC("static"), SCRIPT("script"), UNKNOWN("?") }

object Elf {

    /** Which libc a program is built for, from its first bytes (the ELF header and PT_INTERP). */
    fun flavour(head: ByteArray): Flavour {
        if (head.size >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) return Flavour.SCRIPT
        if (head.size < 64 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) return Flavour.UNKNOWN
        val is64 = head[4].toInt() == 2
        val little = head[5].toInt() == 1
        if (!is64 || !little) return Flavour.UNKNOWN
        fun u16(o: Int) = (head[o].toInt() and 0xff) or ((head[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = (0..3).fold(0L) { acc, k -> acc or ((head[o + k].toLong() and 0xff) shl (8 * k)) }
        fun u64(o: Int) = (0..7).fold(0L) { acc, k -> acc or ((head[o + k].toLong() and 0xff) shl (8 * k)) }
        val phoff = u64(32)
        val phentsize = u16(54)
        val phnum = u16(56)
        if (phoff <= 0 || phentsize < 56 || phnum <= 0) return Flavour.UNKNOWN
        for (n in 0 until phnum) {
            val base = phoff + n.toLong() * phentsize
            if (base + 56 > head.size) return Flavour.UNKNOWN
            val b = base.toInt()
            if (u32(b) == 3L) { // PT_INTERP
                val off = u64(b + 8).toInt()
                val size = u64(b + 32).toInt()
                if (off < 0 || size <= 0 || off + size > head.size) return Flavour.UNKNOWN
                val interp = String(head, off, size, Charsets.US_ASCII).trimEnd('\u0000')
                return when {
                    interp.contains("ld-musl") -> Flavour.MUSL
                    interp.contains("ld-linux") -> Flavour.GLIBC
                    else -> Flavour.UNKNOWN
                }
            }
        }
        return Flavour.STATIC
    }
}

// ==================================================================== session & launch

object GuiSession {

    const val XDG_RUNTIME_DIR = "/tmp/ps-desktop/xdg"
    const val WAYLAND_DISPLAY = "psgui"
    const val XAUTHORITY = "/tmp/ps-desktop/xauthority"

    /** The environment a GUI app needs to find the host's desktop session (same values as the host's own desktop wrapper). */
    fun env(hasXauthority: Boolean): Map<String, String> = buildMap {
        put("XDG_RUNTIME_DIR", XDG_RUNTIME_DIR)
        put("WAYLAND_DISPLAY", WAYLAND_DISPLAY)
        put("DISPLAY", ":0")
        if (hasXauthority) put("XAUTHORITY", XAUTHORITY)
        put("GSETTINGS_BACKEND", "keyfile")
    }

    /** The session socket's path under the rootfs (host side). */
    const val SOCKET_RELATIVE = "tmp/ps-desktop/xdg/psgui"
    const val XAUTHORITY_RELATIVE = "tmp/ps-desktop/xauthority"
}

/** The one launch recipe: environment, then (when there is no session bus) a private one, then the program. */
object LaunchPlan {

    /** `sh -c '...' job argv...` - constant text; the program and its arguments are positional. */
    private const val DBUS_SCRIPT = "if [ -z \"\$DBUS_SESSION_BUS_ADDRESS\" ] && command -v dbus-run-session >/dev/null 2>&1; then exec dbus-run-session -- \"\$@\"; else exec \"\$@\"; fi"

    /** [program] is the executable (an absolute path or a name on PATH); [extraArgs] come after the catalog's own. */
    fun argv(program: String, args: List<String>, env: Map<String, String>): List<String> {
        val envPart = if (env.isEmpty()) emptyList() else listOf("env") + env.map { "${it.key}=${it.value}" }
        return envPart + listOf("sh", "-c", DBUS_SCRIPT, "launch", program) + args
    }

    fun forCatalog(app: CatalogApp, installedPath: String?, session: Map<String, String>): List<String> {
        val program = installedPath ?: app.exec
        val env = LinkedHashMap<String, String>(session)
        env.putAll(app.env)
        if (app.delivery == Delivery.APPIMAGE && app.runtime == Runtime.APPIMAGE) env["APPIMAGE_EXTRACT_AND_RUN"] = "1"
        return argv(program, app.args, env)
    }

    fun forDesktop(entry: DesktopEntry, session: Map<String, String>): List<String> =
        argv(entry.command.first(), entry.command.drop(1), session)
}

// ==================================================================== custom AppImage

object CustomApp {

    /** An AppImage the user points at. https only, a plain file name, a name that is safe on disk. */
    fun problem(name: String, url: String): String? {
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,39}$").matches(name.trim())) return "Name: letters, digits, space . _ -"
        val u = url.trim()
        if (!u.startsWith("https://")) return "The link must be https://"
        if (u.length > 400 || u.any { it <= ' ' }) return "That is not a plain link"
        val file = fileName(u) ?: return "The link must end in a file name"
        if (!file.endsWith(".AppImage", ignoreCase = true)) return "Only .AppImage files can be added"
        return null
    }

    fun fileName(url: String): String? = url.substringBefore('?').substringBefore('#').substringAfterLast('/').takeIf { Assets.safeFileName(it) }

    /** The catalog id of a custom app: `custom-` + a slug of its name. */
    fun id(name: String): String = "custom-" + name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30)
}

/** User-added AppImages: `id|name|url` per line, re-validated on read. */
object CustomApps {

    fun encode(list: List<CatalogApp>): String = list.joinToString("") { "${it.id}|${it.name}|${it.directUrl}\n" }

    fun decode(text: String?): List<CatalogApp> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 3 || !p[0].startsWith("custom-") || !p[0].matches(Regex("^[a-z0-9-]{8,40}$"))) return@mapNotNull null
        if (CustomApp.problem(p[1], p[2]) != null) return@mapNotNull null
        make(p[0], p[1], p[2])
    }

    fun make(id: String, name: String, url: String) = CatalogApp(
        id = id, name = name.trim(), summary = "Added by you", category = GuiCategory.TOOLS, runtime = Runtime.APPIMAGE,
        exec = name.trim(), directUrl = url.trim(), args = GuiCatalog.ELECTRON_FLAGS.filter { it == "--no-sandbox" },
    )
}

// ==================================================================== help text from logs

object LaunchDiagnosis {

    /** A plain-words reason for a program that exited early or printed a known failure, or null if nothing is recognised. */
    fun diagnose(log: String, exitCode: Int?, glibc: Boolean): String? {
        val t = log.lowercase()
        return when {
            "cannot open display" in t || "failed to connect to wayland" in t || "could not connect to display" in t ||
                "unable to open display" in t || "no protocol specified" in t || "xdg_runtime_dir" in t && "not set" in t ->
                "The desktop session is not reachable. Start the PocketShell desktop session, then launch again."
            "error while loading shared libraries" in t -> {
                val lib = Regex("error while loading shared libraries: ([^:\\s]+)").find(log)?.groupValues?.get(1)
                "A library is missing${lib?.let { " ($it)" } ?: ""}. Install the Debian package that provides it " +
                    "(in the terminal: apt-get install apt-file, apt-file update, apt-file search ${lib ?: "<library>"})."
            }
            "fusermount" in t || "/dev/fuse" in t || "fuse: " in t -> "The AppImage tried to mount itself with FUSE, which this device cannot do. It is started in extract-and-run mode; if this repeats, the file may be an old type-1 AppImage."
            "exec format error" in t -> "That file is not an arm64 program."
            "no usable sandbox" in t || "setuid sandbox" in t || "sandbox" in t && "failed" in t -> "The browser engine refused to start without its sandbox. Chromium-based apps need --no-sandbox here."
            "permission denied" in t -> "The file is not executable or cannot be read."
            "ld-linux" in t && "no such file" in t -> "The C library loader is missing. Repair the Linux environment from Diagnostics."
            exitCode == 139 || "segmentation fault" in t -> "The program crashed (segmentation fault). Try again; if it repeats, a library it needs may not suit this device."
            exitCode == 132 || "illegal instruction" in t -> "The program used a CPU instruction this device does not have."
            else -> null
        }
    }
}

object AptOutput {

    /** Did apt fail because its package index is missing / stale (so `apt-get update` then a retry may help)? */
    fun needsUpdate(output: String): Boolean {
        val t = output.lowercase()
        return "unable to locate package" in t || "has no installation candidate" in t || "404  not found" in t || "404 not found" in t
    }

    /** One line a person can read from a failed apt run. */
    fun reason(stdout: String, stderr: String, error: String?): String {
        val all = (stderr + "\n" + stdout).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val errLine = all.lastOrNull { it.startsWith("E:") }
        return when {
            all.any { it.contains("Could not get lock", true) || it.contains("dpkg frontend lock", true) } ->
                "Another package operation is running. Wait for it, then try again."
            all.any { it.contains("Temporary failure resolving", true) || it.contains("Could not resolve", true) } ->
                "The repository could not be reached. Check the connection."
            errLine != null -> errLine.removePrefix("E:").trim().take(200)
            all.isNotEmpty() -> all.last().take(200)
            else -> error ?: "apt failed without a message"
        }
    }

    private val UPGRADABLE = Regex("^([a-z0-9][a-z0-9.+-]*)/\\S+\\s+(\\S+)\\s+\\S+\\s+\\[upgradable from:")

    /** `apt list --upgradable` lines (`name/suite new arch [upgradable from: old]`) -> package name -> new version. */
    fun upgradable(listOutput: String): Map<String, String> = listOutput.lines().mapNotNull { line ->
        UPGRADABLE.find(line.trim())?.let { it.groupValues[1] to it.groupValues[2] }
    }.toMap()
}

/** The exact apt argv this plugin runs; package names are validated first. */
object AptCmd {

    private val NAME = Regex("^[a-z0-9][a-z0-9.+-]{0,80}$")
    private val GET = listOf("env", "DEBIAN_FRONTEND=noninteractive", "/usr/bin/apt-get", "-q", "-y")

    private fun names(list: List<String>): List<String> {
        require(list.isNotEmpty() && list.all { NAME.matches(it) }) { "bad package name" }
        return list
    }

    fun install(pkgs: List<String>) = GET + "install" + names(pkgs)
    fun upgrade(pkg: String) = GET + listOf("install", "--only-upgrade") + names(listOf(pkg))
    fun remove(pkg: String) = GET + listOf("remove", "--autoremove") + names(listOf(pkg))
    val update = GET + "update"
    val listUpgradable = listOf("env", "DEBIAN_FRONTEND=noninteractive", "/usr/bin/apt", "list", "--upgradable")
}
