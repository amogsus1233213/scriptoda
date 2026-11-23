package com.chesstrainer.app.service

object BoardPositionParser {

    private val PIECE_PATTERNS = mapOf(
        "pawn" to 'p', "knight" to 'n', "bishop" to 'b',
        "rook" to 'r', "queen" to 'q', "king" to 'k',
        "Pawn" to 'p', "Knight" to 'n', "Bishop" to 'b',
        "Rook" to 'r', "Queen" to 'q', "King" to 'k',
    )

    private val SQUARE_REGEX = Regex("[a-h][1-8]")
    private val FEN_REGEX = Regex("[rnbqkpRNBQKP1-8]{1,8}(/[rnbqkpRNBQKP1-8]{1,8}){7}")

    data class ParseResult(
        val positions: Map<String, Char>?,
        val fen: String?,
        val nodeCount: Int,
        val boardNodesFound: Int,
    )

    fun parse(nodes: List<ViewTreeReader.NodeData>): ParseResult {
        var boardNodes = 0
        val positions = mutableMapOf<String, Char>()
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
                    if (descLower.contains(name.lowercase())) {
                        val isWhite = descLower.contains("white") ||
                            (!descLower.contains("black") && piece.isUpperCase())
                        val finalPiece = if (isWhite) piece.uppercaseChar() else piece
                        positions[square] = finalPiece
                        boardNodes++
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
        )
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
