package app.pocketshell.widget.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The BACKUP half of Sync — the pure model (JVM-tested, no Android, no IO).
 *
 * WHAT a backup is: the user's Linux data, not the app. The root filesystem
 * is regenerable (the host itself keeps it in noBackupFilesDir) and fragile to
 * move (proot's `.l2s.*` link emulation stores absolute host paths); the
 * explicitly-installed package list is 60-odd lines and replaces it. So a
 * backup is: the Linux HOME, the package list, a small allow-list of app
 * settings, and optional extra trees (/usr/local, /opt) — as ONE portable
 * file, [PsbakHeader] + a compressed (optionally encrypted) tar.
 *
 * Every path in an archive is ROOT-RELATIVE (`root/…`, `usr/local/…`), and
 * the self-description lives under [META_DIR]. Nothing here touches a shell:
 * the guest job reads include/exclude/member LISTS from files (see
 * [BackupJobFiles]), so no user text is ever interpolated into a command.
 */

// ------------------------------------------------------------------ scopes

/** What a backup may contain. [guestPaths] are root-relative tar members. */
@Serializable
enum class BackupScope(val label: String, val guestPaths: List<String>, val defaultOn: Boolean) {
    HOME("Linux home (~)", listOf("root"), true),
    PACKAGES("Package list", emptyList(), true),
    APP_SETTINGS("App settings", emptyList(), true),
    USR_LOCAL("/usr/local", listOf("usr/local"), false),
    OPT("/opt", listOf("opt"), false),
}

/**
 * Regenerable weight a backup should not carry. Patterns are tar `-X` globs
 * (busybox: `*` crosses `/`; an excluded directory is skipped whole —
 * verified on the device). Defaults ON: they make a first backup minutes,
 * not hours.
 */
@Serializable
enum class ExcludePreset(val label: String, val patterns: List<String>, val defaultOn: Boolean) {
    NODE_MODULES("node_modules folders", listOf("*/node_modules"), true),
    BUILD_CACHES(
        "Gradle, npm, Maven and Cargo caches",
        listOf(
            "*/.gradle/caches", "*/.gradle/daemon", "*/.gradle/wrapper/dists",
            "*/.npm/_cacache", "*/.m2/repository", "*/.cargo/registry", "*/.cargo/git",
        ),
        true,
    ),
    HOME_CACHE("~/.cache", listOf("root/.cache"), true),
    PYTHON_ENVS("Python virtualenvs and bytecode", listOf("*/.venv", "*/__pycache__"), true),
}

/**
 * Best-effort patterns for files that hold keys and credentials. They are
 * LEFT OUT unless the backup is encrypted or the user explicitly accepts
 * including them — and the UI says "best-effort", never "guaranteed".
 */
object SensitivePatterns {
    val ALL: List<String> = listOf(
        "root/.ssh", "root/.gnupg", "root/.netrc", "root/.git-credentials", "root/.aws",
        "root/.config/gcloud", "root/.docker/config.json", "root/.config/gh", "root/.npmrc", "root/.pypirc", "root/.kube",
        "*/.env", "*/.env.*", "*.pem", "*.key", "*/credentials.json", "*/.credentials.json", "*/service-account.json",
        "*/auth.json", "*/oauth_creds.json", "root/.claude.json",
    )
}

/** What the user chose. The passphrase is NEVER part of this (it is never persisted). */
@Serializable
data class BackupOptions(
    val scopes: Set<BackupScope> = BackupScope.entries.filter { it.defaultOn }.toSet(),
    val presets: Set<ExcludePreset> = ExcludePreset.entries.filter { it.defaultOn }.toSet(),
    val extraExcludes: List<String> = emptyList(),
    /** Include key/credential files even though the backup is not encrypted (explicit opt-in). */
    val includeSensitive: Boolean = false,
    val keep: Int = DEFAULT_KEEP,
    /** Everything under `~`, or only [homeItems]. */
    val homeMode: HomeMode = HomeMode.EVERYTHING,
    /**
     * Paths RELATIVE TO `~` the user ticked ("Projects", ".claude", ".config/kilo") —
     * only meaningful when [homeMode] is [HomeMode.CHOSEN]. Any size is allowed:
     * the user decides what their data is worth.
     */
    val homeItems: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_KEEP = 3
        const val MAX_KEEP = 20
        const val MAX_EXTRA_EXCLUDES = 20
        const val MAX_HOME_ITEMS = 500
    }
}

