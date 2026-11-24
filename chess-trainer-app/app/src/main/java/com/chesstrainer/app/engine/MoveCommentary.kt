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
 * When the engine's principal variation is known the coach also explains the plan behind the move:
 * the mating line, what a capture sequence wins, forks, pins, threats and positional ideas.
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
     * @param pv the engine's principal variation in UCI format, starting with the suggested move
     *   ("g1f3", "b8c6", ...). Used to explain the plan behind the move; ignored when it does
     *   not start with the suggested move.
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
        pv: List<String> = emptyList(),
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

        // The line the engine expects, replayed on the board. Without a usable PV it is just
        // the suggested move, which still allows the static explanations (forks, pins, ...).
        val suggested = fromSquare.take(2) + toSquare.take(2)
        val line = pv.map { it.trim().lowercase(Locale.ROOT) }
            .takeIf { it.firstOrNull()?.startsWith(suggested) == true }
            ?: listOf(suggested + if (isPromotion) promoteTo.toString() else "")
        val steps = simulate(board, line, moverIsWhite)
        val plan = if (mateIn != null || isPromotion) null else
            explainPlan(position, steps, loss, canBeRecaptured, isSacrifice, moverEval)
        val tacticalPlan = plan?.takeIf { it.tactical }?.text

        // 1. Things that must not be drowned out: forced mates and gifts from the opponent.
        val critical: String? = when {
            mateIn != null && mateIn > 0 && steps.isNotEmpty() -> explainMate(position, steps, mateIn)
            mateIn != null && mateIn == 1 -> pick(MATE_IN_ONE_LINES)
            mateIn != null && mateIn in 2..3 -> pick(MATE_SOON_LINES, mateIn)
            mateIn != null && mateIn > 0 -> pick(MATE_LINES, mateIn)
            mateIn != null && mateIn < 0 -> pick(GETTING_MATED_LINES)

            // The opponent slipped and there is a concrete way to punish it: say how.
            tacticalPlan != null && opponentClass == MoveClass.BLUNDER ->
                "${pick(OPPONENT_BLUNDER_PREFIXES)} $tacticalPlan"
            tacticalPlan != null &&
                (opponentClass == MoveClass.MISTAKE || opponentClass == MoveClass.MISS) ->
                "${pick(OPPONENT_MISTAKE_PREFIXES)} $tacticalPlan"

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
        if (timePressure) return@synchronized tacticalPlan ?: motif ?: pick(TIME_PRESSURE_LINES)

        // 4. Advice for this move, preceded by a reaction to the user's previous one. An
        //    explanation of the plan beats a generic line: tactics first, then the motif of the
        //    move itself, then positional ideas.
        val explained = tacticalPlan != null || (motif == null && plan != null)
        val advice = tacticalPlan ?: motif ?: plan?.text ?: when {
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
            // Why the move is good is what the user is here for.
            explained -> advice
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
    // Post-move reactions (the user just played a move and it has been classified)
    // -------------------------------------------------------------------------------------

    /** At least this far ahead / behind (centipawns, mover's POV) counts as better / worse. */
    private const val ADVANTAGE_CP = 150
    /** Within this margin (centipawns) the game counts as roughly equal. */
    private const val LEVEL_CP = 75
    /** Evaluations at or beyond this are forced mates; pawn counts are meaningless there. */
    private const val MATE_EVAL_CP = StockfishEngine.MATE_SCORE - 1_000
    /** Above this loss the exact pawn count is no longer quoted. */
    private const val MAX_QUOTED_LOSS_CP = 1_500

    /**
     * The coach's reaction to a move the user just played: what it was, why it was good or what
     * was better, and what it did to the game.
     *
     * @param classification how the move was classified
     * @param playedMove the move the user played, UCI format ("g1f3")
     * @param bestMove the engine's best move in that position, UCI format; may be empty
     * @param cpLoss centipawns lost compared with [bestMove]
     * @param evalBefore centipawns from the user's POV before the move
     * @param evalAfter centipawns from the user's POV after the move
     * @param fen position before the move. When given, moves are written in algebraic notation
     *   ("Nxd4"); otherwise as plain squares ("f3d4").
     * @param wasSuggested true when [playedMove] is the move the coach was showing as its top
     *   suggestion. Following the arrow is no surprise, so the coach only nods briefly.
     * @return one to three short sentences, never empty
     */
    fun postMoveCommentary(
        classification: MoveClass,
        playedMove: String,
        bestMove: String,
        cpLoss: Int,
        evalBefore: Int,
        evalAfter: Int,
        fen: String? = null,
        wasSuggested: Boolean = false,
    ): String {
        if (wasSuggested) return synchronized(this) {
            var index = random.nextInt(FOLLOWED_SUGGESTION_LINES.size)
            if (index == lastPicks[FOLLOWED_SUGGESTION_LINES]) {
                index = (index + 1 + random.nextInt(FOLLOWED_SUGGESTION_LINES.size - 1)) %
                    FOLLOWED_SUGGESTION_LINES.size
            }
            lastPicks[FOLLOWED_SUGGESTION_LINES] = index
            FOLLOWED_SUGGESTION_LINES[index]
        }
        val board = fen?.let { parseBoard(it) }
        val played = describeMove(playedMove, board)
        val sameMove = playedMove.trim().equals(bestMove.trim(), ignoreCase = true)
        // Only name the better move when there is one to name.
        val best = bestMove.trim().takeIf { it.length >= 4 && !sameMove }?.let { describeMove(it, board) }
        val hadMate = evalBefore >= MATE_EVAL_CP && evalAfter < MATE_EVAL_CP
        // Null when a pawn count would be noise: a thrown-away mate or an absurdly large loss.
        val pawns = cpLoss.takeIf { !hadMate && it in 1..MAX_QUOTED_LOSS_CP }
            ?.let { String.format(Locale.US, "%.1f", it / 100.0) }

        val reaction = when (classification) {
            MoveClass.BRILLIANT ->
                "Incredible! $played is a sacrifice that only works because it's the only " +
                    "winning move. The engine bows."
            MoveClass.GREAT -> "Perfect! $played was the only good move here and you found it."
            MoveClass.BEST -> "That's exactly what the engine recommends. $played — clean play."
            MoveClass.EXCELLENT -> "$played is almost as good as the best. Tiny difference, don't worry."
            MoveClass.GOOD -> "$played is a solid move. Nothing wrong with it."
            MoveClass.BOOK -> "$played — straight out of the opening book."

            MoveClass.INACCURACY -> when {
                best == null -> "$played was okay, but there was something stronger."
                pawns == null -> "$played was okay, but $best was stronger."
                else -> "$played was okay, but $best was stronger. About $pawns pawn difference."
            }
            MoveClass.MISTAKE -> {
                val cost = when {
                    hadMate -> "$played let a forced mate slip."
                    pawns == null -> "$played was a mistake."
                    else -> "$played cost you about $pawns pawns."
                }
                if (best == null) cost else "$cost $best was the right call."
            }
            MoveClass.BLUNDER -> {
                val needed = if (best == null) "" else " $best was what you needed."
                val gone = when {
                    hadMate -> " You had a forced mate."
                    pawns == null -> ""
                    else -> " That's $pawns pawns gone."
                }
                "$played threw away the game.$needed$gone"
            }
            MoveClass.MISS -> when {
                best == null -> "You missed a tactic there! That was worth grabbing."
                hadMate -> "You missed $best! That was a forced mate."
                else -> "You missed $best! That was a tactic worth grabbing."
            }
        }

        val context = positionalContext(classification, evalBefore, evalAfter)
        return if (context == null) reaction else "$reaction $context"
    }

    /** Brief, unsurprised nods for when the user plays the move the coach suggested. */
    private val FOLLOWED_SUGGESTION_LINES = arrayOf(
        "Good, that's the plan.",
        "Exactly.",
        "That's the one.",
        "Just as we discussed.",
        "Right on plan.",
        "Yes. Keep going.",
    )

    /** What the move did to the game as a whole, or null when there is nothing worth adding. */
    private fun positionalContext(classification: MoveClass, evalBefore: Int, evalAfter: Int): String? {
        val wasWinning = evalBefore >= ADVANTAGE_CP
        val wasLosing = evalBefore <= -ADVANTAGE_CP
        val wasLevel = abs(evalBefore) <= LEVEL_CP
        val isWinning = evalAfter >= ADVANTAGE_CP
        val strongMove = classification == MoveClass.BRILLIANT || classification == MoveClass.GREAT ||
            classification == MoveClass.BEST
        return when {
            wasWinning && isWinning -> "Still in control."
            wasWinning && evalAfter <= LEVEL_CP -> "You had the advantage and let it slip!"
            wasLosing && strongMove -> "Fighting spirit! You're not going down easy."
            wasLevel && isWinning -> "BOOM! You just took over this game."
            else -> null
        }
    }

    /**
     * Writes a UCI move in algebraic notation ("Nxd4", "exd5", "O-O", "e8=Q+") for [board], the
     * position before the move. Falls back to the UCI text when the board is unknown or does not
     * match the move. Disambiguation ignores pins, and mate is marked as a plain check.
     */
    private fun describeMove(uci: String, board: CharArray?): String {
        val move = uci.trim().lowercase(Locale.ROOT)
        if (board == null) return move
        val from = squareIndex(move) ?: return move
        val to = squareIndex(move.drop(2)) ?: return move
        val piece = board[from]
        if (piece == EMPTY) return move
        val white = piece.isUpperCase()
        val kind = piece.lowercaseChar()
        val toName = move.substring(2, 4)
        val castle = kind == 'k' && isCastling(move.substring(0, 2), toName)
        val promoteTo = move.getOrNull(4)?.takeIf { kind == 'p' && it in "qrbn" }
        val isCapture = board[to] != EMPTY || (kind == 'p' && from % 8 != to % 8)

        val text = StringBuilder()
        when {
            castle -> text.append(if (to % 8 == 6) "O-O" else "O-O-O")
            kind == 'p' -> {
                if (isCapture) text.append(move[0]).append('x')
                text.append(toName)
                if (promoteTo != null) text.append('=').append(promoteTo.uppercaseChar())
            }
            else -> {
                text.append(kind.uppercaseChar())
                val rivals = attackers(board, to, white).filter { it != from && board[it] == piece }
                if (rivals.isNotEmpty()) {
                    when {
                        rivals.none { it % 8 == from % 8 } -> text.append(move[0])
                        rivals.none { it / 8 == from / 8 } -> text.append(move[1])
                        else -> text.append(move, 0, 2)
                    }
                }
                if (isCapture) text.append('x')
                text.append(toName)
            }
        }
        val after = applyMove(board, from, to, castle, promoteTo)
        val check = kingSquare(after, !white)?.let { isAttacked(after, it, white) } ?: false
        if (check) text.append('+')
        return text.toString()
    }

    // -------------------------------------------------------------------------------------
    // Explaining the plan behind the suggested move (from the engine's principal variation)
    // -------------------------------------------------------------------------------------

    /** Material swing (in pawns) from which a line counts as winning something. */
    private const val CLEAR_GAIN = 2
    /** Behind by at least this much (centipawns) the suggested move is framed as defence. */
    private const val DEFENSIVE_EVAL_CP = 100
    /** With at least this many knights, bishops, rooks and queens left it is still the opening. */
    private const val OPENING_PIECE_COUNT = 12
    /** A king can only take what is undefended, so as an attacker it outranks everything. */
    private const val KING_ATTACKER_VALUE = 100

    /** One move of the principal variation, replayed on the board. */
    private class Step(
        val white: Boolean,
        val piece: Char,
        val captured: Char?,
        val from: Int,
        val to: Int,
        /** Lowercase piece a pawn promotes to, or null. */
        val promotion: Char?,
        /** Algebraic notation, e.g. "Nxd4+". */
        val san: String,
        val check: Boolean,
        val before: CharArray,
        val after: CharArray,
    )

    /** @param tactical true when the explanation is about winning something concrete. */
    private class Plan(val text: String, val tactical: Boolean)

    /** Replays up to eight moves of [pv]; stops at the first move that does not fit the board. */
    private fun simulate(board: CharArray, pv: List<String>, whiteToMove: Boolean): List<Step> {
        val steps = ArrayList<Step>()
        var current = board
        var white = whiteToMove
        for (uci in pv.take(8)) {
            if (uci.length < 4) break
            val from = squareIndex(uci) ?: break
            val to = squareIndex(uci.substring(2)) ?: break
            val piece = current[from]
            if (piece == EMPTY || piece.isUpperCase() != white) break
            val kind = piece.lowercaseChar()
            val enPassant = kind == 'p' && from % 8 != to % 8 && current[to] == EMPTY
            val captured = if (enPassant) (if (white) 'p' else 'P') else current[to].takeIf { it != EMPTY }
            if (captured != null && captured.isUpperCase() == white) break
            val castle = kind == 'k' && isCastling(uci.substring(0, 2), uci.substring(2, 4))
            val promotion = if (kind == 'p' && (to / 8 == 7 || to / 8 == 0)) {
                uci.getOrNull(4)?.takeIf { it in "qrbn" } ?: 'q'
            } else null
            val after = applyMove(current, from, to, castle, promotion)
            val san = describeMove(uci, current)
            steps += Step(white, piece, captured, from, to, promotion, san, san.endsWith('+'), current, after)
            current = after
            white = !white
        }
        return steps
    }

    /**
     * Material (in pawns) the side playing the first move comes out ahead over the first moves
     * of the line. Exchanges in progress are followed to their end; when the line stops right
     * after a move, the piece that moved is checked for being en prise.
     */
    private fun materialSwing(steps: List<Step>): Int {
        if (steps.isEmpty()) return 0
        val us = steps[0].white
        var horizon = minOf(steps.size, 3)
        while (horizon < steps.size && steps[horizon].captured != null) horizon++
        var net = 0
        for (i in 0 until horizon) {
            val step = steps[i]
            val gain = (step.captured?.let { pieceValue(it) } ?: 0) +
                (step.promotion?.let { pieceValue(it) - 1 } ?: 0)
            net += if (step.white == us) gain else -gain
        }
        val last = steps[horizon - 1]
        if (horizon == steps.size && last.piece.lowercaseChar() != 'k') {
            val lost = materialLostOn(last.after, last.to, last.white, pieceValue(last.after[last.to]))
            net += if (last.white == us) -lost else lost
        }
        return net
    }

    private fun fmt(position: String, lines: Array<String>, vararg args: Any): String =
        String.format(Locale.US, lines[pickIndex(position, lines)], *args)

    /** Spells out a forced mate: the move, the expected defence and the finishing blow. */
    private fun explainMate(position: String, steps: List<Step>, mateIn: Int): String {
        fun mating(step: Step) = step.san.trimEnd('+') + "#"
        val first = steps[0]
        val reply = steps.getOrNull(1)
        val next = steps.getOrNull(2)
        return when {
            mateIn == 1 -> fmt(position, MATE_NOW_PLAN_LINES, mating(first))
            mateIn == 2 && reply != null && next != null ->
                fmt(position, MATE_IN_TWO_PLAN_LINES, first.san, reply.san, mating(next))
            reply != null && next != null ->
                fmt(position, MATE_LINE_PLAN_LINES, first.san, reply.san, next.san, mateIn)
            else -> fmt(position, MATE_FORCED_PLAN_LINES, first.san, mateIn)
        }
    }

    /**
     * Why the first move of [steps] is good, in one short sentence, or null when there is
     * nothing interesting to say about it.
     *
     * @param loss material the moved piece stands to lose on its new square
     * @param moverEval centipawns from the POV of the side playing the move
     */
    private fun explainPlan(
        position: String,
        steps: List<Step>,
        loss: Int,
        canBeRecaptured: Boolean,
        isSacrifice: Boolean,
        moverEval: Int?,
    ): Plan? {
        val m1 = steps.firstOrNull() ?: return null
        val r1 = steps.getOrNull(1)
        val m2 = steps.getOrNull(2)
        val white = m1.white
        val kind = m1.piece.lowercaseChar()
        val name = pieceName(m1.piece)
        val board = m1.before
        val after = m1.after
        val safe = loss == 0
        val net = materialSwing(steps)
        val theyTake = r1 != null && r1.captured != null && r1.to == m1.to

        fun tactical(lines: Array<String>, vararg args: Any) = Plan(fmt(position, lines, *args), true)
        fun quiet(lines: Array<String>, vararg args: Any) = Plan(fmt(position, lines, *args), false)

        // --- Tactics the engine's line confirms ---------------------------------------------

        if (isSacrifice && theyTake && m2 != null) {
            if (m2.captured != null && net >= 1) return tactical(SAC_WINS_BACK_PLAN_LINES, m1.san, m2.san)
            if (m2.check) return tactical(SAC_ATTACK_PLAN_LINES, m1.san, m2.san)
        }

        val forked = forkTargets(after, m1.to, white)
        val pin = pinOrSkewer(after, m1.to, white)

        if (m1.captured != null && net >= 1 && !isSacrifice) {
            val victim = pieceName(m1.captured)
            return when {
                theyTake && m2?.captured != null && net >= CLEAR_GAIN ->
                    tactical(CAPTURE_THEN_MORE_PLAN_LINES, victim, m1.san, m2.san)
                !canBeRecaptured -> tactical(FREE_CAPTURE_PLAN_LINES, m1.san, victim)
                else -> tactical(AHEAD_CAPTURE_PLAN_LINES, m1.san, victim)
            }
        }

        if (m2?.captured != null && net >= CLEAR_GAIN) {
            val target = pieceName(m2.captured)
            return when {
                forked.size >= 2 && m2.to in forked ->
                    tactical(FORK_WINS_PLAN_LINES, m1.san, pieceName(after[forked[0]]), pieceName(after[forked[1]]))
                pin != null && !pin.skewer && m2.to == pin.front ->
                    tactical(PIN_WINS_PLAN_LINES, m1.san, pieceName(after[pin.front]), pieceName(after[pin.rear]))
                theyTake -> tactical(IF_THEY_TAKE_PLAN_LINES, m1.san, m2.san, target)
                m1.captured != null -> tactical(TRADE_THEN_WIN_PLAN_LINES, m1.san, m2.san, target)
                else -> tactical(SETS_UP_PLAN_LINES, m1.san, m2.san, target)
            }
        }

        // --- Tactics visible on the board (the piece must be safe where it lands) ------------

        if (safe && forked.size >= 2) {
            return tactical(FORK_PLAN_LINES, m1.san, pieceName(after[forked[0]]), pieceName(after[forked[1]]))
        }
        if (safe && pin != null) {
            val front = pieceName(after[pin.front])
            val rear = pieceName(after[pin.rear])
            return if (pin.skewer) tactical(SKEWER_PLAN_LINES, m1.san, front, rear)
            else tactical(PIN_PLAN_LINES, m1.san, front, rear)
        }

        // --- Quiet ideas ----------------------------------------------------------------------

        val inCheck = kingSquare(board, white)?.let { isAttacked(board, it, !white) } ?: false
        if (inCheck) {
            return when {
                kind == 'k' && m1.captured == null -> quiet(KING_STEPS_OUT_PLAN_LINES, m1.san)
                m1.captured != null -> quiet(TAKE_CHECKER_PLAN_LINES, m1.san)
                else -> quiet(BLOCK_CHECK_PLAN_LINES, m1.san)
            }
        }

        if (kind != 'k' && safe && materialLostOn(board, m1.from, white, pieceValue(m1.piece)) > 0 &&
            pieceValue(m1.piece) >= 3 && m1.captured == null
        ) {
            return quiet(RESCUE_PLAN_LINES, m1.san, name)
        }

        if (safe) {
            val moverValue = if (kind == 'k') KING_ATTACKER_VALUE else pieceValue(m1.piece)
            val already = attacksFrom(board, m1.from)
            val threatened = attacksFrom(after, m1.to)
                .filter { sq ->
                    val c = after[sq]
                    c != EMPTY && c.isUpperCase() != white && c.lowercaseChar() != 'k' && sq !in already &&
                        (pieceValue(c) > moverValue ||
                            (pieceValue(c) >= 3 && attackers(after, sq, !white).isEmpty()))
                }
                .maxByOrNull { pieceValue(after[it]) }
            if (threatened != null) {
                val target = pieceName(after[threatened])
                return if (attackers(after, threatened, !white).isEmpty()) quiet(HITS_LOOSE_PLAN_LINES, m1.san, target)
                else quiet(HITS_PIECE_PLAN_LINES, m1.san, target)
            }

            // A friendly piece that was about to be lost is now covered.
            val covered = (0 until 64).firstOrNull { sq ->
                val c = board[sq]
                sq != m1.from && c != EMPTY && c.isUpperCase() == white && pieceValue(c) >= 3 &&
                    after[sq] == c && materialLostOn(board, sq, white, pieceValue(c)) > 0 &&
                    materialLostOn(after, sq, white, pieceValue(c)) == 0
            }
            if (covered != null) return quiet(COVERS_PLAN_LINES, m1.san, pieceName(board[covered]))
        }

        val opening = board.count { it.lowercaseChar() in "nbrq" } >= OPENING_PIECE_COUNT
        val file = m1.to % 8
        val rank = m1.to / 8

        if (kind == 'p' && m1.captured == null) {
            val opened = openedLine(m1, m2)
            val central = file in 3..4 && rank in 3..4
            val relativeRank = if (white) rank else 7 - rank
            return when {
                opening && central && opened != null -> quiet(CENTER_OPENS_PLAN_LINES, m1.san, opened.second)
                opening && central -> quiet(CENTER_PLAN_LINES, m1.san)
                relativeRank >= 5 && safe && isPassedPawn(after, m1.to, white) -> quiet(PASSED_PAWN_PLAN_LINES, m1.san)
                opened != null -> quiet(OPENS_LINE_PLAN_LINES, m1.san, opened.first, opened.second)
                else -> defensivePlan(position, m1, moverEval)
            }
        }

        if (safe && m1.captured == null) {
            val homeRank = if (white) 0 else 7
            if (opening && (kind == 'n' || kind == 'b') && m1.from / 8 == homeRank) {
                val central = kind == 'n' && file in 2..5 && rank in 2..5
                return if (central) quiet(DEVELOP_CENTER_PLAN_LINES, m1.san) else quiet(DEVELOP_PLAN_LINES, m1.san, name)
            }
            if (kind == 'r' && m1.from % 8 != file) {
                val onFile = (0..7).map { after[it * 8 + file] }
                val ownPawn = if (white) 'P' else 'p'
                when {
                    onFile.none { it.lowercaseChar() == 'p' } -> return quiet(OPEN_FILE_PLAN_LINES, m1.san)
                    ownPawn !in onFile -> return quiet(HALF_OPEN_FILE_PLAN_LINES, m1.san)
                }
            }
            if (kind == 'r' && m1.from / 8 != rank && rank == (if (white) 6 else 1) &&
                (0..7).any { after[rank * 8 + it] == (if (white) 'p' else 'P') }
            ) {
                return quiet(SEVENTH_RANK_PLAN_LINES, m1.san)
            }
        }

        return defensivePlan(position, m1, moverEval)
    }

    /** Somewhat worse but not lost: the best move is about holding on. */
    private fun defensivePlan(position: String, m1: Step, moverEval: Int?): Plan? {
        if (moverEval == null || moverEval > -DEFENSIVE_EVAL_CP || moverEval < -DECISIVE_EVAL_CP) return null
        if (m1.captured != null || m1.check) return null
        return Plan(fmt(position, DEFENSE_PLAN_LINES, m1.san), false)
    }

    /** Squares the piece on [square] attacks (occupied or not). */
    private fun attacksFrom(board: CharArray, square: Int): List<Int> {
        val piece = board[square]
        if (piece == EMPTY) return emptyList()
        val f = square % 8
        val r = square / 8
        val found = ArrayList<Int>()
        fun add(file: Int, rank: Int) {
            if (file in 0..7 && rank in 0..7) found += rank * 8 + file
        }
        when (val kind = piece.lowercaseChar()) {
            'p' -> {
                val dr = if (piece.isUpperCase()) 1 else -1
                add(f - 1, r + dr); add(f + 1, r + dr)
            }
            'n' -> for ((df, dr) in KNIGHT_STEPS) add(f + df, r + dr)
            'k' -> for ((df, dr) in KING_STEPS) add(f + df, r + dr)
            else -> for ((df, dr) in KING_STEPS) {
                val diagonal = df != 0 && dr != 0
                if (kind == 'b' && !diagonal || kind == 'r' && diagonal) continue
                var cf = f + df
                var cr = r + dr
                while (cf in 0..7 && cr in 0..7) {
                    found += cr * 8 + cf
                    if (board[cr * 8 + cf] != EMPTY) break
                    cf += df
                    cr += dr
                }
            }
        }
        return found
    }

    /**
     * Enemy pieces the piece on [square] attacks and would win: the king, anything worth more
     * than itself and undefended pieces. Most valuable first (king first of all).
     */
    private fun forkTargets(board: CharArray, square: Int, white: Boolean): List<Int> {
        val piece = board[square]
        val moverValue = if (piece.lowercaseChar() == 'k') KING_ATTACKER_VALUE else pieceValue(piece)
        return attacksFrom(board, square)
            .filter { sq ->
                val c = board[sq]
                c != EMPTY && c.isUpperCase() != white && (
                    c.lowercaseChar() == 'k' || pieceValue(c) > moverValue ||
                        (pieceValue(c) >= 3 && attackers(board, sq, !white).isEmpty())
                    )
            }
            .sortedByDescending { if (board[it].lowercaseChar() == 'k') KING_ATTACKER_VALUE else pieceValue(board[it]) }
    }

    /** Two enemy pieces lined up in front of a bishop, rook or queen. */
    private class LineUp(val front: Int, val rear: Int, val skewer: Boolean)

    /**
     * A pin (the piece in front shields a more valuable one) or a skewer (the piece in front is
     * the more valuable one) created by the slider on [square], or null.
     */
    private fun pinOrSkewer(board: CharArray, square: Int, white: Boolean): LineUp? {
        val piece = board[square]
        val kind = piece.lowercaseChar()
        if (kind !in "brq") return null
        fun worth(c: Char) = if (c.lowercaseChar() == 'k') KING_ATTACKER_VALUE else pieceValue(c)
        for ((df, dr) in KING_STEPS) {
            val diagonal = df != 0 && dr != 0
            if (kind == 'b' && !diagonal || kind == 'r' && diagonal) continue
            var cf = square % 8 + df
            var cr = square / 8 + dr
            var front = -1
            while (cf in 0..7 && cr in 0..7) {
                val sq = cr * 8 + cf
                val c = board[sq]
                if (c != EMPTY) {
                    if (c.isUpperCase() == white) break
                    if (front < 0) {
                        front = sq
                    } else {
                        val a = worth(board[front])
                        val b = worth(c)
                        // Pin: a piece (not a pawn) stuck in front of something bigger than
                        // both it and the attacker. Skewer: the big piece is the one in front.
                        if (a in 3 until b && b > pieceValue(piece)) return LineUp(front, sq, skewer = false)
                        if (a > b && b >= 3 && a > pieceValue(piece)) return LineUp(front, sq, skewer = true)
                        break
                    }
                }
                cf += df
                cr += dr
            }
        }
        return null
    }

    /**
     * The line a pawn move opens for a friendly bishop, rook or queen that was stuck behind it,
     * as ("diagonal" | "file", piece name), or null. A piece that uses the line on the next move
     * of the variation is preferred.
     */
    private fun openedLine(m1: Step, m2: Step?): Pair<String, String>? {
        val after = m1.after
        val f = m1.from % 8
        val r = m1.from / 8
        var best: Pair<String, String>? = null
        for ((df, dr) in KING_STEPS) {
            if (dr == 0) continue
            val diagonal = df != 0
            // Look behind the pawn's old square for the piece it was blocking.
            var cf = f - df
            var cr = r - dr
            while (cf in 0..7 && cr in 0..7 && after[cr * 8 + cf] == EMPTY) { cf -= df; cr -= dr }
            if (cf !in 0..7 || cr !in 0..7) continue
            val behind = cr * 8 + cf
            val c = after[behind]
            val slides = c.lowercaseChar() == 'q' || c.lowercaseChar() == (if (diagonal) 'b' else 'r')
            if (c.isUpperCase() != m1.white || !slides) continue
            // The line only counts when it now reaches at least two squares past the pawn's.
            var reach = 0
            cf = f + df
            cr = r + dr
            while (cf in 0..7 && cr in 0..7) {
                val x = after[cr * 8 + cf]
                if (x != EMPTY && x.isUpperCase() == m1.white) break
                reach++
                if (x != EMPTY) break
                cf += df
                cr += dr
            }
            if (reach < 2) continue
            val found = (if (diagonal) "diagonal" else "file") to pieceName(c)
            if (m2 != null && m2.from == behind) return found
            if (best == null || c.lowercaseChar() != 'q') best = found
        }
        return best
    }

    /** No enemy pawn can stop or capture the pawn on [square] on its way to promotion. */
    private fun isPassedPawn(board: CharArray, square: Int, white: Boolean): Boolean {
        val enemyPawn = if (white) 'p' else 'P'
        val f = square % 8
        val ranks = if (white) (square / 8 + 1)..7 else 0 until square / 8
        return ranks.none { rank -> (f - 1..f + 1).any { it in 0..7 && board[rank * 8 + it] == enemyPawn } }
    }

    // Plan lines. Moves are inserted in algebraic notation ("Nf3"), pieces by name ("knight").

    private val MATE_NOW_PLAN_LINES = arrayOf(
        "%s. Checkmate.",
        "Mate in 1: %s.",
        "%s. Game over.",
        "Play %s. Mate.",
    )

    // Args are (move, their reply, mating move).
    private val MATE_IN_TWO_PLAN_LINES = arrayOf(
        "Mate in 2: %1\$s, then %3\$s.",
        "%1\$s, then %3\$s. Mate.",
        "Mate in 2. %1\$s, %2\$s, %3\$s.",
    )

    // Args are (move, their reply, follow-up, moves to mate).
    private val MATE_LINE_PLAN_LINES = arrayOf(
        "Mate in %4\$d: %1\$s, then %3\$s.",
        "Mate in %4\$d. Start %1\$s.",
        "%1\$s, then %3\$s. Mate in %4\$d.",
    )

    private val MATE_FORCED_PLAN_LINES = arrayOf(
        "Mate in %2\$d. Start %1\$s.",
        "%s. Forced mate in %d.",
    )

    private val OPPONENT_BLUNDER_PREFIXES = arrayOf("Blunder.", "They blundered.", "They hung it.")
    private val OPPONENT_MISTAKE_PREFIXES = arrayOf("Mistake.", "They slipped.", "Punish it.")

    // Args are (sacrifice, follow-up).
    private val SAC_WINS_BACK_PLAN_LINES = arrayOf(
        "%s. If taken, %s wins more.",
        "Sacrifice: %s. Then %s.",
    )

    private val SAC_ATTACK_PLAN_LINES = arrayOf(
        "%s. If taken, %s with check.",
        "Sacrifice: %s, then %s. Attack.",
    )

    // Args are (captured piece, move, follow-up).
    private val CAPTURE_THEN_MORE_PLAN_LINES = arrayOf(
        "Take the %1\$s: %2\$s. Then %3\$s.",
        "%2\$s. If they recapture, %3\$s.",
    )

    // Args are (move, captured piece).
    private val FREE_CAPTURE_PLAN_LINES = arrayOf(
        "%s. Free %s.",
        "Take the %2\$s. %1\$s.",
        "Hanging %2\$s. %1\$s.",
        "%s. The %s is undefended.",
    )

    private val AHEAD_CAPTURE_PLAN_LINES = arrayOf(
        "%s. Win the %s.",
        "Take the %2\$s: %1\$s.",
        "%s wins the %s.",
    )

    // Args are (move, first target, second target).
    private val FORK_WINS_PLAN_LINES = arrayOf(
        "%s. Fork the %s and %s.",
        "Fork: %s. %s or %s falls.",
        "%s forks %s and %s. One falls.",
    )

    private val FORK_PLAN_LINES = arrayOf(
        "%s. Fork the %s and %s.",
        "Fork: %s hits %s and %s.",
        "Double attack: %s. %s and %s.",
    )

    // Args are (move, piece in front, piece behind).
    private val PIN_WINS_PLAN_LINES = arrayOf(
        "%s. Pin. Win the %s.",
        "Pin the %2\$s to the %3\$s: %1\$s.",
        "%s. The pinned %s falls.",
    )

    private val PIN_PLAN_LINES = arrayOf(
        "%s. Pin the %s.",
        "Pin the %2\$s to the %3\$s: %1\$s.",
        "%s pins the %s. Pile on.",
    )

    private val SKEWER_PLAN_LINES = arrayOf(
        "%s. Skewer the %s and %s.",
        "Skewer: %1\$s. Win the %3\$s.",
    )

    // Args are (move, follow-up, target).
    private val IF_THEY_TAKE_PLAN_LINES = arrayOf(
        "%s. If taken, %s wins the %s.",
        "%s. They take, then %s.",
    )

    private val TRADE_THEN_WIN_PLAN_LINES = arrayOf(
        "Trade: %s. Then %s wins the %s.",
        "%s first, then %s.",
    )

    private val SETS_UP_PLAN_LINES = arrayOf(
        "%s. Threat: %s.",
        "%s, then %s wins the %s.",
        "%1\$s. The %3\$s falls.",
    )

    private val KING_STEPS_OUT_PLAN_LINES = arrayOf(
        "Check. King out: %s.",
        "Step out of check. %s.",
    )

    private val TAKE_CHECKER_PLAN_LINES = arrayOf(
        "Check. Take it: %s.",
        "%s. Remove the checker.",
    )

    private val BLOCK_CHECK_PLAN_LINES = arrayOf(
        "Check. Block: %s.",
        "Block with %s.",
    )

    // Args are (move, piece).
    private val RESCUE_PLAN_LINES = arrayOf(
        "%s. Save the %s.",
        "Move the %2\$s. %1\$s.",
        "Don't hang the %2\$s. %1\$s.",
    )

    private val HITS_PIECE_PLAN_LINES = arrayOf(
        "%s. Hit the %s.",
        "Attack the %2\$s: %1\$s.",
        "%s. Tempo on the %s.",
    )

    private val HITS_LOOSE_PLAN_LINES = arrayOf(
        "%s. The %s is loose.",
        "Loose %2\$s. %1\$s.",
        "Attack the loose %2\$s: %1\$s.",
    )

    private val COVERS_PLAN_LINES = arrayOf(
        "%s. Defend the %s.",
        "Cover the %2\$s. %1\$s.",
    )

    // Args are (move, piece).
    private val CENTER_OPENS_PLAN_LINES = arrayOf(
        "%s. Center, and frees the %s.",
        "%s. Open up the %s.",
    )

    private val CENTER_PLAN_LINES = arrayOf(
        "%s. Take the center.",
        "Center. %s.",
        "%s. Claim space.",
    )

    private val PASSED_PAWN_PLAN_LINES = arrayOf(
        "Push the pawn. %s.",
        "%s. Passed pawn. Push.",
        "Push. %s.",
    )

    // Args are (move, "diagonal" | "file", piece).
    private val OPENS_LINE_PLAN_LINES = arrayOf(
        "%s. Open the %s for the %s.",
        "%s opens the %s.",
    )

    private val DEVELOP_CENTER_PLAN_LINES = arrayOf(
        "%s. Develop.",
        "Develop the knight. %s.",
        "%s. Knight to the center.",
    )

    // Args are (move, piece).
    private val DEVELOP_PLAN_LINES = arrayOf(
        "%s. Develop the %s.",
        "Develop. %s.",
        "Get the %2\$s out: %1\$s.",
    )

    private val OPEN_FILE_PLAN_LINES = arrayOf(
        "%s. Rook to the open file.",
        "Open file. %s.",
        "Take the file: %s.",
    )

    private val HALF_OPEN_FILE_PLAN_LINES = arrayOf(
        "%s. Half-open file.",
        "%s. Pressure the pawn.",
    )

    private val SEVENTH_RANK_PLAN_LINES = arrayOf(
        "%s. Rook to the seventh.",
        "Seventh rank. %s.",
    )

    private val DEFENSE_PLAN_LINES = arrayOf(
        "%s. Hold.",
        "Defend. %s.",
        "%s. Stay solid.",
    )

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
        "Mate in 1.",
        "Mate in 1. Play it.",
        "Checkmate. Play it.",
        "Mate. One move.",
        "Deliver mate.",
        "Mate on the board.",
        "Finish it.",
        "End it.",
    )

    private val MATE_SOON_LINES = arrayOf(
        "Mate in %d.",
        "Forced mate in %d.",
        "Mate in %d. Checks only.",
        "Mate in %d. Stay precise.",
        "Mate in %d. Finish it.",
        "%d moves to mate.",
        "Mate in %d. Don't drift.",
        "Mate in %d. Calculate it.",
    )

    private val MATE_LINES = arrayOf(
        "Mate in %d.",
        "Forced mate in %d.",
        "Mate in %d. Follow the line.",
        "Mate in %d. Keep checking.",
        "Mate in %d. No rush.",
        "Mate in %d. Stay accurate.",
        "Mate in %d. King's trapped.",
    )

    private val GETTING_MATED_LINES = arrayOf(
        "Forced mate against you.",
        "You're getting mated.",
        "They have mate. Delay it.",
        "Mate is coming. Defend longest.",
        "Lost. Make them prove it.",
        "No defense. Set a trap.",
    )

    private val OPPONENT_BLUNDER_LINES = arrayOf(
        "Blunder. Punish it.",
        "They blundered.",
        "They hung it. Take.",
        "Blunder. Collect.",
        "Free material. Take it.",
        "They cracked. Punish.",
        "Blunder. Find the win.",
        "That loses. Punish it.",
        "Big error. Cash in.",
    )

    private val OPPONENT_MISTAKE_LINES = arrayOf(
        "Mistake. Press.",
        "They slipped. Punish it.",
        "Mistake. Take the initiative.",
        "Loose move. Press now.",
        "They erred. Make it count.",
        "Mistake. Look for tactics.",
        "Weak defense. Push.",
        "An opening. Use it.",
    )

    private val OPPONENT_INACCURACY_LINES = arrayOf(
        "Small slip. Press.",
        "Inaccuracy. Keep the pressure.",
        "Slightly off. Take the initiative.",
        "Soft move. Improve your position.",
        "They drifted. Squeeze.",
        "Inaccurate. Grab space.",
        "Small edge. Build on it.",
    )

    private val OPPONENT_BRILLIANT_LINES = arrayOf(
        "Strong move. Defend carefully.",
        "Good move by them. Stay calm.",
        "Sharp. Calculate before replying.",
        "Strong. Don't panic.",
        "They found it. Dig in.",
        "Dangerous. Check the threats.",
        "Respect that. Defend.",
    )

    private val SACRIFICE_LINES = arrayOf(
        "Sacrifice the %s.",
        "Sac the %s.",
        "Give up the %s.",
        "%s sacrifice. It works.",
        "Sac the %s. Attack.",
        "Offer the %s.",
        "Sac the %s. It's sound.",
        "Sac the %s for the attack.",
        "%s sacrifice. Calculate it.",
    )

    // Args are (mover, captured).
    private val SACRIFICE_CAPTURE_LINES = SACRIFICE_LINES + "Give the %s for the %s."

    private val BIG_CAPTURE_LINES = arrayOf(
        "Take the %s.",
        "Free %s. Take it.",
        "Hanging %s. Take.",
        "The %s is loose. Take.",
        "Win the %s.",
        "Undefended %s. Capture.",
        "Take the %s. It's free.",
        "Grab the %s.",
    )

    private val FREE_PIECE_LINES = arrayOf(
        "Free %s.",
        "Take the %s.",
        "Loose %s. Take it.",
        "Hanging %s.",
        "Win the %s.",
        "Undefended %s. Take.",
        "Collect the %s.",
        "The %s hangs. Take.",
    )

    // Args are (mover, captured).
    private val WINNING_CAPTURE_LINES = arrayOf(
        "%s takes %s. Wins material.",
        "Take the %2\$s. You come out ahead.",
        "Win the %2\$s.",
        "%s takes %s. Favorable trade.",
        "Capture the %2\$s. Good trade.",
    )

    private val CHECK_LINES = arrayOf(
        "Check with the %s.",
        "%s check.",
        "Check. Keep the initiative.",
        "%s check. Force the king.",
        "Check. Drive the king out.",
        "Give check with the %s.",
    )

    // Args are (mover, captured).
    private val CAPTURE_LINES = arrayOf(
        "%s takes %s.",
        "Take the %2\$s.",
        "Capture the %2\$s.",
        "Take with the %1\$s.",
        "Take it.",
    )

    private val CASTLE_LINES = arrayOf(
        "Castle now.",
        "Castle.",
        "Castle. King safety.",
        "Get the king safe. Castle.",
        "Castle. Connect the rooks.",
        "Castle before attacking.",
        "Tuck the king away.",
    )

    private val PROMOTION_LINES = arrayOf(
        "Promote.",
        "Queen the pawn.",
        "Push. New queen.",
        "Promote to a queen.",
        "Queen it.",
        "Promote now.",
    )

    private val UNDERPROMOTION_LINES = arrayOf(
        "Promote to a %s.",
        "Underpromote to a %s.",
        "Take a %s, not a queen.",
        "Make it a %s.",
    )

    private val WINNING_BIG_LINES = arrayOf(
        "Winning. Trade down.",
        "Trade down. You're winning.",
        "Winning. Simplify.",
        "Convert. No risks.",
        "Winning. Check their threats.",
        "Trade pieces, not pawns.",
        "Keep it simple.",
        "Winning. Don't rush.",
        "Ahead. Trade pieces.",
        "Winning. Stay careful.",
    )

    private val LOSING_BIG_LINES = arrayOf(
        "Losing. Complicate.",
        "Behind. Keep pieces on.",
        "Defend. Set traps.",
        "Tough. Find counterplay.",
        "Don't trade. Create threats.",
        "Behind. Stay active.",
        "Hold on. Look for tricks.",
        "Losing. Make it messy.",
        "Fight. Seek counterplay.",
    )

    private val EQUAL_LINES = arrayOf(
        "Equal. Stay solid.",
        "Level. Improve your worst piece.",
        "Equal. No rush.",
        "Balanced. Be patient.",
        "Even. Find a plan.",
        "Equal. Watch for tactics.",
        "Level game. Play accurately.",
        "Equal. Build slowly.",
    )

    private val BEST_MOVE_LINES = arrayOf(
        "Play the %s.",
        "Move the %s.",
        "%s move. Best.",
        "Best is the %s.",
        "%s move. Keep control.",
        "Go with the %s.",
        "The %s. Stay on plan.",
    )

    private val DEFAULT_LINES = arrayOf(
        "Play the %s.",
        "Move the %s.",
        "Improve the %s.",
        "%s move.",
        "Activate the %s.",
        "Quiet %s move.",
        "Reposition the %s.",
        "The %s. Solid.",
    )

    // -------------------------------------------------------------------------------------
    // Lines: opponent's turn (the label describes the user's last move)
    // -------------------------------------------------------------------------------------

    // Reactions to the user's own move, by classification (see feedbackLines).

    private val BRILLIANT_LINES = arrayOf(
        "Brilliant.",
        "Brilliant find.",
        "Brilliant. Well calculated.",
        "Outstanding move.",
        "Brilliant. Deep idea.",
        "Exceptional.",
        "Brilliant. Hard to see.",
        "Master-level move.",
        "Brilliant. Exactly right.",
    )

    private val GREAT_LINES = arrayOf(
        "Great move.",
        "Only move. Found it.",
        "Great find.",
        "Strong. That was critical.",
        "Great. Well spotted.",
        "Precise.",
        "Great move. Key moment.",
        "Sharp. Well played.",
        "Exactly. Nothing else worked.",
    )

    private val BEST_LINES = arrayOf(
        "Best move.",
        "Correct.",
        "Good.",
        "Best. Keep going.",
        "Right move.",
        "Accurate.",
        "That's it.",
        "Exactly.",
        "Best. Carry on.",
    )

    private val EXCELLENT_LINES = arrayOf(
        "Excellent.",
        "Very good.",
        "Strong move.",
        "Nearly best. Fine.",
        "Excellent. Carry on.",
        "Good choice.",
        "That works.",
        "Solid and strong.",
    )

    private val GOOD_LINES = arrayOf(
        "Good.",
        "Solid.",
        "Fine.",
        "That works.",
        "Good move.",
        "Reasonable.",
        "Sound.",
        "Playable. Keep building.",
    )

    private val BOOK_LINES = arrayOf(
        "Book.",
        "Theory.",
        "Book move.",
        "Main line.",
        "Still theory.",
        "Known position.",
    )

    private val INACCURACY_LINES = arrayOf(
        "Inaccuracy.",
        "Hmm. There was better.",
        "Slightly off.",
        "Imprecise.",
        "Inaccurate. Look deeper.",
        "Not the best.",
        "Small slip.",
        "Inaccuracy. Slow down.",
        "There was a stronger move.",
    )

    private val MISTAKE_LINES = arrayOf(
        "Mistake.",
        "That's a mistake.",
        "Mistake. Check their threats.",
        "Wrong move.",
        "That costs you.",
        "Mistake. Tighten up.",
        "Careless.",
        "Mistake. Calculate first.",
        "No. There was better.",
    )

    private val MISS_LINES = arrayOf(
        "Missed it.",
        "Missed a tactic.",
        "Miss. Check captures first.",
        "Missed chance.",
        "Miss. Look for forcing moves.",
        "There was a tactic.",
        "Missed. Checks, captures, threats.",
        "Opportunity gone.",
        "Miss. Slow down.",
    )

    private val BLUNDER_LINES = arrayOf(
        "Blunder.",
        "That's a blunder.",
        "Blunder. Defend now.",
        "Blunder. Check before moving.",
        "Serious error.",
        "Blunder. Damage control.",
        "That loses.",
        "Blunder. Reset and defend.",
        "Bad. Look at their reply.",
    )

    private val TIME_PRESSURE_LINES = arrayOf(
        "Low time. Move.",
        "Play it. Hit the clock.",
        "No time. Keep it simple.",
        "Move now.",
        "Clock. Safe moves only.",
        "Low time. Don't hang anything.",
        "Quick. Play the move.",
        "Time. Just play.",
    )

    private val THREAT_MATE_LINES = arrayOf(
        "They have mate.",
        "Forced mate against you.",
        "Mate threat. Defend.",
        "They mate. Delay it.",
    )

    private val USER_MATES_LINES = arrayOf(
        "Mate in %d.",
        "You have mate in %d.",
        "Mate in %d. Stay precise.",
        "Forced mate in %d.",
        "Mate in %d. Finish it.",
        "Mate in %d. Checks only.",
    )

    private val THREAT_BIG_CAPTURE_LINES = arrayOf(
        "Your %s hangs.",
        "Watch your %s.",
        "They take the %s.",
        "Your %s is loose.",
        "Your %s is attacked.",
    )

    private val THREAT_TRADE_LINES = arrayOf(
        "Expect a %s trade.",
        "They trade the %s. Recapture.",
        "They take the %s. Take back.",
        "%s trade coming. Recapture.",
    )

    private val THREAT_CHECK_LINES = arrayOf(
        "%s check coming.",
        "Expect a %s check.",
        "Watch the %s check.",
        "Their %s gives check.",
    )

    private val THREAT_CAPTURE_LINES = arrayOf(
        "They take the %s.",
        "Your %s is under attack.",
        "Watch the %s.",
        "Expect them to take the %s.",
    )

    private val OPPONENT_REPLY_LINES = arrayOf(
        "Expect a %s move.",
        "Watch their %s.",
        "Their %s moves next.",
        "Likely reply: the %s.",
    )
}
