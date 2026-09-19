package app.pocketshell.widget.git

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/**
 * M8.5 — the plugin entry point: the catalog's git artifact carries this
 * class; the host instantiates it through [WidgetPlugin] and receives the
 * FULL Git application (compiled code, not a data manifest).
 */
class GitPlugin : WidgetPlugin {

    override val pluginId: String = "git"
    override val pluginVersion: String = "2.0.0"

    override fun create(androidContext: Context): HomeApplication = GitApp
}
