package com.chesstrainer.app.vision

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Pixels
// ---------------------------------------------------------------------------------------------

/**
 * Luma image of a screenshot, box-downscaled so that its shorter side is about [targetMinSide]
 * pixels. Images that are already smaller are kept at their native resolution.
 */
class Pixels(bitmap: Bitmap, targetMinSide: Int = 400) {
    val width: Int
    val height: Int

    /** Factor converting source-bitmap coordinates to Pixels coordinates. */
    val scale: Float

    private val luma: FloatArray

    /** Box-averaged colour per pixel, packed as 0xRRGGBB (used for occupancy / colour tests). */
    private val rgb: IntArray

    init {
        val src = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val sw = src.width
        val sh = src.height
        require(sw > 0 && sh > 0) { "Empty bitmap" }

        val s = min(1f, targetMinSide.toFloat() / min(sw, sh))
        width = max(1, (sw * s).roundToInt())
        height = max(1, (sh * s).roundToInt())
        scale = width.toFloat() / sw

        // Box (area) downscale: every source pixel contributes to exactly one target pixel.
        // Channels are summed separately; luma is linear in RGB, so the luma of the averaged
        // colour equals the average of the per-pixel lumas.
        val n = width * height
        val rSums = IntArray(n)
        val gSums = IntArray(n)
        val bSums = IntArray(n)
        val counts = IntArray(n)
        val xMap = IntArray(sw) { min(width - 1, (it.toLong() * width / sw).toInt()) }
        val row = IntArray(sw)
        for (y in 0 until sh) {
            src.getPixels(row, 0, sw, 0, y, sw, 1)
            val base = min(height - 1, (y.toLong() * height / sh).toInt()) * width
            for (x in 0 until sw) {
                val c = row[x]
                val i = base + xMap[x]
                rSums[i] += (c shr 16) and 0xFF
                gSums[i] += (c shr 8) and 0xFF
                bSums[i] += c and 0xFF
                counts[i]++
            }
        }
        luma = FloatArray(n)
        rgb = IntArray(n)
        for (i in 0 until n) {
            val cnt = counts[i]
            if (cnt == 0) continue
            val r = rSums[i].toFloat() / cnt
            val g = gSums[i].toFloat() / cnt
            val b = bSums[i].toFloat() / cnt
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
            rgb[i] = (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or b.roundToInt()
        }
        if (src !== bitmap) src.recycle()
    }

    /** Luma (0..255) at integer coordinates; out-of-range coordinates are clamped. */
    fun lumaAt(x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return luma[cy * width + cx]
    }

    /** Colour packed as 0xRRGGBB at integer coordinates; out-of-range coordinates are clamped. */
    fun rgbAt(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return rgb[cy * width + cx]
    }

    /**
     * Bilinearly interpolated colour at fractional pixel-center coordinates. Writes R, G, B
     * (0..255) into [out] at indices 0, 1, 2.
     */
    fun sampleRgb(fx: Float, fy: Float, out: FloatArray) {
        val x = fx - 0.5f
        val y = fy - 0.5f
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val ax = x - x0
        val ay = y - y0
        val w00 = (1 - ax) * (1 - ay)
        val w10 = ax * (1 - ay)
        val w01 = (1 - ax) * ay
        val w11 = ax * ay
        val c00 = rgbAt(x0, y0)
        val c10 = rgbAt(x0 + 1, y0)
        val c01 = rgbAt(x0, y0 + 1)
        val c11 = rgbAt(x0 + 1, y0 + 1)
        for (k in 0 until 3) {
            val sh = 16 - 8 * k
            out[k] = w00 * ((c00 shr sh) and 0xFF) + w10 * ((c10 shr sh) and 0xFF) +
                w01 * ((c01 shr sh) and 0xFF) + w11 * ((c11 shr sh) and 0xFF)
        }
    }

    /** Bilinearly interpolated luma at fractional pixel-center coordinates. */
    fun sample(fx: Float, fy: Float): Float {
        val x = fx - 0.5f
        val y = fy - 0.5f
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val ax = x - x0
        val ay = y - y0
        val top = lumaAt(x0, y0) * (1 - ax) + lumaAt(x0 + 1, y0) * ax
        val bottom = lumaAt(x0, y0 + 1) * (1 - ax) + lumaAt(x0 + 1, y0 + 1) * ax
        return top * (1 - ay) + bottom * ay
    }
}

// ---------------------------------------------------------------------------------------------
// Grid
// ---------------------------------------------------------------------------------------------

/** Board geometry in [Pixels] coordinates: top-left corner and size of one square. */
data class Grid(val x0: Float, val y0: Float, val cw: Float, val ch: Float)

// ---------------------------------------------------------------------------------------------
// BoardLocator
// ---------------------------------------------------------------------------------------------

/**
 * Finds the 8x8 board by looking for 7 equally spaced interior grid lines on both axes.
 *
 * A horizontal boundary between two ranks produces a luma jump in every column of the board
 * (the checkerboard alternates), so the row edge-count profile peaks at those 7 lines. The same
 * holds for columns. The search is done independently of screen orientation; the board just has
 * to fit inside the shorter screen side.
 */
object BoardLocator {
    private const val EDGE_THRESHOLD = 18f
    private const val MIN_BOARD_FRACTION = 0.3f
    private const val MIN_LINE_FILL = 0.35f
    private const val MAX_ASPECT_ERROR = 0.1f

    private class Fit(val origin: Int, val size: Int, val score: Float)

    fun locate(px: Pixels): Grid? {
        val w = px.width
        val h = px.height
        val minSide = min(w, h)
        val sMin = max(16, (minSide * MIN_BOARD_FRACTION).toInt())
        if (sMin > minSide) return null

        // Coarse pass over full-width / full-height profiles with a shared (square) board size.
        val rowProfile = horizontalLineProfile(px, 0, w)
        val colProfile = verticalLineProfile(px, 0, h)
        var bestX: Fit? = null
        var bestY: Fit? = null
        var bestTotal = 0f
        for (s in sMin..minSide) {
            val fx = bestOrigin(colProfile, s, 0, w - s) ?: continue
            val fy = bestOrigin(rowProfile, s, 0, h - s) ?: continue
            val total = fx.score + fy.score
            if (total > bestTotal) {
                bestTotal = total
                bestX = fx
                bestY = fy
            }
        }
        if (bestX == null || bestY == null) return null

        // Refinement pass: profiles restricted to the board's extent, axes fitted independently.
        val xFrom = bestX.origin
        val yFrom = bestY.origin
        val s = bestX.size
        val rowRestricted = horizontalLineProfile(px, xFrom, min(w, xFrom + s))
        val colRestricted = verticalLineProfile(px, yFrom, min(h, yFrom + s))
        val (gx, cw) = refineAxis(colRestricted, xFrom, s) ?: return null
        val (gy, ch) = refineAxis(rowRestricted, yFrom, s) ?: return null

        if (cw < 4f || ch < 4f) return null
        if (abs(cw - ch) / max(cw, ch) > MAX_ASPECT_ERROR) return null

        // The 7 interior lines are ambiguous by one cell when an outer board edge is also a strong
        // line. Resolve by picking the shift whose 64 squares form the cleanest checkerboard.
        var best: Grid? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (sy in -1..1) for (sx in -1..1) {
            val g = Grid(gx + sx * cw, gy + sy * ch, cw, ch)
            if (!fitsInside(g, w, h)) continue
            val sc = checkerboardScore(px, g)
            if (sc > bestScore) {
                bestScore = sc
                best = g
            }
        }
        return best
    }

    private fun fitsInside(g: Grid, w: Int, h: Int): Boolean =
        g.x0 >= -g.cw / 2 && g.y0 >= -g.ch / 2 &&
            g.x0 + 8 * g.cw <= w + g.cw / 2 && g.y0 + 8 * g.ch <= h + g.ch / 2

    /** Contrast between the two square parities minus the spread within each parity. */
    private fun checkerboardScore(px: Pixels, g: Grid): Float {
        val even = ArrayList<Float>(32)
        val odd = ArrayList<Float>(32)
        for (r in 0 until 8) for (c in 0 until 8) {
            val v = CellReader.background(px, g, r, c)
            if ((r + c) % 2 == 0) even.add(v) else odd.add(v)
        }
        val me = CellReader.median(even)
        val mo = CellReader.median(odd)
        val spread = (even.sumOf { abs(it - me).toDouble() } + odd.sumOf { abs(it - mo).toDouble() }) / 64.0
        return abs(me - mo) - spread.toFloat()
    }

    /** Index i = number of strong luma jumps between rows i-1 and i, for x in [xFrom, xTo). */
    private fun horizontalLineProfile(px: Pixels, xFrom: Int, xTo: Int): FloatArray {
        val p = FloatArray(px.height)
        for (y in 1 until px.height) {
            var n = 0
            for (x in xFrom until xTo) {
                if (abs(px.lumaAt(x, y) - px.lumaAt(x, y - 1)) > EDGE_THRESHOLD) n++
            }
            p[y] = n.toFloat()
        }
        return p
    }

    /** Index i = number of strong luma jumps between columns i-1 and i, for y in [yFrom, yTo). */
    private fun verticalLineProfile(px: Pixels, yFrom: Int, yTo: Int): FloatArray {
        val p = FloatArray(px.width)
        for (x in 1 until px.width) {
            var n = 0
            for (y in yFrom until yTo) {
                if (abs(px.lumaAt(x, y) - px.lumaAt(x - 1, y)) > EDGE_THRESHOLD) n++
            }
            p[x] = n.toFloat()
        }
        return p
    }

    private fun at(p: FloatArray, i: Int): Float = if (i in p.indices) p[i] else 0f

    private fun peak(p: FloatArray, i: Int): Float = max(at(p, i - 1), max(at(p, i), at(p, i + 1)))

