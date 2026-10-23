package app.pocketshell.widget.packages

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/** The plugin entry point: Packages & Environment. */
class PackagesPlugin : WidgetPlugin {

    override val pluginId: String = "packages"
    override val pluginVersion: String = "1.0.0"

    override fun create(androidContext: Context): HomeApplication = PkgApp
}
