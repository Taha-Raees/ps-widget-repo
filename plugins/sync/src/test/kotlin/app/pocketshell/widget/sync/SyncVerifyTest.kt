package app.pocketshell.widget.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M8.4.3 — the VERIFY interpretation: the dry run's preview re-read as a
 * point-in-time destination check. Pinned here:
 *
 *   - "up to date" comes ONLY from a clean zero-change dry run;
 *   - NEW/CHANGED/ATTR/HARDLINK entries are what the ADDITIVE-ONLY run
 *     (rsync -a / rclone copy) would transfer — the pending count;
 *   - DELETED entries transfer nothing (no removing mode is ever run —
 *     the additive contract), so they cannot make a destination stale;
 *   - ERROR entries mean the check could not see everything: no claim
 *     either way, the verify is inconclusive.
 */
class SyncVerifyTest {

    private fun preview(vararg kinds: PreviewKind): SyncPreview = SyncPreview(
        backend = SyncBackend.RSYNC,
        entries = kinds.map { PreviewEntry(it, "path") },
        unchangedCount = 0,
        notices = emptyList(),
        summary = emptyList(),
        dryRunConfirmed = true,
    )

    @Test
    fun `a clean zero-change dry run is the only up-to-date claim`() {
        assertEquals(VerifyVerdict.UpToDate(42L), interpretVerify(preview(), verifiedAtMs = 42L))
    }

    @Test
    fun `new and changed entries count as what would transfer`() {
        val verdict = interpretVerify(preview(PreviewKind.NEW, PreviewKind.CHANGED, PreviewKind.NEW), 1L)
        assertEquals(VerifyVerdict.Pending(3), verdict)
    }

    @Test
    fun `attribute and hardlink entries would transfer under the additive run`() {
        val verdict = interpretVerify(preview(PreviewKind.ATTR, PreviewKind.HARDLINK), 1L)
        assertEquals(VerifyVerdict.Pending(2), verdict)
    }

    @Test
    fun `deleted entries never transfer - no removing mode is ever run`() {
        val verdict = interpretVerify(preview(PreviewKind.DELETED, PreviewKind.DELETED, PreviewKind.DELETED), 1L)
        assertEquals(VerifyVerdict.UpToDate(1L), verdict)
    }

    @Test
    fun `errors make the verify inconclusive - no claim either way`() {
        assertEquals(VerifyVerdict.Inconclusive, interpretVerify(preview(PreviewKind.ERROR), 1L))
        assertEquals(
            VerifyVerdict.Inconclusive,
            interpretVerify(preview(PreviewKind.NEW, PreviewKind.ERROR), 1L),
        )
    }
}
