package app.pocketshell.widget.git

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
 * `NoSuchMethodError: buildLaunchSpec$default`.
 *
 * This resolves the real method at runtime and passes the nine parameters
 * the plugin cares about positionally; any trailing parameter it does not
 * know is given its neutral default (null / empty list / the first enum
 * constant, which is `PROOT` for the engine). The same artifact therefore
 * runs on the jar's host and on newer ones.
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
        for (i in KNOWN_PARAMS until args.size) {
            args[i] = neutralDefault(method.parameterTypes[i])
        }
        try {
            return method.invoke(RuntimeProcessLauncher, *args) as RuntimeProcessLauncher.LaunchSpec
        } catch (e: InvocationTargetException) {
            // Surface the host's own failure (e.g. the precondition message).
            throw e.targetException
        }
    }

    internal fun neutralDefault(type: Class<*>): Any? = when {
        List::class.java.isAssignableFrom(type) -> emptyList<Any?>()
        type.isEnum -> type.enumConstants.firstOrNull { (it as Enum<*>).name == "PROOT" }
            ?: type.enumConstants.firstOrNull()
        else -> null
    }
}
