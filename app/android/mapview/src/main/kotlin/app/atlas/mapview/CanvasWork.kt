package app.atlas.mapview

/**
 * Whether the map's canvas draws anything over the core's picture (ENGINE.md gain 12): the overlays' canvas redraws at
 * every frame only where it draws something, so where the core draws the map alone HWUI does not render the window
 * over it again at each frame, on the same GPU as the core.
 */
internal object CanvasWork {
    /**
     * Whether the canvas draws nothing: no debug panel, no tape and no honest state; the core's path wanted and none of
     * today's map over it ([cover] 0); nothing on the ground (a route, an area: the canvas's still); and the labels,
     * markers and puck there are the core's ([coreLabels], [coreMarkers], [coreLocation]).
     */
    fun idle(
        debug: Boolean, tape: Boolean, honest: Boolean, core: Boolean, cover: Float, overlays: MapOverlays,
        coreLabels: Boolean, coreMarkers: Boolean, coreLocation: Boolean,
    ): Boolean =
        !debug && !tape && !honest && core && cover <= 0f && !overlays.onGround &&
            (overlays.labels.isEmpty() || coreLabels) && (overlays.markers.isEmpty() || coreMarkers) &&
            (overlays.location == null || coreLocation)
}
