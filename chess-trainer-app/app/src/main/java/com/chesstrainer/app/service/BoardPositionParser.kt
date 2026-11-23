package com.chesstrainer.app.service

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private typealias Node = ViewTreeReader.NodeData

private val Node.width get() = boundsRight - boundsLeft
private val Node.height get() = boundsBottom - boundsTop
private val Node.centerX get() = (boundsLeft + boundsRight) / 2.0
private val Node.centerY get() = (boundsTop + boundsBottom) / 2.0
private val Node.hasArea get() = width > 0 && height > 0

/**
 * Reads the piece placement from chess.com's accessibility tree (see [ViewTreeReader]).
 *
 * Sources, in order of preference:
 *  1. A FEN placement string in any node's text or content description.
 *  2. Per-node piece descriptions. A node's text and description are split into words (case and
 *     accents ignored) and searched for a square ("e4"), a piece name and a colour, in any order
 *     and in English, Spanish, French, German, Portuguese, Italian or Russian. "White Knight on
 *     e4", "e4 White Knight" and "Caballo blanco en e4" all read the same.
 *
 * Nodes that name a piece but no square ("White Knight") are placed by their screen bounds. The
 * board grid for that is fitted to the nodes that do name a square, including empty squares
 * described as just "e4". The fit also gives the board orientation. When no node names a square,
 * the board is taken to be the smallest square node holding the pieces. Its orientation then
 * comes from the coordinate labels or, failing that, from where each side's pieces stand.
 *
 * The parser would rather give no answer than a wrong one. When [ParseResult.positions] is null
 * the caller falls back to screen capture, but a wrong position would be analysed as if it were
 * exact. A read is therefore rejected when two nodes disagree about a square, when a piece on the
 * board cannot be placed or coloured, or when the result is not a plausible chess position.
 */
object BoardPositionParser {

    data class ParseResult(
        /** Square ("e4") to FEN letter (uppercase white, lowercase black); null if no usable board. */
        val positions: Map<String, Char>?,
        /** FEN placement field, when the tree contained one. */
        val fen: String?,
        val nodeCount: Int,
        /** Nodes that were read as pieces on the board. */
        val boardNodesFound: Int,
        /**
         * Board orientation on screen: true if white is at the bottom, false if black is. The
         * square names in [positions] are absolute, so this is the only orientation signal the
         * tree carries. It is null unless read from the tree (a grid fitted to the square names,
         * or coordinate labels). A guess from where the pieces stand is never reported here.
         */
        val whiteAtBottom: Boolean? = null,
        /** Why [positions] is null although board nodes were found (for logging); else null. */
        val rejectReason: String? = null,
    )

    // Regexes come first: the word tables below are normalised with them during initialisation.
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val WORD_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val SQUARE_WORD = Regex("[a-h][1-8]")
    private val FEN_REGEX = Regex("[rnbqkpRNBQKP1-8]{1,8}(?:/[rnbqkpRNBQKP1-8]{1,8}){7}")

    /**
     * Piece names to lowercase FEN letters, stored normalised (see [normalize]). Each line lists
     * English, Spanish, French, German, Portuguese, Italian, then Russian (with common declined
     * forms). "torre" and "dama" are shared by Spanish, Portuguese and Italian; "dame" by French
     * and German. Words are only matched whole, so the Italian "re" (king) does not hit "torre".
     */
    private val PIECE_WORDS: Map<String, Char> = buildMap {
        fun add(piece: Char, vararg words: String) = words.forEach { put(normalize(it), piece) }
        add('p', "pawn", "peón", "pion", "bauer", "bauern", "peão", "pedone", "пешка", "пешку", "пешки")
        add('n', "knight", "caballo", "cavalier", "springer", "cavalo", "cavallo", "конь", "коня")
        add('b', "bishop", "alfil", "fou", "läufer", "laeufer", "bispo", "alfiere", "слон", "слона")
        add('r', "rook", "torre", "tour", "turm", "ладья", "ладью", "ладьи")
        add('q', "queen", "dama", "reina", "dame", "reine", "rainha", "donna", "regina", "ферзь", "ферзя")
        add('k', "king", "rey", "roi", "könig", "koenig", "rei", "re", "король", "короля")
    }

