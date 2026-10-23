package app.pocketshell.widget.network

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/**
 * The plugin entry point. Network & Home Lab: find devices, watch servers, DNS / ping /
 * traceroute / port tools, and the ssh hosts, keys, tunnels and sharing it always had.
 */
class NetworkPlugin : WidgetPlugin {

    override val pluginId: String = "network"
    override val pluginVersion: String = "2.0.0"

    override fun create(androidContext: Context): HomeApplication = NetworkApp
}
