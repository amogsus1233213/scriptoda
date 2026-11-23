package com.chesstrainer.app.service

import kotlin.math.abs
import kotlin.math.min

object BoardPositionParser {

    private val PIECE_PATTERNS = mapOf(
        "pawn" to 'p', "knight" to 'n', "bishop" to 'b',
        "rook" to 'r', "queen" to 'q', "king" to 'k',
    )

    private val SQUARE_REGEX = Regex("[a-h][1-8]")
    private val FEN_REGEX = Regex("[rnbqkpRNBQKP1-8]{1,8}(/[rnbqkpRNBQKP1-8]{1,8}){7}")

    /** Minimum number of informative node pairs before the bounds are trusted for orientation. */
    private const val MIN_ORIENTATION_PAIRS = 3

    /** Fraction of informative node pairs that must agree on one orientation. */
    private const val MIN_ORIENTATION_AGREEMENT = 0.8f

    data class ParseResult(
        val positions: Map<String, Char>?,
        val fen: String?,
        val nodeCount: Int,
        val boardNodesFound: Int,
        /**
         * Board orientation on screen, derived from where the piece nodes are drawn (true = white
         * at the bottom), or null if the node bounds cannot tell (e.g. the board came as FEN text).
         * The square names in [positions] are absolute, so this is the only orientation signal
         * the accessibility data carries; without it the overlay cannot know how to flip arrows.
         */
        val whiteAtBottom: Boolean? = null,
    )

    /** A piece node with its algebraic square and the centre of its on-screen bounds. */
    private class PlacedNode(val file: Int, val rank: Int, val cx: Float, val cy: Float, val size: Float)

    fun parse(nodes: List<ViewTreeReader.NodeData>): ParseResult {
        var boardNodes = 0
        val positions = mutableMapOf<String, Char>()
        val placed = mutableListOf<PlacedNode>()
        var foundFen: String? = null

        for (node in nodes) {
            // Check for FEN string in any text field
            val allText = listOfNotNull(node.text, node.contentDescription).joinToString(" ")

            val fenMatch = FEN_REGEX.find(allText)
            if (fenMatch != null) {
                foundFen = fenMatch.value
                return ParseResult(parseFen(foundFen), foundFen, nodes.size, 1)
            }

            // Check for piece descriptions like "White Knight on e4" or "e4 Knight"
            val desc = node.contentDescription ?: continue
            val descLower = desc.lowercase()

            val square = SQUARE_REGEX.find(descLower)?.value
            if (square != null) {
                for ((name, piece) in PIECE_PATTERNS) {
                    if (descLower.contains(name)) {
                        // Without an explicit colour word the piece is assumed to be black.
                        val isWhite = descLower.contains("white")
                        val finalPiece = if (isWhite) piece.uppercaseChar() else piece
                        positions[square] = finalPiece
                        boardNodes++
                        placedNode(square, node)?.let { placed.add(it) }
                        break
                    }
                }
            }
        }

        return ParseResult(
            positions = positions.takeIf { it.isNotEmpty() },
            fen = foundFen,
            nodeCount = nodes.size,
            boardNodesFound = boardNodes,
            whiteAtBottom = orientationFromBounds(placed),
        )
    }

    private fun placedNode(square: String, node: ViewTreeReader.NodeData): PlacedNode? {
        val w = node.boundsRight - node.boundsLeft
        val h = node.boundsBottom - node.boundsTop
        if (w <= 0 || h <= 0) return null
        return PlacedNode(
            file = square[0] - 'a',
            rank = square[1] - '1',
            cx = (node.boundsLeft + node.boundsRight) / 2f,
            cy = (node.boundsTop + node.boundsBottom) / 2f,
            size = min(w, h).toFloat(),
        )
    }

    /**
     * Decides the on-screen orientation from the piece nodes' bounds. Every pair of nodes on
     * different ranks votes by which one is drawn higher up, and every pair on different files by
     * which one is drawn further right. With white at the bottom, higher ranks are higher up and
     * later files further right; with black at the bottom both are reversed. Returns null when
     * there are too few informative pairs (e.g. all nodes share the same bounds) or the votes are
     * not clearly one-sided (e.g. stray nodes outside the board, such as a move list).
     */
    private fun orientationFromBounds(nodes: List<PlacedNode>): Boolean? {
        var votes = 0
        var total = 0
        for (i in nodes.indices) {
            val a = nodes[i]
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]
                // Nodes on different squares are at least one square apart along that axis.
                val tolerance = min(a.size, b.size) / 4f
                if (a.rank != b.rank) {
                    val dy = b.cy - a.cy
                    if (abs(dy) > tolerance) {
                        total++
                        // White at bottom: the higher rank has the smaller y.
                        votes += if ((a.rank - b.rank) * dy > 0f) 1 else -1
                    }
                }
                if (a.file != b.file) {
                    val dx = b.cx - a.cx
                    if (abs(dx) > tolerance) {
                        total++
                        // White at bottom: the later file has the larger x.
                        votes += if ((b.file - a.file) * dx > 0f) 1 else -1
                    }
                }
            }
        }
        if (total < MIN_ORIENTATION_PAIRS) return null
        // votes = agree - disagree, so agreement >= p  <=>  |votes| >= (2p - 1) * total.
        val needed = (2f * MIN_ORIENTATION_AGREEMENT - 1f) * total
        return when {
            votes >= needed -> true
            -votes >= needed -> false
            else -> null
        }
    }

    private fun parseFen(fen: String): Map<String, Char> {
        val positions = mutableMapOf<String, Char>()
        val rows = fen.split("/")
        if (rows.size != 8) return positions

        for ((rowIdx, row) in rows.withIndex()) {
            val rank = 8 - rowIdx
            var file = 0
            for (c in row) {
                if (c.isDigit()) {
                    file += c - '0'
                } else {
                    if (file < 8) {
                        val square = "${'a' + file}$rank"
                        positions[square] = c
                    }
                    file++
                }
            }
        }
        return positions
    }

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
        sb.append("KQkq - 0 1")
        return sb.toString()
    }
}
