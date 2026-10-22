package app.pocketshell.widget.servers

import app.pocketshell.widget.probe.ServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card's row model: what is shown, in what order, and what counts as "my server". */
class ServersCardTest {

    private fun server(port: Int, pid: Int?, name: String = "node") = ServerInfo(
        port = port, pid = pid, processName = name, cwd = null,
        attribution = ServerInfo.Attribution.ACCEPTOR_MATCH,
    )

    private fun rec(pid: Int, comm: String, argv: String, ppid: Int = 1, tty: Int = 0, rss: Long = 1_000_000, swap: Long = 0) =
        ProcRecord(
            pid = pid, ppid = ppid, comm = comm, argv = argv, state = 'S', pgrp = pid, sid = pid, tty = tty, tpgid = -1,
            threads = 1, cpuTicks = 0, rssBytes = rss, swapBytes = swap, startedAtMs = 0, cwd = "/root", hupIgnored = false,
        )

    /** gradle (:43521 IPC, big), dbus, a tmux keeper, a detached node dev server :3000, a small watcher. */
    private val procs = ProcViews.build(
        listOf(
            rec(400, "java", "java org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.5", rss = 100_000_000, swap = 900_000_000),
            rec(500, "dbus-daemon", "dbus-daemon --session", rss = 300_000),
            rec(600, "tmux: server", "tmux: server"),
            rec(800, "node", "node /srv/app.js", rss = 80_000_000),
            rec(900, "watcher", "watcher --loop", rss = 5_000_000),
        ),
        cpu = emptyMap(),
        portsByPid = mapOf(400 to listOf(43521), 800 to listOf(3000)),
    )

    private val listeners = listOf(server(43521, 400, "java"), server(3000, 800), server(9999, null, "mystery"))

    @Test
    fun `build-daemon IPC ports are not dev servers but an unattributed listener still is`() {
        assertEquals(listOf(3000, 9999), CardRows.devServers(listeners, procs).map { it.port })
    }

    @Test
    fun `rows are ordered by importance - servers, keepers, background by footprint, system last`() {
        val rows = CardRows.build(listeners, procs)
        val order = rows.map {
            when (it) {
                is CardRow.Srv -> "srv:${it.server.port}"
                is CardRow.Proc -> "proc:${it.entry.pid}"
            }
        }
        // gradle (1 GB footprint) outranks the small watcher; dbus is SYSTEM and goes last.
        assertEquals(listOf("srv:3000", "srv:9999", "proc:600", "proc:400", "proc:900", "proc:500"), order)
    }

    @Test
    fun `a keeper row carries how many processes sit below it`() {
        val keeper = CardRows.build(listeners, procs).filterIsInstance<CardRow.Proc>().first { it.entry.pid == 600 }
        assertEquals(0, keeper.jobs)
    }

    @Test
    fun `headline counts - up, background, sessions`() {
        val (up, bg, ses) = CardRows.counts(listeners, procs)
        assertEquals(2, up)
        // gradle, dbus, watcher — not tmux (a session) and not the :3000 server.
        assertEquals(3, bg)
        assertEquals(1, ses)
        assertEquals("2 up · 3 bg · 1 ses", ProcPresentation.summary(up, bg, ses))
    }

    @Test
    fun `an empty device has no rows and zero counts`() {
        assertTrue(CardRows.build(emptyList(), ProcView.EMPTY).isEmpty())
        assertEquals(Triple(0, 0, 0), CardRows.counts(emptyList(), ProcView.EMPTY))
    }

    @Test
    fun `the card row cap leaves a visible remainder`() {
        val rows = CardRows.build(listeners, procs)
        val shown = rows.take(ServersLayout.COMPACT.cardRows)
        assertEquals(ServersLayout.COMPACT.cardRows, shown.size)
        assertEquals(rows.size - shown.size, 2)
    }

    @Test
    fun `rows that fit follow the real card height, never below one nor above the tier cap`() {
        // A short compact card still shows one row; a tall one stops at the cap.
        assertEquals(1, CardRows.rowsThatFit(60f, ServersLayout.COMPACT))
        assertEquals(ServersLayout.COMPACT.cardRows, CardRows.rowsThatFit(900f, ServersLayout.COMPACT))
        // The same height holds fewer two-line ROOMY rows than one-line COMPACT rows.
        assertTrue(CardRows.rowsThatFit(250f, ServersLayout.ROOMY) < CardRows.rowsThatFit(250f, ServersLayout.COMPACT) + 3)
        assertEquals(3, CardRows.rowsThatFit(250f, ServersLayout.ROOMY))
        // Fit grows monotonically with height.
        val fits = (100..400 step 20).map { CardRows.rowsThatFit(it.toFloat(), ServersLayout.ROOMY) }
        assertEquals(fits.sorted(), fits)
    }
}
