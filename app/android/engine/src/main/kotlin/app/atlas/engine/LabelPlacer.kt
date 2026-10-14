package app.atlas.engine

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Places labels and point-of-interest symbols for a frame, most important first, so that none overlaps another:
 * place and water names, then symbols by rank, then street names along their lines (upright, following the street),
 * then house numbers. Labels fade in when they first appear, and fade out where a more important one takes their place
 * (drawn over by it for a moment, outside the collision boxes).
 */
internal class LabelPlacer(private val measurer: TextMeasurer) {
    class Placement(val labels: List<PlacedLabel>, val pois: List<PlacedPoi>, val poiBoxes: FloatArray)

    private val fade = HashMap<Long, Float>()
    /** Where each text was placed this frame: the same name is not repeated close by (a canal and its quay street). */
    private val placedText = HashMap<String, FloatList>()
    private val seen = HashSet<Long>()
    private var lastTime = Long.MIN_VALUE
    private val grid = CollisionGrid()
    private val m = FloatArray(12)
    private var keys = LongArray(1024)
    private var keyCount = 0
    private var adv = FloatArray(64)
    private var qx = FloatArray(256)
    private var qy = FloatArray(256)
    private var qs = FloatArray(256)
    private val tangent = FloatArray(3)
    /** Glyph positions of the label being tried; copied out only when it is placed. */
    private var glyphScratch = FloatArray(96)

    // Labels and symbols fading out this frame, with the keys their fades go by: settled once every solid one is placed.
    private val ghostLabels = ArrayList<PlacedLabel>()
    private val ghostLabelKeys = LongList()
    private val ghostPois = ArrayList<PlacedPoi>()
    private val ghostPoiKeys = LongList()