    /**
     * Score of a board of size [s] starting at [o]: weakest of the 7 interior lines minus the
     * mean response in the middle of the 8 cells. Zero if any line is not a strong edge.
     */
    private fun scoreAt(p: FloatArray, o: Int, s: Int): Float {
        val cell = s / 8f
        val minFill = MIN_LINE_FILL * s
        var minLine = Float.MAX_VALUE
        for (k in 1..7) {
            val v = peak(p, (o + k * cell).roundToInt())
            if (v < minFill) return 0f
            if (v < minLine) minLine = v
        }
        var mid = 0f
        for (k in 0..7) mid += at(p, (o + (k + 0.5f) * cell).roundToInt())
        return minLine - mid / 8f
    }

    private fun bestOrigin(p: FloatArray, s: Int, oFrom: Int, oTo: Int): Fit? {
        var best: Fit? = null
        var bestScore = 0f
        for (o in max(0, oFrom)..min(p.size - s, oTo)) {
            val sc = scoreAt(p, o, s)
            if (sc > bestScore) {
                bestScore = sc
                best = Fit(o, s, sc)
            }
        }
        return best
    }

    /** Returns (origin, cellSize) with sub-pixel precision, or null if the lines vanished. */
    private fun refineAxis(p: FloatArray, origin: Int, size: Int): Pair<Float, Float>? {
        val sLo = max(16, (size * 0.96f).toInt())
        val sHi = (size * 1.04f).roundToInt() + 1
        val slack = max(2, size / 16)
        var best: Fit? = null
        for (s in sLo..sHi) {
            val f = bestOrigin(p, s, origin - slack, origin + slack) ?: continue
            if (best == null || f.score > best.score) best = f
        }
        if (best == null) return null

        // Centroid of each line peak, then least-squares fit pos(k) = origin + k * cell.
        val cell = best.size / 8f
        val pos = FloatArray(7)
        for (k in 1..7) {
            val c = (best.origin + k * cell).roundToInt()
            var lo = Float.MAX_VALUE
            for (i in c - 2..c + 2) lo = min(lo, at(p, i))
            var wSum = 0f
            var xSum = 0f
            for (i in c - 2..c + 2) {
                val wgt = at(p, i) - lo
                wSum += wgt
                xSum += wgt * i
            }
            pos[k - 1] = if (wSum > 0f) xSum / wSum else c.toFloat()
        }
        val meanPos = pos.average().toFloat()
        var num = 0f
        for (k in 1..7) num += (k - 4) * (pos[k - 1] - meanPos)
        val slope = num / 28f // sum of (k-4)^2 for k = 1..7
        if (slope <= 0f) return null
        return Pair(meanPos - 4f * slope, slope)
    }
}

// ---------------------------------------------------------------------------------------------
// Cell
// ---------------------------------------------------------------------------------------------

/**
 * One board square as seen on screen.
 *
 * @property features FEATURE_SIZE x FEATURE_SIZE values, row-major, each (luma - background) / 255.
 * @property isLight whether the square is a light square of the board theme.
 * @property background estimated luma of the empty square colour.
 */
class Cell(val features: FloatArray, val isLight: Boolean, val background: Float)

// ---------------------------------------------------------------------------------------------
// CellReader
// ---------------------------------------------------------------------------------------------

object CellReader {
    const val FEATURE_SIZE = 16
    private const val INNER_FRACTION = 0.76f
    private const val CORNER_INSET = 0.05f
    private const val CORNER_SIZE = 0.10f

    /** Returns 64 cells in screen order: index = row * 8 + col, row 0 at the top. */
    fun read(px: Pixels, grid: Grid): List<Cell> {
        val backgrounds = FloatArray(64)
        for (r in 0 until 8) for (c in 0 until 8) {
            backgrounds[r * 8 + c] = background(px, grid, r, c)
        }

        // The checkerboard parity whose squares are brighter (by median) is the light shade.
        // Using parity instead of a per-square threshold keeps highlighted squares correct.
        val even = ArrayList<Float>(32)
        val odd = ArrayList<Float>(32)
        for (r in 0 until 8) for (c in 0 until 8) {
            if ((r + c) % 2 == 0) even.add(backgrounds[r * 8 + c]) else odd.add(backgrounds[r * 8 + c])
        }
        val lightParity = if (median(even) >= median(odd)) 0 else 1

        val cells = ArrayList<Cell>(64)
        for (r in 0 until 8) for (c in 0 until 8) {
            val bg = backgrounds[r * 8 + c]
            cells.add(Cell(features(px, grid, r, c, bg), (r + c) % 2 == lightParity, bg))
        }
        return cells
    }

    /** Median of the four corner-patch means; robust to a coordinate label in one corner. */
    internal fun background(px: Pixels, grid: Grid, r: Int, c: Int): Float {
        val left = grid.x0 + c * grid.cw
        val top = grid.y0 + r * grid.ch
        val right = left + grid.cw
        val bottom = top + grid.ch
        val dx0 = CORNER_INSET * grid.cw
        val dx1 = (CORNER_INSET + CORNER_SIZE) * grid.cw
        val dy0 = CORNER_INSET * grid.ch
        val dy1 = (CORNER_INSET + CORNER_SIZE) * grid.ch
        val corners = floatArrayOf(
            patchMean(px, left + dx0, top + dy0, left + dx1, top + dy1),
            patchMean(px, right - dx1, top + dy0, right - dx0, top + dy1),
            patchMean(px, left + dx0, bottom - dy1, left + dx1, bottom - dy0),
            patchMean(px, right - dx1, bottom - dy1, right - dx0, bottom - dy0),
        )
        corners.sort()
        return (corners[1] + corners[2]) / 2f
    }

    private fun patchMean(px: Pixels, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val ix0 = floor(x0).toInt()
        val iy0 = floor(y0).toInt()
        val ix1 = max(ix0, floor(x1).toInt())
        val iy1 = max(iy0, floor(y1).toInt())
        var sum = 0f
        var n = 0
        for (y in iy0..iy1) for (x in ix0..ix1) {
            sum += px.lumaAt(x, y)
            n++
        }
        return sum / n
    }

    private fun features(px: Pixels, grid: Grid, r: Int, c: Int, bg: Float): FloatArray {
        val margin = (1f - INNER_FRACTION) / 2f
        val left = grid.x0 + (c + margin) * grid.cw
        val top = grid.y0 + (r + margin) * grid.ch
        val binW = INNER_FRACTION * grid.cw / FEATURE_SIZE
        val binH = INNER_FRACTION * grid.ch / FEATURE_SIZE
        val out = FloatArray(FEATURE_SIZE * FEATURE_SIZE)
        for (i in 0 until FEATURE_SIZE) {
            for (j in 0 until FEATURE_SIZE) {
                val cx = left + (j + 0.5f) * binW
                val cy = top + (i + 0.5f) * binH
                var sum = 0f
                for (sy in -1..1) for (sx in -1..1) {
                    sum += px.sample(cx + sx * binW / 3f, cy + sy * binH / 3f)
                }
                out[i * FEATURE_SIZE + j] = (sum / 9f - bg) / 255f
            }
        }
        return out
    }

    internal fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    // ---- wide sampling (template matching) ----------------------------------------------

    /** Side of the wide (template-matching) sample grid. */
    const val WIDE_SIZE = 32

    /**
     * Fraction of the cell covered by the wide sample (2%..98%). Wide enough to keep the king's
     * cross, the bishop's tip and the base row, narrow enough to avoid grid-line bleed.
     */
    const val WIDE_FRACTION = 0.96f

    /** Side of the change-detection thumbnail. */
    const val THUMB_SIZE = 8

    /**
     * Samples cell ([r], [c]) over the central [fraction] of the square into a [size] x [size]
     * colour grid (2x2 bilinear supersampling per bin), plus the square's background colour.
     */
    fun readWide(
        px: Pixels,
        grid: Grid,
        r: Int,
        c: Int,
        size: Int = WIDE_SIZE,
        fraction: Float = WIDE_FRACTION,
    ): WideCell {
        val margin = (1f - fraction) / 2f
        val left = grid.x0 + (c + margin) * grid.cw
        val top = grid.y0 + (r + margin) * grid.ch
        val binW = fraction * grid.cw / size
        val binH = fraction * grid.ch / size
        val n = size * size
        val red = FloatArray(n)
        val green = FloatArray(n)
        val blue = FloatArray(n)
        val gray = FloatArray(n)
        val tmp = FloatArray(3)
        for (i in 0 until size) {
            for (j in 0 until size) {
                val cx = left + (j + 0.5f) * binW
                val cy = top + (i + 0.5f) * binH
                var sr = 0f
                var sg = 0f
                var sb = 0f
                for (sy in 0..1) for (sx in 0..1) {
                    px.sampleRgb(cx + (sx - 0.5f) * binW / 2f, cy + (sy - 0.5f) * binH / 2f, tmp)
                    sr += tmp[0]
                    sg += tmp[1]
                    sb += tmp[2]
                }
                val k = i * size + j
                red[k] = sr / 4f
                green[k] = sg / 4f
                blue[k] = sb / 4f
                gray[k] = luma(red[k], green[k], blue[k])
            }
        }
        val bg = backgroundRgb(px, grid, r, c)
        return WideCell(size, red, green, blue, gray, bg[0], bg[1], bg[2])
    }

    /** [size] x [size] luma box averages over the wide window, for cheap frame-to-frame diffs. */
    fun thumbnail(
        px: Pixels,
        grid: Grid,
        r: Int,
        c: Int,
        size: Int = THUMB_SIZE,
        fraction: Float = WIDE_FRACTION,
    ): FloatArray {
        val margin = (1f - fraction) / 2f
        val left = grid.x0 + (c + margin) * grid.cw
        val top = grid.y0 + (r + margin) * grid.ch
        val binW = fraction * grid.cw / size
        val binH = fraction * grid.ch / size
        return FloatArray(size * size) { k ->
            val i = k / size
            val j = k % size
            val x0 = left + j * binW
            val y0 = top + i * binH
            // Inclusive integer ranges in patchMean; shrink by a hair so bins do not overlap.
            patchMean(px, x0, y0, x0 + binW - 0.01f, y0 + binH - 0.01f)
        }
    }

