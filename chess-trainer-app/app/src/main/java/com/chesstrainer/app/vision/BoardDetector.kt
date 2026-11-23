package com.chesstrainer.app.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
        val sums = FloatArray(width * height)
        val counts = IntArray(width * height)
        val xMap = IntArray(sw) { min(width - 1, (it.toLong() * width / sw).toInt()) }
        val row = IntArray(sw)
        for (y in 0 until sh) {
            src.getPixels(row, 0, sw, 0, y, sw, 1)
            val base = min(height - 1, (y.toLong() * height / sh).toInt()) * width
            for (x in 0 until sw) {
                val c = row[x]
                val l = 0.299f * ((c shr 16) and 0xFF) +
                    0.587f * ((c shr 8) and 0xFF) +
                    0.114f * (c and 0xFF)
                val i = base + xMap[x]
                sums[i] += l
                counts[i]++
            }
        }
        luma = FloatArray(width * height) { if (counts[it] > 0) sums[it] / counts[it] else 0f }
        if (src !== bitmap) src.recycle()
    }

    /** Luma (0..255) at integer coordinates; out-of-range coordinates are clamped. */
    fun lumaAt(x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return luma[cy * width + cx]
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

object Orientation {
    private const val KING_BACK_RANK_BONUS = 4f

    /** True if white plays from the bottom of the screen. */
    fun whiteAtBottom(letters: Array<CharArray>): Boolean {
        var score = 0f
        for (r in 0 until 8) for (c in 0 until 8) {
            val ch = letters[r][c]
            if (ch == '.') continue
            // Positive when the piece is in the bottom half, scaled by distance from the centre.
            val d = r - 3.5f
            score += if (ch.isUpperCase()) d else -d
            when (ch) {
                'K' -> if (r == 7) score += KING_BACK_RANK_BONUS else if (r == 0) score -= KING_BACK_RANK_BONUS
                'k' -> if (r == 0) score += KING_BACK_RANK_BONUS else if (r == 7) score -= KING_BACK_RANK_BONUS
            }
        }
        return score >= 0f
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

/** Entry point: screenshot in, FEN out. */
class ChessScreenReader(private val templates: Templates) {

    /** Returns the FEN of the board in [bitmap], or null if no board was found. */
    fun read(bitmap: Bitmap, whiteToMove: Boolean = true): String? {
        val px = Pixels(bitmap)
        val grid = BoardLocator.locate(px) ?: return null
        val cells = CellReader.read(px, grid)
        val letters = Classifier.classify(cells, templates)
        val whiteBottom = Orientation.whiteAtBottom(letters)
        return Fen.build(letters, whiteBottom, whiteToMove)
    }

    companion object {
        /** One-time setup from a start-position screenshot with white at the bottom. */
        fun calibrate(bitmap: Bitmap): Templates? = Calibrator.calibrate(bitmap)
    }
}
