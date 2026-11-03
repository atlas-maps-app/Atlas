package app.atlas.services

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.atlas.providers.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The provider keys the user pasted in Data sources & keys, kept on this phone only (a DataStore file of its own, not
 * backed up) and never logged. It is the providers' [Keys]: a key typed in Settings works at once.
 */
class KeyStore(private val store: DataStore<Preferences>, scope: CoroutineScope) : Keys {
    /**
     * The keys as their file holds them, null until read: read once, then taken from each write as it returns, one at a
     * time ([writing]). This class is the file's only writer, so they never lag a write, where a collector of DataStore's
     * data flow can for good (SettingsStore says how): a key saved as the file is first read, or one deleted with
     * everything as the keys are first used, could have stayed as it was for the screens and the providers.
     */
    private val held = MutableStateFlow<Map<String, String>?>(null)
    private val writing = Mutex()

    /** Every key by provider id, null until the file has been read. */
    val keys: StateFlow<Map<String, String>?> = held.asStateFlow()

    init {
        // read at once for the screens
        scope.launch { read() }
    }

    /**
     * The key for [providerId]. Before the file has been read once this reads it (a few hundred bytes), so a provider
     * asked at start-up does not wrongly report that it needs a key.
     */
    override fun key(providerId: String): String? = (held.value ?: runBlocking { read() })[providerId]

    /** Saves [key] for [providerId]; blank removes it. The change is visible to [key] as soon as this returns. */
    suspend fun set(providerId: String, key: String?) {
        edit {
            val k = stringPreferencesKey(providerId)
            if (key.isNullOrBlank()) it.remove(k) else it[k] = key.trim()
        }
    }

    /** Forgets every key (Privacy · delete everything). */
    suspend fun clear() {
        edit { it.clear() }
    }

    /** The keys as the file holds them, read the first time (a file that cannot be read holds none). */
    private suspend fun read(): Map<String, String> = held.value ?: run {
        val read = try {
            toMap(store.data.first())
        } catch (_: IOException) {
            emptyMap()
        }
        // a write that returned meanwhile holds them newer
        held.compareAndSet(null, read)
        held.value!!
    }

    /** Writes [change] to the file and holds the keys it then has, as SettingsStore.edit does. */
    private suspend fun edit(change: (MutablePreferences) -> Unit) {
        withContext(Dispatchers.IO) {
            writing.withLock { withContext(NonCancellable) { held.value = toMap(store.edit { change(it) }) } }
        }
    }

    private fun toMap(p: Preferences): Map<String, String> =
        p.asMap().entries.associate { (k, v) -> k.name to v.toString() }.filterValues { it.isNotBlank() }
}