    fun place(camera: Camera, tiles: List<DrawTile>, time: Long, style: FrameStyle): Placement {
        val first = lastTime == Long.MIN_VALUE
        val dt = if (first) 0L else (time - lastTime).coerceIn(0L, 1000L)
        lastTime = time
        val width = camera.width.toFloat()
        val height = camera.height.toFloat()
        grid.reset(width, height, 64f * max(1f, camera.density))
        val labels = ArrayList<PlacedLabel>()
        val pois = ArrayList<PlacedPoi>()
        val poiBoxes = FloatList(64)
        var solidPois = 0
        if (!style.labels && !style.pois) return Placement(labels, pois, FloatArray(0))
        val zoom = camera.zoom.toFloat()

        // Every candidate in range, as sortable keys: priority, tile, kind (label or symbol), index.
        keyCount = 0
        for ((ti, t) in tiles.withIndex()) {
            if (ti > 255) break
            val s = t.scene
            if (style.labels) {
                for ((i, c) in s.labels.withIndex()) {
                    if (zoom < c.minZoom || zoom >= c.maxZoom) continue
                    if (t.only != null && !t.includes(s.unitX(c.x), s.unitY(c.y))) continue
                    push(c.priority, ti, false, i)
                }
            }
            if (style.pois) {
                for ((i, p) in s.pois.withIndex()) {
                    if (t.only != null && !t.includes(s.unitX(p.x), s.unitY(p.y))) continue
                    push(18f + min(p.rank, 60) * 0.1f, ti, true, i)
                }
            }
        }
        java.util.Arrays.sort(keys, 0, keyCount)

        val density = camera.density
        val far = camera.distance * 3.2
        var lastTile = -1
        seen.clear()
        placedText.clear()
        ghostLabels.clear(); ghostLabelKeys.clear(); ghostPois.clear(); ghostPoiKeys.clear()
        val repeatGap = 220f * density
        val out = DoubleArray(3)
        for (k in 0 until keyCount) {
            val key = keys[k]
            val ti = ((key ushr 24) and 0xFF).toInt()
            val poi = (key ushr 23) and 1L == 1L
            val i = (key and 0x7FFFFF).toInt()
            val t = tiles[ti]
            val s = t.scene
            if (ti != lastTile) {
                camera.tileMatrix(s.originX, s.originY, s.metresPerUnit, m)
                lastTile = ti
            }
            if (poi) {
                if (solidPois >= style.maxPois) continue
                val p = s.pois[i]
                if (!project(p.x, p.y, 0f, camera, out) || out[2] > far) continue
                val x = out[0].toFloat(); val y = out[1].toFloat()
                if (x < 0 || y < 0 || x > width || y > height) continue
                val scale = (camera.distance / out[2]).toFloat().coerceIn(0.7f, 1f)
                val half = style.poiSize * density * scale / 2 + 2 * density
                val fadeKey = p.featureId xor POI_SALT
                val location = LatLon(Mercator.lat(s.unitY(p.y)), Mercator.lon(s.unitX(p.x)))
                if (!grid.free(x - half, y - half, x + half, y + half)) {
                    val a = fadeOut(fadeKey, first, dt) ?: continue
                    ghostPois += PlacedPoi(p, x, y, out[2].toFloat(), scale, a, s.id, location)
                    ghostPoiKeys.add(fadeKey)
                    continue
                }
                grid.add(x - half, y - half, x + half, y + half)
                poiBoxes.add(x - half); poiBoxes.add(y - half); poiBoxes.add(x + half); poiBoxes.add(y + half)
                pois += PlacedPoi(p, x, y, out[2].toFloat(), scale, alphaOf(fadeKey, first, dt), s.id, location)
                solidPois++
                continue
            }
            val c = s.labels[i]
            val size = c.size * density * style.labelScale
            if (c.line != null) {
                placeLine(c, s, camera, size, width, height, labels, first, dt, repeatGap)
            } else {
                if (!project(c.x, c.y, c.z, camera, out) || out[2] > far * (if (c.kind == LabelKind.PLACE || c.kind == LabelKind.WATER) 2.5 else 1.0)) continue
                val x = out[0].toFloat(); val y = out[1].toFloat()
                val scale = sqrt((camera.distance / out[2]).toFloat()).coerceIn(0.8f, 1f)
                val sz = size * scale
                val w = measurer.width(c.text, c.style, sz) + c.letterSpacing * sz * (c.text.length - 1)
                val h = sz * 1.15f
                val pad = (if (c.kind == LabelKind.PLACE || c.kind == LabelKind.WATER) 8f else 2f) * density
                val x0 = x - w / 2 - pad; val x1 = x + w / 2 + pad
                val y0 = y - h / 2 - pad; val y1 = y + h / 2 + pad
                if (x0 < 0 || y0 < 0 || x1 > width || y1 > height) continue
                if (!grid.free(x0, y0, x1, y1) || repeated(c.text, x, y, repeatGap)) {
                    val a = fadeOut(c.key, first, dt) ?: continue
                    ghostLabels += PlacedLabel(c.text, c.kind, x, y, 0f, null, c.style, sz, c.letterSpacing, c.colour, c.halo, haloWidth(c, density), a, c.key)
                    ghostLabelKeys.add(c.key)
                    continue
                }
                grid.add(x0, y0, x1, y1)
                remember(c.text, x, y)
                labels += PlacedLabel(
                    c.text, c.kind, x, y, 0f, null, c.style, sz, c.letterSpacing, c.colour, c.halo, haloWidth(c, density),
                    alphaOf(c.key, first, dt), c.key,
                )
            }
        }
        // What fades out goes under what is shown, each once, and only when nothing with its key is shown solidly
        // (a street in several pieces shares one key). A fading symbol cannot be tapped: its box is empty.
        val allLabels = ArrayList<PlacedLabel>(labels.size + ghostLabels.size)
        for (g in ghostLabels.indices) {
            val key = ghostLabelKeys[g]
            if (key in seen) continue
            seen += key
            fade[key] = ghostLabels[g].alpha
            allLabels += ghostLabels[g]
        }
        allLabels += labels
        val allPois = ArrayList<PlacedPoi>(pois.size + ghostPois.size)
        val boxes = FloatList(poiBoxes.size + 4 * ghostPois.size)
        for (g in ghostPois.indices) {
            val key = ghostPoiKeys[g]
            if (key in seen) continue
            seen += key
            fade[key] = ghostPois[g].alpha
            allPois += ghostPois[g]
            repeat(4) { boxes.add(Float.NaN) }
        }
        allPois += pois
        for (b in 0 until poiBoxes.size) boxes.add(poiBoxes[b])
        // Forget fades of what is gone, so it fades in again when it returns.
        fade.keys.retainAll(seen)
        return Placement(allLabels, allPois, boxes.toArray())
    }

