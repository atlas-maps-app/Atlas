package app.atlas.ui.eat

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

/**
 * The eat boards' own icons, their SVG path data as the boards draw them (`canvas/project/Menus`, `Bar`, `Cafe`,
 * `Carte`, `Order`, `Ramen`, `Street`, `Sweets`, `Market`, `Eat`), with each board's stroke width.
 */
object EatGlyphs {
    // ---- Menus: the table --------------------------------------------------------------------------------------

    /** The folded map of the table's map button and pill. */
    val map = BoardGlyph(listOf("M3 6.5l6-2.5 6 2.5 6-2.5v13.5l-6 2.5-6-2.5-6 2.5z", "M9 4v13.5M15 6.5V20"), 1.9f, cap = StrokeCap.Butt)
    val clock = BoardGlyph(listOf(circleD(12f, 12f, 8.5f), "M12 7.5V12l3 2"), 2.2f)
    val party = BoardGlyph(listOf(circleD(9f, 8.5f, 3.2f), "M3 19a6 6 0 0 1 12 0", "M15.5 5.6a3.2 3.2 0 0 1 0 6M17.5 14.2A6 6 0 0 1 21 19"), 2.2f)

    /** The order pad: a slip with three lines. */
    val pad = BoardGlyph(listOf(rectD(5f, 3f, 14f, 18f, 1.5f), "M8 8h8M8 12h8M8 16h5"), 1.9f)
    val sparkle = BoardGlyph(listOf("M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"), fill = true)
    val mic = BoardGlyph(listOf(rectD(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0 0 14 0M12 18v3"), 1.9f)
    val cutlery = BoardGlyph(listOf("M7 3v8M5 3v5a2 2 0 0 0 4 0V3M7 11v10", "M17 21V3c-2 1-3 3.5-3 7h3"), 1.5f)

    /** The steaming cup chalked on the café's board (a 44 × 40 drawing). */
    val chalkCup = BoardGlyph(listOf("M8 14h24v10a10 10 0 0 1-10 10h-4A10 10 0 0 1 8 24z", "M32 17h3a4 4 0 0 1 0 8h-3M15 3c0 3 3 3 3 7M22 3c0 3 3 3 3 7"), 2f, viewBox = 44f)
    val martini = BoardGlyph(listOf("M4 4h16l-8 9z", "M12 13v7M8 20.5h8"), 1.5f)
    val stall = BoardGlyph(listOf("M3 10l2-6h14l2 6", "M3 10c0 1.7 1.3 3 3 3s3-1.3 3-3c0 1.7 1.3 3 3 3s3-1.3 3-3c0 1.7 1.3 3 3 3s3-1.3 3-3M5 13v8h14v-8"), 1.6f)
    val dice = BoardGlyph(
        listOf(rectD(4f, 4f, 16f, 16f, 3.5f)), 1.8f,
        filled = listOf(circleD(9f, 9f, 1.3f), circleD(15f, 15f, 1.3f), circleD(15f, 9f, 1.3f), circleD(9f, 15f, 1.3f), circleD(12f, 12f, 1.3f)),
    )

    // ---- Carte -------------------------------------------------------------------------------------------------

    val backBold = BoardGlyph(listOf("M15 5l-7 7 7 7"), 2f)

    /** The carte's die for "surprise me": four pips. */
    val diceFour = BoardGlyph(
        listOf(rectD(4f, 4f, 16f, 16f, 3.5f)), 1.8f,
        filled = listOf(circleD(9f, 9f, 1.3f), circleD(15f, 15f, 1.3f), circleD(15f, 9f, 1.3f), circleD(9f, 15f, 1.3f)),
    )

    /** The engraved flourish over "La Carte" (120 × 16). */
    val flourish = BoardGlyph(
        listOf("M0 8h44M76 8h44", "M60 2c-6 0-10 3-10 6s4 6 10 6 10-3 10-6-4-6-10-6z", "M44 8c4-4 6-4 10 0M66 8c4 4 6 4 10 0"),
        1f, viewBox = 120f, cap = StrokeCap.Butt, join = StrokeJoin.Miter,
    )

    // ---- Bar ---------------------------------------------------------------------------------------------------

    val back = BoardGlyph(listOf("M15 5l-7 7 7 7"), 1.8f)
    val padThin = BoardGlyph(listOf(rectD(5f, 3f, 14f, 18f, 1.5f), "M8 8h8M8 12h8M8 16h5"), 1.7f)
    val happyHour = BoardGlyph(listOf("M4 4h16l-8 9z", "M12 13v7M8 20.5h8M17 4l3-2"), 1.5f, join = StrokeJoin.Round)
    val outdoors = BoardGlyph(listOf("M3 12a9 9 0 0 1 18 0H3z", "M12 12v6.5a2 2 0 0 1-4 0"), 1.5f)
    val music = BoardGlyph(listOf("M9 18V5l11-2v13", circleD(6.5f, 18f, 2.5f), circleD(17.5f, 16f, 2.5f)), 1.5f)
    val screen = BoardGlyph(listOf(rectD(3f, 5f, 18f, 12f, 1.5f), "M8 21h8M12 17v4"), 1.5f)
    val talk = BoardGlyph(listOf("M4 5h11v8H9l-4 3v-3H4z", "M15 9h5v7h-1v3l-3-3h-4v-3"), 1.5f)
    val walk = BoardGlyph(listOf(circleD(13f, 4.5f, 1.7f), "M10 21l2.2-6.5L10 12l1-4.5 3.2 2.5 3 .8M12.2 14.5L15 21"), 2.2f)

    // ---- Order: the guest check ----------------------------------------------------------------------------------

    val braces = BoardGlyph(listOf("M8 4c-2 0-3 1-3 3v2.5c0 1.2-.8 2-2 2.5 1.2.5 2 1.3 2 2.5V17c0 2 1 3 3 3M16 4c2 0 3 1 3 3v2.5c0 1.2.8 2 2 2.5-1.2.5-2 1.3-2 2.5V17c0 2-1 3-3 3"), 2f)
    val trash = BoardGlyph(listOf("M4 7h16M9 7V4.5h6V7M6.5 7l1 13h9l1-13"), 2f)
    val clockGutter = BoardGlyph(listOf(circleD(12f, 12f, 8.5f), "M12 7.5V12l3 2"), 1.9f)
    val cutleryGutter = BoardGlyph(listOf("M7 3v8M5 3v5a2 2 0 0 0 4 0V3M7 11v10", "M17 21V3c-2 1-3 3.5-3 7h3"), 1.9f)

    /** Two ticks: "must have" (a 28 × 20 drawing). */
    val mustHave = BoardGlyph(listOf("M2 10l5 5L17 4", "M11 13l2 2L24 4"), 2.4f, viewBox = 28f)
    val tick = BoardGlyph(listOf("M4 12.5l5 5L20 6"), 2.4f)
    val umbrella = BoardGlyph(listOf("M3 12a9 9 0 0 1 18 0H3z", "M12 12v6.5a2 2 0 0 1-4 0"), 2f)
    val leaf = BoardGlyph(listOf("M5 19c0-8 5-13.5 14-14 0 9-5 14-13 14", "M5 19l7.5-7.5"), 2f)
    val stepFree = BoardGlyph(listOf(circleD(10f, 4.5f, 1.6f), "M10 7.5v6h6l2.5 5M10 10.5h5M7.5 11.2A5.2 5.2 0 1 0 14.3 18"), 2f)
    val paw = BoardGlyph(
        listOf(circleD(6.5f, 9.5f, 1.9f), circleD(10.5f, 6f, 1.9f), circleD(15f, 6f, 1.9f), circleD(18.5f, 9.8f, 1.9f), "M8 17c0-3 2.4-6 4.8-6s4.7 3 4.7 6c0 2-1.9 2.6-4.7 2.6S8 19 8 17z"),
        fill = true,
    )
    val quiet = BoardGlyph(listOf(circleD(9f, 8.5f, 3.2f), "M3 19a6 6 0 0 1 12 0", "M16 12l2 2 4-4"), 2f)
    val upArrow = BoardGlyph(listOf("M12 19V5M6 11l6-6 6 6"), 2.4f)
    val card = BoardGlyph(listOf(rectD(3f, 6f, 18f, 12f, 2f), "M3 10h18"), 2f)
    val pin = BoardGlyph(listOf("M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z", circleD(12f, 10f, 2.3f)), 2f)
    val plus = BoardGlyph(listOf("M12 5v14M5 12h14"), 2.4f)
    val star = BoardGlyph(listOf("M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z"), fill = true)
    val walkThin = BoardGlyph(listOf(circleD(13f, 4.5f, 1.7f), "M10 21l2.2-6.5L10 12l1-4.5 3.2 2.5 3 .8M12.2 14.5L15 21"), 2f)
    val flame = BoardGlyph(listOf("M12 21c-3.9 0-6.5-2.6-6.5-6.2 0-3.5 2.6-5.3 3.6-8.3.8 1.5 1.3 2.6 1.3 3.9 1.4-1.3 2.4-3.6 2.4-6.4 3.4 2.3 5.7 6.1 5.7 10.3 0 3.9-2.6 6.7-6.5 6.7z"), fill = true)
    val moon = BoardGlyph(listOf("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"), fill = true)
    val bookmark = BoardGlyph(listOf("M6 3.5h12v17l-6-4-6 4v-17z"), 2.4f)
    val cloche = BoardGlyph(listOf("M3 17h18M5 17a7 7 0 0 1 14 0M12 7V5.5M10.5 5.5h3"), 1.9f)

    // ---- Cafe: the chalkboard ------------------------------------------------------------------------------------

    val wifi = BoardGlyph(listOf("M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.8 16a5 5 0 0 1 6.4 0", circleD(12f, 19.3f, 0.9f)), 1.8f)
    val speakerOff = BoardGlyph(listOf("M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M16 9.5l5 5M21 9.5l-5 5"), 1.8f)
    val umbrellaChalk = BoardGlyph(listOf("M3 12a9 9 0 0 1 18 0H3z", "M12 12v6.5a2 2 0 0 1-4 0"), 1.8f)
    val leafChalk = BoardGlyph(listOf("M5 19c0-8 5-13.5 14-14 0 9-5 14-13 14", "M5 19l7.5-7.5"), 1.8f)
    val sunrise = BoardGlyph(listOf("M3 18h18M6.5 18a5.5 5.5 0 0 1 11 0M12 5v4M4.8 9.8l1.6 1.6M19.2 9.8l-1.6 1.6"), 1.8f)
    val takeawayCup = BoardGlyph(listOf("M6 7h12l-1.5 14h-9z", "M5 7l1-3h12l1 3M8 12h8"), 1.8f)
    val leafMark = BoardGlyph(listOf("M5 19c0-8 5-13.5 14-14 0 9-5 14-13 14"), 2.4f)
    val specialCup = BoardGlyph(listOf("M4 9h13v5a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5V9z", "M17 10.5h1.5a2.5 2.5 0 0 1 0 5H17M8 3.5c0 1.5 1 1.5 1 3M12 3.5c0 1.5 1 1.5 1 3"), 1.6f)
    val arrow = BoardGlyph(listOf("M5 12h14M13 6l6 6-6 6"), 2f)

    // ---- Ramen: the ticket machine -------------------------------------------------------------------------------

    val moonFilled = BoardGlyph(listOf("M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"), fill = true)
    val leafStamp = BoardGlyph(listOf("M5 19c0-8 5-13.5 14-14 0 9-5 14-13 14", "M5 19l7.5-7.5"), 2f)
    val bag = BoardGlyph(listOf("M5 8h14l-1 12H6L5 8z", "M9 8V6.5a3 3 0 0 1 6 0V8"), 2f)
    val cardStamp = BoardGlyph(listOf(rectD(3f, 6f, 18f, 12f, 2f), "M3 10h18M7 14.5h4"), 2f)
    val leafKey = BoardGlyph(listOf("M5 19c0-8 5-13.5 14-14 0 9-5 14-13 14"), 2.6f)
    val bill = BoardGlyph(listOf(rectD(3f, 6.5f, 18f, 11f, 1.5f), circleD(12f, 12f, 2.5f)), 2.2f)
    val person = BoardGlyph(listOf(circleD(9f, 8.5f, 3.2f), "M3 19a6 6 0 0 1 12 0"), 2.2f)
    val clockSmall = BoardGlyph(listOf(circleD(12f, 12f, 8.5f), "M12 7.5V12l3 2"), 2.2f)
    val turn = BoardGlyph(listOf("M6 21v-8a4 4 0 0 1 4-4h9", "M15 5l4 4-4 4"), 2f)
    val stepFreeStamp = BoardGlyph(listOf(circleD(10f, 4.5f, 1.6f), "M10 7.5v6h6l2.5 5M10 10.5h5M7.5 11.2A5.2 5.2 0 1 0 14.3 18"), 2f)

    // ---- Street: the neon wall -----------------------------------------------------------------------------------

    val tacos = BoardGlyph(listOf("M2 16a10 10 0 0 1 20 0z", "M5 12c1-1 2 0 3-1s2 0 3-1 2 0 3-1 2 .5 3 .5"), 1.6f)
    val slice = BoardGlyph(listOf("M12 21L3.5 5.5a15 15 0 0 1 17 0z", circleD(10f, 9f, 1.2f), circleD(14f, 12.5f, 1.2f)), 1.6f)
    val burger = BoardGlyph(listOf("M4 11a8 5.5 0 0 1 16 0z", "M3.5 14h17M4.5 17h15a2 2 0 0 1-2 2.5h-11a2 2 0 0 1-2-2.5z"), 1.6f)
    val kebab = BoardGlyph(listOf("M10 1v22", "M5 5c3-1 7-1 10 0l-1 12c-2 1-6 1-8 0z"), 1.6f, viewBox = 20f)
    val falafel = BoardGlyph(listOf(circleD(8f, 14f, 4f), circleD(16f, 14f, 4f), circleD(12f, 8f, 4f)), 1.6f)
    val dumpling = BoardGlyph(listOf("M2 13c0-5 4.5-9 10-9s10 4 10 9c-3 2-6 3-10 3S5 15 2 13z", "M7 6l1 3M12 4.5v4M17 6l-1 3"), 1.6f)
    val hotDog = BoardGlyph(listOf("M3 8c0 4 4 6 10 6s10-2 10-6", "M1 7.5c0-2 2-3 12-3s12 1 12 3-2 2-12 2S1 9.5 1 7.5z"), 1.6f, viewBox = 26f)
    val chicken = BoardGlyph(listOf("M14 4a6 6 0 0 1 4 10l-4 3-4-4 3-4a6 6 0 0 1 1-5z", "M10 13l-5 5M5 18l-1.5-1.5M5 18l1.5 1.5"), 1.6f)
    val truck = BoardGlyph(listOf("M2 4h14v11H2z", "M16 8h4l4 4v3h-8M5 8h8", circleD(6f, 16f, 2f), circleD(19f, 16f, 2f)), 1.6f, viewBox = 26f)
    val driveThrough = BoardGlyph(listOf("M5 13l1.6-4.2A2 2 0 0 1 8.5 7.5h7a2 2 0 0 1 1.9 1.3L19 13", rectD(3.5f, 13f, 17f, 5f, 1.5f)), 1.9f)
    val scooter = BoardGlyph(listOf(circleD(6f, 17f, 2.5f), circleD(18f, 17f, 2.5f), "M8.5 17h7M14 7h3l3 7M4 10h7v4"), 1.9f)
    val cash = BoardGlyph(listOf(rectD(3f, 6.5f, 18f, 11f, 1.5f), circleD(12f, 12f, 2.5f)), 1.9f)
    val sort = BoardGlyph(listOf("M7 4v16M4 17l3 3 3-3M13 7h8M13 12h6M13 17h4"), 2.4f)

    // ---- Sweets: the gelato case ---------------------------------------------------------------------------------

    val backRound = BoardGlyph(listOf("M15 5l-7 7 7 7"), 2.2f)
    val padRound = BoardGlyph(listOf(rectD(5f, 3f, 14f, 18f, 1.5f), "M8 8h8M8 12h8M8 16h5"), 2f)
    val leafPill = BoardGlyph(listOf("M5 19c0-8 5-13.5 14-14 0 9-5 14-13 14", "M5 19l7.5-7.5"), 2.2f)
    val bench = BoardGlyph(listOf("M3 10h18M4 14h16M6 14v5M18 14v5M5 10V6h14v4"), 2f)
    val child = BoardGlyph(listOf(circleD(12f, 6f, 3f), "M8 21l1-7h6l1 7M6 12l6-2 6 2"), 2f)

    // ---- Market: the price tags ----------------------------------------------------------------------------------

    val produce = BoardGlyph(listOf("M12 8c-4 0-7 3-7 7 0 3 2 6 7 6s7-3 7-6c0-4-3-7-7-7z", "M12 8c0-3 1-5 3-6M12 8c-2-2-4-2-6-1"), 1.8f)
    val butcher = BoardGlyph(listOf("M14 4c4 0 7 3 6 7-1 5-7 8-11 8-3 0-5-2-5-5 0-4 5-10 10-10z", circleD(11f, 13f, 2f)), 1.8f)
    val fish = BoardGlyph(listOf("M3 12c4-6 12-7 17 0-5 7-13 6-17 0z", "M20 12l4-4v8z"), 1.8f, viewBox = 26f, filled = listOf(circleD(8f, 11f, 1f)))
    val cheese = BoardGlyph(listOf("M3 16l11-9 7 5v6H3z", "M3 16h18", circleD(9f, 18f, 1f), circleD(15f, 14f, 1f)), 1.8f)
    val deli = BoardGlyph(listOf("M3 12h18c0 4-4 7-9 7s-9-3-9-7z", "M5 12c1-3 3-4 7-4s6 1 7 4M8 8l1-3M16 8l-1-3"), 1.8f)
    val bread = BoardGlyph(listOf("M3 15c0-5 5-8 10-8s10 3 10 8c0 2-2 3-10 3S3 17 3 15z", "M9 9l2 4M13 8v5M17 9l-2 4"), 1.8f, viewBox = 26f)
    val wine = BoardGlyph(listOf("M10 2h4v5c2 1 3 3 3 5v9H7v-9c0-2 1-4 3-5z", "M7 14h10"), 1.8f)
    val farm = BoardGlyph(listOf("M3 11l9-7 9 7v10H3z", "M9 21v-6h6v6M3 11h18"), 1.8f)
    val tickBox = BoardGlyph(listOf("M5 12.5l4.5 4.5L19 7.5"), 2.6f)

    // ---- Eat: the map and its cards ------------------------------------------------------------------------------

    val backMap = BoardGlyph(listOf("M15 5l-7 7 7 7"), 2f)
    val cutleryChip = BoardGlyph(listOf("M7 3v8M5 3v5a2 2 0 0 0 4 0V3M7 11v10", "M17 21V3c-2 1-3 3.5-3 7h3"), 2f)
    val clockFilter = BoardGlyph(listOf(circleD(12f, 12f, 8.5f), "M12 7.5V12l3 2"), 2f)
    val leafFilter = BoardGlyph(listOf("M5 19c0-8 5-13.5 14-14 0 9-5 14-13 14", "M5 19l7.5-7.5"), 2f)
    val calendar = BoardGlyph(listOf(rectD(3.5f, 5f, 17f, 15f, 2.5f), "M3.5 10h17M8 3v4M16 3v4"), 2f)
    val heart = BoardGlyph(listOf("M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.4 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z"), fill = true)
    val walkCard = BoardGlyph(listOf(circleD(13f, 4.5f, 1.7f), "M10 21l2.2-6.5L10 12l1-4.5 3.2 2.5 3 .8M12.2 14.5L15 21"), 2.4f)
}
