package app.pocketshell.widget.packages

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PkgModelTest {

    private val db = """
        C:Q1aaa=
        P:mousepad
        V:0.7.0-r0
        A:aarch64
        I:852000
        T:Simple Text Editor for Xfce4
        U:https://gitlab.xfce.org/apps/mousepad
        o:mousepad
        D:desktop-file-utils so:libgtk-3.so.0 so:libc.musl-aarch64.so.1
        p:cmd:mousepad=0.7.0-r0 so:libmousepad.so.0=0.0.0

        C:Q1bbb=
        P:gtk+3.0
        V:3.24.43-r2
        A:aarch64
        I:9000000
        T:The GTK+ Toolkit (v3)
        D:so:libc.musl-aarch64.so.1
        p:so:libgtk-3.so.0=0.2417.32

        C:Q1ccc=
        P:musl
        V:1.2.5-r10
        A:aarch64
        I:700000
        T:the musl c library
        p:so:libc.musl-aarch64.so.1=1

        C:Q1ddd=
        P:desktop-file-utils
        V:0.28-r0
        A:aarch64
        I:200000
        T:Command line utilities
        D:so:libc.musl-aarch64.so.1

    """.trimIndent()

    @Test fun databaseIsParsed() {
        val pkgs = ApkDb.parse(db)
        assertEquals(listOf("mousepad", "gtk+3.0", "musl", "desktop-file-utils"), pkgs.map { it.name })
        val m = pkgs.first()
        assertEquals("0.7.0-r0", m.version); assertEquals(852000L, m.installedBytes)
        assertEquals(3, m.depends.size); assertEquals("https://gitlab.xfce.org/apps/mousepad", m.url)
        assertTrue(ApkDb.parse("").isEmpty())
        assertTrue(ApkDb.parse("garbage\nmore garbage").isEmpty())
    }

    @Test fun reverseDependenciesResolveVirtualsThroughProvides() {
        val rev = Deps.requiredBy(ApkDb.parse(db))
        assertEquals(listOf("mousepad"), rev["gtk+3.0"])
        assertEquals(listOf("mousepad"), rev["desktop-file-utils"])
        assertEquals(listOf("desktop-file-utils", "gtk+3.0", "mousepad"), rev["musl"])
        assertNull(rev["mousepad"])
    }

    @Test fun worldFileKeepsNamesOnly() {
        assertEquals(setOf("alpine-base", "bash", "gtk+3.0", "foo"), World.parse("alpine-base\nbash\n\n gtk+3.0 \nfoo>=1.2\n"))
    }

    @Test fun filterByScopeAndText() {
        val pkgs = ApkDb.parse(db)
        val world = setOf("mousepad")
        assertEquals(listOf("mousepad"), PkgFilter.apply(pkgs, world, Scope.ADDED, "").map { it.name })
        assertEquals(4, PkgFilter.apply(pkgs, world, Scope.ALL, "").size)
        assertEquals(listOf("gtk+3.0"), PkgFilter.apply(pkgs, world, Scope.ALL, "toolkit").map { it.name })
        assertTrue(PkgFilter.apply(pkgs, world, Scope.ADDED, "gtk").isEmpty())
    }

    // ------------------------------------------------------------------ plans

    private val install = """
        (1/3) Installing libxfce4ui (4.20.1-r1)
        (2/3) Installing xfconf (4.20.0-r2)
        (3/3) Installing mousepad (0.7.0-r0)
        OK: 1870.3 MiB in 431 packages
    """.trimIndent()

    @Test fun installPlan() {
        val p = PlanParse.parse(install)
        assertEquals(3, p.installs.size)
        assertEquals("mousepad", p.installs.last().name)
        assertEquals(1870.3, p.resultMiB!!, 0.001); assertEquals(431, p.resultPackages)
        val delta = p.deltaBytes(1951856262L)!!
        assertTrue("about +9 MiB: $delta", delta in 9_000_000L..9_800_000L)
    }

    @Test fun upgradeAndRemovalPlans() {
        val up = PlanParse.parse("(22/24) Upgrading vim-common (9.2.1091-r0 -> 9.2.1202-r0)\n(24/24) Upgrading vim (9.2.1091-r0 -> 9.2.1202-r0)\nOK: 1861.7 MiB in 419 packages")
        assertEquals(listOf("vim-common", "vim"), up.upgrades.map { it.name })
        assertEquals("9.2.1091-r0", up.upgrades[0].from); assertEquals("9.2.1202-r0", up.upgrades[0].version)
        val del = PlanParse.parse("(1/3) Purging xterm (410-r0)\n(2/3) Purging libxaw (1.0.16-r1)\n(3/3) Purging libxpm (3.5.20-r0)\nOK: 1860.0 MiB in 416 packages")
        assertEquals(3, del.removals.size)
        assertTrue(PlanParse.parse("OK: 1861.4 MiB in 419 packages").isEmpty)
        assertTrue(PlanParse.parse("nonsense").isEmpty)
        assertNull(PlanParse.parse("nonsense").resultMiB)
    }

    @Test fun totalsInOtherUnits() {
        assertEquals(2048.0, PlanParse.parse("OK: 2 GiB in 5 packages").resultMiB!!, 0.001)
        assertEquals(0.5, PlanParse.parse("OK: 512 KiB in 1 package").resultMiB!!, 0.001)
    }

    @Test fun delThatCannotCascadeIsExplained() {
        val out = "World updated, but the following packages are not removed due to:\n  busybox: coreutils glib-dev gnome-keyring\n           firefox util-linux bash npm\n  gtk+3.0: firefox\n\nOK: 1861.4 MiB in 419 packages"
        val nr = NotRemoved.parse(out)
        assertEquals(listOf("coreutils", "glib-dev", "gnome-keyring", "firefox", "util-linux", "bash", "npm"), nr["busybox"])
        assertEquals(listOf("firefox"), nr["gtk+3.0"])
        assertTrue(NotRemoved.parse("(1/1) Purging xterm (410-r0)\nOK: 1 MiB in 1 packages").isEmpty())
    }

    @Test fun apkCommandsAreBuiltFromValidatedNames() {
        assertEquals(listOf("/sbin/apk", "--no-progress", "add", "--simulate", "git", "curl"), ApkCmd.add(listOf("git", "curl"), simulate = true))
        assertEquals(listOf("/sbin/apk", "--no-progress", "add", "-u", "vim"), ApkCmd.add(listOf("vim"), simulate = false, upgrade = true))
        assertEquals(listOf("/sbin/apk", "--no-progress", "del", "xterm"), ApkCmd.del(listOf("xterm"), simulate = false))
        assertEquals(listOf("/sbin/apk", "--no-progress", "upgrade", "--simulate"), ApkCmd.upgradeAll(true))
        assertEquals(listOf("/sbin/apk", "--no-progress", "search", "-v", "-d", "text editor"), ApkCmd.search("text editor"))
        assertTrue(runCatching { ApkCmd.add(listOf("--allow-untrusted"), false) }.isFailure)
        assertTrue(runCatching { ApkCmd.del(listOf("x; reboot"), false) }.isFailure)
        assertTrue(runCatching { ApkCmd.search("a") }.isFailure)
        assertTrue(runCatching { ApkCmd.search("-X --repository http://evil") }.isFailure)
        assertTrue(runCatching { ApkCmd.info("../x") }.isFailure)
    }

    // ----------------------------------------------------------------- guards

    @Test fun protectedPackagesCannotBeRemoved() {
        val bad = PlanParse.parse("(1/2) Purging foo (1-r0)\n(2/2) Purging busybox (1.37-r0)\nOK: 1 MiB in 1 packages")
        assertTrue(RemovalGuard.check(bad)!!.contains("busybox"))
        val fine = PlanParse.parse("(1/1) Purging xterm (410-r0)\nOK: 1 MiB in 1 packages")
        assertNull(RemovalGuard.check(fine))
        assertFalse(RemovalGuard.needsTypedConfirm(fine))
    }

    @Test fun bigRemovalsNeedAStrongerConfirmOrAreRefused() {
        fun plan(n: Int) = Plan((1..n).map { Change(ChangeKind.PURGE, "p$it", "1") }, null, null)
        assertTrue(RemovalGuard.needsTypedConfirm(plan(RemovalGuard.LARGE_REMOVAL)))
        assertNull(RemovalGuard.check(plan(RemovalGuard.LARGE_REMOVAL)))
        assertNotNull(RemovalGuard.check(plan(RemovalGuard.MAX_REMOVAL + 1)))
    }

    @Test fun packageNamesAreValidated() {
        assertEquals(listOf("git", "gtk+3.0", "py3-pip"), PkgNames.parseList("git, gtk+3.0  py3-pip,git"))
        assertNull(PkgNames.parseList(""))
        assertNull(PkgNames.parseList("-s"))
        assertNull(PkgNames.parseList("git; rm -rf /"))
        assertNull(PkgNames.parseList("a/b"))
        assertNull(PkgNames.parseList((1..17).joinToString(" ") { "p$it" }))
        assertTrue(PkgNames.valid("openjdk21-jdk"))
        assertFalse(PkgNames.valid("--allow-untrusted"))
    }

    // ---------------------------------------------------------------- search

    @Test fun searchRanksExactThenPrefix() {
        val out = "faenza-icon-theme-xterm-1.3.1-r6 - faenza xterm\nlxterminal-0.4.0-r2 - VTE terminal\nxterm-410-r0 - X Terminal Emulator\nxterm-doc-410-r0 - docs\nexpect-5.45.4-r5 - tool"
        val hits = SearchParse.parse(out, "xterm")
        assertEquals("xterm", hits[0].name)
        assertEquals("xterm-doc", hits[1].name)
        assertEquals("410-r0", hits[0].version)
        assertEquals("X Terminal Emulator", hits[0].description)
        assertEquals(5, hits.size)
        assertTrue(SearchParse.parse("garbage line", "x").isEmpty())
    }

    @Test fun upgradableParse() {
        val u = UpgradeParse.parse("brotli-libs-1.2.1-r0 aarch64 {brotli} (MIT) [upgradable from: brotli-libs-1.2.0-r1]\nxterm-410-r0 aarch64 {xterm} (X11) [installed]")
        assertEquals(listOf(Upgradable("brotli-libs", "1.2.0-r1", "1.2.1-r0")), u)
    }

    @Test fun errorsAreExplained() {
        assertTrue(ApkErrors.reason("", "ERROR: Unable to lock database: Resource busy", null).contains("Another package operation"))
        assertTrue(ApkErrors.reason("", "ERROR: unable to select packages:\n  nosuch (no such package):\n    required by: world[nosuch]", null).contains("no such package"))
        assertTrue(ApkErrors.reason("", "WARNING: fetch failed\ntemporary error (try again later)", null).contains("could not be reached"))
        assertEquals("exec failed", ApkErrors.reason("", "", "exec failed"))
        assertTrue(ApkErrors.needsUpdate("ERROR: unable to select packages:\n x (no such package)"))
        assertFalse(ApkErrors.needsUpdate("OK: 1 MiB in 1 packages"))
    }

    // ---------------------------------------------------------------- bundles

    @Test fun bundlesAreSane() {
        val ids = Bundles.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (b in Bundles.all) {
            assertTrue(b.id, b.packages.isNotEmpty() && b.packages.size <= 24)
            assertTrue("${b.id} names are valid", b.packages.all { PkgNames.valid(it) })
        }
        val e = Bundles.byId("essentials")!!
        assertEquals(listOf("curl"), Bundles.missing(Bundle("t", "t", "t", listOf("git", "curl")), setOf("git")))
        assertTrue(Bundles.missing(e, e.packages.toSet()).isEmpty())
    }

    // ------------------------------------------------------------------ facts

    @Test fun osReleaseAndFormats() {
        val os = OsRelease.parse("NAME=\"Alpine Linux\"\nVERSION_ID=3.24.2\n# c\nPRETTY_NAME=\"Alpine Linux v3.24\"\n")
        assertEquals("Alpine Linux v3.24", os["PRETTY_NAME"]); assertEquals("3.24.2", os["VERSION_ID"])
        assertEquals("512 B", Fmt.bytes(512)); assertEquals("2 KB", Fmt.bytes(2048)); assertEquals("1.5 MB", Fmt.bytes(1_572_864)); assertEquals("2.00 GB", Fmt.bytes(2_147_483_648))
        assertEquals("+1.0 MB", Fmt.signed(1_048_576)); assertEquals("-1.0 MB", Fmt.signed(-1_048_576))
    }

    @Test fun repositories() {
        val r = Repos.parse("https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n#https://dl-cdn.alpinelinux.org/alpine/v3.24/testing\nhttp://plain.example/x\n\ngarbage")
        assertEquals(3, r.size)
        assertTrue(r[0].enabled && r[0].secure); assertFalse(r[1].enabled); assertFalse(r[2].secure)
        assertEquals("dl-cdn.alpinelinux.org/alpine/v3.24/main", r[0].label)
    }

    // -------------------------------------------- the real database on this device

    @Test fun realInstalledDatabaseParsesWhenPresent() {
        val f = File("/lib/apk/db/installed")
        assumeTrue(f.isFile)
        val pkgs = ApkDb.parse(f.readText())
        assertTrue("many packages: ${pkgs.size}", pkgs.size > 50)
        assertNotNull(pkgs.firstOrNull { it.name == "busybox" })
        assertTrue(pkgs.all { it.version.isNotEmpty() })
        val rev = Deps.requiredBy(pkgs)
        assertTrue("musl is needed by many", (rev["musl"]?.size ?: 0) > 20)
        val world = File("/etc/apk/world")
        if (world.isFile) assertTrue(World.parse(world.readText()).isNotEmpty())
    }
}
