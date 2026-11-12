package app.atlas.rendertiles.model

/**
 * The heights layer of format 3 (ENGINE.md part 6, terrain): one height raster per map tile, in a tile of a terrain pack
 * ([Profile.TERRAIN]). This file is the specification the core's reader (`CP/tiles/Heights`) follows;
 * [app.atlas.rendertiles.codec.HeightsCodec] writes and reads it.
 *
 * **Where the samples lie.** A raster of side n has n × n samples over its tile, the edges included: sample (r, c), row
 * r from the north and column c from the west, lies at the tile pyramid's point `X = (x + c / (n − 1)) / 2^z`,
 * `Y = (y + r / (n − 1)) / 2^z`, whose longitude is `360·X − 180` and whose latitude is Web Mercator's
 * `atan(sinh(π·(1 − 2Y)))` ([lonOf], [latOf]). So the samples of a level lie on one lattice over the world: a tile's edge
 * samples are its neighbours' edge samples, and every second sample of a level is a sample of the level above, which is
 * what lets a tile without a raster of its own level read the coarser one cut to its box. Beyond each edge lie [apron]
 * more samples of the same lattice, the neighbours' own, so normals, sky-view and the near horizons see the same samples
 * on both sides of an edge. The raster is stored whole, `w = n + 2·apron` samples a row, `w` rows, from the north-west
 * corner of the apron.
 *
 * **Heights** are orthometric metres in their source's datum (EGM2008 for Copernicus; each national model's own, all
 * within about a metre of mean sea level). A sample's height is `step · (base + v)`: [step] metres (a double; the packer
 * takes the power of two nearest [STEP_SHARE] of the samples' spacing, so tiles side by side share their step and their
 * shared samples are equal to the bit, and a coarser level's step is a multiple of a finer one's), [base] the raster's
 * lowest sample in steps, and `v` the sample in steps above it, 0 to [max].
 *
 * **The layer** ([Layer.HEIGHTS]) holds one feature, the raster, counted in the tile's first cell, whose box is the
 * tile's and whose top is the raster's highest sample (centimetres, at least 0). Its columns, in the format's own
 * column layout (a varint string index for the name, a codec byte, a varint length, the bytes), with the format's
 * codecs and one of its own:
 * - `side` ([Codec.VARU]): n, 2^k + 1 with k from 1 to 8 (the packer writes [SIDES]);
 * - `apron` (VARU): the samples beyond each edge, at most [MAX_APRON] (absent: 0);
 * - `step` ([Codec.F64]): metres, finite and above 0;
 * - `base` ([Codec.VARZ]): the lowest sample, in steps (absent: 0);
 * - `max` (VARU): the highest sample in steps above the base, at most [MAX_STEPS];
 * - `flags` (VARU): [FLAG_NODATA], [FLAG_FILLED]; a reader ignores the bits it does not know (absent: 0);
 * - `sources` ([Codec.STRS]) and `shares` ([Codec.IDS]): the registry ids of the sources the samples came from
 *   (`packs/terrain-sources.json`) and how many of the w × w samples each gave, in the order of their priority; the
 *   shares add up to at most w × w, and what is left had no source (absent: none);
 * - `samples` ([CODEC_MED], 19): a varint w, a varint w again (the rows), then w × w zig-zag varints, row by row: each
 *   sample minus its prediction from its west (a), north (b) and north-west (c) neighbours by LOCO-I's median edge
 *   detector: min(a, b) where c ≥ max(a, b), max(a, b) where c ≤ min(a, b), else a + b − c. Outside the raster, a is
 *   the sample above (0 for the first sample), b is a, and c is b.
 * A reader skips a column it does not know, so a later column (a mask, a baked sky-view) is no change of the format.
 *
 * **What a reader checks:** one feature; a side and an apron it allows; the samples' width and rows equal to
 * n + 2·apron; every sample within 0 to `max` as it is summed (so nothing overflows), the lowest exactly 0 and the
 * highest exactly `max`; no byte left over; as many shares as sources, none negative, their sum at most w × w.
 */
object Heights {
    /** The sides the packer writes, finest first: a raster is capped at its source's own resolution. */
    val SIDES: IntArray get() = intArrayOf(257, 129, 65, 33, 17)

    /** The samples beyond each edge the packer writes. */
    const val APRON = 8

    /** The most a reader allows. */
    const val MAX_APRON = 32

    /** The step as a share of the samples' spacing: a slope step of 0.6°. */
    const val STEP_SHARE = 0.01

    /** The most steps a sample may stand above the base: no height on Earth at a step of a millimetre passes it. */
    const val MAX_STEPS = 1 shl 28

    /** At least one sample has no source and stands at 0 m (the open sea beyond every source). */
    const val FLAG_NODATA = 1

    /** At least one sample was filled from its surroundings: its source left it empty (water, a building). */
    const val FLAG_FILLED = 2

