package app.pocketshell.widget.network

import android.content.Context
import app.pocketshell.runtime.RuntimeStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Everything the app reads or writes on disk, in one place.
 *
 * The Linux home is `rootfs/root`: the same directory the guest sees as `/root`, readable and
 * writable by the app. Reading and writing it from the host side needs no exec at all, which is why
 * hosts, keys and tunnels cost nothing to show. Every name that reaches a path is validated first and
 * the resulting file is checked to still sit inside `~/.ssh` (or the config folder).
 */
class NetFiles(val rootfs: File) {

    constructor(appContext: Context) : this(RuntimeStorage(appContext.noBackupFilesDir).rootfsDir)

    val home: File get() = File(rootfs, "root")
    val sshDir: File get() = File(home, ".ssh")
    val configFile: File get() = File(sshDir, "config")
    val knownHosts: File get() = File(sshDir, "known_hosts")
    private val appConfigDir: File get() = File(home, ".config/pocketshell")
    val tunnelsFile: File get() = File(appConfigDir, "network-tunnels.txt")
    val jobsDir: File get() = File(rootfs, "tmp/netjobs")
    val resolvConf: File get() = File(rootfs, "etc/resolv.conf")

    /** Guest path (`/root/site`) → its host file, only if it stays inside the rootfs. */
    fun hostPath(guestPath: String): File? {
        val n = ShareGuard.normalize(guestPath) ?: return null
        val f = File(rootfs, n.removePrefix("/"))
        return if (f.canonicalPath.startsWith(rootfs.canonicalPath)) f else null
    }

    // ---------------------------------------------------------------- config

    fun readConfig(): String = try {
        if (configFile.isFile && configFile.length() < MAX_CONFIG_BYTES) configFile.readText() else ""
    } catch (_: Exception) {
        ""
    }

    /** Write the config atomically, keeping a one-time `.pre-network` copy of whatever was there. */
    fun writeConfig(text: String) {
        ensureSshDir()
        val backup = File(sshDir, "config.pre-network")
        if (configFile.isFile && !backup.exists()) configFile.copyTo(backup)
        atomicWrite(configFile, text, secret = true)
    }

    // ----------------------------------------------------------------- keys

    fun listKeys(): List<KeyInfo> {
        val dir = sshDir
        val pubs = dir.listFiles { f -> f.isFile && f.name.endsWith(".pub") && f.length() < 16_384 }?.sortedBy { it.name } ?: return emptyList()
        return pubs.mapNotNull { pub ->
            val name = pub.name.removeSuffix(".pub")
            if (!SshKeys.validName(name)) return@mapNotNull null
            val line = try { pub.readLines().firstOrNull { it.isNotBlank() } } catch (_: Exception) { null } ?: return@mapNotNull null
            val parsed = SshKeys.parsePublic(line) ?: return@mapNotNull null
            val priv = File(dir, name)
            val head = try { if (priv.isFile) priv.inputStream().use { it.readNBytes(2048).toString(Charsets.US_ASCII) } else "" } catch (_: Exception) { "" }
            KeyInfo(
                name = name,
                type = parsed.type,
                bits = parsed.bits,
                fingerprint = parsed.fingerprint,
                comment = parsed.comment,
                publicLine = line.trim(),
                hasPrivate = priv.isFile,
                encrypted = head.isNotEmpty() && SshKeys.isEncrypted(head + (if (head.contains("-----END")) "" else "\n-----END OPENSSH PRIVATE KEY-----")),
                permissionsTooOpen = tooOpen(priv),
            )
        }
    }

    private fun tooOpen(f: File): Boolean = try {
        if (!f.isFile) false
        else Files.getPosixFilePermissions(f.toPath()).any {
            it in setOf(PermissionGroupRead, PermissionOthersRead, PermissionGroupWrite, PermissionOthersWrite)
        }
    } catch (_: Exception) {
        false
    }

    /** Does a key pair of this name already exist (so a new one must not overwrite it)? */
    fun keyExists(name: String): Boolean = File(sshDir, name).exists() || File(sshDir, "$name.pub").exists()

    fun deleteKey(name: String): Boolean {
        if (!SshKeys.validName(name)) return false
        val a = File(sshDir, name)
        val b = File(sshDir, "$name.pub")
        if (a.canonicalFile.parentFile != sshDir.canonicalFile) return false
        var ok = true
        if (a.exists()) ok = a.delete() && ok
        if (b.exists()) ok = b.delete() && ok
        return ok
    }

    fun ensureSshDir() {
        if (!sshDir.isDirectory) sshDir.mkdirs()
        runCatching { Files.setPosixFilePermissions(sshDir.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)) }
    }

    // ---------------------------------------------------------- known_hosts

    private val KNOWN_LINE = Regex("^[A-Za-z0-9.:\\[\\]_,-]{1,255} (ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/]{20,2000}={0,2}$")

    /** Lines from `ssh-keyscan` that are safe to append (plain host + key; never a cert-authority or marker). */
    fun acceptableKnownLines(lines: List<String>): List<String> = lines.map { it.trim() }.filter { KNOWN_LINE.matches(it) }

    fun appendKnownHosts(lines: List<String>) {
        val ok = acceptableKnownLines(lines)
        if (ok.isEmpty()) return
        ensureSshDir()
        val existing = if (knownHosts.isFile) knownHosts.readText() else ""
        val prefix = if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else ""
        atomicWrite(knownHosts, existing + prefix + ok.joinToString("\n") + "\n", secret = false)
    }

    // -------------------------------------------------------------- tunnels

    fun readTunnels(): List<Tunnel> = try {
        if (tunnelsFile.isFile && tunnelsFile.length() < 64_000) Tunnels.decode(tunnelsFile.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun writeTunnels(list: List<Tunnel>) {
        appConfigDir.mkdirs()
        atomicWrite(tunnelsFile, Tunnels.encode(list), secret = false)
    }

    // ---------------------------------------------------------------- misc

    fun resolvConfText(): String = try { if (resolvConf.isFile) resolvConf.readText() else "" } catch (_: Exception) { "" }

    fun hasPython(): Boolean = File(rootfs, "usr/bin/python3").exists()

    /** Names found in [dir]'s subtree (bounded), for [ShareGuard]. */
    fun scanNames(dir: File, maxEntries: Int = 4000, maxDepth: Int = 6): Pair<List<String>, Boolean> {
        val names = ArrayList<String>()
        var truncated = false
        fun walk(d: File, depth: Int) {
            if (truncated) return
            val kids = d.listFiles() ?: return
            for (k in kids) {
                if (names.size >= maxEntries) { truncated = true; return }
                names += k.name
                // Symlinks are not followed: python's server would follow them, but a link out of the
                // folder is a link the user made on purpose; the names above already cover what is inside.
                if (k.isDirectory && depth < maxDepth && !Files.isSymbolicLink(k.toPath())) walk(k, depth + 1)
                else if (k.isDirectory && depth >= maxDepth) truncated = true
            }
        }
        walk(dir, 0)
        return names to truncated
    }

    private fun atomicWrite(target: File, text: String, secret: Boolean) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        if (secret) runCatching { Files.setPosixFilePermissions(tmp.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val MAX_CONFIG_BYTES = 256_000L
        private val PermissionGroupRead = PosixFilePermission.GROUP_READ
        private val PermissionOthersRead = PosixFilePermission.OTHERS_READ
        private val PermissionGroupWrite = PosixFilePermission.GROUP_WRITE
        private val PermissionOthersWrite = PosixFilePermission.OTHERS_WRITE
    }
}
