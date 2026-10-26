package app.pocketshell.widget.sync

import java.io.File

/**
 * The guest side of a backup or restore: ONE constant shell script
 * ([BackupJob.SCRIPT]) that is written into the stage directory and run as
 * `sh job.sh <op> <stage>`.
 *
 * INJECTION DISCIPLINE (the contract test pins it): the script text never
 * contains user data and the command line never contains user data. Every
 * variable input — which paths to include or exclude, which members to
 * extract, where to extract, which codec — is a FILE in the stage dir that the
 * script reads (`-T`, `-X`, `cat`). A path such as `root/$(reboot)` is just a
 * line in a list file; no shell ever re-parses it.
 *
 * The stage lives in the guest at `/tmp/psbak/<id>/`, which the app sees as
 * `<rootfs>/tmp/psbak/<id>/` (same files, host-side), so the engine can write
 * inputs, copy the payload in and out, and POLL progress without a guest exec
 * per tick: the script publishes `phase` (what it is doing) and `exit`
 * (written last, atomically) and the growing `payload` file is its own progress.
 *
 * Verified on the device with the guest's busybox tar/gzip: excludes skip a
 * whole directory (`*&#47;node_modules`), `-T` lists may name directories and
 * paths with spaces, symlinks (absolute and relative) and modes survive, and
 * a missing include path makes tar fail — so the script FILTERS the include
 * list to paths that exist (e.g. `/opt` on a fresh guest).
 */
object BackupJob {

    /** What the script can do. */
    enum class Op(val id: String) {
        BACKUP("backup"),
        /** Pass 1: list every member (the app audits the list before anything is extracted). */
        LIST("list"),
        INSPECT("inspect"),
        APPLY("apply"),
        REINSTALL("reinstall"),
        INSTALL_ZSTD("installzstd"),
    }

    /** Where the stage lives, as the guest sees it. */
    const val GUEST_STAGE_ROOT = "/tmp/psbak"

    /** The staged file names (host and guest agree on them). */
    object Files {
        const val SCRIPT = "job.sh"
        const val CODEC = "codec"
        const val INCLUDE = "include.txt"
        const val EXCLUDE = "exclude.txt"
        const val MEMBERS = "members.txt"
        const val APPDATA_MEMBERS = "appdata-members.txt"
        const val DEST = "dest"
        const val WORLD = "world.txt"
        const val META_DIR = "meta"
        const val PAYLOAD = "payload"
        const val PHASE = "phase"
        const val EXIT = "exit"
        const val ERROR = "error"
        const val PID = "pid"
        const val SYSTEM = "system.txt"
        const val INSPECT_DIR = "inspect"
        const val APPDATA_OUT = "appdata-out"
        const val SKIPPED = "skipped.txt"
        const val WANT_PACKAGES = "want-packages"
        const val LIST = "list.txt"
    }

    /** `sh <stage>/job.sh <op> <stage>` — constant words plus the stage path (an app-generated id). */
    fun argv(op: Op, guestStage: String): List<String> =
        listOf("/bin/sh", "$guestStage/${Files.SCRIPT}", op.id, guestStage)

    fun guestStage(id: String): String = "$GUEST_STAGE_ROOT/$id"

    /** A stage id is app-generated; the shape is pinned so it can never smuggle a path. */
    fun isValidStageId(id: String): Boolean = Regex("^[a-z0-9]{6,32}$").matches(id)