/** How much of the Linux home a backup takes. */
@Serializable
enum class HomeMode { EVERYTHING, CHOSEN }

/** A path the user ticked, relative to `~`: validated before it can ever become a tar member. */
object HomePaths {

    /** The problem text, or null when [rel] is an acceptable relative path under `~`. */
    fun validate(rel: String): String? {
        val p = rel.trim()
        return when {
            p.isEmpty() -> "path is empty"
            p.length > 200 -> "path is limited to 200 characters"
            p.startsWith("/") -> "path must be relative to ~"
            p.contains('\n') || p.contains('\r') || p.contains('\u0000') -> "path must be one line"
            p.split('/').any { it.isEmpty() || it == "." || it == ".." } -> "path must not contain empty, . or .. parts"
            else -> null
        }
    }

    /** The archive member for a ticked path. */
    fun member(rel: String): String = "root/${rel.trim().trimEnd('/')}"
}

/** Options → the exact lists the guest job reads. Pure. */
object BackupPlan {

    /** The self-description folder inside every archive (root-relative). */
    const val META_DIR = "tmp/psbak-meta"

    /**
     * Sensitive files stay out unless the backup is encrypted or the user
     * opted in. [encrypted] is a PARAMETER (the passphrase lives only for the
     * run), so this stays a pure function of what will actually be written.
     */
    fun includesSensitive(options: BackupOptions, encrypted: Boolean): Boolean =
        encrypted || options.includeSensitive

    fun includeList(options: BackupOptions): List<String> {
        val trees = options.scopes.flatMap { scope ->
            if (scope == BackupScope.HOME && options.homeMode == HomeMode.CHOSEN) {
                options.homeItems.sorted().map { HomePaths.member(it) }
            } else {
                scope.guestPaths
            }
        }
        return (listOf(META_DIR) + trees).distinct()
    }

    fun excludeList(options: BackupOptions, encrypted: Boolean): List<String> {
        val presets = options.presets.flatMap { it.patterns }
        val sensitive = if (includesSensitive(options, encrypted)) emptyList() else SensitivePatterns.ALL
        return (presets + sensitive + options.extraExcludes).distinct()
    }

    /** A user-typed exclude pattern: the problem text, or null when acceptable. */
    fun validateExclude(pattern: String): String? {
        val p = pattern.trim()
        return when {
            p.isEmpty() -> "pattern is empty"
            p.length > 200 -> "pattern is limited to 200 characters"
            p.contains('\n') || p.contains('\r') || p.contains('\u0000') -> "pattern must be one line"
            p.split('/').contains("..") -> "pattern must not contain .."
            else -> null
        }
    }

    /** At least one tree or meta scope must be chosen — an empty backup is refused, not written. */
    fun validate(options: BackupOptions): String? = when {
        options.scopes.isEmpty() -> "choose something to back up"
        options.keep !in 1..BackupOptions.MAX_KEEP -> "keep between 1 and ${BackupOptions.MAX_KEEP} backups"
        options.extraExcludes.size > BackupOptions.MAX_EXTRA_EXCLUDES -> "too many extra exclude patterns"
        BackupScope.HOME in options.scopes && options.homeMode == HomeMode.CHOSEN && options.homeItems.isEmpty() ->
            "tick at least one folder in your home, or choose everything"
        options.homeItems.size > BackupOptions.MAX_HOME_ITEMS -> "too many folders chosen"
        else -> options.homeItems.firstNotNullOfOrNull { HomePaths.validate(it)?.let { p -> "$it: $p" } }
            ?: options.extraExcludes.firstNotNullOfOrNull { validateExclude(it) }
    }
}

// ------------------------------------------------------------------- codecs

