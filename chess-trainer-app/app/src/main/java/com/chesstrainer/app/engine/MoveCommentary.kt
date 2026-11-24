package com.chesstrainer.app.engine

import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Short one-liners for the overlay's best move, in the voice of Mittens — a coach with
 * personality: fun, encouraging, honest about bad moves, and a little too sure of itself.
 *
 * What is said depends on the game: forced mates, the opponent's last move, the user's own last
 * move (its [MoveClass]), tactical motifs of the suggested move, the clock and the evaluation.
 *
 * Lines are picked at random from their category and never twice in a row from the same
 * category. The picks are remembered for the current position, so re-rendering it (e.g. after a
 * deeper analysis) never makes the commentary flicker between lines.
 */
object MoveCommentary {

    /** Feedback on the last move is only joined with advice when both fit the bubble. */
    private const val MAX_COMBINED_LENGTH = 100

    /** At least this far ahead / behind (centipawns) counts as winning / losing. */
    private const val DECISIVE_EVAL_CP = 300
    private const val EQUAL_EVAL_CP = 30

    /** Source of randomness; replaceable in tests. */
    internal var random: Random = Random.Default

    /** Position the picks in [positionPicks] belong to. */
    private var pickedPosition: String? = null
    /** Line index chosen per category for [pickedPosition]. Categories are compared by identity. */
    private val positionPicks = IdentityHashMap<Array<String>, Int>()
    /** Last line index used per category, to avoid saying the same thing twice in a row. */
    private val lastPicks = IdentityHashMap<Array<String>, Int>()

