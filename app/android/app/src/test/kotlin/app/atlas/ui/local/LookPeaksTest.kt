package app.atlas.ui.local

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Look around, the street photo, and What's that peak?: the geometry and the data, without a screen. */
class LookPeaksTest {
    private val here = LatLon(52.0, 5.0)

    private fun building(northM: Double, southM: Double, westM: Double, eastM: Double, height: Double? = 10.0): Footprint {
        val frame = app.atlas.geo.LocalFrame(here)
        val box = BBox(frame.lon(westM), frame.lat(southM), frame.lon(eastM), frame.lat(northM))
        return Footprint(Place(OsmId(OsmType.WAY, 1), box.center, mapOf("building" to "yes"), box), box, BuiltYear.Unknown, height, null)
    }

    private fun named(name: String, northM: Double, eastM: Double, tags: Map<String, String> = emptyMap()): Place {
        val frame = app.atlas.geo.LocalFrame(here)
        return Place(OsmId(OsmType.NODE, name.hashCode().toLong()), frame.toLatLon(eastM, northM), tags + ("name" to name))
    }

    // ---- look around ----------------------------------------------------------------------------------------------------

    @Test
    fun aBuildingAheadShowsItsFacingWallAndOneBehindShowsNothing() {
        val ahead = building(40.0, 20.0, -10.0, 10.0)
        val behind = building(-20.0, -40.0, -10.0, 10.0)
        val seen = LookScene.walls(here, 0.0, listOf(ahead, behind), 412f, 330f)
        assertEquals(1, seen.size)
        val w = seen.single()
        assertTrue(w.footprint === ahead)
        // Centred, its base below the horizon, its top above.
        assertTrue(w.xs.all { it in 40f..370f })
        assertEquals(206f, (w.xs.min() + w.xs.max()) / 2, 1f)
        assertTrue(w.ys[0] > 330f && w.ys[2] < 330f)
        assertTrue(LookScene.walls(here, 180.0, listOf(ahead), 412f, 330f).isEmpty())
    }

    @Test
    fun whatIsOnTheLeftOnTheMapIsOnTheLeftInThePicture() {
        val left = building(40.0, 30.0, -30.0, -20.0)
        val right = building(40.0, 30.0, 20.0, 30.0)
        val l = LookScene.walls(here, 0.0, listOf(left), 412f, 330f)
        val r = LookScene.walls(here, 0.0, listOf(right), 412f, 330f)
        assertTrue(l.isNotEmpty() && l.all { w -> w.xs.all { it < 206f } })
        assertTrue(r.isNotEmpty() && r.all { w -> w.xs.all { it > 206f } })
        // Turning right by 90 degrees puts the building that was on the right ahead of you.
        val east = building(10.0, -10.0, 30.0, 40.0)
        assertTrue(LookScene.walls(here, 0.0, listOf(east), 412f, 330f).none { w -> w.xs.all { it in 150f..260f } })
        val turned = LookScene.walls(here, 90.0, listOf(east), 412f, 330f)
        assertTrue(turned.isNotEmpty() && turned.all { w -> w.xs.all { it in 100f..312f } })
    }

    @Test
    fun nearerWallsAreTallerOnTheScreenAndComeLastInThePaintingOrder() {
        val near = building(30.0, 20.0, -30.0, -20.0, 10.0)
        val far = building(100.0, 90.0, 20.0, 30.0, 10.0)
        val walls = LookScene.walls(here, 0.0, listOf(far, near), 412f, 330f)
        assertTrue(walls.last().footprint === near)
        assertTrue(walls.first().depth > walls.last().depth)
        val h = { w: LookScene.Wall -> w.ys[0] - w.ys[3] }
        assertTrue(h(walls.last()) > h(walls.first()))
    }

    @Test
    fun aBuildingYouStandInIsNotInThePictureAndAFarOneIsLeftOut() {
        assertTrue(LookScene.walls(here, 0.0, listOf(building(5.0, -5.0, -5.0, 5.0)), 412f, 330f).isEmpty())
        assertTrue(LookScene.walls(here, 0.0, listOf(building(400.0, 380.0, -10.0, 10.0)), 412f, 330f).isEmpty())
    }

    @Test
    fun placesInViewAreFoundByBearingAndDistance() {
        val ahead = named("Ahead", 50.0, 0.0)
        val leftish = named("Left", 40.0, -25.0)
        val behind = named("Behind", -50.0, 0.0)
        val far = named("Far", 400.0, 0.0)
        val marks = LookScene.marks(here, 0.0, listOf(behind, far, leftish, ahead), { it.tags["name"] })
        assertEquals(listOf("Left", "Ahead"), marks.map { it.name }.sortedBy { if (it == "Left") 0 else 1 })
        assertTrue(marks.first { it.name == "Left" }.relative < 0)
        assertEquals("Ahead", LookScene.ahead(marks)!!.name)
        assertNull(LookScene.ahead(marks.filter { it.name == "Left" }, cone = 20.0))
    }

