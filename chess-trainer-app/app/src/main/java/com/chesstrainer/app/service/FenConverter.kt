package com.chesstrainer.app.service

object FenConverter {

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

    fun fenToPositions(fen: String): Map<String, Char> {
        val positions = mutableMapOf<String, Char>()
        val rows = fen.trim().substringBefore(' ').split("/")
        if (rows.size != 8) return positions

        for ((rowIdx, row) in rows.withIndex()) {
            val rank = 8 - rowIdx
            var file = 0
            for (c in row) {
                if (c.isDigit()) {
                    file += c - '0'
                } else {
                    if (file < 8) {
                        positions["${'a' + file}$rank"] = c
                    }
                    file++
                }
            }
        }
        return positions
    }

    fun sideToMove(fen: String): Boolean {
        val parts = fen.trim().split(" ")
        return parts.getOrNull(1) != "b"
    }
}
