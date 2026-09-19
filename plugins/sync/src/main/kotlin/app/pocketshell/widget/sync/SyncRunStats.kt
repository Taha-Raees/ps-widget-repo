package app.pocketshell.widget.sync

import java.util.Locale

/**
 * M8.4.3 — the REAL-run stats parsers: after a successful RUN NOW, the
 * tool's own captured output ([RunResult.output]) is mined for the one
 * compact transfer-fact line a profile's [SyncProfile.lastStats] carries.
 *
 *   rsync  — `--info=stats1` prints labeled totals; the two that describe
 *            what actually moved are "Number of regular files
 *            transferred: N" and "Total transferred file size: X bytes"
 *            (rsync(1), --info=stats1). Numbers arrive with grouping
 *            commas ("1,234,567").
 *   rclone — `copy` prints its end-of-run stats block (stderr) whose
 *            "Transferred:" line carries "<moved> / <considered>, %, rate,
 *            ETA"; the moved/considered sizes are the honest extract.
 *
 * Nothing is invented: a missing line leaves that part out, a missing
 * everything leaves [lastStats] null — a run without parsable stats is
 * recorded as the exit fact it is, undecorated. Pure String in / String
 * out (JVM-tested with real output shapes).
 */
internal object SyncRunStats {

    /** One entry point — the backend picks its own output grammar. */
    fun from(backend: SyncBackend, output: String): String? = when (backend) {
        SyncBackend.RSYNC -> fromRsync(output)
        SyncBackend.RCLONE -> fromRclone(output)
    }

    private val RSYNC_FILES =
        Regex("""Number of regular files transferred:\s*([\d,]+)""")
    private val RSYNC_BYTES =
        Regex("""Total transferred file size:\s*([\d,]+) bytes""")

    /** "3 files · 1.2 MB" — each part only when the tool reported it. */
    fun fromRsync(output: String): String? {
        val files = RSYNC_FILES.find(output)?.groupValues?.get(1)
            ?.replace(",", "")?.toLongOrNull()
        val bytes = RSYNC_BYTES.find(output)?.groupValues?.get(1)
            ?.replace(",", "")?.toLongOrNull()
        if (files == null && bytes == null) return null
        return listOfNotNull(
            files?.let { "$it files" },
            bytes?.let { formatBytes(it) },
        ).joinToString(" · ")
    }

    /** The first "Transferred:" line — rclone lists bytes first, counts second. */
    fun fromRclone(output: String): String? {
        val line = output.lineSequence()
            .firstOrNull { it.trimStart().startsWith("Transferred:") }
            ?: return null
        val rest = line.substringAfter("Transferred:").trim()
        if (rest.isEmpty()) return null
        // "1.234 MiB / 2.500 GiB, 0%, 0 B/s, ETA -" → the sizes; anything
        // else (e.g. "0 B") passes through, capped — verbatim enough.
        val compact = rest.substringBefore(",").replace(Regex("\\s+"), " ").trim()
        if (compact.isEmpty()) return null
        return compact.take(40)
    }

    /**
     * The package's one byte formatter (binary units, matching the tools'
     * own KiB/MiB reporting): "512 B", "1 KB", "1.5 KB", "1.2 MB", "5 GB".
     */
    fun formatBytes(bytes: Long): String = when {
        bytes < KIB -> "$bytes B"
        bytes < MIB -> scaled(bytes, KIB) + " KB"
        bytes < GIB -> scaled(bytes, MIB) + " MB"
        else -> scaled(bytes, GIB) + " GB"
    }

    private const val KIB = 1_024L
    private const val MIB = 1_024L * KIB
    private const val GIB = 1_024L * MIB

    /** One decimal below 100, none at or above; ".0" trimmed. US locale —
     *  the decimal separator must not follow the device's. */
    private fun scaled(bytes: Long, unit: Long): String {
        val value = bytes.toDouble() / unit
        val text = if (value >= 100.0) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return text.removeSuffix(".0")
    }
}
