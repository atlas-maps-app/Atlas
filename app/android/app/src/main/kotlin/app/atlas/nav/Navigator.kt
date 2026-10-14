package app.atlas.nav

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import app.atlas.R

/** The bottom bar's tabs, with their symbol, label and the tint of their selected pill (from the boards). */
enum class Tab(val symbol: String, @StringRes val label: Int, val accent: Color, val accentSoft: Color) {
    MAP("ui-map", R.string.tab_map, Color(0xFFE0592A), Color(0xFFFBE2D6)),
    GO("ui-corner-up-right", R.string.tab_go, Color(0xFF2E5BFF), Color(0xFFE3EAFF)),
    SOON("ui-calendar-days", R.string.tab_soon, Color(0xFF7B4FD6), Color(0xFFECE4FB)),
    LOCAL("ui-book-open", R.string.tab_local, Color(0xFF8A5A2B), Color(0xFFF1E5D3)),
    MINE("ui-user", R.string.tab_mine, Color(0xFF14161B), Color(0xFFE6E2DA));

    /** The tab's home screen. */
    val root: Dest
        get() = when (this) {
            MAP -> Dest.Home()
            GO -> Dest.Go
            SOON -> Dest.Soon
            LOCAL -> Dest.Local()
            MINE -> Dest.Mine
        }
}

/**
 * What a screen calls to move: forward to a destination, back, or to a tab. Screens get one as `nav` and never hold
 * the back stack themselves.
 */
@Stable
interface Navigator {
    /** The destination on top. */
    val current: Dest

    /** Opens [dest] on top of the current screen. */
    fun go(dest: Dest)

    /** Closes the current screen; false when there is nothing to go back to. */
    fun back(): Boolean

    /** Swaps the current screen for [dest] (a step in a flow that should not come back). */
    fun replace(dest: Dest)

    /** Shows [tab]'s home; the Map stays underneath, so back from any tab lands on the map. */
    fun tab(tab: Tab)

    /** Replaces the whole back stack (deep links, CI). An empty [stack] means the Map home. */
    fun reset(stack: List<Dest>)
}

/** The app's [Navigator] over a back stack that survives configuration changes and process death (as routes). */
class BackStackNavigator(initial: List<Dest> = listOf(Dest.Home())) : Navigator {
    val stack: SnapshotStateList<Dest> = mutableStateListOf<Dest>().apply { addAll(initial.ifEmpty { listOf(Dest.Home()) }) }

    override val current: Dest get() = stack.last()

    override fun go(dest: Dest) {
        if (stack.lastOrNull() == dest) return
        stack.add(dest)
    }

    override fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    override fun replace(dest: Dest) {
        if (stack.isEmpty()) stack.add(dest) else stack[stack.lastIndex] = dest
    }

    override fun tab(tab: Tab) {
        val home = stack.firstOrNull() as? Dest.Home ?: Dest.Home()
        val target = if (tab == Tab.MAP) listOf(home) else listOf(home, tab.root)
        if (stack.toList() == target) return
        stack.clear()
        stack.addAll(target)
    }

    override fun reset(stack: List<Dest>) {
        val s = stack.ifEmpty { listOf(Dest.Home()) }
        // The Map is always at the bottom, so back ends on it.
        val full = if (s.first() is Dest.Home) s else listOf(Dest.Home()) + s
        this.stack.clear()
        this.stack.addAll(full)
    }

    companion object {
        /** Saves the stack as routes; unknown routes (an older build's) are dropped on restore, the map stays underneath. */
        val Saver: Saver<BackStackNavigator, ArrayList<String>> = Saver(
            save = { nav -> ArrayList(nav.stack.map { it.route }) },
            restore = { routes -> BackStackNavigator().apply { reset(routes.mapNotNull { Dest.parse(it) }) } },
        )
    }
}
