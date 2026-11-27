package com.chesstrainer.app.service

/**
 * Extracts chess moves from chess.com's accessibility tree.
 *
 * Chess.com shows a horizontal move bar with TextViews containing move numbers ("5.") and
 * move notation ("qg5", "h6", "qxe5+"). This reader finds those nodes and returns
 * an ordered list of (moveNumber, sanMove) pairs.
 *
 * Chess.com uses lowercase piece letters in its move list (q=Queen, n=Knight, etc.).
 */
object MoveListReader {

    data class ExtractedMove(val moveNumber: Int, val isWhite: Boolean, val san: String)

    private val MOVE_NUMBER = Regex("^(\\d+)\\.+$")
    private val CHESS_MOVE = Regex(
        "^([kqrbn])?([a-h])?([1-8])?(x)?([a-h])([1-8])(=[kqrbn])?[+#!?]*$",
        RegexOption.IGNORE_CASE
    )
    private val CASTLING = Regex("^[oO0]-[oO0](-[oO0])?[+#!?]*$")

    fun extract(nodes: List<ViewTreeReader.NodeData>): List<ExtractedMove> {
        // Collect all TextViews with their x-position for ordering
        val textNodes = mutableListOf<Pair<Int, String>>() // (x-position, text)
        for (n in nodes) {
            val text = n.text?.trim() ?: continue
            if (text.isEmpty()) continue
            if (n.className != "android.widget.TextView") continue
            if (isMoveNumber(text) || isChessMove(text)) {
                textNodes.add(n.boundsLeft to text)
            }
        }

        if (textNodes.isEmpty()) return emptyList()

        // Sort by x-position (left to right reading order)
        textNodes.sortBy { it.first }

        // Parse: move numbers set the current number, moves alternate white/black
        val moves = mutableListOf<ExtractedMove>()
        var currentNumber = 0
        var expectBlack = false

        for ((_, text) in textNodes) {
            val numMatch = MOVE_NUMBER.matchEntire(text)
            if (numMatch != null) {
                currentNumber = numMatch.groupValues[1].toInt()
                expectBlack = false
                continue
            }

            if (isChessMove(text)) {
                if (currentNumber == 0) {
                    // No move number seen yet. This could be a continuation.
                    // If we have no prior context, assume it's part of an ongoing game.
                    // We'll infer the move number from the game tracker.
                    currentNumber = 1
                    expectBlack = true // Assume it's a black move if no number precedes it
                }

                moves.add(ExtractedMove(currentNumber, !expectBlack, text))
                if (expectBlack) {
                    currentNumber++
                }
                expectBlack = !expectBlack
            }
        }

        return moves
    }

    private fun isMoveNumber(text: String): Boolean = MOVE_NUMBER.matches(text)

    private fun isChessMove(text: String): Boolean {
        val t = text.trim()
        return CHESS_MOVE.matches(t) || CASTLING.matches(t)
    }
}
