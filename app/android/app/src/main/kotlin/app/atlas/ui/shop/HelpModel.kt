package app.atlas.ui.shop

import app.atlas.places.OpenState
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** One place that gives food or water: what kind, and how its week looks. */
internal class HelpRow(val place: ShopPlace, val kind: HelpKind)

/** The Help screen's answer: how many of each kind are near, and the nearest places (of one kind, or of all). */
internal class HelpView(val counts: Map<HelpKind, Int>, val total: Int, val rows: List<HelpRow>)

internal object Helps {
    fun view(area: ShopArea, only: HelpKind?, limit: Int = 12): HelpView {
        val rows = area.places.mapNotNull { p -> HelpKind.of(p.tags)?.let { HelpRow(p, it) } }.filter { only == null || it.kind == only }
        val all = area.places.mapNotNull { p -> HelpKind.of(p.tags)?.let { it to p } }
        val counts = HelpKind.entries.associateWith { k -> all.count { it.first == k } }
        // Everything at once shows the nearest few of each kind, so water points do not bury the fridges.
        val shown = if (only != null) rows.sortedBy { it.place.distanceM }.take(limit)
        else rows.groupBy { it.kind }.values.flatMap { it.sortedBy { r -> r.place.distanceM }.take(3) }.sortedBy { it.place.distanceM }.take(limit)
        return HelpView(counts, all.size, shown)
    }

    /** The days a place is open at some hour, Monday first ("Tue · Thu"); empty for every day or when no hours are mapped. */
    fun days(p: ShopPlace, area: ShopArea, locale: Locale = Locale.getDefault()): List<String> {
        val h = p.hours ?: return emptyList()
        if (h.isAlwaysOpen) return emptyList()
        val table = runCatching { h.weekTable(null, area.hours) }.getOrNull() ?: return emptyList()
        val open = table.mapIndexedNotNull { i, spans -> if (spans.any { it.state == OpenState.OPEN }) DayOfWeek.of(i + 1) else null }
        if (open.size == 7 || open.isEmpty()) return emptyList()
        return open.map { it.getDisplayName(TextStyle.SHORT, locale) }
    }

    /** True when every day is open (the schedule needs no days line). */
    fun daily(p: ShopPlace, area: ShopArea): Boolean {
        val h = p.hours ?: return false
        if (h.isAlwaysOpen) return false
        val table = runCatching { h.weekTable(null, area.hours) }.getOrNull() ?: return false
        return table.all { day -> day.any { it.state == OpenState.OPEN } }
    }

    /** The next opening after [at] of a closed place, as "Wed 12:00" or "12:00" today; null when it is open or unknown. */
    fun next(p: ShopPlace, area: ShopArea, at: LocalDateTime): String? = p.opensAt(at, area.hours)?.let { opensText(it, at) }
}