    /**
     * The script. `§` stands for the shell's dollar sign (a Kotlin raw string
     * would interpolate it); [SCRIPT] is the substituted constant.
     */
    private val RAW = """
#!/bin/sh
# PocketShell backup job — all variable input is a FILE in the stage dir.
OP="§1"
S="§2"
R="§{PSBAK_ROOT:-}"
umask 077

phase() { printf '%s\n' "§1" > "§S/phase.tmp" && mv "§S/phase.tmp" "§S/phase"; }

# finish <exit code> [message] — the exit file is written LAST and atomically.
finish() {
  if [ -n "§{2:-}" ]; then printf '%s\n' "§2" > "§S/error"; fi
  printf '%s\n' "§1" > "§S/exit.tmp" && mv "§S/exit.tmp" "§S/exit"
  exit "§1"
}

echo "§§" > "§S/pid"

codec_setup() {
  CODEC="§(cat "§S/codec" 2>/dev/null)"
  case "§CODEC" in
    zstd) CZ="zstd -T0 -3 -q -c"; DZ="zstd -dc -q" ;;
    gzip) CZ="gzip -6 -c"; DZ="gzip -dc" ;;
    xz) CZ="xz -T0 -3 -c"; DZ="xz -dc" ;;
    *) finish 2 "unknown codec" ;;
  esac
}

# Decompress the payload into tar, remembering BOTH exit codes.
unpack() {
  rm -f "§S/dz.exit" "§S/tar.exit" "§S/tar.err"
  { §DZ < "§S/payload"; echo "§?" > "§S/dz.exit"; } | tar -x -p -f - "§@" 2> "§S/tar.err"
  echo "§?" > "§S/tar.exit"
  if [ "§(cat "§S/dz.exit" 2>/dev/null)" != 0 ] || [ "§(cat "§S/tar.exit")" != 0 ]; then
    finish 4 "§(tail -n 1 "§S/tar.err" 2>/dev/null)"
  fi
}

op_backup() {
  codec_setup
  phase preparing
  META="§R/tmp/psbak-meta"
  rm -rf "§META" && mkdir -p "§META" || finish 3 "cannot prepare the metadata folder"
  cp -a "§S/meta/." "§META/" 2>/dev/null
  if [ -f "§S/want-packages" ]; then
    # (the package list, meta/pkg-manual, was staged by the app and copied above)
    if [ -d "§R/etc/apt/sources.list.d" ]; then cat "§R"/etc/apt/sources.list.d/*.sources > "§META/apt-sources" 2>/dev/null; fi
  fi
  # system.txt: the architecture, then the distribution ("debian 13.7"; an Alpine-era backup holds its release).
  { uname -m; if [ -f "§R/etc/debian_version" ]; then echo "debian §(cat "§R/etc/debian_version")"; else cat "§R/etc/alpine-release" 2>/dev/null; fi; } > "§META/system.txt"
  cp "§META/system.txt" "§S/system.txt"

  # tar fails on an include path that does not exist (/opt on a fresh guest):
  # keep the ones that do, and say which were skipped.
  : > "§S/include.eff"
  : > "§S/skipped.txt"
  while IFS= read -r p; do
    if [ -e "§R/§p" ] || [ -L "§R/§p" ]; then printf '%s\n' "§p" >> "§S/include.eff"; else printf '%s\n' "§p" >> "§S/skipped.txt"; fi
  done < "§S/include.txt"

  phase archiving
  rm -f "§S/payload" "§S/tar.exit" "§S/tar.err"
  { tar -c -f - -C "§{R:-/}" -X "§S/exclude.txt" -T "§S/include.eff" 2> "§S/tar.err"; echo "§?" > "§S/tar.exit"; } | §CZ > "§S/payload"
  CRC="§?"
  rm -rf "§META"
  if [ "§(cat "§S/tar.exit" 2>/dev/null)" != 0 ]; then finish 4 "§(tail -n 1 "§S/tar.err" 2>/dev/null)"; fi
  if [ "§CRC" != 0 ]; then finish 5 "compression failed"; fi
  phase done
  finish 0
}

op_list() {
  codec_setup
  # Lists every member; because it reads the whole stream it also proves the archive is intact.
  phase auditing
  rm -f "§S/list.txt" "§S/dz.exit" "§S/tar.exit" "§S/tar.err"
  { §DZ < "§S/payload"; echo "§?" > "§S/dz.exit"; } | tar -t -v -f - > "§S/list.txt" 2> "§S/tar.err"
  echo "§?" > "§S/tar.exit"
  if [ "§(cat "§S/dz.exit" 2>/dev/null)" != 0 ] || [ "§(cat "§S/tar.exit")" != 0 ]; then
    finish 4 "§(tail -n 1 "§S/tar.err" 2>/dev/null)"
  fi
  phase done
  finish 0
}

# Runs only AFTER the app has audited the listing: extracts ONLY the manifest and meta files.
op_inspect() {
  codec_setup
  phase reading
  rm -rf "§S/inspect"; mkdir -p "§S/inspect"
  unpack -C "§S/inspect" tmp/psbak-meta
  phase done
  finish 0
}

op_apply() {
  codec_setup
  DEST="§(cat "§S/dest" 2>/dev/null)"
  [ -n "§DEST" ] || finish 2 "no destination"
  if [ -s "§S/members.txt" ]; then
    phase restoring
    mkdir -p "§R§DEST" || finish 3 "cannot create the destination"
    unpack -C "§R§DEST" -T "§S/members.txt"
  fi
  if [ -s "§S/appdata-members.txt" ]; then
    phase "app settings"
    rm -rf "§S/appdata-out"; mkdir -p "§S/appdata-out"
    unpack -C "§S/appdata-out" -T "§S/appdata-members.txt"
  fi
  phase done
  finish 0
}

op_reinstall() {
  phase installing
  set --
  while IFS= read -r line; do
    if [ -n "§line" ]; then set -- "§@" "§line"; fi
  done < "§S/world.txt"
  if [ "§#" -eq 0 ]; then phase done; finish 0; fi
  export DEBIAN_FRONTEND=noninteractive
  apt-get update > "§S/apt.log" 2>&1
  apt-get install -y -- "§@" >> "§S/apt.log" 2>&1
  RC="§?"
  if [ "§RC" != 0 ]; then finish "§RC" "§(tail -n 1 "§S/apt.log")"; fi
  phase done
  finish 0
}

op_installzstd() {
  phase installing
  export DEBIAN_FRONTEND=noninteractive
  apt-get install -y zstd > "§S/apt.log" 2>&1 || { apt-get update >> "§S/apt.log" 2>&1 && apt-get install -y zstd >> "§S/apt.log" 2>&1; }
  RC="§?"
  if [ "§RC" != 0 ]; then finish "§RC" "§(tail -n 1 "§S/apt.log")"; fi
  phase done
  finish 0
}

case "§OP" in
  backup) op_backup ;;
  list) op_list ;;
  inspect) op_inspect ;;
  apply) op_apply ;;
  reinstall) op_reinstall ;;
  installzstd) op_installzstd ;;
  *) finish 2 "unknown operation" ;;
esac
""".trimStart()

