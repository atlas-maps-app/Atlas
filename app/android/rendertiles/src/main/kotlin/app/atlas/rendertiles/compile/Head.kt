package app.atlas.rendertiles.compile

import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveScene
import app.atlas.rendertiles.model.Profile
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.StyleVersion

/**
 * The `rhead` section of a pack ([RenderHead]): the profile, the frame with its doubles as the port computed them, the
 * versions, the source's hash and the region's views (`ViewLadder`'s knots, as `places.py` writes them), so the core
 * places tiles and derives every view's values with no JSON and no `CrMath`.
 */
object Head {
    fun of(scene: LiveScene, frame: SceneToTiles, source: String, levels: IntArray): RenderHead {
        val region = scene.region
        val profile = if (region.kind == LiveRegion.Kind.LIVING) Profile.LIVING else Profile.REGION
        val views = region.ladder.knots.map { k -> RenderHead.View(k.name, LinkedHashMap(k.cam)) }
        return RenderHead(profile, region.id, levels.copyOf(), frame.lat0, frame.lon0, frame.kx, frame.kz, EngineVersion.ID, StyleVersion.ID, source, views)
    }
}
