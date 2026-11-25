package com.chesstrainer.app.engine

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Result of a single engine search.
 *
 * [eval] is always expressed in centipawns from the side-to-move point of view.
 * When the engine reports a forced mate, [mate] holds the number of moves to mate
 * (positive = side to move mates, negative = side to move gets mated, 0 = side to
 * move is already checkmated) and [eval] is set to a large value
 * ([StockfishEngine.MATE_SCORE] minus the mate distance) with the matching sign.
 */
data class AnalysisResult(
    val bestMove: String,       // UCI format e.g. "e2e4"; empty if there is no legal move
    val eval: Int,              // centipawns from side-to-move POV
    val mate: Int?,             // moves to mate (positive = winning), null if no mate
    val depth: Int,
    val pv: List<String>,       // principal variation moves
    /**
     * How far the score of this line moved (max - min, centipawns) over the last few search
     * depths. Small = the evaluation has settled, so the later PV moves can be trusted as a plan.
     * Null when unknown (too few depths, a mate score, or not the best line).
     */
    val evalSwing: Int? = null,
)

/**
 * Runs a Stockfish binary as a child process and talks to it over UCI.
 *
 * Engine output is consumed by a dedicated daemon reader thread and handed to callers
 * through a queue. All searches are serialized through a lock, so the instance can be
 * shared between threads. Every call except [stop] blocks, so call them off the main thread.
 *
 * If the process dies, the next [analyze] call restarts it transparently.
 */
class StockfishEngine(private val binaryPath: String) {

    companion object {
        /** Base value used to represent mate scores in centipawns. */
        const val MATE_SCORE = 100_000

        /**
         * Eval (cp) given to a line whose mate score could not be trusted: clearly winning, but
         * well below the range that stands for a forced mate.
         */
        const val UNPROVEN_MATE_CP = 5_000

        private const val TAG = "StockfishEngine"

        private const val ANALYSIS_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val READY_TIMEOUT_MS = 3_000L
        private const val STOP_DRAIN_TIMEOUT_MS = 1_000L
        private const val OPTION_TIMEOUT_MS = 1_000L
        private const val QUIT_WAIT_MS = 1_000L
        private const val DESTROY_WAIT_MS = 500L
        private const val POLL_INTERVAL_MS = 100L
        private const val ENGINE_THREADS = 6
        private const val ENGINE_HASH_MB = 1800
        /** Number of final search depths compared by [AnalysisResult.evalSwing]. */
        private const val SWING_DEPTHS = 4

        /** Sentinel pushed by the reader thread when the engine's stdout closes. */
        private val EOF_MARKER = String(charArrayOf('\u0000'))

        private val WHITESPACE = Regex("\\s+")
    }

    private val analysisLock = ReentrantLock()
    private val stateLock = Any()
    private val writeLock = Any()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var lines: LinkedBlockingQueue<String> = LinkedBlockingQueue()
    @Volatile private var initialized = false
    @Volatile private var currentMultiPv = 1

