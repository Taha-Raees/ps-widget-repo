package app.pocketshell.widget.guiapps

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/** The plugin entry point: the GUI App Library. */
class GuiAppsPlugin : WidgetPlugin {

    override val pluginId: String = "guiapps"
    override val pluginVersion: String = "2.0.0"

    override fun create(androidContext: Context): HomeApplication = GuiApp
}