    private fun repeated(text: String, x: Float, y: Float, gap: Float): Boolean {
        val l = placedText[text] ?: return false
        var k = 0
        while (k < l.size) {
            val dx = l[k] - x; val dy = l[k + 1] - y
            if (dx * dx + dy * dy < gap * gap) return true
            k += 2
        }
        return false
    }

    private fun remember(text: String, x: Float, y: Float) {
        val l = placedText.getOrPut(text) { FloatList(4) }
        l.add(x); l.add(y)
    }

    private fun haloWidth(c: LabelCandidate, density: Float): Float =
        (if (c.style == LabelStyle.SERIF_ITALIC) 2.2f else 1.6f) * density

    /**
     * The alpha of a label or symbol that was shown and is now in the way of a more important one: lower than last
     * frame, or null once it has gone (or was never shown).
     */
    private fun fadeOut(key: Long, first: Boolean, dt: Long): Float? {
        if (first) return null
        val a = (fade[key] ?: return null) - dt / FADE_MS
        return if (a > 0f) a else null
    }

    private fun alphaOf(key: Long, first: Boolean, dt: Long): Float {
        // A key shown twice in one frame (a street in two pieces) fades in once, not twice as fast.
        if (!seen.add(key)) return fade[key] ?: 1f
        val a = if (first) 1f else min(1f, (fade[key] ?: 0f) + dt / FADE_MS)
        fade[key] = a
        return a
    }

    private fun push(priority: Float, tile: Int, poi: Boolean, index: Int) {
        if (keyCount == keys.size) keys = keys.copyOf(keys.size * 2)
        val p = (priority.coerceIn(0f, 2000f) * 1000f).toLong()
        keys[keyCount++] = (p shl 32) or ((tile.toLong() and 0xFF) shl 24) or ((if (poi) 1L else 0L) shl 23) or (index.toLong() and 0x7FFFFF)
    }

    private fun project(x: Float, y: Float, z: Float, camera: Camera, out: DoubleArray): Boolean {
        val w = m[8] * x + m[9] * y + m[10] * z + m[11]
        if (w < camera.near) return false
        out[0] = ((m[0] * x + m[1] * y + m[2] * z + m[3]) / w).toDouble()
        out[1] = ((m[4] * x + m[5] * y + m[6] * z + m[7]) / w).toDouble()
        out[2] = w.toDouble()
        return true
    }

