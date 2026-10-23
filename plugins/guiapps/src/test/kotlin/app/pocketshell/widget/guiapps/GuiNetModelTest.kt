package app.pocketshell.widget.guiapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiNetModelTest {

    private val release = """
        {"tag_name":"2.8.0","prerelease":false,"assets":[
          {"name":"KeePassXC-2.8.0-aarch64.AppImage.DIGEST","size":10,"browser_download_url":"https://github.com/x/d"},
          {"name":"KeePassXC-2.8.0-aarch64.AppImage","size":51268104,"digest":"sha256:fec5bb7cd0a5da240018014e6377fc47b711d99e00efba310cec1d210c01c940",
           "browser_download_url":"https://github.com/keepassxreboot/keepassxc/releases/download/2.8.0/KeePassXC-2.8.0-aarch64.AppImage"},
          {"name":"other.dmg","size":1,"browser_download_url":"https://github.com/x/o"}
        ]}
    """.trimIndent()

    @Test fun jsonParsesNestedValues() {
        val v = MiniJson.obj(MiniJson.parse("""{"a":[1,2.5,"x\n\u0041",true,null],"b":{"c":-3}}"""))!!
        assertEquals(listOf<Any?>(1L, 2.5, "x\nA", true, null), v["a"])
        assertEquals(-3L, MiniJson.obj(v["b"])!!["c"])
    }

    @Test fun jsonRefusesMalformedAndHostileInput() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,2", "{'a':1}", "{\"a\":1} trailing", "nul", "\"unterminated", "[\"\\q\"]")) assertNull(bad, MiniJson.parse(bad))
        val deep = "[".repeat(200) + "]".repeat(200)
        assertNull("depth is bounded", MiniJson.parse(deep))
        assertNull("size is bounded", MiniJson.parse("\"" + "a".repeat(MiniJson.MAX_CHARS) + "\""))
    }

    @Test fun releaseAssetIsPickedWithItsDigest() {
        val a = Releases.pickAsset(release, "-aarch64\\.AppImage$")!!
        assertEquals("2.8.0", a.tag)
        assertEquals("KeePassXC-2.8.0-aarch64.AppImage", a.name)
        assertEquals(51268104L, a.size)
        assertEquals("fec5bb7cd0a5da240018014e6377fc47b711d99e00efba310cec1d210c01c940", a.sha256)
        assertTrue(a.url.startsWith("https://"))
    }

    @Test fun releaseWithoutAMatchOrWithUnsafeDataIsRejected() {
        assertNull(Releases.pickAsset(release, "nothing-matches"))
        assertNull(Releases.pickAsset("not json", "x"))
        assertNull(Releases.pickAsset("""{"tag_name":"1","assets":[{"name":"a.AppImage","browser_download_url":"http://insecure/a.AppImage"}]}""", "AppImage"))
        assertNull(Releases.pickAsset("""{"tag_name":"1","assets":[{"name":"../a.AppImage","browser_download_url":"https://x/a"}]}""", "AppImage"))
        assertNull(Releases.pickAsset("""{"assets":[]}""", "x"))
    }

    @Test fun apkInstalledDatabaseIsParsed() {
        val db = "C:Q1abc=\nP:xterm\nV:410-r0\nA:aarch64\nS:1\n\nC:Q1def=\nP:py3-foo-bar\nV:1.2.3-r2\n\n"
        assertEquals(mapOf("xterm" to "410-r0", "py3-foo-bar" to "1.2.3-r2"), ApkDb.installed(db))
        assertTrue(ApkDb.installed("").isEmpty())
    }

    @Test fun apkNamesSplitFromTheRight() {
        assertEquals("py3-foo" to "1.2.3-r0", ApkNames.split("py3-foo-1.2.3-r0"))
        assertEquals("gtk+3.0" to "3.24.43-r2", ApkNames.split("gtk+3.0-3.24.43-r2"))
        assertNull(ApkNames.split("noversion"))
    }

    @Test fun upgradableListIsParsed() {
        val out = "alpine-release-3.24.3-r0 aarch64 {alpine-base} (MIT) [upgradable from: alpine-release-3.24.2-r0]\nxterm-410-r0 aarch64 {xterm} (X11) [installed]\n"
        assertEquals(mapOf("alpine-release" to "3.24.3-r0"), ApkOutput.upgradable(out))
    }

    @Test fun apkFailuresAreExplained() {
        assertTrue(ApkOutput.needsUpdate("ERROR: unable to select packages:\n  foo (no such package):"))
        assertFalse(ApkOutput.needsUpdate("OK: 100 MiB in 400 packages"))
        assertEquals("Another package operation is running. Wait for it, then try again.", ApkOutput.reason("", "ERROR: Unable to lock database: Resource busy\nerror opening database", null))
        assertEquals("ERROR: boom", ApkOutput.reason("", "fine\nERROR: boom", null))
        assertEquals("exec failed", ApkOutput.reason("", "", "exec failed"))
    }

    @Test fun launchFailuresAreExplained() {
        assertTrue(LaunchDiagnosis.diagnose("Error: cannot open display: :0", 1, false)!!.contains("desktop session"))
        assertTrue(LaunchDiagnosis.diagnose("Failed to connect to Wayland display", 1, true)!!.contains("desktop session"))
        assertTrue(LaunchDiagnosis.diagnose("./x: error while loading shared libraries: libfoo.so.1: cannot open", 127, true)!!.contains("pocketshell-doctor"))
        assertTrue(LaunchDiagnosis.diagnose("./x: error while loading shared libraries: libfoo.so.1: cannot open", 127, false)!!.contains("Alpine"))
        assertTrue(LaunchDiagnosis.diagnose("fusermount: failed to open /dev/fuse", 1, true)!!.contains("FUSE"))
        assertTrue(LaunchDiagnosis.diagnose("", 139, true)!!.contains("crashed"))
        assertNull(LaunchDiagnosis.diagnose("all fine", 0, true))
    }

    @Test fun customAppsRoundTripAndAreRevalidated() {
        val a = CustomApps.make(CustomApp.id("My Tool"), "My Tool", "https://example.com/dl/tool.AppImage")
        assertEquals(listOf(a), CustomApps.decode(CustomApps.encode(listOf(a))))
        assertTrue(CustomApps.decode("custom-x|My Tool|http://insecure/a.AppImage\nbad\nnotcustom|n|https://e.com/a.AppImage").isEmpty())
        assertNotNull(a.directUrl)
        assertEquals(Runtime.GLIBC, a.runtime)
    }
}
