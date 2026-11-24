package com.chesstrainer.app.engine

import android.util.Log
import kotlin.math.abs

enum class MoveClass(val symbol: String, val displayName: String) {
    BRILLIANT("!!", "Brilliant"),
    GREAT("!", "Great"),
    BEST("★", "Best"),
    EXCELLENT("●", "Excellent"),
    GOOD("○", "Good"),
    BOOK("📖", "Book"),
    INACCURACY("?!", "Inaccuracy"),
    MISTAKE("?", "Mistake"),
    MISS("△", "Miss"),
    BLUNDER("??", "Blunder")
}

data class ClassifiedMove(
    val move: String,
    val classification: MoveClass,
    val cpLoss: Int,
    val engineBest: String,
    val evalBefore: Int,   // centipawns, mover's POV, before the move
    val evalAfter: Int     // centipawns, mover's POV, after the move
)

/**
 * Classifies a played move by comparing the engine evaluation before and after it.
 *
 * All evaluations are converted to the mover's point of view. Evaluations are clamped to
 * +/-[EVAL_CAP] when computing centipawn loss, so "mate in 3" vs "mate in 5" counts as no
 * loss, while giving up a forced mate always counts as a large one, even when the mover
 * stays far ahead.
 */
object MoveClassifier {

    /** Above any realistic non-mate evaluation, so only mate scores saturate. */
    private const val EVAL_CAP = 5_000
    private const val MATE_THRESHOLD = StockfishEngine.MATE_SCORE - 1_000

    private const val EXCELLENT_MAX = 10      // CPL < 10
    private const val GOOD_MAX = 50           // 10 <= CPL < 50
    private const val INACCURACY_MAX = 100    // 50 <= CPL < 100
    private const val MISTAKE_MAX = 200       // 100 <= CPL <= 200, above is a blunder

    /** A brilliant or "only" move must leave the mover at least this well off. */
    private const val HOLDING_MIN_EVAL = -50
    /** A sacrifice in an already crushing position is not brilliant. */
    private const val BRILLIANT_MAX_EVAL_BEFORE = 500
    /** Minimum net material (pawn units) put en prise to count as a sacrifice. */
    private const val SACRIFICE_MIN_MATERIAL = 2

    /** For an "only move", every alternative must be at least this bad for the mover. */
    private const val ONLY_MOVE_ALTERNATIVE_MAX = -200
    /** Minimum gap (cp) between best and second-best to qualify as "only move". */
    private const val ONLY_MOVE_GAP = 100

    /** Eval swing (mover's POV, vs. before the opponent's last move) that makes a move "great". */
    private const val GREAT_MIN_SWING = 150
    /** Punishing an error only counts as great if the mover is not still clearly losing. */
    private const val GREAT_MIN_EVAL = -200

    /** A miss requires an advantage that was there to be exploited. */
    private const val MISS_MIN_EVAL_BEFORE = 150
    /** Below this eval after the move, a large loss is a blunder rather than a miss. */
    private const val MISS_LOSING_EVAL = -200

    private const val TAG = "MoveClassifier"
    /** A move that gains more than this (cp) must not come out as a mistake or blunder. */
    private const val CONSISTENCY_GAIN_CP = 100
    /** A move that loses more than this (cp) must not come out as brilliant or great. */
    private const val CONSISTENCY_LOSS_CP = 200