    /**
     * Background colour (R, G, B) from the four corner patches: the two corners with median luma
     * are averaged, so a coordinate label in one corner (or two, on a1/h8) does not dominate.
     */
    internal fun backgroundRgb(px: Pixels, grid: Grid, r: Int, c: Int): FloatArray {
        val left = grid.x0 + c * grid.cw
        val top = grid.y0 + r * grid.ch
        val right = left + grid.cw
        val bottom = top + grid.ch
        val dx0 = CORNER_INSET * grid.cw
        val dx1 = (CORNER_INSET + CORNER_SIZE) * grid.cw
        val dy0 = CORNER_INSET * grid.ch
        val dy1 = (CORNER_INSET + CORNER_SIZE) * grid.ch
        val corners = arrayOf(
            patchMeanRgb(px, left + dx0, top + dy0, left + dx1, top + dy1),
            patchMeanRgb(px, right - dx1, top + dy0, right - dx0, top + dy1),
            patchMeanRgb(px, left + dx0, bottom - dy1, left + dx1, bottom - dy0),
            patchMeanRgb(px, right - dx1, bottom - dy1, right - dx0, bottom - dy0),
        )
        corners.sortBy { luma(it[0], it[1], it[2]) }
        return FloatArray(3) { k -> (corners[1][k] + corners[2][k]) / 2f }
    }

    private fun patchMeanRgb(px: Pixels, x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val ix0 = floor(x0).toInt()
        val iy0 = floor(y0).toInt()
        val ix1 = max(ix0, floor(x1).toInt())
        val iy1 = max(iy0, floor(y1).toInt())
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var n = 0
        for (y in iy0..iy1) for (x in ix0..ix1) {
            val v = px.rgbAt(x, y)
            sr += (v shr 16) and 0xFF
            sg += (v shr 8) and 0xFF
            sb += v and 0xFF
            n++
        }
        return floatArrayOf(sr / n, sg / n, sb / n)
    }

    internal fun luma(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b
}

/**
 * Colour sample of (almost) a whole board square, for template matching. Arrays are
 * [size] x [size], row-major, values 0..255. The bg* fields are the empty-square colour.
 */
class WideCell(
    val size: Int,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray,
    val gray: FloatArray,
    val bgRed: Float,
    val bgGreen: Float,
    val bgBlue: Float,
) {
    val bgLuma: Float get() = CellReader.luma(bgRed, bgGreen, bgBlue)
}

// ---------------------------------------------------------------------------------------------
// Templates
// ---------------------------------------------------------------------------------------------

/** Template key: FEN piece character ('.' for empty) and the shade of the square it sits on. */
data class TemplateKey(val piece: Char, val lightSquare: Boolean)

/** Averaged feature vectors per (piece, square shade), produced by [Calibrator]. */
class Templates(val entries: Map<TemplateKey, FloatArray>) {

    fun forShade(lightSquare: Boolean): List<Pair<Char, FloatArray>> =
        entries.filterKeys { it.lightSquare == lightSquare }.map { (k, v) -> k.piece to v }

    /** Plain-text form suitable for SharedPreferences or a file. One entry per line. */
    fun serialize(): String = buildString {
        for ((key, vec) in entries) {
            append(key.piece).append('|').append(if (key.lightSquare) 'L' else 'D').append('|')
            vec.joinTo(this, ",")
            append('\n')
        }
    }

    companion object {
        private const val VECTOR_SIZE = CellReader.FEATURE_SIZE * CellReader.FEATURE_SIZE

        fun deserialize(text: String): Templates? {
            val map = HashMap<TemplateKey, FloatArray>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split('|')
                if (parts.size != 3 || parts[0].length != 1) return null
                val light = when (parts[1]) {
                    "L" -> true
                    "D" -> false
                    else -> return null
                }
                val values = parts[2].split(',')
                if (values.size != VECTOR_SIZE) return null
                val vec = FloatArray(VECTOR_SIZE)
                for (i in values.indices) vec[i] = values[i].toFloatOrNull() ?: return null
                map[TemplateKey(parts[0][0], light)] = vec
            }
            return if (map.isEmpty()) null else Templates(map)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Classifier
// ---------------------------------------------------------------------------------------------

object Classifier {

    /** Returns letters[row][col] in screen order (row 0 at top), '.' for empty squares. */
    fun classify(cells: List<Cell>, templates: Templates): Array<CharArray> {
        require(cells.size == 64) { "Expected 64 cells, got ${cells.size}" }
        val light = templates.forShade(true)
        val dark = templates.forShade(false)
        val all = templates.entries.map { (k, v) -> k.piece to v }
        return Array(8) { r ->
            CharArray(8) { c ->
                val cell = cells[r * 8 + c]
                val candidates = (if (cell.isLight) light else dark).ifEmpty { all }
                nearest(cell.features, candidates)
            }
        }
    }

    private fun nearest(features: FloatArray, candidates: List<Pair<Char, FloatArray>>): Char {
        var bestPiece = '.'
        var bestDist = Float.MAX_VALUE
        for ((piece, tpl) in candidates) {
            val d = distance(features, tpl, bestDist)
            if (d < bestDist) {
                bestDist = d
                bestPiece = piece
            }
        }
        return bestPiece
    }

    /** Squared Euclidean distance with early exit once [limit] is exceeded. */
    private fun distance(a: FloatArray, b: FloatArray, limit: Float): Float {
        var sum = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) {
            val d = a[i] - b[i]
            sum += d * d
            if (sum >= limit) return sum
        }
        return sum
    }
}

// ---------------------------------------------------------------------------------------------
// TemplateFreeClassifier
// ---------------------------------------------------------------------------------------------

/**
 * Classifies board squares without templates, so recognition works from any position.
 *
 * Per cell:
 *  1. **Occupancy**: count of feature bins that differ strongly from the square colour (corner
 *     bins are ignored because chess.com draws coordinate labels there).
 *  2. **Silhouette**: strong bins, morphologically closed and hole-filled. Filling matters for
 *     white pieces on light squares, where only the dark outline differs from the background.
 *  3. **Colour**: a bright percentile of the *absolute* luma inside the silhouette (the piece
 *     fill), split into two groups by 2-means over all occupied squares. This is more robust
 *     than the sign of the mean feature, which the dark outline can flip on light squares.
 *  4. **Type**: shape metrics of the silhouette (top row, height, area, top-band width and
 *     fill, upper-body width, waist, aspect, left-right asymmetry), scored against per-piece
 *     prototypes with a normalized squared distance.
 *
 * Board-level checks then fix the least confident reads: 3..32 pieces in total (occupancy is
 * re-read with stricter gates when there are too many), at most 16 pieces per colour, no pawns
 * on the first or last rank, the tallest pieces of a colour hold its king and its clearly
 * shortest pieces are pawns, exactly one king per side, at most 8 pawns per side.
 *
 * Prototype values are tuned for chess.com's default ("neo") pieces, but they rely on coarse
 * silhouette proportions only, so similar sets should work too.
 */
object TemplateFreeClassifier {
    private const val N = CellReader.FEATURE_SIZE

    /** |feature| above which a bin counts as part of a piece (about 23 luma levels). */
    private const val MASK_THRESHOLD = 0.09f

    /** Side of the square corner patches ignored because of coordinate labels. */
    private const val CORNER = 3

    // Occupancy gates. Kept fairly strict so that coordinate labels, move highlights and
    // anti-aliasing noise are not mistaken for pieces.
    private const val MIN_STRONG_BINS = 15
    private const val MIN_MEAN_ENERGY = 0.04f
    private const val MIN_SILHOUETTE_BINS = 14
    private const val MIN_COMPONENT_BINS = 3

    /**
     * Board-level piece-count sanity. More than 32 pieces is impossible, so the occupancy gates
     * are tightened by [STRICTNESS_STEP] per retry (up to [MAX_STRICTNESS_LEVEL] retries). Fewer
     * than [MIN_TOTAL_PIECES] pieces (two kings and at most nothing else) is treated as a failed
     * read.
     */
    private const val MAX_TOTAL_PIECES = 32
    private const val MIN_TOTAL_PIECES = 3
    private const val MAX_PIECES_PER_SIDE = 16
    private const val MAX_STRICTNESS_LEVEL = 3
    private const val STRICTNESS_STEP = 0.25f

    /**
     * Relative-height heuristic. Heights are quantised to 1/N (one feature row = 0.0625).
     * [HEIGHT_TIE]: pieces within about one row of the extreme count as "tallest"/"shortest".
     * [MIN_PAWN_HEIGHT_GAP]: a pawn candidate must be clearly shorter than the side's tallest
     * piece (a pawn is ~3-4 rows shorter than a king, a rook only ~1-2 rows).
     * Boosts are subtracted from the prototype distance and only applied to reads whose current
     * type wins by less than [LOW_CONFIDENCE_MARGIN].
     */
    private const val HEIGHT_TIE = 0.07f
    private const val MIN_PAWN_HEIGHT_GAP = 0.15f
    private const val LOW_CONFIDENCE_MARGIN = 2.0f
    private const val KING_BOOST = 3.0f
    private const val PAWN_BOOST = 3.0f

    private const val BODY_PERCENTILE = 0.65f
    private const val DEFAULT_COLOR_THRESHOLD = 150f
    private const val MIN_COLOR_SEPARATION = 40f
    private const val MIN_COLOR_THRESHOLD = 70f
    private const val MAX_COLOR_THRESHOLD = 210f

    private const val MAX_PAWNS = 8

    // Type indices; TYPE_CHARS maps them to (black) FEN letters.
    private const val PAWN = 0
    private const val KNIGHT = 1
    private const val BISHOP = 2
    private const val ROOK = 3
    private const val QUEEN = 4
    private const val KING = 5
    private const val TYPE_COUNT = 6
    private val TYPE_CHARS = charArrayOf('p', 'n', 'b', 'r', 'q', 'k')

