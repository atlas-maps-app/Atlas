package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.atlas.places.OpeningHours
import app.atlas.testing.shot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.sin

/** Draws every kit component to a PNG (light and dark) for review; each file must come out non-empty. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class KitScreenshotTest {

    private fun gallery(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        val f = shot(name) {
            AtlasTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Atlas.colors.paper).padding(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                }
            }
        }
        assertTrue("$f was not written", f.isFile && f.length() > 1000)
    }

    @Composable
    private fun Label(text: String) = Kicker(text)

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SymbolsAndButtons() {
        Label("Symbols")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (id in listOf(
                "ui-map", "ui-corner-up-right", "ui-calendar-days", "ui-book-open", "ui-user", "ui-search", "ui-mic", "ui-near",
                "ui-cloud-sun", "ui-cloud-rain", "ui-cloud-check", "ui-layers", "ui-cube", "ui-navigation", "ui-globe", "ui-sparkles",
                "maki-cafe", "maki-restaurant", "maki-bakery", "temaki-bench", "logo-albertheijn",
            )) Symbol(id, size = 28.dp, contentDescription = id)
        }
        Label("Buttons")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton({})
            SymbolButton("ui-layers", "Layers", {})
            SymbolButton("ui-cube", "3D", {}, selected = true)
            PillButton({}) {
                Symbol("ui-sparkles", tint = Color(0xFFFFD66B), size = 18.dp)
                Text("3", style = Atlas.type.name)
            }
        }
        PrimaryButton("12′", {}, symbol = "ui-corner-up-right")
        PrimaryButton("Download & continue", {}, colour = Atlas.colors.pill, modifier = Modifier.fillMaxWidth())
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ChipsAndPills() {
        Label("Chips")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Open now", onClick = {})
            Chip("Step-free", symbol = "ui-wheelchair", outlined = true)
            Chip("café", symbol = "maki-cafe", symbolTint = Color(0xFFE07A1F), count = 12)
            ToggleChip("Open now", true, {})
            ToggleChip("Wi-Fi", false, {}, symbol = "ui-wifi")
            ToggleChip(null, true, {}, symbol = "ui-vegan", contentDescription = "Vegan")
        }
        Label("Value chips")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ValueChip("ui-bolt", "150", Color(0xFF1F9D55))
            ValueChip("ui-bolt", "150", Color(0xFF1F9D55), dark = true)
            ValueChip("ui-star", "4.8", Color(0xFFE29A12))
            ValueChip("ui-walk", "4′", Color(0xFF5D636E))
        }
        Label("Segmented pill")
        SegmentedPill(listOf(Segment("Chains", "ui-link", 84), Segment("Independent", "ui-store", 212)), 0, {})
        SegmentedPill(listOf(Segment("Fuel"), Segment("Charge"), Segment("Park")), 1, {})
    }

    @Composable
    private fun CardsAndRows() {
        Label("Card and rows")
        Card {
            PlaceRow("Linden Pharmacy", symbol = "maki-pharmacy", colour = Color(0xFF1F9D55), meta = "open until 20:00 · step-free · 350 m", metaColour = Atlas.colors.open, trailing = "5′")
            Hairline()
            PlaceRow("Night duty", symbol = "maki-pharmacy", colour = Color(0xFFD23C3C), meta = "closed · opens 20:00", trailing = "14′", trailingColour = Atlas.colors.alert)
        }
        StateMessage("ui-cloud-off", "Nothing mapped nearby", text = "Online adds what the phone does not have yet.")
    }

    @Composable
    private fun Instruments() {
        Label("Instruments")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val hours = OpeningHours.parse("Mo-Su 12:00-15:00,17:30-23:00")
            ClockDial(hours.spans(LocalDate.of(2026, 10, 13)), LocalTime.of(19, 42), size = 128.dp, contentDescription = "open until 23:00")
            Compass(heading = 20f, size = 128.dp, target = 75f)
            ProgressRing(0.68f, size = 96.dp, stroke = 9.dp) { Text("12′", style = Atlas.type.numeralSmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            BatteryGauge(0.38f, rangeKm = 142.0)
            BatteryGauge(0.12f, showText = false, width = 70.dp)
            BatteryGauge(0.8f, charging = true, showText = false, width = 70.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoadSign(SignKind.SPEED, "50")
            RoadSign(SignKind.SPEED, "130")
            RoadSign(SignKind.HEIGHT, "3.5 m")
            RoadSign(SignKind.WIDTH, "2.2 m")
            RoadSign(SignKind.WEIGHT, "7.5 t")
            RoadSign(SignKind.LENGTH, "12 m")
        }
        // A ride over a bridge and a dyke: a gentle rise, a steeper ramp, a long descent.
        val elevations = DoubleArray(120) { i ->
            val x = i / 119.0
            2.0 + 16.0 * sin(x * Math.PI) + 9.0 * Math.exp(-((x - 0.42) * (x - 0.42)) / 0.004)
        }
        Card { ElevationProfile(elevations, progress = 0.28f, contentDescription = "climb 31 m") }
    }

    @Composable
    private fun Board() {
        Label("Departure board")
        DepartureBoard(
            listOf(
                Departure("19:08", "13", Color(0xFFE8A33D), "Centraal", "A", "0′"),
                Departure("19:11", "17", Color(0xFF2E9E5B), "Osdorp", "B", "+2′", Color(0xFFFF8A65)),
                Departure("19:14", "N", Color(0xFF14161B), "Westerkerk", "1", null),
            ),
        )
    }

    @Test
    fun symbolsAndButtons() = gallery("kit_symbols_buttons") { SymbolsAndButtons() }

    @Test
    fun chips() = gallery("kit_chips") { ChipsAndPills() }

    @Test
    fun cardsAndRows() = gallery("kit_cards_rows") { CardsAndRows() }

    @Test
    fun instruments() = gallery("kit_instruments") { Instruments(); Board() }

    @Test
    fun dark() = gallery("kit_dark", dark = true) { SymbolsAndButtons(); ChipsAndPills(); CardsAndRows() }

    @Test
    fun locationCard() = gallery("kit_location_card") { app.atlas.ui.home.LocationPermissionCard(onGranted = {}) }

    @Test
    fun afterDark() {
        val ams = app.atlas.Services.WESTERKERK
        assertTrue(isAfterDark(ams, java.time.Instant.parse("2026-10-13T20:30:00Z")))
        assertTrue(!isAfterDark(ams, java.time.Instant.parse("2026-10-13T12:00:00Z")))
    }

    @Test
    fun sheet() {
        val f = shot("kit_sheet") {
            AtlasTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFFDCD5C8))) {
                    Sheet(rememberSheetState(SheetStop.HALF)) {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Osteria Lume", style = Atlas.type.title)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PrimaryButton("6′", {}, symbol = "ui-corner-up-right", modifier = Modifier.width(160.dp))
                                SymbolButton("ui-phone", "Call", {})
                                SymbolButton("ui-globe", "Web", {})
                            }
                            Box(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
        assertTrue(f.isFile && f.length() > 1000)
    }
}
