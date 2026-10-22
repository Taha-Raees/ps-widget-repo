package app.pocketshell.widget.servers

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/**
 * The plugin entry point: the catalog's servers artifact carries this
 * class; the host instantiates it through [WidgetPlugin] and receives the
 * FULL Servers application (compiled code, not the v1 data manifest).
 *
 * 2.0.0 is the manifest→plugin graduation (the same jump git and sync
 * took): the six-line proc.net.listen card becomes the server monitor —
 * verified endpoints, HTTP checks with session response history, process
 * facts, system health, and the stop action in the maximized tier.
 */
class ServersPlugin : WidgetPlugin {

    override val pluginId: String = "servers"
    override val pluginVersion: String = "2.0.0"

    override fun create(androidContext: Context): HomeApplication = ServersApp
}