    // Shape metric indices.
    private const val M_TOP = 0 // first silhouette row / N
    private const val M_HEIGHT = 1 // silhouette rows / N
    private const val M_AREA = 2 // silhouette bins / N^2
    private const val M_TOP_SPAN = 3 // widest row in the top 20% / widest row overall
    private const val M_TOP_FILL = 4 // fill ratio of the top 20% band
    private const val M_UPPER_SPAN = 5 // mean width of 20%..45% band / widest row
    private const val M_WAIST = 6 // narrowest row in 45%..80% band / widest row
    private const val M_ASPECT = 7 // widest row / height
    private const val M_ASYM = 8 // left-right mirror mismatch
    private const val METRIC_COUNT = 9

    /**
     * Prototype means per type, metric order as above.
     *
     * The feature window is the central 76% of the square and all pieces stand on a common base
     * line near (or below) the bottom of that window, so for every type `top + height` is close
     * to 1. Distinguishing features per type:
     *  - pawn: much lower top / shorter, small area, small round head (narrow top band), symmetric.
     *  - knight: tall and strongly asymmetric (horse head) - asymmetry is the key metric.
     *  - bishop: tall, narrow, pointed mitre (narrow top band), slim upper body.
     *  - rook: a bit shorter, blocky, battlements make the top band nearly as wide as the body and
     *    well filled.
     *  - queen: tall, crown spikes make a moderately wide but poorly filled top band, large area.
     *  - king: tallest, cross gives a narrow top band, but a very wide upper body and largest area.
     */
    private val PROTO_MEAN = arrayOf(
        //            top    height area   topSp  topFl  upper  waist  aspect asym
        floatArrayOf(0.30f, 0.68f, 0.24f, 0.50f, 0.70f, 0.50f, 0.35f, 0.78f, 0.05f), // pawn
        floatArrayOf(0.03f, 0.95f, 0.42f, 0.50f, 0.55f, 0.70f, 0.55f, 0.78f, 0.25f), // knight
        floatArrayOf(0.02f, 0.96f, 0.32f, 0.28f, 0.50f, 0.42f, 0.35f, 0.58f, 0.06f), // bishop
        floatArrayOf(0.06f, 0.92f, 0.50f, 0.95f, 0.88f, 0.75f, 0.70f, 0.80f, 0.05f), // rook
        floatArrayOf(0.01f, 0.98f, 0.45f, 0.68f, 0.50f, 0.65f, 0.42f, 0.74f, 0.06f), // queen
        floatArrayOf(0.00f, 1.00f, 0.52f, 0.35f, 0.55f, 0.85f, 0.65f, 0.78f, 0.05f), // king
    )

    /**
     * Tolerance per metric. The discriminating metrics are tight: asymmetry (knight vs the
     * rest), top span (rook's battlements) and height (pawn's shortness). The others stay wide
     * because rendering size, anti-aliasing and the grid fit all shift them.
     */
    private val PROTO_SIGMA = floatArrayOf(0.12f, 0.10f, 0.12f, 0.12f, 0.18f, 0.18f, 0.18f, 0.15f, 0.06f)

    /**
     * The knight's own asymmetry tolerance is a bit wider (how lopsided the head looks depends
     * on the set and resolution), while every symmetric type uses the tight 0.06 above, so an
     * asymmetric silhouette is expensive for every type except the knight.
     */
    private const val KNIGHT_ASYM_SIGMA = 0.10f

    // Hard pre-classification rules (see earlyClassify).
    private const val EARLY_KNIGHT_ASYM = 0.18f
    private const val EARLY_ROOK_TOP_SPAN = 0.85f
    private const val EARLY_ROOK_TOP_FILL = 0.80f
    private const val EARLY_ROOK_MAX_ASYM = 0.12f
    private const val EARLY_PAWN_MAX_HEIGHT = 0.70f
    private const val EARLY_PAWN_MAX_ASYM = 0.12f

    /** Score lead given to an early (rule-based) type so later margin checks treat it as sure. */
    private const val EARLY_LOCK_MARGIN = 10f

    /** A rook read whose raw asymmetry is above this is really a knight. */
    private const val ROOK_TO_KNIGHT_ASYM = 0.14f

    /** Per-square result before board-level validation. */
    private class Reading(
        val row: Int,
        var white: Boolean,
        /** Distance of the fill luma from the colour threshold (higher = surer). */
        var colorConfidence: Float,
        /** Distance to each type prototype (lower = better). */
        val scores: FloatArray,
        var type: Int,
        val bodyLuma: Float,
        /** Shape metrics of the silhouette (see M_* indices). */
        val metrics: FloatArray,
        /** Set by [earlyClassify]: the type came from a hard rule and must not be re-guessed. */
        val locked: Boolean = false,
        /** Colour comes from a [SquareOverride] and is not re-derived from the fill luma. */
        val fixedColor: Boolean = false,
    )

    /**
     * Scale converting an NCC gap into the prototype-distance units used by the board-level
     * checks: an NCC margin of 0.03 (the template matcher's acceptance gate) maps to 3, above
     * [LOW_CONFIDENCE_MARGIN].
     */
    private const val NCC_SCORE_SCALE = 100f

    /** Score gap used for an override that carries no per-type scores. */
    private const val OVERRIDE_DEFAULT_GAP = 50f

    /** Returns letters[row][col] in screen order (row 0 at top), '.' for empty squares. */
    fun classify(cells: List<Cell>): Array<CharArray> = classify(cells, null)

    /**
     * Like [classify], but squares with a non-null entry in [overrides] (64 entries, screen
     * order) take their occupancy, colour and type from it (e.g. a confident
     * [TemplateMatchingClassifier] read). All other squares are read by this classifier, and the
     * board-level sanity checks (king count, pawn count, side sizes, back-rank pawns) run over
     * the combined result; override reads are treated as the most certain ones.
     */
    fun classify(cells: List<Cell>, overrides: Array<SquareOverride?>?): Array<CharArray> {
        require(cells.size == 64) { "Expected 64 cells, got ${cells.size}" }
        require(overrides == null || overrides.size == 64) { "Expected 64 overrides" }

        // Occupancy, retried with stricter gates while the count is impossible (> 32 pieces).
        var level = 0
        var readings = readAll(cells, level, overrides)
        while (readings.count { it != null } > MAX_TOTAL_PIECES && level < MAX_STRICTNESS_LEVEL) {
            level++
            readings = readAll(cells, level, overrides)
        }
        val occupied = readings.filterNotNull()
        // Still impossible, or too few pieces for a real position: the read is garbage.
        if (occupied.size > MAX_TOTAL_PIECES || occupied.size < MIN_TOTAL_PIECES) {
            return Array(8) { CharArray(8) { '.' } }
        }

        // Colour: split fill lumas into two clusters (override reads keep their colour, but
        // their measured fill lumas still help the clustering).
        val threshold = colorThreshold(occupied.map { it.bodyLuma }.filter { !it.isNaN() })
        for (rd in occupied) {
            if (rd.fixedColor) continue
            rd.white = rd.bodyLuma > threshold
            rd.colorConfidence = abs(rd.bodyLuma - threshold)
        }

        validate(occupied)

        return Array(8) { r ->
            CharArray(8) { c ->
                val rd = readings[r * 8 + c]
                if (rd == null) {
                    '.'
                } else {
                    val ch = TYPE_CHARS[rd.type]
                    if (rd.white) ch.uppercaseChar() else ch
                }
            }
        }
    }

    // ---- per cell ----------------------------------------------------------------------

    private fun isCorner(i: Int): Boolean {
        val r = i / N
        val c = i % N
        val nearRowEdge = r < CORNER || r >= N - CORNER
        val nearColEdge = c < CORNER || c >= N - CORNER
        return nearRowEdge && nearColEdge
    }

    private fun readAll(cells: List<Cell>, strictness: Int, overrides: Array<SquareOverride?>?): Array<Reading?> =
        Array(64) { i ->
            val o = overrides?.get(i)
            if (o != null) fromOverride(cells[i], i / 8, o) else readCell(cells[i], i / 8, strictness)
        }

    /**
     * Reading for a square decided externally. Shape metrics and fill luma are still measured
     * (when this classifier sees a piece there) so relative-height checks of the other pieces
     * stay meaningful; otherwise the type's prototype metrics stand in.
     */
    private fun fromOverride(cell: Cell, row: Int, o: SquareOverride): Reading? {
        if (o.piece == '.') return null
        val type = TYPE_CHARS.indexOf(o.piece.lowercaseChar())
        if (type < 0) return readCell(cell, row)
        val measured = readCell(cell, row)
        val ncc = o.typeScores
        val scores = FloatArray(TYPE_COUNT) { t ->
            when {
                ncc != null && t < ncc.size && !ncc[t].isNaN() -> (1f - ncc[t]) * NCC_SCORE_SCALE
                t == type -> 0f
                else -> OVERRIDE_DEFAULT_GAP
            }
        }
        // The override's type must be the favourite whatever the per-type scores say.
        var bestOther = Float.MAX_VALUE
        for (t in 0 until TYPE_COUNT) if (t != type) bestOther = min(bestOther, scores[t])
        scores[type] = min(scores[type], bestOther)
        return Reading(
            row,
            white = o.piece.isUpperCase(),
            colorConfidence = o.colorConfidence,
            scores = scores,
            type = type,
            bodyLuma = measured?.bodyLuma ?: Float.NaN,
            metrics = measured?.metrics ?: PROTO_MEAN[type].copyOf(),
            locked = true,
            fixedColor = true,
        )
    }