    /** Places a label along its line, upright, where the line is straight enough; repeats along long lines. */
    private fun placeLine(
        c: LabelCandidate, s: TileScene, camera: Camera, size: Float, width: Float, height: Float,
        labels: MutableList<PlacedLabel>, first: Boolean, dt: Long, repeatGap: Float,
    ) {
        val line = c.line!!
        val n = line.size / 2
        if (qx.size < n) { qx = FloatArray(n * 2); qy = FloatArray(n * 2); qs = FloatArray(n * 2) }
        val text = c.text
        if (adv.size < text.length) adv = FloatArray(text.length * 2)
        measurer.advances(text, c.style, size, adv)
        val spacing = c.letterSpacing * size
        var textW = 0f
        for (k in text.indices) textW += adv[k] + spacing
        textW -= spacing
        val maxDepth = camera.distance * 2.2
        var placedHere = 0
        var fading = false
        // Walk visible runs of the projected line.
        var i = 0
        while (i < n) {
            var r = 0
            while (i < n) {
                val x = line[2 * i]; val y = line[2 * i + 1]
                val w = m[8] * x + m[9] * y + m[10] * c.z + m[11]
                if (w < camera.near || w > maxDepth) { i++; break }
                val sx = (m[0] * x + m[1] * y + m[2] * c.z + m[3]) / w
                val sy = (m[4] * x + m[5] * y + m[6] * c.z + m[7]) / w
                qx[r] = sx; qy[r] = sy
                qs[r] = if (r == 0) 0f else qs[r - 1] + dist(qx[r - 1], qy[r - 1], sx, sy)
                r++
                i++
            }
            if (r < 2) continue
            val total = qs[r - 1]
            val pad = size * 0.6f
            if (total < textW + 2 * pad) continue
            val repeat = max(textW * 2.5f, 300f * camera.density)
            val mid = total / 2
            var tries = 0
            var offset = 0f
            while (tries < 9 && placedHere < 3) {
                // Try the middle, then alternately further either way.
                val sCentre = mid + (if (tries % 2 == 1) offset else -offset)
                if (tries % 2 == 0) offset += repeat
                tries++
                if (sCentre - textW / 2 < pad || sCentre + textW / 2 > total - pad) continue
                if (tryGlyphs(c, sCentre, textW, spacing, size, r, width, height, labels, first, dt, repeatGap, placedHere)) placedHere++
            }
            // Shown last frame, in the way now: the first of its repeats fades out where it would stand.
            if (placedHere == 0 && !fading) {
                val a = fadeOut(c.key xor REPEAT_SALT, first, dt)
                if (a != null) fading = tryGlyphs(c, mid, textW, spacing, size, r, width, height, labels, first, dt, repeatGap, 0, a)
            }
        }
    }

    /**
     * Lays [c]'s glyphs along the projected run centred at arc length [sCentre]; adds the label and its boxes when they
     * fit. With [fadeAlpha] set it is fading out: placed at that alpha regardless of the others, and in no one's way.
     */
    private fun tryGlyphs(
        c: LabelCandidate, sCentre: Float, textW: Float, spacing: Float, size: Float, r: Int, width: Float, height: Float,
        labels: MutableList<PlacedLabel>, first: Boolean, dt: Long, repeatGap: Float, nth: Int, fadeAlpha: Float = -1f,
    ): Boolean {
        val fadingOut = fadeAlpha >= 0f
        val text = c.text
        val n = text.length
        val tangent = this.tangent
        pointAt(sCentre, r, tangent)
        val reverse = tangent[2] > 90f || tangent[2] < -90f
        if (glyphScratch.size < n * 3) glyphScratch = FloatArray(n * 6)
        val glyphs = glyphScratch
        var cum = 0f
        var prevAngle = 0f
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
        for (k in 0 until n) {
            val sk = if (!reverse) sCentre - textW / 2 + cum + adv[k] / 2 else sCentre + textW / 2 - cum - adv[k] / 2
            cum += adv[k] + spacing
            pointAt(sk, r, tangent)
            var a = tangent[2]
            if (reverse) a = if (a > 0) a - 180f else a + 180f
            if (k > 0 && abs(angleDiff(a, prevAngle)) > 32f) return false
            prevAngle = a
            minA = min(minA, a); maxA = max(maxA, a)
            glyphs[3 * k] = tangent[0]; glyphs[3 * k + 1] = tangent[1]; glyphs[3 * k + 2] = a
        }
        if (maxA - minA > 60f && maxA - minA < 300f) return false
        // Boxes per glyph.
        val half = size * 0.55f
        for (k in 0 until n) {
            val x = glyphs[3 * k]; val y = glyphs[3 * k + 1]
            if (x - half < 0 || y - half < 0 || x + half > width || y + half > height) return false
            if (!fadingOut && text[k] != ' ' && !grid.free(x - half, y - half, x + half, y + half)) return false
        }
        val mid = n / 2
        if (!fadingOut) {
            if (repeated(text, glyphs[3 * mid], glyphs[3 * mid + 1], repeatGap)) return false
            for (k in 0 until n) {
                val x = glyphs[3 * k]; val y = glyphs[3 * k + 1]
                grid.add(x - half, y - half, x + half, y + half)
            }
            remember(text, glyphs[3 * mid], glyphs[3 * mid + 1])
        }
        // Keyed by which repeat along the street it is, not by where it sits on screen, so it keeps its fade as the view moves.
        val fadeKey = c.key xor (nth + 1) * REPEAT_SALT
        val placed = PlacedLabel(
            text, c.kind, glyphs[3 * mid], glyphs[3 * mid + 1], glyphs[3 * mid + 2], glyphs.copyOf(n * 3), c.style, size, c.letterSpacing,
            c.colour, c.halo, (if (c.style == LabelStyle.SERIF_ITALIC) 2.2f else 1.6f) * (size / max(1f, c.size)),
            if (fadingOut) fadeAlpha else alphaOf(fadeKey, first, dt), c.key,
        )
        if (fadingOut) { ghostLabels += placed; ghostLabelKeys.add(fadeKey) } else labels += placed
        return true
    }