    /** The codec of the `samples` column, the layer's own. */
    const val CODEC_MED = 19

    /** The column names. */
    const val SIDE = "side"
    const val APRON_COLUMN = "apron"
    const val STEP = "step"
    const val BASE = "base"
    const val MAX = "max"
    const val FLAGS = "flags"
    const val SOURCES = "sources"
    const val SHARES = "shares"
    const val SAMPLES = "samples"

    /** Whether [side] is one a reader allows: 2^k + 1, k from 1 to 8. */
    fun sideAllowed(side: Int): Boolean = side in 3..257 && (side - 1) and (side - 2) == 0

    /** The longitude, degrees, of lattice column [c] of tile column [x] at [level] with [side] samples a side. */
    fun lonOf(level: Int, x: Int, c: Int, side: Int): Double = (x + c.toDouble() / (side - 1)) / (1 shl level) * 360.0 - 180.0

    /** The latitude, degrees, of lattice row [r] of tile row [y] at [level] with [side] samples a side. */
    fun latOf(level: Int, y: Int, r: Int, side: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * (y + r.toDouble() / (side - 1)) / (1 shl level)
        return Math.toDegrees(StrictMath.atan(StrictMath.sinh(n)))
    }

    /** The equator's length on Web Mercator's sphere, metres. */
    const val EQUATOR = 2.0 * Math.PI * 6378137.0

    /** The samples' spacing on the ground, metres, in the middle of tile row [y] at [level] with [side] samples a side. */
    fun spacing(level: Int, y: Int, side: Int): Double =
        EQUATOR * StrictMath.cos(Math.toRadians(latOf(level, y, (side - 1) / 2, side))) / (1 shl level) / (side - 1)

    /** The packer's step for samples [spacing] metres apart: the power of two nearest [STEP_SHARE] of it. */
    fun step(spacing: Double): Double = StrictMath.pow(2.0, Math.rint(StrictMath.log(STEP_SHARE * spacing) / StrictMath.log(2.0)))

    /**
     * The side a source of [resolution] metres gives tile row [y] at [level]: the finest of [SIDES] whose samples lie no
     * closer than [CAP] of the source's own, so nothing is oversampled; null where even the coarsest would be.
     */
    fun sideFor(level: Int, y: Int, resolution: Double): Int? = SIDES.firstOrNull { spacing(level, y, it) >= CAP * resolution }

    /** How much finer than its source a raster may be sampled: TINITALY's 10 m stops at L13, GLO-30's 30 m at L12. */
    const val CAP = 0.75
}

/**
 * One tile's raster ([Heights]): [side] samples a side with [apron] beyond each edge, [samples] the w × w values in
 * steps above [base], row by row from the apron's north-west corner.
 */
class HeightRaster(
    val side: Int,
    val apron: Int,
    val step: Double,
    val base: Long,
    val samples: IntArray,
    val flags: Int,
    val sources: List<String>,
    val shares: List<Long>,
) {
    /** Samples a row, and rows. */
    val width: Int get() = side + 2 * apron

    /** The highest sample, in steps above the base. */
    val max: Int = samples.maxOrNull() ?: 0

    init {
        require(Heights.sideAllowed(side)) { "a raster of side $side" }
        require(apron in 0..Heights.MAX_APRON) { "an apron of $apron" }
        require(step > 0 && step.isFinite()) { "a step of $step" }
        require(samples.size == width * width) { "${samples.size} samples for a raster of $width a row" }
        require((samples.minOrNull() ?: 0) == 0 && max <= Heights.MAX_STEPS) { "samples from ${samples.minOrNull()} to $max steps above the base" }
        require(shares.size == sources.size && shares.all { it >= 0 } && shares.sum() <= samples.size.toLong()) { "shares $shares of ${samples.size} samples" }
    }

    /** The sample at row [r], column [c] of the tile (each −[apron] to [side] − 1 + [apron]), in steps: `base + v`. */
    fun steps(r: Int, c: Int): Long = base + samples[(r + apron) * width + c + apron]

    /** Its height, metres. */
    fun metres(r: Int, c: Int): Double = step * steps(r, c)

    companion object {
        /**
         * The raster of [heights] (metres, w × w, row by row from the apron's north-west corner), each rounded to the
         * nearest step (ties to even, as `rint` rounds them, on the lattice of whole steps from 0 m, so the same height
         * rounds alike in every tile).
         */
        fun of(heights: FloatArray, side: Int, apron: Int, step: Double, flags: Int, sources: List<String>, shares: List<Long>): HeightRaster {
            val q = LongArray(heights.size) { Math.rint(heights[it].toDouble() / step).toLong() }
            val base = q.minOrNull() ?: 0L
            return HeightRaster(side, apron, step, base, IntArray(q.size) { Math.toIntExact(q[it] - base) }, flags, sources, shares)
        }
    }
}