/** Compression of the tar stream. [headerId] is what the file header records. */
@Serializable
enum class BackupCodec(val headerId: Int, val fileExt: String, val label: String) {
    GZIP(0, "gz", "gzip"),
    ZSTD(1, "zst", "zstd"),
    XZ(2, "xz", "xz");

    companion object {
        fun fromHeaderId(id: Int): BackupCodec? = entries.firstOrNull { it.headerId == id }

        /** zstd when the guest has it (fast, better ratio); gzip is always there (busybox). */
        fun preferred(zstdInstalled: Boolean): BackupCodec = if (zstdInstalled) ZSTD else GZIP
    }
}

// ------------------------------------------------------------------- header

/** The 8-byte head of every `.psbak`: "PSBAK", version, flags, codec. */
data class PsbakHeader(val version: Int, val encrypted: Boolean, val codec: BackupCodec) {

    fun encode(): ByteArray = byteArrayOf(
        'P'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(),
        version.toByte(), (if (encrypted) FLAG_ENCRYPTED else 0).toByte(), codec.headerId.toByte(),
    )

    sealed interface Parsed {
        data class Ok(val header: PsbakHeader) : Parsed
        data object NotPsbak : Parsed
        data class Newer(val version: Int) : Parsed
        data object UnknownCodec : Parsed
    }

    companion object {
        const val SIZE = 8
        const val VERSION = 1
        const val FLAG_ENCRYPTED = 1

        fun parse(bytes: ByteArray): Parsed {
            if (bytes.size < SIZE) return Parsed.NotPsbak
            val magic = String(bytes, 0, 5, Charsets.ISO_8859_1)
            if (magic != "PSBAK") return Parsed.NotPsbak
            val version = bytes[5].toInt() and 0xff
            if (version > VERSION) return Parsed.Newer(version)
            val codec = BackupCodec.fromHeaderId(bytes[7].toInt() and 0xff) ?: return Parsed.UnknownCodec
            return Parsed.Ok(PsbakHeader(version, (bytes[6].toInt() and FLAG_ENCRYPTED) != 0, codec))
        }
    }
}

// ----------------------------------------------------------------- manifest

/** The self-description written INTO the archive and mirrored in the sidecar. */
@Serializable
data class BackupManifest(
    val format: Int = PsbakHeader.VERSION,
    val createdAtMs: Long,
    val device: String,
    val pluginVersion: String,
    val codec: BackupCodec,
    val scopes: List<BackupScope>,
    val excludes: List<String>,
    val sensitiveIncluded: Boolean,
    val encrypted: Boolean,
    val packages: Int,
    /** DataStore names whose files are inside (empty when APP_SETTINGS is off). */
    val appSettings: List<String>,
    /** The home folders a backup was limited to; empty = everything under `~`. */
    val homeItems: List<String> = emptyList(),
)

/** The plaintext sidecar `NAME.psbak.json`: the manifest plus what only the finished file knows. */
@Serializable
data class BackupRecord(
    val name: String,
    val sizeBytes: Long,
    /** sha256 of the stored file (hex). */
    val sha256: String,
    val manifest: BackupManifest,
    /** From the guest at backup time (`uname -m`, /etc/alpine-release); null when unreadable. */
    val arch: String? = null,
    val alpine: String? = null,
)

object BackupCodecs {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeManifest(m: BackupManifest): String = json.encodeToString(BackupManifest.serializer(), m)

