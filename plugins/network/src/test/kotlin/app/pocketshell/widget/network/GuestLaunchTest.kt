package app.pocketshell.widget.network

import app.pocketshell.runtime.GuestExecutionProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The launch seam resolves the host's real method instead of the synthetic
 * `$default`, so a host with extra trailing parameters still links.
 */
class GuestLaunchTest {

    private enum class Engine { PSX, PROOT }

    @Test
    fun `a trailing List parameter defaults to empty`() {
        assertEquals(emptyList<Any?>(), GuestLaunch.neutralDefault(List::class.java))
    }

    @Test
    fun `a trailing engine enum defaults to PROOT, wherever it sits`() {
        assertEquals(Engine.PROOT, GuestLaunch.neutralDefault(Engine::class.java))
    }

    @Test
    fun `any other trailing parameter defaults to null`() {
        assertNull(GuestLaunch.neutralDefault(String::class.java))
        assertNull(GuestLaunch.neutralDefault(File::class.java))
    }

    @Test
    fun `the call reaches the host method and surfaces the host's own failure`() {
        // A NoSuchMethodError would mean the descriptor drifted again; the
        // host's precondition check (IllegalArgumentException) means the
        // method was found and invoked.
        try {
            GuestLaunch.spec(
                nativeLibraryDir = "/nonexistent-native-lib-dir",
                rootfsDir = File("/nonexistent-rootfs"),
                hostCwd = File("/nonexistent-cwd"),
                prootTmpDir = File("/nonexistent-tmp"),
                guestCommand = listOf("/bin/true"),
                profile = GuestExecutionProfile.PACKAGE_OPERATION,
            )
            fail("expected the host precondition to reject a missing runtime")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }
}
