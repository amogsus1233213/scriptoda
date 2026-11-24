package com.chesstrainer.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.chesstrainer.app.engine.AnalysisResult
import com.chesstrainer.app.engine.ClassifiedMove
import com.chesstrainer.app.engine.MoveClassifier
import com.chesstrainer.app.engine.MoveClass
import com.chesstrainer.app.engine.MoveCommentary
import com.chesstrainer.app.engine.PositionValidator
import com.chesstrainer.app.engine.StockfishEngine
import com.chesstrainer.app.service.AccessibilityData
import com.chesstrainer.app.service.FenConverter
import com.chesstrainer.app.ui.MoveColors
import com.chesstrainer.app.ui.OverlayService
import com.chesstrainer.app.ui.SuggestedLine
import com.chesstrainer.app.vision.BoardLocator
import com.chesstrainer.app.vision.Calibrator
import com.chesstrainer.app.vision.Cell
import com.chesstrainer.app.vision.CellReader
import com.chesstrainer.app.vision.ChessScreenReader
import com.chesstrainer.app.vision.Classifier
import com.chesstrainer.app.vision.Fen
import com.chesstrainer.app.vision.Grid
import com.chesstrainer.app.vision.Orientation
import com.chesstrainer.app.vision.Pixels
import com.chesstrainer.app.vision.TemplateFreeClassifier
import com.chesstrainer.app.vision.TemplateManager
import com.chesstrainer.app.vision.TemplateMatchingClassifier
import com.chesstrainer.app.vision.Templates
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Glue between screen capture, board recognition, the chess engine and the overlay.
 *
 * Pipeline per captured frame:
 *  1. [ScreenCaptureService] delivers a (reused) bitmap on its capture thread; it is copied and
 *     handed to a single worker thread. Frames that arrive while the worker is busy are dropped.
 *  2. The board is located and its squares classified. No setup is needed: the
 *     [TemplateFreeClassifier] reads any position from the first frame. If [Templates] exist
 *     (learnt silently whenever the starting position is on screen, or from an explicit
 *     [calibrate] call) they are tried first, as they are more accurate for the current theme.
 *  3. The piece placement must be read identically on [STABLE_FRAMES] consecutive frames before it
 *     is accepted (filters out piece animations and drags). A placement differing from the last
 *     successfully analysed one on more than [MAX_SQUARE_JUMP] squares needs [JUMP_STABLE_FRAMES]
 *     identical reads instead, as it is most likely a misread.
 *  4. A new placement is diffed against the previous one to recover the move that was played,
 *     which also tells whose turn it is now. The move is classified with [MoveClassifier] and
 *     the new position is analysed by Stockfish.
 *  5. On the user's turn the overlay shows the engine's best move, the classification of the
 *     opponent's last move and the evaluation. On the opponent's turn it shows feedback on the
 *     move the user just played (arrow of that move + its classification).
 *
 * All recognition and engine work runs on one worker thread; all game state below is confined to
 * that thread. Overlay calls are posted to the main thread.
 */
