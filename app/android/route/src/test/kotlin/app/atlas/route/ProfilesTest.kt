package app.atlas.route

import app.atlas.model.Mode
import app.atlas.model.RoutePrefs
import app.atlas.model.Vehicle
import btools.expressions.BExpressionContext
import btools.router.ProfileCache
import btools.router.RoutingContext
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProfilesTest {

    companion object {
        private lateinit var dir: File
        private lateinit var files: Map<String, File>

        @BeforeClass
        @JvmStatic
        fun install() {
            dir = Files.createTempDirectory("atlas-profiles-test").toFile()
            files = ProfileStore(dir).install()
        }

        @AfterClass
        @JvmStatic
        fun clean() {
            dir.deleteRecursively()
        }
    }

    /** Parses a generated profile with BRouter itself and hands over the routing context. */
    private fun <T> parsed(spec: ProfileSpec, keyValues: Map<String, String> = emptyMap(), block: (RoutingContext) -> T): T {
        val rc = RoutingContext()
        rc.localFunction = files.getValue(spec.name).absolutePath
        rc.keyValues = HashMap(keyValues)
        ProfileCache.parseProfile(rc)
        try {
            return block(rc)
        } finally {
            ProfileCache.releaseProfile(rc)
        }
    }

    private fun way(rc: RoutingContext, vararg tags: Pair<String, String>): Float {
        val ctx = rc.expctxWay
        evaluate(ctx, tags)
        return ctx.costfactor
    }

    private fun node(rc: RoutingContext, vararg tags: Pair<String, String>): Float {
        val ctx = rc.expctxNode
        evaluate(ctx, tags)
        return ctx.initialcost
    }

    // addLookupValue returns a package-private BRouter type, which the test ignores
    @Suppress("INFERRED_INVISIBLE_RETURN_TYPE_WARNING")
    private fun evaluate(ctx: BExpressionContext, tags: Array<out Pair<String, String>>) {
        val ld = ctx.createNewLookupData()
        for ((k, v) in tags) {
            ctx.addLookupValue(k, v, ld)
            assertTrue("$k=$v is not in lookups.dat", ld[ctx.getLookupNameIdx(k)] >= 2)
        }
        ctx.evaluate(false, ctx.encode(ld))
    }

    @Test
    fun everyModeHasAProfileThatBRouterParses() {
        for (mode in Mode.entries) {
            val spec = Profiles.forMode(mode)
            if (mode == Mode.TRANSIT) {
                assertNull(spec); continue
            }
            val kv = ProfileOptions.keyValues(mode, RoutePrefs(quiet = 0.6, green = 0.6, flat = 0.5, avoidFerries = true), Vehicle(weightT = 12.0, heightM = 3.5))
            parsed(spec!!, kv) { rc ->
                assertEquals("$mode pass2coefficient", 1.0, rc.pass2coefficient, 1e-9)
                when {
                    mode.motor -> assertTrue("$mode is a car profile", rc.carMode)
                    mode.cycling -> assertTrue("$mode is a bike profile", rc.bikeMode)
                    mode.onFoot -> assertTrue("$mode is a foot profile", rc.footMode)
                    mode.water -> assertFalse(rc.carMode || rc.bikeMode || rc.footMode)
                }
            }
        }
        assertEquals(15, files.size)
        assertTrue(File(dir, "lookups.dat").isFile)
    }

    @Test
    fun walkingPrefersFootwaysAndWheelchairsNeverTakeSteps() {
        parsed(Profiles.WALK) { rc ->
            assertEquals(1.0f, way(rc, "highway" to "footway"), 1e-6f)
            assertEquals(1.3f, way(rc, "highway" to "steps"), 1e-6f)
            assertEquals(1.8f, way(rc, "highway" to "primary"), 1e-6f)
            assertEquals(1.3f, way(rc, "highway" to "primary", "sidewalk" to "both"), 1e-6f)
            assertEquals(2.5f, way(rc, "highway" to "primary", "sidewalk" to "separate"), 1e-6f)
            assertTrue(way(rc, "highway" to "motorway") > 9998)
            assertTrue(way(rc, "highway" to "footway", "foot" to "no") > 9998)
            assertEquals(0f, node(rc, "kerb" to "raised"), 0f)
        }
        parsed(Profiles.WHEELCHAIR) { rc ->
            assertTrue(way(rc, "highway" to "steps") > 9998)
            assertTrue(way(rc, "highway" to "footway", "surface" to "sand") > 9998)
            assertTrue(way(rc, "highway" to "footway", "incline" to "15%") > 9998)
            assertEquals(2.4f, way(rc, "highway" to "footway", "surface" to "sett") * way(rc, "highway" to "footway", "wheelchair" to "yes"), 1e-5f)
            assertEquals(1_000_000f, node(rc, "kerb" to "raised"), 0f)
            assertEquals(0f, node(rc, "kerb" to "lowered"), 0f)
            assertEquals(1_000_000f, node(rc, "barrier" to "kerb"), 0f)
            assertEquals(1_000_000f, node(rc, "barrier" to "stile"), 0f)
            assertEquals(4.2 / 3.6, rc.maxSpeed, 1e-6)
        }
    }

    @Test
    fun trucksFollowHgvRulesAndAvoidHeightRestrictors() {
        parsed(Profiles.TRUCK) { rc ->
            assertEquals(0.9f, way(rc, "highway" to "motorway", "hgv" to "designated"), 1e-6f)
            assertEquals(1.0f, way(rc, "highway" to "primary"), 1e-6f)
            assertEquals(3.0f, way(rc, "highway" to "residential"), 1e-6f)
            assertEquals(5.0f, way(rc, "highway" to "primary", "hgv" to "destination"), 1e-6f)
            assertTrue(way(rc, "highway" to "primary", "hgv" to "no") > 9998)
            assertTrue(way(rc, "highway" to "residential", "access" to "no") > 9998)
            assertTrue(way(rc, "highway" to "track", "surface" to "gravel") >= 50f)
            assertEquals(1_000_000f, node(rc, "barrier" to "height_restrictor"), 0f)
            assertEquals(1_000_000f, node(rc, "hgv" to "no"), 0f)
            assertEquals(0f, node(rc, "highway" to "traffic_signals"), 0f)
        }
        parsed(Profiles.TRUCK, mapOf("destination_ok" to "1")) { rc ->
            assertEquals(1.0f, way(rc, "highway" to "primary", "hgv" to "destination"), 1e-6f)
        }
    }

    @Test
    fun boatsStayOnNavigableWater() {
        parsed(Profiles.BOAT) { rc ->
            assertEquals(1.0f, way(rc, "waterway" to "canal"), 1e-6f)
            assertEquals(0.9f, way(rc, "waterway" to "fairway"), 1e-6f)
            assertTrue(way(rc, "highway" to "residential") > 9998)
            assertTrue(way(rc, "waterway" to "canal", "boat" to "no") > 9998)
            assertTrue(way(rc, "waterway" to "canal", "motorboat" to "no") > 9998)
            assertTrue(way(rc, "waterway" to "dam") > 9998)
            assertTrue(way(rc, "waterway" to "canal", "tunnel" to "culvert") > 9998)
            assertEquals(1_000_000f, node(rc, "waterway" to "waterfall"), 0f)
            assertEquals(50_000f, node(rc, "waterway" to "weir"), 0f)
            assertEquals(300f, node(rc, "waterway" to "lock_gate"), 0f)
            assertEquals(12 / 3.6, rc.maxSpeed, 1e-6)
        }
        parsed(Profiles.SAIL) { rc ->
            assertEquals(1.0f, way(rc, "waterway" to "canal", "motorboat" to "no"), 1e-6f)
            assertTrue(way(rc, "waterway" to "canal", "boat" to "no") > 9998)
            assertEquals(8 / 3.6, rc.maxSpeed, 1e-6)
        }
    }

    @Test
    fun preferencesScaleTheBaseProfiles() {
        val plain = parsed(Profiles.CAR) { rc -> way(rc, "highway" to "primary", "estimated_traffic_class" to "5") }
        val quiet = parsed(Profiles.CAR, mapOf("atlas_quiet" to "1")) { rc -> way(rc, "highway" to "primary", "estimated_traffic_class" to "5") }
        assertEquals(plain * 2, quiet, 1e-4f)
        parsed(Profiles.CAR, mapOf("atlas_avoid_ferries" to "1", "avoid_toll" to "1")) { rc ->
            assertTrue(way(rc, "route" to "ferry") > 9998)
            assertTrue(way(rc, "highway" to "motorway", "toll" to "yes") > 9998)
            assertEquals(1f, rc.expctxWay.getVariableValue("avoid_toll", 0f), 0f)
        }
        parsed(Profiles.CARGO_BIKE) { rc ->
            assertEquals(1_000_000f, node(rc, "barrier" to "cycle_barrier"), 0f)
        }
        parsed(Profiles.MOTORCYCLE) { rc ->
            assertTrue(way(rc, "highway" to "residential", "motorcycle" to "no") > 9998)
            assertTrue(way(rc, "highway" to "residential", "motorcar" to "no") < 9998)
        }
        parsed(Profiles.CAMPER, mapOf("atlas_heavy" to "1")) { rc ->
            assertTrue(way(rc, "highway" to "primary", "hgv" to "no") > 9998)
            assertEquals(1_000_000f, node(rc, "barrier" to "height_restrictor"), 0f)
        }
    }

    @Test
    fun renderingReplacesSingleValuesAndRefusesExpressions() {
        val ebike = Profiles.render(Profiles.EBIKE)
        assertTrue(ebike.contains(Regex("""(?m)^assign\s+downhillcost = 20\s+# Atlas""")))
        assertTrue(ebike.contains("assign downhillcost       = if consider_elevation then downhillcost else 0"))
        assertTrue(ebike.contains("pass2coefficient = 1.0"))
        assertTrue(ebike.contains("--- Atlas preferences (appended) ---"))
        try {
            Profiles.render(ProfileSpec("bad", "mtb", defaults = mapOf("uphillcostvalue" to "1")))
            fail("a multi-token assignment was overridden")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("uphillcostvalue"))
        }
        try {
            Profiles.render(ProfileSpec("bad", "car-vario", replacements = listOf("nothing like this" to "x")))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not found"))
        }
        val added = Profiles.render(ProfileSpec("x", "river", defaults = mapOf("brand_new" to "3")))
        assertTrue(added.contains("assign brand_new = 3"))
    }

    @Test
    fun storeOnlyRewritesChangedFiles() {
        val d = Files.createTempDirectory("atlas-store").toFile()
        try {
            val first = ProfileStore(d).install()
            val car = first.getValue("atlas-car")
            car.setLastModified(1_000_000L)
            ProfileStore(d).install()
            assertEquals(1_000_000L, car.lastModified())
            car.writeText("changed")
            ProfileStore(d).install()
            assertTrue(car.readText().contains("Generated by Atlas"))
            // a store whose folder was cleared (the system emptied a cache) writes it again when asked for a profile
            val store = ProfileStore(d)
            store.install()
            d.listFiles()!!.forEach { it.delete() }
            val again = store.file(Profiles.CAR)
            assertTrue(again.isFile && File(d, "lookups.dat").isFile)
        } finally {
            d.deleteRecursively()
        }
    }

    @Test
    fun swappedOptionsGetTheirOwnParsedProfile() {
        // BRouter's profile cache compares the sum of the keys' and values' hash codes, and a swap keeps the sum
        val tolls = mapOf("avoid_toll" to "1", "avoid_motorways" to "0", "vmax" to "125")
        val motorways = mapOf("avoid_toll" to "0", "avoid_motorways" to "1", "vmax" to "125")
        fun checksum(kv: Map<String, String>) = RoutingContext().apply { keyValues = HashMap(kv) }.keyValueChecksum
        assertEquals(checksum(tolls), checksum(motorways))
        val a = BRouterEngine.withDigest(tolls)
        val b = BRouterEngine.withDigest(motorways)
        assertTrue(checksum(a) != checksum(b))
        // stable, a number like every key value, and the request's own values untouched
        assertEquals(a, BRouterEngine.withDigest(a))
        assertEquals(a, BRouterEngine.withDigest(HashMap(tolls)))
        assertTrue(a.getValue(BRouterEngine.DIGEST_KEY).toFloatOrNull() != null)
        assertEquals(tolls, a - BRouterEngine.DIGEST_KEY)
    }

    @Test
    fun keyValuesFollowTheRequest() {
        val truck = ProfileOptions.keyValues(Mode.TRUCK, RoutePrefs(avoidTolls = true), Vehicle())
        assertEquals("80", truck["vmax"])
        assertEquals("40000", truck["totalweight"])
        assertEquals("1", truck["avoid_toll"])
        assertEquals("90", ProfileOptions.keyValues(Mode.TRUCK, RoutePrefs(), Vehicle(weightT = 7.0))["vmax"])
        assertEquals("85", ProfileOptions.keyValues(Mode.TRUCK, RoutePrefs(), Vehicle(maxSpeedKmh = 85.0))["vmax"])
        assertEquals("125", ProfileOptions.keyValues(Mode.CAR, RoutePrefs(fast = 0.5), Vehicle())["vmax"])
        assertEquals("90", ProfileOptions.keyValues(Mode.CAR, RoutePrefs(fast = 0.0), Vehicle())["vmax"])
        assertEquals("1", ProfileOptions.keyValues(Mode.WALK, RoutePrefs(avoidSteps = true), Vehicle())["avoid_steps"])
        assertEquals("0", ProfileOptions.keyValues(Mode.BIKE, RoutePrefs(avoidSteps = true), Vehicle())["allow_steps"])
        assertEquals("1", ProfileOptions.keyValues(Mode.CAMPER, RoutePrefs(), Vehicle(weightT = 5.0))["atlas_heavy"])
        assertEquals("15", ProfileOptions.keyValues(Mode.BOAT, RoutePrefs(), Vehicle(maxSpeedKmh = 15.0))["maxSpeed"])
        // every value is a number, as BRouter needs
        for (mode in Mode.entries) for ((k, v) in ProfileOptions.keyValues(mode, RoutePrefs(quiet = 1.0, flat = 1.0), Vehicle(ev = app.atlas.model.EvSpec(60.0, 16.0)))) {
            assertTrue("$mode $k=$v", v.toFloatOrNull() != null)
        }
    }
}
