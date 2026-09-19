package app.pocketshell.widget.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * M8.4 — the SYNC/BACKUP application's OWN DataStore file, per the
 * established per-domain pattern (home_widgets, settings, notifications,
 * companion, todo_store each own one file). ONE file ("sync_profiles"),
 * ONE key ("sync_profiles"): a JSON array of [SyncProfile] via
 * [SyncStoreCodec].
 *
 * Writes go through DataStore's own IO executor (the `edit` transaction —
 * read-modify-write is atomic; no main-thread IO anywhere). Reads are a
 * cold Flow that emits on change only — no polling. There is no seed: an
 * empty store means the user has no profiles, and showing fake ones would
 * be a lie.
 *
 * SECURITY: this store holds PATHS and TIMESTAMPS only — by construction
 * it cannot hold credentials, because [SyncProfile] has no credential
 * field to persist.
 */
class SyncRepository(private val context: Context) {

    private val profilesKey = stringPreferencesKey("sync_profiles")

    val profiles: Flow<List<SyncProfile>> =
        context.syncStore.data.map { prefs -> SyncStoreCodec.decode(prefs[profilesKey]) }

    suspend fun add(profile: SyncProfile) = mutate { SyncProfiles.upsert(it, profile) }

    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** Atomic read-modify-write through DataStore's `edit` (IO executor). */
    private suspend fun mutate(op: (List<SyncProfile>) -> List<SyncProfile>) {
        context.syncStore.edit { prefs ->
            prefs[profilesKey] = SyncStoreCodec.encode(op(SyncStoreCodec.decode(prefs[profilesKey])))
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        fun now(): Long = System.currentTimeMillis()
    }
}

private val Context.syncStore by preferencesDataStore(name = "sync_profiles")
