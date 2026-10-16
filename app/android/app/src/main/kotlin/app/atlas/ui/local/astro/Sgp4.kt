package app.atlas.ui.local.astro

import java.time.Instant
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Why a propagation failed (the reference code's error numbers 1 to 6). */
enum class Sgp4Error { MEAN_ELEMENTS, MEAN_MOTION, PERT_ELEMENTS, SEMI_LATUS_RECTUM, EPOCH_ELEMENTS_SUB_ORBITAL, DECAYED }

/** Position (km) and velocity (km/s) in the TEME frame, at [minutes] after the element set's epoch. */
class TemeState(val minutes: Double, val x: Double, val y: Double, val z: Double, val vx: Double, val vy: Double, val vz: Double)

/** Which gravity model the element set was fitted with: NORAD's two-line sets use WGS-72. */
enum class Gravity(val mu: Double, val radiusKm: Double, val j2: Double, val j3: Double, val j4: Double, val xke: Double) {
    WGS72(398600.8, 6378.135, 0.001082616, -0.00000253881, -0.00000165597, 60.0 / sqrt(6378.135 * 6378.135 * 6378.135 / 398600.8)),
    WGS72_OLD(398600.79964, 6378.135, 0.001082616, -0.00000253881, -0.00000165597, 0.0743669161),
    WGS84(398600.5, 6378.137, 0.00108262998905, -0.00000253215306, -0.00000161098761, 60.0 / sqrt(6378.137 * 6378.137 * 6378.137 / 398600.5));

    val tumin: Double get() = 1.0 / xke
    val j3oj2: Double get() = j3 / j2
}

/** The numbers of one two-line element set that SGP4 needs, in radians and minutes. */
class Tle(
    val name: String,
    val catalogue: String,
    /** Days since 1949-12-31 00:00 UT (the reference code's epoch). */
    val epochDays1950: Double,
    val bstar: Double,
    val ndot: Double,
    val nddot: Double,
    val inclination: Double,
    val raan: Double,
    val eccentricity: Double,
    val argPerigee: Double,
    val meanAnomaly: Double,
    /** Mean motion in radians per minute (Kozai). */
    val meanMotion: Double,
    val line1: String,
    val line2: String,
) {
    /** The epoch in seconds since 1970-01-01T00:00Z. */
    val epochSeconds: Double get() = (epochDays1950 - 7306.0) * 86_400.0

    /** The element set's epoch as an instant. */
    val epoch: Instant get() = Instant.ofEpochMilli(Math.round(epochSeconds * 1000.0))

    /** Julian date (UT) of the epoch. */
    val epochJd: Double get() = epochDays1950 + 2433281.5

    companion object {
        /**
         * Reads a two-line element set (a name line before them is optional). Null when a line is short, the checksum
         * is wrong or a number does not read.
         */
        fun parse(text: String): Tle? {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val i1 = lines.indexOfFirst { it.startsWith("1 ") && it.length >= 69 }
            if (i1 < 0 || i1 + 1 >= lines.size) return null
            val l1 = lines[i1]
            val l2 = lines[i1 + 1]
            if (!l2.startsWith("2 ") || l2.length < 69) return null
            val name = if (i1 > 0) lines[i1 - 1].trim().removePrefix("0 ") else ""
            return parse(name, l1, l2)
        }

        fun parse(name: String, l1: String, l2: String): Tle? = try {
            if (!checksumOk(l1) || !checksumOk(l2)) null else read(name, l1, l2)
        } catch (e: NumberFormatException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }

        /** The last column is the modulo-10 sum of the digits, minus signs counting one. */
        fun checksumOk(line: String): Boolean {
            if (line.length < 69) return false
            var sum = 0
            for (i in 0 until 68) {
                val c = line[i]
                if (c in '0'..'9') sum += c - '0' else if (c == '-') sum += 1
            }
            return line[68] in '0'..'9' && sum % 10 == line[68] - '0'
        }

        // Fixed columns; the decimal point and exponent of the third and fourth numbers are implied.
        internal fun read(name: String, l1: String, l2: String): Tle {
            val deg = PI / 180.0
            val xpdotp = 1440.0 / (2.0 * PI)
            val yy = l1.substring(18, 20).trim().toInt()
            val year = if (yy < 57) 2000 + yy else 1900 + yy
            val dayOfYear = l1.substring(20, 32).trim().toDouble()
            val ndot = l1.substring(33, 43).trim().toDouble() / (xpdotp * 1440.0)
            val nddot = implied(l1.substring(44, 52)) / (xpdotp * 1440.0 * 1440.0)
            val bstar = implied(l1.substring(53, 61))
            val inclo = l2.substring(8, 16).trim().toDouble() * deg
            val nodeo = l2.substring(17, 25).trim().toDouble() * deg
            val ecco = ("0." + l2.substring(26, 33).trim().replace(' ', '0')).toDouble()
            val argpo = l2.substring(34, 42).trim().toDouble() * deg
            val mo = l2.substring(43, 51).trim().toDouble() * deg
            val noKozai = l2.substring(52, 63).trim().toDouble() / xpdotp
            val start = LocalDate.of(year, 1, 1).toEpochDay()
            // JD of 1950-01-00 is 2433281.5; 1970-01-01 is JD 2440587.5, so 7306 days later.
            val epochDays = (start + 7306).toDouble() + (dayOfYear - 1.0)
            return Tle(name, l1.substring(2, 7).trim(), epochDays, bstar, ndot, nddot, inclo, nodeo, ecco, argpo, mo, noKozai, l1, l2)
        }

        // " 28098-4" is 0.28098e-4; "-11606-4" is -0.11606e-4.
        private fun implied(s: String): Double {
            val t = s.trim()
            if (t.isEmpty()) return 0.0
            val sign = if (t.startsWith("-")) -1.0 else 1.0
            val body = t.trimStart('-', '+', ' ')
            val expAt = body.lastIndexOfAny(charArrayOf('-', '+')).takeIf { it > 0 } ?: return sign * ("0.$body").toDouble()
            val mantissa = ("0." + body.substring(0, expAt)).toDouble()
            return sign * mantissa * 10.0.pow(body.substring(expAt).toInt())
        }
    }
}

/**
 * SGP4 and SDP4, the analytic propagator of NORAD's two-line element sets: a Kotlin port of David Vallado's reference
 * implementation (SGP4.cpp, 2021 version, from "Revisiting Spacetrack Report #3", AIAA 2006-6753; his code is free to use
 * and the algorithms are those of Hoots and Roehrich's Spacetrack Report #3). It follows the reference line by line,
 * variable names included, so the two can be read side by side. Near-Earth and deep-space (period of 225 minutes and
 * more, with the lunar-solar terms and the resonances) sets are both handled.
 *
 * A propagator keeps the integration state of the deep-space resonances between calls, so it is not thread safe: use
 * [propagate] under its lock (it is synchronized), or one instance per thread.
 */
class Sgp4(val tle: Tle, private val gravity: Gravity = Gravity.WGS72, private val afspc: Boolean = false) {
    private val twopi = 2.0 * PI
    private val radiusearthkm = gravity.radiusKm
    private val xke = gravity.xke
    private val j2 = gravity.j2
    private val j3oj2 = gravity.j3oj2
    private val j4 = gravity.j4

    /** The failure of the last [propagate], null when it worked. */
    var error: Sgp4Error? = null
        private set

    /** True for a deep-space element set (period of 225 minutes or more). */
    val deepSpace: Boolean get() = method == 'd'

    // ---- the element record ----
    private var method = 'n'
    private var isimp = 0
    private var aycof = 0.0
    private var con41 = 0.0
    private var cc1 = 0.0
    private var cc4 = 0.0
    private var cc5 = 0.0
    private var d2 = 0.0
    private var d3 = 0.0
    private var d4 = 0.0
    private var delmo = 0.0
    private var eta = 0.0
    private var argpdot = 0.0
    private var omgcof = 0.0
    private var sinmao = 0.0
    private var t2cof = 0.0
    private var t3cof = 0.0
    private var t4cof = 0.0
    private var t5cof = 0.0
    private var x1mth2 = 0.0
    private var x7thm1 = 0.0
    private var mdot = 0.0
    private var nodedot = 0.0
    private var xlcof = 0.0
    private var xmcof = 0.0
    private var nodecf = 0.0
    private var irez = 0
    private var d2201 = 0.0
    private var d2211 = 0.0
    private var d3210 = 0.0
    private var d3222 = 0.0
    private var d4410 = 0.0
    private var d4422 = 0.0
    private var d5220 = 0.0
    private var d5232 = 0.0
    private var d5421 = 0.0
    private var d5433 = 0.0
    private var dedt = 0.0
    private var del1 = 0.0
    private var del2 = 0.0
    private var del3 = 0.0
    private var didt = 0.0
    private var dmdt = 0.0
    private var dnodt = 0.0
    private var domdt = 0.0
    private var e3 = 0.0
    private var ee2 = 0.0
    private var peo = 0.0
    private var pgho = 0.0
    private var pho = 0.0
    private var pinco = 0.0
    private var plo = 0.0
    private var se2 = 0.0
    private var se3 = 0.0
    private var sgh2 = 0.0
    private var sgh3 = 0.0
    private var sgh4 = 0.0
    private var sh2 = 0.0
    private var sh3 = 0.0
    private var si2 = 0.0
    private var si3 = 0.0
    private var sl2 = 0.0
    private var sl3 = 0.0
    private var sl4 = 0.0
    private var gsto = 0.0
    private var xfact = 0.0
    private var xgh2 = 0.0
    private var xgh3 = 0.0
    private var xgh4 = 0.0
    private var xh2 = 0.0
    private var xh3 = 0.0
    private var xi2 = 0.0
    private var xi3 = 0.0
    private var xl2 = 0.0
    private var xl3 = 0.0
    private var xl4 = 0.0
    private var xlamo = 0.0
    private var zmol = 0.0
    private var zmos = 0.0
    private var atime = 0.0
    private var xli = 0.0
    private var xni = 0.0
    private var no_unkozai = 0.0
    private var init = 'y'
    private var t = 0.0

    // The elements as they are changed during initialisation (the reference passes them by reference).
    private var ecco = tle.eccentricity
    private var argpo = tle.argPerigee
    private var inclo = tle.inclination
    private var mo = tle.meanAnomaly
    private var nodeo = tle.raan
    private val bstar = tle.bstar
    private val no_kozai = tle.meanMotion

    /** The mean elements after the last propagation, for tests: semi-major axis in Earth radii. */
    var am = 0.0
        private set

    init {
        initialise()
    }

    /**
     * The state [minutes] after the epoch, or null when the elements decay or break down (see [error]). Positions are
     * in the true equator, mean equinox frame (TEME).
     */
    @Synchronized
    fun propagate(minutes: Double): TemeState? {
        val r = DoubleArray(3)
        val v = DoubleArray(3)
        return if (sgp4(minutes, r, v)) TemeState(minutes, r[0], r[1], r[2], v[0], v[1], v[2]) else null
    }

    /** The state at [instant]. */
    fun propagate(instant: Instant): TemeState? = propagate(minutesSinceEpoch(instant))

    /** Minutes from the element set's epoch to [instant]. */
    fun minutesSinceEpoch(instant: Instant): Double =
        ((instant.epochSecond - tle.epochSeconds) + instant.nano / 1e9) / 60.0

    private class Dscom {
        var snodm = 0.0; var cnodm = 0.0; var sinim = 0.0; var cosim = 0.0; var sinomm = 0.0; var cosomm = 0.0
        var day = 0.0; var em = 0.0; var emsq = 0.0; var gam = 0.0; var rtemsq = 0.0
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var s5 = 0.0; var s6 = 0.0; var s7 = 0.0
        var ss1 = 0.0; var ss2 = 0.0; var ss3 = 0.0; var ss4 = 0.0; var ss5 = 0.0; var ss6 = 0.0; var ss7 = 0.0
        var sz1 = 0.0; var sz2 = 0.0; var sz3 = 0.0
        var sz11 = 0.0; var sz12 = 0.0; var sz13 = 0.0; var sz21 = 0.0; var sz22 = 0.0; var sz23 = 0.0
        var sz31 = 0.0; var sz32 = 0.0; var sz33 = 0.0
        var nm = 0.0
        var z1 = 0.0; var z2 = 0.0; var z3 = 0.0
        var z11 = 0.0; var z12 = 0.0; var z13 = 0.0; var z21 = 0.0; var z22 = 0.0; var z23 = 0.0
        var z31 = 0.0; var z32 = 0.0; var z33 = 0.0
    }

    /** The perturbed elements dpper works on, in and out. */
    private class Elements(var ep: Double, var inclp: Double, var nodep: Double, var argpp: Double, var mp: Double)

    private fun dpper(t: Double, inclo: Double, init: Char, el: Elements) {
        val zns = 1.19459e-5
        val zes = 0.01675
        val znl = 1.5835218e-4
        val zel = 0.05490
        var zm = zmos + zns * t
        if (init == 'y') zm = zmos
        var zf = zm + 2.0 * zes * sin(zm)
        var sinzf = sin(zf)
        var f2 = 0.5 * sinzf * sinzf - 0.25
        var f3 = -0.5 * sinzf * cos(zf)
        val ses = se2 * f2 + se3 * f3
        val sis = si2 * f2 + si3 * f3
        val sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf
        val sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf
        val shs = sh2 * f2 + sh3 * f3
        zm = zmol + znl * t
        if (init == 'y') zm = zmol
        zf = zm + 2.0 * zel * sin(zm)
        sinzf = sin(zf)
        f2 = 0.5 * sinzf * sinzf - 0.25
        f3 = -0.5 * sinzf * cos(zf)
        val sel = ee2 * f2 + e3 * f3
        val sil = xi2 * f2 + xi3 * f3
        val sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf
        val sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf
        val shll = xh2 * f2 + xh3 * f3
        var pe = ses + sel
        var pinc = sis + sil
        var pl = sls + sll
        var pgh = sghs + sghl
        var ph = shs + shll
        if (init == 'n') {
            pe -= peo
            pinc -= pinco
            pl -= plo
            pgh -= pgho
            ph -= pho
            el.inclp += pinc
            el.ep += pe
            val sinip = sin(el.inclp)
            val cosip = cos(el.inclp)
            if (el.inclp >= 0.2) {
                ph /= sinip
                pgh -= cosip * ph
                el.argpp += pgh
                el.nodep += ph
                el.mp += pl
            } else {
                val sinop = sin(el.nodep)
                val cosop = cos(el.nodep)
                var alfdp = sinip * sinop
                var betdp = sinip * cosop
                val dalf = ph * cosop + pinc * cosip * sinop
                val dbet = -ph * sinop + pinc * cosip * cosop
                alfdp += dalf
                betdp += dbet
                el.nodep %= twopi
                if (el.nodep < 0.0 && afspc) el.nodep += twopi
                var xls = el.mp + el.argpp + cosip * el.nodep
                val dls = pl + pgh - pinc * el.nodep * sinip
                xls += dls
                val xnoh = el.nodep
                el.nodep = atan2(alfdp, betdp)
                if (el.nodep < 0.0 && afspc) el.nodep += twopi
                if (abs(xnoh - el.nodep) > PI) {
                    if (el.nodep < xnoh) el.nodep += twopi else el.nodep -= twopi
                }
                el.mp += pl
                el.argpp = xls - el.mp - cosip * el.nodep
            }
        }
    }

    private fun dscom(epoch: Double, ep: Double, argpp: Double, tc: Double, inclp: Double, nodep: Double, np: Double, d: Dscom) {
        val zes = 0.01675
        val zel = 0.05490
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458
        d.nm = np
        d.em = ep
        d.snodm = sin(nodep)
        d.cnodm = cos(nodep)
        d.sinomm = sin(argpp)
        d.cosomm = cos(argpp)
        d.sinim = sin(inclp)
        d.cosim = cos(inclp)
        d.emsq = d.em * d.em
        val betasq = 1.0 - d.emsq
        d.rtemsq = sqrt(betasq)
        peo = 0.0
        pinco = 0.0
        plo = 0.0
        pgho = 0.0
        pho = 0.0
        d.day = epoch + 18261.5 + tc / 1440.0
        val xnodce = (4.5236020 - 9.2422029e-4 * d.day) % twopi
        val stem = sin(xnodce)
        val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1.0 - zcosil * zcosil)
        val zsinhl = 0.089683511 * stem / zsinil
        val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
        d.gam = 5.8351514 + 0.0019443680 * d.day
        var zx = 0.39785416 * stem / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx = d.gam + zx - xnodce
        val zcosgl = cos(zx)
        val zsingl = sin(zx)
        var zcosg = zcosgs
        var zsing = zsings
        var zcosi = zcosis
        var zsini = zsinis
        var zcosh = d.cnodm
        var zsinh = d.snodm
        var cc = c1ss
        val xnoi = 1.0 / d.nm
        for (lsflg in 1..2) {
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = d.cosim * a7 + d.sinim * a8
            val a4 = d.cosim * a9 + d.sinim * a10
            val a5 = -d.sinim * a7 + d.cosim * a8
            val a6 = -d.sinim * a9 + d.cosim * a10
            val x1 = a1 * d.cosomm + a2 * d.sinomm
            val x2 = a3 * d.cosomm + a4 * d.sinomm
            val x3 = -a1 * d.sinomm + a2 * d.cosomm
            val x4 = -a3 * d.sinomm + a4 * d.cosomm
            val x5 = a5 * d.sinomm
            val x6 = a6 * d.sinomm
            val x7 = a5 * d.cosomm
            val x8 = a6 * d.cosomm
            d.z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
            d.z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
            d.z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4
            d.z1 = 3.0 * (a1 * a1 + a2 * a2) + d.z31 * d.emsq
            d.z2 = 6.0 * (a1 * a3 + a2 * a4) + d.z32 * d.emsq
            d.z3 = 3.0 * (a3 * a3 + a4 * a4) + d.z33 * d.emsq
            d.z11 = -6.0 * a1 * a5 + d.emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
            d.z12 = -6.0 * (a1 * a6 + a3 * a5) + d.emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5))
            d.z13 = -6.0 * a3 * a6 + d.emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)
            d.z21 = 6.0 * a2 * a5 + d.emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
            d.z22 = 6.0 * (a4 * a5 + a2 * a6) + d.emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
            d.z23 = 6.0 * a4 * a6 + d.emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)
            d.z1 = d.z1 + d.z1 + betasq * d.z31
            d.z2 = d.z2 + d.z2 + betasq * d.z32
            d.z3 = d.z3 + d.z3 + betasq * d.z33
            d.s3 = cc * xnoi
            d.s2 = -0.5 * d.s3 / d.rtemsq
            d.s4 = d.s3 * d.rtemsq
            d.s1 = -15.0 * d.em * d.s4
            d.s5 = x1 * x3 + x2 * x4
            d.s6 = x2 * x3 + x1 * x4
            d.s7 = x2 * x4 - x1 * x3
            if (lsflg == 1) {
                d.ss1 = d.s1; d.ss2 = d.s2; d.ss3 = d.s3; d.ss4 = d.s4; d.ss5 = d.s5; d.ss6 = d.s6; d.ss7 = d.s7
                d.sz1 = d.z1; d.sz2 = d.z2; d.sz3 = d.z3
                d.sz11 = d.z11; d.sz12 = d.z12; d.sz13 = d.z13
                d.sz21 = d.z21; d.sz22 = d.z22; d.sz23 = d.z23
                d.sz31 = d.z31; d.sz32 = d.z32; d.sz33 = d.z33
                zcosg = zcosgl
                zsing = zsingl
                zcosi = zcosil
                zsini = zsinil
                zcosh = zcoshl * d.cnodm + zsinhl * d.snodm
                zsinh = d.snodm * zcoshl - d.cnodm * zsinhl
                cc = c1l
            }
        }
        zmol = (4.7199672 + 0.22997150 * d.day - d.gam) % twopi
        zmos = (6.2565837 + 0.017201977 * d.day) % twopi
        se2 = 2.0 * d.ss1 * d.ss6
        se3 = 2.0 * d.ss1 * d.ss7
        si2 = 2.0 * d.ss2 * d.sz12
        si3 = 2.0 * d.ss2 * (d.sz13 - d.sz11)
        sl2 = -2.0 * d.ss3 * d.sz2
        sl3 = -2.0 * d.ss3 * (d.sz3 - d.sz1)
        sl4 = -2.0 * d.ss3 * (-21.0 - 9.0 * d.emsq) * zes
        sgh2 = 2.0 * d.ss4 * d.sz32
        sgh3 = 2.0 * d.ss4 * (d.sz33 - d.sz31)
        sgh4 = -18.0 * d.ss4 * zes
        sh2 = -2.0 * d.ss2 * d.sz22
        sh3 = -2.0 * d.ss2 * (d.sz23 - d.sz21)
        ee2 = 2.0 * d.s1 * d.s6
        e3 = 2.0 * d.s1 * d.s7
        xi2 = 2.0 * d.s2 * d.z12
        xi3 = 2.0 * d.s2 * (d.z13 - d.z11)
        xl2 = -2.0 * d.s3 * d.z2
        xl3 = -2.0 * d.s3 * (d.z3 - d.z1)
        xl4 = -2.0 * d.s3 * (-21.0 - 9.0 * d.emsq) * zel
        xgh2 = 2.0 * d.s4 * d.z32
        xgh3 = 2.0 * d.s4 * (d.z33 - d.z31)
        xgh4 = -18.0 * d.s4 * zel
        xh2 = -2.0 * d.s2 * d.z22
        xh3 = -2.0 * d.s2 * (d.z23 - d.z21)
    }

    /** The integration state dsinit and dspace pass between them. */
    private class Secular(var em: Double, var argpm: Double, var inclm: Double, var mm: Double, var nm: Double, var nodem: Double)

    private fun dsinit(
        cosim: Double, emsq0: Double, s1: Double, s2: Double, s3: Double, s4: Double, s5: Double, sinim: Double,
        d: Dscom, t: Double, tc: Double, xpidot: Double, eccsq: Double, sec: Secular,
    ) {
        var emsq = emsq0
        val q22 = 1.7891679e-6
        val q31 = 2.1460748e-6
        val q33 = 2.2123015e-7
        val root22 = 1.7891679e-6
        val root44 = 7.3636953e-9
        val root54 = 2.1765803e-9
        val rptim = 4.37526908801129966e-3
        val root32 = 3.7393792e-7
        val root52 = 1.1428639e-7
        val x2o3 = 2.0 / 3.0
        val znl = 1.5835218e-4
        val zns = 1.19459e-5
        var aonv = 0.0
        irez = 0
        if (sec.nm < 0.0052359877 && sec.nm > 0.0034906585) irez = 1
        if (sec.nm >= 8.26e-3 && sec.nm <= 9.24e-3 && sec.em >= 0.5) irez = 2
        val ses = d.ss1 * zns * d.ss5
        val sis = d.ss2 * zns * (d.sz11 + d.sz13)
        val sls = -zns * d.ss3 * (d.sz1 + d.sz3 - 14.0 - 6.0 * emsq)
        val sghs = d.ss4 * zns * (d.sz31 + d.sz33 - 6.0)
        var shs = -zns * d.ss2 * (d.sz21 + d.sz23)
        if (sec.inclm < 5.2359877e-2 || sec.inclm > PI - 5.2359877e-2) shs = 0.0
        if (sinim != 0.0) shs /= sinim
        val sgs = sghs - cosim * shs
        dedt = ses + s1 * znl * s5
        didt = sis + s2 * znl * (d.z11 + d.z13)
        dmdt = sls - znl * s3 * (d.z1 + d.z3 - 14.0 - 6.0 * emsq)
        val sghl = s4 * znl * (d.z31 + d.z33 - 6.0)
        var shll = -znl * s2 * (d.z21 + d.z23)
        if (sec.inclm < 5.2359877e-2 || sec.inclm > PI - 5.2359877e-2) shll = 0.0
        domdt = sgs + sghl
        dnodt = shs
        if (sinim != 0.0) {
            domdt -= cosim / sinim * shll
            dnodt += shll / sinim
        }
        val theta = (gsto + tc * rptim) % twopi
        sec.em += dedt * t
        sec.inclm += didt * t
        sec.argpm += domdt * t
        sec.nodem += dnodt * t
        sec.mm += dmdt * t
        if (irez != 0) {
            aonv = (sec.nm / xke).pow(x2o3)
            if (irez == 2) {
                val cosisq = cosim * cosim
                val emo = sec.em
                sec.em = ecco
                val emsqo = emsq
                emsq = eccsq
                val em = sec.em
                val eoc = em * emsq
                val g201 = -0.306 - (em - 0.64) * 0.440
                val g211: Double
                val g310: Double
                val g322: Double
                val g410: Double
                val g422: Double
                val g520: Double
                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc
                    g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc
                    g520 = if (em > 0.715) -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc
                    else 1464.74 - 4664.75 * em + 3763.64 * emsq
                }
                val g533: Double
                val g521: Double
                val g532: Double
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc
                }
                val sini2 = sinim * sinim
                val f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq)
                val f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                val f441 = 35.0 * sini2 * f220
                val f442 = 39.3750 * sini2 * sini2
                val f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) + 0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq))
                val f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) + 6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq))
                val f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq))
                val f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq))
                val xno2 = sec.nm * sec.nm
                val ainv2 = aonv * aonv
                var temp1 = 3.0 * xno2 * ainv2
                var temp = temp1 * root22
                d2201 = temp * f220 * g201
                d2211 = temp * f221 * g211
                temp1 *= aonv
                temp = temp1 * root32
                d3210 = temp * f321 * g310
                d3222 = temp * f322 * g322
                temp1 *= aonv
                temp = 2.0 * temp1 * root44
                d4410 = temp * f441 * g410
                d4422 = temp * f442 * g422
                temp1 *= aonv
                temp = temp1 * root52
                d5220 = temp * f522 * g520
                d5232 = temp * f523 * g532
                temp = 2.0 * temp1 * root54
                d5421 = temp * f542 * g521
                d5433 = temp * f543 * g533
                xlamo = (mo + nodeo + nodeo - theta - theta) % twopi
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - no_unkozai
                sec.em = emo
                emsq = emsqo
            }
            if (irez == 1) {
                val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1.0 + 2.0 * emsq
                val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
                val f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim)
                val f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim)
                var f330 = 1.0 + cosim
                f330 = 1.875 * f330 * f330 * f330
                del1 = 3.0 * sec.nm * sec.nm * aonv * aonv
                del2 = 2.0 * del1 * f220 * g200 * q22
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv
                del1 = del1 * f311 * g310 * q31 * aonv
                xlamo = (mo + nodeo + argpo - theta) % twopi
                xfact = mdot + xpidot - rptim + dmdt + domdt + dnodt - no_unkozai
            }
            xli = xlamo
            xni = no_unkozai
            atime = 0.0
            sec.nm = no_unkozai + 0.0
        }
    }

    private fun dspace(t: Double, tc: Double, sec: Secular): Double {
        val fasx2 = 0.13130908
        val fasx4 = 2.8843198
        val fasx6 = 0.37448087
        val g22 = 5.7686396
        val g32 = 0.95240898
        val g44 = 1.8014998
        val g52 = 1.0508330
        val g54 = 4.4108898
        val rptim = 4.37526908801129966e-3
        val stepp = 720.0
        val stepn = -720.0
        val step2 = 259200.0
        var dndt = 0.0
        val theta = (gsto + tc * rptim) % twopi
        sec.em += dedt * t
        sec.inclm += didt * t
        sec.argpm += domdt * t
        sec.nodem += dnodt * t
        sec.mm += dmdt * t
        var ft = 0.0
        if (irez != 0) {
            if (atime == 0.0 || t * atime <= 0.0 || abs(t) < abs(atime)) {
                atime = 0.0
                xni = no_unkozai
                xli = xlamo
            }
            val delt = if (t > 0.0) stepp else stepn
            var iretn = 381
            var xndt = 0.0
            var xldot = 0.0
            var xnddt = 0.0
            while (iretn == 381) {
                if (irez != 2) {
                    xndt = del1 * sin(xli - fasx2) + del2 * sin(2.0 * (xli - fasx4)) + del3 * sin(3.0 * (xli - fasx6))
                    xldot = xni + xfact
                    xnddt = del1 * cos(xli - fasx2) + 2.0 * del2 * cos(2.0 * (xli - fasx4)) + 3.0 * del3 * cos(3.0 * (xli - fasx6))
                    xnddt *= xldot
                } else {
                    val xomi = argpo + argpdot * atime
                    val x2omi = xomi + xomi
                    val x2li = xli + xli
                    xndt = d2201 * sin(x2omi + xli - g22) + d2211 * sin(xli - g22) +
                        d3210 * sin(xomi + xli - g32) + d3222 * sin(-xomi + xli - g32) +
                        d4410 * sin(x2omi + x2li - g44) + d4422 * sin(x2li - g44) +
                        d5220 * sin(xomi + xli - g52) + d5232 * sin(-xomi + xli - g52) +
                        d5421 * sin(xomi + x2li - g54) + d5433 * sin(-xomi + x2li - g54)
                    xldot = xni + xfact
                    xnddt = d2201 * cos(x2omi + xli - g22) + d2211 * cos(xli - g22) +
                        d3210 * cos(xomi + xli - g32) + d3222 * cos(-xomi + xli - g32) +
                        d5220 * cos(xomi + xli - g52) + d5232 * cos(-xomi + xli - g52) +
                        2.0 * (d4410 * cos(x2omi + x2li - g44) + d4422 * cos(x2li - g44) +
                            d5421 * cos(xomi + x2li - g54) + d5433 * cos(-xomi + x2li - g54))
                    xnddt *= xldot
                }
                if (abs(t - atime) >= stepp) {
                    iretn = 381
                } else {
                    ft = t - atime
                    iretn = 0
                }
                if (iretn == 381) {
                    xli = xli + xldot * delt + xndt * step2
                    xni = xni + xndt * delt + xnddt * step2
                    atime += delt
                }
            }
            sec.nm = xni + xndt * ft + xnddt * ft * ft * 0.5
            val xl = xli + xldot * ft + xndt * ft * ft * 0.5
            if (irez != 1) {
                sec.mm = xl - 2.0 * sec.nodem + 2.0 * theta
                dndt = sec.nm - no_unkozai
            } else {
                sec.mm = xl - sec.nodem - sec.argpm + theta
                dndt = sec.nm - no_unkozai
            }
            sec.nm = no_unkozai + dndt
        }
        return dndt
    }

    private fun gstime(jdut1: Double): Double {
        val deg2rad = PI / 180.0
        val tut1 = (jdut1 - 2451545.0) / 36525.0
        var temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841
        temp = (temp * deg2rad / 240.0) % twopi
        if (temp < 0.0) temp += twopi
        return temp
    }

    private fun initialise() {
        val epoch = tle.epochDays1950
        val x2o3 = 2.0 / 3.0
        val temp4 = 1.5e-12
        val ss = 78.0 / radiusearthkm + 1.0
        val qzms2ttemp = (120.0 - 78.0) / radiusearthkm
        val qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp
        init = 'y'
        t = 0.0

        // initl
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val ak = (xke / no_kozai).pow(x2o3)
        val d1 = 0.75 * j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        no_unkozai = no_kozai / (1.0 + del)
        val ao = (xke / no_unkozai).pow(x2o3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)
        method = 'n'
        gsto = gstime(epoch + 2433281.5)

        if (omeosq >= 0.0 || no_unkozai >= 0.0) {
            isimp = 0
            if (rp < 220.0 / radiusearthkm + 1.0) isimp = 1
            var sfour = ss
            var qzms24 = qzms2t
            val perige = (rp - 1.0) * radiusearthkm
            if (perige < 156.0) {
                sfour = perige - 78.0
                if (perige < 98.0) sfour = 20.0
                val qzms24temp = (120.0 - sfour) / radiusearthkm
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp
                sfour = sfour / radiusearthkm + 1.0
            }
            val pinvsq = 1.0 / posq
            val tsi = 1.0 / (ao - sfour)
            eta = ao * ecco * tsi
            val etasq = eta * eta
            val eeta = ecco * eta
            val psisq = abs(1.0 - etasq)
            val coef = qzms24 * tsi.pow(4.0)
            val coef1 = coef / psisq.pow(3.5)
            val cc2 = coef1 * no_unkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * j2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
            cc1 = bstar * cc2
            var cc3 = 0.0
            if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * j3oj2 * no_unkozai * sinio / ecco
            x1mth2 = 1.0 - cosio2
            cc4 = 2.0 * no_unkozai * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
                j2 * tsi / (ao * psisq) * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                    0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
            cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
            val cosio4 = cosio2 * cosio2
            val temp1 = 1.5 * j2 * pinvsq * no_unkozai
            val temp2 = 0.5 * temp1 * j2 * pinvsq
            val temp3 = -0.46875 * j4 * pinvsq * pinvsq * no_unkozai
            mdot = no_unkozai + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
            argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
            val xhdot1 = -temp1 * cosio
            nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
            val xpidot = argpdot + nodedot
            omgcof = bstar * cc3 * cos(argpo)
            xmcof = 0.0
            if (ecco > 1.0e-4) xmcof = -x2o3 * coef * bstar / eeta
            nodecf = 3.5 * omeosq * xhdot1 * cc1
            t2cof = 1.5 * cc1
            xlcof = if (abs(cosio + 1.0) > 1.5e-12) -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
            else -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / temp4
            aycof = -0.5 * j3oj2 * sinio
            val delmotemp = 1.0 + eta * cos(mo)
            delmo = delmotemp * delmotemp * delmotemp
            sinmao = sin(mo)
            x7thm1 = 7.0 * cosio2 - 1.0
            if (2 * PI / no_unkozai >= 225.0) {
                method = 'd'
                isimp = 1
                val tc = 0.0
                val d = Dscom()
                val inclm = inclo
                dscom(epoch, ecco, argpo, tc, inclo, nodeo, no_unkozai, d)
                val el = Elements(ecco, inclo, nodeo, argpo, mo)
                dpper(t, inclm, init, el)
                ecco = el.ep
                inclo = el.inclp
                nodeo = el.nodep
                argpo = el.argpp
                mo = el.mp
                val sec = Secular(d.em, 0.0, inclm, 0.0, d.nm, 0.0)
                dsinit(d.cosim, d.emsq, d.s1, d.s2, d.s3, d.s4, d.s5, d.sinim, d, t, tc, xpidot, eccsq, sec)
            }
            if (isimp != 1) {
                val cc1sq = cc1 * cc1
                d2 = 4.0 * ao * tsi * cc1sq
                val temp = d2 * tsi * cc1 / 3.0
                d3 = (17.0 * ao + sfour) * temp
                d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
                t3cof = d2 + 2.0 * cc1sq
                t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
                t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
            }
        }
        sgp4(0.0, DoubleArray(3), DoubleArray(3))
        init = 'n'
    }

    private fun sgp4(tsince: Double, r: DoubleArray, v: DoubleArray): Boolean {
        val x2o3 = 2.0 / 3.0
        val temp4 = 1.5e-12
        val vkmpersec = radiusearthkm * xke / 60.0
        t = tsince
        error = null
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (isimp != 1) {
            val delomg = omgcof * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        var nm = no_unkozai
        var em = ecco
        var inclm = inclo
        if (method == 'd') {
            val sec = Secular(em, argpm, inclm, mm, nm, nodem)
            dspace(t, t, sec)
            em = sec.em
            argpm = sec.argpm
            inclm = sec.inclm
            mm = sec.mm
            nm = sec.nm
            nodem = sec.nodem
        }
        if (nm <= 0.0) {
            error = Sgp4Error.MEAN_MOTION
            return false
        }
        val am = (xke / nm).pow(x2o3) * tempa * tempa
        this.am = am
        nm = xke / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) {
            error = Sgp4Error.MEAN_ELEMENTS
            return false
        }
        if (em < 1.0e-6) em = 1.0e-6
        mm += no_unkozai * templ
        var xlm = mm + argpm + nodem
        val emsq = em * em
        var temp = 1.0 - emsq
        nodem %= twopi
        argpm %= twopi
        xlm %= twopi
        mm = (xlm - argpm - nodem) % twopi
        var sinim = sin(inclm)
        var cosim = cos(inclm)
        var ep = em
        var xincp = inclm
        var argpp = argpm
        var nodep = nodem
        var mp = mm
        var sinip = sinim
        var cosip = cosim
        if (method == 'd') {
            val el = Elements(ep, xincp, nodep, argpp, mp)
            dpper(t, inclo, 'n', el)
            ep = el.ep; xincp = el.inclp; nodep = el.nodep; argpp = el.argpp; mp = el.mp
            if (xincp < 0.0) {
                xincp = -xincp
                nodep += PI
                argpp -= PI
            }
            if (ep < 0.0 || ep > 1.0) {
                error = Sgp4Error.PERT_ELEMENTS
                return false
            }
        }
        if (method == 'd') {
            sinip = sin(xincp)
            cosip = cos(xincp)
            aycof = -0.5 * j3oj2 * sinip
            xlcof = if (abs(cosip + 1.0) > 1.5e-12) -0.25 * j3oj2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip)
            else -0.25 * j3oj2 * sinip * (3.0 + 5.0 * cosip) / temp4
        }
        val axnl = ep * cos(argpp)
        temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl
        val u = (xl - nodep) % twopi
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) {
            error = Sgp4Error.SEMI_LATUS_RECTUM
            return false
        }
        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * j2 * temp
        val temp2 = temp1 * temp
        if (method == 'd') {
            val cosisq = cosip * cosip
            con41 = 3.0 * cosisq - 1.0
            x1mth2 = 1.0 - cosisq
            x7thm1 = 7.0 * cosisq - 1.0
        }
        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / xke
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / xke
        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu
        r[0] = mrt * ux * radiusearthkm
        r[1] = mrt * uy * radiusearthkm
        r[2] = mrt * uz * radiusearthkm
        v[0] = (mvt * ux + rvdot * vx) * vkmpersec
        v[1] = (mvt * uy + rvdot * vy) * vkmpersec
        v[2] = (mvt * uz + rvdot * vz) * vkmpersec
        if (mrt < 1.0) {
            error = Sgp4Error.DECAYED
            return false
        }
        return true
    }

    companion object {
        /** Greenwich mean sidereal time in radians at the Julian date [jdUt1], the reference code's `gstime`. */
        fun gmst(jdUt1: Double): Double {
            val tut1 = (jdUt1 - 2451545.0) / 36525.0
            var temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841
            temp = (temp * (PI / 180.0) / 240.0) % (2 * PI)
            if (temp < 0.0) temp += 2 * PI
            return temp
        }
    }
}
