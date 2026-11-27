package com.chesstrainer.app.engine

/**
 * Sanity checks on what goes into and comes out of the engine. Pure functions without Android
 * dependencies; callers decide how to log and what to skip.
 *
 * A malformed FEN makes Stockfish search garbage (or crash), and a bestmove that does not fit
 * the position it was asked about means the UCI conversation is out of sync, so neither must
 * reach the overlay.
 */
object PositionValidator {

    private const val PIECES = "pnbrqkPNBRQK"
    private const val PROMOTION_PIECES = "qrbn"
    private val WHITESPACE = Regex("\\s+")

    /**
     * Returns why [fen] must not be sent to the engine, or null when it is usable: a placement
     * and a side to move of "w" or "b" (the castling, en passant and counter fields may be
     * missing, the engine defaults them), eight ranks of exactly eight squares made of known
     * piece letters, and exactly one king per side.
     *
     * Only positions the engine cannot search are rejected here. Anything merely suspicious must
     * be logged by the caller and analysed anyway: a rejected position shows the user nothing.
     */
    fun fenError(fen: String): String? {
        val parts = fen.trim().split(WHITESPACE)
        if (parts.size < 2 || parts.size > 6) return "expected 2 to 6 fields, found ${parts.size}"
        val side = parts[1]
        if (side != "w" && side != "b") return "side to move is '$side', expected 'w' or 'b'"

        val ranks = parts[0].split('/')
        if (ranks.size != 8) return "expected 8 ranks, found ${ranks.size}"
        var whiteKings = 0
        var blackKings = 0
        for ((index, rank) in ranks.withIndex()) {
            var squares = 0
            for (c in rank) {
                when {
                    c in '1'..'8' -> squares += c - '0'
                    c in PIECES -> {
                        squares++
                        if (c == 'K') whiteKings++
                        if (c == 'k') blackKings++
                    }
                    else -> return "unknown character '$c' in rank ${8 - index}"
                }
            }
            if (squares != 8) return "rank ${8 - index} has $squares squares, expected 8"
        }
        if (whiteKings != 1) return "found $whiteKings white kings, expected 1"
        if (blackKings != 1) return "found $blackKings black kings, expected 1"
        return null
    }

    /**
     * True when the side that is NOT to move in [fen] is in check. Such a position cannot occur
     * in a game (the side to move could capture the king); in practice it means the side to move
     * was tracked or guessed wrongly. False when [fen] cannot be parsed.
     */
    fun sideNotToMoveInCheck(fen: String): Boolean =
        Board.fromFen(fen)?.sideNotToMoveInCheck() == true

    /**
     * Returns why [move] cannot be the engine's answer for [fen], or null when it is plausible:
     * four or five characters, valid from/to squares, an optional promotion letter, and a piece
     * of the side to move on the from-square that lands neither on one of its own pieces nor on
     * a king (the engine only "captures" a king in a position with the wrong side to move).
     *
     * This is not a full legality check; it is meant to catch a bestmove that belongs to another
     * position. [fen] must be the position the move is played in.
     */
    fun bestMoveError(fen: String, move: String): String? {
        val m = move.trim()
        if (m.length != 4 && m.length != 5) return "length ${m.length}, expected 4 or 5"
        if (!isSquare(m, 0)) return "invalid from-square '${m.substring(0, 2)}'"
        if (!isSquare(m, 2)) return "invalid to-square '${m.substring(2, 4)}'"
        if (m.length == 5 && m[4] !in PROMOTION_PIECES) return "invalid promotion piece '${m[4]}'"

        val board = Board.fromFen(fen) ?: return "position could not be parsed"
        val from = squareIndex(m, 0)
        val to = squareIndex(m, 2)
        if (from == to) return "from- and to-square are the same"
        val piece = board.pieceAt(from) ?: return "no piece on ${m.substring(0, 2)}"
        if (piece.isUpperCase() != board.whiteToMove) {
            return "piece on ${m.substring(0, 2)} belongs to the side not to move"
        }
        val target = board.pieceAt(to)
        if (target != null && target.isUpperCase() == piece.isUpperCase()) {
            return "own piece on ${m.substring(2, 4)}"
        }
        if (target != null && target.lowercaseChar() == 'k') return "captures the king on ${m.substring(2, 4)}"
        if (m.length == 5 && piece.lowercaseChar() != 'p') return "promotion by a non-pawn"
        return null
    }

    private fun isSquare(text: String, offset: Int): Boolean =
        text[offset] in 'a'..'h' && text[offset + 1] in '1'..'8'

    private fun squareIndex(text: String, offset: Int): Int =
        (text[offset + 1] - '1') * 8 + (text[offset] - 'a')
}
