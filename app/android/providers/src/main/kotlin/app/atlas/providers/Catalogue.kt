package app.atlas.providers

/**
 * Every online source Atlas knows, for the Settings · Data screen and the credits: what each is, its licence and
 * credit line, whether it needs a key, and whether it is on by default.
 */
object Catalogue {
    val ALL: List<ProviderInfo> = listOf(
        Overpass.INFO, Nominatim.INFO, Photon.INFO,
        Mangrove.INFO, FoursquareRatings.INFO, YelpRatings.INFO, TripAdvisorRatings.INFO,
        OpenChargeMap.INFO, OsmChargers.INFO, NdwChargers.INFO, BnetzaChargers.INFO, Afdc.INFO, Chargemap.INFO,
        Weather.INFO, MetNorway.INFO, Wikidata.INFO, OsmNotes.INFO, Transitous.INFO,
    )

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

    fun byId(id: String): ProviderInfo? = (ALL + BUNDLED).firstOrNull { it.id == id }
}
