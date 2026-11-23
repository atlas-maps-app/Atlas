package app.atlas.mapview

/**
 * The window shown again (ON_START) shows the core once the return's moment has been handed (the review of `hidden`,
 * 23 Nov 2026). The moment goes to the core off the main thread ([MapController]'s sendMoment), and at rest the core's
 * `moment` asks for the camera alone (jni/Bridge.cpp: a step of five minutes must not draw a resting map at any time).
 * Shown first, the frame back could be drawn before the moment arrives, with the light of the hide, which then stays
 * until something else draws: a morning's light in the evening. So the return hands the moment over first and shows
 * the core once it came back, taken or not (a hand-over that fails shows it all the same), or at once where none is
 * due; hidden again before it came back, it shows nothing. The frame on the show is the core's own (Bridge's
 * set_hidden): a moment at rest still asks for none. Main thread.
 */
internal class ShownAgain(private val show: () -> Unit) {
    /** The return still waiting for its moment, by its number; 0 for none. */
    private var waiting = 0L
    private var returns = 0L

    /**
     * The window shown again: [hand] hands the return's moment over where one is due, with what to run on the main
     * thread once it came back, and says whether it did. The core is shown once it came back, or at once.
     */
    fun returned(hand: (back: () -> Unit) -> Boolean) {
        val mine = ++returns
        waiting = mine
        val handing = hand { if (waiting == mine) shown() }
        if (!handing && waiting == mine) shown()
    }

    /** The window hidden again: a return still waiting for its moment shows nothing when it comes back. */
    fun hidden() {
        waiting = 0
    }

    private fun shown() {
        waiting = 0
        show()
    }
}
