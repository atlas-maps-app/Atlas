package app.atlas.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LabelPainter's placement (plan-vector D13, K13), pinned so that the vector engine's port is held to it: 400 generated
 * frames (frames of phone sizes and densities, keep-out boxes, markers, labels of every priority with up to six points,
 * a tenth of them not projected) placed as LabelPainter places them, through [LabelPlacement], hash to the value the
 * core's `CP/labels/Placement.cpp` gives for the same frames (`CP/tests/labels/placement_test.cpp` pins the same hash).
 * The generator is SplitMix64 with floats made exactly, in both languages.
 */
class LabelPlacementTest {
    /** SplitMix64, and floats in [0, 1) from its top 24 bits, exact. */
    private class Mix(seed: Long) {
        private var state = seed
        fun next(): Long {
            state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }
        fun below(n: Int): Int = java.lang.Long.remainderUnsigned(next(), n.toLong()).toInt()
        fun unit(): Float = (next() ushr 40).toFloat() / 16777216f
    }

    /** FNV-1a, 64 bits, over little-endian ints. */
    private class Fnv {
        var hash = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
        fun int(v: Int) {
            for (k in 0 until 4) {
                hash = hash xor ((v ushr (8 * k)) and 0xFF).toLong()
                hash *= 0x100000001B3L
            }
        }
    }

    @Test
    fun theGeneratedFramesPlaceAsPinned() {
        val mix = Mix(0x4B3133L) // "K13"
        val fnv = Fnv()
        val placement = LabelPlacement()
        val at = FloatArray(2)
        var labels = 0
        var placed = 0
        val spots = IntArray(LabelPlacement.SPOTS.size / 2)
        for (frame in 0 until 400) {
            val width = (600 + mix.below(900)).toFloat()
            val height = (900 + mix.below(1500)).toFloat()
            val density = floatArrayOf(2f, 2.625f, 3f, 3.5f)[mix.below(4)]
            val pad = 4f * density
            val keepOutCount = mix.below(5)
            val keepOut = FloatArray(4 * keepOutCount)
            for (i in 0 until keepOutCount) {
                val l = mix.unit() * width
                val t = mix.unit() * height
                val r = l + (20f + mix.unit() * 300f)
                val b = t + (20f + mix.unit() * 120f)
                keepOut[4 * i] = l; keepOut[4 * i + 1] = t; keepOut[4 * i + 2] = r; keepOut[4 * i + 3] = b
            }
            val markerCount = mix.below(8)
            val markers = FloatArray(4 * markerCount)
            for (i in 0 until markerCount) {
                val l = mix.unit() * width
                val t = mix.unit() * height
                markers[4 * i] = l; markers[4 * i + 1] = t; markers[4 * i + 2] = l + 30f * density; markers[4 * i + 3] = t + 30f * density
            }
            val n = 10 + mix.below(50)
            val priority = IntArray(n)
            val half = FloatArray(n)
            val textH = FloatArray(n)
            val points = ArrayList<FloatArray>(n)
            for (i in 0 until n) {
                priority[i] = mix.below(6)
                half[i] = 10f + mix.unit() * 300f + pad
                textH[i] = 12f + mix.unit() * 60f
                val count = 1 + mix.below(6)
                val p = FloatArray(2 * count)
                for (k in 0 until count) {
                    if (mix.below(10) == 0) {
                        p[2 * k] = Float.NaN; p[2 * k + 1] = Float.NaN
                    } else {
                        val x = -60f + mix.unit() * (width + 120f)
                        val y = -60f + mix.unit() * (height + 120f)
                        p[2 * k] = x; p[2 * k + 1] = y
                    }
                }
                points += p
            }
            // LabelPainter's loop: by priority, highest first (a stable sort), each at its first point that takes it
            placement.reset()
            val order = (0 until n).sortedByDescending { priority[it] }
            for (i in order) {
                val p = points[i]
                for (k in 0 until p.size / 2) {
                    if (p[2 * k].isNaN()) continue // GroundView could not project it
                    if (placement.tryPoint(half[i], pad, textH[i], p[2 * k], p[2 * k + 1], width, height, keepOut, keepOutCount, markers, markerCount, at)) {
                        fnv.int(frame); fnv.int(i); fnv.int(k)
                        fnv.int(at[0].toRawBits()); fnv.int(at[1].toRawBits())
                        placed++
                        val s = LabelPlacement.SPOTS
                        spots[(0 until s.size / 2).first { j ->
                            p[2 * k] + s[2 * j] * (half[i] - pad) == at[0] && p[2 * k + 1] + s[2 * j + 1] * textH[i] == at[1]
                        }]++
                        break
                    }
                }
            }
            labels += n
        }
        println("label placement: $labels labels over 400 frames, $placed placed (by spot: ${spots.joinToString()}), hash %016x".format(fnv.hash))
        assertTrue("a fair share is placed", placed in labels / 5..labels * 9 / 10)
        assertTrue("every spot takes labels", spots.all { it > 0 })
        assertEquals("the pinned hash (CP/tests/labels/placement_test.cpp pins it too)", PINNED, "%016x".format(fnv.hash))
    }

    private companion object {
        const val PINNED = "dd1efd97b6fc8e79"
    }
}
