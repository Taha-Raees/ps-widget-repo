package app.pocketshell.widget.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * M8.4 — the SYNC/BACKUP application's data model (pure, JVM-tested).
 *
 * A profile is a RECORD of an intent: "copy [source] to [destination]
 * with [backend]". It is NOT a backup — nothing here, or anywhere in
 * this package, claims that user data is safe or protected. v1 previews
 * with dry runs; RUN NOW (M8.4.1) is the one real action, and it is
 * ADDITIVE-ONLY by construction (rsync -a / rclone copy — never a
 * removing mode).
 *
 * RUN HISTORY (M8.4.3): EVERY finished run is recorded — OK and FAILED
 * alike, because failure history is history ([runRecord]); a run that
 * never exited (timeout, destroyed process) records NOTHING — absence of
 * evidence stays absence. All fields default to null, so an old store
 * decodes as "never run" ([SyncStoreCodec.decode] migrates the one shape
 * the M8.4.1 writer could leave: a [lastRunMs] without a result was, by
 * that writer's contract, a successful run).
 *
 * Secrets are structurally absent: a destination may be an ssh remote
 * STRING ("user@host:/path") that references the user's existing guest
 * ~/.ssh setup, but no credential field exists to fill — there is no
 * password, no key path and no key material anywhere in this model.
 */
@Serializable
data class SyncProfile(
    val id: String,
    val backend: SyncBackend,
    val source: String,
    val destination: String,
    val createdAtMs: Long,
    /** Real-run record; null = this card has never run this profile. */
    val lastRunMs: Long? = null,
    /** Real-run outcome summary (facts only); null alongside [lastRunMs]. */
    val lastRunSummary: String? = null,
    /** The exit's verdict: [SyncProfiles.RESULT_OK] or [SyncProfiles.RESULT_FAILED]. */
    val lastResult: String? = null,
    /** The tool's REAL process exit code for [lastRunMs]. */
    val lastExit: Int? = null,
    /**
     * One-line transfer stats parsed from the tool's own output of a
     * successful run ("3 files · 1.2 MB"); null when the tool reported
     * nothing parsable — never invented.
     */
    val lastStats: String? = null,
    /**
     * M8.4.4.1 — path patterns the run SKIPS (regenerable toolchains and
     * caches, not user data). Defaults to empty: old stores and manual
     * profiles copy everything; the quick presets carry the heavy
     * excludes so a first backup takes minutes, not hours.
     */
    val excludes: List<String> = emptyList(),
)

/** The v1 backends — both exist in Alpine 3.24 aarch64 (verified). */
@Serializable
enum class SyncBackend {
    RSYNC,
    RCLONE,
}

/**
 * The pure profile domain: validation, classification, ordering. String in
 * / data out, no I/O (the contract-test pin keeps the exec layer away).
 */
object SyncProfiles {

    /** An "arbitrary reasonable number" of profiles for one Home card. */
    const val MAX_PROFILES = 6

    /** Path length cap — a guest path or remote spec, not a document. */
    const val SPEC_MAX_LENGTH = 512

    /** The only two run results a REAL exit code can produce. */
    const val RESULT_OK = "OK"
    const val RESULT_FAILED = "FAILED"

    /** Time boundaries for [relativeTime]. */
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60L * MINUTE_MS
    private const val DAY_MS = 24L * HOUR_MS
    private const val WEEK_MS = 7L * DAY_MS

    /**
     * Short dates past a week — one shared formatter (US month/day is
     * locale-stable for this card's mono aesthetic), guarded for the one
     * thread that renders.
     */
    private val runDateFormat = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)

    /**
     * The honest input gate. Returns the problem text, or null when the
     * pair is acceptable. Leading "-" is rejected outright: the specs are
     * passed to rsync/rclone as argv elements, and a leading-dash element
     * would be parsed as an OPTION — this is the injection guard, and it
     * also keeps every path displayable verbatim.
     */
    fun validate(source: String, destination: String): String? {
        val src = source.trim()
        val dst = destination.trim()
        return when {
            src.isEmpty() -> "source is required"
            dst.isEmpty() -> "destination is required"
            src.startsWith("-") || dst.startsWith("-") ->
                "paths must not start with \"-\""
            src.length > SPEC_MAX_LENGTH || dst.length > SPEC_MAX_LENGTH ->
                "paths are limited to $SPEC_MAX_LENGTH characters"
            src == dst -> "source and destination are the same path"
            else -> null
        }
    }

    /** Insert-or-replace by id, capped — the repository's whole write logic. */
    fun upsert(profiles: List<SyncProfile>, profile: SyncProfile): List<SyncProfile> {
        val others = profiles.filterNot { it.id == profile.id }
        return (others + profile).takeLast(MAX_PROFILES)
    }

    /**
     * Remote or guest-local? rsync treats "host:path" / "user@host:path"
     * as remote; rclone treats "remote:path" as its configured remote. The
     * heuristic: a colon in the first segment (before any "/") means
     * remote. A local guest path with a colon in its first directory name
     * would be misread — exotic, and the probe/dry-run report the REAL
     * answer anyway (this classification only picks which honesty the
     * overview line shows; [SyncProbe] never trusts it).
     */
    /**
     * M8.4.5 — expand a user-typed path for LISTING/completion: "~" and
     * "~/" resolve to the guest home; anything else passes verbatim.
     * The result always ends with "/" when the input meant a directory
     * (typed or picked), so appending an entry stays natural.
     */
    fun expandGuestPath(text: String, home: String = "/root"): String = when {
        text.isEmpty() -> "/"
        text == "~" -> "$home/"
        text.startsWith("~/") -> home + "/" + text.drop(2)
        text.endsWith("/") -> text
        else -> text
    }

    fun isRemote(spec: String): Boolean {
        val firstSlash = spec.indexOf('/')
        val head = if (firstSlash < 0) spec else spec.substring(0, firstSlash)
        return head.contains(':')
    }

    /**
     * Guest paths for humans: the guest home shows as "~", everything else
     * passes through verbatim — never reinterpreted (the GitApp rule).
     */
    fun displayPath(path: String, guestHome: String = "/root"): String = when {
        path == guestHome -> "~"
        path.startsWith("$guestHome/") -> "~" + path.removePrefix(guestHome)
        else -> path
    }

    /** One-line "src → dst" for the overview row. */
    fun line(profile: SyncProfile): String =
        "${displayPath(profile.source)} → ${displayPath(profile.destination)}"

    // --------------------------------------------------- run status (M8.4.3)

    /**
     * The derived run state — ONE derivation the whole card reads, so the
     * difference between never run / succeeded / failed is rendered the
     * same way everywhere. A null/unknown result decodes as NEVER_RUN
     * (safe default, the old-store shape after migration can't occur).
     */
    fun runOutcome(profile: SyncProfile): RunOutcome = when (profile.lastResult) {
        RESULT_OK -> RunOutcome.OK
        RESULT_FAILED -> RunOutcome.FAILED
        else -> RunOutcome.NEVER_RUN
    }

    /** The overview row's leading marker for [runOutcome]. Running is a
     *  live UI state (the ticking text), never a persisted one. */
    fun statusGlyph(profile: SyncProfile): String = when (runOutcome(profile)) {
        RunOutcome.NEVER_RUN -> "○"
        RunOutcome.OK -> "✓"
        RunOutcome.FAILED -> "✕"
    }

    /**
     * Compact relative time: "now", "5m", "2h", "3d", then a short date.
     * A future stamp (clock skew) clamps to "now" — never a negative.
     */
    fun relativeTime(nowMs: Long, thenMs: Long): String = relativeParts(nowMs, thenMs).first

    /** [relativeTime] composed for humans: "now", "5m ago", "Sep 12". */
    fun agoText(nowMs: Long, thenMs: Long): String = relativeParts(nowMs, thenMs).let { (text, isDate) ->
        if (isDate || text == "now") text else "$text ago"
    }

    /** text + is-calendar-date, the single formatting core. */
    private fun relativeParts(nowMs: Long, thenMs: Long): Pair<String, Boolean> {
        val delta = (nowMs - thenMs).coerceAtLeast(0L)
        return when {
            delta < MINUTE_MS -> "now" to false
            delta < HOUR_MS -> "${delta / MINUTE_MS}m" to false
            delta < DAY_MS -> "${delta / HOUR_MS}h" to false
            delta < WEEK_MS -> "${delta / DAY_MS}d" to false
            else ->
                synchronized(runDateFormat) { runDateFormat.format(java.util.Date(thenMs)) } to true
        }
    }

    /**
     * The detail page's STATUS value: "never run", "2h ago · OK (exit 0)",
     * "3d ago · FAILED (exit 1)" — the run FACTS, one line.
     */
    fun statusLine(profile: SyncProfile, nowMs: Long): String {
        val ms = profile.lastRunMs ?: return "never run"
        return buildString {
            append(agoText(nowMs, ms))
            when (profile.lastResult) {
                RESULT_OK -> append(" · ").append(RESULT_OK)
                RESULT_FAILED -> append(" · ").append(RESULT_FAILED)
            }
            profile.lastExit?.let { append(" (exit ").append(it).append(")") }
        }
    }
}