    val SCRIPT: String = RAW.replace('§', '$')
}

// ---------------------------------------------------------------- progress

/** What a running (or finished) job has published, read from its stage dir. */
data class JobState(
    /** What it is doing ("archiving", "restoring", …); null before it started. */
    val phase: String?,
    /** The exit code once finished — written last, so non-null means every other file is final. */
    val exitCode: Int?,
    /** One-line reason a non-zero exit left behind. */
    val error: String?,
    /** The growing payload (backup) — the byte progress. */
    val payloadBytes: Long,
    /** The job's own pid (host pid == guest pid; no pid namespace), for cancel. */
    val pid: Int?,
) {
    val finished: Boolean get() = exitCode != null
    val succeeded: Boolean get() = exitCode == 0

    companion object {
        fun read(stage: File): JobState {
            fun text(name: String): String? = try {
                File(stage, name).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
            return JobState(
                phase = text(BackupJob.Files.PHASE),
                exitCode = text(BackupJob.Files.EXIT)?.toIntOrNull(),
                error = text(BackupJob.Files.ERROR),
                payloadBytes = try {
                    File(stage, BackupJob.Files.PAYLOAD).takeIf { it.isFile }?.length() ?: 0L
                } catch (_: Exception) {
                    0L
                },
                pid = text(BackupJob.Files.PID)?.toIntOrNull(),
            )
        }
    }
}
