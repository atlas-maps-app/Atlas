package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import app.atlas.mapcore.state.RouteBlock
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * The map's routes and areas for the vector engine (ENGINE.md step 6; `MapCore.routes`): each route's look as
 * [OverlayPainter.drawGround] sets it (its fill, faded and casing colours, its width, in the painter's order: the
 * alternatives first), its points with their distances along it ([RouteRibbons.along]), and its label's chip drawn with
 * the device's own Skia by [OverlayPainter.routeChip], centred on a whole pixel and cut to its pixels; and the shaded
 * circles. Chips are kept for the next hand-over (by their text, colour and look), so only new ones are drawn again.
 * One thread at a time.
 */
internal class RouteChips(val painter: OverlayPainter) {
    private data class Key(val label: String, val colour: Int, val quiet: Boolean)

    private val chips = LinkedHashMap<Key, RouteBlock.Image>(16, 0.75f, true)

    /** The routes and areas of [overlays] as a block for the core. */
    fun block(overlays: MapOverlays): ByteBuffer {
        val images = ArrayList<RouteBlock.Image>()
        val routes = ArrayList<RouteBlock.Route>()
        for (pass in 0..1) {
            for (r in overlays.routes) {
                if (r.alternative != (pass == 0) || r.points.size < 2) continue
                val fill = OverlayPainter.fillOf(r)
                val along = RouteRibbons.along(r.points)
                val points = DoubleArray(3 * r.points.size)
                for (i in r.points.indices) {
                    points[3 * i] = r.points[i].lat
                    points[3 * i + 1] = r.points[i].lon
                    points[3 * i + 2] = along[i]
                }
                val chip = r.label?.let { label ->
                    images += chip(label, fill, r.alternative)
                    images.size - 1
                } ?: RouteBlock.NO_CHIP
                routes += RouteBlock.Route(
                    points, OverlayPainter.widthOf(r), fill, OverlayPainter.fadedOf(fill), r.casing.toInt() or (0xFF shl 24),
                    r.dashed, r.travelled, chip,
                )
            }
        }
        val areas = overlays.areas.map { RouteBlock.Area(it.centre.lat, it.centre.lon, it.radiusM.toFloat(), it.fill.toInt(), it.stroke.toInt()) }
        return RouteBlock.write(painter.density, areas, routes, images)
    }

    /** The chip of [label] on a route of [colour], as the painter draws it; kept for the next hand-over. */
    fun chip(label: String, colour: Int, quiet: Boolean): RouteBlock.Image {
        val key = Key(label, colour, quiet)
        chips[key]?.let { return it }
        // room for the pill (the text and 10 dp each side, 24 dp tall) and its shadow, more should it touch the edge
        var margin = ceil(CHIP_DP * painter.density).toInt()
        while (true) {
            val w = 2 * margin + ceil(painter.labelWidth(label)).toInt()
            val h = 2 * margin
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cx = w / 2
            val cy = h / 2
            painter.routeChip(Canvas(bitmap), label, cx.toFloat(), cy.toFloat(), colour, quiet)
            val cut = SpriteCut.of(bitmap, cx, cy)
            if (cut != null) {
                val image = RouteBlock.Image(cut.width, cut.height, cut.anchorX, cut.anchorY, cut.pixels)
                chips[key] = image
                while (chips.size > KEPT) chips.remove(chips.keys.first())
                return image
            }
            margin *= 2
        }
    }

    private companion object {
        /** Half the room a chip is drawn in first beside its text, dp: its padding, half its height and its shadow. */
        const val CHIP_DP = 24f

        /** The chips kept for the next hand-over at most. */
        const val KEPT = 64
    }
}
