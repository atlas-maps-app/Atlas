package app.atlas.providers

/**
 * Every online source the app asks, for the Settings · Data screen, the privacy board and the credits: what each is,
 * its licence and credit line, whether it needs a key, whether it is on by default, and the hosts it is asked at. The app
 * adds the sources its own screens ask (SourcesModel); a test holds the lists to the code's clients.
 */
object Catalogue {
    val ALL: List<ProviderInfo> = listOf(
        Overpass.INFO, Nominatim.INFO, Photon.INFO,
        Mangrove.INFO, FoursquareRatings.INFO, YelpRatings.INFO, TripAdvisorRatings.INFO,
        OpenChargeMap.INFO, OsmChargers.INFO,
        Weather.INFO, MetNorway.INFO, Wikidata.INFO, Commons.INFO, OsmNotes.INFO, OsmEdits.INFO,
    )

    /**
     * Sources written but not asked by the app yet: no screen lists them until they are (the US register, Chargemap's
     * partner feed, public transport).
     */
    val NOT_WIRED: List<ProviderInfo> = listOf(NdwChargers.INFO, BnetzaChargers.INFO, Afdc.INFO, Chargemap.INFO, Transitous.INFO)

    /** Data bundled in the app rather than asked online, with the credit each licence requires. */
    val BUNDLED: List<ProviderInfo> = listOf(
        ProviderInfo(
            id = "ev-models", name = "Open EV Data and OpenEV Data", licence = "MIT with attribution; CDLA-Permissive-2.0",
            attribution = EvModels.ATTRIBUTION, url = "https://github.com/KilowattApp/open-ev-data",
        ),
        ProviderInfo(
            id = "ocm-reference", name = "Open Charge Map reference data", licence = "CC BY 4.0",
            attribution = "Operator names from Open Charge Map", url = "https://openchargemap.org",
        ),
    )

    fun byId(id: String): ProviderInfo? = (ALL + BUNDLED + NOT_WIRED).firstOrNull { it.id == id }
}
