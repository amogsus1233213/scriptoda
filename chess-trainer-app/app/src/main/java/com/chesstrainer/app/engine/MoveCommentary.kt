package com.chesstrainer.app.engine

import java.util.Locale
import kotlin.math.abs

/**
 * Short, sassy one-liners for the overlay's best move, in the voice of Mittens —
 * a condescending, dry-witted cat who's better than you at chess and knows it.
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
        "Checkmate. I expected nothing less.",
        "And that's mate. You're welcome.",
        "Checkmate, darling. Try not to gloat.",
        "That's the game. Even I'm purring.",
        "Mate. Honestly surprised you saw it.",
    )

    private val MATE_SOON_LINES = arrayOf(
        "Mate in %d. Don't get creative, just play it.",
        "Forced mate in %d. Even you can't mess this up.",
        "%d moves to checkmate. Stay focused for once.",
        "Mate in %d. I'd resign if I were them.",
        "Mate in %d. Try not to trip at the finish line.",
    )

    private val MATE_LINES = arrayOf(
        "Mate in %d. The king has nowhere to hide.",
        "Forced mate in %d. The board has spoken.",
        "There's a mate in %d here. You see it, right?",
        "Mate in %d. I believe in you. Mostly.",
    )

    private val GETTING_MATED_LINES = arrayOf(
        "Oh dear. There's a forced mate against you.",
        "You're getting mated. I'd look away but I can't.",
        "Forced mate incoming. Perhaps try not losing next time.",
        "They have mate. Play the longest defense and pray.",
        "This is... unfortunate. Forced mate on the board.",
    )

    private val OPPONENT_BLUNDER_LINES = arrayOf(
        "They blundered. How generous of them.",
        "Oh my. That was terrible. Punish it.",
        "They just handed you the game. Don't waste it.",
        "A blunder. Even I'm embarrassed for them.",
        "They blundered. Take the gift and say nothing.",
    )

    private val OPPONENT_MISTAKE_LINES = arrayOf(
        "They slipped up. How delightful.",
        "A mistake. Capitalize before they realize.",
        "Not their finest moment. Press the advantage.",
        "Sloppy by them. Make it count.",
    )

    private val OPPONENT_INACCURACY_LINES = arrayOf(
        "A small slip. Squeeze every drop out of it.",
        "Slightly inaccurate by them. You know what to do.",
        "Not optimal. Keep the pressure on.",
        "They blinked first. Good.",
    )

    private val OPPONENT_BRILLIANT_LINES = arrayOf(
        "Impressive by them. Now defend carefully.",
        "That was actually brilliant. Annoying, but brilliant.",
        "They found something sharp. Stay composed.",
        "A strong move. Don't panic, just think.",
    )

    private val SACRIFICE_LINES = arrayOf(
        "SACRIFICE THE %s!! Trust me on this.",
        "Give up the %s for the %s. Yes, really.",
        "The %s goes. It looks insane but it's winning.",
        "Sac the %s. Tal sends his regards.",
        "%s sacrifice. The kind of move I'd play.",
    )

    private val BIG_CAPTURE_LINES = arrayOf(
        "Their %s is just sitting there. Take it.",
        "Free %s. They forgot about it, apparently.",
        "The %s is unguarded. Help yourself.",
        "Hanging %s. How embarrassing for them.",
        "Take the %s. I won't judge the lack of creativity.",
    )

    private val FREE_PIECE_LINES = arrayOf(
        "Free %s. No strings attached.",
        "The %s is undefended. Easy pickings.",
        "Nobody's watching the %s. Collect it.",
        "A free %s. Even a kitten could find this.",
    )

    private val CHECK_LINES = arrayOf(
        "Check with the %s. Keep their king uncomfortable.",
        "%s check. Make the king dance.",
        "Put the king in check with the %s.",
        "The %s delivers check. Elegant.",
    )

    private val CAPTURE_LINES = arrayOf(
        "%s takes %s. Fair exchange.",
        "Capture the %s. Clean and simple.",
        "%s takes the %s. No drama, just chess.",
        "The %s claims the %s. Moving on.",
    )

    private val CASTLE_LINES = arrayOf(
        "Castle. A wise cat protects their king.",
        "Tuck the king away. Safety first, glory later.",
        "Castle and breathe. The king is safe.",
        "Get the king behind the pawns. Sensible.",
        "Castle. Even I do it sometimes.",
    )

    private val PROMOTION_LINES = arrayOf(
        "Promotion! A new queen enters the chat.",
        "That pawn has earned its crown.",
        "Promote. The pawn's glow-up is complete.",
        "Queening time. The board won't know what hit it.",
    )

    private val WINNING_BIG_LINES = arrayOf(
        "You're winning. Don't do anything stupid.",
        "Big advantage. Just play clean, boring moves.",
        "Way ahead. The hard part is not throwing it.",
        "Winning position. I'd say relax, but... don't.",
        "Ahead by a mile. Simplify and convert.",
    )

    private val LOSING_BIG_LINES = arrayOf(
        "Tough position. Look for complications.",
        "Down material but not dead. Find tricks.",
        "Difficult. Play the best defense you can.",
        "Rough spot. Make them work for every point.",
        "Behind. Set traps and hope they're careless.",
    )

    private val EQUAL_LINES = arrayOf(
        "Equal position. Precision matters here.",
        "Dead level. The better player wins from here.",
        "Balanced. Every move shifts the needle.",
        "Even game. Stay patient, stay sharp.",
        "Perfectly equal. Outplay them move by move.",
    )

    private val BEST_MOVE_LINES = arrayOf(
        "The %s. This is the move.",
        "Play the %s. The engine and I agree.",
        "Best continuation with the %s.",
        "The %s is the answer here.",
        "Move the %s. Trust the process.",
    )

    private val DEFAULT_LINES = arrayOf(
        "Play the %s here. You'll thank me later.",
        "The %s move. Nothing flashy, just correct.",
        "The %s is your best friend right now.",
        "Move the %s. I wouldn't steer you wrong.",
    )

    // -------------------------------------------------------------------------------------
    // Lines: opponent's turn (the label describes the user's last move)
    // -------------------------------------------------------------------------------------

    private val BRILLIANT_LINES = arrayOf(
        "Brilliant. I'm genuinely impressed. Don't let it go to your head.",
        "Oh? You found that? Brilliant move, I must admit.",
        "A brilliant move. Perhaps I underestimated you.",
        "Brilliant. That's the kind of move I'd play.",
        "Well, well. Brilliant find. Color me surprised.",
    )

    private val GREAT_LINES = arrayOf(
        "Great move. You have your moments.",
        "Sharp eye. That was the only move that works.",
        "Great move. I'll allow a small celebration.",
        "You found it. Nicely done, honestly.",
    )

    private val BLUNDER_LINES = arrayOf(
        "Oh no. That was a blunder. We'll get through this.",
        "A blunder. It happens. Even to... well, mostly to humans.",
        "That was painful to watch. Damage control time.",
        "You blundered. Take a breath and find the best defense.",
        "I'd look away but I'm literally your coach.",
    )

    private val MISTAKE_LINES = arrayOf(
        "A mistake. Not the end of the world, but close.",
        "There was something better. Shake it off.",
        "That wasn't optimal. Watch their reply carefully.",
        "A slip. The position is still playable, barely.",
    )

    private val INACCURACY_LINES = arrayOf(
        "Slight inaccuracy. You can do better. I know because I can.",
        "Not your best work. The position forgives you, this time.",
        "A small slip. Nothing fatal, but I noticed.",
        "Playable, but there was something cleaner.",
    )

    private val SOLID_LINES = arrayOf(
        "Solid. Nothing flashy, but correct. I approve.",
        "Good move. See? You can play chess when you try.",
        "Clean and simple. Well played.",
        "Textbook. I'd have played the same.",
        "Perfectly reasonable. Now let's see their response.",
    )

    private val THREAT_MATE_LINES = arrayOf(
        "They have a forced mate. This is not ideal.",
        "Mate threat on the board. Brace yourself.",
        "Their engine found mate. Play the longest defense.",
        "Forced mate incoming. I'm sorry. Truly.",
    )

    private val USER_MATES_LINES = arrayOf(
        "You have mate in %d. Just don't fumble it.",
        "Mate in %d for you. Play carefully and it's over.",
        "Forced mate in %d. I'd purr if I could.",
        "They're done. Mate in %d if you don't panic.",
    )

    private val THREAT_BIG_CAPTURE_LINES = arrayOf(
        "Careful. Your %s is in their crosshairs.",
        "Your %s is hanging. Hope they don't see it.",
        "Watch the %s. It's not safe where it is.",
        "They can take your %s. Concerning.",
    )

    private val THREAT_CHECK_LINES = arrayOf(
        "Expect a %s check. Stay composed.",
        "%s check is coming. Don't panic.",
        "Their %s is about to harass your king.",
        "Incoming %s check. Breathe.",
    )

    private val THREAT_CAPTURE_LINES = arrayOf(
        "They'll probably take the %s. Be ready.",
        "Your %s is likely getting captured.",
        "The %s is under threat. Watch it.",
        "Expect them to grab the %s next.",
    )

    private val OPPONENT_REPLY_LINES = arrayOf(
        "Their best reply involves the %s.",
        "Watch for a %s move from them.",
        "Expect the %s to come into play.",
        "They'll likely respond with the %s.",
    )
}
