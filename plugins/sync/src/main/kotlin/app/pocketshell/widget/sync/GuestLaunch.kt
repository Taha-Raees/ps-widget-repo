package app.pocketshell.widget.sync

import app.pocketshell.runtime.GuestExecutionProfile
import app.pocketshell.runtime.RuntimeProcessLauncher
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * The ONE place the plugin builds a guest launch spec, tolerant of host drift.
 *
 * `RuntimeProcessLauncher.buildLaunchSpec` grows trailing default parameters
 * as the host evolves (guestCwd, extraBinds, engine, …). Kotlin compiles a
 * call that omits them to the synthetic `buildLaunchSpec$default`, whose
 * descriptor changes with EVERY added parameter — so a plugin built against
 * an older host API jar dies on a newer host with
 * `NoSuchMethodError: buildLaunchSpec$default` (Git and Servers hit exactly
 * this; Sync's probe, dry run and run would have failed the same way).
 *
 * This resolves the real method at runtime and passes the parameters the
 * plugin cares about positionally; any trailing parameter it does not know
 * is given its neutral default (null / empty list / the first enum constant,
 * which is `PROOT` for the engine). [extraBinds] lands in the first
 * `List` parameter after the nine known ones (host order: guestCwd,
 * extraBinds, engine).
 */
internal object GuestLaunch {

    private const val TERM = "xterm-256color"
    private const val KNOWN_PARAMS = 9

    fun spec(
        nativeLibraryDir: String,
        rootfsDir: File,
        hostCwd: File,
        prootTmpDir: File,
        guestCommand: List<String>,
        profile: GuestExecutionProfile,
        extraBinds: List<String> = emptyList(),
    ): RuntimeProcessLauncher.LaunchSpec {
        val method = RuntimeProcessLauncher::class.java.methods
            .filter { it.name == "buildLaunchSpec" }
            .maxByOrNull { it.parameterCount }
            ?: throw IllegalStateException("host has no RuntimeProcessLauncher.buildLaunchSpec")
        check(method.parameterCount >= KNOWN_PARAMS) {
            "host buildLaunchSpec has ${method.parameterCount} parameters; expected at least $KNOWN_PARAMS"
        }
        val args = arrayOfNulls<Any?>(method.parameterCount)
        args[0] = nativeLibraryDir
        args[1] = rootfsDir
        args[2] = hostCwd
        args[3] = prootTmpDir
        args[4] = TERM
        args[5] = guestCommand
        args[6] = null // apkCacheDir
        args[7] = profile
        args[8] = emptyList<String>() // sysDataBinds
        var bindsPlaced = false
        for (i in KNOWN_PARAMS until args.size) {
            val type = method.parameterTypes[i]
            args[i] = if (!bindsPlaced && List::class.java.isAssignableFrom(type)) {
                bindsPlaced = true
                extraBinds
            } else {
                neutralDefault(type)
            }
        }
        try {
            return method.invoke(RuntimeProcessLauncher, *args) as RuntimeProcessLauncher.LaunchSpec
        } catch (e: InvocationTargetException) {
            // Surface the host's own failure (e.g. the precondition message).
            throw e.targetException
        }
    }

    internal fun neutralDefault(type: Class<*>): Any? {
        if (List::class.java.isAssignableFrom(type)) return emptyList<Any?>()
        val constants = (if (type.isEnum) type.enumConstants else null) ?: return null
        return constants.firstOrNull { (it as Enum<*>).name == "PROOT" } ?: constants.firstOrNull()
    }
}
