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

    @Test fun dpkgStatusDatabaseIsParsed() {
        val db = "Package: xterm\nStatus: install ok installed\nPriority: optional\nVersion: 398-1\nDescription: X terminal\n continued\n\n" +
            "Package: vim\nStatus: deinstall ok config-files\nVersion: 2:9.1.1230-2\n\nPackage: python3-foo\nStatus: install ok installed\nVersion: 1.2.3-2\n"
        assertEquals(mapOf("xterm" to "398-1", "python3-foo" to "1.2.3-2"), DpkgDb.installed(db))
        assertTrue(DpkgDb.installed("").isEmpty())
    }

    @Test fun upgradableListIsParsed() {
        val out = "Listing...\ntzdata/stable-updates 2026e-0+deb13u1 all [upgradable from: 2026c-0+deb13u1]\nxterm/stable,now 398-1 arm64 [installed]\n"
        assertEquals(mapOf("tzdata" to "2026e-0+deb13u1"), AptOutput.upgradable(out))
    }

    @Test fun aptFailuresAreExplained() {
        assertTrue(AptOutput.needsUpdate("E: Unable to locate package foo"))
        assertFalse(AptOutput.needsUpdate("0 upgraded, 1 newly installed"))
        assertEquals("Another package operation is running. Wait for it, then try again.", AptOutput.reason("", "E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 42", null))
        assertEquals("boom", AptOutput.reason("", "fine\nE: boom", null))
        assertEquals("exec failed", AptOutput.reason("", "", "exec failed"))
    }

    @Test fun aptCommandsAreBuiltFromValidatedNames() {
        val get = listOf("env", "DEBIAN_FRONTEND=noninteractive", "/usr/bin/apt-get", "-q", "-y")
        assertEquals(get + listOf("install", "zathura", "zathura-pdf-poppler"), AptCmd.install(listOf("zathura", "zathura-pdf-poppler")))
        assertEquals(get + listOf("remove", "--autoremove", "xterm"), AptCmd.remove("xterm"))
        assertEquals(get + listOf("install", "--only-upgrade", "xterm"), AptCmd.upgrade("xterm"))
        assertTrue(runCatching { AptCmd.install(listOf("-o", "x")) }.isFailure)
        assertTrue(runCatching { AptCmd.install(emptyList()) }.isFailure)
    }

    @Test fun launchFailuresAreExplained() {
        assertTrue(LaunchDiagnosis.diagnose("Error: cannot open display: :0", 1, false)!!.contains("desktop session"))
        assertTrue(LaunchDiagnosis.diagnose("Failed to connect to Wayland display", 1, true)!!.contains("desktop session"))
        val missing = LaunchDiagnosis.diagnose("./x: error while loading shared libraries: libfoo.so.1: cannot open", 127, true)!!
        assertTrue(missing.contains("Debian package") && missing.contains("apt-file search libfoo.so.1"))
        assertTrue(LaunchDiagnosis.diagnose("fusermount: failed to open /dev/fuse", 1, true)!!.contains("FUSE"))
        assertTrue(LaunchDiagnosis.diagnose("", 139, true)!!.contains("crashed"))
        assertNull(LaunchDiagnosis.diagnose("all fine", 0, true))
    }

    @Test fun customAppsRoundTripAndAreRevalidated() {
        val a = CustomApps.make(CustomApp.id("My Tool"), "My Tool", "https://example.com/dl/tool.AppImage")
        assertEquals(listOf(a), CustomApps.decode(CustomApps.encode(listOf(a))))
        assertTrue(CustomApps.decode("custom-x|My Tool|http://insecure/a.AppImage\nbad\nnotcustom|n|https://e.com/a.AppImage").isEmpty())
        assertNotNull(a.directUrl)
        assertEquals(Runtime.APPIMAGE, a.runtime)
    }
}
