package app.atlas.mapcore

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A core's native handle, which calls on any thread share and one [close] frees (plan-vector T9): every native call on
 * it runs whole before the handle is freed, or not at all. A call counts itself in and out, one atomic operation each:
 * no lock, nothing allocated and never a wait, so the gesture's and the bench's per-frame calls cost what they did.
 * [close] stops new calls first, then waits for the calls under way (a [MapCore.bench] that the render thread is
 * running, a [MapCore.trimMemory] from onTrimMemory), then frees the handle once; a second close waits for the first.
 *
 * The core's upcalls ([FrameSource], on its render thread at each paced vsync) do not come through here, so they cost
 * nothing more. Never close from inside a call or from an upcall: it would wait for itself.
 */
class CoreHandle(@PublishedApi internal val handle: Long) {
    private val calls = AtomicInteger(0) // the calls under way, and CLOSED once close has begun
    private val lock = ReentrantLock()
    private val settled = lock.newCondition()
    private var freed = false // under lock

    /** Whether close has begun: calls do nothing from then on. */
    val closed: Boolean get() = calls.get() and CLOSED != 0

    /** Runs [block] with the handle unless close has begun; then returns [whenClosed] without running it. */
    inline fun <T> call(whenClosed: T, block: (Long) -> T): T {
        if (!enter()) return whenClosed
        try {
            return block(handle)
        } finally {
            exit()
        }
    }

    /** Counts a call in, unless close has begun. */
    @PublishedApi
    internal fun enter(): Boolean {
        while (true) {
            val c = calls.get()
            if (c and CLOSED != 0) return false
            if (calls.compareAndSet(c, c + 1)) return true
        }
    }

    /** Counts a call out; the last one out after close began lets close go on. */
    @PublishedApi
    internal fun exit() {
        if (calls.decrementAndGet() == CLOSED) lock.withLock { settled.signalAll() }
    }

    /**
     * Stops new calls, waits for the calls under way, then frees the handle with [free], once. True for the close that
     * freed it; a close after it, or beside it, returns false once the handle is freed.
     */
    fun close(free: (Long) -> Unit): Boolean {
        while (true) {
            val c = calls.get()
            if (c and CLOSED != 0) {
                lock.withLock { while (!freed) settled.awaitUninterruptibly() }
                return false
            }
            if (calls.compareAndSet(c, c or CLOSED)) break
        }
        lock.withLock { while (calls.get() != CLOSED) settled.awaitUninterruptibly() }
        try {
            free(handle)
        } finally {
            lock.withLock {
                freed = true
                settled.signalAll()
            }
        }
        return true
    }

    private companion object {
        const val CLOSED = 1 shl 30
    }
}
