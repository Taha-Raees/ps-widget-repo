package app.pocketshell.widget.servers

import java.io.File

/**
 * The probe layer — THREE fact sources, each one the app's own UID may
 * read (device-verified by the host probe's design docs):
 *
 *   1. the HOST's [app.pocketshell.widget.probe.ServerProbe] — verified
 *      endpoints + attribution (connect-verify is kernel fact; the
 *      acceptor-diff attribution is lab-proven). This module only wraps
 *      it with the idle gate the caller's tick needs;
 *   2. /proc files the app may read for its OWN processes —
 *      `stat` (CPU ticks + RSS), `cmdline` (full argv) — the htop-lite
 *      facts for an attributed owner;
 *   3. /proc system files + the rootfs directory — `loadavg`,
 *      `meminfo`, `uptime`, and the rootfs `File`'s space facts — the
 *      system health strip. HONESTLY LABELLED device-wide: guest and app
 *      share one kernel, so load/memory/uptime are the DEVICE's facts
 *      (the strip says "device"), while the disk number is the rootfs
 *      image's usage on the host data partition.
 *
 * No guest exec anywhere in this file — the stop operation is the ONLY
 * exec the application ever makes (ServersOps), mirroring GitOps being
 * the Git plugin's only writer. Parsing is pure + JVM-tested.
 */

// ------------------------------------------------------------- the gate

/**
 * The caller-facing probe: the host's snapshot pipeline plus the cheap
 * gate input (one /proc readdir — the same numeric-dir read the host's
 * internal peekPidSet performs; it is module-internal there, so this
 * wrapper re-implements the read, never the pipeline).
 */
internal class ServersProbeWrapper(
    private val probe: app.pocketshell.widget.probe.ServerProbe = app.pocketshell.widget.probe.ServerProbe(),
    procRoot: String = "/proc",
) {

    private val procDir = File(procRoot)

    /** Cheap gate input: the numeric pid set (one /proc readdir). */
    fun peekPidSet(): Set<Int> =
        procDir.list { _, name -> name.isNotEmpty() && name.all { it in '0'..'9' } }
            ?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: emptySet()

    /** The idle gate, delegated: while the numeric pid set is unchanged AND
     * the last snapshot found no servers, a tick needs no /proc work at
     * all. The wrapper adds the SYSTEM strip's own cadence: the health
     * strip re-reads on the same gate (it is a handful of file reads, but
     * a paused or idle card should not even pay those).
     */
    fun shouldFullScan(currentPidSet: Set<Int>): Boolean = probe.shouldFullScan(currentPidSet)

    /** Whether the last full snapshot could see any own-UID process at all. */
    val lastScanSawProcesses: Boolean get() = probe.lastScanSawProcesses

    /** The host's verified pipeline: discovery → verify → attribute. */
    fun snapshot(): List<app.pocketshell.widget.probe.ServerInfo> = probe.snapshot()
}

// ------------------------------------------------------------ process facts

/**
 * One attributed owner's live facts — the delta-based CPU number needs a
 * previous sample, so [ProcessSample] is the raw capture and
 * [ProcessFacts] is the rendered answer.
 */
internal data class ProcessSample(
    val pid: Int,
    /** utime + stime, in clock ticks (USER_HZ, 100 on Android). */
    val ticksTotal: Long,
    /** Resident set, bytes (stat's rss field × page size). */
    val rssBytes: Long,
    val atMs: Long,
)

/** The rendered per-process facts a detail page shows. */
internal data class ProcessFacts(
    val pid: Int,
    /** CPU% across the last sample interval; null on first sighting. */
    val cpuPercent: Float?,
    val rssBytes: Long,
    /** Full argv, NUL-joined for display; null when unreadable. */
    val argv: String?,
)

internal object ProcParsing {

    /** aarch64 Android page size — stat's rss field is counted in pages. */
    const val PAGE_BYTES = 4096L

    /** Android USER_HZ — /proc/stat ticks are 100/s (bionic PKT.The answer). */
    const val USER_HZ = 100L

    /**
     * /proc/<pid>/stat → the sample. The comm field is parenthesised and
     * MAY contain spaces/parens — everything after the LAST ')' is the
     * fixed-field tail; field N of the line maps to tail[N-3]
     * (state=3 → tail[0]). utime=14 → tail[11], stime=15 → tail[12],
     * rss=24 (pages) → tail[21]. Null when the shape is wrong or the
     * process vanished mid-read.
     */
    fun parseStat(pid: Int, text: String, atMs: Long): ProcessSample? {
        val close = text.lastIndexOf(')')
        if (close < 0) return null
        val tail = text.substring(close + 1).trim().split(' ')
        if (tail.size < 22) return null
        val utime = tail[11].toLongOrNull() ?: return null
        val stime = tail[12].toLongOrNull() ?: return null
        val rssPages = tail[21].toLongOrNull() ?: return null
        return ProcessSample(
            pid = pid,
            ticksTotal = utime + stime,
            rssBytes = rssPages * PAGE_BYTES,
            atMs = atMs,
        )
    }