    @Test
    fun aPlaceSaysWhenItClosesOrOpens() {
        val at = LocalDateTime.of(2026, 10, 13, 18, 0)
        val open = LookScene.status("Mo-Su 09:00-18:30", at)!!
        assertTrue(open.first)
        assertEquals(LocalDateTime.of(2026, 10, 13, 18, 30), open.second)
        val closed = LookScene.status("Mo-Su 09:00-17:00", at)!!
        assertEquals(false, closed.first)
        assertEquals(LocalDateTime.of(2026, 10, 14, 9, 0), closed.second)
        assertNull(LookScene.status("24/7", at))
        assertNull(LookScene.status(null, at))
        assertNull(LookScene.status("by appointment", at))
    }

    @Test
    fun theStreetYouStandInIsTheSmallestBoxHoldingYou() {
        fun street(name: String, d: Double) = Place(null, here, mapOf("highway" to "residential", "name" to name), BBox(5.0 - d, 52.0 - d, 5.0 + d, 52.0 + d))
        val long = street("Long Canal", 0.01)
        val short = street("Short Lane", 0.0005)
        assertEquals("Short Lane", LookScene.streetAt(here, listOf(long, short)) { it.tags["name"] })
        assertNull(LookScene.streetAt(here, emptyList()) { it.tags["name"] })
    }

    @Test
    fun stepsMoveAlongTheHeading() {
        val p = LookScene.step(here, 90.0, 15.0)
        assertEquals(15.0, Geo.distance(here, p), 0.2)
        assertEquals(90.0, Geo.bearing(here, p), 0.5)
    }

    // ---- street photos --------------------------------------------------------------------------------------------------

    @Test
    fun aPanoramaxAnswerBecomesAPhotoWithItsCredit() {
        val json = LookPeaksTest::class.java.getResourceAsStream("/local/panoramax_westerkerk.json")!!.bufferedReader().readText()
        val photo = StreetPhotos.parse(json)!!
        assertEquals(2023, photo.year)
        assertEquals("2023-02-06", photo.takenOn)
        assertEquals("City of Amsterdam", photo.author)
        assertEquals("CC-BY-SA-4.0", photo.license)
        assertTrue(photo.panorama)
        assertEquals(357.7, photo.poseHeading!!, 0.1)
        assertTrue(photo.imageUrl.endsWith("sd.jpg"))
        assertEquals(52.374182, photo.at.lat, 1e-6)
        assertNull(StreetPhotos.parse("{\"features\":[]}"))
        assertNull(StreetPhotos.parse("not json"))
        assertTrue(StreetPhotos.searchUrl(LatLon(52.3745, 4.884)).contains("place_position=4.8840,52.3745"))
        // to about 10 m, for a photo within 40 m
        assertTrue(StreetPhotos.searchUrl(LatLon(52.374512345, 4.884012345)).contains("place_position=4.8840,52.3745&"))
    }

    @Test
    fun aViewCutFromAPanoramaLooksWhereYouFace() {
        val w = 16
        val h = 8
        val src = IntArray(w * h) { it }
        // Facing the middle of the picture, the middle pixel is the picture's.
        val ahead = PanoView.render(src, w, h, heading = 100.0, poseHeading = 100.0, fov = 70.0, outW = 11, outH = 11)
        assertEquals(src[(h / 2) * w + w / 2], ahead[5 * 11 + 5])
        // A quarter turn right: a quarter of the width along.
        val right = PanoView.render(src, w, h, heading = 190.0, poseHeading = 100.0, fov = 70.0, outW = 11, outH = 11)
        assertEquals(src[(h / 2) * w + (w * 3 / 4)], right[5 * 11 + 5])
        // Left of the middle is left in the picture: columns do not run backwards.
        val cols = (0 until 11).map { ahead[5 * 11 + it] % w }
        assertTrue(cols.zipWithNext().all { (a, b) -> b >= a })
    }

    // ---- what's that peak -----------------------------------------------------------------------------------------------

    @Test
    fun heightsAreReadFromEleTags() {
        assertEquals(2052.0, Peaks.parseEle("2052")!!, 1e-9)
        assertEquals(1234.5, Peaks.parseEle("1,234.5")!!, 1e-9)
        assertEquals(1234.5, Peaks.parseEle("1234,5")!!, 1e-9)
        assertEquals(712.0, Peaks.parseEle("712 m")!!, 1e-9)
        assertEquals(2052.0, Peaks.parseEle("2052;2050")!!, 1e-9)
        assertNull(Peaks.parseEle("abc"))
        assertNull(Peaks.parseEle("99999"))
        assertNull(Peaks.parseEle(null))
    }

    private fun peak(name: String, ele: Double?, distanceM: Double, bearing: Double) =
        Peak(Place(OsmId(OsmType.NODE, name.hashCode().toLong()), Geo.destination(here, bearing, distanceM), mapOf("natural" to "peak", "name" to name)), name, ele, distanceM, bearing)

