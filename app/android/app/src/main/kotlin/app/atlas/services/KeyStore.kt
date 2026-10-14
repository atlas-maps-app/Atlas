package app.atlas.services

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.atlas.providers.Keys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * The provider keys the user pasted in Data sources & keys, kept on this phone only (a DataStore file of its own, not
 * backed up) and never logged. It is the providers' [Keys]: a key typed in Settings works at once.
 */
class KeyStore(private val store: DataStore<Preferences>, scope: CoroutineScope) : Keys {
    /** What [key] answers from: the file as last read or written here. */
    @Volatile
    private var current: Map<String, String>? = null

    private val data: Flow<Map<String, String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> toMap(p).also { current = it } }

    /** Every key by provider id, null until the file has been read. */
    val keys: StateFlow<Map<String, String>?> = data.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The key for [providerId]. Before the file has been read once this reads it (a few hundred bytes), so a provider
     * asked at start-up does not wrongly report that it needs a key.
     */
    override fun key(providerId: String): String? {
        val now = current ?: keys.value ?: runBlocking { data.first() }.also { current = it }
        return now[providerId]
    }

    /** Saves [key] for [providerId]; blank removes it. The change is visible to [key] as soon as this returns. */
    suspend fun set(providerId: String, key: String?) {
        val after = store.edit {
            val k = stringPreferencesKey(providerId)
            if (key.isNullOrBlank()) it.remove(k) else it[k] = key.trim()
        }
        current = toMap(after)
    }

    /** Forgets every key (Privacy · delete everything). */
    suspend fun clear() {
        current = toMap(store.edit { it.clear() })
    }

    private fun toMap(p: Preferences): Map<String, String> =
        p.asMap().entries.associate { (k, v) -> k.name to v.toString() }.filterValues { it.isNotBlank() }
}
