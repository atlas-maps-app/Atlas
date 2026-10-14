package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.places.Icon
import app.atlas.ui.kit.ComposePathSink
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.drawSymbol
import app.atlas.ui.kit.strokedIcon
import java.util.concurrent.ConcurrentHashMap

/**
 * The go package's own two glyphs for what no board draws (a bridge, simulate), stroked 2 units on a 24 grid like the
 * UI set. Every other `go-` id is a board's own icon ([BoardIcons]); anything else is the kit's [Symbol].
 */
internal object GoGlyphs {
    private val icons: Map<String, Icon> = mapOf(
        // A bridge over water (the bridges ahead on Sail) and "simulate" (the planner's demo drive): the boards have none.
        "go-bridge" to strokedIcon("go-bridge", "M2 8h20", "M4 8v11", "M20 8v11", "M4 19a8 8 0 0 1 16 0", "M8 8v4", "M12 8v3", "M16 8v4"),
        "go-simulate" to strokedIcon("go-simulate", "M7 4v16l13-8z", "M3 8h.01", "M3 12h.01", "M3 16h.01"),
    )

    private val cache = ConcurrentHashMap<String, Built>()

    fun has(id: String): Boolean = id in icons

    /** One glyph's paths, built once. */
    class Built(val icon: Icon, val paths: List<Path>, val stroked: BooleanArray)

    fun built(id: String): Built? {
        cache[id]?.let { return it }
        val icon = icons[id] ?: return null
        val paths = icon.paths.map { p -> Path().also { p.commands.replay(ComposePathSink(it)) } }
        val stroked = BooleanArray(icon.paths.size) { icon.paths[it].stroke && !icon.paths[it].fill }
        return Built(icon, paths, stroked).also { cache[id] = it }
    }
}

/** Draws the glyph [id] (a `go-` glyph or any kit symbol) into a [side] square at [topLeft]. */
internal fun DrawScope.drawGoSymbol(id: String, topLeft: Offset, side: Float, tint: Color, weight: Float = 1f) {
    val b = GoGlyphs.built(id) ?: return drawSymbol(id, topLeft, side, tint, weight)
    val vb = b.icon.viewBox
    val scale = side / maxOf(vb.width, vb.height)
    withTransform({
        translate(topLeft.x - vb.x * scale, topLeft.y - vb.y * scale)
        scale(scale, scale, Offset.Zero)
    }) {
        val stroke = Stroke(width = b.icon.strokeWidth * weight, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (i in b.paths.indices) drawPath(b.paths[i], tint, style = if (b.stroked[i]) stroke else Fill)
    }
}

/**
 * The boards' own icons by the ids the go package uses: the modes as the Go board draws them, the vehicles of the
 * Truck and Twowheel boards, and the controls of Ride, Drive, Sail, Route and Ears. They win over the kit's look-alikes.
 */
internal object BoardIcons {
    /** The e-bike: the Go board's bike with the Twowheel board's charging bolt, small, over the back wheel. */
    private val ebike: Art by lazy {
        val b = BoardArt.mBike
        Art(b.x, b.y, b.w, b.h, b.parts + ArtPart(
            "M21.24 0.96L18.01 5.33H20.48L20.1 8.56L23.33 4.19H20.86z", stroke = CURRENT, width = 1.4f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ))
    }

    val byId: Map<String, Art> by lazy {
        mapOf(
            "go-walk" to BoardArt.mWalk.tinted, "go-bike" to BoardArt.mBike.tinted, "go-ebike" to ebike, "go-cargo" to BoardArt.vCargo.tinted,
            "go-moto" to BoardArt.mMoto.tinted, "go-car" to BoardArt.mCar.tinted, "go-camper" to BoardArt.mCamper.tinted, "go-truck" to BoardArt.mTruck.tinted,
            "go-boat" to BoardArt.vBoat.tinted, "go-sail" to BoardArt.mast.tinted, "go-transit" to BoardArt.mTransit.tinted, "go-scooter" to BoardArt.vScooter.tinted, "go-coach" to BoardArt.vCoach.tinted,
            "ui-walk" to BoardArt.mWalk.tinted, "ui-bike" to BoardArt.mBike.tinted, "ui-car" to BoardArt.mCar.tinted, "ui-truck" to BoardArt.vTruck.tinted,
            "ui-sailboat" to BoardArt.mBoat.tinted, "ui-ship" to BoardArt.vBoat.tinted, "ui-bus" to BoardArt.vCoach.tinted,
            "ui-headphones" to BoardArt.rideHeadphones.tinted, "ui-vibrate" to BoardArt.rideHaptic.tinted, "go-screen-off" to BoardArt.rideScreenOff.tinted,
            "ui-cloud-check" to BoardArt.cloudCheck.tinted, "ui-check" to BoardArt.check.tinted, "ui-x" to BoardArt.close.tinted,
            "ui-chevron-left" to BoardArt.back.tinted, "ui-chevron-right" to BoardArt.chevronRight.tinted, "ui-chevron-down" to BoardArt.chevronDown.tinted,
            "ui-clock" to BoardArt.clock.tinted, "ui-sliders" to BoardArt.sliders.tinted, "ui-search" to BoardArt.search.tinted, "ui-mic" to BoardArt.mic.tinted,
            "ui-lock" to BoardArt.padlock.tinted, "ui-plug-zap" to BoardArt.charge.tinted, "ui-bolt" to BoardArt.charge.tinted, "ui-wrench" to BoardArt.wrench.tinted,
            "ui-fuel" to BoardArt.fuel.tinted, "ui-trending-up" to BoardArt.trendUp.tinted, "ui-leaf" to BoardArt.leaf.tinted, "ui-trees" to BoardArt.tree.tinted,
            "go-swap" to BoardArt.swap.tinted, "ui-navigation" to BoardArt.myLocation.tinted, "go-stand" to BoardArt.stands.tinted,
            "go-covered" to BoardArt.coveredStands.tinted, "go-locker" to BoardArt.lockers.tinted, "go-garage" to BoardArt.garage.tinted,
            "ui-anchor" to BoardArt.marina.tinted, "go-draft" to BoardArt.drift.tinted, "go-volume" to BoardArt.speaker.tinted, "ui-map" to BoardArt.overview.tinted,
            "ui-flag" to BoardArt.flag.tinted, "ui-alert-triangle" to BoardArt.hazard.tinted, "go-cobbles" to BoardArt.cobbles.tinted, "go-door" to BoardArt.door.tinted,
            "ui-door" to BoardArt.door.tinted, "go-repeat" to BoardArt.turnList.tinted, "ui-u-turn" to BoardArt.uTurn.tinted, "ui-wheelchair" to BoardArt.stepFree.tinted,
            "ui-wind" to BoardArt.wind.tinted, "ui-layers" to BoardArt.layers.tinted, "go-share" to BoardArt.sharePerson.tinted, "go-cone" to BoardArt.cone.tinted,
        )
    }
}

/** A glyph of the go package or of the kit, tinted, in a [size] square. */
@Composable
internal fun GoSymbol(
    id: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    contentDescription: String? = null,
    weight: Float = 1f,
) {
    BoardIcons.byId[id]?.let { art ->
        return ArtIcon(if (weight == 1f) art else art.weighted(weight), modifier, size, tint, contentDescription)
    }
    if (!GoGlyphs.has(id)) return Symbol(id, modifier, tint, size, contentDescription, weight)
    val described = if (contentDescription != null) Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
    } else Modifier
    Canvas(modifier.then(described).size(size)) { drawGoSymbol(id, Offset.Zero, this.size.minDimension, tint, weight) }
}