    /**
     * Returns null for an empty square. [strictness] > 0 tightens the occupancy gates only (the
     * silhouette is always built from the base mask so shape metrics stay comparable).
     */
    private fun readCell(cell: Cell, row: Int, strictness: Int = 0): Reading? {
        val f = cell.features
        if (f.size < N * N) return null

        val factor = 1f + STRICTNESS_STEP * strictness
        val occupancyThreshold = MASK_THRESHOLD * factor
        var strong = 0
        var energy = 0f
        var counted = 0
        val mask = BooleanArray(N * N)
        for (i in 0 until N * N) {
            if (isCorner(i)) continue
            val a = abs(f[i])
            energy += a
            counted++
            if (a > MASK_THRESHOLD) mask[i] = true
            if (a > occupancyThreshold) strong++
        }
        if (strong < MIN_STRONG_BINS * factor || energy / counted < MIN_MEAN_ENERGY * factor) return null

        val sil = silhouette(mask)
        val silCount = sil.count { it }
        if (silCount < MIN_SILHOUETTE_BINS) return null

        val metrics = shapeMetrics(sil) ?: return null
        val scores = FloatArray(TYPE_COUNT) { t -> distance(metrics, t) }
        val backRank = row == 0 || row == 7
        val early = earlyClassify(metrics, backRank)
        val type: Int
        if (early != null) {
            type = TYPE_CHARS.indexOf(early)
            // Make the rule's type the clear favourite so margin-based fixes leave it alone.
            var bestOther = Float.MAX_VALUE
            for (t in 0 until TYPE_COUNT) if (t != type) bestOther = min(bestOther, scores[t])
            scores[type] = min(scores[type], bestOther - EARLY_LOCK_MARGIN)
        } else {
            type = bestType(scores, if (backRank) setOf(PAWN) else emptySet())
        }

        // Absolute luma of the piece fill: a bright percentile inside the silhouette, so the
        // dark outline (present on both colours) does not dominate.
        val lumas = FloatArray(silCount)
        var k = 0
        for (i in 0 until N * N) if (sil[i]) lumas[k++] = f[i] * 255f + cell.background
        lumas.sort()
        val body = lumas[((silCount - 1) * BODY_PERCENTILE).roundToInt()]

        return Reading(
            row, white = true, colorConfidence = 0f, scores = scores, type = type, bodyLuma = body,
            metrics = metrics, locked = early != null,
        )
    }

    /**
     * Hard decision rules applied before the prototype matching. Returns a (black) FEN letter
     * when the silhouette is unambiguous, or null to fall back to the Gaussian scores.
     *  - The knight is the only asymmetric piece (horse head): strong asymmetry means knight.
     *  - A wide, well-filled top band (battlements as wide as the body) means rook.
     *  - A clearly short, symmetric silhouette means pawn (never on the first/last rank).
     */
    private fun earlyClassify(metrics: FloatArray, backRank: Boolean): Char? {
        val asym = metrics[M_ASYM]
        if (asym > EARLY_KNIGHT_ASYM) return 'n'
        if (metrics[M_TOP_SPAN] > EARLY_ROOK_TOP_SPAN &&
            metrics[M_TOP_FILL] > EARLY_ROOK_TOP_FILL &&
            asym < EARLY_ROOK_MAX_ASYM
        ) {
            return 'r'
        }
        if (!backRank && metrics[M_HEIGHT] < EARLY_PAWN_MAX_HEIGHT && asym < EARLY_PAWN_MAX_ASYM) return 'p'
        return null
    }

    /** Closing (dilate, fill holes, erode), unioned with the hole-filled raw mask. */
    private fun silhouette(mask: BooleanArray): BooleanArray {
        val direct = fillHoles(mask)
        val closed = erode(fillHoles(dilate(mask)))
        val out = BooleanArray(N * N) { direct[it] || closed[it] }
        return removeSmallComponents(out)
    }

    private fun dilate(m: BooleanArray): BooleanArray = BooleanArray(N * N) { i ->
        val r = i / N
        val c = i % N
        m[i] || (r > 0 && m[i - N]) || (r < N - 1 && m[i + N]) ||
            (c > 0 && m[i - 1]) || (c < N - 1 && m[i + 1])
    }

    /** Erosion that ignores out-of-grid neighbours (pieces may be clipped by the grid edge). */
    private fun erode(m: BooleanArray): BooleanArray = BooleanArray(N * N) { i ->
        val r = i / N
        val c = i % N
        m[i] && (r == 0 || m[i - N]) && (r == N - 1 || m[i + N]) &&
            (c == 0 || m[i - 1]) && (c == N - 1 || m[i + 1])
    }

    /** Everything not 4-connected to the grid border through background becomes foreground. */
    private fun fillHoles(m: BooleanArray): BooleanArray {
        val outside = BooleanArray(N * N)
        val stack = IntArray(N * N)
        var sp = 0
        for (i in 0 until N * N) {
            val r = i / N
            val c = i % N
            if ((r == 0 || r == N - 1 || c == 0 || c == N - 1) && !m[i]) {
                outside[i] = true
                stack[sp++] = i
            }
        }
        while (sp > 0) {
            val i = stack[--sp]
            val r = i / N
            val c = i % N
            if (r > 0) sp = visit(i - N, m, outside, stack, sp)
            if (r < N - 1) sp = visit(i + N, m, outside, stack, sp)
            if (c > 0) sp = visit(i - 1, m, outside, stack, sp)
            if (c < N - 1) sp = visit(i + 1, m, outside, stack, sp)
        }
        return BooleanArray(N * N) { !outside[it] }
    }

    private fun visit(j: Int, m: BooleanArray, seen: BooleanArray, stack: IntArray, sp: Int): Int {
        if (m[j] || seen[j]) return sp
        seen[j] = true
        stack[sp] = j
        return sp + 1
    }

    /** Drops 8-connected specks (label remnants, noise); the largest component is always kept. */
    private fun removeSmallComponents(m: BooleanArray): BooleanArray {
        val label = IntArray(N * N) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(N * N)
        for (start in 0 until N * N) {
            if (!m[start] || label[start] >= 0) continue
            val id = sizes.size
            var size = 0
            var sp = 0
            label[start] = id
            stack[sp++] = start
            while (sp > 0) {
                val i = stack[--sp]
                size++
                val r = i / N
                val c = i % N
                for (dr in -1..1) for (dc in -1..1) {
                    val rr = r + dr
                    val cc = c + dc
                    if (rr !in 0 until N || cc !in 0 until N) continue
                    val j = rr * N + cc
                    if (m[j] && label[j] < 0) {
                        label[j] = id
                        stack[sp++] = j
                    }
                }
            }
            sizes.add(size)
        }
        if (sizes.isEmpty()) return m
        val largest = sizes.indices.maxByOrNull { sizes[it] } ?: 0
        return BooleanArray(N * N) { i ->
            val id = label[i]
            id >= 0 && (id == largest || sizes[id] >= MIN_COMPONENT_BINS)
        }
    }

    private fun shapeMetrics(sil: BooleanArray): FloatArray? {
        val left = IntArray(N) { -1 }
        val right = IntArray(N) { -1 }
        val rowCount = IntArray(N)
        var count = 0
        for (r in 0 until N) for (c in 0 until N) {
            if (!sil[r * N + c]) continue
            count++
            rowCount[r]++
            if (left[r] < 0) left[r] = c
            right[r] = c
        }
        if (count == 0) return null
        val top = (0 until N).first { rowCount[it] > 0 }
        val bottom = (0 until N).last { rowCount[it] > 0 }
        val h = bottom - top + 1

        fun span(r: Int): Int = if (rowCount[r] > 0) right[r] - left[r] + 1 else 0
        var maxSpan = 1
        for (r in top..bottom) maxSpan = max(maxSpan, span(r))

        fun bandStart(frac: Float): Int = min(bottom, top + (h * frac).roundToInt())

        // Top band: first 20% of the piece's rows (at least one row).
        val topEnd = max(top, bandStart(0.2f) - 1)
        var topSpanMax = 0
        var topCount = 0
        for (r in top..topEnd) {
            topSpanMax = max(topSpanMax, span(r))
            topCount += rowCount[r]
        }
        val topRows = topEnd - top + 1
        val topFill = if (topSpanMax > 0) topCount.toFloat() / (topRows * topSpanMax) else 0f

        // Upper body: 20%..45%.
        val upFrom = bandStart(0.2f)
        val upTo = max(upFrom, bandStart(0.45f) - 1)
        var upSum = 0f
        for (r in upFrom..upTo) upSum += span(r)
        val upperSpan = upSum / (upTo - upFrom + 1) / maxSpan

        // Waist: narrowest non-empty row in 45%..80%.
        val wFrom = bandStart(0.45f)
        val wTo = max(wFrom, bandStart(0.8f) - 1)
        var waist = maxSpan
        for (r in wFrom..wTo) {
            val s = span(r)
            if (s > 0) waist = min(waist, s)
        }

        // Asymmetry: mirror about the bounding-box centre (tolerates small grid offsets).
        var lo = N
        var hi = -1
        for (r in top..bottom) {
            if (rowCount[r] == 0) continue
            lo = min(lo, left[r])
            hi = max(hi, right[r])
        }
        var mismatch = 0
        for (r in top..bottom) for (c in 0 until N) {
            if (!sil[r * N + c]) continue
            val m = lo + hi - c
            if (m !in 0 until N || !sil[r * N + m]) mismatch++
        }

        val out = FloatArray(METRIC_COUNT)
        out[M_TOP] = top.toFloat() / N
        out[M_HEIGHT] = h.toFloat() / N
        out[M_AREA] = count.toFloat() / (N * N)
        out[M_TOP_SPAN] = topSpanMax.toFloat() / maxSpan
        out[M_TOP_FILL] = topFill
        out[M_UPPER_SPAN] = upperSpan
        out[M_WAIST] = waist.toFloat() / maxSpan
        out[M_ASPECT] = maxSpan.toFloat() / h
        out[M_ASYM] = mismatch.toFloat() / count
        return out
    }

    private fun distance(metrics: FloatArray, type: Int): Float {
        val mean = PROTO_MEAN[type]
        var d = 0f
        for (m in 0 until METRIC_COUNT) {
            val sigma = if (type == KNIGHT && m == M_ASYM) KNIGHT_ASYM_SIGMA else PROTO_SIGMA[m]
            val z = (metrics[m] - mean[m]) / sigma
            d += z * z
        }
        return d
    }

    private fun bestType(scores: FloatArray, excluded: Set<Int>): Int {
        var best = -1
        for (t in 0 until TYPE_COUNT) {
            if (t in excluded) continue
            if (best < 0 || scores[t] < scores[best]) best = t
        }
        return if (best < 0) QUEEN else best
    }

