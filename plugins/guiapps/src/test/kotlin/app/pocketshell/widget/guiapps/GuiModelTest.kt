package app.pocketshell.widget.guiapps

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GuiModelTest {

    // ---------------------------------------------------------------- catalog

    @Test fun catalogIsConsistent() {
        val ids = GuiCatalog.all.map { it.id }
        assertEquals("ids are unique", ids.size, ids.toSet().size)
        for (a in GuiCatalog.all) {
            assertTrue("${a.id} id is a slug", a.id.matches(Regex("^[a-z0-9-]{1,40}$")))
            assertTrue("${a.id} has a name and summary", a.name.isNotBlank() && a.summary.isNotBlank() && a.exec.isNotBlank())
            when (a.runtime) {
                Runtime.MUSL -> assertTrue("${a.id} needs an apk package", !a.apk.isNullOrBlank() && a.repo == null)
                Runtime.GLIBC -> {
                    assertTrue("${a.id} needs a repo", a.repo!!.matches(Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")))
                    assertNotNull("${a.id} asset pattern compiles", runCatching { Regex(a.asset!!) }.getOrNull())
                }
            }
        }
    }

    @Test fun catalogHasBothRuntimes() {
        assertTrue(GuiCatalog.all.count { it.runtime == Runtime.MUSL } >= 30)
        assertTrue(GuiCatalog.all.count { it.runtime == Runtime.GLIBC } >= 8)
        for (c in GuiCategory.entries) assertTrue("${c.label} is not empty", GuiCatalog.all.any { it.category == c })
    }

    @Test fun glibcAppImagesRunWithoutFuse() {
        val plan = LaunchPlan.forCatalog(GuiCatalog.byId("keepassxc-appimage")!!, "/root/Applications/x/K.AppImage", mapOf("DISPLAY" to ":0"))
        assertTrue("APPIMAGE_EXTRACT_AND_RUN=1" in plan)
        val musl = LaunchPlan.forCatalog(GuiCatalog.byId("xterm")!!, null, mapOf("DISPLAY" to ":0"))
        assertFalse("APPIMAGE_EXTRACT_AND_RUN=1" in musl)
    }

    @Test fun searchFiltersByTextRuntimeAndCategory() {
        assertTrue(GuiCatalog.search("firefox", null, null).any { it.id == "firefox" })
        assertTrue(GuiCatalog.search("", Runtime.GLIBC, null).all { it.runtime == Runtime.GLIBC })
        assertTrue(GuiCatalog.search("", null, GuiCategory.TERMINAL).all { it.category == GuiCategory.TERMINAL })
        assertTrue(GuiCatalog.search("zzzz-nothing", null, null).isEmpty())
        assertEquals(GuiCatalog.search("", null, null).map { it.name.lowercase() }, GuiCatalog.search("", null, null).map { it.name.lowercase() }.sorted())
    }

    @Test fun electronAppsGetTheGuestFlags() {
        for (id in listOf("vscodium", "obsidian", "logseq", "zettlr", "marktext")) {
            val args = GuiCatalog.byId(id)!!.args
            assertTrue("$id --no-sandbox", "--no-sandbox" in args)
            assertTrue("$id --disable-gpu", "--disable-gpu" in args)
        }
        assertTrue("--no-sandbox" in GuiCatalog.byId("chromium")!!.args)
    }

    @Test fun codeServerIsALoopbackWebApp() {
        val cs = GuiCatalog.byId("code-server")!!
        assertEquals(Delivery.TARBALL_WEB, cs.delivery)
        assertEquals(8080, cs.webPort)
        assertTrue("127.0.0.1:8080" in cs.args)
        assertFalse("never bound to every interface", cs.args.any { it.startsWith("0.0.0.0") })
    }

    // ----------------------------------------------------------------- assets

    @Test fun assetPickingSkipsChecksumsAndPicksTheBuild() {
        val names = listOf(
            "KeePassXC-2.8.0-aarch64.AppImage.DIGEST", "KeePassXC-2.8.0-aarch64.AppImage", "KeePassXC-2.8.0-aarch64.AppImage.zsync.DIGEST", "KeePassXC-2.8.0-arm64.dmg",
        )
        assertEquals("KeePassXC-2.8.0-aarch64.AppImage", Assets.pick(names, "-aarch64\\.AppImage$"))
        assertNull(Assets.pick(names, "nothing"))
        assertNull(Assets.pick(names, "(unclosed"))
    }

    @Test fun digestParsing() {
        val hex = "a".repeat(64)
        assertEquals(hex, Assets.sha256("sha256:$hex"))
        assertEquals(hex, Assets.sha256("SHA256:${hex.uppercase()}"))
        assertNull(Assets.sha256("sha1:$hex"))
        assertNull(Assets.sha256("sha256:short"))
        assertNull(Assets.sha256(null))
    }

    @Test fun fileNamesCannotEscape() {
        assertTrue(Assets.safeFileName("Obsidian-1.14.5-arm64.AppImage"))
        assertFalse(Assets.safeFileName("../x"))
        assertFalse(Assets.safeFileName("a/b"))
        assertFalse(Assets.safeFileName(".hidden"))
        assertFalse(Assets.safeFileName("a b"))
        assertFalse(Assets.safeFileName("a..b"))
    }

    // ------------------------------------------------------------- installed

    @Test fun installedImagesRoundTripAndRejectTraversal() {
        val a = InstalledImage("obsidian", "Obsidian-1.AppImage", "1.14.5", InstalledImages.relPath("obsidian", "Obsidian-1.AppImage"))
        assertEquals(listOf(a), InstalledImages.decode(InstalledImages.encode(listOf(a))))
        assertTrue(InstalledImages.decode("x|y|z|../../etc/passwd\nobsidian|f.AppImage|1|Applications/../x\nbad").isEmpty())
        assertTrue(InstalledImages.decode("OBS|f.AppImage|1|Applications/obs/f.AppImage").isEmpty())
    }

    // ------------------------------------------------------------ .desktop

    private val xterm = """
        [Desktop Entry]
        Name=XTerm
        Name[de]=XTerm DE
        Comment=Terminal emulator
        Exec=xterm
        Icon=xterm-color
        Type=Application
        Categories=System;TerminalEmulator;
    """.trimIndent()

    @Test fun parsesADesktopFile() {
        val e = DesktopFile.parse("xterm.desktop", xterm)!!
        assertEquals("xterm", e.fileId)
        assertEquals("XTerm", e.name)
        assertEquals(listOf("xterm"), e.command)
        assertEquals(listOf("System", "TerminalEmulator"), e.categories)
    }

    @Test fun hiddenAndTerminalEntriesAreNotLaunchers() {
        assertNull(DesktopFile.parse("a", xterm.replace("Type=Application", "Type=Application\nNoDisplay=true")))
        assertNull(DesktopFile.parse("a", xterm.replace("Type=Application", "Type=Application\nTerminal=true")))
        assertNull(DesktopFile.parse("a", xterm.replace("Type=Application", "Type=Link")))
        assertNull(DesktopFile.parse("a", "[Desktop Entry]\nName=x\nType=Application"))
        assertNull(DesktopFile.parse("a", "garbage"))
    }

    @Test fun actionGroupsDoNotOverrideTheMainEntry() {
        val text = xterm + "\n\n[Desktop Action new]\nName=New\nExec=other --new\n"
        assertEquals(listOf("xterm"), DesktopFile.parse("a", text)!!.command)
    }

    @Test fun execLinesFollowTheSpec() {
        assertEquals(listOf("firefox"), ExecLine.tokens("firefox %u"))
        assertEquals(listOf("env", "MOZ=1", "firefox", "--new-window"), ExecLine.tokens("env MOZ=1 firefox --new-window %U"))
        assertEquals(listOf("/opt/My App/run", "--flag=a b"), ExecLine.tokens("\"/opt/My App/run\" \"--flag=a b\" %F"))
        assertEquals(listOf("prog", "100%"), ExecLine.tokens("prog 100%%"))
        assertEquals(listOf("prog", "a\"b"), ExecLine.tokens("prog a\\\"b"))
        assertEquals(listOf("prog", "--icon", "x"), ExecLine.tokens("prog --icon %i x").let { listOf(it[0], it[1], it.last()) })
        assertTrue(ExecLine.tokens("%U").isEmpty())
    }

    // -------------------------------------------------------------------- ELF

    /** A minimal ELF64 little-endian image with one PT_INTERP naming [interp]. */
    private fun elf(interp: String?): ByteArray {
        val b = ByteArray(512)
        b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte(); b[4] = 2; b[5] = 1
        fun put(o: Int, v: Long, n: Int) { for (k in 0 until n) b[o + k] = (v shr (8 * k) and 0xff).toByte() }
        put(32, 64, 8)   // e_phoff
        put(54, 56, 2)   // e_phentsize
        put(56, 1, 2)    // e_phnum
        val text = (interp ?: "")
        put(64, if (interp != null) 3 else 1, 4) // p_type: PT_INTERP or PT_LOAD
        put(64 + 8, 256, 8)                      // p_offset
        put(64 + 32, (text.length + 1).toLong(), 8) // p_filesz
        text.toByteArray().copyInto(b, 256)
        return b
    }

    @Test fun elfFlavours() {
        assertEquals(Flavour.MUSL, Elf.flavour(elf("/lib/ld-musl-aarch64.so.1")))
        assertEquals(Flavour.GLIBC, Elf.flavour(elf("/lib/ld-linux-aarch64.so.1")))
        assertEquals(Flavour.STATIC, Elf.flavour(elf(null)))
        assertEquals(Flavour.SCRIPT, Elf.flavour("#!/bin/sh\necho".toByteArray()))
        assertEquals(Flavour.UNKNOWN, Elf.flavour(ByteArray(10)))
        assertEquals(Flavour.UNKNOWN, Elf.flavour("not an elf at all, just text, long enough to pass the size check....................".toByteArray()))
    }

    @Test fun truncatedAndHostileHeadersDoNotCrash() {
        val e = elf("/lib/ld-musl-aarch64.so.1")
        assertEquals(Flavour.UNKNOWN, Elf.flavour(e.copyOf(100)))
        val evil = e.copyOf()
        for (k in 0 until 8) evil[64 + 32 + k] = 0xff.toByte() // absurd p_filesz
        assertEquals(Flavour.UNKNOWN, Elf.flavour(evil))
    }

    @Test fun realBinariesOnThisDeviceAreClassifiedWhenPresent() {
        val musl = File("/usr/bin/xterm")
        assumeTrue(musl.isFile)
        assertEquals(Flavour.MUSL, Elf.flavour(musl.inputStream().use { it.readNBytes(65_536) }))
        val loader = File("/lib/ld-linux-aarch64.so.1")
        assumeTrue(loader.isFile)
        val f = Elf.flavour(loader.inputStream().use { it.readNBytes(65_536) })
        assertTrue("the glibc loader is a static ELF or glibc: $f", f == Flavour.STATIC || f == Flavour.GLIBC)
    }

    // ----------------------------------------------------------------- launch

    @Test fun launchPlanKeepsUserDataOutOfTheScript() {
        val argv = LaunchPlan.argv("/root/Applications/x/App Name.AppImage", listOf("--arg", "\$(reboot)"), mapOf("DISPLAY" to ":0"))
        assertEquals(listOf("env", "DISPLAY=:0", "sh", "-c"), argv.take(4))
        val script = argv[4]
        assertFalse(script.contains("AppImage")); assertFalse(script.contains("reboot"))
        assertEquals("launch", argv[5])
        assertEquals("/root/Applications/x/App Name.AppImage", argv[6])
        assertEquals("\$(reboot)", argv.last())
    }

    @Test fun sessionEnvironment() {
        val env = GuiSession.env(hasXauthority = true)
        assertEquals("psgui", env["WAYLAND_DISPLAY"]); assertEquals("/tmp/ps-desktop/xdg", env["XDG_RUNTIME_DIR"])
        assertTrue("XAUTHORITY" in env)
        assertFalse("XAUTHORITY" in GuiSession.env(false))
    }

    @Test fun customAppValidation() {
        assertNull(CustomApp.problem("My Tool", "https://example.com/dl/My-Tool-aarch64.AppImage"))
        assertNull(CustomApp.problem("My Tool", "https://example.com/dl/tool.appimage?token=1"))
        assertNotNull(CustomApp.problem("My Tool", "http://example.com/x.AppImage"))
        assertNotNull(CustomApp.problem("My Tool", "https://example.com/x.zip"))
        assertNotNull(CustomApp.problem("../x", "https://example.com/x.AppImage"))
        assertNotNull(CustomApp.problem("ok", "https://example.com/a b.AppImage"))
        assertEquals("custom-my-tool", CustomApp.id("My Tool"))
    }
}
