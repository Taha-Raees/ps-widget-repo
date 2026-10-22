package app.pocketshell.widget.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Coming back to the card must never re-run a real action: every tick-keyed effect claims its tick once. */
class ReentryTest {

    @Test
    fun `a tick is claimed once and a new tick runs`() {
        val once = RunOnce()
        assertFalse("tick 0 means nothing was launched", once.claim(0))
        assertTrue(once.claim(1))
        assertFalse("re-entering with the same tick must not run again", once.claim(1))
        assertTrue(once.claim(2))
        once.reset()
        assertTrue("after a deliberate reset the counter may start over", once.claim(1))
    }

    @Test
    fun `every manual-action effect in SyncApp claims its tick before it acts`() {
        val f = listOf("src/main/kotlin/app/pocketshell/widget/sync/SyncApp.kt", "plugins/sync/src/main/kotlin/app/pocketshell/widget/sync/SyncApp.kt")
            .map { java.io.File(it) }.firstOrNull { it.isFile }
        assumeTrue("source not found on this runner", f != null)
        val app = f!!.readText()
        listOf("runTick" to "runOnce", "verifyTick" to "verifyOnce", "installTick" to "installOnce", "previewTick" to "previewOnce").forEach { (tick, once) ->
            val effect = app.substring(app.indexOf("LaunchedEffect(state.$tick)"))
            assertTrue("$tick must be claimed", effect.substringBefore("}\n\n").contains("state.$once.claim(state.$tick)"))
        }
    }
}