    /** How much better [type] is than the best alternative (positive = [type] preferred). */
    private fun margin(rd: Reading, type: Int): Float {
        val excluded = if (rd.row == 0 || rd.row == 7) setOf(PAWN, type) else setOf(type)
        return rd.scores[bestType(rd.scores, excluded)] - rd.scores[type]
    }

    private fun excludedFor(rd: Reading, vararg extra: Int): Set<Int> {
        val s = extra.toMutableSet()
        if (rd.row == 0 || rd.row == 7) s.add(PAWN)
        return s
    }

    // ---- board level -------------------------------------------------------------------

    /** Two-means threshold between the white and black fill lumas. */
    private fun colorThreshold(values: List<Float>): Float {
        if (values.size < 2) return DEFAULT_COLOR_THRESHOLD
        var lo = values.minOrNull() ?: return DEFAULT_COLOR_THRESHOLD
        var hi = values.maxOrNull() ?: return DEFAULT_COLOR_THRESHOLD
        if (hi - lo < MIN_COLOR_SEPARATION) return DEFAULT_COLOR_THRESHOLD
        repeat(12) {
            val t = (lo + hi) / 2f
            val low = values.filter { it <= t }
            val high = values.filter { it > t }
            if (low.isEmpty() || high.isEmpty()) return@repeat
            lo = low.average().toFloat()
            hi = high.average().toFloat()
        }
        if (hi - lo < MIN_COLOR_SEPARATION) return DEFAULT_COLOR_THRESHOLD
        return ((lo + hi) / 2f).coerceIn(MIN_COLOR_THRESHOLD, MAX_COLOR_THRESHOLD)
    }

    private fun validate(pieces: List<Reading>) {
        // Pawns can never stand on the first or last rank (screen rows 0 and 7 in either
        // orientation); readCell already excluded them there, this is a safety net.
        for (rd in pieces) {
            if (rd.type == PAWN && (rd.row == 0 || rd.row == 7)) {
                rd.type = bestType(rd.scores, setOf(PAWN))
            }
        }

        // Two kings of one colour and none of the other: the less certain one has the wrong colour.
        for (white in listOf(true, false)) {
            val own = pieces.filter { it.type == KING && it.white == white }
            val other = pieces.filter { it.type == KING && it.white != white }
            if (own.size >= 2 && other.isEmpty()) {
                val flip = own.minByOrNull { it.colorConfidence } ?: continue
                flip.white = !white
            }
        }

        fixSideSizes(pieces)

        for (white in listOf(true, false)) {
            val side = pieces.filter { it.white == white }
            if (side.isEmpty()) continue
            applyRelativeHeight(side)
            fixKings(side)
            fixPawns(side)
        }
    }

    /**
     * A side can have at most 16 pieces. If one side is over-full while the other has room, the
     * over-full side's least certain colour reads are the likeliest errors; move them across.
     */
    private fun fixSideSizes(pieces: List<Reading>) {
        for (white in listOf(true, false)) {
            val own = pieces.filter { it.white == white }
            val otherCount = pieces.size - own.size
            val excess = min(own.size - MAX_PIECES_PER_SIDE, MAX_PIECES_PER_SIDE - otherCount)
            if (excess <= 0) continue
            for (rd in own.sortedBy { it.colorConfidence }.take(excess)) rd.white = !white
        }
    }

    /**
     * Uses the height ordering within one colour to fix shaky type reads. The king is the
     * tallest piece and pawns are clearly the shortest, regardless of the exact rendering, so:
     *  - if the side has no king, the cheapest-to-promote piece among the tallest becomes king;
     *  - if the side's king is not among its tallest pieces, a low-confidence piece among the
     *    tallest gets its king score boosted (fixKings then keeps the more convincing king);
     *  - low-confidence non-pawns among the shortest pieces get their pawn score boosted.
     * Heights of tall pieces are clipped by the feature window, so "tallest" is a tie group.
     */
    private fun applyRelativeHeight(side: List<Reading>) {
        // A rook never has a lopsided silhouette; if the raw metrics say it does, it is a knight
        // whose head happened to look blocky. Independent of the side size.
        for (rd in side) {
            if (rd.locked || rd.type != ROOK) continue
            if (rd.metrics[M_ASYM] > ROOK_TO_KNIGHT_ASYM) rd.type = KNIGHT
        }

        if (side.size < 2) return
        val tallest = side.maxOf { it.metrics[M_HEIGHT] }
        val shortest = side.minOf { it.metrics[M_HEIGHT] }
        val tallGroup = side.filter { tallest - it.metrics[M_HEIGHT] <= HEIGHT_TIE }
        val unlockedTall = tallGroup.filter { !it.locked }

        val kings = side.filter { it.type == KING }
        if (kings.isEmpty()) {
            val pick = unlockedTall.minByOrNull { it.scores[KING] - it.scores[it.type] }
            if (pick != null) pick.type = KING
        } else if (kings.none { tallest - it.metrics[M_HEIGHT] <= HEIGHT_TIE }) {
            val pick = unlockedTall
                .filter { it.type != KING && margin(it, it.type) < LOW_CONFIDENCE_MARGIN }
                .minByOrNull { it.scores[KING] - it.scores[it.type] }
            if (pick != null) {
                pick.scores[KING] -= KING_BOOST
                pick.type = bestType(pick.scores, excludedFor(pick))
            }
        }

        if (tallest - shortest >= MIN_PAWN_HEIGHT_GAP) {
            // The clearly shortest pieces of a side are pawns. A bishop is one of the tallest
            // pieces, so a "bishop" down there is always a pawn; other types switch when unsure.
            for (rd in side) {
                if (rd.locked || rd.type == PAWN || rd.type == KING || rd.row == 0 || rd.row == 7) continue
                val h = rd.metrics[M_HEIGHT]
                if (h - shortest > HEIGHT_TIE || tallest - h < MIN_PAWN_HEIGHT_GAP) continue
                if (rd.type == BISHOP) {
                    rd.type = PAWN
                    continue
                }
                if (margin(rd, rd.type) >= LOW_CONFIDENCE_MARGIN) continue
                rd.scores[PAWN] -= PAWN_BOOST
                rd.type = bestType(rd.scores, excludedFor(rd))
            }
        }

        // A "pawn" as tall as the side's real pieces is not a pawn (typically a bishop whose
        // narrow outline matched the pawn prototype). Re-pick among the non-pawn types.
        val nonPawnHeights = side.filter { it.type != PAWN }.map { it.metrics[M_HEIGHT] }
        if (nonPawnHeights.isEmpty()) return
        val refHeight = CellReader.median(nonPawnHeights)
        for (rd in side) {
            if (rd.locked || rd.type != PAWN) continue
            val h = rd.metrics[M_HEIGHT]
            if (h < refHeight - HEIGHT_TIE || tallest - h >= MIN_PAWN_HEIGHT_GAP) continue
            rd.type = bestType(rd.scores, setOf(PAWN))
        }
    }

    private fun fixKings(side: List<Reading>) {
        val kings = side.filter { it.type == KING }
        when {
            kings.isEmpty() -> {
                // Promote the piece for which "king" costs the least relative to its current read.
                val pool = side.filter { !it.locked }.ifEmpty { side }
                val pick = pool.minByOrNull { it.scores[KING] - it.scores[it.type] } ?: return
                pick.type = KING
            }
            kings.size > 1 -> {
                val keep = kings.maxByOrNull { margin(it, KING) } ?: return
                for (k in kings) {
                    if (k !== keep) k.type = bestType(k.scores, excludedFor(k, KING))
                }
            }
        }
    }

