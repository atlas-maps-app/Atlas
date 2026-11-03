package app.atlas.testing

import androidx.datastore.core.FileStorage
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * A preferences file whose writes can be held where DataStore has taken the change and raised its version, and the file
 * has not taken the write yet: the moment a first read of DataStore's data flow takes the old file for the new version,
 * and the moment a caller cancelled no longer hears of a write the file takes all the same.
 */
class HeldWrites(file: File) : Storage<Preferences> {
    private val inner = FileStorage(PreferencesFileSerializer) { file }

    @Volatile
    private var gate: CompletableDeferred<Unit>? = null

    /** Done as a held write waits for [release]. */
    val waiting = CompletableDeferred<Unit>()

    /** Holds the writes from now until [release]. */
    fun hold() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
    }

    override fun createConnection(): StorageConnection<Preferences> {
        val c = inner.createConnection()
        return object : StorageConnection<Preferences> by c {
            override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) = c.writeScope {
                val w = this
                object : WriteScope<Preferences> by w {
                    override suspend fun writeData(value: Preferences) {
                        gate?.let { g ->
                            waiting.complete(Unit)
                            g.await()
                        }
                        w.writeData(value)
                    }
                }.block()
            }
        }
    }
}
