package app.pocketshell.widget.network

import java.io.File
import java.net.Socket
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShareServerTest {

    private lateinit var tmp: File
    private lateinit var root: File
    private lateinit var server: ShareServer

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("share").toFile()
        root = File(tmp, "site").apply { mkdirs() }
        File(root, "hello.txt").writeText("hello world")
        File(root, "page.html").writeText("<p>page</p>")
        File(root, "sub").mkdirs(); File(root, "sub/inner.txt").writeText("inner")
        File(root, "empty").mkdirs()
        File(root, "with space & <tag>.txt").writeText("odd name")
        File(root, ".env").writeText("SECRET=1")
        File(root, ".hidden").mkdirs(); File(root, ".hidden/x.txt").writeText("hidden")
        File(root, "id_rsa").writeText("PRIVATE")
        File(root, "notes.pem").writeText("PRIVATE")
        File(tmp, "outside.txt").writeText("TOP SECRET OUTSIDE")
        Files.createSymbolicLink(File(root, "escape.txt").toPath(), File(tmp, "outside.txt").toPath())
        Files.createSymbolicLink(File(root, "escapedir").toPath(), tmp.toPath())
        Files.createSymbolicLink(File(root, "inside-link.txt").toPath(), File(root, "hello.txt").toPath())
        server = ShareServer(root, "127.0.0.1", 0).also { it.start() }
    }

    @After
    fun tearDown() {
        server.stop()
        tmp.deleteRecursively()
    }

    private class Reply(val raw: String) {
        val status: Int = raw.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        val body: String = raw.substringAfter("\r\n\r\n", "")
        fun header(name: String): String? = raw.substringBefore("\r\n\r\n").lines().drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(":")?.trim()
    }

    private fun send(request: String): Reply {
        Socket("127.0.0.1", server.boundPort).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            s.getOutputStream().flush()
            return Reply(s.getInputStream().readBytes().toString(Charsets.UTF_8))
        }
    }

    private fun get(path: String) = send("GET $path HTTP/1.1\r\nHost: x\r\n\r\n")

    @Test
    fun `a file is served with its type and length`() {
        val r = get("/hello.txt")
        assertEquals(200, r.status); assertEquals("hello world", r.body)
        assertEquals("11", r.header("Content-Length")); assertTrue(r.header("Content-Type")!!.startsWith("text/plain"))
        assertEquals("nosniff", r.header("X-Content-Type-Options"))
        assertTrue(get("/page.html").header("Content-Type")!!.startsWith("text/html"))
    }

    @Test
    fun `head sends headers only`() {
        val r = send("HEAD /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, r.status); assertEquals("11", r.header("Content-Length")); assertEquals("", r.body)
    }

    @Test
    fun `the root listing shows files and folders but never dotfiles or secrets`() {
        val r = get("/")
        assertEquals(200, r.status)
        for (shown in listOf("hello.txt", "page.html", "sub/", "empty/")) assertTrue("should list $shown", r.body.contains(shown))
        for (hidden in listOf(".env", ".hidden", "id_rsa", "notes.pem", "SECRET")) assertFalse("must not list $hidden", r.body.contains(hidden))
    }

    @Test
    fun `listing escapes hostile file names`() {
        val r = get("/")
        assertFalse("raw angle brackets from a file name must not reach the page", r.body.contains("<tag>"))
        assertTrue(r.body.contains("&lt;tag&gt;"))
        assertEquals("odd name", get("/with%20space%20%26%20%3Ctag%3E.txt").body)
    }

    @Test
    fun `subfolders work and a missing slash redirects`() {
        assertEquals("inner", get("/sub/inner.txt").body)
        assertTrue(get("/sub/").body.contains("inner.txt"))
        val redirect = get("/sub")
        assertEquals(301, redirect.status); assertEquals("/sub/", redirect.header("Location"))
        assertTrue(get("/empty/").body.contains("empty"))
    }

    @Test
    fun `an index file is served for its folder`() {
        File(root, "sub/index.html").writeText("<h1>home</h1>")
        assertEquals("<h1>home</h1>", get("/sub/").body)
    }

    @Test
    fun `dotfiles and secret names are 404 even when asked for exactly`() {
        for (p in listOf("/.env", "/.hidden/x.txt", "/.hidden/", "/id_rsa", "/notes.pem", "/%2eenv", "/%2Eenv", "/sub/.git/config")) {
            assertEquals("must hide $p", 404, get(p).status)
            assertFalse(get(p).body.contains("SECRET"))
        }
    }

    @Test
    fun `path traversal is refused in every spelling`() {
        val attacks = listOf("/../outside.txt", "/sub/../../outside.txt", "/%2e%2e/outside.txt", "/%2E%2E%2Foutside.txt", "/sub/..%2f..%2foutside.txt",
            "/..%5coutside.txt", "/sub\\..\\..\\outside.txt", "/hello.txt%00.png", "//../outside.txt", "/./hello.txt/../../outside.txt")
        for (a in attacks) {
            val r = get(a)
            assertTrue("must not serve $a (got ${r.status})", r.status in listOf(400, 404))
            assertFalse("$a leaked the outside file", r.body.contains("TOP SECRET"))
        }
    }

    @Test
    fun `a symlink that leaves the folder is not followed`() {
        assertEquals(404, get("/escape.txt").status)
        assertEquals(404, get("/escapedir/outside.txt").status)
        assertEquals(404, get("/escapedir/").status)
        assertFalse(get("/").body.contains("escape"))
        assertEquals("a link that stays inside is fine", "hello world", get("/inside-link.txt").body)
    }

    @Test
    fun `only get and head are accepted`() {
        for (m in listOf("POST", "PUT", "DELETE", "OPTIONS", "PATCH")) {
            val r = send("$m /hello.txt HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n")
            assertEquals("$m must be refused", 405, r.status)
        }
    }

    @Test
    fun `malformed and oversized requests are rejected not crashed`() {
        assertEquals(400, send("GARBAGE\r\n\r\n").status)
        assertEquals(400, send("GET /hello.txt\r\n\r\n").status)
        assertEquals(400, send("GET hello.txt HTTP/1.1\r\n\r\n").status)
        assertEquals(400, send("GET /" + "a".repeat(20_000) + " HTTP/1.1\r\n\r\n").status.let { if (it == -1) 400 else it })
        val manyHeaders = (1..200).joinToString("") { "X-$it: v\r\n" }
        assertEquals(400, send("GET /hello.txt HTTP/1.1\r\n$manyHeaders\r\n").status.let { if (it == -1) 400 else it })
        // still alive afterwards
        assertEquals(200, get("/hello.txt").status)
    }

    @Test
    fun `a large file streams completely`() {
        val big = ByteArray(3 * 1024 * 1024 + 123) { (it % 251).toByte() }
        File(root, "big.bin").writeBytes(big)
        Socket("127.0.0.1", server.boundPort).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write("GET /big.bin HTTP/1.0\r\n\r\n".toByteArray())
            val all = s.getInputStream().readBytes()
            val sep = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
            assertEquals(big.size, all.size - sep)
            assertTrue(all.copyOfRange(sep, all.size).contentEquals(big))
        }
    }

    @Test
    fun `stopping closes the port`() {
        val port = server.boundPort
        server.stop()
        Thread.sleep(200)
        val refused = try { Socket("127.0.0.1", port).use { false } } catch (_: java.io.IOException) { true }
        assertTrue("the port must stop accepting after stop()", refused)
    }

    @Test
    fun `path rules are pure and consistent`() {
        val r = root.canonicalFile
        assertTrue(ShareServer.Paths.resolve(r, "/hello.txt") is ShareServer.Paths.Resolved.FileOk)
        assertTrue(ShareServer.Paths.resolve(r, "/") is ShareServer.Paths.Resolved.Dir)
        assertTrue(ShareServer.Paths.resolve(r, "/hello.txt?x=1#frag") is ShareServer.Paths.Resolved.FileOk)
        assertTrue(ShareServer.Paths.resolve(r, "hello.txt") is ShareServer.Paths.Resolved.Bad)
        assertTrue(ShareServer.Paths.resolve(r, "/nope") is ShareServer.Paths.Resolved.NotFound)
        assertEquals("application/octet-stream", ShareServer.Paths.contentType("x.unknown"))
        assertEquals("image/png", ShareServer.Paths.contentType("A.PNG"))
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", ShareServer.Paths.escapeHtml("<a href=\"x\">&'"))
        assertNull(null)
    }
}
