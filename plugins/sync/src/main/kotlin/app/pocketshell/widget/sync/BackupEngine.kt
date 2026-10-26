package app.pocketshell.widget.sync

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * The backup / restore engine — orchestration only. It decides WHAT happens
 * and in what order; the heavy lifting is delegated to the tools that already
 * exist (tar + zstd/gzip in the guest, platform AES-GCM, the system file
 * picker), never reinvented.
 *
 *   BACKUP   stage inputs → guest job (tar | compress → payload) → stream
 *            payload (encrypted?) into the chosen storage while hashing →
 *            sidecar LAST → size check → prune the oldest → clean the stage
 *   INSPECT  stream the chosen file into the stage (decrypting, checking the
 *            sidecar's sha256) → guest job lists/extracts ONLY the manifest →
 *            preview. Nothing in the user's data is touched.
 *   APPLY    guest job extracts the chosen scopes (in place or into a side
 *            folder) → optionally app settings (allow-listed, with a
 *            `.pre-restore` copy)
 *
 * The guest is reached through [GuestJobRunner]; the app's real one is in
 * BackupAndroid.kt and the tests drive the REAL generated script with the real
 * busybox tar/gzip against a temp root. Everything blocking: callers run it on
 * a worker, and [CancelToken] stops it at the next step or copy chunk.
 */

// ----------------------------------------------------------------- seams

/** Starts a guest command; the real one is the host's non-PTY guest runner. */
interface GuestJobRunner {
    fun start(argv: List<String>): RunningJob
}

interface RunningJob {
    /** Blocks until the job ends or [timeoutMs] passes (the host then destroys it). */
    fun waitFor(timeoutMs: Long): JobResult

    /** Terminate; idempotent. */
    fun destroy()
}

data class JobResult(
    val exitCode: Int?,
    val stdout: String = "",
    val stderrTail: String? = null,
    val error: String? = null,
)

/** Stops a job's whole process tree from the host (host pid == guest pid; no pid namespace). */
fun interface ProcessSignaller {
    fun terminateTree(rootPid: Int)
}

class CancelledException : RuntimeException("cancelled")

/** Cooperative cancellation: flipped by the UI, observed at every step and copy chunk. */
class CancelToken {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun check() {
        if (cancelled) throw CancelledException()
    }
}

/** What a long step is doing right now. */
data class Progress(val label: String, val bytes: Long = 0, val total: Long? = null)

/** Everything the engine needs from its surroundings — injectable so tests need no device. */
class EngineEnv(
    /** The host directory that IS the guest's "/" (the staged files are visible to both sides). */
    val rootfs: File,
    /** `filesDir/datastore`; null disables app-settings capture/restore (tests, no context). */
    val datastoreDir: File?,
    val device: String,
    val pluginVersion: String,
    val now: () -> Long = { System.currentTimeMillis() },
    val newId: () -> String = { randomId() },
    /** Free space a backup/restore must start with (the guest's `tmp` holds the staged archive). */
    val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
) {
    companion object {
        const val DEFAULT_MIN_FREE_BYTES = 200L * 1024 * 1024
        private val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun randomId(): String {
            val r = SecureRandom()
            return buildString { repeat(12) { append(ALPHABET[r.nextInt(ALPHABET.length)]) } }
        }
    }
}

// ---------------------------------------------------------------- requests

class BackupRequest(
    val options: BackupOptions,
    /** Null = unencrypted. The passphrase lives only for the call and is never stored. */
    val passphrase: CharArray?,
    val zstdInstalled: Boolean,
    val storage: BackupStorage,
)

sealed interface BackupOutcome {
    data class Done(val record: BackupRecord, val pruned: List<String>, val skippedPaths: List<String>) : BackupOutcome
    data class Failed(val reason: String) : BackupOutcome
    data object Cancelled : BackupOutcome
}

sealed interface InspectOutcome {
    data class Ready(
        val stageId: String,
        val header: PsbakHeader,
        val manifest: BackupManifest,
        val record: BackupRecord?,
        val packages: List<String>,
        val appSettings: List<String>,
        val compatibility: Compatibility,
    ) : InspectOutcome

    data class NeedsPassphrase(val sizeBytes: Long?) : InspectOutcome
    data class WrongPassphrase(val reason: String) : InspectOutcome
    data class NeedsTool(val tool: String) : InspectOutcome
    data class Failed(val reason: String) : InspectOutcome
    data object Cancelled : InspectOutcome
}

sealed interface ApplyOutcome {
    data class Applied(
        val destination: String,
        val restoredPaths: List<String>,
        val appSettingsRestored: List<String>,
    ) : ApplyOutcome {
        /** App-settings files are read when the app starts — a restart applies them. */
        val needsRestart: Boolean get() = appSettingsRestored.isNotEmpty()
    }

    data class Failed(val reason: String) : ApplyOutcome
    data object Cancelled : ApplyOutcome
}

sealed interface StepOutcome {
    data class Done(val detail: String? = null) : StepOutcome
    data class Failed(val reason: String) : StepOutcome
    data object Cancelled : StepOutcome
}

sealed interface VerifyOutcome {
    data class Match(val sizeBytes: Long) : VerifyOutcome
    data class Mismatch(val expected: String, val actual: String) : VerifyOutcome
    data object NoRecord : VerifyOutcome
    data class Failed(val reason: String) : VerifyOutcome
    data object Cancelled : VerifyOutcome
}

private class JobFailure(message: String) : RuntimeException(message)

// ------------------------------------------------------------------ engine

class BackupEngine(
    private val env: EngineEnv,
    private val runner: GuestJobRunner,
    private val signaller: ProcessSignaller,
) {

    companion object {
        /** A backup of a big home is slow on a phone; the host kills a job that outlives this. */
        const val JOB_TIMEOUT_MS = 6L * 60 * 60 * 1000
        const val POLL_MS = 500L
        private const val COPY_BUFFER = 256 * 1024
        private const val MAX_RENAME_TRIES = 10
    }

    /** Stages an operation (or an open restore) is using right now — never offered as "interrupted", never discarded. */
    private val inUse: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Stages whose guest job is executing at this instant. */
    private val running: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun stageDir(id: String): File {
        require(BackupJob.isValidStageId(id)) { "invalid stage id" }
        return File(env.rootfs, "tmp/psbak/$id")
    }

    // ----------------------------------------------------------------- tools

    /** True when [tool] is installed in the guest (`command -v`, name passed positionally). */
    fun hasTool(tool: String): Boolean = try {
        runner.start(listOf("/bin/sh", "-c", "command -v \"\$1\"", "sh", tool)).waitFor(15_000L).let {
            it.exitCode == 0 && it.stdout.isNotBlank()
        }
    } catch (_: Exception) {
        false
    }

    private fun guestArch(): String? = try {
        runner.start(listOf("/bin/uname", "-m")).waitFor(15_000L).stdout.trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- backup

    /** Runs a backup; the passphrase array is zeroed on EVERY path out, early refusals included. */
    fun backup(req: BackupRequest, cancel: CancelToken, onProgress: (Progress) -> Unit): BackupOutcome =
        try {
            doBackup(req, cancel, onProgress)
        } finally {
            req.passphrase?.fill('\u0000')
        }

    private fun doBackup(req: BackupRequest, cancel: CancelToken, onProgress: (Progress) -> Unit): BackupOutcome {
        val opts = req.options
        BackupPlan.validate(opts)?.let { return BackupOutcome.Failed(it) }
        val pass = req.passphrase
        if (pass != null && pass.isEmpty()) return BackupOutcome.Failed("the passphrase is empty")
        val encrypted = pass != null
        if (env.rootfs.usableSpace < env.minFreeBytes) {
            return BackupOutcome.Failed("not enough free space on the device (need ${env.minFreeBytes / (1024 * 1024)} MB)")
        }

        val codec = BackupCodec.preferred(req.zstdInstalled)
        val id = env.newId()
        val stage = stageDir(id)
        inUse += id
        var written: String? = null
        try {
            onProgress(Progress("preparing"))
            val manifest = stageBackup(stage, opts, encrypted, codec)
            cancel.check()

            runJob(BackupJob.Op.BACKUP, id, stage, cancel, onProgress) { st -> st.phase ?: "preparing" }
            cancel.check()

            val payload = File(stage, BackupJob.Files.PAYLOAD)
            if (!payload.isFile || payload.length() == 0L) throw JobFailure("the archive was not produced")
            val system = readLines(File(stage, BackupJob.Files.SYSTEM))
            val skipped = readLines(File(stage, BackupJob.Files.SKIPPED))

            val name = uniqueName(req.storage, manifest.createdAtMs)
            written = name
            onProgress(Progress("writing", 0, payload.length()))
            val header = PsbakHeader(PsbakHeader.VERSION, encrypted, codec).encode()
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = CountingDigestOutputStream(req.storage.openWrite(name, "application/octet-stream"), digest)
            val out: OutputStream = try {
                sink.write(header)
                if (pass != null) PsbakCrypto.encryptingStream(sink, pass, header) else sink
            } catch (t: Throwable) {
                sink.close()
                throw t
            }
            out.use { dst ->
                payload.inputStream().use { src ->
                    copy(src, dst, cancel) { onProgress(Progress("writing", it, payload.length())) }
                }
            }
            val sha = hex(digest.digest())
            val size = sink.count

            val record = BackupRecord(
                name = name, sizeBytes = size, sha256 = sha, manifest = manifest,
                arch = system.getOrNull(0), alpine = system.getOrNull(1),
            )
            onProgress(Progress("finishing", size, size))
            req.storage.openWrite(BackupNames.sidecarName(name), "application/json").use {
                it.write(BackupCodecs.encodeRecord(record).toByteArray(Charsets.UTF_8))
            }
            // The location must really hold what we wrote (a provider that reports a size is held to it).
            // A listing that merely THROWS cannot confirm or refute anything: the finished backup is
            // kept, never deleted on a transient error.
            val listing = try {
                req.storage.list()
            } catch (_: Exception) {
                null
            }
            if (listing != null) {
                val listed = listing.firstOrNull { it.name == name } ?: throw JobFailure("the file is not in the chosen location after writing")
                if (listed.sizeBytes > 0 && listed.sizeBytes != size) {
                    throw JobFailure("the location holds ${listed.sizeBytes} bytes but $size were written")
                }
            }
            // From here the backup is complete: nothing below may discard it.
            written = null

            val pruned = if (listing == null) {
                emptyList()
            } else {
                Retention.toPrune(listing, opts.keep, protect = setOf(name), device = env.device)
                    .filter { runCatching { req.storage.delete(it.name) }.getOrDefault(false) }
                    .also { gone -> gone.forEach { runCatching { req.storage.delete(BackupNames.sidecarName(it.name)) } } }
                    .map { it.name }
            }

            return BackupOutcome.Done(record, pruned, skipped)
        } catch (_: CancelledException) {
            discardPartial(req.storage, written)
            return BackupOutcome.Cancelled
        } catch (e: Exception) {
            discardPartial(req.storage, written)
            return BackupOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            inUse.remove(id)
            deleteTree(stage)
        }
    }

    /** Write every input the job reads; returns the manifest that goes INTO the archive. */
    private fun stageBackup(stage: File, opts: BackupOptions, encrypted: Boolean, codec: BackupCodec): BackupManifest {
        val meta = File(stage, BackupJob.Files.META_DIR).apply { mkdirs() }
        File(stage, BackupJob.Files.SCRIPT).writeText(BackupJob.SCRIPT)
        File(stage, BackupJob.Files.CODEC).writeText(codec.label)
        File(stage, BackupJob.Files.INCLUDE).writeText(lines(BackupPlan.includeList(opts)))
        val excludes = BackupPlan.excludeList(opts, encrypted)
        File(stage, BackupJob.Files.EXCLUDE).writeText(lines(excludes))

        val wantPackages = BackupScope.PACKAGES in opts.scopes
        if (wantPackages) File(stage, BackupJob.Files.WANT_PACKAGES).writeText("1")
        // The package list (apt's manual set) is read from the dpkg files host-side and staged with the
        // other metadata; the job copies meta/ into the backup as-is.
        val packages = if (wantPackages) {
            val manual = PkgList.manual(readText(File(env.rootfs, "var/lib/dpkg/status")), readText(File(env.rootfs, "var/lib/apt/extended_states")))
            if (manual.isNotEmpty()) File(meta, "pkg-manual").writeText(lines(manual))
            manual.size
        } else {
            0
        }

        val captured = ArrayList<String>()
        val dsDir = env.datastoreDir
        if (BackupScope.APP_SETTINGS in opts.scopes && dsDir != null) {
            val appdata = File(meta, "appdata").apply { mkdirs() }
            for (n in AppSettingsFiles.NAMES) {
                val f = File(dsDir, AppSettingsFiles.fileName(n))
                if (f.isFile) {
                    f.copyTo(File(appdata, f.name), overwrite = true)
                    captured += n
                }
            }
        }

        val manifest = BackupManifest(
            createdAtMs = env.now(),
            device = env.device,
            pluginVersion = env.pluginVersion,
            codec = codec,
            scopes = opts.scopes.sortedBy { it.ordinal },
            excludes = excludes,
            sensitiveIncluded = BackupPlan.includesSensitive(opts, encrypted),
            encrypted = encrypted,
            packages = packages,
            appSettings = captured,
            homeItems = if (BackupScope.HOME in opts.scopes && opts.homeMode == HomeMode.CHOSEN) opts.homeItems.sorted() else emptyList(),
        )
        File(meta, "manifest.json").writeText(BackupCodecs.encodeManifest(manifest))
        return manifest
    }

    private fun uniqueName(storage: BackupStorage, createdAtMs: Long): String {
        val existing = storage.list().map { it.name }.toSet()
        var at = createdAtMs
        repeat(MAX_RENAME_TRIES) {
            val name = BackupNames.fileName(env.device, at)
            if (name !in existing) return name
            at += 60_000L // names carry minutes; a second backup in the same minute moves to the next
        }
        throw JobFailure("could not find a free file name in the chosen location")
    }

    private fun discardPartial(storage: BackupStorage, name: String?) {
        if (name == null) return
        runCatching { storage.delete(name) }
        runCatching { storage.delete(BackupNames.sidecarName(name)) }
    }

    // --------------------------------------------------------------- inspect

    /** Opens a backup for preview; the passphrase array is zeroed on EVERY path out. */
    fun inspect(source: BackupSource, passphrase: CharArray?, cancel: CancelToken, onProgress: (Progress) -> Unit): InspectOutcome =
        try {
            doInspect(source, passphrase, cancel, onProgress)
        } finally {
            passphrase?.fill('\u0000')
        }

    private fun doInspect(source: BackupSource, passphrase: CharArray?, cancel: CancelToken, onProgress: (Progress) -> Unit): InspectOutcome {
        val id = env.newId()
        val stage = stageDir(id)
        var keep = false
        try {
            onProgress(Progress("reading"))
            val raw = DigestSource(source.open())
            val headerBytes = ByteArray(PsbakHeader.SIZE)
            val got = raw.stream.readNBytesCompat(headerBytes)
            if (got < PsbakHeader.SIZE) {
                raw.stream.close()
                return InspectOutcome.Failed("this is not a PocketShell backup")
            }
            val header = when (val parsed = PsbakHeader.parse(headerBytes)) {
                is PsbakHeader.Parsed.Ok -> parsed.header
                is PsbakHeader.Parsed.NotPsbak -> { raw.stream.close(); return InspectOutcome.Failed("this is not a PocketShell backup") }
                is PsbakHeader.Parsed.Newer -> { raw.stream.close(); return InspectOutcome.Failed("this backup was made by a newer PocketShell (format ${parsed.version}) — update the app first") }
                is PsbakHeader.Parsed.UnknownCodec -> { raw.stream.close(); return InspectOutcome.Failed("this backup uses a compression this version does not know") }
            }
            if (header.encrypted && (passphrase == null || passphrase.isEmpty())) {
                raw.stream.close()
                return InspectOutcome.NeedsPassphrase(source.sizeBytes)
            }
            val tool = header.codec.label
            if (header.codec != BackupCodec.GZIP && !hasTool(tool)) {
                raw.stream.close()
                return InspectOutcome.NeedsTool(tool)
            }
            val free = env.rootfs.usableSpace
            val need = (source.sizeBytes ?: 0L) * 3 + env.minFreeBytes
            if (free < need) {
                raw.stream.close()
                return InspectOutcome.Failed("not enough free space: about ${need / (1024 * 1024)} MB needed, ${free / (1024 * 1024)} MB free")
            }

            stage.mkdirs()
            inUse += id
            File(stage, BackupJob.Files.SCRIPT).writeText(BackupJob.SCRIPT)
            File(stage, BackupJob.Files.CODEC).writeText(header.codec.label)
            val payload = File(stage, BackupJob.Files.PAYLOAD)
            try {
                val data: InputStream = if (header.encrypted) PsbakCrypto.decryptingStream(raw.stream, passphrase!!, headerBytes) else raw.stream
                data.use { src ->
                    payload.outputStream().buffered().use { dst ->
                        copy(src, dst, cancel) { onProgress(Progress("reading", it, source.sizeBytes)) }
                    }
                }
            } catch (e: PsbakCrypto.BadPassphraseOrCorruptException) {
                return InspectOutcome.WrongPassphrase(e.message ?: "wrong passphrase, or the file is damaged")
            }
            val sha = hex(raw.digest.digest())
            source.record?.let { rec ->
                if (!rec.sha256.equals(sha, ignoreCase = true)) {
                    return InspectOutcome.Failed("the file does not match its checksum — it was changed or damaged in transfer")
                }
            }
            cancel.check()

            // Pass 1: list the members; the app audits the list BEFORE anything is extracted.
            runJob(BackupJob.Op.LIST, id, stage, cancel, onProgress) { st -> st.phase ?: "auditing" }
            val listFile = File(stage, BackupJob.Files.LIST)
            val problem = listFile.bufferedReader().use { ArchiveAudit.check(it.lineSequence()) }
            listFile.delete()
            if (problem != null) return InspectOutcome.Failed("this backup is not safe to restore: $problem")
            cancel.check()

            // Pass 2: extract ONLY the manifest and meta files.
            runJob(BackupJob.Op.INSPECT, id, stage, cancel, onProgress) { st -> st.phase ?: "reading" }

            val metaDir = File(stage, "${BackupJob.Files.INSPECT_DIR}/${BackupPlan.META_DIR}")
            val manifest = BackupCodecs.decodeManifest(readText(File(metaDir, "manifest.json")))
                ?: return InspectOutcome.Failed("this backup has no readable manifest")
            // An Alpine-era backup lists apk names, which do not exist in Debian: its files restore, its
            // package list is not offered (the compatibility warning says so).
            val packages = PkgList.parse(readText(File(metaDir, "pkg-manual")))
            val appSettings = File(metaDir, "appdata").listFiles().orEmpty()
                .map { it.name.removeSuffix(".preferences_pb") }
                .filter { it in AppSettingsFiles.NAMES }
            val distro = readText(File(env.rootfs, "etc/debian_version"))?.trim()?.let { "debian $it" }
            val compat = Compatibility.check(source.record, manifest, guestArch(), distro)
            keep = true
            return InspectOutcome.Ready(id, header, manifest, source.record, packages, appSettings, compat)
        } catch (_: CancelledException) {
            return InspectOutcome.Cancelled
        } catch (e: Exception) {
            return InspectOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (!keep) {
                inUse.remove(id)
                deleteTree(stage)
            }
        }
    }

    // ----------------------------------------------------------------- apply

    fun apply(
        stageId: String,
        scopes: Set<BackupScope>,
        mode: RestoreMode,
        restoreAppSettings: Boolean,
        cancel: CancelToken,
        onProgress: (Progress) -> Unit,
    ): ApplyOutcome {
        val stage = stageDir(stageId)
        if (!File(stage, BackupJob.Files.PAYLOAD).isFile) return ApplyOutcome.Failed("the staged backup is gone — choose the file again")
        return try {
            val members = RestorePlan.members(scopes)
            val dest = RestorePlan.destination(mode, env.now())
            File(stage, BackupJob.Files.MEMBERS).writeText(lines(members))
            File(stage, BackupJob.Files.DEST).writeText(dest)
            val wantApp = restoreAppSettings && BackupScope.APP_SETTINGS in scopes && env.datastoreDir != null
            File(stage, BackupJob.Files.APPDATA_MEMBERS).writeText(if (wantApp) "${BackupPlan.META_DIR}/appdata\n" else "")

            runJob(BackupJob.Op.APPLY, stageId, stage, cancel, onProgress) { st -> st.phase ?: "restoring" }
            cancel.check()

            val restoredSettings = if (wantApp) copyAppSettings(stage) else emptyList()
            ApplyOutcome.Applied(dest, members, restoredSettings)
        } catch (_: CancelledException) {
            ApplyOutcome.Cancelled
        } catch (e: Exception) {
            ApplyOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Copy the captured DataStore files into place — ONLY allow-listed names
     * (an archive can never choose a path), the existing file kept as
     * `.pre-restore`. Returns the names restored.
     */
    private fun copyAppSettings(stage: File): List<String> {
        val ds = env.datastoreDir ?: return emptyList()
        val src = File(stage, "${BackupJob.Files.APPDATA_OUT}/${BackupPlan.META_DIR}/appdata")
        val restored = ArrayList<String>()
        ds.mkdirs()
        for (n in AppSettingsFiles.NAMES) {
            val from = File(src, AppSettingsFiles.fileName(n))
            if (!from.isFile) continue
            val to = File(ds, AppSettingsFiles.fileName(n))
            if (to.isFile) {
                // Keep EVERY previous version: a second restore must not destroy the first one's copy.
                var keepAs = File(ds, "${to.name}.pre-restore")
                if (keepAs.exists()) keepAs = File(ds, "${to.name}.pre-restore-${env.now()}")
                to.copyTo(keepAs, overwrite = false)
            }
            from.copyTo(to, overwrite = true)
            restored += n
        }
        return restored
    }

    // ------------------------------------------------------ packages & tools

    /** `apt-get install` the backup's package list (needs network). */
    fun reinstallPackages(stageId: String, cancel: CancelToken, onProgress: (Progress) -> Unit): StepOutcome {
        val stage = stageDir(stageId)
        return try {
            val world = PkgList.parse(readText(File(stage, "${BackupJob.Files.INSPECT_DIR}/${BackupPlan.META_DIR}/pkg-manual")))
            if (world.isEmpty()) return StepOutcome.Done("the backup lists no packages")
            File(stage, BackupJob.Files.SCRIPT).writeText(BackupJob.SCRIPT)
            File(stage, BackupJob.Files.WORLD).writeText(lines(world))
            runJob(BackupJob.Op.REINSTALL, stageId, stage, cancel, onProgress) { "installing ${world.size} packages" }
            StepOutcome.Done("${world.size} packages")
        } catch (_: CancelledException) {
            StepOutcome.Cancelled
        } catch (e: Exception) {
            StepOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Install zstd (one small Debian package) — a faster, smaller codec than the gzip fallback. */
    fun installZstd(cancel: CancelToken, onProgress: (Progress) -> Unit): StepOutcome {
        val id = env.newId()
        val stage = stageDir(id)
        inUse += id
        return try {
            stage.mkdirs()
            File(stage, BackupJob.Files.SCRIPT).writeText(BackupJob.SCRIPT)
            runJob(BackupJob.Op.INSTALL_ZSTD, id, stage, cancel, onProgress) { "installing zstd" }
            StepOutcome.Done()
        } catch (_: CancelledException) {
            StepOutcome.Cancelled
        } catch (e: Exception) {
            StepOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            inUse.remove(id)
            deleteTree(stage)
        }
    }

    // ---------------------------------------------------------------- verify

    /** Re-read a stored file and compare its sha256 with its sidecar — no passphrase needed. */
    fun verify(storage: BackupStorage, backup: StoredBackup, cancel: CancelToken, onProgress: (Progress) -> Unit): VerifyOutcome {
        val record = backup.record ?: return VerifyOutcome.NoRecord
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            DigestInputStream(storage.openRead(backup.name), digest).use { input ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    cancel.check()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    onProgress(Progress("checking", total, record.sizeBytes))
                }
            }
            val actual = hex(digest.digest())
            if (actual.equals(record.sha256, ignoreCase = true)) VerifyOutcome.Match(total) else VerifyOutcome.Mismatch(record.sha256, actual)
        } catch (_: CancelledException) {
            VerifyOutcome.Cancelled
        } catch (e: Exception) {
            VerifyOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ----------------------------------------------------------------- stages

    /** Stage ids left by an earlier run that never finished (app killed mid-job). */
    fun leftoverStages(): List<String> =
        File(env.rootfs, "tmp/psbak").listFiles().orEmpty()
            .filter { it.isDirectory && BackupJob.isValidStageId(it.name) && it.name !in inUse }
            .map { it.name }

    /** Delete a stage — refused while its job is running, so CLEAN UP can never pull the floor from a live backup. */
    fun discardStage(id: String) {
        if (!BackupJob.isValidStageId(id) || id in running) return
        inUse.remove(id)
        deleteTree(stageDir(id))
    }

    // ------------------------------------------------------------------- jobs

    private fun runJob(
        op: BackupJob.Op,
        id: String,
        stage: File,
        cancel: CancelToken,
        onProgress: (Progress) -> Unit,
        label: (JobState) -> String,
    ): JobState {
        // A previous job in this stage may have left its pid/exit/error: a stale pid would be signalled on cancel.
        listOf(BackupJob.Files.EXIT, BackupJob.Files.PID, BackupJob.Files.ERROR, BackupJob.Files.PHASE).forEach { File(stage, it).delete() }
        this.running += id
        try {
            return runJobStarted(op, id, stage, cancel, onProgress, label)
        } finally {
            this.running -= id
        }
    }

    private fun runJobStarted(
        op: BackupJob.Op,
        id: String,
        stage: File,
        cancel: CancelToken,
        onProgress: (Progress) -> Unit,
        label: (JobState) -> String,
    ): JobState {
        val running = runner.start(BackupJob.argv(op, BackupJob.guestStage(id)))
        val result = AtomicReference<JobResult?>()
        val waiter = Thread({
            result.set(
                try {
                    running.waitFor(JOB_TIMEOUT_MS)
                } catch (t: Throwable) {
                    JobResult(null, error = t.message ?: t.javaClass.simpleName)
                },
            )
        }, "psbak-job").apply { isDaemon = true; start() }

        while (waiter.isAlive) {
            if (cancel.cancelled) {
                JobState.read(stage).pid?.let { runCatching { signaller.terminateTree(it) } }
                runCatching { running.destroy() }
                waiter.join(3_000L)
                throw CancelledException()
            }
            val st = JobState.read(stage)
            onProgress(Progress(label(st), st.payloadBytes))
            waiter.join(POLL_MS)
        }
        val st = JobState.read(stage)
        val res = result.get()
        // The exit file is written LAST by the script; without it the job died.
        if (st.exitCode == null) {
            throw JobFailure(res?.error ?: res?.stderrTail ?: "the job ended without finishing (exit ${res?.exitCode})")
        }
        if (st.exitCode != 0) throw JobFailure(st.error ?: "the job failed (exit ${st.exitCode})")
        return st
    }

    // ---------------------------------------------------------------- helpers

    private fun copy(input: InputStream, output: OutputStream, cancel: CancelToken, onBytes: (Long) -> Unit) {
        val buf = ByteArray(COPY_BUFFER)
        var total = 0L
        var lastReport = 0L
        while (true) {
            cancel.check()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            if (total - lastReport >= REPORT_EVERY) {
                lastReport = total
                onBytes(total)
            }
        }
        onBytes(total)
    }

    private val REPORT_EVERY = 1024 * 1024L

    private fun lines(items: List<String>): String = if (items.isEmpty()) "" else items.joinToString("\n", postfix = "\n")

    private fun readText(f: File): String? = try {
        f.takeIf { it.isFile }?.readText()
    } catch (_: IOException) {
        null
    }

    private fun readLines(f: File): List<String> = readText(f).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Delete a staged tree WITHOUT following symlinks (the rootfs is full of them). */
    private fun deleteTree(dir: File) {
        if (!dir.exists()) return
        try {
            java.nio.file.Files.walkFileTree(dir.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    java.nio.file.Files.delete(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(d: java.nio.file.Path, exc: IOException?): java.nio.file.FileVisitResult {
                    java.nio.file.Files.delete(d)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: java.nio.file.Path, exc: IOException?): java.nio.file.FileVisitResult =
                    java.nio.file.FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // best effort — a leftover stage is reported by [leftoverStages], never silently trusted
        }
    }

    /** The raw stream of a source with a sha256 over every byte read from it. */
    private class DigestSource(input: InputStream) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        val stream: DigestInputStream = DigestInputStream(input, digest)
    }

    /** Fill [into] from the stream; returns how many bytes arrived (less than the size = EOF). */
    private fun InputStream.readNBytesCompat(into: ByteArray): Int {
        var off = 0
        while (off < into.size) {
            val n = read(into, off, into.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}

/** Counts and hashes every byte that reaches the storage — the stored file's identity. */
private class CountingDigestOutputStream(private val out: OutputStream, private val digest: MessageDigest) : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        digest.update(b.toByte())
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        digest.update(b, off, len)
        count += len
    }

    override fun flush() = out.flush()
    override fun close() = out.close()
}
