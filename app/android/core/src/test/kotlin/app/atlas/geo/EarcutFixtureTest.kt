package app.atlas.geo

import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Earcut's own test suite: every fixture, in four rotations, must give the expected triangle count and deviation. */
@RunWith(Parameterized::class)
class EarcutFixtureTest(private val name: String) {

    companion object {
        private val expected = Fixtures.json("earcut/expected.json").jsonObject

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> = expected["triangles"]!!.jsonObject.keys.toList()
    }

    @Test
    fun trianglesAndDeviationInEveryRotation() {
        val expectedTriangles = expected["triangles"]!!.jsonObject[name]!!.jsonPrimitive.int
        val errors = expected["errors"]!!.jsonObject
        val rotated = expected["errors-with-rotation"]!!.jsonObject

        for (rotation in listOf(0, 90, 180, 270)) {
            val (rings, dim) = Fixtures.earcutRings(name)
            if (rotation != 0) {
                val theta = rotation * Math.PI / 180
                val xx = cos(theta).roundToInt(); val xy = (-sin(theta)).roundToInt()
                val yx = sin(theta).roundToInt(); val yy = cos(theta).roundToInt()
                for (r in rings) {
                    var i = 0
                    while (i < r.size) {
                        val x = r[i]; val y = r[i + 1]
                        r[i] = xx * x + xy * y
                        r[i + 1] = yx * x + yy * y
                        i += dim
                    }
                }
            }
            val flat = Earcut.flatten(rings, dim)
            val triangles = Earcut.triangulate(flat.vertices, flat.holes, dim)
            val err = Earcut.deviation(flat.vertices, flat.holes, dim, triangles)
            val allowed = (if (rotation != 0) rotated[name]?.jsonPrimitive?.double else null)
                ?: errors[name]?.jsonPrimitive?.double ?: 0.0

            if (rotation == 0) assertEquals("$name triangles", expectedTriangles, triangles.size / 3)
            if (expectedTriangles > 0) assertTrue("$name rotation $rotation: deviation $err > $allowed", err <= allowed)
            for (v in triangles) assertTrue(v >= 0 && v < flat.vertices.size / dim)
        }
    }
}
