package com.chesstrainer.app.engine

import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Short coach commentary for the overlay's best move: one or two brief sentences, calm and
 * direct, naming the pieces, the tactic and the threat. No jokes, no long explanations.
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
        // Looking ahead is reserved for forced mates (explainMate below). Any other position
        // gets a remark about the suggested move alone: only its first step is passed on, so
        // nothing is said about replies or follow-up moves.
        val plan = if (mateIn != null || isPromotion) null else
            explainPlan(position, steps.take(1), loss, canBeRecaptured, isSacrifice, moverEval)
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
                pick(SACRIFICE_CAPTURE_LINES, pieceName, capturedName)
            isSacrifice -> pick(SACRIFICE_LINES, pieceName)
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
    /** From this loss (centipawns) on, the coach always says how much the move cost. */
    private const val SIGNIFICANT_LOSS_CP = 50
    /** Above this loss the exact pawn count is no longer quoted, only "more than". */
    private const val MAX_QUOTED_LOSS_CP = 1_500

    /**
     * The coach's reaction to a move the user just played. It always opens with the recognition
     * (the classification), then says what happened in real terms and what was better:
     *
     *  - "Blunder. That knight move lost about 3 pawns. The rook move was better."
     *  - "Mistake. That knight move let a forced mate slip. The queen check was stronger."
     *  - "You missed it. The knight capture was a forced mate."
     *  - "Brilliant. That bishop sacrifice was the only winning move."
     *  - "Best move. Clean."
     *
     * A loss of [SIGNIFICANT_LOSS_CP] or more is always quoted in pawns (unless a forced mate
     * is what was lost, which says more), and the better move is always named by its piece
     * whenever [fen] allows it. A closing remark tells what the move did to the game as a
     * whole (see [positionalContext]).
     *
     * @param classification how the move was classified
     * @param playedMove the move the user played, UCI format ("g1f3")
     * @param bestMove the engine's best move in that position, UCI format; may be empty
     * @param cpLoss centipawns lost compared with [bestMove]
     * @param evalBefore centipawns from the user's POV before the move
     * @param evalAfter centipawns from the user's POV after the move
     * @param fen position before the move. When given, moves are described by the piece that
     *   moves ("that knight move", "the queen capture"); otherwise just as "that move".
     *   Squares and algebraic notation never appear in the text.
     * @param wasSuggested true when [playedMove] is the move the coach was showing as its top
     *   suggestion. The move is still recognised for what it was; only the closing remark of
     *   a routine good move is swapped for a brief nod.
     * @return one to four short sentences, never empty
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
        val board = fen?.let { parseBoard(it) }
        // Notation is internal only: it is turned into piece names before it reaches the text.
        val playedSan = describeMove(playedMove, board)
        val sameMove = playedMove.trim().equals(bestMove.trim(), ignoreCase = true)
        // Only name the better move when there is one to name.
        val bestSan = bestMove.trim().takeIf { it.length >= 4 && !sameMove }?.let { describeMove(it, board) }
        // Plain English only: "that knight move", "the queen capture". Never notation.
        val played = thatMove(playedSan)
        // Without a board the better move cannot be described by its piece.
        val best = bestSan?.takeIf { sanToPiece(it) != "piece" }?.let { betterMove(it, playedSan) }

        // The user had a forced mate and no longer has one.
        val hadMate = evalBefore >= MATE_EVAL_CP && evalAfter < MATE_EVAL_CP
        // The move allows a forced mate that was not there before.
        val walksIntoMate = evalAfter <= -MATE_EVAL_CP && evalBefore > -MATE_EVAL_CP
        // What the move cost, in pawns. Null when it cost nothing worth mentioning.
        val lost = cpLoss.takeIf { it >= SIGNIFICANT_LOSS_CP }?.let { pawnsLost(it) }
        // What happened, in real terms. A mate says more than any pawn count.
        val whatHappened = when {
            hadMate -> "$played let a forced mate slip."
            walksIntoMate -> "$played walks into a forced mate."
            lost != null -> "$played lost $lost."
            else -> null
        }
        val context = positionalContext(classification, evalBefore, evalAfter)

        val sentences = ArrayList<String>(4)
        when (classification) {
            MoveClass.BRILLIANT -> {
                // Brilliant means a sacrifice that was the only good move.
                val piece = sanToPiece(playedSan).takeIf { it != "piece" && it != "castling" }
                val sacrifice = if (piece == null) "sacrifice" else "$piece sacrifice"
                val wins = evalAfter >= ADVANTAGE_CP
                sentences += "Brilliant."
                sentences += when (pickFresh(BRILLIANT_SHAPES)) {
                    "only" -> if (wins) "That $sacrifice was the only winning move."
                    else "That $sacrifice was the only move that holds."
                    else -> if (wins) "Hard to find. Only the $sacrifice wins."
                    else "Hard to find. Only the $sacrifice holds."
                }
                context?.let { sentences += it }
            }
            MoveClass.GREAT -> {
                sentences += "Great move."
                sentences += String.format(Locale.US, pickFresh(GREAT_DETAILS), played)
                context?.let { sentences += it }
            }
            MoveClass.BEST -> {
                sentences += pickFresh(BEST_LINES)
                sentences += context ?: pickFresh(if (wasSuggested) FOLLOWED_SUGGESTION_TAILS else BEST_TAILS)
            }
            MoveClass.EXCELLENT -> {
                sentences += "Excellent."
                sentences += context ?: pickFresh(if (wasSuggested) FOLLOWED_SUGGESTION_TAILS else EXCELLENT_TAILS)
            }
            MoveClass.GOOD -> {
                sentences += "Good."
                sentences += context ?: pickFresh(if (wasSuggested) FOLLOWED_SUGGESTION_TAILS else GOOD_TAILS)
            }
            MoveClass.BOOK -> {
                sentences += "Book move."
                sentences += pickFresh(BOOK_TAILS)
            }

            MoveClass.INACCURACY -> {
                sentences += "Inaccuracy."
                whatHappened?.let { sentences += it }
                sentences += if (best == null) "There was a stronger move." else "$best was stronger."
                context?.let { sentences += it }
            }
            MoveClass.MISTAKE -> {
                sentences += "Mistake."
                sentences += whatHappened ?: "$played made things worse."
                sentences += if (best == null) "There was a better move." else "$best was better."
                context?.let { sentences += it }
            }
            MoveClass.BLUNDER -> {
                sentences += "Blunder."
                sentences += whatHappened ?: "$played gave away too much."
                sentences += if (best == null) "There was a much better move." else "$best was better."
                context?.let { sentences += it }
            }
            MoveClass.MISS -> {
                // A miss is a chance the user had and did not take: say what the chance was.
                val chance = if (hadMate) "a forced mate" else "the tactic"
                if (best == null) {
                    sentences += if (hadMate) "You missed a forced mate." else "You missed a tactic."
                } else {
                    sentences += "You missed it."
                    sentences += "$best was $chance."
                }
                if (!hadMate && lost != null) sentences += "That cost $lost."
                context?.let { sentences += it }
            }
        }
        return capitalizeSentences(sentences.joinToString(" "))
    }

    /**
     * A centipawn loss in words: "about half a pawn", "about a pawn", "about 2.5 pawns",
     * "about 3 pawns". Rounded to the nearest half pawn, which is all the precision an engine
     * evaluation has.
     */
    private fun pawnsLost(cpLoss: Int): String {
        if (cpLoss > MAX_QUOTED_LOSS_CP) return "more than ${MAX_QUOTED_LOSS_CP / 100} pawns"
        val halves = ((cpLoss + 25) / 50).coerceAtLeast(1)
        return when {
            halves == 1 -> "about half a pawn"
            halves == 2 -> "about a pawn"
            halves % 2 == 0 -> "about ${halves / 2} pawns"
            else -> "about ${halves / 2}.5 pawns"
        }
    }

    /** A random line of [lines], never the one picked from them last time. */
    private fun pickFresh(lines: Array<String>): String = synchronized(this) {
        var index = random.nextInt(lines.size)
        if (lines.size > 1 && index == lastPicks[lines]) {
            index = (index + 1 + random.nextInt(lines.size - 1)) % lines.size
        }
        lastPicks[lines] = index
        lines[index]
    }

    /** The two ways a brilliant move is described; see [postMoveCommentary]. */
    private val BRILLIANT_SHAPES = arrayOf("only", "hard")

    // Arg is the move the user played, as a subject ("that knight move").
    private val GREAT_DETAILS = arrayOf(
        "%s was the critical one.",
        "%s was exactly what the position needed.",
        "%s was the strongest on the board.",
    )

    private val BEST_TAILS = arrayOf("Clean.", "Nothing better.", "Spot on.", "Keep going.")
    private val EXCELLENT_TAILS = arrayOf("Nearly the best move.", "Almost perfect.", "Right idea.")
    private val GOOD_TAILS = arrayOf("Solid.", "Nothing wrong with it.", "Keeps things steady.")
    private val BOOK_TAILS = arrayOf("Still theory.", "Known opening.", "Keep developing.")

    /** Brief, unsurprised nods for when the user plays the move the coach suggested. */
    private val FOLLOWED_SUGGESTION_TAILS = arrayOf(
        "That's the plan.",
        "Keep going.",
        "Stay on plan.",
        "Carry on.",
    )

    /**
     * What the move did to the game as a whole, judged from the evaluation before and after it,
     * or null when there is nothing worth adding.
     */
    private fun positionalContext(classification: MoveClass, evalBefore: Int, evalAfter: Int): String? {
        val wasWinning = evalBefore >= ADVANTAGE_CP
        val wasLosing = evalBefore <= -ADVANTAGE_CP
        val wasLevel = abs(evalBefore) <= LEVEL_CP
        val isWinning = evalAfter >= ADVANTAGE_CP
        val isLosing = evalAfter <= -ADVANTAGE_CP
        val isLevel = abs(evalAfter) <= LEVEL_CP
        val strongMove = classification == MoveClass.BRILLIANT || classification == MoveClass.GREAT ||
            classification == MoveClass.BEST
        val badMove = classification == MoveClass.INACCURACY || classification == MoveClass.MISTAKE ||
            classification == MoveClass.MISS || classification == MoveClass.BLUNDER
        return when {
            // A forced mate against the user has already been said; nothing softens it.
            evalAfter <= -MATE_EVAL_CP -> null

            wasWinning && isLosing -> "That turned a winning game around."
            wasWinning && evalAfter <= LEVEL_CP -> "The advantage is gone."
            wasWinning && isWinning && badMove -> "You're still ahead."
            wasWinning && isWinning -> "Still in control."

            wasLevel && isLosing -> "Now you're in trouble."
            wasLevel && isWinning -> "You're on top now."

            wasLosing && isWinning -> "You've turned it around."
            wasLosing && isLevel -> "You're back in the game."
            wasLosing && strongMove -> "Good defense. Keep fighting."
            wasLosing && badMove && evalAfter < evalBefore -> "That makes a hard position harder."
            else -> null
        }
    }

    // -------------------------------------------------------------------------------------
    // Plain-English move descriptions. The coach talks about pieces and actions ("the knight
    // takes", "push the pawn"), never about squares or algebraic notation.
    // -------------------------------------------------------------------------------------

    /**
     * The piece a move in algebraic notation is made with: "Qh7+" -> "queen", "Nxd4" -> "knight",
     * "e4" / "exd5" / "e8=Q" -> "pawn", "O-O" / "O-O-O" -> "castling". Anything that is not
     * algebraic notation (e.g. a raw UCI move such as "g1f3") gives "piece".
     */
    internal fun sanToPiece(san: String): String {
        val move = san.trim()
        return when {
            move.startsWith("O-O") || move.startsWith("0-0") -> "castling"
            UCI_MOVE.matches(move) -> "piece"
            move.startsWith('K') -> "king"
            move.startsWith('Q') -> "queen"
            move.startsWith('R') -> "rook"
            move.startsWith('B') -> "bishop"
            move.startsWith('N') -> "knight"
            move.firstOrNull() in 'a'..'h' -> "pawn"
            else -> "piece"
        }
    }

    private val UCI_MOVE = Regex("[a-h][1-8][a-h][1-8][qrbnQRBN]?")

    /** The mover as it reads inside a sentence: "the knight", "castling". */
    private fun sanToMover(san: String): String =
        sanToPiece(san).let { if (it == "castling") it else "the $it" }

    /** What kind of move [san] is, as a noun: "capture", "check", "push" (pawns) or "move". */
    private fun moveKind(san: String): String = when {
        'x' in san -> "capture"
        san.endsWith('+') -> "check"
        sanToPiece(san) == "pawn" -> "push"
        else -> "move"
    }

    /** A move the user played, as a subject: "that knight move", "that pawn push", "castling". */
    private fun thatMove(san: String): String {
        val piece = sanToPiece(san)
        return when {
            piece == "castling" -> "castling"
            piece == "piece" -> "that move"
            '=' in san -> "that promotion"
            else -> "that $piece ${moveKind(san)}"
        }
    }

    /**
     * A move named by its piece, as a subject: "the knight move", "the rook capture", "the queen
     * check", "the pawn push", "castling", "promoting the pawn".
     */
    private fun moveNoun(san: String): String {
        val piece = sanToPiece(san)
        return when {
            piece == "castling" -> "castling"
            piece == "piece" -> "another move"
            '=' in san -> "promoting the pawn"
            else -> "the $piece ${moveKind(san)}"
        }
    }

    /**
     * The better move [bestSan], named by its piece and told apart from the move that was
     * played: when both read the same ("the knight move"), it becomes "a different knight move".
     */
    private fun betterMove(bestSan: String, playedSan: String): String {
        val noun = moveNoun(bestSan)
        if (noun != moveNoun(playedSan)) return noun
        val piece = sanToPiece(bestSan)
        return when {
            piece == "castling" -> "castling the other way"
            piece == "piece" -> "another move"
            '=' in bestSan -> "a different promotion"
            else -> "a different $piece ${moveKind(bestSan)}"
        }
    }

    /** Upper-cases the first letter of the text and of every sentence in it. */
    private fun capitalizeSentences(text: String): String =
        SENTENCE_START.replace(text) { it.value.uppercase(Locale.ROOT) }

    private val SENTENCE_START = Regex("(?:^|(?<=[.!?] ))[a-z]")

    /**
     * Writes a UCI move in algebraic notation ("Nxd4", "exd5", "O-O", "e8=Q+") for [board], the
     * position before the move. Internal use only: the result is turned into a piece name with
     * [sanToPiece] before it reaches any commentary text. Falls back to the UCI text when the board is unknown or does not
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
        /** Algebraic notation, e.g. "Nxd4+". Never shown; see [mover]. */
        val san: String,
        val check: Boolean,
        val before: CharArray,
        val after: CharArray,
    ) {
        /** The piece that moves, as it reads inside a sentence: "the knight", "castling". */
        val mover: String get() = sanToMover(san)
    }

    /**
     * The piece playing the follow-up [next], told apart from the one that played [first]:
     * "the same knight", "the other knight", "another pawn", or simply "the queen".
     */
    private fun followUp(first: Step, next: Step): String {
        val piece = sanToPiece(next.san)
        return when {
            piece != sanToPiece(first.san) || piece == "castling" -> next.mover
            next.from == first.to -> "the same $piece"
            piece == "pawn" -> "another pawn"
            else -> "the other $piece"
        }
    }

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
        capitalizeSentences(String.format(Locale.US, lines[pickIndex(position, lines)], *args))

    /** Spells out a forced mate by the pieces that deliver it. No squares, no notation. */
    private fun explainMate(position: String, steps: List<Step>, mateIn: Int): String {
        val first = steps[0]
        // The mating side's next move; null when the same piece simply keeps going.
        val next = steps.getOrNull(2)?.takeIf { it.from != first.to }
        return when {
            mateIn == 1 -> fmt(position, MATE_NOW_PLAN_LINES, first.mover)
            mateIn == 2 && next != null ->
                fmt(position, MATE_IN_TWO_PLAN_LINES, first.mover, followUp(first, next))
            next != null -> fmt(position, MATE_LINE_PLAN_LINES, first.mover, followUp(first, next), mateIn)
            else -> fmt(position, MATE_FORCED_PLAN_LINES, first.mover, mateIn)
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
        // Pieces, not notation: "the knight" and, for the follow-up, e.g. "the queen".
        val mover = m1.mover
        val then = m2?.let { followUp(m1, it) } ?: "the next move"

        fun tactical(lines: Array<String>, vararg args: Any) = Plan(fmt(position, lines, *args), true)
        fun quiet(lines: Array<String>, vararg args: Any) = Plan(fmt(position, lines, *args), false)

        // --- Tactics the engine's line confirms ---------------------------------------------

        if (isSacrifice && theyTake && m2 != null) {
            if (m2.captured != null && net >= 1) return tactical(SAC_WINS_BACK_PLAN_LINES, mover, then)
            if (m2.check) return tactical(SAC_ATTACK_PLAN_LINES, mover, then)
        }

        val forked = forkTargets(after, m1.to, white)
        val pin = pinOrSkewer(after, m1.to, white)

        if (m1.captured != null && net >= 1 && !isSacrifice) {
            val victim = pieceName(m1.captured)
            return when {
                theyTake && m2?.captured != null && net >= CLEAR_GAIN ->
                    tactical(CAPTURE_THEN_MORE_PLAN_LINES, victim, mover, then)
                !canBeRecaptured -> tactical(FREE_CAPTURE_PLAN_LINES, mover, victim)
                else -> tactical(AHEAD_CAPTURE_PLAN_LINES, mover, victim)
            }
        }

        if (m2?.captured != null && net >= CLEAR_GAIN) {
            val target = pieceName(m2.captured)
            return when {
                forked.size >= 2 && m2.to in forked ->
                    tactical(FORK_WINS_PLAN_LINES, mover, pieceName(after[forked[0]]), pieceName(after[forked[1]]))
                pin != null && !pin.skewer && m2.to == pin.front ->
                    tactical(PIN_WINS_PLAN_LINES, mover, pieceName(after[pin.front]), pieceName(after[pin.rear]))
                theyTake -> tactical(IF_THEY_TAKE_PLAN_LINES, mover, then, target)
                m1.captured != null -> tactical(TRADE_THEN_WIN_PLAN_LINES, mover, then, target)
                else -> tactical(SETS_UP_PLAN_LINES, mover, then, target)
            }
        }

        // --- Tactics visible on the board (the piece must be safe where it lands) ------------

        if (safe && forked.size >= 2) {
            return tactical(FORK_PLAN_LINES, mover, pieceName(after[forked[0]]), pieceName(after[forked[1]]))
        }
        if (safe && pin != null) {
            val front = pieceName(after[pin.front])
            val rear = pieceName(after[pin.rear])
            return if (pin.skewer) tactical(SKEWER_PLAN_LINES, mover, front, rear)
            else tactical(PIN_PLAN_LINES, mover, front, rear)
        }

        // --- Quiet ideas ----------------------------------------------------------------------

        val inCheck = kingSquare(board, white)?.let { isAttacked(board, it, !white) } ?: false
        if (inCheck) {
            return when {
                kind == 'k' && m1.captured == null -> quiet(KING_STEPS_OUT_PLAN_LINES)
                m1.captured != null -> quiet(TAKE_CHECKER_PLAN_LINES, mover)
                else -> quiet(BLOCK_CHECK_PLAN_LINES, mover)
            }
        }

        if (kind != 'k' && safe && materialLostOn(board, m1.from, white, pieceValue(m1.piece)) > 0 &&
            pieceValue(m1.piece) >= 3 && m1.captured == null
        ) {
            return quiet(RESCUE_PLAN_LINES, name)
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
                return if (attackers(after, threatened, !white).isEmpty()) quiet(HITS_LOOSE_PLAN_LINES, mover, target)
                else quiet(HITS_PIECE_PLAN_LINES, mover, target)
            }

            // A friendly piece that was about to be lost is now covered.
            val covered = (0 until 64).firstOrNull { sq ->
                val c = board[sq]
                sq != m1.from && c != EMPTY && c.isUpperCase() == white && pieceValue(c) >= 3 &&
                    after[sq] == c && materialLostOn(board, sq, white, pieceValue(c)) > 0 &&
                    materialLostOn(after, sq, white, pieceValue(c)) == 0
            }
            if (covered != null) return quiet(COVERS_PLAN_LINES, mover, pieceName(board[covered]))
        }

        val opening = board.count { it.lowercaseChar() in "nbrq" } >= OPENING_PIECE_COUNT
        val file = m1.to % 8
        val rank = m1.to / 8

        if (kind == 'p' && m1.captured == null) {
            val opened = openedLine(m1, m2)
            val central = file in 3..4 && rank in 3..4
            val relativeRank = if (white) rank else 7 - rank
            return when {
                opening && central && opened != null -> quiet(CENTER_OPENS_PLAN_LINES, opened.second)
                opening && central -> quiet(CENTER_PLAN_LINES)
                relativeRank >= 5 && safe && isPassedPawn(after, m1.to, white) -> quiet(PASSED_PAWN_PLAN_LINES)
                opened != null -> quiet(OPENS_LINE_PLAN_LINES, opened.first, opened.second)
                else -> defensivePlan(position, m1, moverEval)
            }
        }

        if (safe && m1.captured == null) {
            val homeRank = if (white) 0 else 7
            if (opening && (kind == 'n' || kind == 'b') && m1.from / 8 == homeRank) {
                val central = kind == 'n' && file in 2..5 && rank in 2..5
                return if (central) quiet(DEVELOP_CENTER_PLAN_LINES) else quiet(DEVELOP_PLAN_LINES, name)
            }
            if (kind == 'r' && m1.from % 8 != file) {
                val onFile = (0..7).map { after[it * 8 + file] }
                val ownPawn = if (white) 'P' else 'p'
                when {
                    onFile.none { it.lowercaseChar() == 'p' } -> return quiet(OPEN_FILE_PLAN_LINES)
                    ownPawn !in onFile -> return quiet(HALF_OPEN_FILE_PLAN_LINES)
                }
            }
            if (kind == 'r' && m1.from / 8 != rank && rank == (if (white) 6 else 1) &&
                (0..7).any { after[rank * 8 + it] == (if (white) 'p' else 'P') }
            ) {
                return quiet(SEVENTH_RANK_PLAN_LINES)
            }
        }

        return defensivePlan(position, m1, moverEval)
    }

    /** Somewhat worse but not lost: the best move is about holding on. */
    private fun defensivePlan(position: String, m1: Step, moverEval: Int?): Plan? {
        if (moverEval == null || moverEval > -DEFENSIVE_EVAL_CP || moverEval < -DECISIVE_EVAL_CP) return null
        if (m1.captured != null || m1.check) return null
        return Plan(fmt(position, DEFENSE_PLAN_LINES, m1.mover), false)
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

    // Plan lines. Moves are never written in notation: a "mover" argument is the piece that moves
    // ("the knight", "castling"), every other piece is given by its bare name ("knight"). A mover
    // may start a sentence anywhere in a line; fmt() capitalizes it.

    // Arg is the mover.
    private val MATE_NOW_PLAN_LINES = arrayOf(
        "%s delivers checkmate. Play it.",
        "Mate in 1. %s ends it.",
        "Checkmate with %s. Play it.",
    )

    // Args are (mover, the piece that finishes).
    private val MATE_IN_TWO_PLAN_LINES = arrayOf(
        "Mate in 2. %s first, then %s finishes.",
        "%s first. After their reply, %s mates.",
        "Mate in 2. %s forces it, %s finishes.",
    )

    // Args are (mover, the piece that follows up, moves to mate).
    private val MATE_LINE_PLAN_LINES = arrayOf(
        "Mate in %3\$d. %1\$s forces it, then %2\$s follows.",
        "%1\$s starts a forced mate in %3\$d. %2\$s comes next.",
        "Mate in %3\$d. %1\$s first, then %2\$s.",
    )

    // Args are (mover, moves to mate).
    private val MATE_FORCED_PLAN_LINES = arrayOf(
        "Mate in %2\$d. %1\$s forces it.",
        "%s starts a forced mate in %d.",
    )

    private val OPPONENT_BLUNDER_PREFIXES = arrayOf("They blundered.", "That's a blunder by them.", "Blunder. Punish it.")
    private val OPPONENT_MISTAKE_PREFIXES = arrayOf("They slipped.", "Mistake by them.", "That was a mistake. Punish it.")

    // Args are (sacrificed mover, follow-up mover).
    private val SAC_WINS_BACK_PLAN_LINES = arrayOf(
        "Sacrifice %s. If they take, %s wins back more.",
        "Sacrifice %s. It's sound. %s wins it back with interest.",
    )

    private val SAC_ATTACK_PLAN_LINES = arrayOf(
        "Sacrifice %s. If they take, %s comes with check.",
        "Sacrifice %s. It opens their king, and %s keeps the attack going.",
    )

    // Args are (captured piece, mover, follow-up mover).
    private val CAPTURE_THEN_MORE_PLAN_LINES = arrayOf(
        "Take the %1\$s with %2\$s. If they recapture, %3\$s wins more.",
        "%2\$s wins the %1\$s. When they take back, %3\$s picks up more.",
    )

    // Args are (mover, captured piece).
    private val FREE_CAPTURE_PLAN_LINES = arrayOf(
        "%s wins the %s for free. Nothing defends it.",
        "Their %2\$s is hanging. %1\$s just takes it.",
        "%s takes a free %s. They can't recapture.",
    )

    private val AHEAD_CAPTURE_PLAN_LINES = arrayOf(
        "%s wins the %s. They can take back, but you come out ahead.",
        "Take the %2\$s with %1\$s. Good trade, you gain material.",
    )

    // Args are (mover, first target, second target).
    private val FORK_WINS_PLAN_LINES = arrayOf(
        "%s forks the %s and %s. They can't save both.",
        "Fork with %s. The %s and %s are both hit. One falls.",
    )

    private val FORK_PLAN_LINES = arrayOf(
        "%s forks the %s and %s. Double attack.",
        "Fork with %s. It hits the %s and the %s at once.",
    )

    // Args are (mover, piece in front, piece behind).
    private val PIN_WINS_PLAN_LINES = arrayOf(
        "%s pins the %s to the %s. Take it next move.",
        "Pin the %2\$s to the %3\$s with %1\$s. It can't move, so you win it.",
    )

    private val PIN_PLAN_LINES = arrayOf(
        "%s pins the %s to the %s. Pile up on it.",
        "Pin with %s. Their %s is stuck in front of the %s.",
    )

    private val SKEWER_PLAN_LINES = arrayOf(
        "%s skewers the %s. When it moves, the %s falls.",
        "Skewer with %s. The %s has to step aside and you take the %s.",
    )

    // Args are (mover, follow-up mover, target).
    private val IF_THEY_TAKE_PLAN_LINES = arrayOf(
        "Play %s. If they take, %s wins the %s.",
        "%s sets a trap. If they capture, %s picks up the %s.",
    )

    private val TRADE_THEN_WIN_PLAN_LINES = arrayOf(
        "Trade with %s first. Then %s wins the %s.",
        "%s clears the way. Next, %s wins the %s.",
    )

    private val SETS_UP_PLAN_LINES = arrayOf(
        "%s sets it up. Next, %s wins the %s.",
        "%1\$s makes the threat. Their %3\$s has no escape.",
        "Play %s. Next, %s wins the %s.",
    )

    private val KING_STEPS_OUT_PLAN_LINES = arrayOf(
        "You're in check. The king steps out safely.",
        "Move the king. That gets you out of check.",
    )

    // Arg is the mover.
    private val TAKE_CHECKER_PLAN_LINES = arrayOf(
        "You're in check. %s removes the attacker.",
        "%s takes the checking piece. Problem solved.",
    )

    private val BLOCK_CHECK_PLAN_LINES = arrayOf(
        "You're in check. Block it with %s.",
        "%s blocks the check and holds things together.",
    )

    // Arg is the piece that is moved away.
    private val RESCUE_PLAN_LINES = arrayOf(
        "Your %s is under attack. Move it to safety.",
        "Get the %s out of danger.",
        "Don't hang the %s. Move it away.",
    )

    // Args are (mover, target).
    private val HITS_PIECE_PLAN_LINES = arrayOf(
        "%s hits their %s. It has to move and you gain a tempo.",
        "%s attacks the %s. Make them react.",
    )

    private val HITS_LOOSE_PLAN_LINES = arrayOf(
        "%s attacks their undefended %s. They have to deal with it.",
        "Their %2\$s is loose. %1\$s goes after it.",
    )

    private val COVERS_PLAN_LINES = arrayOf(
        "%s defends your %s, which was hanging.",
        "Your %2\$s needed cover. %1\$s protects it.",
    )

    // Arg is the piece the pawn move frees.
    private val CENTER_OPENS_PLAN_LINES = arrayOf(
        "Push the pawn. It takes the center and opens a line for your %s.",
        "The pawn claims the center and lets the %s out.",
    )

    private val CENTER_PLAN_LINES = arrayOf(
        "Push the pawn. It takes the center.",
        "The pawn grabs central space. Good opening move.",
    )

    private val PASSED_PAWN_PLAN_LINES = arrayOf(
        "Push the passed pawn. No pawn can stop it.",
        "Advance the passed pawn. It gets more dangerous every step.",
    )

    // Args are ("diagonal" | "file", piece).
    private val OPENS_LINE_PLAN_LINES = arrayOf(
        "Push the pawn. It opens the %s for your %s.",
        "The pawn move clears the %s and activates the %s.",
    )

    private val DEVELOP_CENTER_PLAN_LINES = arrayOf(
        "Develop the knight toward the center.",
        "Bring the knight out. It controls the center.",
    )

    // Arg is the piece.
    private val DEVELOP_PLAN_LINES = arrayOf(
        "Develop the %s. Get your pieces out.",
        "Bring the %s into the game. Development first.",
    )

    private val OPEN_FILE_PLAN_LINES = arrayOf(
        "Put the rook on the open file. It's much more active there.",
        "The rook takes the open file. Rooks belong there.",
    )

    private val HALF_OPEN_FILE_PLAN_LINES = arrayOf(
        "Put the rook on the half-open file. It presses on their pawn.",
        "Line the rook up against their pawn.",
    )

    private val SEVENTH_RANK_PLAN_LINES = arrayOf(
        "Put the rook on the seventh rank. Their pawns become targets.",
        "The rook invades the seventh rank and attacks their pawns.",
    )

    // Arg is the mover.
    private val DEFENSE_PLAN_LINES = arrayOf(
        "%s holds the position. Stay solid.",
        "%s is the toughest defense. Wait for your chance.",
        "%s keeps things together. Don't let them break through.",
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
        "Mate in 1. Play it.",
        "Checkmate is one move away.",
        "Mate in 1. Don't overthink it.",
        "One move ends the game.",
        "Checkmate is on the board. Deliver it.",
        "Mate in 1. Finish the game.",
        "There's a mate in 1. Find it.",
        "Mate on the board. End it.",
    )

    private val MATE_SOON_LINES = arrayOf(
        "Mate in %d. Just play the line.",
        "Forced mate in %d. Checks only.",
        "%d moves to checkmate. Stay precise.",
        "Mate in %d. Don't get creative.",
        "Mate in %d. Finish it cleanly.",
        "Forced mate in %d. Calculate it once, then play.",
        "Mate in %d. Keep giving checks.",
        "Mate in %d. The king can't escape.",
    )

    private val MATE_LINES = arrayOf(
        "Mate in %d. The king has nowhere to go.",
        "Forced mate in %d. Follow the line.",
        "There's a mate in %d here. Take your time.",
        "Mate in %d. Keep the checks coming.",
        "Mate in %d. Stay accurate and it's over.",
        "Forced mate in %d. No need to rush.",
        "Mate in %d. Don't let the king out.",
    )

    private val GETTING_MATED_LINES = arrayOf(
        "Forced mate against you. Play the longest defense.",
        "You're getting mated. Make them find every move.",
        "They have a forced mate. Delay it and hope.",
        "Mate is coming. Set a trap on the way.",
        "This is lost. Make them prove it.",
    )

    private val OPPONENT_BLUNDER_LINES = arrayOf(
        "They blundered. Punish it.",
        "That was a blunder. Take what they gave you.",
        "They just hung material. Collect it.",
        "Blunder by them. Find the win.",
        "They cracked. Don't let them off.",
        "Big mistake by them. Cash in now.",
        "That loses for them. Play it accurately.",
        "They blundered. Look for the forcing move.",
        "Free material on offer. Take it.",
    )

    private val OPPONENT_MISTAKE_LINES = arrayOf(
        "They slipped. Press the advantage.",
        "Mistake by them. Make it count.",
        "That was loose. Take the initiative.",
        "They left an opening. Use it.",
        "Mistake by them. Look for tactics.",
        "Not their best. Push while you can.",
        "They erred. Punish it before they regroup.",
        "That was a slip. Your chance to take over.",
    )

    private val OPPONENT_INACCURACY_LINES = arrayOf(
        "Small slip by them. Keep the pressure on.",
        "Slightly inaccurate. Take the initiative.",
        "They drifted a little. Squeeze.",
        "Soft move from them. Improve your position.",
        "Not their best. Grab some space.",
        "A small edge for you. Build on it.",
        "They got loose. Tighten the grip.",
    )

    private val OPPONENT_BRILLIANT_LINES = arrayOf(
        "Strong move by them. Defend carefully.",
        "They found something sharp. Stay calm.",
        "Good move by them. Calculate before you reply.",
        "That was strong. Don't panic, just think.",
        "They found the best idea. Dig in.",
        "Dangerous move. Check every threat first.",
        "Credit to them. Now defend accurately.",
    )

    private val SACRIFICE_LINES = arrayOf(
        "Sacrifice the %s. It's sound.",
        "Sac the %s. The attack is worth more.",
        "Give up the %s. It looks risky, but it wins.",
        "%s sacrifice. Calculate it, then play it.",
        "Sac the %s for the initiative.",
        "Let the %s go. You get more back.",
        "Sacrifice the %s. Trust the calculation.",
        "The %s sacrifice works. Play it.",
    )

    // Args are (mover, captured).
    private val SACRIFICE_CAPTURE_LINES = SACRIFICE_LINES + "Give the %s for the %s. It works."

    private val BIG_CAPTURE_LINES = arrayOf(
        "Their %s is hanging. Take it.",
        "Free %s. Nothing defends it.",
        "The %s is undefended. Capture it.",
        "Take the %s. They can't recapture.",
        "Hanging %s. Win it now.",
        "They left the %s loose. Take it.",
        "Win the %s. It's completely free.",
    )

    private val FREE_PIECE_LINES = arrayOf(
        "Free %s. Take it.",
        "The %s is undefended. Collect it.",
        "Nobody guards the %s. Take it.",
        "Loose %s. Win it.",
        "They left the %s hanging. Capture it.",
        "Take the %s. They can't take back.",
        "Hanging %s. Don't miss it.",
        "The %s is free. Grab it.",
    )

    // Args are (mover, captured).
    private val WINNING_CAPTURE_LINES = arrayOf(
        "%s takes %s. You win material.",
        "Take the %2\$s with the %1\$s. You come out ahead.",
        "Win the %2\$s. Whatever they take back is worth less.",
        "%s for the %s is a good trade.",
        "Capture the %2\$s. Good trade, you're up material.",
    )

    private val CHECK_LINES = arrayOf(
        "Check with the %s. Keep the initiative.",
        "%s check. Force the king to move.",
        "Give check with the %s. Keep them busy.",
        "%s check. Drive the king out.",
        "Check with the %s. Don't give them time.",
        "%s check first. Then see what's loose.",
    )

    // Args are (mover, captured).
    private val CAPTURE_LINES = arrayOf(
        "%s takes %s. Simple and good.",
        "Take the %2\$s with the %1\$s.",
        "Capture the %2\$s. Nothing more to it.",
        "%s takes the %s. Keep it clean.",
        "Take the %2\$s. No need to complicate.",
    )

    private val CASTLE_LINES = arrayOf(
        "Castle now. Get the king safe.",
        "Castle. The king is safer and the rook joins in.",
        "Castle before you attack.",
        "Tuck the king away. Then connect the rooks.",
        "Castle. King safety comes first.",
        "Get the king out of the center. Castle.",
        "Castle now. The attack can wait a move.",
    )

    private val PROMOTION_LINES = arrayOf(
        "Promote the pawn. New queen.",
        "Push it home and take a queen.",
        "Promote to a queen. Play it now.",
        "Queen the pawn. Don't wait.",
        "Promotion. Take the new queen.",
        "The pawn goes through. Make a queen.",
    )

    private val UNDERPROMOTION_LINES = arrayOf(
        "Promote to a %s, not a queen.",
        "Underpromote to a %s. The queen is wrong here.",
        "Make it a %s. That's the accurate move.",
        "Take a %s instead of a queen. It's stronger.",
    )

    private val WINNING_BIG_LINES = arrayOf(
        "You're winning. Trade down.",
        "Big advantage. Simplify and convert.",
        "Winning position. Trade pieces, not pawns.",
        "You're well ahead. Check their threats, then cash in.",
        "Winning. Keep it simple and safe.",
        "Ahead by a lot. Don't rush it.",
        "This is won if you stay careful.",
        "You're winning. No risks needed.",
        "Trade down. Every exchange helps you.",
        "Winning. Play clean, boring moves.",
    )

    private val LOSING_BIG_LINES = arrayOf(
        "Tough position. Look for counterplay.",
        "You're behind. Keep pieces on the board.",
        "Losing, not lost. Set some traps.",
        "Difficult. Play the toughest defense.",
        "Behind. Avoid trades and create threats.",
        "Make them work for it. Stay active.",
        "Down a lot. Complicate the position.",
        "Hold on. Look for tricks.",
        "Keep it messy. Simple positions lose here.",
    )

    private val EQUAL_LINES = arrayOf(
        "Equal position. Play accurately.",
        "Level game. Improve your worst piece.",
        "Balanced. Be patient and find a plan.",
        "Even game. One mistake decides it.",
        "Equal. Stay solid and watch for tactics.",
        "Nothing in it. Build slowly.",
        "Level. No rush, keep improving.",
        "Equal position. Look for a small edge.",
    )

    private val BEST_MOVE_LINES = arrayOf(
        "Play the %s. This is the move.",
        "The %s move is best here.",
        "Move the %s. It keeps control.",
        "Best is the %s. Stay on plan.",
        "Go with the %s. No second-guessing.",
        "The %s. It keeps everything together.",
        "%s move. Clean and correct.",
    )

    private val DEFAULT_LINES = arrayOf(
        "Play the %s here. Solid move.",
        "Move the %s. Nothing flashy, just correct.",
        "Improve the %s. Small moves add up.",
        "Bring the %s into the game.",
        "Reposition the %s. It's better placed there.",
        "Quiet %s move. Build the position.",
        "The %s move keeps things solid.",
        "Play the %s. Keep improving.",
    )

    // -------------------------------------------------------------------------------------
    // Lines: opponent's turn (the label describes the user's last move)
    // -------------------------------------------------------------------------------------

    // Reactions to the user's own move, by classification (see feedbackLines).
    // Recognition of the move, nothing else: two to five words that name what it was. What it
    // cost and what was better is added by postMoveCommentary, the advice by generate.

    private val BRILLIANT_LINES = arrayOf(
        "Brilliant find.",
        "Brilliant. Only move and you found it.",
        "Brilliant sacrifice.",
        "Brilliant. Hard to find.",
        "Brilliant. Well calculated.",
    )

    private val GREAT_LINES = arrayOf(
        "Great move.",
        "Strong.",
        "Well played.",
        "Great find.",
        "Great move. Well spotted.",
    )

    private val BEST_LINES = arrayOf(
        "Best move.",
        "Exactly right.",
        "Engine agrees.",
        "That's the top move.",
        "Best on the board.",
    )

    private val EXCELLENT_LINES = arrayOf(
        "Excellent.",
        "Excellent move.",
        "Nearly best.",
        "Very good.",
    )

    private val GOOD_LINES = arrayOf(
        "Good move.",
        "Good. Solid.",
        "That works.",
        "Sound move.",
    )

    private val BOOK_LINES = arrayOf(
        "Book move.",
        "Still theory.",
        "Main line.",
        "Still in book.",
    )

    private val INACCURACY_LINES = arrayOf(
        "Slight inaccuracy.",
        "There was better.",
        "Close but not best.",
        "Inaccuracy.",
        "A little imprecise.",
    )

    private val MISTAKE_LINES = arrayOf(
        "Mistake.",
        "That cost you.",
        "Not the best.",
        "That was a mistake.",
        "Wrong move there.",
    )

    private val MISS_LINES = arrayOf(
        "You missed it.",
        "Missed chance.",
        "There was a tactic.",
        "You let them off.",
    )

    private val BLUNDER_LINES = arrayOf(
        "Blunder.",
        "That hurts.",
        "Rough.",
        "That was a blunder.",
        "Big blunder.",
    )

    private val TIME_PRESSURE_LINES = arrayOf(
        "Low on time. Play it and hit the clock.",
        "No time to calculate. Keep it simple.",
        "Clock is running. Safe moves only.",
        "Short on time. Don't hang anything.",
        "Move now. Play the arrow.",
        "Time trouble. Simple and safe.",
        "Seconds left. Just play the move.",
        "Low time. Trust the move and go.",
    )

    private val THREAT_MATE_LINES = arrayOf(
        "They have a forced mate. Brace for it.",
        "Mate threat against you. Longest defense.",
        "They have mate. Make them find it.",
        "Forced mate for them. Delay it.",
    )

    private val USER_MATES_LINES = arrayOf(
        "You have mate in %d. Stay precise.",
        "Mate in %d for you. Don't rush.",
        "Forced mate in %d. Checks only.",
        "Mate in %d. Finish it cleanly.",
        "They're done. Mate in %d.",
        "Mate in %d. Calculate, then play.",
    )

    private val THREAT_BIG_CAPTURE_LINES = arrayOf(
        "Your %s is hanging. They can take it.",
        "Watch your %s. It isn't safe there.",
        "They threaten your %s. Be ready.",
        "Your %s is under attack. Expect them to take.",
    )

    private val THREAT_TRADE_LINES = arrayOf(
        "Expect them to take the %s. You can take back.",
        "They'll trade on the %s. Recapture.",
        "A %s trade is coming. Nothing to fear.",
        "They can swap off the %s. Be ready to recapture.",
    )

    private val THREAT_CHECK_LINES = arrayOf(
        "Expect a %s check. Stay calm.",
        "%s check is coming. Have your reply ready.",
        "Their %s is about to give check.",
        "Watch for the %s check next.",
    )

    private val THREAT_CAPTURE_LINES = arrayOf(
        "They'll probably take the %s.",
        "Your %s is under attack. Watch it.",
        "Expect them to take the %s next.",
        "The %s is likely coming off.",
    )

    private val OPPONENT_REPLY_LINES = arrayOf(
        "Expect a %s move from them.",
        "Watch their %s. It's their best reply.",
        "They'll likely answer with the %s.",
        "Their best reply uses the %s.",
    )
}
