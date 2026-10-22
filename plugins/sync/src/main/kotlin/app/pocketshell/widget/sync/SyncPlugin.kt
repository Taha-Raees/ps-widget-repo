package app.pocketshell.widget.sync

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/**
 * M8.5 — the plugin entry point for the SYNC application (RUN NOW, path
 * completion, Android-storage destinations — the full compiled app).
 *
 * 3.0.0 adds BACKUP and RESTORE: your Linux home, package list and a small
 * allow-list of app settings as one portable, optionally encrypted file you
 * can choose from storage on a new phone. The rsync/rclone profiles remain.
 */
class SyncPlugin : WidgetPlugin {

    override val pluginId: String = "sync"
    override val pluginVersion: String = "3.0.0"

    override fun create(androidContext: Context): HomeApplication = SyncApp
}