    /**
     * Launches the engine process and performs the UCI handshake.
     *
     * @return true if the engine answered "uciok" and "readyok", false otherwise.
     */
    fun start(): Boolean = synchronized(stateLock) {
        if (isReady()) return true
        shutdownProcess()

        try {
            val p = ProcessBuilder(binaryPath)
                .redirectErrorStream(true)
                .start()
            val queue = LinkedBlockingQueue<String>()
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val thread = Thread({
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) queue.offer(trimmed)
                    }
                } catch (_: IOException) {
                    // Stream closed: the process exited or was destroyed.
                } finally {
                    queue.offer(EOF_MARKER)
                    try {
                        reader.close()
                    } catch (_: IOException) {
                    }
                }
            }, "stockfish-reader").apply {
                isDaemon = true
            }

            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            lines = queue
            readerThread = thread
            currentMultiPv = 1
            thread.start()

            if (!send("uci") ||
                waitForLine(queue, deadlineAfter(HANDSHAKE_TIMEOUT_MS)) { it == "uciok" } == null
            ) {
                shutdownProcess()
                return false
            }

            // Sized for a mid-range phone (2 big + 6 little cores): more threads or a larger
            // hash table make Android more likely to kill the process.
            send("setoption name Threads value $ENGINE_THREADS")
            send("setoption name Hash value $ENGINE_HASH_MB")

            if (!send("isready") ||
                waitForLine(queue, deadlineAfter(READY_TIMEOUT_MS)) { it == "readyok" } == null
            ) {
                shutdownProcess()
                return false
            }

            initialized = true
            true
        } catch (e: IOException) {
            shutdownProcess()
            false
        } catch (e: SecurityException) {
            shutdownProcess()
            false
        }
    }

    /** True when the process is alive and the UCI handshake has completed. */
    fun isReady(): Boolean = initialized && process?.isAlive == true

    /**
     * Searches [fen] to [depth] plies and returns the deepest principal variation.
     *
     * [moves] (UCI format) are applied to [fen] before searching, which allows analysing
     * the position after a move without computing its FEN.
     *
     * @return the analysis, or null if the engine could not be started, died during the
     * search, or produced nothing usable before the search timeout.
     */
    fun analyze(fen: String, depth: Int = 12, moves: List<String> = emptyList()): AnalysisResult? =
        analyzeMultiPv(fen, depth, 1, moves)?.firstOrNull()

    /**
     * Same as [analyze] but asks the engine for [lineCount] principal variations.
     * The returned list is ordered best line first and may contain fewer than
     * [lineCount] entries when the position has fewer legal moves.
     *
     * With [moveTimeMs] > 0 the search is limited by both [depth] and the time: it ends at
     * whichever is reached first, so a simple position (forced mate, single legal move) returns
     * well before the time is up.
     */
    fun analyzeMultiPv(
        fen: String,
        depth: Int = 12,
        lineCount: Int = 1,
        moves: List<String> = emptyList(),
        moveTimeMs: Int = 0
    ): List<AnalysisResult>? {
        require(depth > 0) { "depth must be positive" }
        require(lineCount > 0) { "lineCount must be positive" }

        // Last line of defence for every caller: a malformed position makes the engine search
        // garbage or crash, so it is never sent.
        PositionValidator.fenError(fen)?.let { error ->
            Log.w(TAG, "Refusing to analyse invalid FEN ($error): $fen")
            return null
        }

        analysisLock.withLock {
            if (!isReady() && !start()) return null
            val queue = lines

            queue.clear()
            if (!send("isready") ||
                waitForLine(queue, deadlineAfter(READY_TIMEOUT_MS)) { it == "readyok" } == null
            ) {
                markDeadIfNeeded()
                return null
            }

            if (lineCount != currentMultiPv) {
                if (!send("setoption name MultiPV value $lineCount")) {
                    markDeadIfNeeded()
                    return null
                }
                currentMultiPv = lineCount
            }

            val position = buildString {
                append("position fen ").append(fen.trim())
                if (moves.isNotEmpty()) append(" moves ").append(moves.joinToString(" "))
            }
            // Both limits are sent: the engine stops at whichever comes first.
            val goCommand = if (moveTimeMs > 0) "go depth $depth movetime $moveTimeMs" else "go depth $depth"
            if (!send(position) || !send(goCommand)) {
                markDeadIfNeeded()
                return null
            }

            val deadline = deadlineAfter(if (moveTimeMs > 0) moveTimeMs.toLong() + ANALYSIS_TIMEOUT_MS else ANALYSIS_TIMEOUT_MS)
            val infos = HashMap<Int, InfoLine>()
            // Exact scores of the best line, one per depth, to tell how settled the eval is.
            val bestLineHistory = ArrayList<InfoLine>()
            // Latest line seen for each first move, whatever its multipv slot or depth; see
            // [buildResults] for why the slots alone are not enough.
            val byFirstMove = HashMap<String, InfoLine>()
            var order = 0
            fun record(parsed: InfoLine) {
                val info = parsed.copy(order = ++order)
                recordBestLine(bestLineHistory, info)
                val existing = infos[info.multiPv]
                if (shouldReplace(existing, info)) infos[info.multiPv] = keepLongerPv(existing, info)
                val first = info.pv.firstOrNull() ?: return
                val known = byFirstMove[first]
                // Newest wins, except that a bound never takes the place of an exact score.
                if (known == null || !info.isBound || known.isBound) {
                    byFirstMove[first] = keepLongerPv(known, info)
                }
            }
            var bestMove: String? = null

            while (bestMove == null) {
                val line = waitForLine(queue, deadline) { true } ?: break
                if (line.startsWith("bestmove")) {
                    bestMove = line.split(WHITESPACE).getOrNull(1) ?: "(none)"
                } else if (line.startsWith("info ")) {
                    record(parseInfo(line) ?: continue)
                }
            }

            if (bestMove == null) {
                // The process died mid-search: whatever was collected comes from a half-finished
                // search, so nothing is returned and the next call restarts the engine.
                if (process?.isAlive != true) {
                    markDeadIfNeeded()
                    return null
                }
                send("stop")
                val stopDeadline = deadlineAfter(STOP_DRAIN_TIMEOUT_MS)
                while (bestMove == null) {
                    val stopLine = waitForLine(queue, stopDeadline) { true } ?: break
                    if (stopLine.startsWith("bestmove")) {
                        bestMove = stopLine.split(WHITESPACE).getOrNull(1) ?: "(none)"
                    } else if (stopLine.startsWith("info ")) {
                        record(parseInfo(stopLine) ?: continue)
                    }
                }
                if (bestMove == null) {
                    // Still no bestmove: the engine is either dead or still searching. Left
                    // alive, its late bestmove would be read as the answer of the next search
                    // and every later result would be one search behind, so it is torn down and
                    // the next call starts a fresh process.
                    val diedMeanwhile = process?.isAlive != true
                    Log.w(TAG, "No bestmove before the timeout (process dead=$diedMeanwhile); restarting the engine")
                    synchronized(stateLock) { shutdownProcess() }
                    if (diedMeanwhile) return null
                    bestMove = infos[1]?.pv?.firstOrNull() ?: infos.values.firstOrNull()?.pv?.firstOrNull()
                    if (bestMove == null) return null
                }
            }

            return buildResults(bestMove, infos, bestLineHistory, byFirstMove)
        }
    }

    /**
     * Tells the engine that the next position belongs to a different game ("ucinewgame"), which
     * clears its hash table and search history so nothing from the previous game carries over.
     * Call it when a new game starts, not between positions of the same game: the hash is what
     * makes consecutive positions fast.
     *
     * Blocks until the engine is ready again (and until any running search has finished), so
     * call it off the main thread. Does not start the engine: if it is not running there is
     * nothing to clear and the next [analyze] call starts a fresh process anyway.
     *
     * @return true if the engine acknowledged, false if it is not running or did not answer.
     */
    fun newGame(): Boolean = analysisLock.withLock {
        if (!isReady()) return false
        val queue = lines
        queue.clear()
        if (!send("ucinewgame") || !send("isready") ||
            waitForLine(queue, deadlineAfter(READY_TIMEOUT_MS)) { it == "readyok" } == null
        ) {
            markDeadIfNeeded()
            return false
        }
        true
    }

    /** Interrupts the current search; the pending [analyze] call returns promptly. */
    fun stop() {
        if (process?.isAlive == true) send("stop")
    }

    /**
     * Sends "stop" without acquiring [analysisLock], so it can be called from another
     * thread while [analyzeMultiPv] is waiting. The running search will produce a
     * "bestmove" that [analyzeMultiPv] picks up.
     */
    fun interruptSearch() {
        if (initialized && process?.isAlive == true) send("stop")
    }

    /** Asks the engine to exit and releases the process. */
    fun quit() {
        synchronized(stateLock) {
            val p = process ?: return
            if (p.isAlive) {
                send("quit")
                try {
                    p.waitFor(QUIT_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            shutdownProcess()
        }
    }

    // ---------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------

    private data class InfoLine(
        val depth: Int,
        val multiPv: Int,
        val cp: Int?,
        val mate: Int?,
        val isBound: Boolean,
        val pv: List<String>,
        /** Only meaningful with [isBound]: true for "lowerbound", false for "upperbound". */
        val isLowerBound: Boolean = false,
        /** Position of the line in the engine's output of one search (1, 2, ...). */
        val order: Int = 0,
    ) {
        /**
         * True if the line's mate score stands for a forced mate. An exact score does. A bound
         * does only in one direction: "mate 3 lowerbound" says the score is at least a mate in
         * 3, so the side to move mates (in 3 or sooner), and "mate -3 upperbound" says it is
         * mated. The other direction ("mate 3 upperbound": at most a mate in 3) proves nothing.
         */
        val provesMate: Boolean
            get() = mate != null && (!isBound || (mate > 0) == isLowerBound)

        /** True for an exact (not a bound) mate score. */
        val isExactMate: Boolean get() = mate != null && !isBound
    }

    private fun shouldReplace(existing: InfoLine?, candidate: InfoLine): Boolean {
        if (existing == null) return true
        if (candidate.isBound && candidate.mate != null) {
            // A mate bound that proves no mate ("score mate 3 upperbound") never takes the place
            // of an exact score, whatever its depth.
            if (!candidate.provesMate && !existing.isBound) return false
            // One that does prove a mate ("score mate 3 lowerbound") is taken like any other
            // line, except over an exact mate: that one has the true distance and the full PV.
            if (existing.isExactMate) return false
        }
        if (candidate.depth < existing.depth) return false
        if (candidate.depth > existing.depth) return true
        // Prefer exact scores over lower/upper bounds at the same depth.
        if (candidate.isBound && !existing.isBound) return false
        // Bound lines often carry a truncated PV; keep the old one if the new one is empty.
        return candidate.pv.isNotEmpty() || existing.pv.isEmpty()
    }

    /**
     * [candidate] is about to replace [existing]. Fail-high / fail-low lines ("lowerbound" /
     * "upperbound") and lines cut short by the hash table often carry only the first move or
     * two of the line, and the search may end before the full line is printed again. If the new
     * PV is just the start of the one already known (or is missing), the known continuation is
     * kept: without it a forced mate arrives with a one-move PV and its later moves cannot be
     * shown.
     *
     * The continuation is only kept when both lines carry the same mate score (or neither has
     * one). The tail of a line that was not a mate, or was a mate of another length, is not the
     * mating line: appended to a "mate in 3" it would be shown as moves 2 and 3 of a mate that
     * they do not deliver.
     */
    private fun keepLongerPv(existing: InfoLine?, candidate: InfoLine): InfoLine {
        if (existing == null || existing.pv.size <= candidate.pv.size) return candidate
        if (existing.mate != candidate.mate) return candidate
        val isPrefix = existing.pv.subList(0, candidate.pv.size) == candidate.pv
        return if (isPrefix) candidate.copy(pv = existing.pv) else candidate
    }

    /** Keeps the latest exact score of the best line (multipv 1) for each depth, in depth order. */
    private fun recordBestLine(history: MutableList<InfoLine>, info: InfoLine) {
        if (info.multiPv != 1 || info.isBound) return
        val last = history.lastOrNull()
        when {
            last == null || info.depth > last.depth -> history.add(info)
            info.depth == last.depth -> history[history.size - 1] = info
            // A lower depth than the last one seen: stale or out of order, ignore.
        }
    }

    /**
     * Score range of the best line over its last [SWING_DEPTHS] depths, or null if that cannot
     * be told (too few depths or a mate score among them).
     */
    private fun evalSwing(history: List<InfoLine>): Int? {
        if (history.size < SWING_DEPTHS) return null
        val scores = history.takeLast(SWING_DEPTHS).map { it.cp ?: return null }
        return scores.max() - scores.min()
    }

    private fun parseInfo(line: String): InfoLine? {
        val tokens = line.split(WHITESPACE)
        var depth: Int? = null
        var multiPv = 1
        var cp: Int? = null
        var mate: Int? = null
        var bound = false
        var lowerBound = false
        var pv: List<String> = emptyList()

        var i = 1
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> {
                    depth = tokens.getOrNull(i + 1)?.toIntOrNull()
                    i += 2
                }
                "multipv" -> {
                    multiPv = tokens.getOrNull(i + 1)?.toIntOrNull() ?: 1
                    i += 2
                }
                "score" -> {
                    val kind = tokens.getOrNull(i + 1)
                    val value = tokens.getOrNull(i + 2)?.toIntOrNull()
                    when (kind) {
                        "cp" -> cp = value
                        "mate" -> mate = value
                    }
                    i += 3
                    val next = tokens.getOrNull(i)
                    if (next == "lowerbound" || next == "upperbound") {
                        bound = true
                        lowerBound = next == "lowerbound"
                        i++
                    }
                }
                "pv" -> {
                    pv = tokens.subList(i + 1, tokens.size).toList()
                    i = tokens.size
                }
                "string" -> {
                    // "info string ..." carries free text only.
                    return null
                }
                else -> i++
            }
        }

        val d = depth ?: return null
        if (cp == null && mate == null) return null
        return InfoLine(d, multiPv, cp, mate, bound, pv, lowerBound)
    }

    /**
     * Turns the stored lines into results, best line first.
     *
     * The bestmove is not always the first move of the multipv 1 line in [infos]: with several
     * search threads Stockfish may take its answer from a helper thread, whose line it prints
     * last and often at a lower depth than the one already stored (so it is not stored), and a
     * stopped search can fall back to an earlier move. A forced mate must survive that, but the
     * mate score must not be put on a move it was not found for. [byFirstMove] holds the latest
     * line of every first move, so in that case:
     *  - the bestmove's own line, if it is the newer one and proves a mate, is used whole;
     *  - otherwise, if the stored line proves a mate for the side to move, its mating move is
     *    returned instead of the bestmove: that mate is proven, the bestmove's is not;
     *  - a proven "getting mated" score is kept on the bestmove: the best line loses by force,
     *    so every move does.
     */
    private fun buildResults(
        bestMoveToken: String,
        infos: Map<Int, InfoLine>,
        bestLineHistory: List<InfoLine> = emptyList(),
        byFirstMove: Map<String, InfoLine> = emptyMap(),
    ): List<AnalysisResult> {
        val bestMove = if (bestMoveToken == "(none)" || bestMoveToken == "0000") "" else bestMoveToken

        if (infos.isEmpty()) {
            return listOf(AnalysisResult(bestMove, 0, null, 0, listOfNotNull(bestMove.ifEmpty { null })))
        }

        // Set when the bestmove is returned in place of the first move of the multipv 1 line:
        // that line then takes the place of the bestmove's own slot further down, so no move is
        // listed twice and the displaced one is not lost.
        var displaced: InfoLine? = null

        return infos.toSortedMap().map { (index, stored) ->
            val slot = displaced?.takeIf { index > 1 && stored.pv.firstOrNull() == bestMove } ?: stored
            var info = slot
            var move = if (index == 1) {
                bestMove.ifEmpty { slot.pv.firstOrNull().orEmpty() }
            } else {
                slot.pv.firstOrNull().orEmpty()
            }
            var pvAdjusted = slot.pv
            val slotMove = slot.pv.firstOrNull()
            val moveMismatch = index == 1 && bestMove.isNotEmpty() && slotMove != null && slotMove != bestMove
            // False when the mate score of [info] belongs to another move than the one returned.
            var mateIsForMove = true
            if (moveMismatch) {
                val own = byFirstMove[bestMove]?.takeIf { it.order > slot.order && it.provesMate }
                val slotMate = slot.mate?.takeIf { slot.provesMate }
                when {
                    own != null && (own.mate ?: 0) > 0 -> {
                        Log.d(TAG, "bestmove $bestMove is not the stored PV move $slotMove; using its own " +
                            "line (mate ${own.mate}, depth ${own.depth})")
                        info = own
                        pvAdjusted = own.pv
                    }
                    slotMate != null && slotMate > 0 && slotMove != null -> {
                        Log.w(TAG, "bestmove $bestMove has no mate line of its own; returning the proven " +
                            "mating move $slotMove (mate $slotMate) instead")
                        move = slotMove
                    }
                    own != null -> {
                        info = own
                        pvAdjusted = own.pv
                    }
                    slotMate != null -> pvAdjusted = listOf(bestMove)
                    else -> {
                        // No mate involved (or none proven): the arrow follows the bestmove. Its
                        // own line, when one was seen, gives it its own score and continuation;
                        // otherwise the PV is cut so that arrow and line stay consistent.
                        val ownLine = byFirstMove[bestMove]?.takeIf { it.mate == null }
                        if (ownLine != null) {
                            info = ownLine
                            pvAdjusted = ownLine.pv
                        } else {
                            pvAdjusted = listOf(bestMove)
                            mateIsForMove = false
                        }
                    }
                }
                if (move == bestMove) displaced = slot
            }
            val mate = info.mate?.takeIf { info.provesMate && mateIsForMove }
            val eval = when {
                mate != null -> mateToCp(mate)
                info.mate != null -> {
                    Log.w(TAG, "Unproven mate score dropped (mate ${info.mate}, bound=${info.isBound}, " +
                        "pv move ${info.pv.firstOrNull()}, bestmove $bestMove)")
                    if ((info.mate ?: 0) > 0) UNPROVEN_MATE_CP else -UNPROVEN_MATE_CP
                }
                else -> info.cp ?: 0
            }
            val pv = when {
                pvAdjusted.isNotEmpty() -> pvAdjusted
                move.isNotEmpty() -> listOf(move)
                else -> emptyList()
            }
            AnalysisResult(
                bestMove = move,
                eval = eval,
                mate = mate,
                depth = info.depth,
                pv = pv,
                evalSwing = if (index == 1) evalSwing(bestLineHistory) else null,
            )
        }
    }

    private fun mateToCp(mate: Int): Int = when {
        mate > 0 -> MATE_SCORE - mate
        mate < 0 -> -MATE_SCORE - mate
        else -> -MATE_SCORE // "mate 0": side to move is checkmated
    }

    /**
     * Polls [queue] until a line matching [predicate] arrives. Returns null on timeout,
     * on end of stream, or if the process is found dead while waiting.
     */
    private inline fun waitForLine(
        queue: LinkedBlockingQueue<String>,
        deadline: Long,
        predicate: (String) -> Boolean
    ): String? {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            val waitMs = minOf(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, POLL_INTERVAL_MS)
            val line = try {
                queue.poll(waitMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (line == null) {
                if (process?.isAlive != true && queue.isEmpty()) return null
                continue
            }
            if (line === EOF_MARKER) return null
            if (predicate(line)) return line
        }
    }

    private fun deadlineAfter(ms: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)

    private fun send(command: String): Boolean = synchronized(writeLock) {
        val w = writer ?: return false
        try {
            w.write(command)
            w.newLine()
            w.flush()
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Tears the process down if it died so the next call restarts it. */
    private fun markDeadIfNeeded() {
        if (process?.isAlive != true) {
            synchronized(stateLock) { shutdownProcess() }
        }
    }

    private fun shutdownProcess() {
        initialized = false
        synchronized(writeLock) {
            try {
                writer?.close()
            } catch (_: IOException) {
            }
            writer = null
        }
        process?.let { p ->
            p.destroy()
            try {
                if (!p.waitFor(DESTROY_WAIT_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            } catch (_: InterruptedException) {
                p.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        process = null
        readerThread?.interrupt()
        readerThread = null
        currentMultiPv = 1
    }
}
