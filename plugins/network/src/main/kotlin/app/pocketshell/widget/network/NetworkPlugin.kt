package app.pocketshell.widget.network

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/**
 * The plugin entry point. Network is a cockpit for the networking CLIs: ssh hosts,
 * keys, port forwards, folder sharing and connectivity checks.
 */
class NetworkPlugin : WidgetPlugin {

    override val pluginId: String = "network"
    override val pluginVersion: String = "1.1.0"

    override fun create(androidContext: Context): HomeApplication = NetworkApp
}