    /** Colour words in the same languages. Normalising also covers "weiss" and "черный". */
    private val WHITE_WORDS: Set<String> = normalizedSetOf(
        "white",
        "blanco", "blanca", "blancos", "blancas",
        "blanc", "blanche", "blancs", "blanches",
        "weiß", "weiße", "weißer", "weißen", "weißes",
        "branco", "branca", "brancos", "brancas",
        "bianco", "bianca", "bianchi", "bianche",
        "белый", "белая", "белые", "белого", "белой", "белую", "белым", "белых",
    )
    private val BLACK_WORDS: Set<String> = normalizedSetOf(
        "black",
        "negro", "negra", "negros", "negras",
        "noir", "noire", "noirs", "noires",
        "schwarz", "schwarze", "schwarzer", "schwarzen", "schwarzes",
        "preto", "preta", "pretos", "pretas",
        "nero", "nera", "neri", "nere",
        "чёрный", "чёрная", "чёрные", "чёрного", "чёрной", "чёрную", "чёрным", "чёрных",
    )

    /** Fewest square-naming nodes a board grid is fitted to. */
    private const val MIN_GRID_NODES = 4

    /** Fewest piece nodes the board container is searched for. */
    private const val MIN_CONTAINER_PIECES = 2

    /** Board nodes may differ from the median node size by this fraction. */
    private const val SIZE_TOLERANCE = 0.35

    /** A node centred further than this from the square it names (in squares) is an outlier. */
    private const val MAX_LABEL_OFFSET = 0.4

    /** Size range of a node read as a piece on the board, in squares. */
    private const val MIN_PIECE_SIZE = 0.5
    private const val MAX_PIECE_SIZE = 1.6

    /** Smallest [distributionScore] magnitude trusted to guess the orientation. */
    private const val MIN_DISTRIBUTION_SCORE = 8.0

    fun parse(nodes: List<ViewTreeReader.NodeData>): ParseResult {
        val described = nodes.mapNotNull(::describe)
        val squareGrid = fitGridToSquares(described)

        // A FEN string is unambiguous, so it wins over piece descriptions.
        for (node in nodes) {
            val fen = listOfNotNull(node.text, node.contentDescription).firstNotNullOfOrNull(::findFen)
                ?: continue
            return ParseResult(
                positions = FenConverter.fenToPositions(fen),
                fen = fen,
                nodeCount = nodes.size,
                boardNodesFound = 1,
                whiteAtBottom = squareGrid?.whiteAtBottom,
            )
        }

        val grid = squareGrid ?: fitGridToContainer(nodes, described)
        val board = mutableMapOf<String, Char>()
        var boardNodes = 0
        var conflicts = 0
        var unresolved = 0

        // Coloured pieces go first, so a colourless one ("Knight" inside a "White Knight on e4"
        // cell) can be checked against them.
        for (d in described.filter { it.piece != null }.sortedBy { it.white == null }) {
            val kind = d.piece ?: continue
            val square = when {
                grid != null -> {
                    // Off the board or not square-sized: captured-piece icons, move list, banners.
                    val onScreen = grid.squareOf(d.node) ?: continue
                    // Names another square than where it is drawn: not a board node either.
                    if (d.square != null && d.square != onScreen) continue
                    onScreen
                }
                d.square != null -> d.square
                // A coloured piece with nowhere to put it means the board is incomplete. A
                // colourless word ("Rey" in a player name) is more likely not about the board.
                d.white != null -> { unresolved++; continue }
                else -> continue
            }

            val existing = board[square]
            if (d.white == null) {
                when {
                    existing == null -> unresolved++
                    existing.lowercaseChar() != kind -> conflicts++
                    else -> boardNodes++
                }
                continue
            }
            val letter = if (d.white) kind.uppercaseChar() else kind
            when (existing) {
                null -> board[square] = letter
                letter -> {}
                else -> conflicts++
            }
            boardNodes++
        }

        val whiteAtBottom = grid?.takeIf { it.reliable }?.whiteAtBottom
        if (boardNodes == 0 && unresolved == 0 && conflicts == 0) {
            return ParseResult(null, null, nodes.size, 0, whiteAtBottom)
        }
        val reject = when {
            conflicts > 0 -> "$conflicts piece descriptions contradict each other"
            unresolved > 0 -> "$unresolved pieces could not be placed or coloured"
            else -> implausibility(board)
        }
        return ParseResult(
            positions = board.takeIf { reject == null },
            fen = null,
            nodeCount = nodes.size,
            boardNodesFound = boardNodes,
            whiteAtBottom = whiteAtBottom,
            rejectReason = reject,
        )
    }

