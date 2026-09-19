package app.pocketshell.widget.sync

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/**
 * M8.5 — the plugin entry point for the SYNC application (RUN NOW, path
 * completion, Android-storage destinations — the full compiled app).
 */
class SyncPlugin : WidgetPlugin {

    override val pluginId: String = "sync"
    override val pluginVersion: String = "2.0.0"

    override fun create(androidContext: Context): HomeApplication = SyncApp
}