class AnalysisCoordinator(
    context: Context,
    private val depth: Int = DEFAULT_DEPTH,
) {

    companion object {
        private const val TAG = "AnalysisCoordinator"

        const val DEFAULT_DEPTH = 18

        /** Number of engine lines (Multi-PV) shown as suggestions. */
        private const val NUM_LINES = 3

        /** Time budget (ms) for the main multi-PV search; 0 means use depth only. */
        private const val SEARCH_TIME_MS = 5000

        /** At or below this many seconds on the user's clock, the search uses [FAST_MOVETIME_MS]. */
        private const val TIME_PRESSURE_THRESHOLD_SECONDS = 20

        /** Time budget (ms) for the main search when the user is in time pressure. */
        private const val FAST_MOVETIME_MS = 500

        /** Opponent move classes worth re-rendering the coach's commentary for. */
        private val COACH_REACTS_TO_OPPONENT =
            setOf(MoveClass.BLUNDER, MoveClass.MISTAKE, MoveClass.MISS, MoveClass.BRILLIANT)

        /** Time budget (ms) for analysing the previous position when classifying a played move. */
        private const val CLASSIFY_TIME_MS = 500

        /** Alternative lines worse than this (cp) are not drawn as arrows. */
        private const val QUALITY_CUTOFF_CP = 150

        /** Extra user moves of a forced mate drawn as numbered premove arrows (after move 1). */
        private const val MAX_MATE_PREMOVES = 4
        /** Extra user moves of an ordinary best line drawn as faint "plan" arrows. */
        private const val MAX_PLAN_PREMOVES = 2
        /** A plan is only shown from a search at least this deep... */
        private const val PLAN_MIN_DEPTH = 10
        /** ...whose score moved by no more than this over its last depths (a settled eval). */
        private const val PLAN_MAX_SWING_CP = 60
        /** Neutral color of the plan arrows of a non-mate line. */
        private val PLAN_COLOR = Color.parseColor("#E6E6E6")

        /**
         * Eval change (cp, white's POV) between two consecutive analyses of nearby positions
         * above which a warning is logged. No ordinary move swings the eval this far, so it is
         * either a mate appearing or disappearing, or the engine answering for another position.
         */
        private const val EVAL_SWING_WARN_CP = 5000

        /** A position analysed and shown less than this long ago is not searched again. */
        private const val STALE_ANALYSIS_MS = 2000L

        /**
         * The engine binary, shipped as `jniLibs/arm64-v8a/libstockfish.so`. Android 10+ refuses
         * to execute files from app-writable storage (targetSdk >= 29), so the binary must be run
         * from the native library directory, where the installer extracts it (legacy packaging).
         */
        private const val STOCKFISH_NATIVE_LIB = "libstockfish.so"

        private const val TEMPLATES_FILE = "board_templates.txt"

        /** Consecutive identical reads required before a placement is trusted. */
        private const val STABLE_FRAMES = 2

        /**
         * Unstable reads (differing from the stable placement) after which the most frequently
         * read placement is accepted by majority vote, tolerating noisy frames.
         */
        private const val MAX_FRAMES_BEFORE_MAJORITY = 6

        /**
         * Largest number of squares a vision read may differ from [lastKnownGoodFen] by and still
         * be accepted after the normal stability check. One move changes 2-4 squares, and a couple
         * of moves can be missed while the engine is busy, so this tolerates about two missed
         * moves. Bigger jumps are usually misreads (piece animations, popups over the board).
         */
        private const val MAX_SQUARE_JUMP = 8

        /**
         * Consecutive identical reads needed to accept a placement that jumped more than
         * [MAX_SQUARE_JUMP] squares (about 2 s at the default 2 fps). Real jumps (new game, another
         * game opened, many moves missed during a long search) persist and get through; transient
         * misreads do not. Without this, a real jump would be rejected forever.
         */
        private const val JUMP_STABLE_FRAMES = 4

        /**
         * Orientation hysteresis for the vision heuristic ([Orientation.score]). A frame whose
         * score contradicts the locked orientation adds 2 votes when the evidence is strong
         * (|score| >= [STRONG_ORIENTATION_SCORE]) and 1 when weak; an agreeing frame resets the
         * count; a frame with |score| < [MIN_ORIENTATION_SCORE] carries no evidence and neither
         * votes nor resets. The votes needed to switch depend on how the current lock was
         * obtained ([ORIENTATION_SWITCH_VOTES], indexed by [orientationConfidence]): a lock from a
         * single weak read is overturned by one strong contrary frame, a heuristic lock by 2 strong
         * (or 4 weak) frames, and a lock from hard evidence (starting position, accessibility data,
         * calibration) only by 4 strong frames in a row.
         */
        private val ORIENTATION_SWITCH_VOTES = intArrayOf(2, 4, 8)
        private const val STRONG_ORIENTATION_SCORE = 20f
        private const val MIN_ORIENTATION_SCORE = 3f

        /** Orientation provisionally taken from one weak read. */
        private const val CONFIDENCE_LOW = 0
        /** Orientation from strong heuristic evidence. */
        private const val CONFIDENCE_NORMAL = 1
        /** Orientation from hard evidence: starting position, accessibility, calibration. */
        private const val CONFIDENCE_HIGH = 2

        /**
         * Accessibility path: minimum lead (in agreement points, see [screenAgreement]) one
         * orientation must have over the other before the screen read decides the orientation.
         * The wrong orientation typically loses by 30+ points; a move in flight costs at most 8.
         */
        private const val CROSS_CHECK_MIN_MARGIN = 10

        /** Consecutive contrary cross-checks needed to overturn an orientation that is already set. */
        private const val CROSS_CHECK_SWITCH_FRAMES = 2

        /** Frames without a visible board after which the overlay suggestion is cleared. */
        private const val MISSED_FRAMES_BEFORE_CLEAR = 6

        /** Minimum change (screen fraction) before new board bounds are pushed to the overlay. */
        private const val BOUNDS_EPSILON = 0.004f

        private const val WORKER_KEEP_ALIVE_SECONDS = 30L

        /** Accessibility positions older than this are ignored in favour of vision. */
        private const val ACCESSIBILITY_MAX_AGE_MS = 2000L
        /** Longer timeout while the chess app is still visible (avoids vision reading our own arrows). */
        private const val ACCESSIBILITY_MAX_AGE_VISIBLE_MS = 30_000L

        /** Minimum number of accessibility-reported pieces for the data to be trusted. */
        private const val ACCESSIBILITY_MIN_PIECES = 3

        // User-facing status messages. Users should never need to know about "calibration".
        private const val STATUS_LOOKING = "Looking for chess board..."
        private const val STATUS_READING = "Board found — reading pieces..."
        private const val STATUS_RECOGNIZED = "Board recognized"
        private const val STATUS_ANALYZING = "Engine is calculating..."

        private const val START_PLACEMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
        private const val EMPTY = '.'
    }

    // -------------------------------------------------------------------------------------
    // Public callbacks
    // -------------------------------------------------------------------------------------

    /** Human-readable status updates (engine state, calibration, board found ...). Main thread. */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    /** Called on the main thread after each calibration attempt with its outcome. */
    @Volatile
    var calibrationListener: ((Boolean) -> Unit)? = null

    // -------------------------------------------------------------------------------------
    // Threading
    // -------------------------------------------------------------------------------------

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Single worker thread; it exits after a period of inactivity and is recreated on demand. */
    private val worker = ThreadPoolExecutor(
        1, 1, WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
    ) { r -> Thread(r, "analysis-coordinator").apply { isDaemon = true } }.apply {
        allowCoreThreadTimeOut(true)
    }

    /** Reference piece images for template matching; downloaded in the background. */
    private val templateManager = TemplateManager.getInstance(appContext)

    /** Highest-priority piece reader once [templateManager] is ready. Worker thread only. */
    private val templateMatcher = TemplateMatchingClassifier(templateManager)

    private val running = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val calibrationRequested = AtomicBoolean(false)

    private val frameListener = ScreenCaptureService.Companion.FrameListener { bitmap -> onFrame(bitmap) }

    @Volatile
    private var engine: StockfishEngine? = null

    @Volatile
    private var templates: Templates? = null

    // -------------------------------------------------------------------------------------
    // Worker-thread state
    // -------------------------------------------------------------------------------------

    private var templatesLoaded = false

    /** Last hint posted while no position could be read yet; used to avoid repeating it. */
    private var lastHintStatus: String? = null

    /**
     * Locked board orientation on screen (true = white at the bottom); null until the first board
     * was read. It is used both to turn vision reads into a FEN and to map engine moves to screen
     * squares, so it must always describe the real screen (also on the accessibility path, whose
     * FEN does not depend on it).
     */
    private var whiteAtBottom: Boolean? = null
    /**
     * Last value [whiteAtBottom] was set to. Used as a better fallback than hardcoded `true`
     * when orientation is temporarily unknown. Cleared by [resetGameState].
     */
    private var lastKnownOrientation: Boolean? = null
    private var orientationVotes = 0
    /** How [whiteAtBottom] was obtained: one of the CONFIDENCE_* constants. */
    private var orientationConfidence = CONFIDENCE_LOW
    /** Consecutive accessibility cross-checks contradicting [whiteAtBottom]. */
    private var crossCheckContrary = 0
    /** Accessibility placement whose cross-check last confirmed [whiteAtBottom]. */
    private var crossCheckedPlacement: String? = null
    /**
     * Counter for consecutive moves inferred as being from the same side. If this exceeds 2 it
     * suggests the orientation may be wrong (two white moves in a row without a black move, etc.).
     */
    private var consecutiveSameSideMoves = 0
    /** Side of the last inferred move (true = white). */
    private var lastInferredMoverWasWhite: Boolean? = null

    private var candidatePlacement: String? = null
    private var candidateCount = 0
    private var stablePlacement: String? = null
    private var candidateHistory = HashMap<String, Int>()
    private var candidateFramesSinceStable = 0
    private var missedFrames = 0
    private var boardVisible = false

    /** Position before [currentFen] and its analysis (mover = side to move in [previousFen]). */
    private var previousFen: String? = null
    private var previousAnalysis: AnalysisResult? = null
    private var currentFen: String? = null
    /** Multi-PV analysis of [currentFen], best line first. */
    private var currentAnalyses: List<AnalysisResult>? = null

    /**
     * Last FEN whose engine analysis succeeded. Vision reads that differ from it by more than
     * [MAX_SQUARE_JUMP] squares are treated as likely misreads (see [isLargeJump]).
     */
    private var lastKnownGoodFen: String? = null

    private var lastMove: InferredMove? = null
    private var lastClassified: ClassifiedMove? = null
    /** Classification of the user's own most recent move, for the coach's reaction to it. */
    private var lastUserClassified: ClassifiedMove? = null
    private var castlingRights = "KQkq"
    private var halfMoveClock = 0
    private var fullMoveNumber = 1

    /**
     * The position last drawn on the overlay ([positionKey]), when it was drawn
     * ([SystemClock.elapsedRealtime]) and the analyses it was drawn from. See [recentlyShown].
     */
    private var shownKey: String? = null
    private var shownAtMs = 0L
    private var shownAnalyses: List<AnalysisResult>? = null

    /** Placement and eval (white's POV) of the last main search, for [checkEvalSanity]. */
    private var lastSearchedPlacement: String? = null
    private var lastSearchedEvalWhite: Int? = null

    /** Whether the last position change found no side-to-move source in [AccessibilityData]. */
    private var turnSourcesUnavailable = false

    /**
     * Set when a new position has been detected while the previous analysis is still running.
     * The analysis loop checks this and returns early so stale results are never displayed.
     */
    @Volatile
    private var positionStale = false

    private var portraitBounds: RectF? = null
    private var landscapeBounds: RectF? = null

    /** Overlay instance that last received the board bounds. Main thread only. */
    private var boundsDeliveredTo: OverlayService? = null

    // -------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------

    /** Prepares the engine, loads saved templates and starts consuming screen frames. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        // Non-blocking: fetches/loads the piece templates on its own background thread.
        templateManager.ensureLoaded()
        submit {
            resetGameState()
            ensureTemplatesLoaded()
            initEngine()
        }
        ScreenCaptureService.listener = frameListener
    }

    /** Stops consuming frames, shuts the engine down and clears the overlay. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        if (ScreenCaptureService.listener === frameListener) {
            ScreenCaptureService.listener = null
        }
        calibrationRequested.set(false)
        // Interrupt a running search so the teardown below is not stuck behind it.
        engine?.stop()
        submit {
            engine?.quit()
            engine = null
            resetGameState()
        }
        mainHandler.post {
            OverlayService.instance?.clearSuggestion()
            boundsDeliveredTo = null
        }
    }

    fun isRunning(): Boolean = running.get()

    /**
     * True once optional recognition templates are available (learnt now or loaded from disk).
     * Recognition does not depend on this: without templates the template-free classifier is used.
     */
    fun isCalibrated(): Boolean = templates != null || File(appContext.filesDir, TEMPLATES_FILE).isFile

    // -------------------------------------------------------------------------------------
    // Optional templates (accuracy enhancement, never required)
    // -------------------------------------------------------------------------------------

    /**
     * Builds recognition templates from a screenshot of the starting position with white at the
     * bottom and persists them. Runs asynchronously; the outcome is reported to
     * [calibrationListener]. The bitmap is copied, so the caller may reuse it immediately.
     */
    fun calibrate(bitmap: Bitmap) {
        val copy = copyBitmap(bitmap)
        if (copy == null) {
            notifyCalibration(false, "Could not read the board: out of memory")
            return
        }
        if (!submit { try { calibrateOnWorker(copy) } finally { copy.recycle() } }) {
            copy.recycle()
            notifyCalibration(false, "Could not read the board right now")
        }
    }

    /** Calibrates from the next captured frame (requires [start] to have been called). */
    fun requestCalibration() {
        calibrationRequested.set(true)
        postStatus("Reading the board…")
    }

    /**
     * Deletes the stored templates. Recognition continues uninterrupted with the template-free
     * classifier; templates are re-learnt silently the next time the starting position is seen.
     */
    fun clearCalibration() {
        submit {
            templates = null
            templatesLoaded = true
            AtomicFile(File(appContext.filesDir, TEMPLATES_FILE)).delete()
            resetGameState()
            postOverlayClear()
            lastHintStatus = STATUS_LOOKING
            postStatus(STATUS_LOOKING)
        }
    }

    private fun calibrateOnWorker(bitmap: Bitmap) {
        val result = try {
            ChessScreenReader.calibrate(bitmap)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Template building crashed", e)
            null
        }
        if (result == null) {
            // Not an error for the user: recognition keeps working without templates.
            notifyCalibration(false, STATUS_LOOKING)
            return
        }
        templates = result
        templatesLoaded = true
        saveTemplates(result)
        resetGameState()
        // Templates are only built from the initial position with white at the bottom.
        whiteAtBottom = true
        orientationConfidence = CONFIDENCE_HIGH
        lastHintStatus = null
        notifyCalibration(true, STATUS_RECOGNIZED)
    }

    private fun ensureTemplatesLoaded() {
        if (templatesLoaded) return
        templatesLoaded = true
        if (templates != null) return
        val file = AtomicFile(File(appContext.filesDir, TEMPLATES_FILE))
        templates = try {
            Templates.deserialize(file.readFully().toString(Charsets.UTF_8))
        } catch (e: IOException) {
            null
        }
        lastHintStatus = STATUS_LOOKING
        postStatus(STATUS_LOOKING)
    }

    private fun saveTemplates(t: Templates) {
        val file = AtomicFile(File(appContext.filesDir, TEMPLATES_FILE))
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(t.serialize().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            Log.e(TAG, "Could not save templates", e)
            if (out != null) file.failWrite(out)
        }
    }

    // -------------------------------------------------------------------------------------
    // Engine
    // -------------------------------------------------------------------------------------

    private fun initEngine() {
        if (engine != null) return
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val binary = nativeDir?.let { File(it, STOCKFISH_NATIVE_LIB) }
        if (binary == null || !binary.exists() || !binary.canExecute()) {
            Log.e(TAG, "Stockfish binary not found or not executable in $nativeDir")
            postStatus("Stockfish binary not found")
            return
        }
        val e = StockfishEngine(binary.absolutePath)
        engine = e
        postStatus(if (e.start()) "Engine ready" else "Engine failed to start")
    }

    private fun analyze(fen: String): AnalysisResult? {
        val e = engine ?: return null
        if (!running.get()) return null
        if (!isAnalyzable(fen)) return null
        val result = try {
            e.analyze(fen, depth)
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Analysis failed for $fen", ex)
            null
        }
        return validatedAnalyses(fen, result?.let { listOf(it) })?.firstOrNull()
    }

    /**
     * False (and logged) only when [fen] cannot be searched at all: it is malformed
     * ([PositionValidator.fenError]). Skipping a position leaves the user without arrows, so
     * anything that is merely suspicious is logged and analysed anyway. That includes the side
     * not to move being in check (a wrongly tracked side to move): [resyncTo] corrects it where
     * it can, and an engine answer that takes the king is dropped by [validatedAnalyses].
     */
    private fun isAnalyzable(fen: String): Boolean {
        val error = PositionValidator.fenError(fen)
        if (error != null) {
            Log.w(TAG, "Skipping analysis, invalid FEN ($error): $fen")
            return false
        }
        if (PositionValidator.sideNotToMoveInCheck(fen)) {
            Log.w(TAG, "Side not to move is in check (wrong side to move?), analysing anyway: $fen")
        }
        return true
    }

    /**
     * Checks the engine's moves against the position they were asked for
     * ([PositionValidator.bestMoveError]). A bad top move means the answer does not belong to
     * [fen] (UCI desync), so the whole result is dropped and nothing is rendered; a bad
     * alternative line is dropped on its own. An empty best move (no legal move: mate or
     * stalemate) is a valid answer and passes through.
     */
    private fun validatedAnalyses(fen: String, analyses: List<AnalysisResult>?): List<AnalysisResult>? {
        if (analyses.isNullOrEmpty()) return analyses
        val errors = analyses.map { result ->
            result.bestMove.takeIf { it.isNotEmpty() }?.let { PositionValidator.bestMoveError(fen, it) }
        }
        if (errors.all { it == null }) return analyses
        errors[0]?.let { error ->
            Log.w(TAG, "Discarding analysis, bestmove '${analyses[0].bestMove}' does not fit the " +
                "position ($error): $fen")
            return null
        }
        return analyses.filterIndexed { index, result ->
            val error = errors[index]
            if (error != null) {
                Log.w(TAG, "Dropping line ${index + 1}, move '${result.bestMove}' does not fit the " +
                    "position ($error): $fen")
            }
            error == null
        }
    }

    /**
     * Logs a warning when the eval of [best] (the main search of [fen]) is more than
     * [EVAL_SWING_WARN_CP] away from the previous main search, provided the two positions are
     * close ([MAX_SQUARE_JUMP]) and this is not the start of a new game. A swing that size with
     * no mate score involved cannot come from one or two moves and points at a UCI desync.
     */
    private fun checkEvalSanity(fen: String, best: AnalysisResult?) {
        if (best == null) return
        val placement = fen.substringBefore(' ')
        val evalWhite = if (sideToMoveIsWhite(fen)) best.eval else -best.eval
        val previousPlacement = lastSearchedPlacement
        val previousEval = lastSearchedEvalWhite
        lastSearchedPlacement = placement
        lastSearchedEvalWhite = evalWhite
        if (previousPlacement == null || previousEval == null) return
        if (placement == START_PLACEMENT) return
        val distance = squaresDiffering(previousPlacement, placement) ?: return
        if (distance > MAX_SQUARE_JUMP) return
        val swing = abs(evalWhite - previousEval)
        if (swing <= EVAL_SWING_WARN_CP) return
        val mateInvolved = best.mate != null || abs(previousEval) >= StockfishEngine.MATE_SCORE - 1000
        Log.w(TAG, "Eval swing of $swing cp between consecutive analyses ($previousEval -> " +
            "$evalWhite, white's POV, $distance squares changed): " +
            (if (mateInvolved) "a forced mate appeared or disappeared" else "possible UCI desync") +
            ". FEN: $fen")
    }

    /** Number of squares on which two FEN placements differ, or null if either is malformed. */
    private fun squaresDiffering(a: String, b: String): Int? {
        val first = parsePlacement(a) ?: return null
        val second = parsePlacement(b) ?: return null
        return (0 until 64).count { first[it] != second[it] }
    }

    /** Identity of a position for [recentlyShown]: its FEN without the move counters. */
    private fun positionKey(fen: String): String = fenFields(fen).take(4).joinToString(" ")

    /**
     * The analyses of [fen] if that exact position was analysed and drawn on the overlay less
     * than [STALE_ANALYSIS_MS] ago, otherwise null. Used to answer a position that comes back
     * right away (duplicate or flickering accessibility events, a one-frame misread) without
     * another search, which would blank the arrows behind "thinking" for its whole duration.
     */
    private fun recentlyShown(fen: String): List<AnalysisResult>? {
        val analyses = shownAnalyses ?: return null
        if (shownKey != positionKey(fen)) return null
        val age = SystemClock.elapsedRealtime() - shownAtMs
        if (age < 0 || age >= STALE_ANALYSIS_MS) return null
        Log.d(TAG, "Position was analysed and shown $age ms ago, reusing that analysis: $fen")
        return analyses
    }

    /**
     * Logs when neither [AccessibilityData.whiteToMove] nor [AccessibilityData.trackedFen] can
     * tell whose turn it is (once per change, not per position). The side to move then comes
     * from move inference, or from [resyncSideToMove] when no move can be inferred.
     */
    private fun checkTurnTrackingSources() {
        val unavailable = AccessibilityData.whiteToMove == null && AccessibilityData.trackedFen == null
        if (unavailable == turnSourcesUnavailable) return
        turnSourcesUnavailable = unavailable
        if (unavailable) {
            Log.w(TAG, "Turn tracking: neither the move list nor the tracked FEN is available; " +
                "the side to move comes from move inference or the last known state")
        } else {
            Log.d(TAG, "Turn tracking: accessibility side-to-move source available again")
        }
    }

    /**
     * Side to move for a position reached without a recognisable move ([resyncTo]). In order:
     * the move list, the tracked FEN if it describes this very placement, then the last known
     * state: the side to move of [prevFen] when the board is still close to it (an unexplained
     * small change is most often two plies seen as one, which leaves the same side to move).
     * Only when there is no usable previous state is it assumed to be the user's turn.
     */
    private fun resyncSideToMove(placement: String, prevFen: String?, userIsWhite: Boolean): Boolean {
        AccessibilityData.whiteToMove?.let { return it }
        val tracked = AccessibilityData.trackedFen
        if (tracked != null && tracked.substringBefore(' ') == placement) {
            return sideToMoveIsWhite(tracked)
        }
        if (prevFen != null) {
            val distance = squaresDiffering(prevFen.substringBefore(' '), placement)
            if (distance != null && distance <= MAX_SQUARE_JUMP) {
                val white = sideToMoveIsWhite(prevFen)
                Log.d(TAG, "No side-to-move source for resync; keeping last known side " +
                    "(${if (white) "white" else "black"} to move, $distance squares changed)")
                return white
            }
        }
        Log.d(TAG, "No side-to-move source and no usable previous position; assuming the user's turn")
        return userIsWhite
    }

    /**
     * Time budget for the main search: [FAST_MOVETIME_MS] when the user's clock
     * ([AccessibilityData.userTimeSeconds]) is known and at or below
     * [TIME_PRESSURE_THRESHOLD_SECONDS], otherwise [SEARCH_TIME_MS].
     */
    private fun searchTimeMs(): Int {
        val seconds = AccessibilityData.userTimeSeconds
        if (seconds != null && seconds <= TIME_PRESSURE_THRESHOLD_SECONDS) {
            Log.d(TAG, "Time pressure (${seconds}s left): searching for $FAST_MOVETIME_MS ms")
            return FAST_MOVETIME_MS
        }
        return SEARCH_TIME_MS
    }

    /**
     * Analyses [fen] with [NUM_LINES] principal variations, ordered best first, searching for
     * [searchMs] (the same budget the overlay was told to count down from).
     */
    private fun analyzeMulti(fen: String, searchMs: Int = searchTimeMs()): List<AnalysisResult>? {
        val e = engine ?: return null
        if (!running.get()) return null
        if (!isAnalyzable(fen)) return null
        val raw = try {
            e.analyzeMultiPv(fen, depth, NUM_LINES, moveTimeMs = searchMs)
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Multi-PV analysis failed for $fen", ex)
            null
        }
        if (raw.isNullOrEmpty()) Log.w(TAG, "Engine returned no analysis (ready=${e.isReady()}) for $fen")
        val analyses = validatedAnalyses(fen, raw)
        checkEvalSanity(fen, analyses?.firstOrNull())
        return analyses
    }

    // -------------------------------------------------------------------------------------
    // Frame intake (capture thread)
    // -------------------------------------------------------------------------------------

    private fun onFrame(bitmap: Bitmap) {
        if (!running.get()) return

        if (calibrationRequested.getAndSet(false)) {
            calibrate(bitmap)
            return
        }

        if (!processing.compareAndSet(false, true)) return

        // Check if accessibility data is available (higher accuracy than vision). When it is
        // fresh and complete enough, piece recognition is skipped for this frame; the frame is
        // still used to locate the board so the overlay has bounds to draw arrows in.
        val accessibilityPositions = freshAccessibilityPositions()
        if (accessibilityPositions != null) {
            // The accessibility squares are absolute, so the screen orientation (needed to draw
            // the arrows) must come from elsewhere: the accessibility service when it can tell,
            // otherwise a cross-check against this frame (see processAccessibilityPositions).
            val reportedOrientation = AccessibilityData.userPlaysWhite
            // A failed copy only costs the bounds refresh; previously known bounds are reused.
            val frame = copyBitmap(bitmap)
            val accepted = submit {
                try {
                    val board = if (frame != null) updateBoundsFromFrame(frame) else null
                    processAccessibilityPositions(accessibilityPositions, reportedOrientation, board)
                } finally {
                    frame?.recycle()
                    processing.set(false)
                }
            }
            if (!accepted) {
                frame?.recycle()
                processing.set(false)
            }
            return
        }

        val copy = copyBitmap(bitmap)
        if (copy == null) {
            processing.set(false)
            return
        }
        val accepted = submit {
            try {
                processFrame(copy)
            } finally {
                copy.recycle()
                processing.set(false)
            }
        }
        if (!accepted) {
            copy.recycle()
            processing.set(false)
        }
    }

    private fun copyBitmap(bitmap: Bitmap): Bitmap? = try {
        if (bitmap.isRecycled) null else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory copying frame")
        null
    }

    /** Runs [task] on the worker thread, logging failures. Returns false if it was rejected. */
    private fun submit(task: () -> Unit): Boolean = try {
        worker.execute {
            try {
                task()
            } catch (t: Exception) {
                Log.e(TAG, "Worker task failed", t)
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    // -------------------------------------------------------------------------------------
    // Frame processing (worker thread)
    // -------------------------------------------------------------------------------------

    private fun processFrame(bitmap: Bitmap) {
        if (!running.get()) return
        ensureTemplatesLoaded()

        val px = Pixels(bitmap)
        val grid = BoardLocator.locate(px)
        if (grid == null) {
            onBoardMissing()
            if (!boardVisible) postHintStatus(STATUS_LOOKING)
            return
        }

        // The board is on screen: keep the overlay aligned even if this frame's pieces are
        // unreadable (e.g. mid-animation).
        missedFrames = 0
        updateBoardBounds(grid, px, bitmap)

        val cells = CellReader.read(px, grid)
        // Optional accuracy boost: silently learn templates when the starting position shows up.
        if (templates == null) learnTemplatesFromStartPosition(cells)

        val letters = readLetters(px, grid, cells)
        if (letters == null) {
            if (!boardVisible) postHintStatus(STATUS_READING)
            return
        }

        if (!boardVisible) {
            boardVisible = true
            lastHintStatus = null
            postStatus(STATUS_ANALYZING)
        }

        val bottomIsWhite = resolveOrientation(letters)
        val placement = Fen.build(letters, bottomIsWhite).substringBefore(' ')

        if (placement == candidatePlacement) {
            candidateCount++
        } else {
            candidatePlacement = placement
            candidateCount = 1
        }

        // Track all readings since last stable position for majority vote
        if (placement != stablePlacement) {
            candidateFramesSinceStable++
            candidateHistory[placement] = (candidateHistory[placement] ?: 0) + 1
        }

        // candidatePlacement == placement here, so candidateCount counts consecutive reads of it.
        val finalPlacement = when {
            // Normal path: two identical reads in a row
            candidateCount >= STABLE_FRAMES && placement != stablePlacement -> placement
            // Majority vote: after MAX_FRAMES_BEFORE_MAJORITY frames, pick the most-read position
            candidateFramesSinceStable >= MAX_FRAMES_BEFORE_MAJORITY && candidateHistory.isNotEmpty() -> {
                val best = candidateHistory.maxByOrNull { it.value }
                if (best != null && best.key != stablePlacement && best.value >= 2) best.key else null
            }
            else -> null
        } ?: return

        // A large jump from the last analysed position is likely a misread: accept it only once
        // it is on screen now and was read identically on JUMP_STABLE_FRAMES frames in a row.
        if (isLargeJump(finalPlacement)) {
            if (finalPlacement != placement || candidateCount < JUMP_STABLE_FRAMES) {
                Log.w(TAG, "Holding back position far from last known good (likely misread): $finalPlacement")
                // Restart the majority vote so stale history does not re-pick it every frame.
                candidateHistory.clear()
                candidateFramesSinceStable = 0
                return
            }
            Log.i(TAG, "Accepting large position change after $candidateCount stable reads")
        }

        stablePlacement = finalPlacement
        candidateHistory.clear()
        candidateFramesSinceStable = 0
        onPositionChanged(finalPlacement, fromAccessibility = false)
    }

    /**
     * Returns a snapshot of the accessibility-provided piece positions if they are recent
     * (< [ACCESSIBILITY_MAX_AGE_MS]) and contain at least [ACCESSIBILITY_MIN_PIECES] pieces,
     * otherwise null (the vision pipeline is used instead).
     */
    private fun freshAccessibilityPositions(): Map<String, Char>? {
        val maxAge = if (AccessibilityData.targetPackageVisible) ACCESSIBILITY_MAX_AGE_VISIBLE_MS
                     else ACCESSIBILITY_MAX_AGE_MS
        if (!AccessibilityData.isRecent(maxAge)) return null
        val positions = AccessibilityData.lastStablePositions ?: AccessibilityData.detectedPositions ?: return null
        return if (positions.size >= ACCESSIBILITY_MIN_PIECES) positions else null
    }

    /** Board located in an accessibility-path frame (used for the orientation cross-check). */
    private class ScreenBoard(val px: Pixels, val grid: Grid)

    /**
     * Locates the board in [bitmap] to refresh the overlay bounds. Used on the accessibility
     * path, where the position comes from accessibility data but the arrows still need to know
     * where the board is on screen. If the board cannot be located in this frame, the last known
     * bounds are kept and null is returned.
     */
    private fun updateBoundsFromFrame(bitmap: Bitmap): ScreenBoard? {
        if (!running.get()) return null
        val px = Pixels(bitmap)
        val grid = try {
            BoardLocator.locate(px)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Board location failed on accessibility frame", e)
            null
        } ?: return null
        updateBoardBounds(grid, px, bitmap)
        return ScreenBoard(px, grid)
    }

    /**
     * Accessibility counterpart of [processFrame]: converts the reported positions to a FEN and
     * feeds its placement into the same move-tracking path ([onPositionChanged]) that sets
     * [currentFen] for vision reads. Accessibility data is exact, so no multi-frame stability
     * check is needed; an unchanged placement is simply ignored.
     *
     * The accessibility squares are absolute, so the FEN is right whatever the orientation, but
     * the arrows are mapped to the screen with [whiteAtBottom]. That is therefore updated here
     * from [reportedOrientation] (accessibility service) or, failing that, by cross-checking the
     * positions against the located [board].
     */
    private fun processAccessibilityPositions(
        positions: Map<String, Char>,
        reportedOrientation: Boolean?,
        board: ScreenBoard?,
    ) {
        if (!running.get()) return
        ensureTemplatesLoaded()

        // When the accessibility service has move tracking active, it produces a full FEN with
        // correct side-to-move, castling rights, and en passant. Prefer it over rebuilding a
        // lossy FEN from the position map.
        val trackedFen = AccessibilityData.trackedFen
        val fen: String
        if (trackedFen != null && AccessibilityData.isRecent()) {
            fen = trackedFen
            Log.d(TAG, "Using tracked FEN from accessibility service: $fen")
        } else {
            // Side to move: keep the tracked side if known, otherwise default to white. The actual
            // mover is recovered by move inference in onPositionChanged once a move is diffed.
            val whiteToMove = AccessibilityData.whiteToMove
                ?: currentFen?.let { sideToMoveIsWhite(it) }
                ?: true
            fen = try {
                FenConverter.positionsToFen(positions, whiteToMove)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not convert accessibility positions", e)
                return
            }
            Log.d(TAG, "Using accessibility data: ${positions.size} pieces, FEN: $fen")
        }

        val placement = fen.substringBefore(' ')
        if (parsePlacement(placement) == null) {
            Log.w(TAG, "Ignoring malformed accessibility placement: $placement")
            return
        }

        missedFrames = 0
        if (!boardVisible) {
            boardVisible = true
            lastHintStatus = null
            postStatus(STATUS_ANALYZING)
        }

        // May reset tracking (stablePlacement = null), which makes the code below resync and
        // redraw the arrows with the corrected orientation.
        resolveAccessibilityOrientation(placement, positions, reportedOrientation, board)

        if (placement == stablePlacement) return
        candidatePlacement = placement
        candidateCount = STABLE_FRAMES
        candidateHistory.clear()
        candidateFramesSinceStable = 0
        stablePlacement = placement
        onPositionChanged(placement, fromAccessibility = true)
    }

    /**
     * Keeps [whiteAtBottom] in line with the real screen while the position comes from
     * accessibility data. Without this the orientation stayed whatever vision last guessed, or
     * null (drawn as white at the bottom), so when playing black every arrow was drawn rotated by
     * 180 degrees (e.g. a knight move starting on a bishop).
     *
     * Order of evidence: the orientation reported by the accessibility service, then a cross-check
     * of [positions] against the pieces visible on [board] ([orientationFromScreen]). The
     * cross-check is skipped for a placement it already confirmed, as the orientation only
     * changes together with the position (new game) in practice.
     */
    private fun resolveAccessibilityOrientation(
        placement: String,
        positions: Map<String, Char>,
        reportedOrientation: Boolean?,
        board: ScreenBoard?,
    ) {
        if (reportedOrientation != null) {
            crossCheckContrary = 0
            setOrientation(reportedOrientation, CONFIDENCE_HIGH, "accessibility service", absolute = true)
            return
        }
        if (board == null) return
        if (placement == crossCheckedPlacement && orientationConfidence == CONFIDENCE_HIGH) return

        val seen = orientationFromScreen(board, positions) ?: return
        val locked = whiteAtBottom
        if (locked != null && seen != locked && orientationConfidence == CONFIDENCE_HIGH) {
            // A confirmed orientation needs the contrary evidence on consecutive frames.
            crossCheckContrary++
            if (crossCheckContrary < CROSS_CHECK_SWITCH_FRAMES) return
        }
        crossCheckContrary = 0
        setOrientation(seen, CONFIDENCE_HIGH, "screen cross-check of accessibility data", absolute = true)
        crossCheckedPlacement = placement
    }

    /**
     * Decides the screen orientation by laying the (absolute) accessibility [positions] onto the
     * located board both ways and keeping the layout that matches what the frame shows. Only
     * occupancy and piece colour are compared, which the template-free classifier reads reliably;
     * piece types do not matter. Returns null when the frame cannot tell (no pieces read, or both
     * layouts fit about equally well, e.g. with very few pieces left).
     */
    private fun orientationFromScreen(board: ScreenBoard, positions: Map<String, Char>): Boolean? {
        val letters = try {
            TemplateFreeClassifier.classify(CellReader.read(board.px, board.grid))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Orientation cross-check failed", e)
            return null
        }
        val lead = screenAgreement(letters, positions, true) - screenAgreement(letters, positions, false)
        Log.d(TAG, "Orientation cross-check lead (white at bottom minus black at bottom): $lead")
        return when {
            lead >= CROSS_CHECK_MIN_MARGIN -> true
            lead <= -CROSS_CHECK_MIN_MARGIN -> false
            else -> null
        }
    }

    /**
     * How well screen-ordered [letters] match absolute [positions] if the board is shown with
     * white at the bottom ([bottomIsWhite]) or black at the bottom: one point per square whose
     * occupancy agrees and one more per square showing a piece of the same colour in both.
     */
    private fun screenAgreement(letters: Array<CharArray>, positions: Map<String, Char>, bottomIsWhite: Boolean): Int {
        var points = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            // Screen row 0 is the top. Same mapping as Fen.build and the overlay's squareCenter.
            val square = if (bottomIsWhite) "${'a' + c}${8 - r}" else "${'h' - c}${r + 1}"
            val seen = letters[r][c]
            val reported = positions[square]
            if ((seen == EMPTY) == (reported == null)) points++
            if (seen != EMPTY && reported != null && seen.isUpperCase() == reported.isUpperCase()) points++
        }
        return points
    }

    /**
     * Reads the piece letters of a frame. Order of preference:
     *  1. Template matching against the downloaded "neo" pieces (once available): confident
     *     squares come from it, uncertain ones from the template-free classifier, and the
     *     board-level sanity checks run over the combination.
     *  2. Learnt templates (if any), as they match the exact theme.
     *  3. The template-free classifier alone.
     * A stage that gives an impossible position is skipped.
     *
     * @return screen-ordered letters of a plausible position, or null if none could be read.
     */
    private fun readLetters(px: Pixels, grid: Grid, cells: List<Cell>): Array<CharArray>? {
        if (templateMatcher.isReady) {
            val hybrid = try {
                templateMatcher.classify(px, grid)?.let { TemplateFreeClassifier.classify(cells, it) }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Template matching failed", e)
                null
            }
            if (hybrid != null && isPlausible(hybrid)) return hybrid
        }
        val tpl = templates
        if (tpl != null) {
            val byTemplate = try {
                Classifier.classify(cells, tpl)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Template classification failed", e)
                null
            }
            if (byTemplate != null && isPlausible(byTemplate)) return byTemplate
        }
        val free = try {
            TemplateFreeClassifier.classify(cells)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Template-free classification failed", e)
            null
        }
        return if (free != null && isPlausible(free)) free else null
    }

    /**
     * Opportunistically builds templates when the frame shows the starting position
     * ([Calibrator.fromCells] validates this), with white at the bottom or, failing that, with
     * black at the bottom (the cells are then read in reverse order, which maps each screen
     * square onto the matching square of a white-at-bottom starting position and preserves the
     * square colours). Never required and never reported to the user: recognition already works
     * without templates, this only improves accuracy for the current theme.
     */
    private fun learnTemplatesFromStartPosition(cells: List<Cell>) {
        var bottomIsWhite = true
        val result = try {
            Calibrator.fromCells(cells)
                ?: Calibrator.fromCells(cells.asReversed())?.also { bottomIsWhite = false }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Template learning crashed", e)
            null
        }
        if (result == null) return

        templates = result
        templatesLoaded = true
        saveTemplates(result)
        // The starting position fixes the orientation; game tracking resyncs on its own when the
        // start placement is read below. Placements tracked with the other orientation are void
        // (setOrientation resets them).
        setOrientation(bottomIsWhite, CONFIDENCE_HIGH, "starting position (template learning)")
        Log.i(TAG, "Learnt piece templates from the starting position")
    }

    /** Posts a hint status, skipping repeats so the status is not spammed per frame. */
    private fun postHintStatus(message: String) {
        if (message == lastHintStatus) return
        lastHintStatus = message
        postStatus(message)
    }

    private fun onBoardMissing() {
        candidatePlacement = null
        candidateCount = 0
        candidateHistory.clear()
        candidateFramesSinceStable = 0
        missedFrames++
        if (boardVisible && missedFrames >= MISSED_FRAMES_BEFORE_CLEAR) {
            boardVisible = false
            postOverlayClear()
            lastHintStatus = STATUS_LOOKING
            postStatus(STATUS_LOOKING)
        }
    }

    /**
     * True if [placement] differs from [lastKnownGoodFen] on more than [MAX_SQUARE_JUMP] squares.
     * The starting position never counts as a jump: it is unambiguous and signals a new game.
     */
    private fun isLargeJump(placement: String): Boolean {
        if (placement == START_PLACEMENT) return false
        val last = lastKnownGoodFen?.let { parsePlacement(it.substringBefore(' ')) } ?: return false
        val now = parsePlacement(placement) ?: return false
        val diff = (0 until 64).count { now[it] != last[it] }
        if (diff > MAX_SQUARE_JUMP) Log.d(TAG, "$diff squares differ from last known good position")
        return diff > MAX_SQUARE_JUMP
    }

    /** Rejects reads that cannot be a legal chess position. */
    private fun isPlausible(letters: Array<CharArray>): Boolean {
        var whiteKings = 0
        var blackKings = 0
        var whitePawns = 0
        var blackPawns = 0
        var whitePieces = 0
        var blackPieces = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            val ch = letters[r][c]
            if (ch == EMPTY) continue
            if (ch.isUpperCase()) whitePieces++ else blackPieces++
            when (ch) {
                'K' -> whiteKings++
                'k' -> blackKings++
                'P' -> whitePawns++
                'p' -> blackPawns++
            }
            // The top and bottom screen rows are the first and last ranks in either orientation.
            if ((ch == 'P' || ch == 'p') && (r == 0 || r == 7)) return false
        }
        return whiteKings == 1 && blackKings == 1 &&
            whitePawns <= 8 && blackPawns <= 8 &&
            whitePieces <= 16 && blackPieces <= 16
    }

    /**
     * Returns the board orientation, re-checked on every read frame. A wrong orientation rotates
     * the FEN by 180 degrees, so the engine's moves come out mirrored on screen (e.g. a pawn
     * "moving backwards"); it must therefore be corrected quickly, yet one noisy frame must not
     * flip it. See [ORIENTATION_SWITCH_VOTES]. The starting position fixes it immediately.
     *
     * When the accessibility service reports which side the user plays
     * ([AccessibilityData.userPlaysWhite]), that is authoritative and vision-based detection is
     * skipped. An orientation established from accessibility data while it was available (see
     * [resolveAccessibilityOrientation]) carries over with high confidence.
     *
     * When the orientation changes, all placements tracked so far were built with the wrong
     * orientation, so move tracking is reset and resyncs from the next stable read.
     */
    private fun resolveOrientation(letters: Array<CharArray>): Boolean {
        // Trust the accessibility service's orientation when available.
        AccessibilityData.userPlaysWhite?.let { accessWhite ->
            setOrientation(accessWhite, CONFIDENCE_HIGH, "accessibility service")
            return accessWhite
        }

        val score = Orientation.score(letters)
        val detected = score >= 0f
        val strong = abs(score) >= STRONG_ORIENTATION_SCORE
        val locked = whiteAtBottom
        if (locked == null) {
            // First read: lock it, but a weak read only provisionally (overturned quickly).
            setOrientation(detected, if (strong) CONFIDENCE_NORMAL else CONFIDENCE_LOW, "first read (score $score)")
            return detected
        }
        // The starting position settles the orientation at once.
        if (Fen.build(letters, detected).substringBefore(' ') == START_PLACEMENT) {
            setOrientation(detected, CONFIDENCE_HIGH, "starting position")
            return detected
        }
        // A (near) zero score is no evidence either way: it must neither vote nor reset the votes,
        // or a wrong lock could survive forever in a balanced position.
        if (abs(score) < MIN_ORIENTATION_SCORE) return locked
        if (detected == locked) {
            orientationVotes = 0
            if (strong && orientationConfidence < CONFIDENCE_NORMAL) orientationConfidence = CONFIDENCE_NORMAL
            return locked
        }
        orientationVotes += if (strong) 2 else 1
        if (orientationVotes >= ORIENTATION_SWITCH_VOTES[orientationConfidence]) {
            setOrientation(detected, if (strong) CONFIDENCE_NORMAL else CONFIDENCE_LOW, "vision (score $score)")
            return detected
        }
        return locked
    }

    /**
     * Locks the board orientation with the given confidence (CONFIDENCE_*). If the orientation
     * used for drawing changes (an unset orientation is drawn as white at the bottom, see
     * [render]), everything tracked so far is void: vision placements were built with the other
     * orientation and the arrows on screen are drawn rotated. Tracking is then reset (it resyncs
     * from the next read) and the overlay cleared at once.
     */
    private fun setOrientation(bottomIsWhite: Boolean, confidence: Int, reason: String, absolute: Boolean = false) {
        val drawnBefore = whiteAtBottom ?: lastKnownOrientation ?: true
        val wasUnset = whiteAtBottom == null
        whiteAtBottom = bottomIsWhite
        lastKnownOrientation = bottomIsWhite
        orientationConfidence = confidence
        orientationVotes = 0
        if (drawnBefore != bottomIsWhite) {
            Log.i(TAG, "Board orientation changed: white at bottom = $bottomIsWhite ($reason)")
            crossCheckContrary = 0
            crossCheckedPlacement = null
            if (absolute) {
                // Accessibility placements are absolute (not rotated), so the tracked position is
                // still valid, but the analyses were computed assuming the other side is the
                // user's. Drop them and force a re-analysis of the current placement.
                currentAnalyses = null
                stablePlacement = null
                postOverlayClear()
            } else {
                resetTracking()
                postOverlayClear()
            }
        } else if (wasUnset) {
            Log.i(TAG, "Board orientation: white at bottom = $bottomIsWhite ($reason)")
        }
    }

    /** Forgets all tracked placements and positions (but not the orientation). */
    private fun resetTracking() {
        candidatePlacement = null
        candidateCount = 0
        stablePlacement = null
        candidateHistory.clear()
        candidateFramesSinceStable = 0
        previousFen = null
        previousAnalysis = null
        currentFen = null
        currentAnalyses = null
        // Placements read with another orientation are rotated, so they must not be compared.
        lastKnownGoodFen = null
        lastMove = null
        lastClassified = null
        lastUserClassified = null
        postClearCoachMessage()
        castlingRights = "KQkq"
        halfMoveClock = 0
        fullMoveNumber = 1
        // Analyses of the old tracking state must be neither reused nor compared against.
        shownKey = null
        shownAnalyses = null
        lastSearchedPlacement = null
        lastSearchedEvalWhite = null
    }

    private fun updateBoardBounds(grid: Grid, px: Pixels, bitmap: Bitmap) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val s = px.scale
        if (bw <= 0f || bh <= 0f || s <= 0f) return
        val rect = RectF(
            (grid.x0 / s / bw).coerceIn(0f, 1f),
            (grid.y0 / s / bh).coerceIn(0f, 1f),
            ((grid.x0 + 8f * grid.cw) / s / bw).coerceIn(0f, 1f),
            ((grid.y0 + 8f * grid.ch) / s / bh).coerceIn(0f, 1f),
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val landscape = bitmap.width > bitmap.height
        val old = if (landscape) landscapeBounds else portraitBounds
        if (old != null && closeTo(old, rect)) return
        if (landscape) landscapeBounds = rect else portraitBounds = rect
        postBoardBounds(force = true)
    }

    private fun closeTo(a: RectF, b: RectF): Boolean =
        abs(a.left - b.left) < BOUNDS_EPSILON && abs(a.top - b.top) < BOUNDS_EPSILON &&
            abs(a.right - b.right) < BOUNDS_EPSILON && abs(a.bottom - b.bottom) < BOUNDS_EPSILON

    // -------------------------------------------------------------------------------------
    // Move tracking (worker thread)
    // -------------------------------------------------------------------------------------

    /**
     * @param fromAccessibility true when [placement] comes from accessibility data (exact), false
     *   when it was read from the screen by vision (may contain misreads).
     */
    private fun onPositionChanged(placement: String, fromAccessibility: Boolean) {
        EngineStats.positionsSeen++
        positionStale = false
        engine?.interruptSearch()
        checkTurnTrackingSources()
        val orientationKnown = whiteAtBottom != null || lastKnownOrientation != null
        val userIsWhite = whiteAtBottom ?: lastKnownOrientation ?: true
        val prevFen = currentFen
        val after = parsePlacement(placement) ?: return

        val move = if (prevFen != null && placement != START_PLACEMENT) {
            val before = parsePlacement(prevFen.substringBefore(' '))
            if (before != null) MoveInference.infer(before, after, epSquareOf(prevFen)) else null
        } else {
            null
        }

        if (move == null) {
            if (placement != START_PLACEMENT && !orientationKnown) {
                postOverlayClear()
                return
            }
            // Guard against vision misreads caused by our own arrow overlay.
            // When the board differs from the last known-good position by only
            // a few squares and no legal move can be inferred, it is almost
            // certainly the arrow graphics being read as pieces. Accessibility data is exact
            // and never sees the overlay, so there such a difference is real (e.g. a capture and
            // recapture seen as one change) and must resync instead of being dropped.
            if (!fromAccessibility && placement != START_PLACEMENT && lastKnownGoodFen != null) {
                val goodPlacement = lastKnownGoodFen!!.substringBefore(' ')
                val goodBoard = parsePlacement(goodPlacement)
                if (goodBoard != null) {
                    var diffs = 0
                    for (i in after.indices) {
                        if (after[i] != goodBoard[i]) diffs++
                    }
                    if (diffs in 1..3) return
                }
            }
            resyncTo(
                placement,
                after,
                if (placement == START_PLACEMENT) true else resyncSideToMove(placement, prevFen, userIsWhite),
            )
            return
        }

        // --- Cross-validate with move inference: same-side-moves sanity check ---
        if (lastInferredMoverWasWhite == move.white) {
            consecutiveSameSideMoves++
            if (consecutiveSameSideMoves >= 2) {
                Log.w(TAG, "Orientation suspect: ${consecutiveSameSideMoves + 1} consecutive " +
                    "${if (move.white) "white" else "black"} moves inferred")
            }
        } else {
            consecutiveSameSideMoves = 0
        }
        lastInferredMoverWasWhite = move.white

        // The side that actually moved is authoritative; fix the previous FEN if we guessed wrong.
        var beforeFen: String = prevFen ?: return
        // Only the best line of the previous position is needed for classification.
        var beforeAnalysis: AnalysisResult? = currentAnalyses?.firstOrNull()
        var olderAnalysis = previousAnalysis
        var multiPvInvalidated = false
        if (sideToMoveIsWhite(beforeFen) != move.white) {
            beforeFen = withSideToMove(beforeFen, move.white)
            beforeAnalysis = null
            olderAnalysis = null
            multiPvInvalidated = true
        }
        if (move.enPassant && epSquareOf(beforeFen) != move.to) {
            beforeFen = withEnPassant(beforeFen, move.to)
            beforeAnalysis = null
        }
        // Classifying the played move is deferred until after the user-facing analysis below
        // (see classifyMove); only capture what it needs before currentAnalyses is dropped.
        val multiPvForClassifier = if (multiPvInvalidated) null else currentAnalyses?.takeIf { it.size >= 2 }

        castlingRights = updatedCastlingRights(castlingRights, move, after)
        val prevBoard = currentFen?.let { parsePlacement(it.substringBefore(' ')) }
        val movedPiece = prevBoard?.get(move.from)?.lowercaseChar()
        val wasCapture = (prevBoard != null && prevBoard[move.to] != EMPTY) || move.enPassant
        halfMoveClock = if (movedPiece == 'p' || wasCapture) 0 else halfMoveClock + 1
        if (!move.white) fullMoveNumber++
        val newFen = composeFen(placement, !move.white, castlingRights, enPassantTarget(move, after), fullMoveNumber)
        currentFen = newFen
        // currentAnalyses belongs to the previous position from here on; drop it so nothing
        // renders or classifies against the wrong position if analysis below fails.
        currentAnalyses = null
        val newWhiteToMove = !move.white

        // It's the opponent's turn (= user just moved) when the side to move next
        // is NOT the user's side.  The mover check (move.white != userIsWhite) is the
        // complement of this — it tells us whether the opponent made the last move.
        val isOpponentTurn = newWhiteToMove != userIsWhite
        val opponentJustMoved = move.white != userIsWhite

        if (isOpponentTurn) {
            // User just moved → opponent's turn now.  Clear overlay and skip analysis of the new
            // position; the user's move is still classified (time-limited) for later feedback.
            previousFen = beforeFen
            previousAnalysis = beforeAnalysis
            currentAnalyses = null
            lastKnownGoodFen = newFen
            lastMove = move
            lastClassified = null
            lastUserClassified = null
            postClearOpponentBadge()
            postClearCoachMessage()
            postOverlayClear()
            val (analysedBefore, classified) =
                classifyMove(beforeFen, move, beforeAnalysis, olderAnalysis, multiPvForClassifier)
            if (!running.get()) return
            previousAnalysis = analysedBefore
            lastClassified = classified
            lastUserClassified = classified
            // Tell the user right away how their move was, and what was better if it was not.
            // beforeAnalysis is the top line that was on screen (null if nothing was shown), so a
            // match means the user simply followed the arrow: no surprise, just a brief nod.
            if (classified != null) {
                val suggested = beforeAnalysis?.bestMove?.trim()
                postCoachReaction(classified, beforeFen, suggested.equals(move.uci, ignoreCase = true))
            } else {
                Log.w(TAG, "User move ${move.uci} could not be classified: no coach reaction")
            }
            return
        }
        // Opponent just moved → user's turn.  The user-facing analysis runs first so the arrows
        // appear as soon as possible; the opponent's move is classified afterwards.
        // A position shown moments ago (duplicate or flickering events) is not searched again.
        val newAnalyses = recentlyShown(newFen) ?: run {
            val searchMs = searchTimeMs()
            postOverlayThinking(searchMs)
            analyzeMulti(newFen, searchMs)
        }
        if (!running.get()) return

        previousFen = beforeFen
        previousAnalysis = beforeAnalysis
        currentAnalyses = newAnalyses
        if (!newAnalyses.isNullOrEmpty()) lastKnownGoodFen = newFen
        lastMove = move
        lastClassified = null
        EngineStats.movesAnalyzed++
        EngineStats.lastFen = newFen
        EngineStats.whiteAtBottom = whiteAtBottom
        val best = newAnalyses?.firstOrNull()
        if (best != null) {
            EngineStats.currentDepth = best.depth
            EngineStats.currentEvalCp = best.eval
            EngineStats.currentMateIn = best.mate
        } else {
            EngineStats.currentDepth = 0
            EngineStats.currentEvalCp = null
            EngineStats.currentMateIn = null
        }
        // Do not draw arrows for a position that already changed while the engine was busy.
        val live = freshAccessibilityPositions()?.let {
            BoardPositionParser.positionsToFen(it).substringBefore(' ')
        }
        if (live != null && live != placement) {
            stablePlacement = null
            postOverlayClear()
            return
        }
        render()

        // Only now, with the arrows on screen, classify the opponent's move for its badge.
        val (analysedBefore, classified) =
            classifyMove(beforeFen, move, beforeAnalysis, olderAnalysis, multiPvForClassifier)
        if (!running.get()) return
        previousAnalysis = analysedBefore
        lastClassified = classified
        if (opponentJustMoved && classified != null) {
            postOpponentBadge(move.uci.substring(2, 4), classified.classification.displayName, !userIsWhite)
            // The coach's line was written before the opponent's move was classified. When that
            // move turns out to be remarkable, render again so the coach can react to it.
            if (classified.classification in COACH_REACTS_TO_OPPONENT) {
                val stillLive = freshAccessibilityPositions()?.let {
                    BoardPositionParser.positionsToFen(it).substringBefore(' ')
                }
                if (stillLive == null || stillLive == placement) render()
            }
        }
    }

    /**
     * Classifies [move], played in [beforeFen]. [beforeAnalysis] is the best line of [beforeFen]
     * if already known; otherwise it is computed with a [CLASSIFY_TIME_MS] time limit so this
     * never holds up the worker for long. Returns the analysis used and the classification.
     */
    private fun classifyMove(
        beforeFen: String,
        move: InferredMove,
        beforeAnalysis: AnalysisResult?,
        olderAnalysis: AnalysisResult?,
        multiPvBefore: List<AnalysisResult>?,
    ): Pair<AnalysisResult?, ClassifiedMove?> {
        val e = engine ?: return beforeAnalysis to null
        if (!running.get()) return beforeAnalysis to null
        if (!isAnalyzable(beforeFen)) return beforeAnalysis to null
        val analysis = beforeAnalysis ?: try {
            validatedAnalyses(
                beforeFen,
                e.analyzeMultiPv(beforeFen, depth, 1, moveTimeMs = CLASSIFY_TIME_MS),
            )?.firstOrNull()
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Analysis failed for $beforeFen", ex)
            null
        }
        if (analysis == null || !running.get() || !e.isReady()) return analysis to null
        val classified = try {
            MoveClassifier.classify(
                engine = e,
                fen = beforeFen,
                playedMove = move.uci,
                analysisBefore = analysis,
                depth = 12,
                isBookMove = false,
                evalBeforeOpponentMove = olderAnalysis?.let { -it.eval },
                multiPvBefore = multiPvBefore,
            )
        } catch (ex: IllegalStateException) {
            Log.w(TAG, "Could not classify ${move.uci}", ex)
            null
        }
        return analysis to classified
    }

    /** Starts tracking from [placement] without a known previous move. */
    private fun resyncTo(placement: String, board: CharArray, whiteToMove: Boolean) {
        // Prefer the accessibility service's side-to-move when available; the caller's guess
        // (typically userIsWhite) is only a fallback.
        var sideToMove = AccessibilityData.whiteToMove ?: whiteToMove
        castlingRights = castlingFromPlacement(board)
        halfMoveClock = 0
        fullMoveNumber = 1
        lastUserClassified = null
        // The move that led here is unknown, so a reaction to an earlier move no longer applies.
        postClearCoachMessage()
        var fen = composeFen(placement, sideToMove, castlingRights, null, fullMoveNumber)
        // If sideToMove leaves the other side in check the position is impossible, and the turn
        // must be the other side's. This holds whatever the source: the board is exact, while
        // the move list it is read next to can lag one ply behind it.
        if (PositionValidator.sideNotToMoveInCheck(fen)) {
            val other = composeFen(placement, !sideToMove, castlingRights, null, fullMoveNumber)
            if (!PositionValidator.sideNotToMoveInCheck(other)) {
                Log.w(TAG, "Resync: ${if (sideToMove) "black" else "white"} is in check, so it " +
                    "must be that side's move; correcting the side to move")
                sideToMove = !sideToMove
                fen = other
            }
        }
        currentFen = fen
        currentAnalyses = null
        if (sideToMove != (whiteAtBottom ?: lastKnownOrientation ?: true)) {
            previousFen = null
            previousAnalysis = null
            currentAnalyses = null
            lastKnownGoodFen = fen
            lastMove = null
            lastClassified = null
            postOverlayClear()
            return
        }
        // A position shown moments ago (duplicate or flickering events) is not searched again,
        // and the overlay is not blanked for it; render() below redraws the same lines.
        val analyses = recentlyShown(fen) ?: run {
            postOverlayClear()
            postClearOpponentBadge()
            val searchMs = searchTimeMs()
            postOverlayThinking(searchMs)
            analyzeMulti(fen, searchMs)
        }
        if (!running.get()) return
        previousFen = null
        previousAnalysis = null
        currentAnalyses = analyses
        if (!analyses.isNullOrEmpty()) lastKnownGoodFen = fen
        lastMove = null
        lastClassified = null
        EngineStats.lastFen = fen
        EngineStats.whiteAtBottom = whiteAtBottom
        val best = analyses?.firstOrNull()
        if (best != null) {
            EngineStats.currentDepth = best.depth
            EngineStats.currentEvalCp = best.eval
            EngineStats.currentMateIn = best.mate
        } else {
            EngineStats.currentDepth = 0
            EngineStats.currentEvalCp = null
            EngineStats.currentMateIn = null
        }
        // Do not draw arrows for a position that already changed while the engine was busy.
        val live = freshAccessibilityPositions()?.let {
            BoardPositionParser.positionsToFen(it).substringBefore(' ')
        }
        if (live != null && live != placement) {
            stablePlacement = null
            postOverlayClear()
            return
        }
        render()
    }

    private fun resetGameState() {
        // A previous game's orientation must not leak into the next one.
        lastKnownOrientation = null
        whiteAtBottom = null
        orientationVotes = 0
        orientationConfidence = CONFIDENCE_LOW
        crossCheckContrary = 0
        crossCheckedPlacement = null
        consecutiveSameSideMoves = 0
        lastInferredMoverWasWhite = null
        missedFrames = 0
        boardVisible = false
        resetTracking()
        EngineStats.clear()
    }

    // -------------------------------------------------------------------------------------
    // Overlay output
    // -------------------------------------------------------------------------------------

    /**
     * Shows up to [NUM_LINES] engine moves for the side to move. The best line carries the
     * classification of the last played move when one is available; the other lines are labelled
     * by how much worse they are than the best line.
     */
    private fun render() {
        val fen = currentFen ?: return
        val analyses = currentAnalyses
        val whiteToMove = sideToMoveIsWhite(fen)
        // On chess.com the user's pieces are at the bottom. The FEN (and so every engine move) is
        // in absolute squares; flipped maps them onto a board drawn with black at the bottom.
        // This is only right if whiteAtBottom describes the real screen, on both input paths.
        val userIsWhite = whiteAtBottom ?: lastKnownOrientation ?: true
        val flipped = !userIsWhite
        val usersTurn = whiteToMove == userIsWhite
        // Never draw the opponent's best move: suggestions are only shown on the user's turn.
        if (!usersTurn) { postOverlayClear(); return }

        if (analyses.isNullOrEmpty()) {
            postOverlayClear()
            return
        }

        // Engine scores are side-to-move relative; the overlay expects white's point of view.
        val sign = if (whiteToMove) 1 else -1
        val bestEval = analyses[0].eval

        val suggestions = analyses.take(NUM_LINES).mapIndexedNotNull { index, result ->
            val move = result.bestMove
            if (move.length < 4) return@mapIndexedNotNull null
            val rank = index + 1
            val evalCp = result.eval.coerceIn(-StockfishEngine.MATE_SCORE, StockfishEngine.MATE_SCORE) * sign
            val mateIn = result.mate?.let { it * sign }

            // result.mate is from the side to move's POV, which is the user here (usersTurn).
            val userMate = result.mate?.takeIf { it > 0 }

            val label: String
            val color: Int
            when {
                // A forced mate must never hide behind a generic "Best move" / "Excellent".
                userMate != null -> {
                    if (rank > 1 && bestEval - result.eval > QUALITY_CUTOFF_CP) return@mapIndexedNotNull null
                    label = mateLabel(userMate)
                    color = if (userMate == 1) MoveColors.MATE else MoveColors.BEST
                }
                rank == 1 -> {
                    label = "Best move"
                    color = MoveColors.BEST
                }
                else -> {
                    val cpDiff = bestEval - result.eval
                    if (cpDiff > QUALITY_CUTOFF_CP) return@mapIndexedNotNull null
                    label = when {
                        cpDiff < 10 -> "Excellent"
                        cpDiff < 50 -> "Good"
                        cpDiff < 100 -> "Inaccuracy"
                        else -> "Mistake"
                    }
                    color = MoveColors.forLabel(label)
                }
            }
            SuggestedLineData(move.substring(0, 2), move.substring(2, 4), evalCp, mateIn, label, color, rank)
        }
            // Two arrows landing on the same square overlap (heads and badges collide), so only
            // the best-ranked move per destination is kept. The list is in rank order and
            // distinctBy keeps the first occurrence, so PV1 beats PV2 beats PV3. This also
            // collapses promotions of one pawn to different pieces (e7e8q / e7e8n).
            .distinctBy { it.to }
            .toMutableList()

        if (suggestions.isEmpty()) {
            postOverlayClear()
            return
        }

        val best = suggestions[0]
        // lastMove / lastClassified describe the opponent's move here (it is the user's turn).
        val opponentClass = lastClassified?.classification
            ?.takeIf { lastMove?.let { m -> m.white != userIsWhite } == true }
        val seconds = AccessibilityData.userTimeSeconds
        // Engine line behind the best suggestion: its PV lets the coach explain the plan.
        val bestAnalysis = analyses.firstOrNull { it.bestMove.startsWith(best.from + best.to) }
        // Mate distance for the side to move (the user). Taken straight from the engine line when
        // it is known; SuggestedLineData holds it from white's POV, hence the sign otherwise.
        val moverMate = bestAnalysis?.mate ?: best.mateIn?.let { it * sign }
        val generated = try {
            MoveCommentary.generate(
                fromSquare = best.from,
                toSquare = best.to,
                label = best.label,
                evalCp = best.evalCp,
                mateIn = moverMate,
                fen = fen,
                isUsersTurn = usersTurn,
                rank = 1,
                promotion = bestAnalysis?.bestMove?.getOrNull(4),
                userLastMove = lastUserClassified?.classification,
                opponentLastMove = opponentClass,
                timePressure = seconds != null && seconds <= TIME_PRESSURE_THRESHOLD_SECONDS,
                pv = bestAnalysis?.pv.orEmpty(),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Commentary failed for $fen", e)
            ""
        }
        // A forced mate is the most important thing the coach can say. If the generator had
        // nothing (or failed), say it plainly rather than leaving the previous text on screen.
        // No squares or notation in coach text: the arrow already shows where to play.
        val commentary = if (generated.isBlank() && moverMate != null && moverMate > 0) {
            if (moverMate == 1) "Checkmate in one. Play the arrow."
            else "Forced mate in $moverMate. Start with the arrow."
        } else {
            generated
        }
        suggestions[0] = best.copy(commentary = commentary)
        // The rest of the plan: the user's later moves of the best line, as numbered arrows.
        suggestions += premoveLines(bestAnalysis, best, sign)

        postMultiSuggestion(suggestions, flipped)
        // Remember what is on screen so the same position is not re-analysed right away. The
        // age counts from the search: redrawing a reused analysis must not extend its life.
        if (analyses !== shownAnalyses || shownKey != positionKey(fen)) {
            shownKey = positionKey(fen)
            shownAtMs = SystemClock.elapsedRealtime()
            shownAnalyses = analyses
        }
    }

    /**
     * The user's follow-up moves (2nd, 3rd, ...) from the principal variation behind [best], to
     * be drawn as numbered premove arrows. The PV alternates user move, opponent reply, ..., so
     * the user's moves sit at the even indices.
     *
     * A forced mate shows every remaining move of the mate (up to [MAX_MATE_PREMOVES]) in gold.
     * Any other line shows at most [MAX_PLAN_PREMOVES] faint neutral arrows, and only when the
     * evaluation has settled, as a line the engine is still changing its mind about is no plan.
     * @param sign +1 if the user (side to move) is white, -1 if black
     */
    private fun premoveLines(
        analysis: AnalysisResult?,
        best: SuggestedLineData,
        sign: Int,
    ): List<SuggestedLineData> {
        analysis ?: return emptyList()
        val pv = analysis.pv
        // The PV must be the line of the arrow on screen, not a leftover of another move.
        if (pv.firstOrNull()?.startsWith(best.from + best.to) != true) return emptyList()

        val mate = analysis.mate
        val userMate = mate?.takeIf { it > 0 }
        val count = when {
            userMate != null -> minOf(userMate - 1, MAX_MATE_PREMOVES)
            mate != null -> 0 // getting mated: nothing to plan
            analysis.depth < PLAN_MIN_DEPTH -> 0
            (analysis.evalSwing ?: Int.MAX_VALUE) > PLAN_MAX_SWING_CP -> 0
            else -> MAX_PLAN_PREMOVES
        }

        val lines = ArrayList<SuggestedLineData>(count.coerceAtLeast(0))
        for (step in 2..count + 1) {
            val index = (step - 1) * 2
            val move = pv.getOrNull(index) ?: break
            if (!isUciMove(move) || !isUciMove(pv[index - 1])) break
            // The very last move of a non-mate PV is the least searched; only show a planned
            // move the engine has looked beyond.
            if (userMate == null && index == pv.lastIndex) break
            if (userMate != null) {
                // Mate distance left once the earlier moves are played, so the last arrow is M1.
                val left = userMate - (step - 1)
                lines += SuggestedLineData(
                    from = move.substring(0, 2),
                    to = move.substring(2, 4),
                    evalCp = best.evalCp,
                    mateIn = left * sign,
                    label = mateLabel(left),
                    labelColor = MoveColors.MATE,
                    rank = 1,
                    step = step,
                )
            } else {
                lines += SuggestedLineData(
                    from = move.substring(0, 2),
                    to = move.substring(2, 4),
                    evalCp = best.evalCp,
                    mateIn = null,
                    label = "Plan",
                    labelColor = PLAN_COLOR,
                    rank = 1,
                    step = step,
                )
            }
        }
        return lines
    }

    /** True for a well-formed UCI move such as "e2e4" or "e7e8q". */
    private fun isUciMove(move: String): Boolean =
        move.length in 4..5 && squareIndex(move.substring(0, 2)) != null &&
            squareIndex(move.substring(2, 4)) != null

    /** Arrow label for a forced mate in [moves] by the side to move. */
    private fun mateLabel(moves: Int): String = if (moves == 1) "Mate in 1!" else "Mate in $moves"

    private fun postMultiSuggestion(suggestions: List<SuggestedLineData>, flipped: Boolean) {
        if (!running.get()) return
        postBoardBounds(force = false)
        mainHandler.post {
            if (running.get()) {
                val overlay = OverlayService.instance ?: return@post
                val lines = suggestions.map { s ->
                    SuggestedLine(
                        from = s.from,
                        to = s.to,
                        evalCp = s.evalCp,
                        mateIn = s.mateIn,
                        label = s.label,
                        labelColor = s.labelColor,
                        rank = s.rank,
                        commentary = s.commentary,
                        step = s.step,
                    )
                }
                overlay.showMultiSuggestion(lines, flipped)
            }
        }
    }

    private fun postOverlayClear() {
        mainHandler.post { OverlayService.instance?.clearSuggestion() }
    }

    private fun postOpponentBadge(square: String, label: String, flipped: Boolean) {
        mainHandler.post { OverlayService.instance?.showOpponentBadge(square, label, flipped) }
    }

    private fun postClearOpponentBadge() {
        mainHandler.post { OverlayService.instance?.clearOpponentBadge() }
    }

    /**
     * Shows the coach's reaction to the user's own move, played in [beforeFen].
     * @param wasSuggested the move is the one the coach was showing as its best line
     */
    private fun postCoachReaction(classified: ClassifiedMove, beforeFen: String, wasSuggested: Boolean) {
        val text = try {
            MoveCommentary.postMoveCommentary(
                classification = classified.classification,
                playedMove = classified.move,
                bestMove = classified.engineBest,
                cpLoss = classified.cpLoss,
                evalBefore = classified.evalBefore,
                evalAfter = classified.evalAfter,
                fen = beforeFen,
                wasSuggested = wasSuggested,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Post-move commentary failed for ${classified.move}", e)
            return
        }
        mainHandler.post { if (running.get()) OverlayService.instance?.showCoachMessage(text) }
    }

    private fun postClearCoachMessage() {
        mainHandler.post { OverlayService.instance?.clearCoachMessage() }
    }

    /**
     * Puts the overlay into its "thinking" state for a search of [searchMs], which it counts
     * down live. The deadline is taken here, as the search starts right after this call.
     */
    private fun postOverlayThinking(searchMs: Int) {
        val deadline = SystemClock.elapsedRealtime() + searchMs
        mainHandler.post {
            // Whatever the main thread's delay ate is taken off the countdown.
            val remaining = deadline - SystemClock.elapsedRealtime()
            OverlayService.instance?.showThinking(if (searchMs >= 1000) remaining.coerceAtLeast(0L) else 0L)
        }
    }

    /**
     * Sends the known board bounds to the overlay. Unknown orientations get an empty rect, which
     * makes the overlay hide the arrow instead of drawing it in the wrong place. Without [force]
     * the bounds are only re-sent when the overlay service instance changed.
     */
    private fun postBoardBounds(force: Boolean) {
        val portrait = portraitBounds?.let { RectF(it) } ?: RectF()
        val landscape = landscapeBounds?.let { RectF(it) } ?: RectF()
        if (portrait.isEmpty() && landscape.isEmpty()) return
        mainHandler.post {
            val overlay = OverlayService.instance ?: return@post
            if (force || overlay !== boundsDeliveredTo) {
                overlay.setBoardBounds(portrait, landscape)
                boundsDeliveredTo = overlay
            }
        }
    }

    private fun postStatus(message: String) {
        Log.i(TAG, message)
        mainHandler.post { statusListener?.invoke(message) }
    }

    private fun notifyCalibration(success: Boolean, message: String) {
        postStatus(message)
        mainHandler.post { calibrationListener?.invoke(success) }
    }

    // -------------------------------------------------------------------------------------
    // FEN helpers
    // -------------------------------------------------------------------------------------

    /** Parses a FEN placement field into 64 squares indexed `rank * 8 + file` (a1 = 0). */
    private fun parsePlacement(placement: String): CharArray? {
        val rows = placement.split('/')
        if (rows.size != 8) return null
        val squares = CharArray(64) { EMPTY }
        for ((rowIndex, row) in rows.withIndex()) {
            val rank = 7 - rowIndex
            var file = 0
            for (c in row) {
                if (c.isDigit()) {
                    file += c - '0'
                } else {
                    if (file > 7) return null
                    squares[rank * 8 + file] = c
                    file++
                }
            }
            if (file != 8) return null
        }
        return squares
    }

    private fun composeFen(
        placement: String,
        whiteToMove: Boolean,
        castling: String,
        epSquare: Int?,
        fullMove: Int,
    ): String {
        val side = if (whiteToMove) "w" else "b"
        val ep = epSquare?.let { squareName(it) } ?: "-"
        return "$placement $side ${castling.ifEmpty { "-" }} $ep $halfMoveClock ${max(1, fullMove)}"
    }

    private fun fenFields(fen: String): MutableList<String> {
        val fields = fen.trim().split(Regex("\\s+")).toMutableList()
        while (fields.size < 6) {
            fields.add(
                when (fields.size) {
                    1 -> "w"
                    2, 3 -> "-"
                    4 -> "0"
                    else -> "1"
                },
            )
        }
        return fields
    }

    private fun sideToMoveIsWhite(fen: String): Boolean = fenFields(fen)[1] != "b"

    private fun withSideToMove(fen: String, white: Boolean): String {
        val f = fenFields(fen)
        f[1] = if (white) "w" else "b"
        f[3] = "-" // an en passant square only makes sense for the side we assumed before
        return f.joinToString(" ")
    }

    private fun withEnPassant(fen: String, square: Int): String {
        val f = fenFields(fen)
        f[3] = squareName(square)
        return f.joinToString(" ")
    }

    private fun epSquareOf(fen: String): Int = squareIndex(fenFields(fen)[3]) ?: -1

    private fun castlingFromPlacement(b: CharArray): String = buildString {
        if (b[4] == 'K') {
            if (b[7] == 'R') append('K')
            if (b[0] == 'R') append('Q')
        }
        if (b[60] == 'k') {
            if (b[63] == 'r') append('k')
            if (b[56] == 'r') append('q')
        }
    }

    /** Drops rights whose king or rook left (or was captured on) its home square. */
    private fun updatedCastlingRights(rights: String, move: InferredMove, after: CharArray): String {
        val possible = castlingFromPlacement(after)
        val touched = setOf(move.from, move.to)
        return buildString {
            for (r in rights) {
                val lost = when (r) {
                    'K' -> 4 in touched || 7 in touched
                    'Q' -> 4 in touched || 0 in touched
                    'k' -> 60 in touched || 63 in touched
                    'q' -> 60 in touched || 56 in touched
                    else -> true
                }
                if (!lost && r in possible) append(r)
            }
        }
    }

    /** En passant target after a double pawn push, if an enemy pawn could actually capture. */
    private fun enPassantTarget(move: InferredMove, after: CharArray): Int? {
        val pawn = if (move.white) 'P' else 'p'
        if (after[move.to] != pawn || abs(move.to - move.from) != 16) return null
        val enemy = if (move.white) 'p' else 'P'
        val file = move.to % 8
        val adjacent = (file > 0 && after[move.to - 1] == enemy) || (file < 7 && after[move.to + 1] == enemy)
        return if (adjacent) (move.from + move.to) / 2 else null
    }

    private fun squareName(index: Int): String = "${'a' + index % 8}${'1' + index / 8}"

    private fun squareIndex(name: String): Int? {
        if (name.length != 2) return null
        val file = name[0] - 'a'
        val rank = name[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        return rank * 8 + file
    }

    // -------------------------------------------------------------------------------------
    // Move inference
    // -------------------------------------------------------------------------------------

    /** One engine line to draw on the overlay (eval in white's POV, rank 1 = best). */
    private data class SuggestedLineData(
        val from: String,
        val to: String,
        val evalCp: Int?,
        val mateIn: Int?,
        val label: String,
        val labelColor: Int,
        val rank: Int,
        /** Short coach commentary; only set on the best line. */
        val commentary: String = "",
        /** 1 = the move to play now; 2, 3, ... = the user's later moves of the same line. */
        val step: Int = 1,
    )

    private data class InferredMove(
        val from: Int,
        val to: Int,
        val promotion: Char?,
        val white: Boolean,
        val enPassant: Boolean,
    ) {
        val uci: String
            get() = buildString {
                append('a' + from % 8).append('1' + from / 8)
                append('a' + to % 8).append('1' + to / 8)
                if (promotion != null) append(promotion.lowercaseChar())
            }
    }

    /**
     * Recovers the single move that turns one placement into another, or null if the difference
     * is not explained by exactly one pseudo-legal move (missed moves, misreads, new game ...).
     */
    private object MoveInference {

        fun infer(before: CharArray, after: CharArray, epSquare: Int): InferredMove? {
            val changed = (0 until 64).filter { before[it] != after[it] }
            if (changed.isEmpty() || changed.size > 4) return null

            // Only the mover's pieces can appear on a square; the victim's can only disappear.
            val whiteArrivals = changed.filter { after[it] != EMPTY && after[it].isUpperCase() }
            val blackArrivals = changed.filter { after[it] != EMPTY && after[it].isLowerCase() }
            val white = when {
                whiteArrivals.isNotEmpty() && blackArrivals.isEmpty() -> true
                blackArrivals.isNotEmpty() && whiteArrivals.isEmpty() -> false
                else -> return null
            }
            val arrivals = if (white) whiteArrivals else blackArrivals
            val vacated = changed.filter { isOwn(before[it], white) && after[it] == EMPTY }

            if (arrivals.size == 2 && vacated.size == 2 && changed.size == 4) {
                return castling(before, after, white, vacated, arrivals)
            }
            if (arrivals.size != 1 || vacated.size != 1) return null

            val from = vacated[0]
            val to = arrivals[0]
            val piece = before[from]
            val landed = after[to]
            val target = before[to]
            if (isOwn(target, white)) return null
            if (target.lowercaseChar() == 'k') return null

            val isPawn = piece.lowercaseChar() == 'p'
            val lastRank = if (white) 7 else 0
            var promotion: Char? = null
            if (landed != piece) {
                if (!isPawn || to / 8 != lastRank || landed.lowercaseChar() !in "nbrq") return null
                promotion = landed
            } else if (isPawn && to / 8 == lastRank) {
                return null
            }

            // Any further change must be the pawn removed by an en passant capture.
            val others = changed.filter { it != from && it != to }
            var enPassant = false
            if (others.isNotEmpty()) {
                val victim = (from / 8) * 8 + to % 8
                val enemyPawn = if (white) 'p' else 'P'
                if (others.size != 1 || others[0] != victim || !isPawn || target != EMPTY ||
                    before[victim] != enemyPawn || after[victim] != EMPTY ||
                    abs(from % 8 - to % 8) != 1
                ) {
                    return null
                }
                enPassant = true
            }

            if (enPassant && epSquare >= 0 && to != epSquare) return null
            if (!isPseudoLegal(before, from, to, piece, white, enPassant)) return null
            return InferredMove(from, to, promotion, white, enPassant)
        }

        private fun castling(
            before: CharArray,
            after: CharArray,
            white: Boolean,
            vacated: List<Int>,
            arrivals: List<Int>,
        ): InferredMove? {
            val king = if (white) 'K' else 'k'
            val rook = if (white) 'R' else 'r'
            val base = if (white) 0 else 56
            val kingFrom = vacated.firstOrNull { before[it] == king } ?: return null
            val kingTo = arrivals.firstOrNull { after[it] == king } ?: return null
            val rookFrom = vacated.firstOrNull { before[it] == rook } ?: return null
            val rookTo = arrivals.firstOrNull { after[it] == rook } ?: return null
            if (kingFrom != base + 4) return null
            val ok = (kingTo == base + 6 && rookFrom == base + 7 && rookTo == base + 5) ||
                (kingTo == base + 2 && rookFrom == base && rookTo == base + 3)
            if (!ok) return null
            // Squares the rook passes must have been empty (b-file included for long castling).
            val path = if (kingTo == base + 6) listOf(base + 5, base + 6) else listOf(base + 1, base + 2, base + 3)
            if (path.any { before[it] != EMPTY }) return null
            return InferredMove(kingFrom, kingTo, null, white, false)
        }

        private fun isOwn(c: Char, white: Boolean): Boolean = c != EMPTY && c.isUpperCase() == white

        private fun isPseudoLegal(
            board: CharArray,
            from: Int,
            to: Int,
            piece: Char,
            white: Boolean,
            enPassantAllowed: Boolean,
        ): Boolean {
            val df = to % 8 - from % 8
            val dr = to / 8 - from / 8
            val adf = abs(df)
            val adr = abs(dr)
            val target = board[to]
            return when (piece.lowercaseChar()) {
                'p' -> {
                    val dir = if (white) 1 else -1
                    val startRank = if (white) 1 else 6
                    when {
                        df == 0 && dr == dir -> target == EMPTY
                        df == 0 && dr == 2 * dir -> from / 8 == startRank && target == EMPTY &&
                            board[from + 8 * dir] == EMPTY
                        adf == 1 && dr == dir -> (target != EMPTY && !isOwn(target, white)) ||
                            (target == EMPTY && enPassantAllowed)
                        else -> false
                    }
                }
                'n' -> (adf == 1 && adr == 2) || (adf == 2 && adr == 1)
                'b' -> adf == adr && adf > 0 && pathClear(board, from, to)
                'r' -> (adf == 0) != (adr == 0) && pathClear(board, from, to)
                'q' -> ((adf == adr && adf > 0) || ((adf == 0) != (adr == 0))) && pathClear(board, from, to)
                'k' -> max(adf, adr) == 1
                else -> false
            }
        }

        private fun pathClear(board: CharArray, from: Int, to: Int): Boolean {
            val stepF = Integer.signum(to % 8 - from % 8)
            val stepR = Integer.signum(to / 8 - from / 8)
            var f = from % 8 + stepF
            var r = from / 8 + stepR
            while (r * 8 + f != to) {
                if (board[r * 8 + f] != EMPTY) return false
                f += stepF
                r += stepR
            }
            return true
        }
    }
}