    /**
     * Builds a FEN from [positions]. Castling rights are inferred from the placement (see
     * [inferCastlingRights]). En passant is unknown and the move counters are reset.
     */
    fun positionsToFen(positions: Map<String, Char>, whiteToMove: Boolean = true): String {
        val sb = StringBuilder()
        for (rank in 8 downTo 1) {
            var empty = 0
            for (file in 'a'..'h') {
                val piece = positions["$file$rank"]
                if (piece != null) {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(piece)
                } else {
                    empty++
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 1) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        sb.append(inferCastlingRights(positions))
        sb.append(" - 0 1")
        return sb.toString()
    }

    /**
     * Castling rights the placement allows: K needs the white king on e1 and a white rook on h1,
     * Q the king on e1 and a rook on a1, and k/q the same on rank 8. Returns "-" if there are none.
     * This is an upper bound: a king or rook that moved and came back has lost the right, and a
     * single position cannot show that.
     */
    fun inferCastlingRights(positions: Map<String, Char>): String = buildString {
        if (positions["e1"] == 'K') {
            if (positions["h1"] == 'R') append('K')
            if (positions["a1"] == 'R') append('Q')
        }
        if (positions["e8"] == 'k') {
            if (positions["h8"] == 'r') append('k')
            if (positions["a8"] == 'r') append('q')
        }
    }.ifEmpty { "-" }

    // ---------------------------------------------------------------------------------------
    // Node descriptions

    /** What one node says about the board. */
    private class Described(
        val node: Node,
        /** The one square the node names, if any. */
        val square: String?,
        /** Lowercase FEN letter of the piece the node names, if any. */
        val piece: Char?,
        /** Colour of that piece: true white, false black, null if not stated. */
        val white: Boolean?,
    )

    /** Reads [node]'s words; null if it names neither a square nor a piece, or is ambiguous. */
    private fun describe(node: Node): Described? {
        val words = listOfNotNull(node.contentDescription, node.text).flatMap(::wordsOf)
        if (words.isEmpty()) return null

        val squares = words.filter { SQUARE_WORD.matches(it) }.toSet()
        // Several squares ("e2 e4", "Knight from g1 to f3") describe a move, not a square.
        if (squares.size > 1) return null

        val pieceAt = words.indices.filter { words[it] in PIECE_WORDS }
        val pieces = pieceAt.map { PIECE_WORDS.getValue(words[it]) }.toSet()
        // Several piece kinds ("Knight takes Bishop") describe a move or a capture.
        if (pieces.size > 1) return null

        val square = squares.firstOrNull()
        val piece = pieces.firstOrNull()
        if (square == null && piece == null) return null
        val white = if (piece != null) colourNear(words, pieceAt) else null
        return Described(node, square, piece, white)
    }

    /**
     * The colour word closest to a piece word, so "White Knight on a black square" reads as
     * white. Null if there is none, or if two different colours are equally close.
     */
    private fun colourNear(words: List<String>, pieceAt: List<Int>): Boolean? {
        var best: Boolean? = null
        var bestDistance = Int.MAX_VALUE
        var tie = false
        for ((i, word) in words.withIndex()) {
            val white = when (word) {
                in WHITE_WORDS -> true
                in BLACK_WORDS -> false
                else -> null
            } ?: continue
            val distance = pieceAt.minOf { abs(it - i) }
            if (distance < bestDistance) {
                best = white
                bestDistance = distance
                tie = false
            } else if (distance == bestDistance && white != best) {
                tie = true
            }
        }
        return if (tie) null else best
    }

    /** Lowercase, without accents ("Peón" -> "peon", "чёрный" -> "черныи"), ß as "ss". */
    private fun normalize(s: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
            .replace("ß", "ss")

    private fun wordsOf(s: String): List<String> =
        normalize(s).split(WORD_SEPARATORS).filter { it.isNotEmpty() }

    private fun normalizedSetOf(vararg words: String): Set<String> = words.mapTo(HashSet(), ::normalize)

    // ---------------------------------------------------------------------------------------
    // FEN

    /** The first FEN placement in [text] that describes a plausible position, if any. */
    private fun findFen(text: String): String? = FEN_REGEX.findAll(text)
        .map { it.value }
        .firstOrNull { fen ->
            fen.split('/').all { row -> row.sumOf { c -> if (c.isDigit()) c - '0' else 1 } == 8 } &&
                implausibility(FenConverter.fenToPositions(fen)) == null
        }

    /** Why [board] cannot be a chess position, or null if it can. */
    private fun implausibility(board: Map<String, Char>): String? {
        for (white in listOf(true, false)) {
            val side = board.values.filter { it.isUpperCase() == white }
            val name = if (white) "white" else "black"
            val kings = side.count { it.lowercaseChar() == 'k' }
            if (kings != 1) return "$kings $name kings"
            if (side.size > 16) return "${side.size} $name pieces"
            if (side.count { it.lowercaseChar() == 'p' } > 8) return "more than 8 $name pawns"
        }
        if (board.any { (square, piece) -> piece.lowercaseChar() == 'p' && square[1] in "18" }) {
            return "pawn on the first or last rank"
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Board geometry

    /**
     * Maps screen positions to squares. A node centred at (x, y) is on file index
     * round((x - x0) / dx) and rank index round((y - y0) / dy), where 0..7 stand for a..h and
     * 1..8. The signs of [dx] and [dy] encode the orientation.
     */
    private class Grid(
        val x0: Double,
        val dx: Double,
        val y0: Double,
        val dy: Double,
        /** Whether the orientation was read from the tree rather than guessed. */
        val reliable: Boolean,
    ) {
        /** Rank numbers grow upwards on screen exactly when white is at the bottom. */
        val whiteAtBottom: Boolean get() = dy < 0
        val cellSize: Double get() = (abs(dx) + abs(dy)) / 2

        /** The square [node] is drawn on, or null if it is off the board or not square-sized. */
        fun squareOf(node: Node): String? {
            if (!node.hasArea) return null
            val size = MIN_PIECE_SIZE * cellSize..MAX_PIECE_SIZE * cellSize
            if (node.width.toDouble() !in size || node.height.toDouble() !in size) return null
            val file = ((node.centerX - x0) / dx).roundToInt()
            val rank = ((node.centerY - y0) / dy).roundToInt()
            if (file !in 0..7 || rank !in 0..7) return null
            return "${'a' + file}${rank + 1}"
        }

        /** How far [node] is from the centre of [square], in squares (Chebyshev distance). */
        fun offset(node: Node, square: String): Double = max(
            abs((node.centerX - x0) / dx - (square[0] - 'a')),
            abs((node.centerY - y0) / dy - (square[1] - '1')),
        )
    }

    /**
     * Fits the board grid to the nodes that name a square. It needs [MIN_GRID_NODES] nodes of a
     * common square-like size, spanning at least two files and two ranks. Text that names a square
     * but is not on the board (move list, banners) is dropped as a size or position outlier.
     */
    private fun fitGridToSquares(described: List<Described>): Grid? {
        val candidates = described.filter { it.square != null && it.node.hasArea && isSquarish(it.node, 0.6) }
        if (candidates.size < MIN_GRID_NODES) return null

        val medianWidth = candidates.map { it.node.width }.median()
        val medianHeight = candidates.map { it.node.height }.median()
        val sized = candidates.filter {
            abs(it.node.width - medianWidth) <= SIZE_TOLERANCE * medianWidth &&
                abs(it.node.height - medianHeight) <= SIZE_TOLERANCE * medianHeight
        }

        // One outlier pass: drop nodes far from the square they name, then refit.
        val first = leastSquaresGrid(sized) ?: return null
        val inliers = sized.filter { first.offset(it.node, it.square!!) <= MAX_LABEL_OFFSET }
        if (inliers.size < 0.75 * sized.size) return null
        val grid = if (inliers.size == sized.size) first else leastSquaresGrid(inliers) ?: return null

        // Board nodes are about one square in size. This rejects grids fitted to unrelated text.
        val nodeSize = (medianWidth + medianHeight) / 2
        return grid.takeIf { nodeSize / it.cellSize in MIN_PIECE_SIZE..1.3 }
    }

    private fun leastSquaresGrid(points: List<Described>): Grid? {
        if (points.size < MIN_GRID_NODES) return null
        val (dx, x0) = linearFit(points.map { (it.square!![0] - 'a').toDouble() to it.node.centerX })
            ?: return null
        val (dy, y0) = linearFit(points.map { (it.square!![1] - '1').toDouble() to it.node.centerY })
            ?: return null
        // On any real board, a..h runs left to right exactly when 1..8 runs bottom to top.
        if ((dx > 0) != (dy < 0)) return null
        if (abs(dx) < 1.0 || abs(dx) / abs(dy) !in 0.8..1.25) return null
        return Grid(x0, dx, y0, dy, reliable = true)
    }

    /**
     * Used when no node names a square. The board is then the smallest square node that holds
     * most piece nodes and is about eight pieces wide. Its orientation comes from the coordinate
     * labels, or failing that from the piece distribution when that is clear-cut. Returns null
     * if the orientation cannot be told.
     */
    private fun fitGridToContainer(nodes: List<Node>, described: List<Described>): Grid? {
        val pieces = described.filter {
            it.piece != null && it.square == null && it.node.hasArea && isSquarish(it.node, 0.6)
        }
        if (pieces.size < MIN_CONTAINER_PIECES) return null
        val pieceSize = pieces.map { max(it.node.width, it.node.height) }.median()

        val board = nodes
            .filter { n ->
                n.hasArea && isSquarish(n, 0.9) && n.width / pieceSize in 7.0..12.0 &&
                    pieces.count { containsCenter(n, it.node) } >= 0.8 * pieces.size
            }
            .minByOrNull { it.width.toLong() * it.height }
            ?: return null

        val cellX = board.width / 8.0
        val cellY = board.height / 8.0
        val labelled = coordinateOrientation(nodes, board)
        val whiteAtBottom = labelled ?: run {
            val score = distributionScore(pieces.filter { containsCenter(board, it.node) }, board, cellY)
            when {
                score >= MIN_DISTRIBUTION_SCORE -> true
                score <= -MIN_DISTRIBUTION_SCORE -> false
                else -> null
            }
        } ?: return null

        val left = board.boundsLeft.toDouble()
        val top = board.boundsTop.toDouble()
        return if (whiteAtBottom) {
            Grid(left + cellX / 2, cellX, top + 7.5 * cellY, -cellY, reliable = labelled != null)
        } else {
            Grid(left + 7.5 * cellX, -cellX, top + cellY / 2, cellY, reliable = labelled != null)
        }
    }

    /**
     * Orientation from coordinate labels inside [board] (single-character nodes "1".."8" and
     * "a".."h"). White is at the bottom when rank numbers grow upwards and file letters grow to
     * the right. Null if there are no labels or the two kinds disagree.
     */
    private fun coordinateOrientation(nodes: List<Node>, board: Node): Boolean? {
        val ranks = mutableListOf<Pair<Double, Double>>()
        val files = mutableListOf<Pair<Double, Double>>()
        for (n in nodes) {
            if (!n.hasArea || !containsCenter(board, n)) continue
            val label = (n.text ?: n.contentDescription)?.trim()?.lowercase() ?: continue
            if (label.length != 1) continue
            when (val c = label[0]) {
                in '1'..'8' -> ranks += (c - '1').toDouble() to n.centerY
                in 'a'..'h' -> files += (c - 'a').toDouble() to n.centerX
            }
        }
        val byRanks = linearFit(ranks)?.let { (slope, _) -> slope < 0 }
        val byFiles = linearFit(files)?.let { (slope, _) -> slope > 0 }
        return when {
            byRanks == null -> byFiles
            byFiles == null || byFiles == byRanks -> byRanks
            else -> null
        }
    }

    /**
     * Positive when white pieces stand lower on screen than black ones, negative when higher.
     * Each piece adds its row offset from the board's middle, signed by colour. Pawns count
     * double, since they never move backwards.
     */
    private fun distributionScore(pieces: List<Described>, board: Node, cellY: Double): Double =
        pieces.sumOf { p ->
            val white = p.white ?: return@sumOf 0.0
            val row = ((p.node.centerY - board.boundsTop) / cellY).toInt().coerceIn(0, 7)
            val weight = if (p.piece == 'p') 2.0 else 1.0
            (if (white) 1.0 else -1.0) * weight * (row - 3.5)
        }

    /** Least-squares slope and intercept of y over x for (x, y) points; null if x never varies. */
    private fun linearFit(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (points.size < 2) return null
        val meanX = points.sumOf { it.first } / points.size
        val meanY = points.sumOf { it.second } / points.size
        var sxx = 0.0
        var sxy = 0.0
        for ((x, y) in points) {
            sxx += (x - meanX) * (x - meanX)
            sxy += (x - meanX) * (y - meanY)
        }
        if (sxx == 0.0) return null
        val slope = sxy / sxx
        return slope to meanY - slope * meanX
    }

    /** Whether [node]'s aspect ratio is within [minRatio]..1/[minRatio]. */
    private fun isSquarish(node: Node, minRatio: Double): Boolean {
        val ratio = node.width.toDouble() / node.height
        return ratio in minRatio..1 / minRatio
    }

    private fun containsCenter(outer: Node, inner: Node): Boolean =
        inner.centerX >= outer.boundsLeft && inner.centerX < outer.boundsRight &&
            inner.centerY >= outer.boundsTop && inner.centerY < outer.boundsBottom

    private fun List<Int>.median(): Double {
        val s = sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid].toDouble() else (s[mid - 1] + s[mid]) / 2.0
    }
}
