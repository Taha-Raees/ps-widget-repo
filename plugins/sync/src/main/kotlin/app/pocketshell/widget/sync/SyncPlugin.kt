package app.pocketshell.widget.sync

import android.content.Context
import app.pocketshell.widget.HomeApplication
import app.pocketshell.widget.plugin.WidgetPlugin

/**
 * The plugin entry point for BACKUP & RESTORE (id stays `sync`, so installs update in place): your Linux home,
 * package list and a small allow-list of app settings as one portable, optionally encrypted file you can choose
 * from storage on a new phone.
 *
 * 3.2.0: renamed from "Sync". The rsync / rclone profiles (the SYNC tab) are switched off - see
 * [SyncApp.PROFILES_ENABLED]; saved profiles are not deleted.
 */
class SyncPlugin : WidgetPlugin {

    override val pluginId: String = "sync"
    override val pluginVersion: String = "4.0.0"

    override fun create(androidContext: Context): HomeApplication = SyncApp
}
