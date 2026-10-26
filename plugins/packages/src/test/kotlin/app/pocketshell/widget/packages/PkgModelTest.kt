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

    // Shaped like the real /var/lib/dpkg/status of the Debian 13 guest (fields trimmed).
    private val db = """
        Package: mousepad
        Status: install ok installed
        Priority: optional
        Section: editors
        Installed-Size: 832
        Maintainer: Debian Xfce Maintainers
        Architecture: arm64
        Version: 0.6.3-1
        Depends: desktop-file-utils, libgtk-3-0t64 (>= 3.22), libc6 (>= 2.34)
        Description: simple Xfce oriented text editor
         Mousepad is a text editor for Xfce based on Leafpad.
        Homepage: https://docs.xfce.org/apps/mousepad/start

        Package: libgtk-3-0t64
        Status: install ok installed
        Priority: optional
        Installed-Size: 8901
        Architecture: arm64
        Source: gtk+3.0
        Version: 3.24.49-3
        Provides: libgtk-3-0 (= 3.24.49-3)
        Depends: libc6 (>= 2.34) | libc6.1
        Description: GTK graphical user interface library

        Package: libc6
        Status: install ok installed
        Priority: optional
        Installed-Size: 12000
        Architecture: arm64
        Version: 2.41-12+deb13u4
        Description: GNU C Library: Shared libraries

        Package: desktop-file-utils
        Status: install ok installed
        Priority: optional
        Installed-Size: 200
        Architecture: arm64
        Version: 0.28-1
        Pre-Depends: libc6 (>= 2.34)
        Description: Utilities for .desktop files

        Package: coreutils
        Essential: yes
        Status: install ok installed
        Priority: required
        Installed-Size: 18000
        Architecture: arm64
        Version: 9.7-3
        Description: GNU core utilities

        Package: vim
        Status: deinstall ok config-files
        Priority: optional
        Architecture: arm64
        Version: 2:9.1.1230-2
        Description: removed, only its configuration is left

    """.trimIndent()

    @Test fun databaseIsParsed() {
        val pkgs = DpkgStatus.parse(db)
        assertEquals(listOf("mousepad", "libgtk-3-0t64", "libc6", "desktop-file-utils", "coreutils"), pkgs.map { it.name })
        val m = pkgs.first()
        assertEquals("0.6.3-1", m.version); assertEquals(832L * 1024, m.installedBytes)
        assertEquals(listOf("desktop-file-utils", "libgtk-3-0t64", "libc6"), m.depends)
        assertEquals("https://docs.xfce.org/apps/mousepad/start", m.url)
        assertEquals("simple Xfce oriented text editor", m.description)
        assertEquals("gtk+3.0", pkgs[1].origin)
        assertTrue(pkgs.last().essential); assertFalse(m.essential)
        assertTrue(DpkgStatus.parse("").isEmpty())
        assertTrue(DpkgStatus.parse("garbage\nmore garbage").isEmpty())
    }

    @Test fun dependencyTokensDropVersionsArchAndKeepAlternatives() {
        assertEquals(listOf("a", "b", "c", "d"), DpkgStatus.depTokens("a (>= 1), b | c, d:any"))
        assertTrue(DpkgStatus.depTokens(null).isEmpty())
    }

    @Test fun reverseDependenciesResolveVirtualsThroughProvides() {
        val rev = Deps.requiredBy(DpkgStatus.parse(db) + Pkg("old-app", "1", "arm64", 0, "", null, null, listOf("libgtk-3-0"), emptyList()))
        assertEquals(listOf("mousepad", "old-app"), rev["libgtk-3-0t64"])
        assertEquals(listOf("mousepad"), rev["desktop-file-utils"])
        assertEquals(listOf("desktop-file-utils", "libgtk-3-0t64", "mousepad"), rev["libc6"])
        assertNull(rev["mousepad"])
    }

    @Test fun manualSetIsInstalledMinusAutomaticAndBase() {
        val auto = ManualMarks.parseAuto("Package: libc6\nArchitecture: arm64\nAuto-Installed: 1\n\nPackage: desktop-file-utils\nArchitecture: arm64\nAuto-Installed: 1\n\nPackage: mousepad\nArchitecture: arm64\nAuto-Installed: 0\n")
        assertEquals(setOf("libc6", "desktop-file-utils"), auto)
        assertEquals(setOf("mousepad", "libgtk-3-0t64"), ManualMarks.added(DpkgStatus.parse(db), auto))
        assertTrue(ManualMarks.parseAuto("").isEmpty())
    }

    @Test fun filterByScopeAndText() {
        val pkgs = DpkgStatus.parse(db)
        val world = setOf("mousepad")
        assertEquals(listOf("mousepad"), PkgFilter.apply(pkgs, world, Scope.ADDED, "").map { it.name })
        assertEquals(5, PkgFilter.apply(pkgs, world, Scope.ALL, "").size)
        assertEquals(listOf("libgtk-3-0t64"), PkgFilter.apply(pkgs, world, Scope.ALL, "graphical").map { it.name })
        assertTrue(PkgFilter.apply(pkgs, world, Scope.ADDED, "gtk").isEmpty())
    }

    // ------------------------------------------------------------------ plans

    // Real `apt-get -q -y install --simulate tmux mc` output from the Debian 13 guest (trimmed).
    private val install = """
        0 upgraded, 9 newly installed, 0 to remove and 1 not upgraded.
        Inst sensible-utils (0.0.25 Debian:13.7/stable [all])
        Inst libevent-core-2.1-7t64 (2.1.13-stable-1~deb13u1 Debian-Security:13/stable-security [arm64])
        Inst tmux (3.5a-3 Debian:13.7/stable [arm64])
        Conf sensible-utils (0.0.25 Debian:13.7/stable [all])
        Conf tmux (3.5a-3 Debian:13.7/stable [arm64])
    """.trimIndent()

    @Test fun installPlan() {
        val p = PlanParse.parse(install)
        assertEquals(listOf("sensible-utils", "libevent-core-2.1-7t64", "tmux"), p.installs.map { it.name })
        assertEquals("2.1.13-stable-1~deb13u1", p.installs[1].version)
        // apt prints no size line in simulation mode: the change is honestly unknown
        assertNull(p.deltaBytes(1_000_000L))
    }

    @Test fun upgradeReinstallAndRemovalPlans() {
        val up = PlanParse.parse("Inst vim-common [2:9.1.1230-2] (2:9.1.1230-3 Debian:13.8/stable [all])\nInst vim [2:9.1.1230-2] (2:9.1.1230-3 Debian:13.8/stable [arm64])")
        assertEquals(listOf("vim-common", "vim"), up.upgrades.map { it.name })
        assertEquals("2:9.1.1230-2", up.upgrades[0].from); assertEquals("2:9.1.1230-3", up.upgrades[0].version)
        val re = PlanParse.parse("Inst nano [8.4-1+deb13u1] (8.4-1+deb13u1 Debian:13.7/stable [arm64])")
        assertEquals(ChangeKind.REINSTALL, re.changes.single().kind)
        // real: a removal line can name the packages it breaks in a second bracket
        val del = PlanParse.parse("Remv python3-yaml [6.0.2-1+b2]\nRemv python3-jaraco.text [4.0.0-1] [python3-setuptools:arm64 ]\nPurg xterm [398-1]")
        assertEquals(listOf("python3-yaml", "python3-jaraco.text", "xterm"), del.removals.map { it.name })
        assertTrue(PlanParse.parse("0 upgraded, 0 newly installed, 0 to remove and 1 not upgraded.").isEmpty)
        assertTrue(PlanParse.parse("nonsense").isEmpty)
    }

    @Test fun sizeLineWhenAptPrintsOne() {
        assertEquals(12_300_000L, PlanParse.parse("After this operation, 12.3 MB of additional disk space will be used.").deltaBytes(0))
        assertEquals(-1_024_000L, PlanParse.parse("After this operation, 1,024 kB disk space will be freed.").deltaBytes(0))
    }

    @Test fun aptRemovalLeavesNothingBehindToExplain() {
        assertTrue(NotRemoved.parse("Remv xterm [398-1]").isEmpty())
    }

    @Test fun aptCommandsAreBuiltFromValidatedNames() {
        val env = listOf("env", "DEBIAN_FRONTEND=noninteractive")
        val get = env + listOf("/usr/bin/apt-get", "-q", "-y")
        assertEquals(get + listOf("install", "--simulate", "git", "curl"), AptCmd.add(listOf("git", "curl"), simulate = true))
        assertEquals(get + listOf("install", "--only-upgrade", "vim"), AptCmd.add(listOf("vim"), simulate = false, upgrade = true))
        assertEquals(get + listOf("remove", "--autoremove", "xterm"), AptCmd.del(listOf("xterm"), simulate = false))
        assertEquals(get + listOf("upgrade", "--with-new-pkgs", "--simulate"), AptCmd.upgradeAll(true))
        assertEquals(env + listOf("/usr/bin/apt-cache", "search", "--", "text editor"), AptCmd.search("text editor"))
        assertEquals(env + listOf("/usr/bin/apt", "list", "--upgradable"), AptCmd.listUpgradable)
        assertTrue(runCatching { AptCmd.add(listOf("--allow-unauthenticated"), false) }.isFailure)
        assertTrue(runCatching { AptCmd.del(listOf("x; reboot"), false) }.isFailure)
        assertTrue(runCatching { AptCmd.search("a") }.isFailure)
        assertTrue(runCatching { AptCmd.search("-o Dir=/evil") }.isFailure)
        assertTrue(runCatching { AptCmd.info("../x") }.isFailure)
    }

    // ----------------------------------------------------------------- guards

    @Test fun protectedPackagesCannotBeRemoved() {
        val bad = PlanParse.parse("Remv foo [1]\nRemv dpkg [1.22.21]")
        assertTrue(RemovalGuard.check(bad)!!.contains("dpkg"))
        val fine = PlanParse.parse("Remv xterm [398-1]")
        assertNull(RemovalGuard.check(fine))
        assertFalse(RemovalGuard.needsTypedConfirm(fine))
        // anything the database marks Essential / required is protected too
        assertTrue(RemovalGuard.check(PlanParse.parse("Remv coreutils [9.7-3]"), setOf("coreutils"))!!.contains("coreutils"))
    }

    @Test fun bigRemovalsNeedAStrongerConfirmOrAreRefused() {
        fun plan(n: Int) = Plan((1..n).map { Change(ChangeKind.PURGE, "p$it", "1") }, null, null)
        assertTrue(RemovalGuard.needsTypedConfirm(plan(RemovalGuard.LARGE_REMOVAL)))
        assertNull(RemovalGuard.check(plan(RemovalGuard.LARGE_REMOVAL)))
        assertNotNull(RemovalGuard.check(plan(RemovalGuard.MAX_REMOVAL + 1)))
    }

    @Test fun packageNamesAreValidated() {
        assertEquals(listOf("git", "libgtk-3-0t64", "python3-pip", "g++"), PkgNames.parseList("git, libgtk-3-0t64  python3-pip,git g++"))
        assertNull(PkgNames.parseList(""))
        assertNull(PkgNames.parseList("-s"))
        assertNull(PkgNames.parseList("git; rm -rf /"))
        assertNull(PkgNames.parseList("a/b"))
        assertNull(PkgNames.parseList((1..17).joinToString(" ") { "p$it" }))
        assertTrue(PkgNames.valid("openjdk-21-jdk-headless"))
        assertFalse(PkgNames.valid("--allow-unauthenticated"))
        assertFalse("Debian names are lowercase", PkgNames.valid("Nano"))
    }

    // ---------------------------------------------------------------- search

    @Test fun searchRanksExactThenPrefix() {
        // real `apt-cache search -- xterm` lines (plus the package itself)
        val out = "libconvert-color-xterm-perl - module for indexed colors used by XTerm\nlxterminal - LXDE terminal emulator\nxterm - X terminal emulator\nxterm-doc - docs\nlibjs-xterm - terminal front-end component for the browser - browser library"
        val hits = SearchParse.parse(out, "xterm")
        assertEquals("xterm", hits[0].name)
        assertEquals("xterm-doc", hits[1].name)
        assertEquals("", hits[0].version)
        assertEquals("X terminal emulator", hits[0].description)
        assertEquals("terminal front-end component for the browser - browser library", hits.first { it.name == "libjs-xterm" }.description)
        assertEquals(5, hits.size)
        assertTrue(SearchParse.parse("garbage line", "x").isEmpty())
    }

    @Test fun upgradableParse() {
        val u = UpgradeParse.parse("Listing...\ntzdata/stable-updates 2026e-0+deb13u1 all [upgradable from: 2026c-0+deb13u1]\nnano/stable,now 8.4-1 arm64 [installed]")
        assertEquals(listOf(Upgradable("tzdata", "2026c-0+deb13u1", "2026e-0+deb13u1")), u)
    }

    @Test fun errorsAreExplained() {
        assertTrue(AptErrors.reason("", "E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 123 (apt-get)", null).contains("Another package operation"))
        assertTrue(AptErrors.reason("", "E: Unable to locate package nosuch", null).contains("Unable to locate package nosuch"))
        assertTrue(AptErrors.reason("", "W: Failed to fetch http://deb.debian.org/debian/dists/trixie/InRelease  Temporary failure resolving 'deb.debian.org'", null).contains("could not be reached"))
        assertEquals("Sub-process /usr/bin/dpkg returned an error code (1)", AptErrors.reason("", "dpkg: error\nE: Sub-process /usr/bin/dpkg returned an error code (1)", null))
        assertEquals("exec failed", AptErrors.reason("", "", "exec failed"))
        assertTrue(AptErrors.needsUpdate("E: Unable to locate package x"))
        assertTrue(AptErrors.needsUpdate("E: Failed to fetch http://deb.debian.org/x.deb  404  Not Found [IP: 1.2.3.4 80]"))
        assertFalse(AptErrors.needsUpdate("0 upgraded, 1 newly installed"))
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
        val os = OsRelease.parse("PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nNAME=\"Debian GNU/Linux\"\nVERSION_ID=\"13\"\n# c\n")
        assertEquals("Debian GNU/Linux 13 (trixie)", os["PRETTY_NAME"]); assertEquals("13", os["VERSION_ID"])
        assertEquals("512 B", Fmt.bytes(512)); assertEquals("2 KB", Fmt.bytes(2048)); assertEquals("1.5 MB", Fmt.bytes(1_572_864)); assertEquals("2.00 GB", Fmt.bytes(2_147_483_648))
        assertEquals("+1.0 MB", Fmt.signed(1_048_576)); assertEquals("-1.0 MB", Fmt.signed(-1_048_576))
    }

    @Test fun repositories() {
        // the image's real debian.sources, plus a disabled stanza and a one-line list
        val sources = "Types: deb\nURIs: http://deb.debian.org/debian\nSuites: trixie trixie-updates\nComponents: main\nSigned-By: /usr/share/keyrings/debian-archive-keyring.pgp\n\nTypes: deb\nURIs: http://deb.debian.org/debian-security\nSuites: trixie-security\nComponents: main\n\nTypes: deb\nEnabled: no\nURIs: https://example.org/apt\nSuites: stable\n"
        val r = Repos.parse(sources, "deb [signed-by=/k.gpg] https://pkgs.example/deb stable main\n# deb http://old.example/deb bookworm main\ngarbage")
        assertEquals(6, r.size)
        assertEquals("deb.debian.org/debian trixie", r[0].label)
        assertTrue(r[0].enabled && !r[0].secure)
        assertFalse(r[3].enabled); assertTrue(r[3].secure)
        assertEquals("pkgs.example/deb stable", r[4].label); assertTrue(r[4].enabled)
        assertFalse(r[5].enabled)
    }

    // -------------------------------------------- the real database on a Debian system

    @Test fun realInstalledDatabaseParsesWhenPresent() {
        val f = File("/var/lib/dpkg/status")
        assumeTrue(f.isFile)
        val pkgs = DpkgStatus.parse(f.readText())
        assertTrue("many packages: ${pkgs.size}", pkgs.size > 50)
        assertNotNull(pkgs.firstOrNull { it.name == "dpkg" })
        assertTrue(pkgs.all { it.version.isNotEmpty() })
        val rev = Deps.requiredBy(pkgs)
        assertTrue("libc6 is needed by many", (rev["libc6"]?.size ?: 0) > 20)
    }
}
