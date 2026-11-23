package app.atlas.mapcore

/**
 * Who hides the process's one core (ENGINE.md's audit, B1): it is hidden while it has at least one map and none of
 * them is shown, and [MapCore]'s `setHidden` is told only when that answer changes. Each map (an activity's
 * MapController) [join]s when it takes the core, says with its lifecycle whether its window is shown ([show]: ON_START,
 * ON_STOP), and [leave]s when it lets the core go. So an old activity's late stop and destroy (Back at the root and a
 * quick return, where Back still ends the activity: Android 10 and 11) neither hide nor show the core under a new one's
 * window: the new map joined and shows. A core with no map (VK facts, VK bench) is never hidden. Main thread.
 *
 * [tell] is the core's own call (the native setHidden); a JVM test records it.
 */
class CoreHiding internal constructor(private val tell: (Boolean) -> Unit) {
    /** Each map that joined, and whether its window is shown. */
    private val maps = HashMap<Any, Boolean>()

    /** Whether the core was last told it is hidden. */
    var hidden = false
        private set

    /** [map] draws with the core from now on, its window [shown] or not (a map that joined before: its window now). */
    fun join(map: Any, shown: Boolean) {
        maps[map] = shown
        settle()
    }

    /** [map]'s window shown (ON_START) or hidden (ON_STOP); nothing for a map that has not joined, or has left. */
    fun show(map: Any, shown: Boolean) {
        if (map !in maps) return
        maps[map] = shown
        settle()
    }

    /** [map] no longer draws with the core; nothing for one that has not joined. */
    fun leave(map: Any) {
        if (maps.remove(map) == null) return
        settle()
    }

    private fun settle() {
        val now = maps.isNotEmpty() && maps.values.none { it }
        if (now == hidden) return
        hidden = now
        tell(now)
    }
}
