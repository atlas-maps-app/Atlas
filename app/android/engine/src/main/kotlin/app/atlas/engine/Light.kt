package app.atlas.engine

import app.atlas.geo.Sun
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the light comes from: the sun at [azimuth] (degrees clockwise from north) and [elevation] (degrees above the
 * horizon). [x], [y], [z] point toward the sun in the engine's frame (x east, y south, z up).
 */
class Light(val azimuth: Double, val elevation: Double) {
    val isUp: Boolean get() = elevation > 0.0
    val x: Float
    val y: Float
    val z: Float

    init {
        val a = azimuth * PI / 180
        val e = elevation.coerceAtLeast(0.5) * PI / 180
        x = (sin(a) * cos(e)).toFloat()
        y = (-cos(a) * cos(e)).toFloat()
        z = sin(e).toFloat()
    }

    /** Where a point [height] metres up casts its shadow on the ground, as an offset in metres: x. */
    fun shadowDx(height: Float): Float = -x / z * height
    fun shadowDy(height: Float): Float = -y / z * height

    /** The same light, kept at least [minElevation] high, so a daylight look never goes flat at dusk. */
    fun atLeast(minElevation: Double) = if (elevation >= minElevation) this else Light(azimuth, minElevation)

    override fun equals(other: Any?) = other is Light && other.azimuth == azimuth && other.elevation == elevation
    override fun hashCode() = azimuth.hashCode() * 31 + elevation.hashCode()
    override fun toString() = "Light(az=${"%.1f".format(azimuth)}, el=${"%.1f".format(elevation)})"

    companion object {
        /** An afternoon sun from the south-west: the boards' light. */
        val PLEASANT = Light(215.0, 38.0)

        /** The real sun at [instant] over [lat], [lon]. */
        fun at(instant: Instant, lat: Double, lon: Double): Light {
            val p = Sun.position(instant, lat, lon)
            return Light(p.azimuthDeg, p.altitudeDeg)
        }
    }
}

/**
 * The face-shading formula of the living map: an ambient tint, sky light that favours upward faces, and sun light on
 * faces that face the sun and stand outside every shadow. Night has no sun: colours fall toward the ambient blue.
 */
class Shading(val theme: Theme, val light: Light) {
    private val lx = light.x
    private val ly = light.y
    private val lz = light.z
    private val ambR = Colours.red(theme.ambient) * theme.ambientK * 0.55f * 0.35f
    private val ambG = Colours.green(theme.ambient) * theme.ambientK * 0.55f * 0.35f
    private val ambB = Colours.blue(theme.ambient) * theme.ambientK * 0.55f * 0.35f
    private val baseAmb = theme.ambientK * 0.65f
    private val sunR = 1.05f * theme.sunK * Colours.red(theme.sun) / 255f
    private val sunG = 1.05f * theme.sunK * Colours.green(theme.sun) / 255f
    private val sunB = 1.05f * theme.sunK * Colours.blue(theme.sun) / 255f

    /** How much sun a face with unit normal (nx, ny, nz) catches: 0..1. */
    fun lambert(nx: Float, ny: Float, nz: Float): Float = maxOf(0f, nx * lx + ny * ly + nz * lz)

    /** The colour of a face of [base] colour with unit normal (nx, ny, nz), [lit] when no shadow falls on it. */
    fun shade(base: Int, nx: Float, ny: Float, nz: Float, lit: Boolean): Int {
        val r = Colours.red(base).toFloat()
        val g = Colours.green(base).toFloat()
        val b = Colours.blue(base).toFloat()
        val up = if (nz > 0f) nz else 0f
        if (theme.night || !light.isUp) {
            val k = 0.40f + 0.08f * up
            val ar = Colours.red(theme.ambient); val ag = Colours.green(theme.ambient); val ab = Colours.blue(theme.ambient)
            return Colours.rgb((r * 0.45f + ar * 0.55f) * k, (g * 0.45f + ag * 0.55f) * k, (b * 0.45f + ab * 0.55f) * k)
        }
        val lam = if (lit) lambert(nx, ny, nz) else 0f
        val sky = (0.18f + 0.12f * up) * theme.skyK
        var cr = r * baseAmb + ambR + r * sky + r * lam * sunR
        var cg = g * baseAmb + ambG + g * sky + g * lam * sunG
        var cb = b * baseAmb + ambB + b * sky + b * lam * sunB
        if (theme.tone) {
            cr = tone(cr); cg = tone(cg); cb = tone(cb)
        }
        return Colours.rgb(cr, cg, cb)
    }

    /** A soft shoulder so pale stone stays cream in full sun instead of burning white. */
    private fun tone(v: Float): Float = if (v < 205f) v else 205f + 50f * (1f - kotlin.math.exp(-(v - 205f) / 50f))
}
