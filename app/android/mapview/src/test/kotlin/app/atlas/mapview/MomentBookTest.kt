package app.atlas.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MapController's moments: only a send the core took is the core's, and a failed one goes again. */
class MomentBookTest {
    private data class Key(val moment: String, val palette: Int)

    @Test
    fun aSendTheCoreTookIsTheCoresAndNotDueAgain() {
        val book = MomentBook<Key>()
        val a = Key("sunny", 1)
        assertTrue(book.due(a))
        book.start(a)
        assertFalse("on its way: not sent twice", book.due(a))
        assertNull("not the core's until it took it", book.sent)
        assertFalse(book.finish(a, ok = true))
        assertEquals(a, book.sent)
        assertFalse(book.due(a))
        assertTrue("another palette is due", book.due(Key("sunny", 2)))
    }

    @Test
    fun aFailedSendLeavesTheCoresLookAndIsDueAgainWithOneRetryAtRest() {
        val book = MomentBook<Key>()
        val a = Key("sunny", 1)
        val b = Key("sunny", 2)
        book.start(a)
        book.finish(a, ok = true)
        book.start(b)
        assertTrue("the first failure is tried again at rest", book.finish(b, ok = false))
        assertEquals("the core still holds the palette before", a, book.sent)
        assertTrue("due at the next update", book.due(b))
        book.start(b)
        assertFalse("a palette that keeps failing is not tried for ever at rest", book.finish(b, ok = false))
        assertTrue(book.due(b))
        book.start(b)
        book.finish(b, ok = true)
        assertEquals(b, book.sent)
        val c = Key("night", 2)
        book.start(c)
        assertTrue("after a success, a failure is tried again at rest", book.finish(c, ok = false))
    }

    @Test
    fun anOlderSendComingBackLateDoesNotClearTheOneOnItsWay() {
        val book = MomentBook<Key>()
        val a = Key("sunny", 1)
        val b = Key("sunny", 2)
        book.start(a)
        book.start(b)
        book.finish(a, ok = true)
        assertFalse("b is still on its way", book.due(b))
        book.finish(b, ok = true)
        assertEquals(b, book.sent)
    }

    @Test
    fun aNewCoreHoldsNothing() {
        val book = MomentBook<Key>()
        val a = Key("sunny", 1)
        book.start(a)
        book.finish(a, ok = true)
        book.reset()
        assertNull(book.sent)
        assertTrue("a new core is sent the moment, its palette with it", book.due(a))
    }
}