    private fun fixPawns(side: List<Reading>) {
        val pawns = side.filter { it.type == PAWN }
        if (pawns.size <= MAX_PAWNS) return
        // Rule-based pawns are demoted last; within each group the least sure go first.
        val leastSure = pawns.sortedWith(compareBy<Reading>({ it.locked }, { margin(it, PAWN) }))
        for (i in 0 until pawns.size - MAX_PAWNS) {
            val rd = leastSure[i]
            rd.type = bestType(rd.scores, setOf(PAWN, KING))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// TemplateMatchingClassifier
// ---------------------------------------------------------------------------------------------

/**
 * Externally decided content of one square, fed into [TemplateFreeClassifier.classify].
 *
 * @property piece FEN letter, or '.' for a square known to be empty.
 * @property typeScores optional per-type similarity in [-1, 1] (index order p, n, b, r, q, k),
 *   used when board-level checks must re-pick a type.
 * @property colorConfidence how sure the colour is (higher = surer; TemplateFreeClassifier's own
 *   reads are roughly 0..150).
 */
class SquareOverride(
    val piece: Char,
    val typeScores: FloatArray? = null,
    val colorConfidence: Float = 1000f,
)

/**
 * Pixel-level classifier for chess.com's "neo" piece set, using the reference PNGs provided by
 * [NeoTemplateSet] (downloaded by [TemplateManager]).
 *
 * Per square (sampled over 96% of the cell, see [CellReader.readWide]):
 *  1. **Occupancy / colour** from pixel statistics against the corner-sampled background colour:
 *     empty if fewer than 3% of pixels differ from it by more than 60 (RGB distance); black if
 *     more than 10% of pixels are dark (luma < min(100, background - 30)); white otherwise.
 *     Pixels in the label corners are ignored.
 *  2. **Type** by anchor-aligned normalized cross-correlation: the 32x32 grayscale square is
 *     compared with each template of the detected colour, composited onto the square's
 *     background, rendered at 5 scales and aligned on the piece's baseline and horizontal
 *     centre (plus a +-1 px offset search at the best scale). The best NCC wins; the gap to the
 *     runner-up type is the margin.
 *  3. **Gating**: a type read is accepted only if NCC >= [MIN_NCC] and margin >= [MIN_MARGIN];
 *     other occupied squares are left to the [TemplateFreeClassifier] (null override).
 *
 * Squares whose 8x8 luma thumbnail barely changed since the previous frame reuse the previous
 * result, so a typical frame only re-matches the one or two squares of the last move.
 *
 * Thread-safe ([classify] is synchronized); keep one instance per frame stream.
 */
class TemplateMatchingClassifier(private val source: () -> NeoTemplateSet?) {

    constructor(manager: TemplateManager) : this({ manager.templates() })

    companion object {
        private const val TAG = "TemplateMatching"

        private const val T = CellReader.WIDE_SIZE

        /** Template scales relative to the on-screen square. */
        val SCALES = floatArrayOf(0.88f, 0.94f, 1.00f, 1.06f, 1.12f)

        private const val TYPE_CHARS = "pnbrqk"

        // Occupancy and colour.
        private const val EMPTY_RGB_DISTANCE = 60f
        private const val EMPTY_MAX_FRACTION = 0.03f
        private const val DARK_MAX_LUMA = 100f
        private const val DARK_BELOW_BACKGROUND = 30f
        private const val BLACK_MIN_DARK_FRACTION = 0.10f

        /** Offset search radius (pixels) around the anchor alignment at the best scale. */
        private const val REFINE_RADIUS = 1

        /** Side (in wide-sample pixels) of the corner patches ignored because of labels. */
        private const val LABEL_CORNER = 6

        // Confidence gating.
        const val MIN_NCC = 0.6f
        const val MIN_MARGIN = 0.03f
        private const val NEO_MIN_MEDIAN_NCC = 0.5f
        private const val MIN_SQUARES_FOR_SET_CHECK = 4
        private const val WARN_INTERVAL_MS = 30_000L

        /** Mean absolute thumbnail difference above which a square is re-matched. */
        private const val CHANGE_THRESHOLD = 4f

        /** Grid movement (fraction of a cell) that invalidates the change-detection cache. */
        private const val GRID_SHIFT_TOLERANCE = 0.1f

        /** Pixels that take part in statistics and NCC (everything but the label corners). */
        private val VALID: BooleanArray = BooleanArray(T * T) { i ->
            val y = i / T
            val x = i % T
            val nearX = x < LABEL_CORNER || x >= T - LABEL_CORNER
            val nearY = y < LABEL_CORNER || y >= T - LABEL_CORNER
            !(nearX && nearY)
        }
    }

    /** Result for one square. [override] is null when the square must go to the fallback. */
    private class SquareResult(val override: SquareOverride?, val bestNcc: Float)

    private val lastThumbs = arrayOfNulls<FloatArray>(64)
    private val lastResults = arrayOfNulls<SquareResult>(64)
    private var lastSet: NeoTemplateSet? = null
    private var lastGrid: Grid? = null
    private var lastWarnAt = 0L

    /** Median best NCC over the occupied squares of the last frame (NaN if none). */
    @Volatile
    var lastMedianNcc: Float = Float.NaN
        private set

    /**
     * False when the last frame's median NCC was below 0.5, i.e. the user is probably not using
     * the "neo" piece set; null if unknown.
     */
    @Volatile
    var looksLikeNeoSet: Boolean? = null
        private set

    /** True once the templates are available. Calling this also (re)triggers their loading. */
    val isReady: Boolean get() = source() != null

    /** Drops the change-detection cache (e.g. after a theme change). */
    @Synchronized
    fun reset() {
        lastThumbs.fill(null)
        lastResults.fill(null)
        lastGrid = null
    }

    /**
     * Classifies all 64 squares (screen order). Returns null if the templates are not ready.
     * Entries are '.' overrides for empty squares, piece overrides for confident matches, and
     * null where the caller should fall back to another classifier.
     */
    @Synchronized
    fun classify(px: Pixels, grid: Grid): Array<SquareOverride?>? {
        val set = source() ?: return null
        if (set !== lastSet || !sameGeometry(grid)) {
            lastThumbs.fill(null)
            lastResults.fill(null)
            lastSet = set
        }
        lastGrid = grid

        val out = arrayOfNulls<SquareOverride>(64)
        val nccs = ArrayList<Float>(32)
        for (r in 0 until 8) for (c in 0 until 8) {
            val i = r * 8 + c
            val thumb = CellReader.thumbnail(px, grid, r, c)
            val prevThumb = lastThumbs[i]
            val prev = lastResults[i]
            val result = if (prev != null && prevThumb != null && meanAbsDiff(thumb, prevThumb) <= CHANGE_THRESHOLD) {
                prev
            } else {
                matchSquare(CellReader.readWide(px, grid, r, c), set).also {
                    lastThumbs[i] = thumb
                    lastResults[i] = it
                }
            }
            out[i] = result.override
            if (!result.bestNcc.isNaN()) nccs.add(result.bestNcc)
        }
        checkPieceSet(nccs)
        return out
    }

    private fun sameGeometry(g: Grid): Boolean {
        val p = lastGrid ?: return false
        val tol = GRID_SHIFT_TOLERANCE * max(g.cw, g.ch)
        return abs(p.x0 - g.x0) <= tol && abs(p.y0 - g.y0) <= tol &&
            abs(p.cw - g.cw) <= tol / 8f && abs(p.ch - g.ch) <= tol / 8f
    }

    private fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var s = 0f
        for (k in a.indices) s += abs(a[k] - b[k])
        return s / a.size
    }

    private fun checkPieceSet(nccs: List<Float>) {
        if (nccs.size < MIN_SQUARES_FOR_SET_CHECK) {
            lastMedianNcc = if (nccs.isEmpty()) Float.NaN else CellReader.median(nccs)
            return
        }
        val med = CellReader.median(nccs)
        lastMedianNcc = med
        val neo = med >= NEO_MIN_MEDIAN_NCC
        val was = looksLikeNeoSet
        looksLikeNeoSet = neo
        if (!neo) {
            val now = System.currentTimeMillis()
            if (was != false || now - lastWarnAt >= WARN_INTERVAL_MS) {
                lastWarnAt = now
                Log.w(
                    TAG,
                    (
                        "Median template NCC %.2f < %.2f over %d pieces: the board probably does not use the " +
                            "\"neo\" piece set; falling back to the template-free classifier"
                        ).format(
                        med, NEO_MIN_MEDIAN_NCC, nccs.size,
                    ),
                )
            }
        }
    }

    private fun matchSquare(cell: WideCell, set: NeoTemplateSet): SquareResult {
        val n = T * T
        if (cell.size != T) return SquareResult(null, Float.NaN)
        val bgR = cell.bgRed
        val bgG = cell.bgGreen
        val bgB = cell.bgBlue
        val bgL = cell.bgLuma
        val gray = cell.gray

        // 1. Occupancy and colour.
        val diffSq = EMPTY_RGB_DISTANCE * EMPTY_RGB_DISTANCE
        val darkThr = min(DARK_MAX_LUMA, bgL - DARK_BELOW_BACKGROUND)
        var counted = 0
        var differing = 0
        var dark = 0
        for (k in 0 until n) {
            if (!VALID[k]) continue
            counted++
            val dr = cell.red[k] - bgR
            val dg = cell.green[k] - bgG
            val db = cell.blue[k] - bgB
            if (dr * dr + dg * dg + db * db > diffSq) differing++
            if (gray[k] < darkThr) dark++
        }
        if (counted == 0) return SquareResult(null, Float.NaN)
        if (differing < EMPTY_MAX_FRACTION * counted) return SquareResult(SquareOverride('.'), Float.NaN)
        val darkFraction = dark.toFloat() / counted
        val white = darkFraction <= BLACK_MIN_DARK_FRACTION
        val colorConfidence = 200f + abs(darkFraction - BLACK_MIN_DARK_FRACTION) * 1000f

        // 2. Anchor of the captured piece and statistics of the captured square.
        val anchor = PieceAnchor.find(gray, T, T, bgL, VALID) ?: return SquareResult(null, Float.NaN)
        var sum = 0.0
        var sumSq = 0.0
        for (k in 0 until n) {
            if (!VALID[k]) continue
            val v = gray[k].toDouble()
            sum += v
            sumSq += v * v
        }
        val meanA = sum / counted
        val varA = sumSq / counted - meanA * meanA
        if (varA <= 1e-6) return SquareResult(null, Float.NaN)
        val stdA = sqrt(varA)

        // 3. Best NCC per type over all scales.
        val bank = set.composited(white, bgL)
        val typeNcc = FloatArray(TYPE_CHARS.length) { Float.NaN }
        for (t in TYPE_CHARS.indices) {
            var best = Float.NEGATIVE_INFINITY
            var bestTpl: CompositedTemplate? = null
            var bestDx = 0
            var bestDy = 0
            for (tpl in bank.templates[t]) {
                if (tpl == null) continue
                val dx = (anchor.cx - tpl.anchor.cx).roundToInt()
                val dy = anchor.bottom - tpl.anchor.bottom
                val v = ncc(gray, meanA, stdA, counted, tpl, dx, dy, bank.background)
                if (v > best) {
                    best = v
                    bestTpl = tpl
                    bestDx = dx
                    bestDy = dy
                }
            }
            // The anchor is quantized to whole pixels; a +-1 px search at the best scale
            // recovers thin outlines (white pieces on light squares) that are half a pixel off.
            if (bestTpl != null) {
                for (ddy in -REFINE_RADIUS..REFINE_RADIUS) for (ddx in -REFINE_RADIUS..REFINE_RADIUS) {
                    if (ddx == 0 && ddy == 0) continue
                    val v = ncc(gray, meanA, stdA, counted, bestTpl, bestDx + ddx, bestDy + ddy, bank.background)
                    if (v > best) best = v
                }
                typeNcc[t] = best
            }
        }

        var bestT = -1
        var secondNcc = -1f
        for (t in typeNcc.indices) {
            val v = typeNcc[t]
            if (v.isNaN()) continue
            if (bestT < 0 || v > typeNcc[bestT]) {
                if (bestT >= 0) secondNcc = max(secondNcc, typeNcc[bestT])
                bestT = t
            } else {
                secondNcc = max(secondNcc, v)
            }
        }
        if (bestT < 0) return SquareResult(null, Float.NaN)
        val bestNcc = typeNcc[bestT]
        val margin = bestNcc - secondNcc

        // 4. Gate.
        if (bestNcc < MIN_NCC || margin < MIN_MARGIN) return SquareResult(null, bestNcc)
        val letter = TYPE_CHARS[bestT].let { if (white) it.uppercaseChar() else it }
        return SquareResult(SquareOverride(letter, typeNcc, colorConfidence), bestNcc)
    }

    /**
     * NCC between the captured square [a] and a T x T canvas filled with [background] onto which
     * [tpl] is pasted at offset ([dx], [dy]). Only [VALID] pixels take part.
     */
    private fun ncc(
        a: FloatArray,
        meanA: Double,
        stdA: Double,
        count: Int,
        tpl: CompositedTemplate,
        dx: Int,
        dy: Int,
        background: Float,
    ): Float {
        val s = tpl.size
        val g = tpl.gray
        var sb = 0.0
        var sbb = 0.0
        var sab = 0.0
        for (y in 0 until T) {
            val ty = y - dy
            val rowInside = ty in 0 until s
            val rowBase = ty * s
            for (x in 0 until T) {
                val k = y * T + x
                if (!VALID[k]) continue
                val tx = x - dx
                val v = if (rowInside && tx >= 0 && tx < s) g[rowBase + tx] else background
                sb += v
                sbb += v * v
                sab += a[k] * v
            }
        }
        val meanB = sb / count
        val varB = sbb / count - meanB * meanB
        if (varB <= 1e-6) return 0f
        val cov = sab / count - meanA * meanB
        return (cov / (stdA * sqrt(varB))).toFloat().coerceIn(-1f, 1f)
    }
}

// ---------------------------------------------------------------------------------------------
// Calibrator
// ---------------------------------------------------------------------------------------------

/** Builds [Templates] from a screenshot of the initial position with white at the bottom. */
object Calibrator {
    private val START_POSITION = arrayOf(
        "rnbqkbnr",
        "pppppppp",
        "........",
        "........",
        "........",
        "........",
        "PPPPPPPP",
        "RNBQKBNR",
    )

    fun calibrate(bitmap: Bitmap): Templates? {
        val px = Pixels(bitmap)
        val grid = BoardLocator.locate(px) ?: return null
        return fromCells(CellReader.read(px, grid))
    }

    fun fromCells(cells: List<Cell>): Templates? {
        if (cells.size != 64 || !looksLikeStartPosition(cells)) return null

        val size = CellReader.FEATURE_SIZE * CellReader.FEATURE_SIZE
        val sums = HashMap<TemplateKey, FloatArray>()
        val counts = HashMap<TemplateKey, Int>()
        for (r in 0 until 8) for (c in 0 until 8) {
            val cell = cells[r * 8 + c]
            val key = TemplateKey(START_POSITION[r][c], cell.isLight)
            val acc = sums.getOrPut(key) { FloatArray(size) }
            for (i in 0 until size) acc[i] += cell.features[i]
            counts[key] = (counts[key] ?: 0) + 1
        }

        val result = HashMap<TemplateKey, FloatArray>()
        for ((key, acc) in sums) {
            val n = counts.getValue(key).toFloat()
            result[key] = FloatArray(size) { acc[it] / n }
        }

        // In the start position each king (and queen) stands on one shade only; copy its template
        // to the other shade as a fallback so it can still be recognised there.
        for (key in result.keys.toList()) {
            val other = TemplateKey(key.piece, !key.lightSquare)
            if (other !in result) result[other] = result.getValue(key).copyOf()
        }
        return Templates(result)
    }

    /**
     * Sanity check: piece ranks must carry clearly more structure than the empty middle, and the
     * bottom pieces must be brighter than the top pieces (white at the bottom).
     */
    private fun looksLikeStartPosition(cells: List<Cell>): Boolean {
        fun energy(cell: Cell): Float = cell.features.fold(0f) { s, v -> s + abs(v) } / cell.features.size
        fun meanValue(cell: Cell): Float = cell.features.sum() / cell.features.size

        val pieceRows = listOf(0, 1, 6, 7)
        val emptyEnergy = (2..5).flatMap { r -> (0 until 8).map { c -> energy(cells[r * 8 + c]) } }.average()
        val pieceEnergy = pieceRows.flatMap { r -> (0 until 8).map { c -> energy(cells[r * 8 + c]) } }.average()
        if (pieceEnergy <= emptyEnergy * 1.5 + 0.005) return false

        val topMean = (0..1).flatMap { r -> (0 until 8).map { c -> meanValue(cells[r * 8 + c]) } }.average()
        val bottomMean = (6..7).flatMap { r -> (0 until 8).map { c -> meanValue(cells[r * 8 + c]) } }.average()
        return bottomMean > topMean
    }
}

// ---------------------------------------------------------------------------------------------
// Orientation
// ---------------------------------------------------------------------------------------------

/**
 * Decides which colour plays from the bottom of the screen. On chess.com the user's own pieces
 * are at the bottom (white when playing white, black when playing black).
 *
 * Evidence, combined into one signed score (positive = white at bottom):
 *  - **Home zones**: white pieces in the bottom two rows vs the top two rows (and the reverse for
 *    black). Pieces mostly stay near their own back ranks, so this dominates early in a game.
 *  - **Pawn files**: on a file holding pawns of both colours, the pawns can never have passed
 *    each other, so the white pawn is below the black one when white is at the bottom.
 *  - **Kings**: a king on its own back rank.
 *  - **Centroid**: each piece's distance from the board centre towards its own side.
 */
object Orientation {
    private const val KING_BACK_RANK_BONUS = 4f
    private const val HOME_ZONE_WEIGHT = 1.5f
    private const val PAWN_FILE_WEIGHT = 2f
    private const val CENTROID_WEIGHT = 0.5f

    /** True if white plays from the bottom of the screen. */
    fun whiteAtBottom(letters: Array<CharArray>): Boolean = score(letters) >= 0f

    /**
     * Signed orientation evidence: positive means white at the bottom, negative black at the
     * bottom; the magnitude is a confidence (about 120 for the starting position, around 0 when
     * the position is genuinely ambiguous).
     */
    fun score(letters: Array<CharArray>): Float {
        var score = 0f
        for (r in 0 until 8) for (c in 0 until 8) {
            val ch = letters[r][c]
            if (ch == '.') continue
            val white = ch.isUpperCase()
            val sign = if (white) 1f else -1f

            // Positive when the piece is in its own (bottom = white) half, scaled by distance.
            score += sign * (r - 3.5f) * CENTROID_WEIGHT

            if (r >= 6) score += sign * HOME_ZONE_WEIGHT
            if (r <= 1) score -= sign * HOME_ZONE_WEIGHT

            when (ch) {
                'K' -> if (r == 7) score += KING_BACK_RANK_BONUS else if (r == 0) score -= KING_BACK_RANK_BONUS
                'k' -> if (r == 0) score += KING_BACK_RANK_BONUS else if (r == 7) score -= KING_BACK_RANK_BONUS
            }
        }

        for (c in 0 until 8) {
            for (rw in 0 until 8) {
                if (letters[rw][c] != 'P') continue
                for (rb in 0 until 8) {
                    if (letters[rb][c] != 'p') continue
                    score += if (rw > rb) PAWN_FILE_WEIGHT else -PAWN_FILE_WEIGHT
                }
            }
        }
        return score
    }
}

// ---------------------------------------------------------------------------------------------
// Fen
// ---------------------------------------------------------------------------------------------

object Fen {

    /**
     * @param letters screen-ordered letters (row 0 at top, col 0 at left).
     * @param whiteAtBottom board orientation on screen.
     * @param whiteToMove side to move (cannot be read from a static board).
     */
    fun build(letters: Array<CharArray>, whiteAtBottom: Boolean, whiteToMove: Boolean = true): String {
        // board[i][f]: i = 0 is rank 8, f = 0 is file a.
        val board = Array(8) { i ->
            CharArray(8) { f ->
                if (whiteAtBottom) letters[i][f] else letters[7 - i][7 - f]
            }
        }

        val placement = board.joinToString("/") { rank ->
            buildString {
                var empty = 0
                for (ch in rank) {
                    if (ch == '.') {
                        empty++
                    } else {
                        if (empty > 0) append(empty)
                        empty = 0
                        append(ch)
                    }
                }
                if (empty > 0) append(empty)
            }
        }

        val castling = buildString {
            if (board[7][4] == 'K') {
                if (board[7][7] == 'R') append('K')
                if (board[7][0] == 'R') append('Q')
            }
            if (board[0][4] == 'k') {
                if (board[0][7] == 'r') append('k')
                if (board[0][0] == 'r') append('q')
            }
        }.ifEmpty { "-" }

        val side = if (whiteToMove) "w" else "b"
        return "$placement $side $castling - 0 1"
    }
}

// ---------------------------------------------------------------------------------------------
// ChessScreenReader
// ---------------------------------------------------------------------------------------------

/**
 * Entry point: screenshot in, FEN out. Uses the [matcher] first when it is ready (confident
 * squares from it, the rest from the [TemplateFreeClassifier]), then [templates] when given,
 * otherwise the [TemplateFreeClassifier] alone, so no setup is required.
 */
class ChessScreenReader(
    private val templates: Templates? = null,
    private val matcher: TemplateMatchingClassifier? = null,
) {

    /** Returns the FEN of the board in [bitmap], or null if no board was found. */
    fun read(bitmap: Bitmap, whiteToMove: Boolean = true): String? {
        val px = Pixels(bitmap)
        val grid = BoardLocator.locate(px) ?: return null
        val cells = CellReader.read(px, grid)
        val tpl = templates
        val overrides = matcher?.takeIf { it.isReady }?.classify(px, grid)
        val letters = when {
            overrides != null -> TemplateFreeClassifier.classify(cells, overrides)
            tpl != null -> Classifier.classify(cells, tpl)
            else -> TemplateFreeClassifier.classify(cells)
        }
        val whiteBottom = Orientation.whiteAtBottom(letters)
        return Fen.build(letters, whiteBottom, whiteToMove)
    }

    companion object {
        /** One-time setup from a start-position screenshot with white at the bottom. */
        fun calibrate(bitmap: Bitmap): Templates? = Calibrator.calibrate(bitmap)
    }
}
