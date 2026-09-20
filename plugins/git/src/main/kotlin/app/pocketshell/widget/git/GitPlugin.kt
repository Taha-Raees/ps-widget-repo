package app.pocketshell.widget.git

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/**
 * M9 — the plugin entry point: the catalog's git artifact carries this
 * class; the host instantiates it through [WidgetPlugin] and receives the
 * FULL Git application (compiled code, not a data manifest). 3.0.0 is the
 * information-architecture redesign: a navigation-stack Git workstation
 * (home → repository tabs → detail flows) with staged operations — see
 * GitScreens.kt for the screen model and GitOps.kt for the operation
 * layer's discipline.
 */
class GitPlugin : WidgetPlugin {

    override val pluginId: String = "git"
    override val pluginVersion: String = "3.0.0"

    override fun create(androidContext: Context): HomeApplication = GitApp
}