    /**
     * @param engine engine used to evaluate the position after [playedMove]
     * @param fen position before the move
     * @param playedMove move actually played, UCI format
     * @param analysisBefore engine analysis of [fen]
     * @param depth search depth for the follow-up analyses
     * @param isBookMove pass true when the move comes from an opening book; it is then
     *   classified as [MoveClass.BOOK] without further checks
     * @param evalBeforeOpponentMove evaluation (centipawns, from the mover's POV) of the
     *   position before the opponent's previous move. Needed to detect [MoveClass.GREAT]
     *   (punishing an opponent's error); when null, GREAT is never assigned.
     * @throws IllegalStateException if the engine fails to analyse the resulting position
     *   for a move that is not the engine's best move
     */
    fun classify(
        engine: StockfishEngine,
        fen: String,
        playedMove: String,
        analysisBefore: AnalysisResult,
        depth: Int = 12,
        isBookMove: Boolean = false,
        evalBeforeOpponentMove: Int? = null,
        precomputedEvalAfter: Int? = null,
        multiPvBefore: List<AnalysisResult>? = null
    ): ClassifiedMove {
        val played = playedMove.trim().lowercase()
        val best = analysisBefore.bestMove.trim().lowercase()
        val isBest = played == best
        val board = Board.fromFen(fen)

        val evalBefore = analysisBefore.eval
        val evalAfter = if (precomputedEvalAfter != null) {
            precomputedEvalAfter
        } else {
            val afterAnalysis = engine.analyze(fen, depth, listOf(played))
            when {
                afterAnalysis != null -> -afterAnalysis.eval
                isBest -> evalBefore
                else -> throw IllegalStateException("Engine failed to analyse position after $played")
            }
        }

        val cpLoss = if (isBest) 0 else maxOf(0, clamp(evalBefore) - clamp(evalAfter))

        // Cache expensive checks (engine calls) for use in multiple conditions
        val onlyMove by lazy { isOnlyMove(engine, fen, played, depth, multiPvBefore) }
        val sacrifice by lazy { board?.isSacrifice(played, SACRIFICE_MIN_MATERIAL) == true }

        val classification = when {
            isBookMove -> MoveClass.BOOK
            // Brilliant: best + sacrifice + only good move + eval guards
            isBest && sacrifice && onlyMove && brilliantEvalOk(evalBefore, evalAfter)
                -> MoveClass.BRILLIANT
            // Great: best + only good move (not a sacrifice), OR best + big eval swing
            isBest && onlyMove && !sacrifice -> MoveClass.GREAT
            isBest && isGreatSwing(evalAfter, evalBeforeOpponentMove) -> MoveClass.GREAT
            // Best: engine's top move, doesn't qualify for Brilliant or Great
            isBest -> MoveClass.BEST
            isMiss(cpLoss, evalBefore, evalAfter, board, best) -> MoveClass.MISS
            else -> byCpLoss(cpLoss)
        }

        checkConsistency(played, classification, isBest, evalBefore, evalAfter)

        return ClassifiedMove(
            move = played,
            classification = classification,
            cpLoss = cpLoss,
            engineBest = best,
            evalBefore = evalBefore,
            evalAfter = evalAfter
        )
    }

    /**
     * Verifies that [classification] agrees with the direction the evaluation moved (both from
     * the mover's POV, mate scores clamped as for the centipawn loss). It only logs: a violation
     * does not mean the move was misjudged here, it means the two evaluations it was judged from
     * do not describe the same game (wrong position analysed, wrong side to move, UCI desync, or
     * searches of very different depth disagreeing).
     */
    private fun checkConsistency(
        played: String,
        classification: MoveClass,
        isBest: Boolean,
        evalBefore: Int,
        evalAfter: Int
    ) {
        val change = clamp(evalAfter) - clamp(evalBefore)
        val detail = "evalBefore=$evalBefore evalAfter=$evalAfter (mover's POV, change $change cp)"
        when {
            change > CONSISTENCY_GAIN_CP &&
                (classification == MoveClass.BLUNDER || classification == MoveClass.MISTAKE) ->
                Log.w(TAG, "Inconsistent classification: $played is ${classification.displayName} " +
                    "but the eval improved. $detail")
            change < -CONSISTENCY_LOSS_CP &&
                (classification == MoveClass.BRILLIANT || classification == MoveClass.GREAT) ->
                Log.w(TAG, "Inconsistent classification: $played is ${classification.displayName} " +
                    "but the eval dropped. $detail")
            // Same symptom one step earlier: the engine's own best move should not lose eval.
            isBest && change < -CONSISTENCY_LOSS_CP ->
                Log.w(TAG, "Engine's best move $played loses eval: the analyses before and " +
                    "after the move disagree. $detail")
            else -> Log.d(TAG, "Classified $played as ${classification.displayName}. $detail")
        }
    }

