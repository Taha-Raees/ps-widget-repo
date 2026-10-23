package app.pocketshell.widget.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareGuardTest {

    @Test
    fun `broad roots are never served`() {
        for (d in listOf("/", "/root", "/root/", "/etc", "/proc", "/root/x/..", "/./", "//")) {
            assertNotNull("must refuse $d", ShareGuard.refusal(d, emptyList()))
        }
    }

    @Test
    fun `a project folder is fine`() {
        assertNull(ShareGuard.refusal("/root/site", listOf("index.html", "css", "app.js")))
        assertNull(ShareGuard.refusal("/root/Projects/demo/dist", emptyList()))
    }

    @Test
    fun `a folder that is or contains secrets is refused`() {
        assertNotNull(ShareGuard.refusal("/root/.ssh", emptyList()))
        assertNotNull(ShareGuard.refusal("/root/.ssh/keys", emptyList()))
        for (n in listOf(".ssh", ".gnupg", ".aws", ".env", ".env.local", "prod.env", "id_ed25519", "server.pem", "api.key", "credentials.json", ".git-credentials", ".netrc")) {
            assertNotNull("must refuse a folder containing $n", ShareGuard.refusal("/root/site", listOf("index.html", n)))
        }
    }

    @Test
    fun `an unchecked folder is not served`() {
        assertNotNull(ShareGuard.refusal("/root/huge", listOf("a"), scanTruncated = true))
    }

    @Test
    fun `paths are normalised and relative or hostile ones rejected`() {
        assertEquals("/root/a/c", ShareGuard.normalize("/root/a/b/../c/"))
        assertEquals("/", ShareGuard.normalize("/.."))
        assertNull(ShareGuard.normalize("root/a"))
        assertNull(ShareGuard.normalize("/root/a\nb"))
        assertNotNull(ShareGuard.refusal("root/site", emptyList()))
    }

    @Test
    fun `only private wifi style addresses are offered`() {
        assertTrue(ShareGuard.isPrivateIpv4("192.168.1.20"))
        assertTrue(ShareGuard.isPrivateIpv4("10.0.0.5"))
        assertTrue(ShareGuard.isPrivateIpv4("172.16.4.1"))
        assertTrue(ShareGuard.isPrivateIpv4("172.31.255.255"))
        for (ip in listOf("127.0.0.1", "169.254.1.1", "8.8.8.8", "172.32.0.1", "172.15.0.1", "100.64.0.1", "300.1.1.1", "fe80::1", "1.2.3", "")) assertFalse(ip, ShareGuard.isPrivateIpv4(ip))
        assertEquals(listOf("wlan0" to "192.168.1.5"), ShareGuard.lanAddresses(listOf("lo" to "127.0.0.1", "wlan0" to "192.168.1.5", "rmnet0" to "100.70.1.2")))
    }

    @Test
    fun `the share link is plain http on the chosen address`() {
        assertEquals("http://192.168.1.5:8000/", ShareGuard.url("192.168.1.5", 8000))
    }

    @Test
    fun `ports and durations are bounded`() {
        assertNotNull(ShareGuard.portProblem(80)); assertNotNull(ShareGuard.portProblem(70000)); assertNull(ShareGuard.portProblem(8000))
        assertTrue("there is no unlimited option", ShareGuard.DURATIONS_MIN.all { it in 1..120 })
        assertTrue(ShareGuard.DEFAULT_MIN in ShareGuard.DURATIONS_MIN)
    }
}
