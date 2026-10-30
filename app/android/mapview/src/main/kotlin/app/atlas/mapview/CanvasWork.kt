package app.atlas.mapview

/**
 * Whether the map's canvas draws anything over the core's picture (ENGINE.md gain 12): the overlays' canvas redraws at
 * every frame only where it draws something, so where the core draws the map alone HWUI does not render the window
 * over it again at each frame, on the same GPU as the core.
 */
internal object CanvasWork {
    /**
     * Whether the canvas draws nothing: no debug panel, no tape, no tilt dial ([dial]: from the tilt's start to the end
     * of its fade) and no honest state; the core's path wanted and none of today's map over it ([cover] 0); and the
     * routes and areas, labels, markers and puck there are the core's ([coreRoutes], [coreLabels], [coreMarkers],
     * [coreLocation]).
     */
    fun idle(
        debug: Boolean, tape: Boolean, dial: Boolean, honest: Boolean, core: Boolean, cover: Float, overlays: MapOverlays,
        coreRoutes: Boolean, coreLabels: Boolean, coreMarkers: Boolean, coreLocation: Boolean,
    ): Boolean =
        !debug && !tape && !dial && !honest && core && cover <= 0f && (!overlays.onGround || coreRoutes) &&
            (overlays.labels.isEmpty() || coreLabels) && (overlays.markers.isEmpty() || coreMarkers) &&
            (overlays.location == null || coreLocation)
}
