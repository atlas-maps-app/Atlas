package app.atlas.mapview

/** What the map can say about its picture: drawn, on its way, or no data. */
sealed interface MapStatus {
    /** The picture is the core's, complete and shown. */
    data object Ready : MapStatus

    /** The core is making the picture ([region]: what it waits for, or null). */
    data class Loading(val region: String?) : MapStatus

    /** The map of [region] is on its way ([done] of [bytes], −1 when unknown), or being built from OpenStreetMap ([building]). */
    data class Downloading(val region: String, val done: Long, val bytes: Long, val building: Boolean = false) : MapStatus

    /** No map data here, and why. */
    data class NoData(val reason: String) : MapStatus
}
