package com.chesstrainer.app.service

/**
 * Converts between a position map (square name such as "e4" to FEN piece letter, uppercase for
 * white) and FEN strings.
 */
object FenConverter {

    private const val PIECE_LETTERS = "pnbrqkPNBRQK"
    private val WHITESPACE = Regex("\\s+")

    /**
     * Builds a FEN from [positions]. Entries that are not a FEN piece letter on a square a1..h8
     * are left out, so the result is always well formed.
     *
     * A placement does not show the castling history, the en passant square or the move counters.
     * The castling field is therefore the most the placement allows (see [castlingRights]), en
     * passant is "-" and the counters are "0 1".
     */
    fun positionsToFen(positions: Map<String, Char>, whiteToMove: Boolean = true): String {
        val sb = StringBuilder()
        for (rank in 8 downTo 1) {
            var empty = 0
            for (file in 'a'..'h') {
                val piece = positions["$file$rank"]
                if (piece != null && piece in PIECE_LETTERS) {
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
        sb.append(castlingRights(positions))
        sb.append(" - 0 1")
        return sb.toString()
    }

    /**
     * Reads the piece placement of [fen], which may be a full FEN or only its placement field.
     * Returns an empty map if the placement is malformed: not eight ranks, a rank that does not
     * cover exactly eight files, or a character that is neither a piece letter nor a digit 1-8.
     */
    fun fenToPositions(fen: String): Map<String, Char> {
        val rows = fen.trim().split(WHITESPACE).first().split('/')
        if (rows.size != 8) return emptyMap()

        val positions = mutableMapOf<String, Char>()
        for ((rowIdx, row) in rows.withIndex()) {
            val rank = 8 - rowIdx
            var file = 0
            for (c in row) {
                when (c) {
                    in '1'..'8' -> file += c - '0'
                    in PIECE_LETTERS -> {
                        if (file > 7) return emptyMap()
                        positions["${'a' + file}$rank"] = c
                        file++
                    }
                    else -> return emptyMap()
                }
            }
            if (file != 8) return emptyMap()
        }
        return positions
    }

    /** True if white is to move in [fen]. Also true when the side-to-move field is missing. */
    fun sideToMove(fen: String): Boolean =
        !fen.trim().split(WHITESPACE).getOrNull(1).equals("b", ignoreCase = true)

    /**
     * Castling rights the placement allows: K needs the white king on e1 and a white rook on h1,
     * Q the king on e1 and a rook on a1, and k/q the same on rank 8. Returns "-" if there are none.
     *
     * This is an upper bound: a king or rook that moved and came back has lost the right, and a
     * single position cannot show that. It still has to be inferred, because a right claimed
     * without the king and rook at home makes the FEN invalid, and an engine may reject it or crash.
     */
    private fun castlingRights(positions: Map<String, Char>): String = buildString {
        if (positions["e1"] == 'K') {
            if (positions["h1"] == 'R') append('K')
            if (positions["a1"] == 'R') append('Q')
        }
        if (positions["e8"] == 'k') {
            if (positions["h8"] == 'r') append('k')
            if (positions["a8"] == 'r') append('q')
        }
    }.ifEmpty { "-" }
}