    private fun byCpLoss(cpLoss: Int): MoveClass = when {
        cpLoss < EXCELLENT_MAX -> MoveClass.EXCELLENT
        cpLoss < GOOD_MAX -> MoveClass.GOOD
        cpLoss < INACCURACY_MAX -> MoveClass.INACCURACY
        cpLoss <= MISTAKE_MAX -> MoveClass.MISTAKE
        else -> MoveClass.BLUNDER
    }

    /** Eval guards for a brilliant move; sacrifice and only-move checks are done by the caller. */
    private fun brilliantEvalOk(evalBefore: Int, evalAfter: Int): Boolean {
        if (evalAfter < HOLDING_MIN_EVAL) return false
        return evalBefore < BRILLIANT_MAX_EVAL_BEFORE
    }

    /** True if [move] holds the position while every alternative loses. */
    private fun isOnlyMove(
        engine: StockfishEngine,
        fen: String,
        move: String,
        depth: Int,
        multiPvBefore: List<AnalysisResult>?
    ): Boolean {
        val lines = if (multiPvBefore != null && multiPvBefore.size >= 2) {
            multiPvBefore
        } else {
            engine.analyzeMultiPv(fen, depth, 2) ?: return false
        }
        if (lines.size < 2) return false
        val (bestLine, secondLine) = lines
        if (bestLine.bestMove.trim().lowercase() != move) return false
        if (bestLine.eval < HOLDING_MIN_EVAL) return false
        val gap = bestLine.eval - secondLine.eval
        // Only move if the gap is large enough, or the second-best leads to a losing position
        return gap >= ONLY_MOVE_GAP || secondLine.eval <= ONLY_MOVE_ALTERNATIVE_MAX
    }

    /** True when a best move produces a big positive eval swing (punishing an opponent error). */
    private fun isGreatSwing(evalAfter: Int, evalBeforeOpponentMove: Int?): Boolean {
        if (evalBeforeOpponentMove == null) return false
        if (evalAfter < GREAT_MIN_EVAL) return false
        return clamp(evalAfter) - clamp(evalBeforeOpponentMove) >= GREAT_MIN_SWING
    }

    private fun isMiss(
        cpLoss: Int,
        evalBefore: Int,
        evalAfter: Int,
        board: Board?,
        bestMove: String
    ): Boolean {
        if (cpLoss < INACCURACY_MAX) return false
        if (evalBefore < MISS_MIN_EVAL_BEFORE) return false
        // A move that throws the game away is a blunder, not merely a missed chance.
        if (cpLoss > MISTAKE_MAX && evalAfter < MISS_LOSING_EVAL) return false
        if (evalBefore >= MATE_THRESHOLD && evalAfter < MATE_THRESHOLD) return true
        if (board == null || bestMove.isEmpty()) return false
        return board.isCapture(bestMove) || board.givesCheck(bestMove)
    }

    private fun clamp(eval: Int): Int = eval.coerceIn(-EVAL_CAP, EVAL_CAP)
}

/**
 * Minimal board model, sufficient for capture, check and sacrifice detection.
 * Squares are indexed 0..63 as `rank * 8 + file` with a1 = 0. White pieces are upper case.
 */
