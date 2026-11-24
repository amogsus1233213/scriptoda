package com.chesstrainer.app.service

/**
 * Minimal chess position tracker: applies SAN moves and produces FEN.
 * Used to reconstruct the board from chess.com's accessible move list
 * when the board view itself exposes no piece nodes.
 */
class ChessGame {
    val board = CharArray(64) { '.' }
    var white = true; private set
    private var cK = true; private var cQ = true; private var ck = true; private var cq = true
    private var ep = -1
    var fullMove = 1; private set
    private var halfClock = 0
    val appliedSan = mutableListOf<String>()

    init { reset() }

    fun reset() {
        "RNBQKBNR".forEachIndexed { i, c -> board[i] = c }
        for (i in 8..15) board[i] = 'P'
        for (i in 16..47) board[i] = '.'
        for (i in 48..55) board[i] = 'p'
        "rnbqkbnr".forEachIndexed { i, c -> board[56 + i] = c }
        white = true; cK = true; cQ = true; ck = true; cq = true
        ep = -1; halfClock = 0; fullMove = 1
        appliedSan.clear()
    }

    fun toFen(): String = buildString {
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[r * 8 + f]
                if (p == '.') empty++ else { if (empty > 0) { append(empty); empty = 0 }; append(p) }
            }
            if (empty > 0) append(empty)
            if (r > 0) append('/')
        }
        append(if (white) " w " else " b ")
        val c = buildString {
            if (cK) append('K'); if (cQ) append('Q'); if (ck) append('k'); if (cq) append('q')
        }
        append(c.ifEmpty { "-" })
        append(' ')
        append(if (ep >= 0) sqName(ep) else "-")
        append(" $halfClock $fullMove")
    }

    fun toPositions(): Map<String, Char> = buildMap {
        for (i in 0..63) if (board[i] != '.') put(sqName(i), board[i])
    }

    fun applySan(raw: String): Boolean {
        val san = raw.trim().replace("[​ ]".toRegex(), "")
        if (san.isEmpty()) return false

        val low = san.lowercase()
        if (low == "o-o" || low == "0-0") return applyCastle(true)
        if (low == "o-o-o" || low == "0-0-0") return applyCastle(false)

        val clean = san.replace("[+#!?]".toRegex(), "")
        val parsed = parseSan(clean) ?: return false
        val candidates = findCandidates(parsed)
        if (candidates.size != 1) return false

        val from = candidates[0]
        val to = parsed.destFile + parsed.destRank * 8
        doMove(from, to, parsed.promo)
        appliedSan.add(raw)
        return true
    }

    // --- SAN parsing ---

    private data class San(
        val piece: Char, // uppercase P,N,B,R,Q,K
        val destFile: Int, val destRank: Int,
        val disambFile: Int, val disambRank: Int, // -1 if unspecified
        val capture: Boolean,
        val promo: Char, // '.' if none, uppercase piece letter
    )

    private fun parseSan(s: String): San? {
        var idx = 0
        var piece = 'P'
        val firstChar = s[0]

        if (firstChar.lowercaseChar() in "nbrqk") {
            piece = firstChar.uppercaseChar()
            idx = 1
        }

        val rest = s.substring(idx)
        val promoIdx = rest.indexOf('=')
        val promo = if (promoIdx >= 0) {
            rest.getOrNull(promoIdx + 1)?.uppercaseChar()?.takeIf { it in "NBRQ" } ?: return null
        } else {
            val last = rest.lastOrNull()?.uppercaseChar()
            if (last != null && last in "NBRQ" && rest.length >= 3 && rest[rest.length - 2].isDigit()) last else '.'
        }

        val moveChars = rest.replace("=.*".toRegex(), "").let {
            if (promo != '.' && it.last().uppercaseChar() == promo) it.dropLast(1) else it
        }.replace("x", "").replace("X", "")

        if (moveChars.length < 2) return null
        val destFileC = moveChars[moveChars.length - 2]
        val destRankC = moveChars[moveChars.length - 1]
        if (destFileC !in 'a'..'h' || destRankC !in '1'..'8') return null
        val destFile = destFileC - 'a'
        val destRank = destRankC - '1'

        var disambFile = -1; var disambRank = -1
        val disamb = moveChars.substring(0, moveChars.length - 2)
        for (c in disamb) {
            when {
                c in 'a'..'h' -> disambFile = c - 'a'
                c in '1'..'8' -> disambRank = c - '1'
            }
        }

        val capture = 'x' in rest || 'X' in rest

        // Handle 'b' ambiguity: "bxc5" could be bishop or b-pawn
        // Try piece='B' interpretation first, pawn fallback handled in findCandidates
        if (piece == 'P' && firstChar.lowercaseChar() == 'b' && s.length >= 3 &&
            (s[1] == 'x' || s[1] == 'X' || s[1] in 'a'..'h' || s[1] in '1'..'8')) {
            // Could be bishop. We'll try both in findCandidates.
        }

        return San(piece, destFile, destRank, disambFile, disambRank, capture, promo)
    }

    private fun findCandidates(san: San): List<Int> {
        val to = san.destFile + san.destRank * 8
        val results = mutableListOf<Int>()
        val myPiece = if (white) san.piece.uppercaseChar() else san.piece.lowercaseChar()

        for (from in 0..63) {
            if (board[from] != myPiece) continue
            if (san.disambFile >= 0 && from % 8 != san.disambFile) continue
            if (san.disambRank >= 0 && from / 8 != san.disambRank) continue
            if (!canReach(from, to, san.piece)) continue
            if (!isLegalMove(from, to, san.promo)) continue
            results.add(from)
        }

        // Handle 'b' ambiguity: if piece was P (pawn) and no candidates, try Bishop
        if (results.isEmpty() && san.piece == 'P') {
            val bSan = san.copy(piece = 'B')
            return findCandidates(bSan)
        }
        // If still P (pawn) with multiple candidates from 'b' confusion, also try Bishop
        if (results.size > 1 && san.piece == 'P') {
            val bSan = san.copy(piece = 'B')
            val bResults = findCandidates(bSan)
            if (bResults.size == 1) return bResults
        }

        return results
    }

    private fun canReach(from: Int, to: Int, piece: Char): Boolean {
        if (from == to) return false
        val ff = from % 8; val fr = from / 8
        val tf = to % 8; val tr = to / 8
        val df = tf - ff; val dr = tr - fr
        val target = board[to]
        val friendly = if (white) target.isUpperCase() else target.isLowerCase()
        if (friendly) return false

        return when (piece) {
            'P' -> canPawnReach(ff, fr, tf, tr, df, dr, target, to)
            'N' -> (abs(df) == 1 && abs(dr) == 2) || (abs(df) == 2 && abs(dr) == 1)
            'B' -> abs(df) == abs(dr) && df != 0 && clearPath(from, to)
            'R' -> (df == 0 || dr == 0) && (df != 0 || dr != 0) && clearPath(from, to)
            'Q' -> ((abs(df) == abs(dr) && df != 0) || (df == 0 || dr == 0) && (df != 0 || dr != 0)) && clearPath(from, to)
            'K' -> abs(df) <= 1 && abs(dr) <= 1
            else -> false
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun canPawnReach(ff: Int, fr: Int, tf: Int, tr: Int, df: Int, dr: Int, target: Char, to: Int): Boolean {
        val dir = if (white) 1 else -1
        val startRank = if (white) 1 else 6
        if (df == 0) {
            if (dr == dir && target == '.') return true
            if (dr == 2 * dir && fr == startRank && target == '.' && board[(fr + dir) * 8 + ff] == '.') return true
        } else if (abs(df) == 1 && dr == dir) {
            if (target != '.') return true
            if (to == ep) return true
        }
        return false
    }

    private fun clearPath(from: Int, to: Int): Boolean {
        val ff = from % 8; val fr = from / 8
        val tf = to % 8; val tr = to / 8
        val sf = tf.compareTo(ff); val sr = tr.compareTo(fr)
        var f = ff + sf; var r = fr + sr
        while (f != tf || r != tr) {
            if (board[r * 8 + f] != '.') return false
            f += sf; r += sr
        }
        return true
    }

    private fun isLegalMove(from: Int, to: Int, promo: Char): Boolean {
        val saved = board.copyOf()
        val sEp = ep
        val piece = board[from]
        board[to] = board[from]
        board[from] = '.'
        // En passant capture
        if (piece.lowercaseChar() == 'p' && to == sEp) {
            val capturedRank = if (white) to / 8 - 1 else to / 8 + 1
            board[capturedRank * 8 + to % 8] = '.'
        }
        // Promotion
        if (promo != '.') board[to] = if (white) promo.uppercaseChar() else promo.lowercaseChar()

        val legal = !inCheck(white)
        saved.copyInto(board)
        return legal
    }

    private fun inCheck(asWhite: Boolean): Boolean {
        val king = if (asWhite) 'K' else 'k'
        val kingSq = board.indexOf(king)
        if (kingSq < 0) return false
        return isAttacked(kingSq, !asWhite)
    }

    private fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
        val sf = sq % 8; val sr = sq / 8
        // Knight attacks
        for ((df, dr) in KNIGHT_MOVES) {
            val f = sf + df; val r = sr + dr
            if (f in 0..7 && r in 0..7) {
                val p = board[r * 8 + f]
                if (p.lowercaseChar() == 'n' && p.isUpperCase() == byWhite) return true
            }
        }
        // Pawn attacks
        val pawnDir = if (byWhite) -1 else 1 // pawns attack the opposite direction
        for (df in intArrayOf(-1, 1)) {
            val f = sf + df; val r = sr + pawnDir
            if (f in 0..7 && r in 0..7) {
                val p = board[r * 8 + f]
                if (p.lowercaseChar() == 'p' && p.isUpperCase() == byWhite) return true
            }
        }
        // King
        for (df in -1..1) for (dr in -1..1) {
            if (df == 0 && dr == 0) continue
            val f = sf + df; val r = sr + dr
            if (f in 0..7 && r in 0..7) {
                val p = board[r * 8 + f]
                if (p.lowercaseChar() == 'k' && p.isUpperCase() == byWhite) return true
            }
        }
        // Sliding pieces (bishop/rook/queen)
        for ((df, dr) in DIRECTIONS) {
            var f = sf + df; var r = sr + dr
            while (f in 0..7 && r in 0..7) {
                val p = board[r * 8 + f]
                if (p != '.') {
                    if (p.isUpperCase() == byWhite) {
                        val lc = p.lowercaseChar()
                        val isDiag = abs(df) == 1 && abs(dr) == 1
                        if (lc == 'q') return true
                        if (isDiag && lc == 'b') return true
                        if (!isDiag && lc == 'r') return true
                    }
                    break
                }
                f += df; r += dr
            }
        }
        return false
    }

    // --- Move execution ---

    private fun doMove(from: Int, to: Int, promo: Char) {
        val piece = board[from]
        val lc = piece.lowercaseChar()
        val capture = board[to] != '.' || (lc == 'p' && to == ep)

        // En passant capture
        if (lc == 'p' && to == ep) {
            val capRank = if (white) to / 8 - 1 else to / 8 + 1
            board[capRank * 8 + to % 8] = '.'
        }

        board[to] = piece
        board[from] = '.'

        // Promotion
        if (promo != '.') board[to] = if (white) promo.uppercaseChar() else promo.lowercaseChar()

        // En passant target
        ep = if (lc == 'p' && abs(to / 8 - from / 8) == 2) (from + to) / 2 else -1

        // Castling rights
        if (lc == 'k') { if (white) { cK = false; cQ = false } else { ck = false; cq = false } }
        if (from == 0 || to == 0) cQ = false
        if (from == 7 || to == 7) cK = false
        if (from == 56 || to == 56) cq = false
        if (from == 63 || to == 63) ck = false

        // Counters
        halfClock = if (capture || lc == 'p') 0 else halfClock + 1
        if (!white) fullMove++
        white = !white
    }

    private fun applyCastle(kingSide: Boolean): Boolean {
        val rank = if (white) 0 else 7
        val kingFrom = rank * 8 + 4
        val king = board[kingFrom]
        if (king.lowercaseChar() != 'k') return false
        if (white && king != 'K') return false
        if (!white && king != 'k') return false

        val allowed = if (kingSide) (if (white) cK else ck) else (if (white) cQ else cq)
        if (!allowed) return false

        val rookFile = if (kingSide) 7 else 0
        val rookFrom = rank * 8 + rookFile
        val rook = board[rookFrom]
        if (rook.lowercaseChar() != 'r') return false

        val kingTo = rank * 8 + if (kingSide) 6 else 2
        val rookTo = rank * 8 + if (kingSide) 5 else 3

        // Check path is clear
        val minF = minOf(4, rookFile, if (kingSide) 6 else 2)
        val maxF = maxOf(4, rookFile, if (kingSide) 6 else 2)
        for (f in minF..maxF) {
            val sq = rank * 8 + f
            if (sq != kingFrom && sq != rookFrom && board[sq] != '.') return false
        }

        // Check king doesn't pass through or into check
        val step = if (kingSide) 1 else -1
        for (f in listOf(4, 4 + step, 4 + 2 * step)) {
            val sq = rank * 8 + f
            if (isAttacked(sq, !white)) return false
        }

        board[kingFrom] = '.'; board[rookFrom] = '.'
        board[kingTo] = king; board[rookTo] = rook
        if (white) { cK = false; cQ = false } else { ck = false; cq = false }
        ep = -1
        halfClock++
        if (!white) fullMove++
        white = !white
        appliedSan.add(if (kingSide) "O-O" else "O-O-O")
        return true
    }

    companion object {
        private val KNIGHT_MOVES = arrayOf(
            -2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1
        )
        private val DIRECTIONS = arrayOf(
            -1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1
        )

        fun sqName(i: Int): String = "${'a' + i % 8}${i / 8 + 1}"
        fun abs(x: Int): Int = if (x < 0) -x else x
    }
}