    private fun pickIndex(position: String, lines: Array<String>): Int {
        if (position != pickedPosition) {
            pickedPosition = position
            positionPicks.clear()
        }
        positionPicks[lines]?.let { return it }
        var index = random.nextInt(lines.size)
        if (lines.size > 1 && index == lastPicks[lines]) {
            index = (index + 1 + random.nextInt(lines.size - 1)) % lines.size
        }
        positionPicks[lines] = index
        lastPicks[lines] = index
        return index
    }

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
     * @param promotion piece the pawn promotes to ('q', 'r', 'b' or 'n'), i.e. the fifth
     *   character of a UCI move such as "e7e8n". Also read from [toSquare] when that carries the
     *   suffix ("e8n"). Defaults to a queen.
     * @param userLastMove classification of the user's own previous move, when known. On the
     *   user's turn the coach reacts to it before giving advice.
     * @param opponentLastMove classification of the move the opponent just played, when known.
     *   Takes precedence over [label] on the user's turn.
     * @param timePressure true when the user is short on time; the coach then keeps it brief.
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
        promotion: Char? = null,
        userLastMove: MoveClass? = null,
        opponentLastMove: MoveClass? = null,
        timePressure: Boolean = false,
    ): String = synchronized(this) {
        if (rank != 1) return@synchronized ""
        val board = parseBoard(fen) ?: return@synchronized ""
        val from = squareIndex(fromSquare) ?: return@synchronized ""
        val to = squareIndex(toSquare) ?: return@synchronized ""
        val piece = board[from]
        if (piece == EMPTY) return@synchronized ""

        val moverIsWhite = piece.isUpperCase()
        val kind = piece.lowercaseChar()
        // En passant: a pawn moving diagonally onto an empty square captures the enemy pawn.
        val isEnPassant = kind == 'p' && from % 8 != to % 8 && board[to] == EMPTY
        val captured = when {
            isEnPassant -> if (moverIsWhite) 'p' else 'P'
            else -> board[to].takeIf { it != EMPTY }
        }
        val isCapture = captured != null
        val isCastle = kind == 'k' && isCastling(fromSquare, toSquare)
        val isPromotion = kind == 'p' && (to / 8 == 7 || to / 8 == 0)
        val promoteTo = (promotion ?: toSquare.getOrNull(2))?.lowercaseChar()?.takeIf { it in "qrbn" } ?: 'q'

        // Board after the suggested move (castling rook and en passant victim included).
        val after = applyMove(board, from, to, isCastle, if (isPromotion) promoteTo else null)
        val givesCheck = kingSquare(after, !moverIsWhite)?.let { isAttacked(after, it, moverIsWhite) } ?: false

        val capturedValue = captured?.let { pieceValue(it) } ?: 0
        val canBeRecaptured = legalCapturers(after, to, !moverIsWhite).isNotEmpty()
        // A promoting pawn only ever risks the pawn, whatever it turns into.
        val loss = if (kind == 'k') 0 else materialLostOn(after, to, moverIsWhite, pieceValue(piece))
        val isSacrifice = loss - capturedValue >= 2
        val isHanging = isCapture && !canBeRecaptured && capturedValue >= 3
        val winsMaterial = isCapture && capturedValue - loss >= 2
        // The captured piece is simply lost, as opposed to traded off.
        val capturedIsLost = isCapture && (!canBeRecaptured || pieceValue(piece) < capturedValue)

        // Eval from the side to move's POV (the side playing the suggested move).
        val moverEval = evalCp?.let { if (moverIsWhite) it else -it }
        val lastLabel = label.trim().lowercase(Locale.ROOT)

        val pieceName = pieceName(piece)
        val capturedName = captured?.let { pieceName(it) } ?: "piece"
        val position = "$fen|$fromSquare|$toSquare"

        fun pick(lines: Array<String>, vararg args: Any): String {
            val line = lines[pickIndex(position, lines)]
            val text = if (args.isEmpty()) line else String.format(Locale.US, line, *args)
            return text.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        if (!isUsersTurn) {
            // The suggestion is the opponent's best reply; the label describes the user's move.
            val userClass = userLastMove ?: classOf(lastLabel)
            return@synchronized when {
                mateIn != null && mateIn > 0 -> pick(THREAT_MATE_LINES)
                mateIn != null && mateIn < 0 -> pick(USER_MATES_LINES, abs(mateIn))

                userClass != null && userClass.isNotable() -> pick(feedbackLines(userClass))

                capturedIsLost && isHighValue(captured) -> pick(THREAT_BIG_CAPTURE_LINES, capturedName)
                givesCheck -> pick(THREAT_CHECK_LINES, pieceName)
                isCapture && !capturedIsLost -> pick(THREAT_TRADE_LINES, capturedName)
                isCapture -> pick(THREAT_CAPTURE_LINES, capturedName)

                userClass != null -> pick(feedbackLines(userClass))
                else -> pick(OPPONENT_REPLY_LINES, pieceName)
            }
        }

        val opponentClass = opponentLastMove ?: classOf(lastLabel)

        // 1. Things that must not be drowned out: forced mates and gifts from the opponent.
        val critical: String? = when {
            mateIn != null && mateIn == 1 -> pick(MATE_IN_ONE_LINES)
            mateIn != null && mateIn in 2..3 -> pick(MATE_SOON_LINES, mateIn)
            mateIn != null && mateIn > 0 -> pick(MATE_LINES, mateIn)
            mateIn != null && mateIn < 0 -> pick(GETTING_MATED_LINES)

            opponentClass == MoveClass.BLUNDER -> pick(OPPONENT_BLUNDER_LINES)
            opponentClass == MoveClass.MISTAKE || opponentClass == MoveClass.MISS ->
                pick(OPPONENT_MISTAKE_LINES)
            opponentClass == MoveClass.BRILLIANT -> pick(OPPONENT_BRILLIANT_LINES)
            else -> null
        }
        if (critical != null) return@synchronized critical

        // 2. Tactical motifs of the suggested move itself.
        val motif: String? = when {
            isSacrifice && isCapture ->
                pick(SACRIFICE_CAPTURE_LINES, pieceName.uppercase(Locale.ROOT), capturedName)
            isSacrifice -> pick(SACRIFICE_LINES, pieceName.uppercase(Locale.ROOT))
            isPromotion && promoteTo != 'q' -> pick(UNDERPROMOTION_LINES, pieceName(promoteTo))
            isPromotion -> pick(PROMOTION_LINES)
            isHanging && isHighValue(captured) -> pick(BIG_CAPTURE_LINES, capturedName)
            isHanging -> pick(FREE_PIECE_LINES, capturedName)
            winsMaterial -> pick(WINNING_CAPTURE_LINES, pieceName, capturedName)
            else -> null
        }

        // 3. No time to read: the motif if there is one, otherwise a nudge to just move.
        if (timePressure) return@synchronized motif ?: pick(TIME_PRESSURE_LINES)

        // 4. Advice for this move, preceded by a reaction to the user's previous one.
        val advice = motif ?: when {
            givesCheck -> pick(CHECK_LINES, pieceName)
            isCapture -> pick(CAPTURE_LINES, pieceName, capturedName)
            isCastle -> pick(CASTLE_LINES)

            opponentClass == MoveClass.INACCURACY -> pick(OPPONENT_INACCURACY_LINES)

            moverEval != null && moverEval > DECISIVE_EVAL_CP -> pick(WINNING_BIG_LINES)
            moverEval != null && moverEval < -DECISIVE_EVAL_CP -> pick(LOSING_BIG_LINES)
            moverEval != null && moverEval in -EQUAL_EVAL_CP..EQUAL_EVAL_CP -> pick(EQUAL_LINES)

            opponentClass == MoveClass.BEST || opponentClass == MoveClass.GREAT ||
                lastLabel == "best move" -> pick(BEST_MOVE_LINES, pieceName)
            else -> pick(DEFAULT_LINES, pieceName)
        }
        val feedback = userLastMove?.let { pick(feedbackLines(it)) }
        when {
            feedback == null -> advice
            feedback.length + 1 + advice.length <= MAX_COMBINED_LENGTH -> "$feedback $advice"
            // Both do not fit: a remarkable last move is worth more than routine advice.
            userLastMove.isNotable() || motif == null -> feedback
            else -> advice
        }
    }

    private fun classOf(label: String): MoveClass? =
        MoveClass.values().firstOrNull { it.displayName.lowercase(Locale.ROOT) == label }

    /** Classes the coach always reacts to, as opposed to routine good moves. */
    private fun MoveClass.isNotable(): Boolean = when (this) {
        MoveClass.BRILLIANT, MoveClass.GREAT, MoveClass.INACCURACY, MoveClass.MISTAKE,
        MoveClass.MISS, MoveClass.BLUNDER -> true
        MoveClass.BEST, MoveClass.EXCELLENT, MoveClass.GOOD, MoveClass.BOOK -> false
    }

    /** Reactions to a move the user played, by its classification. */
    private fun feedbackLines(moveClass: MoveClass): Array<String> = when (moveClass) {
        MoveClass.BRILLIANT -> BRILLIANT_LINES
        MoveClass.GREAT -> GREAT_LINES
        MoveClass.BEST -> BEST_LINES
        MoveClass.EXCELLENT -> EXCELLENT_LINES
        MoveClass.GOOD -> GOOD_LINES
        MoveClass.BOOK -> BOOK_LINES
        MoveClass.INACCURACY -> INACCURACY_LINES
        MoveClass.MISTAKE -> MISTAKE_LINES
        MoveClass.MISS -> MISS_LINES
        MoveClass.BLUNDER -> BLUNDER_LINES
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

    /** @param promoteTo lowercase piece the moving pawn promotes to, or null when it does not. */
    private fun applyMove(board: CharArray, from: Int, to: Int, castle: Boolean, promoteTo: Char?): CharArray {
        val b = board.copyOf()
        val piece = b[from]
        val white = piece.isUpperCase()
        // En passant: pawn moves diagonally onto an empty square.
        if (piece.lowercaseChar() == 'p' && from % 8 != to % 8 && b[to] == EMPTY) {
            b[(from / 8) * 8 + to % 8] = EMPTY
        }
        b[to] = promoteTo?.let { if (white) it.uppercaseChar() else it } ?: piece
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
    private fun isAttacked(board: CharArray, square: Int, byWhite: Boolean): Boolean =
        attackers(board, square, byWhite).isNotEmpty()

    /** Squares of the pieces of colour [byWhite] that attack [square]. */
    private fun attackers(board: CharArray, square: Int, byWhite: Boolean): List<Int> {
        val f = square % 8
        val r = square / 8
        val found = ArrayList<Int>()
        fun check(file: Int, rank: Int, kind: Char) {
            if (file !in 0..7 || rank !in 0..7) return
            val c = board[rank * 8 + file]
            if (c != EMPTY && c.isUpperCase() == byWhite && c.lowercaseChar() == kind) found += rank * 8 + file
        }

        // Pawns attack diagonally forward, so look one rank "behind" the square.
        val pawnRank = if (byWhite) r - 1 else r + 1
        check(f - 1, pawnRank, 'p')
        check(f + 1, pawnRank, 'p')

        for ((df, dr) in KNIGHT_STEPS) check(f + df, r + dr, 'n')
        for ((df, dr) in KING_STEPS) check(f + df, r + dr, 'k')

        for ((df, dr) in KING_STEPS) {
            val diagonal = df != 0 && dr != 0
            var cf = f + df
            var cr = r + dr
            while (cf in 0..7 && cr in 0..7) {
                if (board[cr * 8 + cf] != EMPTY) {
                    check(cf, cr, 'q')
                    check(cf, cr, if (diagonal) 'b' else 'r')
                    break
                }
                cf += df
                cr += dr
            }
        }
        return found
    }

    private fun capture(board: CharArray, from: Int, to: Int): CharArray =
        board.copyOf().also { it[to] = it[from]; it[from] = EMPTY }

    /**
     * Attackers of [square] that may actually capture there: excludes pinned pieces and a king
     * that would step into check.
     */
    private fun legalCapturers(board: CharArray, square: Int, byWhite: Boolean): List<Int> =
        attackers(board, square, byWhite).filter { from ->
            val next = capture(board, from, square)
            kingSquare(next, byWhite)?.let { !isAttacked(next, it, !byWhite) } ?: true
        }

    /**
     * Material the side [ownerIsWhite] loses if the opponent takes the piece on [square] with its
     * cheapest capturer and the owner recaptures when possible. 0 when taking does not pay.
     */
    private fun materialLostOn(board: CharArray, square: Int, ownerIsWhite: Boolean, invested: Int): Int {
        val capturer = legalCapturers(board, square, !ownerIsWhite).minByOrNull { pieceValue(board[it]) }
            ?: return 0
        val next = capture(board, capturer, square)
        val regained = if (legalCapturers(next, square, ownerIsWhite).isEmpty()) 0 else pieceValue(board[capturer])
        return (invested - regained).coerceAtLeast(0)
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

    private val MATE_IN_ONE_LINES = arrayOf(
        "Mate in 1. Play it before I lose patience.",
        "Checkmate is one move away. Go on, then.",
        "Mate in 1, darling. Don't overthink it.",
        "One move ends the game. This one.",
        "There's a mate in 1. Please tell me you see it.",
        "MATE IN 1! Don't miss it!",
        "Finish them! One move.",
        "Checkmate is on the board. Deliver it.",
    )

    private val MATE_SOON_LINES = arrayOf(
        "Mate in %d. Don't get creative, just play it.",
        "Forced mate in %d. Even you can't mess this up.",
        "%d moves to checkmate. Stay focused for once.",
        "Mate in %d. I'd resign if I were them.",
        "Mate in %d. Try not to trip at the finish line.",
        "MATE IN %d! Don't miss it!",
        "Finish them! Mate in %d.",
        "Mate in %d. Checks first, questions later.",
    )

    private val MATE_LINES = arrayOf(
        "Mate in %d. The king has nowhere to hide.",
        "Forced mate in %d. The board has spoken.",
        "There's a mate in %d here. You see it, right?",
        "Mate in %d. I believe in you. Mostly.",
        "MATE IN %d! Don't miss it!",
        "Finish them! It's mate in %d.",
        "Mate in %d. Follow the line and it's over.",
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
        "They gave you a gift. Take it!",
        "FREE STUFF! Grab it!",
        "Christmas came early. Unwrap it.",
        "They cracked. Now make it hurt.",
        "That was a howler. Punish it, no mercy.",
    )

    private val OPPONENT_MISTAKE_LINES = arrayOf(
        "They slipped up. How delightful.",
        "A mistake. Capitalize before they realize.",
        "Not their finest moment. Press the advantage.",
        "Sloppy by them. Make it count.",
        "They left the door open. Walk in.",
        "That was a slip. Your move to cash in.",
        "They gave you an opening. Use it.",
        "Mistake by them. This is your moment.",
    )

    private val OPPONENT_INACCURACY_LINES = arrayOf(
        "A small slip. Squeeze every drop out of it.",
        "Slightly inaccurate by them. You know what to do.",
        "Not optimal. Keep the pressure on.",
        "They blinked first. Good.",
        "They got a little loose. Tighten the screws.",
        "Slightly off by them. Nudge the edge your way.",
        "A soft move from them. Take the initiative.",
    )

    private val OPPONENT_BRILLIANT_LINES = arrayOf(
        "Impressive by them. Now defend carefully.",
        "That was actually brilliant. Annoying, but brilliant.",
        "They found something sharp. Stay composed.",
        "A strong move. Don't panic, just think.",
        "Okay, that was a great move by them. Respect.",
        "They found a stunner. Keep calm and defend.",
        "Credit where it's due. Now dig in.",
    )

    private val SACRIFICE_LINES = arrayOf(
        "SACRIFICE THE %s!! Trust me on this.",
        "The %s goes. It looks insane but it's winning.",
        "Sac the %s. Tal sends his regards.",
        "%s sacrifice. The kind of move I'd play.",
        "Throw the %s into the fire. It works!",
        "Give them the %s. It's a trap, and a good one.",
        "The %s is a gift they'll regret accepting.",
        "Material is temporary. Sac the %s!",
        "Offer the %s. Fortune favors the brave.",
        "Let the %s go. The attack is worth more.",
    )

    // Args are (mover, captured).
    private val SACRIFICE_CAPTURE_LINES = SACRIFICE_LINES + "Give up the %s for the %s. Yes, really."

    private val BIG_CAPTURE_LINES = arrayOf(
        "Their %s is just sitting there. Take it.",
        "Free %s. They forgot about it, apparently.",
        "The %s is unguarded. Help yourself.",
        "Hanging %s. How embarrassing for them.",
        "Take the %s. I won't judge the lack of creativity.",
        "FREE STUFF! Their %s is yours.",
        "The %s is a gift. Unwrap it.",
    )

    private val FREE_PIECE_LINES = arrayOf(
        "Free %s. No strings attached.",
        "The %s is undefended. Easy pickings.",
        "Nobody's watching the %s. Collect it.",
        "A free %s. Even a kitten could find this.",
        "Loose pieces drop off. Take the %s.",
        "That %s is on the house.",
        "They left the %s hanging. Say thank you.",
        "Free %s! Don't ask questions.",
    )

    // Args are (mover, captured).
    private val WINNING_CAPTURE_LINES = arrayOf(
        "%s takes %s. That trade only favors you.",
        "Take the %2\$s with the %1\$s. They can recapture, and you still come out ahead.",
        "The %s for their %s? Yes, please.",
        "Grab the %2\$s. Whatever they take back is worth less.",
    )

    private val CHECK_LINES = arrayOf(
        "Check with the %s. Keep their king uncomfortable.",
        "%s check. Make the king dance.",
        "Put the king in check with the %s.",
        "The %s delivers check. Elegant.",
        "Check! Send in the %s.",
        "%s check. Always look at checks first.",
    )

    private val CAPTURE_LINES = arrayOf(
        "%s takes %s. Nothing more to it.",
        // Args are (mover, captured); %2$s refers to the captured piece.
        "Capture the %2\$s. Clean and simple.",
        "%s takes the %s. No drama, just chess.",
        "The %s claims the %s. Moving on.",
    )

    private val CASTLE_LINES = arrayOf(
        "Castle. A wise cat protects their king.",
        "Tuck the king away. Safety first, glory later.",
        "Castle and breathe. The king is safe.",
        "Get the king behind the pawns. Sensible.",
        "Castle. Even I do it sometimes.",
        "Castle now. The attack can wait a move.",
        "King to safety, rook to the party.",
    )

    private val PROMOTION_LINES = arrayOf(
        "Promotion! A new queen enters the chat.",
        "That pawn has earned its crown.",
        "Promote. The pawn's glow-up is complete.",
        "Queening time. The board won't know what hit it.",
        "Push it home. Every pawn dreams of this.",
        "New queen incoming. Enjoy it.",
    )

    private val UNDERPROMOTION_LINES = arrayOf(
        "Promote to a %s, not a queen. Yes, I'm sure.",
        "Underpromote to a %s. The queen can wait.",
        "Make it a %s. Greed loses here, precision wins.",
        "A %s, not a queen. Trust the cat.",
    )

    private val WINNING_BIG_LINES = arrayOf(
        "You're winning. Don't do anything stupid.",
        "Big advantage. Just play clean, boring moves.",
        "Way ahead. The hard part is not throwing it.",
        "Winning position. I'd say relax, but... don't.",
        "Ahead by a mile. Simplify and convert.",
        "You're crushing it. Don't get lazy now.",
        "This is won if you don't blunder.",
        "Winning. Trade pieces, not pawns.",
        "Completely winning. Check for their tricks, then cash in.",
        "The hard part is done. Keep it simple.",
    )

    private val LOSING_BIG_LINES = arrayOf(
        "Tough position. Look for complications.",
        "Down material but not dead. Find tricks.",
        "Difficult. Play the best defense you can.",
        "Rough spot. Make them work for every point.",
        "Behind. Set traps and hope they're careless.",
        "Tough spot. Set some traps.",
        "Play for complications, make them work for it.",
        "Nobody ever won by resigning. Keep fighting.",
        "Losing, yes. Lost, not yet. Stay tricky.",
        "Keep pieces on and keep it messy.",
    )

    private val EQUAL_LINES = arrayOf(
        "Equal position. Precision matters here.",
        "Dead level. The better player wins from here.",
        "Balanced. Every move shifts the needle.",
        "Even game. Stay patient, stay sharp.",
        "Perfectly equal. Outplay them move by move.",
        "Dead equal. This is a battle of wits now.",
        "Even game. One mistake decides it.",
        "All square. Improve your worst piece.",
        "Nothing in it. Patience wins these.",
    )

    private val BEST_MOVE_LINES = arrayOf(
        "The %s. This is the move.",
        "Play the %s. The engine and I agree.",
        "Best continuation with the %s.",
        "The %s is the answer here.",
        "Move the %s. Trust the process.",
        "Go with the %s. No second-guessing.",
        "The %s move keeps everything under control.",
    )

    private val DEFAULT_LINES = arrayOf(
        "Play the %s here. You'll thank me later.",
        "The %s move. Nothing flashy, just correct.",
        "The %s is your best friend right now.",
        "Move the %s. I wouldn't steer you wrong.",
        "Bring the %s into the game.",
        "The %s wants to move. Let it.",
        "Improve the %s. Small moves add up.",
        "Quiet %s move. Good positions are built like this.",
    )

    // -------------------------------------------------------------------------------------
    // Lines: opponent's turn (the label describes the user's last move)
    // -------------------------------------------------------------------------------------

    // Reactions to the user's own move, by classification (see feedbackLines).

    private val BRILLIANT_LINES = arrayOf(
        "Brilliant. I'm genuinely impressed. Don't let it go to your head.",
        "Oh? You found that? Brilliant move, I must admit.",
        "A brilliant move. Perhaps I underestimated you.",
        "Brilliant. That's the kind of move I'd play.",
        "Well, well. Brilliant find. Color me surprised.",
        "THAT'S the move! Nobody sees that coming.",
        "You just made the engine sweat. Beautiful.",
        "Chess poetry. Frame this game.",
        "Sacrifice with STYLE. That's how legends play.",
        "I didn't even see that. And I'm the coach.",
        "Brilliant!! Tal would be proud.",
        "Did you just do that? You did. Wow.",
    )

    private val GREAT_LINES = arrayOf(
        "Great move. You have your moments.",
        "Sharp move. That swung things your way.",
        "Great move. I'll allow a small celebration.",
        "You found it. Nicely done, honestly.",
        "Only move, and you found it. That's pattern recognition.",
        "Under pressure, you chose perfectly.",
        "That's the kind of move that wins tournaments.",
        "Textbook. Absolutely textbook.",
        "Great find! That was the critical moment.",
        "Everything else was worse. You nailed it.",
    )

    private val BEST_LINES = arrayOf(
        "Engine approved. Keep it up.",
        "Top choice. You're in the zone.",
        "That's exactly what Stockfish wanted.",
        "Clean. No debate.",
        "Best move. I'd have played the same.",
        "Spot on. Nothing to add.",
        "Best move on the board. Again, please.",
        "You and the engine agree. Good company.",
    )

    private val EXCELLENT_LINES = arrayOf(
        "Practically perfect. I'm not complaining.",
        "Tiny difference from the best. Still strong.",
        "That works just fine. Trust it.",
        "Excellent. The engine barely blinked.",
        "Near perfect. I'll take that every time.",
        "Excellent choice. Clean and simple.",
        "As good as best, give or take a hair.",
    )

    private val GOOD_LINES = arrayOf(
        "Solid move. Not flashy, but it works.",
        "Good enough to hold the position.",
        "Reasonable. The position is still yours.",
        "Good move. See? You can play chess when you try.",
        "Decent. No harm done.",
        "Sensible chess. Keep building.",
        "Fine move. Good chess is mostly this.",
    )

    private val BOOK_LINES = arrayOf(
        "Theory. You know your openings.",
        "By the book. Literally.",
        "Opening prep paying off.",
        "Still in theory. Somebody did their homework.",
        "Book move. The masters agree with you.",
        "Straight out of the opening manual.",
    )

    private val INACCURACY_LINES = arrayOf(
        "Slight inaccuracy. You can do better. I know because I can.",
        "Not your best work. The position forgives you, this time.",
        "A small slip. Nothing fatal, but I noticed.",
        "Playable, but there was something cleaner.",
        "Hmm, there was something better there.",
        "Small slip. The position still holds, but tighten up.",
        "Not the worst, but let's find the sharper moves.",
        "You're leaving something on the table.",
        "Close, but not quite. Slow down a touch.",
    )

    private val MISTAKE_LINES = arrayOf(
        "A mistake. Not the end of the world, but close.",
        "There was something better. Shake it off.",
        "That wasn't optimal. Tighten up from here.",
        "A slip. The position is still playable, barely.",
        "That's gonna cost you. Let's recover.",
        "Ouch. The advantage just slipped.",
        "Your opponent is smiling right now.",
        "We need to talk about that one...",
        "Mistake. Check what they threaten before you move.",
    )

    private val MISS_LINES = arrayOf(
        "There was a tactic there! Did you see it?",
        "You had a shot and missed it. It happens.",
        "The position was screaming for a tactic.",
        "Missed chance. Checks, captures, threats. Every move.",
        "That was your moment. Stay alert for the next one.",
        "A win walked right past you. Keep looking.",
        "You let them off the hook. Reel them back in.",
    )

    private val BLUNDER_LINES = arrayOf(
        "Oh no. That was a blunder. We'll get through this.",
        "A blunder. It happens. Even to... well, mostly to humans.",
        "That was painful to watch. Damage control time.",
        "You blundered. Take a breath and find the best defense.",
        "I'd look away but I'm literally your coach.",
        "Oh no. OH NO.",
        "That... changed everything. Stay focused.",
        "The engine just had a heart attack.",
        "We don't talk about that move.",
        "Deep breath. We're still playing.",
        "Blunder. Even world champions hang pieces. Reset.",
    )

    private val TIME_PRESSURE_LINES = arrayOf(
        "Clock's ticking! Trust your instincts.",
        "Speed mode. Go with your gut!",
        "No time to calculate, just play!",
        "Seconds left. Safe moves, fast hands.",
        "Tick tock. Play the arrow and hit the clock.",
        "Low on time! Don't hang anything, just move.",
        "Blitz brain on. Move now, think later.",
        "Time trouble! Keep it simple.",
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
        "MATE IN %d! Don't miss it!",
        "Finish them! Mate in %d.",
    )

    private val THREAT_BIG_CAPTURE_LINES = arrayOf(
        "Careful. Your %s is in their crosshairs.",
        "Your %s is hanging. Hope they don't see it.",
        "Watch the %s. It's not safe where it is.",
        "They can take your %s. Concerning.",
    )

    private val THREAT_TRADE_LINES = arrayOf(
        "Expect them to take the %s. You can take back.",
        "They'll likely trade on the %s. Recapture and carry on.",
        "A trade on the %s is coming. Nothing to fear.",
        "They can swap off the %s. Be ready to recapture.",
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