    /** Point and direction (degrees) at arc length [s] of the projected run of [r] points, into [out]. */
    private fun pointAt(s: Float, r: Int, out: FloatArray) {
        var j = 1
        while (j < r - 1 && qs[j] < s) j++
        val s0 = qs[j - 1]; val s1 = qs[j]
        val t = if (s1 > s0) ((s - s0) / (s1 - s0)).coerceIn(0f, 1f) else 0f
        out[0] = qx[j - 1] + (qx[j] - qx[j - 1]) * t
        out[1] = qy[j - 1] + (qy[j] - qy[j - 1]) * t
        out[2] = (atan2(qy[j] - qy[j - 1], qx[j] - qx[j - 1]) * 180.0 / PI).toFloat()
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        const val FADE_MS = 220f
        private const val POI_SALT = 0x5eed_9017L
        private const val REPEAT_SALT = -0x61c8864680b583ebL
    }
}

/** Screen-space boxes on a coarse grid, for fast "is this free?" questions. */
internal class CollisionGrid {
    private var cell = 64f
    private var cols = 1
    private var rows = 1
    private var cells = arrayOfNulls<IntList>(0)
    private var boxes = FloatArray(1024)
    private var count = 0

    fun reset(width: Float, height: Float, cellSize: Float) {
        cell = cellSize
        cols = max(1, (width / cell).toInt() + 1)
        rows = max(1, (height / cell).toInt() + 1)
        if (cells.size < cols * rows) cells = arrayOfNulls(cols * rows)
        for (i in 0 until cols * rows) cells[i]?.clear()
        count = 0
    }

    fun free(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val c0 = (x0 / cell).toInt().coerceIn(0, cols - 1); val c1 = (x1 / cell).toInt().coerceIn(0, cols - 1)
        val r0 = (y0 / cell).toInt().coerceIn(0, rows - 1); val r1 = (y1 / cell).toInt().coerceIn(0, rows - 1)
        for (r in r0..r1) for (c in c0..c1) {
            val list = cells[r * cols + c] ?: continue
            for (k in 0 until list.size) {
                val b = 4 * list[k]
                if (x0 < boxes[b + 2] && x1 > boxes[b] && y0 < boxes[b + 3] && y1 > boxes[b + 1]) return false
            }
        }
        return true
    }

    fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (4 * count + 4 > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
        val b = 4 * count
        boxes[b] = x0; boxes[b + 1] = y0; boxes[b + 2] = x1; boxes[b + 3] = y1
        val c0 = (x0 / cell).toInt().coerceIn(0, cols - 1); val c1 = (x1 / cell).toInt().coerceIn(0, cols - 1)
        val r0 = (y0 / cell).toInt().coerceIn(0, rows - 1); val r1 = (y1 / cell).toInt().coerceIn(0, rows - 1)
        for (r in r0..r1) for (c in c0..c1) {
            val i = r * cols + c
            (cells[i] ?: IntList(8).also { cells[i] = it }).add(count)
        }
        count++
    }
}