    /**
     * CPU% between two samples of the same pid — busy ticks over wall
     * ticks, where 100% means one full core (a multi-threaded server can
     * legitimately exceed it, like htop's). Null when the interval is too
     * short to mean anything (<250 ms) or ticks went backwards (pid
     * reuse — the caller drops the stale sample instead). The upper clamp
     * only guards against pathological noise, never rounds down real load.
     */
    fun cpuPercent(prev: ProcessSample, now: ProcessSample): Float? {
        val dtMs = now.atMs - prev.atMs
        if (dtMs < 250) return null
        val dTicks = now.ticksTotal - prev.ticksTotal
        if (dTicks < 0) return null
        val wallTicks = dtMs * USER_HZ / 1000.0
        if (wallTicks <= 0.0) return null
        val pct = (dTicks / wallTicks) * 100.0
        return pct.toFloat().coerceIn(0f, 6400f)
    }

    /** /proc/<pid>/cmdline → "argv0 argv1 …" (NUL-separated); null when unreadable. */
    fun parseArgv(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val parts = String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        return parts.joinToString(" ").ifEmpty { null }
    }
}

// ------------------------------------------------------------- system strip

internal data class LoadAvg(val one: Float, val five: Float, val fifteen: Float)

internal data class MemInfo(val totalKb: Long, val availableKb: Long) {
    val usedKb: Long get() = (totalKb - availableKb).coerceAtLeast(0)
}

/** The system health strip — every field independently nullable: a file a kernel did not provide degrades ALONE. */
internal data class SystemStrip(
    val load: LoadAvg?,
    val mem: MemInfo?,
    /** /proc/uptime seconds. */
    val uptimeSec: Double?,
    /** rootfs usage, 0..1; null when the rootfs dir is unavailable. */
    val diskUsedFrac: Float?,
)

internal object SystemParsing {

    /** "0.52 0.58 0.59 1/467 12345" → the three load averages. */
    fun parseLoadAvg(line: String): LoadAvg? {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val one = parts[0].toFloatOrNull() ?: return null
        val five = parts[1].toFloatOrNull() ?: return null
        val fifteen = parts[2].toFloatOrNull() ?: return null
        return LoadAvg(one, five, fifteen)
    }

    /** meminfo's MemTotal + MemAvailable (kB lines); null when either is missing. */
    fun parseMemInfo(text: String): MemInfo? {
        var total: Long? = null
        var available: Long? = null
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val kb = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull() ?: continue
            when (line.substring(0, colon)) {
                "MemTotal" -> total = kb
                "MemAvailable" -> available = kb
            }
        }
        val t = total ?: return null
        val a = available ?: return null
        return MemInfo(totalKb = t, availableKb = a)
    }

    /** "12345.67 45678.90" → 12345.67 seconds. */
    fun parseUptimeSeconds(line: String): Double? =
        line.trim().substringBefore(' ').toDoubleOrNull()?.takeIf { it >= 0 }
}

/**
 * The strip's reader — injectable paths so the JVM tests exercise the
 * parser seams; on device the defaults read the real /proc and the
 * rootfs directory's host-side space facts.
 */
internal class SystemFactsReader(
    private val procRoot: String = "/proc",
    private val rootfsDir: File? = null,
) {

    fun read(): SystemStrip = SystemStrip(
        load = try {
            SystemParsing.parseLoadAvg(File("$procRoot/loadavg").readText())
        } catch (_: Exception) {
            null
        },
        mem = try {
            SystemParsing.parseMemInfo(File("$procRoot/meminfo").readText())
        } catch (_: Exception) {
            null
        },
        uptimeSec = try {
            SystemParsing.parseUptimeSeconds(File("$procRoot/uptime").readText())
        } catch (_: Exception) {
            null
        },
        diskUsedFrac = readDiskUsedFrac(),
    )

    /** usable/total on the rootfs dir — the guest image's real footprint. */
    private fun readDiskUsedFrac(): Float? {
        val dir = rootfsDir ?: return null
        return try {
            val total = dir.totalSpace
            val usable = dir.usableSpace
            if (total <= 0) null else (1f - usable.toFloat() / total).coerceIn(0f, 1f)
        } catch (_: Exception) {
            null
        }
    }
}