    @Test
    fun aPeaksAngleIsItsRiseOverItsDistanceLessTheEarthsCurve() {
        assertEquals(11.3, Peaks.angle(peak("A", 1574.0, 5000.0, 0.0), 574.0)!!, 0.1)
        // Far away the curve takes a visible bite: 100 km of 1000 m.
        val far = Peaks.angle(peak("B", 1574.0, 100_000.0, 0.0), 574.0)!!
        assertTrue("$far", far < Math.toDegrees(Math.atan2(1000.0, 100_000.0)) - 0.2)
        assertNull(Peaks.angle(peak("C", null, 5000.0, 0.0), 574.0))
        // From above, a lower peak is below the horizon.
        assertTrue(Peaks.angle(peak("D", 500.0, 3000.0, 0.0), 1000.0)!! < 0)
    }

    @Test
    fun theTapeLaysBearingsOutAcrossTheView() {
        assertEquals(206f, Peaks.x(324.0, 324.0, 412f)!!, 0.01f)
        assertEquals(206f + 6.2f * 10, Peaks.x(334.0, 324.0, 412f)!!, 0.5f)
        assertNull(Peaks.x(324.0 + 40.0, 324.0, 412f))
        // Across north.
        assertEquals(206f + 6.2f * 20, Peaks.x(14.0, 354.0, 412f)!!, 0.5f)
    }

    @Test
    fun theMostStrikingPeaksInViewComeFirstAndTheCameraTiltsUpForThem() {
        val peaks = listOf(peak("Low", 700.0, 3000.0, 0.0), peak("High", 2400.0, 5000.0, 5.0), peak("Behind", 3000.0, 4000.0, 180.0), peak("Unknown", null, 2000.0, 0.0))
        val view = Peaks.inView(peaks, 0.0, 574.0)
        assertEquals(listOf("High", "Low"), view.map { it.first.name })
        assertTrue(view[0].second > view[1].second)
        val f = 314f
        assertTrue(Peaks.pitch(view, f, 250f, 165f) in 0.0..35.0)
        assertEquals(0.0, Peaks.pitch(emptyList(), f, 250f, 165f), 0.0)
        // A bearing and an angle land on the same row for the same camera: higher angles higher up.
        assertTrue(Peaks.y(10.0, 4.0, f, 250f) < Peaks.y(5.0, 4.0, f, 250f))
        assertEquals(250f + f * Math.tan(Math.toRadians(4.0)).toFloat(), Peaks.y(0.0, 4.0, f, 250f), 0.01f)
    }

    @Test
    fun innsbrucksPeaksComeFromTheMapSortedByDistance() {
        val at = LatLon(47.2692, 11.4041)
        val peaks = Peaks.of(at, LocalFixtures.innsbruckPeaks, "en")
        assertTrue(peaks.size > 300)
        assertTrue(peaks.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
        assertTrue(peaks.all { it.distanceM < 23_000 })
        val tall = peaks.filter { it.eleM != null }.maxByOrNull { Peaks.angle(it, 574.0)!! }!!
        assertTrue(Peaks.angle(tall, 574.0)!! > 15)
    }

    @Test
    fun theSunSetsBehindThePeakItsPathMeets() {
        val at = LatLon(47.2692, 11.4041)
        val peaks = Peaks.of(at, LocalFixtures.innsbruckPeaks, "en")
        val now = Instant.parse("2026-10-13T12:00:00Z")
        val zone = ZoneId.of("Europe/Vienna")
        val behind = Peaks.sunBehind(at, now, zone, peaks, 574.0)
        assertNotNull(behind)
        val sun = app.atlas.geo.Sun.position(behind!!.at.toInstant(), at)
        // At that moment the sun stands at the peak's bearing and at its height, within the tolerance and a minute's drift.
        assertEquals(Peaks.angle(behind.peak, 574.0)!!, sun.altitudeDeg, 0.3)
        assertTrue(Geo.bearingDelta(sun.azimuthDeg, behind.peak.bearing) <= 2.6)
        // No peaks, no sunset behind one.
        assertNull(Peaks.sunBehind(at, now, zone, emptyList(), 574.0))
    }

    @Test
    fun aHikeIsNaismithsRule() {
        // 5.3 km as the crow flies, 1861 m of climb.
        assertEquals(269, Peaks.hikeMinutes(5300.0, 1861.0))
        assertEquals(0, Peaks.hikeMinutes(0.0, -50.0))
        assertEquals(16, Peaks.hikeMinutes(1000.0, 0.0))
    }

    // ---- the destinations -----------------------------------------------------------------------------------------------

    @Test
    fun theNewDestinationsRoundTripThroughTheirRoutes() {
        val all = listOf(
            Dest.Time(52.3745, 4.884), Dest.Time(), Dest.Look(52.37495, 4.88356, 315.0), Dest.Look(), Dest.Peaks(47.2692, 11.4041), Dest.Peaks(),
            Dest.Nature("wild", 52.37, 4.88), Dest.Parkland("w23935953", 52.3873, 4.8728), Dest.Picnic("play"), Dest.Swim("sea"), Dest.Relax("sauna"),
            Dest.Moon, Dest.Planets, Dest.Galaxy, Dest.Season, Dest.Year, Dest.Moments, Dest.Sky, Dest.Collect,
        )
        for (d in all) assertEquals(d.route, d, Dest.parse(d.route))
    }
}