    fun decodeManifest(raw: String?): BackupManifest? = raw?.let {
        try {
            json.decodeFromString(BackupManifest.serializer(), it)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeRecord(r: BackupRecord): String = json.encodeToString(BackupRecord.serializer(), r)

    /** Corrupt or foreign JSON is NOT a record — never a half-invented one. */
    fun decodeRecord(raw: String?): BackupRecord? = raw?.let {
        try {
            json.decodeFromString(BackupRecord.serializer(), it).takeIf { r -> r.name.isNotBlank() && r.sha256.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

// ------------------------------------------------------------------- naming

object BackupNames {
    const val EXTENSION = "psbak"
    const val SIDECAR_SUFFIX = ".json"

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val NAME_RE = Regex("""^PocketShell-([A-Za-z0-9-]{1,24})-(\d{8}-\d{4})\.psbak$""")

    /** A device label that is safe in a file name: letters, digits, dashes. */
    fun deviceSlug(device: String): String {
        val slug = device.trim().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(24)
        return slug.ifEmpty { "device" }
    }

    fun fileName(device: String, atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "PocketShell-${deviceSlug(device)}-${stamp.format(Instant.ofEpochMilli(atMs).atZone(zone))}.$EXTENSION"

    fun sidecarName(archiveName: String): String = "$archiveName$SIDECAR_SUFFIX"

    /** True for a name THIS app wrote — the only files retention may ever touch. */
    fun isOurs(name: String): Boolean = NAME_RE.matches(name)

    fun isSidecar(name: String): Boolean = name.endsWith(".$EXTENSION$SIDECAR_SUFFIX") && isOurs(name.removeSuffix(SIDECAR_SUFFIX))

    /** The side folder a "review first" restore extracts into. */
    fun sideFolder(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "restore-${stamp.format(Instant.ofEpochMilli(atMs).atZone(zone))}"
}

// ---------------------------------------------------------------- retention

/** One backup found at a location: the archive, its sidecar record (if valid), its size. */
data class StoredBackup(
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val record: BackupRecord?,
)

object Retention {

    /**
     * Which backups to delete so that only the newest [keep] REMAIN FOR THIS
     * DEVICE. SAFE BY CONSTRUCTION: only files this app named (pattern) that
     * carry a valid sidecar are ever candidates, [protect]ed names never are,
     * [keep] is clamped to at least 1, and — crucially for moving phones —
     * only backups made on [device] are counted or removed: a new phone's
     * backups must never push out the old phone's, which are the ones being
     * migrated. A null [device] counts every device (used by tests only).
     */
    fun toPrune(
        all: List<StoredBackup>,
        keep: Int,
        protect: Set<String> = emptySet(),
        device: String? = null,
    ): List<StoredBackup> {
        val k = keep.coerceIn(1, BackupOptions.MAX_KEEP)
        val slug = device?.let { BackupNames.deviceSlug(it) }
        val ours = all.filter {
            BackupNames.isOurs(it.name) && it.record != null &&
                (slug == null || BackupNames.deviceSlug(it.record.manifest.device) == slug)
        }.sortedByDescending { it.record!!.manifest.createdAtMs }
        return ours.drop(k).filter { it.name !in protect }
    }
}

// ------------------------------------------------------------- app settings

/**
 * The small, allow-listed slice of the app's own data a backup may carry.
 * DataStore preference files live at `filesDir/datastore/<name>.preferences_pb`.
 * DELIBERATELY absent: plugin consents/revocations, the installed-widget
 * catalog (SHA pins), the home layout and notification state — security
 * grants and machine-specific state are re-granted on the new device, never
 * imported.
 */
object AppSettingsFiles {
    val NAMES: List<String> = listOf("notes_store", "launchers", "settings", "sync_profiles")

    fun fileName(name: String): String = "$name.preferences_pb"

    /** Stage-relative path of a captured file. */
    fun stagePath(name: String): String = "appdata/${fileName(name)}"
}

// ------------------------------------------------------------------ restore

enum class RestoreMode(val label: String) {
    IN_PLACE("In place — overwrite the same paths, keep everything else"),
    SIDE_FOLDER("Side folder — restore into ~/restore-… to review first"),
}

/** A restore request → the exact members/destination the guest job reads. Pure. */
object RestorePlan {

    /** The word the user must type to start an in-place restore. */
    const val CONFIRM_WORD = "RESTORE"

    fun members(scopes: Set<BackupScope>): List<String> =
        scopes.flatMap { it.guestPaths }.distinct()

    /** Absolute guest destination for the filesystem trees. */
    fun destination(mode: RestoreMode, atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = when (mode) {
        RestoreMode.IN_PLACE -> "/"
        RestoreMode.SIDE_FOLDER -> "/root/${BackupNames.sideFolder(atMs, zone)}"
    }

    fun confirmationOk(typed: String): Boolean = typed.trim() == CONFIRM_WORD
}

/** What differs between the backup's device and this one. Errors block; warnings inform. */
data class Compatibility(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()

    companion object {
        fun check(record: BackupRecord?, manifest: BackupManifest, localArch: String?, localAlpine: String?): Compatibility {
            val errors = ArrayList<String>()
            val warnings = ArrayList<String>()
            if (manifest.format > PsbakHeader.VERSION) {
                errors += "this backup is from a newer PocketShell (format ${manifest.format}) — update the app first"
            }
            val arch = record?.arch
            if (arch != null && localArch != null && arch != localArch) {
                errors += "this backup is for $arch; this device is $localArch"
            }
            val alpine = record?.alpine
            if (alpine != null && localAlpine != null && alpine.substringBeforeLast('.') != localAlpine.substringBeforeLast('.')) {
                warnings += "this backup was made on Alpine $alpine; this device runs $localAlpine — package versions may differ"
            }
            return Compatibility(errors, warnings)
        }
    }
}

// ------------------------------------------------------------------ packages

/** `/etc/apk/world`: one explicitly installed package (optionally constrained) per line. */
object ApkWorld {

    private val ENTRY = Regex("""^[A-Za-z0-9][A-Za-z0-9._+@~<>=!-]*$""")

    /** Valid entries in file order, deduplicated; comments, blanks and odd lines dropped. */
    fun parse(text: String?): List<String> = text.orEmpty().lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && ENTRY.matches(it) }
        .distinct()
        .toList()
}

/**
 * A defense-in-depth audit of an archive's member list BEFORE anything is
 * extracted. busybox tar already strips `..` and refuses a symlink that a later
 * member would write through (verified on the device) — but that is a property
 * of one tar build, and a backup file may come from anywhere. So the app
 * checks the list itself and refuses the archive on any of:
 *
 *   - a member outside the four trees a backup can contain (`tmp/psbak-meta`,
 *     `root`, `usr/local`, `opt`);
 *   - a `..` path component, or an absolute name;
 *   - a symlink followed by a member whose path runs THROUGH it
 *     (the classic write-through-a-link escape).
 *
 * Input is `tar -t -v` output (`perms owner/group size date time name[ -> target]`),
 * consumed as a stream; memory is bounded by [MAX_LINKS].
 */
object ArchiveAudit {

    const val MAX_LINKS = 200_000

    private val ALLOWED_ROOTS = listOf("tmp/psbak-meta", "root", "usr/local", "opt")
    private val LINE = Regex("""^(\S{10})\s+\S+\s+\d+\s+\d{4}-\d\d-\d\d\s+\d\d:\d\d:\d\d\s(.*)$""")

    /** The first problem found, or null when the listing is acceptable. */
    fun check(lines: Sequence<String>): String? {
        val links = HashSet<String>()
        for (raw in lines) {
            if (raw.isBlank()) continue
            val m = LINE.matchEntire(raw) ?: return "the archive listing has a line this version cannot read"
            val type = m.groupValues[1][0]
            val rest = m.groupValues[2]
            val name = (if (type == 'l') rest.substringBefore(" -> ") else rest).trimEnd('/')
            if (name.isEmpty()) continue
            if (name.startsWith("/")) return "the archive holds an absolute path ($name)"
            if (name.split('/').contains("..")) return "the archive holds a path with .. ($name)"
            if (ALLOWED_ROOTS.none { name == it || name.startsWith("$it/") }) return "the archive holds a path outside a backup's folders ($name)"
            // A member below an earlier symlink would be written THROUGH it.
            var cut = name.indexOf('/')
            while (cut >= 0) {
                if (name.substring(0, cut) in links) return "the archive writes through a link ($name)"
                cut = name.indexOf('/', cut + 1)
            }
            if (type == 'l') {
                if (links.size >= MAX_LINKS) return "the archive holds too many links to check"
                links += name
            }
        }
        return null
    }
}
