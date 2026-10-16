package app.atlas.rendertiles.compile

import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveScene
import app.atlas.render.live.Lod
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Profile
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.StyleVersion

/**
 * The `rhead` section of a pack ([RenderHead]): the profile, the frame with its doubles as the port computed them, the
 * versions, the source's hash, the region's views (`ViewLadder`'s knots, as `places.py` writes them) with the tolerance
 * each loads at, and the live map's bands, so the core places tiles, derives every view's values and loads a view as its
 * renders load it, with no JSON, no `CrMath` and no per-region constant typed again.
 */
object Head {
    fun of(scene: LiveScene, frame: SceneToTiles, source: String, levels: IntArray): RenderHead {
        val region = scene.region
        val living = region.kind == LiveRegion.Kind.LIVING
        val profile = if (living) Profile.LIVING else Profile.REGION
        val views = region.ladder.knots.map { k -> RenderHead.View(k.name, LinkedHashMap(k.cam), if (living) 0 else ladder(Lod.ofView(region.id, k.name), "${region.id}'s view ${k.name}")) }
        return RenderHead(profile, region.id, levels.copyOf(), frame.lat0, frame.lon0, frame.kx, frame.kz, EngineVersion.ID, StyleVersion.ID, source, views, bands(region))
    }

    /**
     * The live map's bands for [region]: `Lod.BANDS` ending at `Lod.UPTO` for a region (`LiveEngine.render`'s
     * `Lod.tolerance`); one band at 0 for the living map, which the engine always loads whole.
     */
    fun bands(region: LiveRegion): List<RenderHead.Band> {
        if (region.kind == LiveRegion.Kind.LIVING) return listOf(RenderHead.Band(0, null))
        check(Lod.UPTO.size == Lod.BANDS.size - 1) { "Lod has ${Lod.BANDS.size} bands and ${Lod.UPTO.size} ends" }
        return Lod.BANDS.indices.map { i -> RenderHead.Band(ladder(Lod.BANDS[i], "band $i"), Lod.UPTO.getOrNull(i)) }
    }

    private fun ladder(tolerance: Double, what: String): Int = Keep.indexOf(tolerance).also { require(it >= 0) { "$what loads at $tolerance m, which is not on the keep ladder" } }
}
