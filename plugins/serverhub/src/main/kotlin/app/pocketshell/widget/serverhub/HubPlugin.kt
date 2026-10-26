package app.pocketshell.widget.serverhub

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/** The plugin entry point: the Personal Server Hub. */
class ServerHubPlugin : WidgetPlugin {

    override val pluginId: String = "serverhub"
    override val pluginVersion: String = "1.0.1"

    override fun create(androidContext: Context): HomeApplication = HubApp
}
