package com.chesstrainer.app.engine

import java.util.Locale
import kotlin.math.abs

/**
 * Short, fun one-liners for the overlay's best move, in the voice of a slightly sarcastic,
 * very hyped chess coach. Pure Kotlin (no Android dependencies), so it is easy to unit test.
 *
 * The line is picked deterministically from the position, so re-rendering the same position
 * never makes the commentary flicker between lines.
 */
object MoveCommentary {

    /**
     * Generates a short commentary (1-2 lines) for the best engine move.
     *
     * @param fromSquare origin square of the suggested move, e.g. "e2"
     * @param toSquare destination square of the suggested move, e.g. "e4"
     * @param label label shown on the best line. When a move was just played this is the
     *   classification of THAT move (Best, Brilliant, Blunder ...), i.e. a move by the side that
     *   is NOT to move now; otherwise it is "Best move" / "Best reply".
     * @param evalCp centipawns from white's POV (positive = white better)
     * @param mateIn moves to mate from the side to move's POV (positive = side to move mates)
     * @param fen current position FEN (before the suggested move)
     * @param isUsersTurn whether it is the user's turn to move
     * @param rank 1 = best line, 2 = second, 3 = third; only the best line gets commentary
     * @return the commentary, or "" when there is nothing to say (e.g. [rank] != 1)
     */
    fun generate(
        fromSquare: String,
        toSquare: String,
        label: String,
        evalCp: Int?,
        mateIn: Int?,
        fen: String,
        isUsersTurn: Boolean,
        rank: Int = 1,
    ): String {
        if (rank != 1) return ""
        val board = parseBoard(fen) ?: return ""
        val from = squareIndex(fromSquare) ?: return ""
        val to = squareIndex(toSquare) ?: return ""
        val piece = board[from]
        if (piece == EMPTY) return ""

        val moverIsWhite = piece.isUpperCase()
        val captured = board[to].takeIf { it != EMPTY }
        val isCapture = captured != null
        val kind = piece.lowercaseChar()
        val isCastle = kind == 'k' && isCastling(fromSquare, toSquare)
        val isPromotion = kind == 'p' && (to / 8 == 7 || to / 8 == 0)

        // Board after the suggested move (castling rook and en passant victim included).
        val after = applyMove(board, from, to, isCastle, isPromotion)
        val givesCheck = kingSquare(after, !moverIsWhite)?.let { isAttacked(after, it, moverIsWhite) } ?: false
        // Defended destination: the opponent can recapture right away.
        val landsDefended = isAttacked(after, to, !moverIsWhite)
        val isSacrifice = isCapture && landsDefended && pieceValue(piece) > pieceValue(captured!!) + 1
        val isHanging = isCapture && !landsDefended && pieceValue(captured!!) >= 3

        // Eval from the side to move's POV (the side playing the suggested move).
        val moverEval = evalCp?.let { if (moverIsWhite) it else -it }
        val lastLabel = label.trim().lowercase(Locale.ROOT)

        val pieceName = pieceName(piece)
        val capturedName = captured?.let { pieceName(it) } ?: "piece"
        val seed = fen.hashCode() * 31 + fromSquare.hashCode() * 7 + toSquare.hashCode()

        fun pick(lines: Array<String>, vararg args: Any): String {
            val line = lines[abs(seed % lines.size)]
            val text = if (args.isEmpty()) line else String.format(Locale.US, line, *args)
            return text.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        return if (isUsersTurn) {
            when {
                mateIn != null && mateIn == 1 -> pick(CHECKMATE_LINES)
                mateIn != null && mateIn in 2..3 -> pick(MATE_SOON_LINES, mateIn)
                mateIn != null && mateIn > 0 -> pick(MATE_LINES, mateIn)
                mateIn != null && mateIn < 0 -> pick(GETTING_MATED_LINES)

                // The opponent just slipped: label describes their move.
                lastLabel == "blunder" -> pick(OPPONENT_BLUNDER_LINES)
                lastLabel == "mistake" || lastLabel == "miss" -> pick(OPPONENT_MISTAKE_LINES)
                lastLabel == "brilliant" -> pick(OPPONENT_BRILLIANT_LINES)

                isSacrifice -> pick(SACRIFICE_LINES, pieceName.uppercase(Locale.ROOT), capturedName)
                isPromotion -> pick(PROMOTION_LINES)
                isHanging && isHighValue(captured) -> pick(BIG_CAPTURE_LINES, capturedName)
                isHanging -> pick(FREE_PIECE_LINES, capturedName)
                givesCheck -> pick(CHECK_LINES, pieceName)
                isCapture -> pick(CAPTURE_LINES, pieceName, capturedName)
                isCastle -> pick(CASTLE_LINES)

                moverEval != null && moverEval > 500 -> pick(WINNING_BIG_LINES)
                moverEval != null && moverEval < -500 -> pick(LOSING_BIG_LINES)
                moverEval != null && moverEval in -30..30 -> pick(EQUAL_LINES)

                lastLabel == "inaccuracy" -> pick(OPPONENT_INACCURACY_LINES)
                lastLabel == "best" || lastLabel == "great" || lastLabel == "best move" ->
                    pick(BEST_MOVE_LINES, pieceName)
                else -> pick(DEFAULT_LINES, pieceName)
            }
        } else {
            // The suggestion is the opponent's best reply; the label describes the user's move.
            when {
                mateIn != null && mateIn > 0 -> pick(THREAT_MATE_LINES)
                mateIn != null && mateIn < 0 -> pick(USER_MATES_LINES, abs(mateIn))

                lastLabel == "brilliant" -> pick(BRILLIANT_LINES)
                lastLabel == "great" -> pick(GREAT_LINES)
                lastLabel == "blunder" -> pick(BLUNDER_LINES)
                lastLabel == "mistake" || lastLabel == "miss" -> pick(MISTAKE_LINES)
                lastLabel == "inaccuracy" -> pick(INACCURACY_LINES)

                isCapture && isHighValue(captured) -> pick(THREAT_BIG_CAPTURE_LINES, capturedName)
                givesCheck -> pick(THREAT_CHECK_LINES, pieceName)
                isCapture -> pick(THREAT_CAPTURE_LINES, capturedName)

                lastLabel == "best" || lastLabel == "excellent" || lastLabel == "good" ||
                    lastLabel == "book" -> pick(SOLID_LINES)
                else -> pick(OPPONENT_REPLY_LINES, pieceName)
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Board helpers (squares indexed rank * 8 + file, a1 = 0)
    // -------------------------------------------------------------------------------------

    private const val EMPTY = '.'

    private fun parseBoard(fen: String): CharArray? {
        val rows = fen.trim().substringBefore(' ').split('/')
        if (rows.size != 8) return null
        val board = CharArray(64) { EMPTY }
        for ((rowIndex, row) in rows.withIndex()) {
            val rank = 7 - rowIndex
            var file = 0
            for (c in row) {
                if (c.isDigit()) {
                    file += c - '0'
                } else {
                    if (file > 7) return null
                    board[rank * 8 + file] = c
                    file++
                }
            }
            if (file != 8) return null
        }
        return board
    }

    private fun squareIndex(square: String): Int? {
        if (square.length < 2) return null
        val file = square[0] - 'a'
        val rank = square[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        return rank * 8 + file
    }

    private fun applyMove(board: CharArray, from: Int, to: Int, castle: Boolean, promotion: Boolean): CharArray {
        val b = board.copyOf()
        val piece = b[from]
        val white = piece.isUpperCase()
        // En passant: pawn moves diagonally onto an empty square.
        if (piece.lowercaseChar() == 'p' && from % 8 != to % 8 && b[to] == EMPTY) {
            b[(from / 8) * 8 + to % 8] = EMPTY
        }
        b[to] = if (promotion) (if (white) 'Q' else 'q') else piece
        b[from] = EMPTY
        if (castle) {
            val base = (from / 8) * 8
            if (to % 8 == 6) {
                b[base + 5] = b[base + 7]; b[base + 7] = EMPTY
            } else {
                b[base + 3] = b[base]; b[base] = EMPTY
            }
        }
        return b
    }

    private fun kingSquare(board: CharArray, white: Boolean): Int? {
        val king = if (white) 'K' else 'k'
        val index = board.indexOf(king)
        return if (index >= 0) index else null
    }

    /** True if any piece of colour [byWhite] attacks [square]. */
    private fun isAttacked(board: CharArray, square: Int, byWhite: Boolean): Boolean {
        val f = square % 8
        val r = square / 8
        fun at(file: Int, rank: Int): Char =
            if (file in 0..7 && rank in 0..7) board[rank * 8 + file] else EMPTY
        fun own(c: Char, kind: Char): Boolean =
            c != EMPTY && c.isUpperCase() == byWhite && c.lowercaseChar() == kind

        // Pawns attack diagonally forward, so look one rank "behind" the square.
        val pawnRank = if (byWhite) r - 1 else r + 1
        if (own(at(f - 1, pawnRank), 'p') || own(at(f + 1, pawnRank), 'p')) return true

        for ((df, dr) in KNIGHT_STEPS) if (own(at(f + df, r + dr), 'n')) return true
        for ((df, dr) in KING_STEPS) if (own(at(f + df, r + dr), 'k')) return true

        for ((df, dr) in KING_STEPS) {
            val diagonal = df != 0 && dr != 0
            var cf = f + df
            var cr = r + dr
            while (cf in 0..7 && cr in 0..7) {
                val c = board[cr * 8 + cf]
                if (c != EMPTY) {
                    if (own(c, 'q') || own(c, if (diagonal) 'b' else 'r')) return true
                    break
                }
                cf += df
                cr += dr
            }
        }
        return false
    }

    private val KNIGHT_STEPS = listOf(1 to 2, 2 to 1, -1 to 2, -2 to 1, 1 to -2, 2 to -1, -1 to -2, -2 to -1)
    private val KING_STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

    private fun pieceName(piece: Char?): String = when (piece?.lowercaseChar()) {
        'p' -> "pawn"
        'n' -> "knight"
        'b' -> "bishop"
        'r' -> "rook"
        'q' -> "queen"
        'k' -> "king"
        else -> "piece"
    }

    private fun isHighValue(piece: Char?): Boolean = piece?.lowercaseChar() in listOf('q', 'r')

    private fun pieceValue(piece: Char): Int = when (piece.lowercaseChar()) {
        'p' -> 1
        'n', 'b' -> 3
        'r' -> 5
        'q' -> 9
        else -> 0
    }

    private fun isCastling(from: String, to: String): Boolean =
        (from == "e1" && (to == "g1" || to == "c1")) || (from == "e8" && (to == "g8" || to == "c8"))

    // -------------------------------------------------------------------------------------
    // Lines: user's turn (the suggestion is the user's move)
    // -------------------------------------------------------------------------------------

    private val CHECKMATE_LINES = arrayOf(
        "CHECKMATE! GG EZ.",
        "That's mate. Pack it up.",
        "BOOM. Checkmate. Next game?",
        "And that's the game! Chef's kiss.",
        "Lights out. Checkmate.",
        "Mate on the board. Go take your victory lap.",
    )

    private val MATE_SOON_LINES = arrayOf(
        "Mate in %d! It's over.",
        "Forced mate in %d. No escape.",
        "%d moves to checkmate. Inevitable.",
        "Mate in %d. They can resign now.",
        "Mate in %d. Don't you dare miss it.",
    )

    private val MATE_LINES = arrayOf(
        "Mate in %d on the board!",
        "Forced mate in %d moves. Start the countdown.",
        "There's a mate in %d here. Hunt it down.",
        "Mate in %d. The king is already sweating.",
    )

    private val GETTING_MATED_LINES = arrayOf(
        "Uh oh... you're getting mated.",
        "Bad news: forced mate against you.",
        "They've got a forced mate. Pray for a blunder.",
        "RIP. Forced mate incoming.",
        "Longest defense it is. Make them prove it.",
    )

    private val OPPONENT_BLUNDER_LINES = arrayOf(
        "They blundered! Punish them.",
        "Free real estate. They messed up.",
        "They just threw. Cash in!",
        "Blunder! Make them pay.",
        "LMAO they blundered. Take advantage.",
    )

    private val OPPONENT_MISTAKE_LINES = arrayOf(
        "They slipped up. Capitalize.",
        "Mistake by them. Time to press.",
        "Opponent got sloppy. Make it hurt.",
        "That was not it, chief. Punish it.",
    )

    private val OPPONENT_INACCURACY_LINES = arrayOf(
        "Small slip by them. Squeeze it.",
        "They gave you a little gift. Unwrap it.",
        "Not their best. Keep the pressure on.",
        "Tiny inaccuracy. Tiny edge. Take it.",
    )

    private val OPPONENT_BRILLIANT_LINES = arrayOf(
        "Okay, that was brilliant. Respect. Now defend.",
        "They found a banger. Stay calm.",
        "Big brain move by them. Careful here.",
        "They're cooking. Find the best defense.",
    )

    private val SACRIFICE_LINES = arrayOf(
        "SACRIFICE THE %s!! Trust the process.",
        "Give up the %s for a %s?! Engine says yes.",
        "%s sac! It's not a blunder, it's a lifestyle.",
        "The %s goes in for the %s. Absolute cinema.",
        "Sac the %s. Tal would be proud.",
    )

    private val BIG_CAPTURE_LINES = arrayOf(
        "Grab that %s! Totally free.",
        "Take the %s, don't even think twice.",
        "That %s is hanging. Yoink!",
        "Free %s on the board!",
        "Their %s is just sitting there. Eat it.",
    )

    private val FREE_PIECE_LINES = arrayOf(
        "Free %s! Say thank you.",
        "That %s is undefended. Snack time.",
        "Nobody's guarding the %s. Take it.",
        "Hanging %s alert. Collect it.",
    )

    private val CHECK_LINES = arrayOf(
        "Check with the %s! Keep that king moving.",
        "%s check. Make them dance.",
        "Check! The %s says hello.",
        "Harass the king with the %s.",
    )

    private val CAPTURE_LINES = arrayOf(
        "%s takes %s. Clean trade.",
        "Capture with the %s. Simplify.",
        "%s snatches the %s.",
        "%s eats the %s. Nom.",
    )

    private val CASTLE_LINES = arrayOf(
        "Castle up! King safety first.",
        "Get that king to safety.",
        "Smart: tuck the king away.",
        "Castle and connect the rooks.",
        "King goes to the bunker. Good.",
    )

    private val PROMOTION_LINES = arrayOf(
        "PROMOTE! New queen on the board!",
        "Push that pawn! Promotion time!",
        "Queening! GG if this goes through.",
        "Pawn graduates to queen. Proud parent moment.",
    )

    private val WINNING_BIG_LINES = arrayOf(
        "You're crushing it. Stay focused.",
        "Massive advantage. Don't throw now.",
        "Way ahead. Keep the pressure on.",
        "Winning position. Finish them off.",
        "This is winning. No funny business.",
    )

    private val LOSING_BIG_LINES = arrayOf(
        "Tough spot. Look for tricks.",
        "Down bad but not dead yet.",
        "Find the best defense here.",
        "Rough position. Make them earn it.",
        "Swindle mode: activated.",
    )

    private val EQUAL_LINES = arrayOf(
        "Dead equal. Every move counts.",
        "Even position. Stay sharp.",
        "Balanced game. Precision matters.",
        "Equal. Patience wins here.",
        "Totally level. Out-think them.",
    )

    private val BEST_MOVE_LINES = arrayOf(
        "Best %s move on the board.",
        "This is the move. The %s goes.",
        "Top engine choice. Play the %s.",
        "Strongest continuation with the %s.",
        "The %s knows what to do. Trust it.",
    )

    private val DEFAULT_LINES = arrayOf(
        "Play the %s here.",
        "Engine likes this %s move.",
        "The %s wants some action.",
        "Move the %s. The engine insists.",
    )

    // -------------------------------------------------------------------------------------
    // Lines: opponent's turn (the label describes the user's last move)
    // -------------------------------------------------------------------------------------

    private val BRILLIANT_LINES = arrayOf(
        "BRILLIANT!! Galaxy brain move.",
        "That's a brilliant find! Take a bow.",
        "Absolutely insane move. Brilliant.",
        "Engine-level stuff. Brilliant!",
        "Magnus is taking notes. Brilliant!",
    )

    private val GREAT_LINES = arrayOf(
        "Great move! Only one that works.",
        "Found it! Great move.",
        "Clutch. That's a great move.",
        "Sharp! You saw that one.",
    )

    private val BLUNDER_LINES = arrayOf(
        "Oof. That one hurt. Big blunder.",
        "BLUNDER ALERT. Brace yourself.",
        "That move was a disaster. Damage control now.",
        "Oh no. That blunders everything.",
        "We don't talk about that move.",
    )

    private val MISTAKE_LINES = arrayOf(
        "Mistake. Not fatal, but ouch.",
        "That wasn't it. Stay calm and regroup.",
        "Slip-up. Watch their reply.",
        "Hmm, there was better. Shake it off.",
    )

    private val INACCURACY_LINES = arrayOf(
        "Slight inaccuracy. Watch out.",
        "Not the worst but not the best either.",
        "Small slip. You can do better.",
        "Meh. Playable, but meh.",
    )

    private val SOLID_LINES = arrayOf(
        "Solid move. Keeps the position healthy.",
        "Good choice. Nothing wrong with this.",
        "Clean and simple. Well played.",
        "Textbook stuff.",
        "Nice. Now see what they've got.",
    )

    private val THREAT_MATE_LINES = arrayOf(
        "DANGER: they have a forced mate.",
        "Uh oh. They've got mate on the board.",
        "Mate threat incoming. Pray they miss it.",
        "Your king is in serious trouble.",
    )

    private val USER_MATES_LINES = arrayOf(
        "You've got mate in %d whatever they do.",
        "Mate in %d for you. Just don't blink.",
        "Forced mate in %d. Sit back and enjoy.",
        "They're toast. Mate in %d.",
    )

    private val THREAT_BIG_CAPTURE_LINES = arrayOf(
        "Careful: your %s is up for grabs.",
        "Watch it. They can take your %s.",
        "Your %s is in danger. Hope they miss it.",
        "Uh oh, the %s is hanging.",
    )

    private val THREAT_CHECK_LINES = arrayOf(
        "Heads up: %s check is coming.",
        "Expect a %s check. Stay calm.",
        "They'll probably check you with the %s.",
        "Incoming check from their %s.",
    )

    private val THREAT_CAPTURE_LINES = arrayOf(
        "They'll likely take your %s.",
        "Expect them to grab the %s.",
        "Your %s is about to get traded.",
        "Watch your %s. It's under fire.",
    )

    private val OPPONENT_REPLY_LINES = arrayOf(
        "Their best reply is a %s move.",
        "Watch for their %s.",
        "Expect the %s to move next.",
        "If they're good, the %s goes here.",
    )
}
