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

        private const val TAG = "StockfishEngine"

        private const val ANALYSIS_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val READY_TIMEOUT_MS = 3_000L
        private const val STOP_DRAIN_TIMEOUT_MS = 1_000L
        private const val OPTION_TIMEOUT_MS = 1_000L
        private const val QUIT_WAIT_MS = 1_000L
        private const val DESTROY_WAIT_MS = 500L
        private const val POLL_INTERVAL_MS = 100L
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

            send("setoption name Threads value 4")
            send("setoption name Hash value 256")

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
     * search, or did not produce "bestmove" within 30 seconds.
     */
    fun analyze(fen: String, depth: Int = 12, moves: List<String> = emptyList()): AnalysisResult? =
        analyzeMultiPv(fen, depth, 1, moves)?.firstOrNull()

    /**
     * Same as [analyze] but asks the engine for [lineCount] principal variations.
     * The returned list is ordered best line first and may contain fewer than
     * [lineCount] entries when the position has fewer legal moves.
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
            val goCommand = if (moveTimeMs > 0) "go movetime $moveTimeMs" else "go depth $depth"
            if (!send(position) || !send(goCommand)) {
                markDeadIfNeeded()
                return null
            }

            val deadline = deadlineAfter(if (moveTimeMs > 0) moveTimeMs.toLong() + ANALYSIS_TIMEOUT_MS else ANALYSIS_TIMEOUT_MS)
            val infos = HashMap<Int, InfoLine>()
            // Exact scores of the best line, one per depth, to tell how settled the eval is.
            val bestLineHistory = ArrayList<InfoLine>()
            var bestMove: String? = null

            while (bestMove == null) {
                val line = waitForLine(queue, deadline) { true } ?: break
                if (line.startsWith("bestmove")) {
                    bestMove = line.split(WHITESPACE).getOrNull(1) ?: "(none)"
                } else if (line.startsWith("info ")) {
                    val info = parseInfo(line) ?: continue
                    recordBestLine(bestLineHistory, info)
                    val existing = infos[info.multiPv]
                    if (shouldReplace(existing, info)) infos[info.multiPv] = info
                }
            }

            if (bestMove == null) {
                if (process?.isAlive == true) {
                    send("stop")
                    val stopDeadline = deadlineAfter(STOP_DRAIN_TIMEOUT_MS)
                    while (bestMove == null) {
                        val stopLine = waitForLine(queue, stopDeadline) { true } ?: break
                        if (stopLine.startsWith("bestmove")) {
                            bestMove = stopLine.split(WHITESPACE).getOrNull(1) ?: "(none)"
                        } else if (stopLine.startsWith("info ")) {
                            val info = parseInfo(stopLine) ?: continue
                            recordBestLine(bestLineHistory, info)
                            val existing = infos[info.multiPv]
                            if (shouldReplace(existing, info)) infos[info.multiPv] = info
                        }
                    }
                }
                if (bestMove == null && infos.isNotEmpty()) {
                    bestMove = infos[1]?.pv?.firstOrNull() ?: infos.values.firstOrNull()?.pv?.firstOrNull()
                }
                if (bestMove == null) {
                    markDeadIfNeeded()
                    return null
                }
            }

            return buildResults(bestMove, infos, bestLineHistory)
        }
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
        val pv: List<String>
    )

    private fun shouldReplace(existing: InfoLine?, candidate: InfoLine): Boolean {
        if (existing == null) return true
        if (candidate.depth < existing.depth) return false
        if (candidate.depth > existing.depth) return true
        // Prefer exact scores over lower/upper bounds at the same depth.
        if (candidate.isBound && !existing.isBound) return false
        // Bound lines often carry a truncated PV; keep the old one if the new one is empty.
        return candidate.pv.isNotEmpty() || existing.pv.isEmpty()
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
        return InfoLine(d, multiPv, cp, mate, bound, pv)
    }

    private fun buildResults(
        bestMoveToken: String,
        infos: Map<Int, InfoLine>,
        bestLineHistory: List<InfoLine> = emptyList(),
    ): List<AnalysisResult> {
        val bestMove = if (bestMoveToken == "(none)" || bestMoveToken == "0000") "" else bestMoveToken

        if (infos.isEmpty()) {
            return listOf(AnalysisResult(bestMove, 0, null, 0, listOfNotNull(bestMove.ifEmpty { null })))
        }

        return infos.toSortedMap().map { (index, info) ->
            val eval = when {
                info.mate != null -> mateToCp(info.mate)
                else -> info.cp ?: 0
            }
            val move = if (index == 1) {
                bestMove.ifEmpty { info.pv.firstOrNull().orEmpty() }
            } else {
                info.pv.firstOrNull().orEmpty()
            }
            // After an interrupted search the bestmove token may not match the
            // PV's first move.  When they disagree, replace the PV so the
            // displayed arrow and eval stay consistent.
            val pvAdjusted = if (index == 1 && bestMove.isNotEmpty() &&
                info.pv.firstOrNull() != null && info.pv[0] != bestMove
            ) {
                listOf(bestMove)
            } else {
                info.pv
            }
            val pv = when {
                pvAdjusted.isNotEmpty() -> pvAdjusted
                move.isNotEmpty() -> listOf(move)
                else -> emptyList()
            }
            AnalysisResult(
                bestMove = move,
                eval = eval,
                mate = info.mate,
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
