package app.atlas.mapcore

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * One core at a time in a process (plan-vector D5.10, [MapCore]): a core's close holds the lock its start
 * ([MapCore.shared], [MapCore.create]), the facts without a core and the guard's reset hold, and forgets the process's
 * core only once its free has returned, so no other core starts while the close's stage runs; and create refuses while
 * the process's core is alive (the crash guard's marker is one file a process's cores would share). The test's own
 * free takes the native one's place; no core is made.
 */
class MapCoreCloseTest {
    private val processField = MapCore::class.java.getDeclaredField("process").apply { isAccessible = true }

    /** A MapCore over a handle no native call sees (the test frees it itself). */
    private fun core(): MapCore = MapCore::class.java
        .getDeclaredConstructor(java.lang.Long.TYPE, CoreConfig::class.java)
        .apply { isAccessible = true }
        .newInstance(42L, CoreConfig(cacheDir = "", guardDir = "", system = "jvm-test"))

    @After
    fun forget() {
        processField.set(null, null)
        MapCore::class.java.getDeclaredField("refusal").apply { isAccessible = true }.set(null, "")
    }

    @Test
    fun closeHoldsTheCompanionsLockAndForgetsTheProcesssCoreOnlyOnceItIsFreed() {
        val core = core()
        processField.set(null, core) // as shared() keeps it
        val seenWhileFreeing = AtomicReference<MapCore?>()
        val freed = CountDownLatch(1)
        val closer: Thread
        synchronized(MapCore.Companion) {
            // The lock held here, as a start, VK facts without a core or a reset holds it: the close waits.
            closer = thread(name = "atlas-test-close") {
                core.close { handle ->
                    check(handle == 42L)
                    seenWhileFreeing.set(MapCore.sharedOrNull())
                    freed.countDown()
                }
            }
            assertFalse("the close freed the core under another's lock", freed.await(300, TimeUnit.MILLISECONDS))
            assertSame(core, MapCore.sharedOrNull())
        }
        assertTrue(freed.await(30, TimeUnit.SECONDS))
        closer.join()
        assertSame("the process's core was forgotten before it was freed", core, seenWhileFreeing.get())
        assertNull(MapCore.sharedOrNull())
        // Closed once: a second close frees nothing, and leaves another process core alone.
        val other = core()
        processField.set(null, other)
        core.close { throw AssertionError("freed twice") }
        assertSame(other, MapCore.sharedOrNull())
    }

    @Test
    fun noCoreIsMadeBesideTheProcesssOwn() {
        // The library counted as loaded, so that create reaches its own check before any native call.
        val loaded = Native::class.java.getDeclaredField("state").apply { isAccessible = true }
        val was = loaded.get(null)
        loaded.set(null, "")
        try {
            processField.set(null, core())
            assertNull(MapCore.create(CoreConfig(cacheDir = "", guardDir = "", system = "jvm-test")))
            assertTrue(MapCore.lastError(), MapCore.lastError().contains("one core runs in a process at a time"))
        } finally {
            loaded.set(null, was)
        }
    }

    @Test
    fun aCloseWhoseFreeFailsStillForgetsTheProcesssCore() {
        val core = core()
        processField.set(null, core)
        val failed = runCatching { core.close { throw IllegalStateException("the native free failed") } }
        assertTrue(failed.isFailure)
        assertNull(MapCore.sharedOrNull())
    }
}