/** The persisted run state a profile's fields derive to. */
enum class RunOutcome { NEVER_RUN, OK, FAILED }

/**
 * The record one finished run leaves behind — the SINGLE persistence
 * decision for run facts. EVERY finished run is recorded, OK and FAILED
 * alike (failure history is history); a run with NO exit code (timeout,
 * destroyed process, backend absent) records NOTHING — no exit, no
 * claim. Stats parse only from a successful run's real tool output.
 * Returns null when nothing may be recorded.
 */
internal fun runRecord(profile: SyncProfile, result: RunResult, atMs: Long): SyncProfile? {
    val exit = result.exitCode ?: return null
    return profile.copy(
        lastRunMs = atMs,
        lastRunSummary = result.summary,
        lastResult = if (exit == 0) SyncProfiles.RESULT_OK else SyncProfiles.RESULT_FAILED,
        lastExit = exit,
        lastStats = if (exit == 0) SyncRunStats.from(profile.backend, result.output) else null,
    )
}

/**
 * The JSON codec for the profile list — pure String in / List out (the
 * established codec discipline, mirroring [app.pocketshell.widget.HomeAppIdCodec]
 * and the todo store): sanitize, cap, honest degradation.
 */
object SyncStoreCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(profiles: List<SyncProfile>): String =
        json.encodeToString(ListSerializer(SyncProfile.serializer()), profiles.take(SyncProfiles.MAX_PROFILES))

    /**
     * Corrupt/absent → EMPTY (never invented profiles). Well-shaped but
     * dirty records are sanitized: blank source/destination dropped, specs
     * trimmed, leading-dash specs dropped (they are refused at creation —
     * a record that arrives with one cannot be probed honestly), duplicate
     * ids deduped (first wins), capped. Unknown JSON keys are ignored, so
     * an older app reading a newer store degrades instead of crashing.
     *
     * Run-history migration (M8.4.3): a result without a run is not data
     * (cleared to the honest never-run nulls); a [lastRunMs] WITHOUT a
     * result is exactly the shape the M8.4.1 writer left — and that writer
     * persisted only exit-0 runs — so it migrates to a recorded success.
     */
    fun decode(raw: String?): List<SyncProfile> {
        val parsed = raw?.let {
            try {
                json.decodeFromString(ListSerializer(SyncProfile.serializer()), it)
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()
        return parsed
            .map { it.copy(source = it.source.trim(), destination = it.destination.trim()) }
            .filter { it.source.isNotEmpty() && it.destination.isNotEmpty() }
            .filter { !it.source.startsWith("-") && !it.destination.startsWith("-") }
            .map { migrateRunHistory(it) }
            .distinctBy { it.id }
            .take(SyncProfiles.MAX_PROFILES)
    }

    private fun migrateRunHistory(profile: SyncProfile): SyncProfile = when {
        profile.lastRunMs == null ->
            profile.copy(lastResult = null, lastExit = null, lastStats = null)
        profile.lastResult == null ->
            // M8.4.1 writer contract: lastRunMs was persisted on exit 0 only.
            profile.copy(lastResult = SyncProfiles.RESULT_OK, lastExit = 0, lastStats = null)
        else -> profile
    }
}
