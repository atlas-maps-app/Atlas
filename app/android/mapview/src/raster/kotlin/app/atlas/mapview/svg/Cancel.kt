package app.atlas.mapview.svg

import java.util.concurrent.CancellationException

/**
 * Stops long work where it can stop cleanly: throws [CancellationException] when [cancelled] says so. The raster asks
 * between elements, filter primitives, bands and noise tiles; what it made so far is let go as the exception unwinds.
 */
internal fun stopIf(cancelled: () -> Boolean) {
    if (cancelled()) throw CancellationException("cancelled")
}
