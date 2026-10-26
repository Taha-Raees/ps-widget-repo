package app.pocketshell.widget.sync

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Test scaffolding that runs the REAL generated job script with the machine's
 * own /bin/sh, tar and gzip against a temp directory standing in for the
 * guest's "/" (PSBAK_ROOT), so the whole backup/restore path — script, tar,
 * compression, staging, hashing, encryption — is exercised end to end.
 */
internal object Tools {
    fun have(name: String): Boolean = try {
        val p = ProcessBuilder("/bin/sh", "-c", "command -v \"\$1\"", "sh", name).redirectErrorStream(true).start()
        val ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        ok
    } catch (_: Exception) {
        false
    }

    val canRunPipelines: Boolean by lazy { have("tar") && have("gzip") && File("/bin/sh").exists() }
}

/** Runs guest jobs locally: guest paths under /tmp/psbak are mapped into [rootfs]. */
internal class LocalShellRunner(private val rootfs: File, private val extraEnv: Map<String, String> = emptyMap()) : GuestJobRunner {

    val started = ArrayList<List<String>>()

    private fun map(arg: String): String =
        if (arg.startsWith("/tmp/psbak/")) File(rootfs, arg.removePrefix("/")).path else arg

    override fun start(argv: List<String>): RunningJob {
        started += argv
        val isJob = argv.size >= 3 && argv[0] == "/bin/sh" && argv[1].startsWith("/tmp/psbak/")
        val pb = ProcessBuilder(argv.map { map(it) })
        if (isJob) pb.environment()["PSBAK_ROOT"] = rootfs.path
        pb.environment().putAll(extraEnv)
        pb.redirectInput(File("/dev/null"))
        val proc = pb.start()
        return object : RunningJob {
            override fun waitFor(timeoutMs: Long): JobResult {
                val out = StringBuilder()
                val err = StringBuilder()
                val t1 = Thread { out.append(proc.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
                val t2 = Thread { err.append(proc.errorStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
                val done = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                t1.join(2000); t2.join(2000)
                if (!done) { proc.destroy(); return JobResult(null, out.toString(), err.toString().lines().lastOrNull { it.isNotBlank() }, "timed out") }
                return JobResult(proc.exitValue(), out.toString(), err.toString().lines().lastOrNull { it.isNotBlank() })
            }

            override fun destroy() {
                proc.destroy()
            }
        }
    }
}

/** A device-in-a-directory: the guest "/" plus the app's DataStore folder. */
internal class FakeDevice(val name: String = "Test Phone") {
    val root: File = Files.createTempDirectory("psbak-root").toFile()
    val datastore: File = Files.createTempDirectory("psbak-ds").toFile()
    val clock = AtomicLong(1_792_693_800_000L)

    fun write(path: String, text: String, executable: Boolean = false): File {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText(text)
        if (executable) f.setExecutable(true)
        return f
    }

    fun symlink(path: String, target: String) {
        val f = File(root, path)
        f.parentFile.mkdirs()
        Files.createSymbolicLink(f.toPath(), File(target).toPath())
    }

    /** A realistic little Linux home. */
    fun populate(): FakeDevice {
        // apt's manual set: base-files, bash, git, openjdk-21-jdk-headless (libc6 is automatic, vim removed)
        write("var/lib/dpkg/status", "Package: base-files\nStatus: install ok installed\nVersion: 13.8\n\nPackage: libc6\nStatus: install ok installed\nVersion: 2.41-12\n\nPackage: bash\nStatus: install ok installed\nVersion: 5.2.37-2\n\nPackage: git\nStatus: install ok installed\nVersion: 1:2.47.3-0\n\nPackage: openjdk-21-jdk-headless\nStatus: install ok installed\nVersion: 21.0.12\n\nPackage: vim\nStatus: deinstall ok config-files\nVersion: 2:9.1\n")
        write("var/lib/apt/extended_states", "Package: libc6\nArchitecture: arm64\nAuto-Installed: 1\n")
        write("etc/apt/sources.list.d/debian.sources", "Types: deb\nURIs: http://deb.debian.org/debian\nSuites: trixie\n")
        write("etc/debian_version", "13.7\n")
        write("root/proj/src/a.kt", "fun main() = println(\"hello\")\n", executable = true)
        write("root/proj/node_modules/pkg/index.js", "junk\n")
        write("root/.gradle/caches/modules/x.bin", "cache\n")
        write("root/.gradle/init.d/keep.gradle", "// keep\n")
        write("root/.cache/thing", "cache\n")
        write("root/.ssh/id_ed25519", "PRIVATE-KEY-MATERIAL\n")
        write("root/.env", "API_THING=1\n")
        write("root/dir with space/file.txt", "spaced\n")
        write("usr/local/bin/mytool", "#!/bin/sh\necho tool\n", executable = true)
        symlink("root/abs-link", "/root/proj/src/a.kt")
        symlink("root/proj/src/rel-link", "a.kt")
        return this
    }

    fun writeDatastore(name: String, content: String) {
        File(datastore, AppSettingsFiles.fileName(name)).writeText(content)
    }

    fun env(minFree: Long = 1L, ids: Iterator<String>? = null): EngineEnv = EngineEnv(
        rootfs = root, datastoreDir = datastore, device = name, pluginVersion = "3.0.0-test",
        now = { clock.get() },
        newId = { ids?.next() ?: EngineEnv.randomId() },
        minFreeBytes = minFree,
    )

    fun engine(runner: GuestJobRunner = LocalShellRunner(root), ids: Iterator<String>? = null) =
        BackupEngine(env(ids = ids), runner) { }

    fun cleanup() {
        root.deleteRecursively()
        datastore.deleteRecursively()
    }
}

internal fun tempStorage(label: String = "Test storage"): Pair<File, FileBackupStorage> {
    val dir = Files.createTempDirectory("psbak-store").toFile()
    return dir to FileBackupStorage(dir, label)
}

/** Lists the members of a `.psbak` payload with the machine's own tools (header stripped). */
internal fun listArchive(file: File, decompress: String = "gzip -dc"): List<String> {
    val p = ProcessBuilder("/bin/sh", "-c", "tail -c +9 \"\$1\" | $decompress | tar -t", "sh", file.path)
        .redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor(60, TimeUnit.SECONDS)
    return out.lines().filter { it.isNotBlank() }
}

internal fun BackupStorage.firstArchive(): StoredBackup = list().first()

internal fun storedSource(storage: BackupStorage, backup: StoredBackup): BackupSource = StoredSource(storage, backup)

/** A source that is just a file the user picked: no sidecar, so no checksum to compare. */
internal class PickedFileSource(private val file: File) : BackupSource {
    override val name: String get() = file.name
    override val sizeBytes: Long? get() = file.length()
    override val record: BackupRecord? get() = null
    override fun open(): java.io.InputStream = file.inputStream()
}

/**
 * A minimal ustar writer so tests can craft HOSTILE archives (`..` members,
 * absolute names, write-through symlinks) that no honest tar would produce.
 */
internal class TarBuilder {
    private val out = java.io.ByteArrayOutputStream()

    private fun field(bytes: ByteArray, off: Int, len: Int, text: String) {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(b, 0, bytes, off, minOf(b.size, len))
    }

    private fun entry(name: String, type: Char, size: Int, link: String = "", mode: Int = 420) {
        require(name.length <= 100 && link.length <= 100)
        val h = ByteArray(512)
        field(h, 0, 100, name)
        field(h, 100, 8, String.format("%07o\u0000", mode))
        field(h, 108, 8, "0000000\u0000")
        field(h, 116, 8, "0000000\u0000")
        field(h, 124, 12, String.format("%011o\u0000", size))
        field(h, 136, 12, String.format("%011o\u0000", 1_700_000_000L))
        field(h, 148, 8, "        ") // checksum placeholder: spaces
        h[156] = type.code.toByte()
        field(h, 157, 100, link)
        field(h, 257, 6, "ustar\u0000")
        field(h, 263, 2, "00")
        val sum = h.sumOf { it.toInt() and 0xff }
        field(h, 148, 8, String.format("%06o\u0000 ", sum))
        out.write(h)
    }

    fun dir(name: String) = apply { entry(name.trimEnd('/') + "/", '5', 0, mode = 493) }

    fun file(name: String, text: String = "data\n") = apply {
        val b = text.toByteArray()
        entry(name, '0', b.size)
        out.write(b)
        out.write(ByteArray((512 - b.size % 512) % 512))
    }

    fun symlink(name: String, target: String) = apply { entry(name, '2', 0, link = target, mode = 511) }

    fun bytes(): ByteArray = out.toByteArray() + ByteArray(1024) // two zero blocks end the archive
}

/** Wraps a tar into a valid unencrypted gzip `.psbak`, as the app would have written it. */
internal fun psbakFrom(tar: ByteArray, dir: File, name: String = "PocketShell-Hostile-20261022-1830.psbak"): File {
    val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(tar) } }.toByteArray()
    return File(dir, name).also { it.writeBytes(PsbakHeader(PsbakHeader.VERSION, false, BackupCodec.GZIP).encode() + gz) }
}
