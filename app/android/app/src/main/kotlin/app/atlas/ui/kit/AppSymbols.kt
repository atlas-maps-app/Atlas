package app.atlas.ui.kit

import app.atlas.places.Icon

/**
 * The few UI symbols the app needs beyond `:places`' set: the tab bar's map, turn and open book, weather skies, and
 * Atlas's own "A near B" rings. Lucide paths (ISC licence, see LICENSES.md), stroked 2 units on a 24 grid like the rest
 * of the UI set; ids use the same `ui-` prefix, and `:places` is asked only when an id is not here.
 */
object AppSymbols {
    private val icons: Map<String, Icon> = mapOf(
        "ui-map" to strokedIcon(
            "ui-map",
            "M14.106 5.553a2 2 0 0 0 1.788 0l3.659-1.83A1 1 0 0 1 21 4.619v12.764a1 1 0 0 1-.553.894l-4.553 2.277a2 2 0 0 1-1.788 0l-4.212-2.106a2 2 0 0 0-1.788 0l-3.659 1.83A1 1 0 0 1 3 19.381V6.618a1 1 0 0 1 .553-.894l4.553-2.277a2 2 0 0 1 1.788 0z",
            "M15 5.764v15",
            "M9 3.236v15",
        ),
        "ui-corner-up-right" to strokedIcon(
            "ui-corner-up-right",
            "m15 14 5-5-5-5",
            "M4 20v-7a4 4 0 0 1 4-4h12",
        ),
        "ui-book-open" to strokedIcon(
            "ui-book-open",
            "M12 5v16",
            "M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z",
        ),
        "ui-cloud-check" to strokedIcon(
            "ui-cloud-check",
            "m17 15-5.5 5.5L9 18",
            "M5.516 16.07A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 3.501 7.327",
        ),
        "ui-cloud-sun" to strokedIcon(
            "ui-cloud-sun",
            "M12 2v2",
            "m4.93 4.93 1.41 1.41",
            "M20 12h2",
            "m19.07 4.93-1.41 1.41",
            "M15.947 12.65a4 4 0 0 0-5.925-4.128",
            "M13 22H7a5 5 0 1 1 4.9-6H13a3 3 0 0 1 0 6Z",
        ),
        "ui-cloud-moon" to strokedIcon(
            "ui-cloud-moon",
            "M13 16a3 3 0 0 1 0 6H7a5 5 0 1 1 4.9-6z",
            "M18.376 14.512a6 6 0 0 0 3.461-4.127c.148-.625-.659-.97-1.248-.714a4 4 0 0 1-5.259-5.26c.255-.589-.09-1.395-.716-1.248a6 6 0 0 0-4.594 5.36",
        ),
        "ui-cloud-fog" to strokedIcon(
            "ui-cloud-fog",
            "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
            "M16 17H7",
            "M17 21H9",
        ),
        "ui-cloud-drizzle" to strokedIcon(
            "ui-cloud-drizzle",
            "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
            "M8 19v1",
            "M8 14v1",
            "M16 19v1",
            "M16 14v1",
            "M12 21v1",
            "M12 16v1",
        ),
        "ui-cloud-lightning" to strokedIcon(
            "ui-cloud-lightning",
            "M6 16.326A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 .5 8.973",
            "m13 12-3 5h4l-3 5",
        ),
        "ui-cloud-snow" to strokedIcon(
            "ui-cloud-snow",
            "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
            "M8 15h.01",
            "M8 19h.01",
            "M12 17h.01",
            "M12 21h.01",
            "M16 15h.01",
            "M16 19h.01",
        ),
        "ui-cloud-rain" to strokedIcon(
            "ui-cloud-rain",
            "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
            "M16 14v6",
            "M8 14v6",
            "M12 16v6",
        ),
        "ui-calendar-days" to strokedIcon(
            "ui-calendar-days",
            "M8 2v3",
            "M16 2v3",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z",
            "M3 9h18",
            "M8 13h.01",
            "M12 13h.01",
            "M16 13h.01",
            "M8 17h.01",
            "M12 17h.01",
            "M16 17h.01",
        ),
        "ui-rotate-3d" to strokedIcon(
            "ui-rotate-3d",
            "m15.194 13.707 3.814 1.86-1.86 3.814",
            "M16.47214 7.52786 A 5 10 0 1 0 13 21.79796",
            "M21.79796 11 A 10 5 0 1 0 19 15.57071",
        ),
        "ui-navigation-2" to strokedIcon(
            "ui-navigation-2",
            "M12 2 L19 21 L12 17 L5 21 L12 2z",
        ),
        "ui-crosshair" to strokedIcon(
            "ui-crosshair",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z",
            "M22 12L18 12",
            "M6 12L2 12",
            "M12 6L12 2",
            "M12 22L12 18",
        ),
        "ui-locate-fixed" to strokedIcon(
            "ui-locate-fixed",
            "M2 12L5 12",
            "M19 12L22 12",
            "M12 2L12 5",
            "M12 19L12 22",
            "M5 12a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
        ),
        "ui-map-pinned" to strokedIcon(
            "ui-map-pinned",
            "M18 8c0 3.613-3.869 7.429-5.393 8.795a1 1 0 01-1.214 0C9.87 15.429 6 11.613 6 8a6 6 0 0112 0",
            "M4.474 15h-.197a1 1 0 00-.969.753l-1.097 4.35a1.5 1.5 0 001.444 1.898L20.344 22a1.5 1.5 0 001.446-1.897l-1.098-4.35a1 1 0 00-.969-.753h-.197",
            "M10 8a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
        ),
        "ui-waypoints" to strokedIcon(
            "ui-waypoints",
            "m10.586 5.414-5.172 5.172",
            "m18.586 13.414-5.172 5.172",
            "M6 12h12",
            "M10 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
            "M10 4a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
            "M18 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
            "M2 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
        ),
        // Atlas's own: two places joined, for "A near B" queries.
        "ui-near" to strokedIcon(
            "ui-near",
            "M4 7a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
            "M14 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
            "M9.3 9.3L14.7 14.7",
        ),
    )

    fun icon(id: String): Icon? = icons[id]

    fun has(id: String): Boolean = id in icons

    val ids: Set<String> get() = icons.keys
}
