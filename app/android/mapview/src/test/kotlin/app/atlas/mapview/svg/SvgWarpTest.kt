package app.atlas.mapview.svg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** [SvgCamera] against `render/city.py`'s Camera, and [SvgWarp]'s homographies against both cameras. */
class SvgWarpTest {
    private val base = SvgCamera(10.0, 0.0, -5.0, 30.0, 35.0, 400.0, 40.0, 412.0, 915.0, cy = 380.0)

    @Test
    fun cameraMatchesThePythonEngine() {
        val repo = SvgCompare.repo
        assumeTrue(File(repo, "render/city.py").isFile)
        val script = """
            import sys, json
            sys.path.insert(0, 'render')
            from city import Camera
            out = []
            for (t, yaw, pitch, dist, fov, cy) in [((10.0, 0.0, -5.0), 30.0, 35.0, 400.0, 40.0, 380.0), ((-120.5, 3.0, 88.0), -147.0, 62.0, 1500.0, 32.0, None)]:
                c = Camera(t, yaw, pitch, dist, fov, 412, 915, cy=cy)
                pts = [c.proj(p) for p in [(50.0, 0.0, 20.0), (-300.0, 12.0, -800.0), (0.0, 0.0, 0.0)]]
                out.append({'p': pts, 'h': c.horizon_y(), 'g': c.ground_depth(700.0), 'f': c.focal})
            print(json.dumps(out))
        """.trimIndent()
        val p = try {
            ProcessBuilder("python3", "-c", script).directory(repo).redirectErrorStream(true)
                .apply { environment()["PYTHONHASHSEED"] = "0" }.start()
        } catch (e: java.io.IOException) {
            assumeTrue("no python3", false)
            return
        }
        val text = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(text, 0, p.exitValue())
        val nums = Regex("-?\\d+\\.\\d+(?:e-?\\d+)?|null").findAll(text).map { it.value }.toList()
        val cams = listOf(base, SvgCamera(-120.5, 3.0, 88.0, -147.0, 62.0, 1500.0, 32.0, 412.0, 915.0))
        var k = 0
        for (c in cams) {
            for (pt in listOf(doubleArrayOf(50.0, 0.0, 20.0), doubleArrayOf(-300.0, 12.0, -800.0), doubleArrayOf(0.0, 0.0, 0.0))) {
                val s = c.proj(pt[0], pt[1], pt[2])
                if (nums[k] == "null") {
                    assertNull(s)
                    k++
                } else {
                    assertEquals(nums[k].toDouble(), s!![0], 1e-9)
                    assertEquals(nums[k + 1].toDouble(), s[1], 1e-9)
                    k += 2
                }
            }
            assertEquals(nums[k++].toDouble(), c.horizonY(), 1e-9)
            assertEquals(nums[k++].toDouble(), c.groundDepth(700.0)!!, 1e-9)
            assertEquals(nums[k++].toDouble(), c.focal, 1e-9)
        }
    }

    @Test
    fun groundAtInvertsTheProjection() {
        for ((x, z) in listOf(50.0 to 20.0, -40.0 to -90.0, 100.0 to 100.0)) {
            val s = base.proj(x, 0.0, z)!!
            val g = base.groundAt(s[0], s[1])!!
            assertEquals(x, g[0], 1e-7)
            assertEquals(z, g[2], 1e-7)
        }
        assertNull(base.groundAt(206.0, base.horizonY() - 5))
    }

    @Test
    fun theGroundHomographyIsExactForGroundPoints() {
        val moves = listOf(
            base.copy(yaw = 38.0),
            base.copy(pitch = 30.0, dist = 330.0),
            base.copy(targetX = 40.0, targetZ = -30.0),
            base.copy(yaw = 20.0, pitch = 45.0, dist = 520.0, targetX = -20.0),
        )
        for (to in moves) {
            val w = SvgWarp.between(base, to)
            val m = w.ground!!
            for ((x, z) in listOf(0.0 to 0.0, 60.0 to 40.0, -80.0 to -60.0, 30.0 to 90.0)) {
                val a = base.proj(x, 0.0, z) ?: continue
                val b = to.proj(x, 0.0, z) ?: continue
                val p = Homography.map(m, a[0], a[1])!!
                assertEquals(b[0], p[0], 1e-6)
                assertEquals(b[1], p[1], 1e-6)
            }
            // The sky: directions at infinity move by the rotation alone.
            val sky = w.sky!!
            for ((sx, sy) in listOf(10.0 to 10.0, 300.0 to 100.0)) {
                val expect = to.projDirection(base.ray(sx, sy)) ?: continue
                val p = Homography.map(sky, sx, sy)!!
                assertEquals(expect[0], p[0], 1e-6)
                assertEquals(expect[1], p[1], 1e-6)
            }
            assertTrue(w.groundClip.size >= 6)
        }
    }

    @Test
    fun theSameCameraWarpsToIdentity() {
        val w = SvgWarp.between(base, base)
        val m = w.ground!!
        for ((x, y) in listOf(0.0 to 915.0, 206.0 to 600.0, 400.0 to 500.0)) {
            val p = Homography.map(m, x, y)!!
            assertEquals(x, p[0], 1e-6)
            assertEquals(y, p[1], 1e-6)
        }
        val inv = Homography.invert(m)!!
        val id = Homography.multiply(m, inv)
        for (i in 0 until 9) assertEquals(if (i % 4 == 0) 1.0 else 0.0, id[i] / id[8], 1e-9)
    }

    @Test
    fun groundBehindTheNewCameraIsClippedAway() {
        // Moving forward past the bottom of the old view: part of the old ground is now behind the camera.
        val to = base.copy(dist = 120.0, pitch = 10.0)
        val w = SvgWarp.between(base, to)
        val m = w.ground
        assertNotNull(m)
        val poly = w.groundClip
        for (i in 0 until poly.size / 2) {
            val ww = m!![6] * poly[2 * i] + m[7] * poly[2 * i + 1] + m[8]
            assertTrue(ww >= 0)
        }
    }

    @Test
    fun clippingAPolygonByAHalfPlane() {
        // w = x − 5 > 0 keeps the right half of a 10 × 10 square.
        val m = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, -5.0)
        val out = SvgWarp.inFront(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0), m, 0.0)
        val b = Homography.bounds(out)
        assertEquals(5.0, b[0], 1e-9)
        assertEquals(10.0, b[2], 1e-9)
        assertEquals(0.0, b[1], 1e-9)
        assertEquals(10.0, b[3], 1e-9)
    }
}