internal class Board private constructor(
    private val squares: CharArray,
    val whiteToMove: Boolean,
    private val enPassant: Int
) {

    companion object {
        private const val EMPTY = '.'

        private val KNIGHT_OFFSETS = arrayOf(
            1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2
        )
        private val KING_OFFSETS = arrayOf(
            1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1
        )
        private val ROOK_DIRS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val BISHOP_DIRS = arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

        fun fromFen(fen: String): Board? {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) return null
            val rows = parts[0].split('/')
            if (rows.size != 8) return null

            val squares = CharArray(64) { EMPTY }
            for ((rowIndex, row) in rows.withIndex()) {
                val rank = 7 - rowIndex
                var file = 0
                for (c in row) {
                    if (c.isDigit()) {
                        file += c - '0'
                    } else {
                        if (file > 7 || c.lowercaseChar() !in "pnbrqk") return null
                        squares[rank * 8 + file] = c
                        file++
                    }
                }
                if (file != 8) return null
            }

            val whiteToMove = parts.getOrNull(1) != "b"
            val ep = parts.getOrNull(3)?.let { parseSquare(it, 0) } ?: -1
            return Board(squares, whiteToMove, ep)
        }

        private fun parseSquare(text: String, offset: Int): Int? {
            if (text.length < offset + 2) return null
            val file = text[offset] - 'a'
            val rank = text[offset + 1] - '1'
            if (file !in 0..7 || rank !in 0..7) return null
            return rank * 8 + file
        }

        private fun isWhite(piece: Char) = piece.isUpperCase()

        private fun value(piece: Char): Int = when (piece.lowercaseChar()) {
            'p' -> 1
            'n' -> 3
            'b' -> 3
            'r' -> 5
            'q' -> 9
            'k' -> 100
            else -> 0
        }
    }

    private data class Move(val from: Int, val to: Int, val promotion: Char?)

    private fun parseMove(uci: String): Move? {
        val m = uci.trim().lowercase()
        val from = parseSquare(m, 0) ?: return null
        val to = parseSquare(m, 2) ?: return null
        val promo = m.getOrNull(4)?.takeIf { it in "nbrq" }
        return Move(from, to, promo)
    }

    private fun isEnPassant(move: Move): Boolean {
        val piece = squares[move.from]
        return piece.lowercaseChar() == 'p' &&
            move.to == enPassant &&
            move.from % 8 != move.to % 8 &&
            squares[move.to] == EMPTY
    }

    fun isCapture(uci: String): Boolean {
        val move = parseMove(uci) ?: return false
        val piece = squares[move.from]
        if (piece == EMPTY) return false
        val target = squares[move.to]
        if (target != EMPTY && isWhite(target) != isWhite(piece)) return true
        return isEnPassant(move)
    }

    fun givesCheck(uci: String): Boolean {
        val after = applyMove(uci) ?: return false
        val king = after.kingSquare(after.whiteToMove) ?: return false
        return after.isAttacked(king, byWhite = !after.whiteToMove)
    }

    /**
     * True if the moved piece lands on a square where the opponent can win at least
     * [minMaterial] pawns of material from it (undefended, or attacked by a cheaper piece).
     */
    fun isSacrifice(uci: String, minMaterial: Int): Boolean {
        val move = parseMove(uci) ?: return false
        val piece = squares[move.from]
        if (piece == EMPTY || piece.lowercaseChar() == 'k') return false
        val moverWhite = isWhite(piece)

        val capturedValue = when {
            isEnPassant(move) -> 1
            squares[move.to] != EMPTY && isWhite(squares[move.to]) != moverWhite -> value(squares[move.to])
            else -> 0
        }

        val after = applyMove(uci) ?: return false
        val landed = after.squares[move.to]
        val atRisk = value(landed) - capturedValue
        if (atRisk < minMaterial) return false

        val defended = after.attackerValues(move.to, byWhite = moverWhite).isNotEmpty()
        val attackers = after.attackerValues(move.to, byWhite = !moverWhite)
            .filter { !(defended && it == value('k')) } // king cannot capture a defended piece
        val cheapest = attackers.minOrNull() ?: return false

        if (!defended) return true
        // Opponent takes with its cheapest attacker, we recapture it.
        return atRisk - cheapest >= minMaterial
    }

    private fun applyMove(uci: String): Board? {
        val move = parseMove(uci) ?: return null
        val piece = squares[move.from]
        if (piece == EMPTY) return null
        val white = isWhite(piece)
        val next = squares.copyOf()

        if (isEnPassant(move)) {
            val capturedSquare = (move.from / 8) * 8 + (move.to % 8)
            next[capturedSquare] = EMPTY
        }

        // Castling: king moves two files; bring the rook across.
        if (piece.lowercaseChar() == 'k' && abs(move.to % 8 - move.from % 8) == 2) {
            val rankBase = (move.from / 8) * 8
            if (move.to % 8 == 6) {
                next[rankBase + 5] = next[rankBase + 7]
                next[rankBase + 7] = EMPTY
            } else {
                next[rankBase + 3] = next[rankBase]
                next[rankBase] = EMPTY
            }
        }

        next[move.from] = EMPTY
        next[move.to] = move.promotion?.let { if (white) it.uppercaseChar() else it } ?: piece

        val newEp = if (piece.lowercaseChar() == 'p' && abs(move.to - move.from) == 16) {
            (move.from + move.to) / 2
        } else {
            -1
        }
        return Board(next, !whiteToMove, newEp)
    }

    /** Piece on [square] (0..63, a1 = 0), or null when it is empty or off the board. */
    fun pieceAt(square: Int): Char? = squares.getOrNull(square)?.takeIf { it != EMPTY }

    /** True when the side that is not to move is in check, which no legal position allows. */
    fun sideNotToMoveInCheck(): Boolean {
        val king = kingSquare(!whiteToMove) ?: return false
        return isAttacked(king, byWhite = whiteToMove)
    }

    private fun kingSquare(white: Boolean): Int? {
        val king = if (white) 'K' else 'k'
        val index = squares.indexOf(king)
        return if (index >= 0) index else null
    }

    private fun isAttacked(square: Int, byWhite: Boolean): Boolean =
        attackerValues(square, byWhite).isNotEmpty()

    /** Values of all pieces of the given colour that attack [square]. */
    private fun attackerValues(square: Int, byWhite: Boolean): List<Int> {
        val result = mutableListOf<Int>()
        val file = square % 8
        val rank = square / 8

        fun pieceAt(f: Int, r: Int): Char? =
            if (f in 0..7 && r in 0..7) squares[r * 8 + f] else null

        fun own(c: Char?): Boolean = c != null && c != EMPTY && isWhite(c) == byWhite

        // Pawns: a white pawn attacks diagonally upward, so it sits one rank below.
        val pawnRank = if (byWhite) rank - 1 else rank + 1
        val pawn = if (byWhite) 'P' else 'p'
        for (df in intArrayOf(-1, 1)) {
            if (pieceAt(file + df, pawnRank) == pawn) result.add(1)
        }

        for ((df, dr) in KNIGHT_OFFSETS) {
            val c = pieceAt(file + df, rank + dr)
            if (own(c) && c!!.lowercaseChar() == 'n') result.add(3)
        }

        for ((df, dr) in KING_OFFSETS) {
            val c = pieceAt(file + df, rank + dr)
            if (own(c) && c!!.lowercaseChar() == 'k') result.add(value('k'))
        }

        fun slide(dirs: Array<Pair<Int, Int>>, sliders: String) {
            for ((df, dr) in dirs) {
                var f = file + df
                var r = rank + dr
                while (f in 0..7 && r in 0..7) {
                    val c = squares[r * 8 + f]
                    if (c != EMPTY) {
                        if (isWhite(c) == byWhite && c.lowercaseChar() in sliders) result.add(value(c))
                        break
                    }
                    f += df
                    r += dr
                }
            }
        }
        slide(ROOK_DIRS, "rq")
        slide(BISHOP_DIRS, "bq")

        return result
    }
}
