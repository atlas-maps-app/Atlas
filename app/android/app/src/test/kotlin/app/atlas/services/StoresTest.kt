package app.atlas.services

import androidx.datastore.core.FileStorage
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.atlas.geo.LatLon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** The settings and key stores, on DataStore files in a temporary folder. */
class StoresTest {
    private val dir: File = Files.createTempDirectory("atlas-stores").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun theCameraAndUnitsAreKept() = runBlocking {
        val s = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })
        assertNull(s.camera.first())
        assertEquals(Units.METRIC, s.units.first())
        val cam = SavedCamera(LatLon(52.37452, 4.88377), 16.5, 20.0, 50.0)
        s.saveCamera(cam)
        assertEquals(cam, s.camera.first())
        s.set(SettingsKeys.UNITS, Units.NAUTICAL.name)
        assertEquals(Units.NAUTICAL, s.units.first())
        val mine = stringPreferencesKey("mine.voice")
        assertEquals("warm", s.flow(mine, "warm").first())
        s.set(mine, "calm")
        assertEquals("calm", s.flow(mine).first())
        s.set(mine, null)
        assertNull(s.flow(mine).first())
    }

    @Test
    fun keysAreReadAtOnceAndBlankRemoves() = runBlocking {
        val k = KeyStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "keys.preferences_pb") }, scope)
        assertNull(k.key("ocm"))
        k.set("ocm", "  abc123 ")
        assertEquals("abc123", k.key("ocm"))
        k.set("ocm", " ")
        assertNull(k.key("ocm"))
        k.set("foursquare", "f")
        k.clear()
        assertNull(k.key("foursquare"))
    }

    @Test
    fun aKeySavedAsTheFileIsFirstReadShowsAtOnce() = runBlocking {
        val file = HeldWrites(File(dir, "keys.preferences_pb"))
        val store = PreferenceDataStoreFactory.create(file, scope = scope)
        store.edit { it[stringPreferencesKey("ocm")] = "old" }
        // the keys are read as the store is made, on a thread held up until the key saved below is under way
        val paused = CountDownLatch(1)
        val thread = Executors.newSingleThreadExecutor().apply { execute { paused.await() } }
        val reading = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher())
        try {
            val k = KeyStore(store, reading)
            file.hold()
            val saving = launch(Dispatchers.IO) { k.set("ocm", "abc123") }
            file.waiting.await()
            paused.countDown()
            withTimeout(5_000) { k.keys.first { it != null } }
            file.release()
            saving.join()
            // shown once saved, where DataStore's data flow dropped the write for good as no newer than that first read
            assertEquals(mapOf("ocm" to "abc123"), k.keys.value)
            assertEquals("abc123", k.key("ocm"))
        } finally {
            reading.cancel()
            thread.shutdown()
        }
        // everything deleted as the keys are first used after a start (Privacy's delete everything): gone at once, for
        // the screens and for the providers
        val again = KeyStore(store, scope)
        again.clear()
        assertEquals(emptyMap<String, String>(), again.keys.value)
        assertNull(again.key("ocm"))
    }

    @Test
    fun aSettingWrittenAsAFlowFirstReadsTheFileShowsInIt() = runBlocking {
        val file = HeldWrites(File(dir, "settings.preferences_pb"))
        val s = SettingsStore(PreferenceDataStoreFactory.create(file, scope = scope))
        val hours = stringPreferencesKey("go.tacho")
        s.set(hours, "1 h")
        // a write held where DataStore has raised its version and the file has not taken the write yet
        file.hold()
        val writing = launch(Dispatchers.IO) { s.set(hours, "2 h") }
        file.waiting.await()
        // a flow begun then, as GoHub's tacho first shows the hours, has them as they were...
        val seen = s.flow(hours).stateIn(scope, SharingStarted.Eagerly, null)
        withTimeout(5_000) { seen.first { it != null } }
        file.release()
        writing.join()
        // ...and the write as it lands, which DataStore's own data flow dropped for good as no newer than its first read
        withTimeout(5_000) { seen.first { it == "2 h" } }
        assertEquals("2 h", s.flow(hours).first())
    }
}

/**
 * A preferences file whose writes can be held where DataStore has raised its version and the file has not taken the
 * write yet: the moment a first read of DataStore's data flow takes the old file for the new version.
 */
private class HeldWrites(file: File) : Storage<Preferences> {
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
