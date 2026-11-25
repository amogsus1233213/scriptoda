package com.chesstrainer.app.service

import android.content.Context
import android.graphics.Bitmap
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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
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
 *     identical reads instead, as it is most likely a misread. A finished move of the opponent
 *     is accepted from a single read ([isClearOpponentMove]), and a move reported by the
 *     accessibility service is picked up between frames ([pollAccessibility]), so the search for
 *     the user's reply starts without waiting for the next frame.
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
        private const val NUM_LINES = 4

        /** Time budget (ms) for the main multi-PV search; 0 means use depth only. */
        private const val SEARCH_TIME_MS = 5000

        /** At or below this many seconds on the user's clock, the search uses [FAST_MOVETIME_MS]. */
        private const val TIME_PRESSURE_THRESHOLD_SECONDS = 60

        /** Time budget (ms) for the main search when the user is in time pressure. */
        private const val FAST_MOVETIME_MS = 500

        /** Opponent move classes worth re-rendering the coach's commentary for. */
        private val COACH_REACTS_TO_OPPONENT =
            setOf(MoveClass.BLUNDER, MoveClass.MISTAKE, MoveClass.MISS, MoveClass.BRILLIANT)

        /** Time budget (ms) for analysing the previous position when classifying a played move. */
        private const val CLASSIFY_TIME_MS = 500

        /** Alternative lines worse than this (cp) are not drawn as arrows. */
        private const val QUALITY_CUTOFF_CP = 150

        /**
         * Extra user moves of a forced mate drawn as numbered premoves (after move 1). With
         * move 1 that is 8 steps; the overlay's MAX_PREMOVES must not be smaller than this.
         */
        private const val MAX_MATE_PREMOVES = 7
        /** Coach text while the rest of a forced mate stays on screen after a move of it. */
        private const val MATE_FOLLOW_COMMENTARY = "Mate is forced. Keep following the numbered arrows."
        /** Coach text once only the mating move of a followed forced mate is left. */
        private const val MATE_LAST_MOVE_COMMENTARY = "Checkmate in one. Play the arrow."
        /** Coach text when the user has played the last move of a followed forced mate. */
        private const val MATE_DELIVERED_COMMENTARY = "Checkmate! You played the whole mate."
        /**
         * Most plies of a followed forced mate accepted as one board change. A queued premove is
         * played together with the opponent's reply (2 plies); 3 covers a read that also missed
         * the user's own move before them.
         */
        private const val MAX_MATE_CATCH_UP_PLIES = 6

        /** Time (ms) of one follow-up search that completes a mate PV; see [completeMatePv]. */
        private const val MATE_PV_STEP_MS = 200

        /** Most follow-up searches spent on completing one mate PV. */
        private const val MATE_PV_MAX_ROUNDS = 3

        /**
         * Time limit (ms) of the search that confirms a mate line ends in checkmate; see
         * [verifiedMatePv]. A checkmated position is answered at once, so this is only spent
         * when the line turns out not to mate.
         */
        private const val MATE_VERIFY_MS = 100

        /**
         * Eval change (cp, white's POV) between two consecutive analyses of nearby positions
         * above which a warning is logged. No ordinary move swings the eval this far, so it is
         * either a mate appearing or disappearing, or the engine answering for another position.
         */
        private const val EVAL_SWING_WARN_CP = 5000

        /** A position analysed and shown less than this long ago is not searched again. */
        private const val STALE_ANALYSIS_MS = 2000L

        /** How often the search of one placement is repeated after it gave no analysis. */
        private const val MAX_ANALYSIS_RETRIES = 2
        /** How often in a row a position is read again after its worker task threw. */
        private const val MAX_WORKER_FAILURE_RETRIES = 3

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
         * How often (ms) [AccessibilityData] is checked for a new move between screen frames.
         * Frames arrive only every 500 ms at the default 2 fps, which used to be the wait before
         * a move the accessibility service had already reported reached the engine.
         */
        private const val ACCESSIBILITY_POLL_MS = 100L

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

        /**
         * Vision path: how often in a row the same small unexplained board change (see the
         * arrow-misread guard in [onPositionChanged]) is read before the arrows are taken off
         * the screen to find out whether they cause it, and how often before it is accepted as
         * real. The reads between the two are made with the arrows gone.
         */
        private const val ARROW_GUARD_CLEAR_HITS = 3
        private const val ARROW_GUARD_ACCEPT_HITS = 6

        /** Minimum change (screen fraction) before new board bounds are pushed to the overlay. */
        private const val BOUNDS_EPSILON = 0.004f

        private const val WORKER_KEEP_ALIVE_SECONDS = 30L

        /** Accessibility positions older than this are ignored in favour of vision. */
        private const val ACCESSIBILITY_MAX_AGE_MS = 2000L
        /** Longer timeout while the chess app is still visible (avoids vision reading our own arrows). */
        private const val ACCESSIBILITY_MAX_AGE_VISIBLE_MS = 30_000L

        /** Minimum number of accessibility-reported pieces for the data to be trusted. */
        private const val ACCESSIBILITY_MIN_PIECES = 3

        /**
         * A read of the chess app's view tree that found the board less than this long ago
         * proves the board is on screen, whatever package detection or the vision locator say.
         * The accessibility service re-reads the tree at least once a second while the chess
         * app is in front, so this allows for two late reads.
         */
        private const val ACCESSIBILITY_CONFIRMED_MS = 2500L

        /**
         * Consecutive polls ([ACCESSIBILITY_POLL_MS] apart) that must find the chess app gone
         * before the overlay is taken down for it; see [checkTargetLeft].
         */
        private const val TARGET_GONE_POLLS = 5

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

    /**
     * Accessibility path: consecutive frames in which the board could not be located on screen.
     * The accessibility positions outlive the board (another screen of the chess app), so this
     * is what takes the arrows down there; see [noteAccessibilityBoard]. Worker thread only.
     */
    private var accessibilityMissedBoards = 0

    /** Placement last held back by the arrow-misread guard and how often in a row. Worker only. */
    private var guardedPlacement: String? = null
    private var guardedCount = 0

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

    /**
     * The forced mate whose arrows are on the overlay, kept while the game follows it.
     * [lines] are the arrows after the first move as [premoveLines] returned them: `lines[i]`
     * is the move `pv[i + 1]`. [played] counts the plies of [pv] played on the board so far.
     */
    private class MateLine(
        val pv: List<String>,
        val lines: List<SuggestedLineData>,
        val flipped: Boolean,
        var played: Int = 0,
    )

    /**
     * Set by [render] when it draws a forced mate with premoves (or a mate in one, which has
     * none: [MateLine.lines] is then empty), dropped by every clearing of
     * the overlay ([postOverlayClear]) and as soon as a move leaves the line. While it is set
     * and the moves played are the expected ones, the remaining arrows stay on screen: the user
     * is queueing premoves from them and must not lose them when the first move is played and
     * the position is searched again. See [advanceMateLine]. Worker thread only.
     */
    private var mateLine: MateLine? = null

    /**
     * Set by [advanceMateLine] when the move it recorded was the mating move of the line (the
     * whole mate has been played), cleared by its next call. Worker thread only.
     */
    private var mateDelivered = false

    /** Placement and eval (white's POV) of the last main search, for [checkEvalSanity]. */
    private var lastSearchedPlacement: String? = null
    private var lastSearchedEvalWhite: Int? = null

    /** Whether the last position change found no side-to-move source in [AccessibilityData]. */
    private var turnSourcesUnavailable = false

    /**
     * Placement the worker's main search or move classification is running for, null while
     * neither is running. Written by the worker, read by the capture and poll threads
     * ([interruptStaleSearch]).
     */
    @Volatile
    private var searchingPlacement: String? = null

    /**
     * Set by the capture or poll thread ([interruptStaleSearch]) when the board changed while
     * the engine work for [searchingPlacement] was still running; the search is interrupted at
     * the same time. The worker checks it once the search returns and drops the (cut short)
     * result, so the new position is analysed from the next frame.
     */
    @Volatile
    private var positionStale = false

    /** Checks [AccessibilityData] for moves between frames; see [pollAccessibility]. */
    @Volatile
    private var accessibilityPoller: ScheduledExecutorService? = null

    /** Set once a frame went down the accessibility path; [pollAccessibility] waits for it. */
    @Volatile
    private var accessibilityFrameSeen = false

    /** Whether [checkTargetLeft] last found the chess app on screen. Poll thread. */
    @Volatile
    private var targetWasVisible = false

    /** Polls in a row in which [checkTargetLeft] found the chess app gone. Poll thread. */
    @Volatile
    private var targetGonePolls = 0

    /** Placement [pollAccessibility] last handed to the worker. */
    @Volatile
    private var polledPlacement: String? = null

    /** Placement whose search last gave no analysis, and how often it was retried. Worker only. */
    private var failedPlacement: String? = null
    private var failedRetries = 0

    /** Worker tasks that threw in a row; see [recoverFromWorkerFailure]. Worker only. */
    private var workerFailures = 0

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
        // A flag left set by an earlier run must not keep every frame of this one out.
        processing.set(false)
        targetWasVisible = chessAppOnScreen()
        targetGonePolls = 0
        // Non-blocking: fetches/loads the piece templates on its own background thread.
        templateManager.ensureLoaded()
        submit {
            resetGameState()
            ensureTemplatesLoaded()
            initEngine()
        }
        ScreenCaptureService.listener = frameListener
        accessibilityFrameSeen = false
        polledPlacement = null
        accessibilityPoller = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "analysis-a11y-poll").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(
                { pollAccessibility() }, ACCESSIBILITY_POLL_MS, ACCESSIBILITY_POLL_MS, TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Stops consuming frames, shuts the engine down and clears the overlay. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        if (ScreenCaptureService.listener === frameListener) {
            ScreenCaptureService.listener = null
        }
        calibrationRequested.set(false)
        accessibilityPoller?.shutdownNow()
        accessibilityPoller = null
        // Interrupt a running search so the teardown below is not stuck behind it.
        engine?.stop()
        submit {
            engine?.quit()
            engine = null
            resetGameState()
        }
        mainHandler.post {
            OverlayService.instance?.clearSuggestion()
            OverlayService.instance?.clearOpponentBadge()
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
        // Null (as opposed to a result cut short by an interrupt) means the engine did not answer
        // at all. A process that died is restarted by the next call on its own, but one that is
        // alive and no longer answering would fail every search from here on, leaving every
        // position without arrows. It is torn down so the retry starts a fresh process.
        if (raw == null && running.get() && e.isReady()) {
            Log.w(TAG, "Engine is alive but gave no answer; restarting it")
            try {
                e.quit()
            } catch (ex: RuntimeException) {
                Log.w(TAG, "Could not shut the unresponsive engine down", ex)
            }
        }
        val validated = validatedAnalyses(fen, raw)
        checkEvalSanity(fen, validated?.firstOrNull())
        if (validated.isNullOrEmpty()) return validated
        val trusted = trustedMates(fen, validated)
        // Only the best line gets numbered follow-up moves, so only its PV is completed.
        return listOf(verifiedMatePv(fen, completeMatePv(fen, trusted[0], searchMs))) + trusted.drop(1)
    }

    /**
     * Last check before the moves of a forced mate are drawn: a PV that holds the whole mate
     * (2N - 1 plies for a mate in N) is played out on the engine's board and must end with the
     * opponent checkmated. The engine stops applying moves at the first illegal one, so a line
     * with a move that cannot be played fails as well.
     *
     * A line that fails is not the mate: only its first move is kept, so no premove arrow and
     * no mate ring is drawn from it. For a "mate in 1" the claim itself is then disproved (the
     * move on the arrow does not mate) and the mate score is dropped too. A PV shorter than the
     * whole mate is left alone: [premoveLines] draws it without a mating move.
     */
    private fun verifiedMatePv(fen: String, best: AnalysisResult): AnalysisResult {
        val mate = best.mate?.takeIf { it > 0 } ?: return best
        val e = engine ?: return best
        val plies = 2 * mate - 1
        val pv = best.pv.takeWhile { isUciMove(it) }
        if (pv.size < plies || pv[0] != best.bestMove) return best
        val line = pv.take(plies)
        val end = try {
            e.analyzeMultiPv(fen, depth, 1, moves = line, moveTimeMs = MATE_VERIFY_MS)?.firstOrNull()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "Could not verify the mate line for $fen", ex)
            null
        }
        // "mate 0" with no move to play: the side to move at the end of the line is checkmated.
        if (end != null && end.mate == 0 && end.bestMove.isEmpty()) {
            return if (line.size == best.pv.size) best else best.copy(pv = line)
        }
        // The search was cut short because the board moved on: this result is discarded anyway.
        if (end == null || positionStale) return best.copy(pv = listOf(best.bestMove))
        Log.w(TAG, "Mate in $mate: the line ${line.joinToString(" ")} does not end in checkmate " +
            "(engine: mate ${end.mate}, bestmove '${end.bestMove}'), premoves dropped: $fen")
        if (mate > 1) return best.copy(pv = listOf(best.bestMove))
        val cap = StockfishEngine.UNPROVEN_MATE_CP
        return best.copy(mate = null, eval = best.eval.coerceIn(-cap, cap), pv = listOf(best.bestMove))
    }

    /**
     * Removes what cannot be trusted about the mate scores of [analyses] before they are shown.
     *
     * With the side not to move in check, [fen] is not a legal position (the side to move was
     * tracked wrongly: it is really the other side's turn). The engine's "mates" there are the
     * product of a free extra move and do not exist on the board, so they are dropped.
     *
     * A mate in N by the side to move is 2N - 1 plies long; anything in the PV after that is
     * not part of the mate and is cut off.
     */
    private fun trustedMates(fen: String, analyses: List<AnalysisResult>): List<AnalysisResult> {
        if (analyses.none { it.mate != null }) return analyses
        if (PositionValidator.sideNotToMoveInCheck(fen)) {
            Log.w(TAG, "Mate scores dropped, the side not to move is in check (wrong side to move): $fen")
            val cap = StockfishEngine.UNPROVEN_MATE_CP
            return analyses.map { if (it.mate == null) it else it.copy(mate = null, eval = it.eval.coerceIn(-cap, cap)) }
        }
        return analyses.map { result ->
            val mate = result.mate
            if (mate != null && mate > 0 && result.pv.size > 2 * mate - 1) {
                result.copy(pv = result.pv.take(2 * mate - 1))
            } else {
                result
            }
        }
    }

    /**
     * Makes sure the PV of a forced mate by the side to move holds every move of the mate that
     * [premoveLines] draws. Stockfish regularly reports "mate in 3" with a PV of one or two
     * moves (lines cut short by the hash table, fail-high / fail-low output, a search stopped
     * mid-iteration), and without the later moves no premove arrow can be drawn at all. The
     * missing moves are asked for by searching the position at the end of the known PV, which
     * is quick as the mate is already in the hash table. An extension is only taken when the
     * engine reports exactly the mate distance that is left at that point of the line, and it
     * is cut at the mating move. Anything else (no mate, the other side mating, a longer or a
     * shorter mate) means the moves found are not the rest of this mate, and the PV is left as
     * far as it got: fewer premoves, but never moves that do not lead to the mate.
     */
    private fun completeMatePv(fen: String, best: AnalysisResult, searchMs: Int): AnalysisResult {
        val mate = best.mate ?: return best
        if (mate <= 1) return best
        val e = engine ?: return best
        // Plies up to and including the last user move that is drawn.
        val needed = 2 * minOf(mate, MAX_MATE_PREMOVES + 1) - 1
        var pv = best.pv.takeWhile { isUciMove(it) }
        if (pv.isEmpty() || pv[0] != best.bestMove || pv.size >= needed) return best
        val stepMs = if (searchMs <= FAST_MOVETIME_MS) MATE_PV_STEP_MS / 2 else MATE_PV_STEP_MS
        val deadline = SystemClock.elapsedRealtime() + stepMs * MATE_PV_MAX_ROUNDS
        var rounds = 0
        while (pv.size < needed && rounds < MATE_PV_MAX_ROUNDS && running.get() && !positionStale &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            rounds++
            val next = try {
                e.analyzeMultiPv(fen, depth, 1, moves = pv, moveTimeMs = stepMs)?.firstOrNull()
            } catch (ex: RuntimeException) {
                Log.w(TAG, "Could not extend the mate PV for $fen", ex)
                null
            } ?: break
            val nextMate = next.mate ?: break
            // After k plies of a mate in N: with k even the mating side is to move and has a
            // mate in N - k/2; with k odd the defender is to move and is mated in N - (k+1)/2.
            val k = pv.size
            val expectedMate = if (k % 2 == 0) mate - k / 2 else -(mate - (k + 1) / 2)
            if (nextMate != expectedMate) {
                Log.w(TAG, "Mate in $mate: after $k plies the engine reports mate $nextMate, " +
                    "expected $expectedMate; not extending the PV: $fen")
                break
            }
            val tail = next.pv.takeWhile { isUciMove(it) }
            if (tail.isEmpty()) break
            // The whole mate is 2 * mate - 1 plies; nothing after the mating move belongs to it.
            pv = (pv + tail).take(2 * mate - 1)
        }
        if (pv.size <= best.pv.size) {
            Log.w(TAG, "Mate in $mate but the PV has only ${best.pv.size} moves and could not be extended: $fen")
            return best
        }
        Log.d(TAG, "Mate in $mate: PV extended from ${best.pv.size} to ${pv.size} moves in $rounds searches")
        return best.copy(pv = pv)
    }

    /**
     * [analyzeMulti] for the user-facing search of [placement]. While it runs, the capture
     * thread can cut it short when the board moves on ([interruptStaleSearch]); the caller must
     * check [positionStale] afterwards.
     */
    private fun analyzeInterruptibly(fen: String, placement: String, searchMs: Int): List<AnalysisResult>? {
        positionStale = false
        searchingPlacement = placement
        try {
            return analyzeMulti(fen, searchMs)
        } finally {
            searchingPlacement = null
        }
    }

    /**
     * [classifyMove] for the move that led to [placement]. Classifying takes several short
     * engine searches during which the worker sees no frames, so a quick reply on the board
     * used to wait for all of them before its own search could start. Like the main search it
     * can therefore be cut short when the board moves on ([interruptStaleSearch]); a
     * classification from interrupted searches is unreliable and is dropped.
     */
    private fun classifyInterruptibly(
        placement: String,
        beforeFen: String,
        move: InferredMove,
        beforeAnalysis: AnalysisResult?,
        olderAnalysis: AnalysisResult?,
        multiPvBefore: List<AnalysisResult>?,
    ): Pair<AnalysisResult?, ClassifiedMove?> {
        positionStale = false
        searchingPlacement = placement
        val result = try {
            classifyMove(beforeFen, move, beforeAnalysis, olderAnalysis, multiPvBefore)
        } finally {
            searchingPlacement = null
        }
        if (positionStale) {
            Log.d(TAG, "Board moved on while classifying ${move.uci}; classification dropped")
            return beforeAnalysis to null
        }
        return result
    }

    /**
     * Capture or poll thread: called when the board is looked at while the worker is busy. If
     * the worker is in the main search or classifying a move, and accessibility data shows
     * another placement than the one it works on, the engine is stopped from here (the worker
     * itself is blocked inside it and cannot do so), which makes it return within moments.
     */
    private fun interruptStaleSearch() {
        val searching = searchingPlacement ?: return
        val live = liveAccessibilityPlacement() ?: return
        if (live == searching) return
        // The worker may have moved on to other engine work since the read above.
        if (searchingPlacement != searching) return
        positionStale = true
        engine?.interruptSearch()
    }

    /**
     * The placement accessibility data shows right now, from the same source
     * [processAccessibilityPositions] takes it from (the tracked FEN when move tracking is
     * active, else the reported positions), or null when the accessibility path is not in use.
     * Any thread.
     */
    private fun liveAccessibilityPlacement(): String? {
        val positions = freshAccessibilityPositions() ?: return null
        val tracked = AccessibilityData.trackedFen
        if (tracked != null && AccessibilityData.isRecent()) return tracked.substringBefore(' ')
        return try {
            BoardPositionParser.positionsToFen(positions).substringBefore(' ')
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * Poll thread, every [ACCESSIBILITY_POLL_MS]: hands a placement newly reported by the
     * accessibility service to the worker right away instead of with the next screen frame.
     * Only ordinary moves are taken this way (see [isClearMoveFromCurrent]); anything else
     * (new game, resync, orientation not settled) is left to the frame path, which has the
     * screen to cross-check against. While the worker is busy, this also stops a search of a
     * position that is no longer on the board sooner than the next frame would.
     */
    private fun pollAccessibility() {
        try {
            if (!running.get()) return
            checkTargetLeft()
            if (!accessibilityFrameSeen) return
            val live = liveAccessibilityPlacement() ?: return
            if (live == polledPlacement) return
            if (!processing.compareAndSet(false, true)) {
                interruptStaleSearch()
                return
            }
            // Whatever happens below, the flag must not stay set without a task to reset it.
            var handedOff = false
            try {
                val positions = freshAccessibilityPositions() ?: return
                polledPlacement = live
                val reportedOrientation = AccessibilityData.userPlaysWhite
                handedOff = submit {
                    try {
                        processAccessibilityPositions(positions, reportedOrientation, null, clearMoveOnly = true)
                    } finally {
                        processing.set(false)
                    }
                }
            } finally {
                if (!handedOff) processing.set(false)
            }
        } catch (e: RuntimeException) {
            // An exception escaping a scheduled task would silently end the polling.
            Log.w(TAG, "Accessibility poll failed", e)
        }
    }

    /**
     * Whether the chess app is on screen as far as the accessibility service can tell. Package
     * detection ([AccessibilityData.targetPackageVisible]) alone is not reliable: on some
     * devices another package (launcher, system UI) reports itself as the active window while
     * the chess app is in front. A view tree read that found the board moments ago is proof
     * that it is there, so it counts as well. Any thread.
     */
    private fun chessAppOnScreen(): Boolean =
        AccessibilityData.targetPackageVisible || AccessibilityData.isRecent(ACCESSIBILITY_CONFIRMED_MS)

    /**
     * Poll thread: takes the overlay down soon after the chess app stops being in front.
     * Without this the arrows stayed over the next app until the vision path had missed the
     * board on [MISSED_FRAMES_BEFORE_CLEAR] frames. The app must be gone ([chessAppOnScreen])
     * on [TARGET_GONE_POLLS] polls in a row, so a flicker of the package detection does not
     * wipe a position that is still on the board. The overlay is cleared from here, as the
     * worker may be inside a search; that search is stopped and its result dropped, and the
     * worker then forgets the placement so the position is drawn again when the board is back.
     */
    private fun checkTargetLeft() {
        if (chessAppOnScreen()) {
            targetGonePolls = 0
            targetWasVisible = true
            return
        }
        if (!targetWasVisible) return
        if (++targetGonePolls < TARGET_GONE_POLLS) return
        targetWasVisible = false
        targetGonePolls = 0
        if (!running.get()) return
        Log.d(TAG, "Chess app left the screen; clearing the overlay")
        if (searchingPlacement != null) {
            positionStale = true
            engine?.interruptSearch()
        }
        mainHandler.post {
            OverlayService.instance?.clearSuggestion()
            OverlayService.instance?.clearOpponentBadge()
        }
        submit {
            if (!running.get()) return@submit
            candidatePlacement = null
            candidateCount = 0
            candidateHistory.clear()
            candidateFramesSinceStable = 0
            stablePlacement = null
            missedFrames = 0
            if (boardVisible) {
                boardVisible = false
                lastHintStatus = STATUS_LOOKING
                postStatus(STATUS_LOOKING)
            }
            postOverlayClear()
        }
    }

    /**
     * Called after the user-facing search of [placement]. A search that gave nothing (engine
     * died or timed out, answer dropped by [validatedAnalyses]) leaves the user without arrows
     * until the board changes, because [stablePlacement] already equals the placement. It is
     * therefore forgotten so the next frame searches again, at most [MAX_ANALYSIS_RETRIES]
     * times per placement.
     */
    private fun retryIfAnalysisFailed(placement: String, analyses: List<AnalysisResult>?) {
        if (!analyses.isNullOrEmpty()) {
            failedPlacement = null
            failedRetries = 0
            return
        }
        if (failedPlacement != placement) {
            failedPlacement = placement
            failedRetries = 0
        }
        if (failedRetries >= MAX_ANALYSIS_RETRIES) {
            Log.w(TAG, "No analysis after $failedRetries retries, giving up on: $placement")
            return
        }
        failedRetries++
        Log.w(TAG, "No analysis, retry $failedRetries of $MAX_ANALYSIS_RETRIES on the next frame: $placement")
        stablePlacement = null
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

        if (!processing.compareAndSet(false, true)) {
            // The worker is busy; if that is a search of a position no longer on the board,
            // stop it from this thread so the worker gets to the new position quickly.
            interruptStaleSearch()
            return
        }

        // From here on the flag is set. It is reset by the worker task once one was accepted;
        // on every other way out (nothing to do, rejected task, anything thrown) it is reset
        // here, or no frame would ever be processed again.
        var handedOff = false
        try {
            // Check if accessibility data is available (higher accuracy than vision). When it is
            // fresh and complete enough, piece recognition is skipped for this frame; the frame is
            // still used to locate the board so the overlay has bounds to draw arrows in.
            val accessibilityPositions = freshAccessibilityPositions()
            if (accessibilityPositions != null) {
                accessibilityFrameSeen = true
                // The accessibility squares are absolute, so the screen orientation (needed to draw
                // the arrows) must come from elsewhere: the accessibility service when it can tell,
                // otherwise a cross-check against this frame (see processAccessibilityPositions).
                val reportedOrientation = AccessibilityData.userPlaysWhite
                // A failed copy only costs the bounds refresh; previously known bounds are reused.
                val frame = copyBitmap(bitmap)
                handedOff = submit {
                    try {
                        val board = if (frame != null) updateBoundsFromFrame(frame) else null
                        // Only a frame that was actually looked at says whether the board is there.
                        if (frame != null) noteAccessibilityBoard(board != null)
                        processAccessibilityPositions(accessibilityPositions, reportedOrientation, board)
                    } finally {
                        frame?.recycle()
                        processing.set(false)
                    }
                }
                if (!handedOff) frame?.recycle()
                return
            }

            val copy = copyBitmap(bitmap) ?: return
            handedOff = submit {
                try {
                    processFrame(copy)
                } finally {
                    copy.recycle()
                    processing.set(false)
                }
            }
            if (!handedOff) copy.recycle()
        } finally {
            if (!handedOff) processing.set(false)
        }
    }

    private fun copyBitmap(bitmap: Bitmap): Bitmap? = try {
        if (bitmap.isRecycled) null else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory copying frame")
        null
    } catch (e: RuntimeException) {
        // E.g. IllegalStateException when the capture thread recycles the bitmap meanwhile.
        Log.w(TAG, "Could not copy frame", e)
        null
    }

    /** Runs [task] on the worker thread, logging failures. Returns false if it was rejected. */
    private fun submit(task: () -> Unit): Boolean = try {
        worker.execute {
            try {
                task()
                workerFailures = 0
            } catch (t: Exception) {
                Log.e(TAG, "Worker task failed", t)
                recoverFromWorkerFailure()
            }
        }
        true
    } catch (e: RejectedExecutionException) {
        false
    }

    /**
     * Worker thread, after a task threw. The placement it was handling is already recorded as
     * [stablePlacement], so without this it would never be looked at again and the position
     * would stay without arrows until the board changes. It is forgotten so the next frame
     * starts over from the board, and a forced mate being followed is dropped with it (its
     * bookkeeping may be half done), which sends that position through a normal search. Not
     * repeated endlessly: after [MAX_WORKER_FAILURE_RETRIES] failures in a row the position is
     * left alone until the board changes.
     */
    private fun recoverFromWorkerFailure() {
        mateLine = null
        mateDelivered = false
        if (workerFailures >= MAX_WORKER_FAILURE_RETRIES) return
        workerFailures++
        stablePlacement = null
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
        accessibilityMissedBoards = 0
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
            // Fast path: a finished move of the opponent is taken from the first read, so the
            // search for the user's reply starts one frame interval (500 ms at 2 fps) sooner.
            isClearOpponentMove(placement) -> {
                Log.d(TAG, "Clear opponent move read once; not waiting for a second frame")
                placement
            }
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
     * Accessibility path, once per frame that was looked at: counts the frames in a row without
     * a board on screen. The accessibility positions stay "fresh" for
     * [ACCESSIBILITY_MAX_AGE_VISIBLE_MS] after the board is gone (another screen of the chess
     * app), and an unchanged placement is never drawn or cleared again, so the arrows used to
     * stay on screen for that long. After [MISSED_FRAMES_BEFORE_CLEAR] such frames the overlay
     * is cleared and the placement forgotten; until the board is located again
     * [processAccessibilityPositions] draws nothing, then the position is analysed afresh.
     *
     * A frame without a located board is only counted while the view tree does not show the
     * board either. Not locating it is no proof that it is gone (the locator also fails on
     * frames with arrows or a highlight across the grid lines, or mid-animation), while a tree
     * read that found the pieces moments ago is proof that it is there. Without this, a board
     * the locator had trouble with lost its arrows every few seconds, or for good.
     */
    private fun noteAccessibilityBoard(found: Boolean) {
        if (found || AccessibilityData.isRecent(ACCESSIBILITY_CONFIRMED_MS)) {
            accessibilityMissedBoards = 0
            return
        }
        if (!running.get()) return
        accessibilityMissedBoards++
        if (accessibilityMissedBoards != MISSED_FRAMES_BEFORE_CLEAR) return
        Log.d(TAG, "No board on screen for $accessibilityMissedBoards accessibility frames; clearing the overlay")
        stablePlacement = null
        postOverlayClear()
        if (boardVisible) {
            boardVisible = false
            lastHintStatus = STATUS_LOOKING
            postStatus(STATUS_LOOKING)
        }
    }

    /**
     * Accessibility counterpart of [processFrame]: converts the reported positions to a FEN and
     * feeds its placement into the same move-tracking path ([onPositionChanged]) that sets
     * [currentFen] for vision reads. Accessibility data is exact, so no multi-frame stability
     * check is needed; an unchanged placement is simply ignored. With [clearMoveOnly] (calls
     * from [pollAccessibility], which have no frame and so no [board]) anything but an ordinary
     * move is ignored as well and left to the next frame.
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
        clearMoveOnly: Boolean = false,
    ) {
        if (!running.get()) return
        // The board is not on screen (see noteAccessibilityBoard): nothing to draw arrows on.
        if (board == null && accessibilityMissedBoards >= MISSED_FRAMES_BEFORE_CLEAR) return
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
        // Between frames ([pollAccessibility]) only an ordinary move is taken.
        if (clearMoveOnly && !isClearMoveFromCurrent(placement, reportedOrientation)) return

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
     * The single pseudo-legal move that turns the tracked position into [placement], or null
     * if there is none or move tracking is not in step with [stablePlacement].
     */
    private fun moveFromCurrent(placement: String): InferredMove? {
        if (placement == stablePlacement || placement == START_PLACEMENT) return null
        val cur = currentFen ?: return null
        val curPlacement = cur.substringBefore(' ')
        // A null stablePlacement only means the placement must be confirmed again (e.g. after a
        // search that was cut short); the tracked position is still the one to diff against.
        val stable = stablePlacement
        if (stable != null && curPlacement != stable) return null
        val before = parsePlacement(curPlacement) ?: return null
        val after = parsePlacement(placement) ?: return null
        return MoveInference.infer(before, after, epSquareOf(cur))
    }

    /**
     * Accessibility path between frames: true if [placement] is one ordinary move away from
     * the tracked position and the screen orientation is settled, so it can be processed
     * without a frame to cross-check the orientation against.
     */
    private fun isClearMoveFromCurrent(placement: String, reportedOrientation: Boolean?): Boolean {
        if (reportedOrientation == null &&
            (whiteAtBottom == null || orientationConfidence != CONFIDENCE_HIGH)
        ) {
            return false
        }
        return moveFromCurrent(placement) != null || isMateLineJump(placement)
    }

    /**
     * True if [placement] is the tracked position with the next few plies of the forced mate on
     * screen played (the opponent's reply plus a queued premove arrive as one change). Such a
     * jump is as unambiguous as a single move, so it need not wait for a screen frame.
     */
    private fun isMateLineJump(placement: String): Boolean {
        val line = mateLine ?: return false
        val cur = currentFen ?: return false
        val curPlacement = cur.substringBefore(' ')
        val stable = stablePlacement
        if (placement == curPlacement || (stable != null && curPlacement != stable)) return false
        val before = parsePlacement(curPlacement) ?: return false
        val after = parsePlacement(placement) ?: return false
        return matchMateLine(line, before, after) != null
    }

    /**
     * Vision path: true if a single read of [placement] can be trusted without the usual
     * second identical read. That is the case when it is exactly one move of the opponent,
     * made on the opponent's turn, to a square the piece cannot be merely passing over.
     *
     * The stability check exists for frames caught mid-animation or mid-drag. The opponent's
     * pieces are never dragged on the user's screen, and of an animated piece only a slider
     * (or a pawn's double step, or the king while castling) can be read on a square short of
     * its destination and still look like a legal move. Those cases keep the two-read rule;
     * the user's own moves keep it too, as a dragged piece can hover over any square.
     */
    private fun isClearOpponentMove(placement: String): Boolean {
        val userIsWhite = whiteAtBottom ?: return false
        if (orientationConfidence == CONFIDENCE_LOW) return false
        val cur = currentFen ?: return false
        if (sideToMoveIsWhite(cur) == userIsWhite) return false
        val move = moveFromCurrent(placement) ?: return false
        if (move.white == userIsWhite) return false
        val before = parsePlacement(cur.substringBefore(' ')) ?: return false
        val after = parsePlacement(placement) ?: return false
        return isFinalDestination(before, after, move)
    }

    /** False if the piece of [move] could be caught in passing, on its way to a square beyond. */
    private fun isFinalDestination(before: CharArray, after: CharArray, move: InferredMove): Boolean {
        val fromFile = move.from % 8
        val fromRank = move.from / 8
        val toFile = move.to % 8
        val toRank = move.to / 8
        val capture = before[move.to] != EMPTY || move.enPassant
        return when (before[move.from].lowercaseChar()) {
            'n' -> true
            // A single king step may be the first half of castling; castling itself is complete.
            'k' -> abs(toFile - fromFile) == 2
            'p' -> {
                val dir = if (move.white) 1 else -1
                val startRank = if (move.white) 1 else 6
                // Only a single step off the starting rank can still become a double step.
                capture || move.promotion != null || abs(toRank - fromRank) == 2 ||
                    fromRank != startRank || after[move.to + 8 * dir] != EMPTY
            }
            'b', 'r', 'q' -> {
                if (capture) return true
                // A slider that could go on along its line may still be moving.
                val nextFile = toFile + Integer.signum(toFile - fromFile)
                val nextRank = toRank + Integer.signum(toRank - fromRank)
                if (nextFile !in 0..7 || nextRank !in 0..7) return true
                val beyond = after[nextRank * 8 + nextFile]
                beyond != EMPTY && beyond.isUpperCase() == move.white
            }
            else -> false
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
            // A placement seen while the orientation was unknown was accepted as stable but
            // possibly never analysed (onPositionChanged gives up without an orientation). It
            // matches the default drawn before, so the branch above does not reset it; forget
            // it here so the current position is analysed now, unless it already has arrows.
            if (currentAnalyses.isNullOrEmpty()) stablePlacement = null
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
        // A forced mate of the old tracking state must not be continued by a move of the new one.
        mateLine = null
        mateDelivered = false
        // Retries belong to the placement that was just forgotten.
        failedPlacement = null
        failedRetries = 0
        guardedPlacement = null
        guardedCount = 0
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
        checkTurnTrackingSources()
        val orientationKnown = whiteAtBottom != null || lastKnownOrientation != null
        val userIsWhite = whiteAtBottom ?: lastKnownOrientation ?: true
        var prevFen = currentFen
        val after = parsePlacement(placement) ?: return

        var move = if (prevFen != null && placement != START_PLACEMENT) {
            val before = parsePlacement(prevFen.substringBefore(' '))
            if (before != null) MoveInference.infer(before, after, epSquareOf(prevFen)) else null
        } else {
            null
        }

        // Not one move, but a forced mate is being followed: a queued premove is played together
        // with the opponent's reply, so the board skips a position. If the change is the next
        // plies of the mate, the skipped ones are recorded here and the last one is handled
        // below like any single move.
        // The same position read again while a forced mate is being followed (a forced re-read,
        // a flickering event): nothing was played, so the line and its arrows stay as they are.
        // Without this the "no move found" path below would drop the line and search again.
        val followed = mateLine
        if (move == null && prevFen != null && followed != null && followed.played > 0 &&
            placement == prevFen.substringBefore(' ')
        ) {
            val remaining = remainingMateLines(followed)
            if (remaining.isNotEmpty()) {
                Log.d(TAG, "MateLine re-read of the same position: played=${followed.played}, " +
                    "pv.size=${followed.pv.size}, next=${followed.pv.getOrNull(followed.played)}")
                postMultiSuggestion(remaining, followed.flipped)
                return
            }
        }

        if (move == null && prevFen != null && placement != START_PLACEMENT && mateLine != null) {
            val before = parsePlacement(prevFen.substringBefore(' '))
            val last = if (before != null) catchUpMateLine(before, after) else null
            if (last != null) {
                move = last
                prevFen = currentFen
            }
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
                    if (diffs in 1..3) {
                        if (placement == guardedPlacement) {
                            guardedCount++
                        } else {
                            guardedPlacement = placement
                            guardedCount = 1
                        }
                        // Not accepted, so it must not count as the stable placement either: it
                        // would never be looked at again, and a real change of this size (a
                        // capture and the recapture on the same square is 3 squares) would be
                        // lost for good. It has to be read again.
                        stablePlacement = null
                        if (guardedCount < ARROW_GUARD_ACCEPT_HITS) {
                            if (guardedCount == ARROW_GUARD_CLEAR_HITS) {
                                // It persists. Take the arrows away: a misread caused by them
                                // disappears with them, a real position is still there after.
                                Log.d(TAG, "Unexplained small change read $guardedCount times; " +
                                    "clearing the arrows to tell a misread from a real change: $placement")
                                postOverlayClear()
                            }
                            return
                        }
                        Log.i(TAG, "Small change still read with the arrows gone, accepting it: $placement")
                    }
                }
            }
            guardedPlacement = null
            guardedCount = 0
            // Whatever happened, it was not the forced mate on screen: fresh analysis decides.
            mateLine?.let {
                Log.d(TAG, "MateLine dropped, board change is not on the line: played=${it.played}, " +
                    "pv.size=${it.pv.size}, next=${it.pv.getOrNull(it.played)}, " +
                    "from=${prevFen?.substringBefore(' ')}, to=$placement")
            }
            mateLine = null
            resyncTo(
                placement,
                after,
                if (placement == START_PLACEMENT) true else resyncSideToMove(placement, prevFen, userIsWhite),
            )
            return
        }

        guardedPlacement = null
        guardedCount = 0

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
            // Following the forced mate on screen: its remaining arrows stay, the user is
            // about to premove them. Any other move clears the overlay as usual.
            val remaining = advanceMateLine(move)
            // Read now: classifying below can take a while and must not see a later value.
            val delivered = mateDelivered
            if (remaining.isNotEmpty()) {
                postMultiSuggestion(remaining, mateLine?.flipped ?: !userIsWhite)
            } else {
                postOverlayClear()
                // The last move of the mate was played: nothing left to show but the result.
                if (delivered) postCoachMessage(MATE_DELIVERED_COMMENTARY)
            }
            val (analysedBefore, classified) =
                classifyInterruptibly(placement, beforeFen, move, beforeAnalysis, olderAnalysis, multiPvForClassifier)
            if (!running.get()) return
            previousAnalysis = analysedBefore
            lastClassified = classified
            lastUserClassified = classified
            // Tell the user right away how their move was, and what was better if it was not.
            // beforeAnalysis is the top line that was on screen (null if nothing was shown), so a
            // match means the user simply followed the arrow: no surprise, just a brief nod.
            if (delivered) {
                // The celebration stays; a "best move" nod would only replace it.
            } else if (classified != null) {
                val suggested = beforeAnalysis?.bestMove?.trim()
                // A premove of the mate played without a fresh search has no beforeAnalysis,
                // but it was the arrow on screen all the same.
                postCoachReaction(
                    classified,
                    beforeFen,
                    remaining.isNotEmpty() || suggested.equals(move.uci, ignoreCase = true),
                )
            } else {
                Log.w(TAG, "User move ${move.uci} could not be classified: no coach reaction")
            }
            return
        }
        // Opponent just moved → user's turn.  The user-facing analysis runs first so the arrows
        // appear as soon as possible; the opponent's move is classified afterwards.
        // A position shown moments ago (duplicate or flickering events) is not searched again.
        // The opponent played the reply the forced mate on screen expected: the user's remaining
        // premoves stay visible through the search, until the new analysis replaces them.
        val remainingMate = advanceMateLine(move)
        if (remainingMate.isNotEmpty()) {
            // The mate is forced and its line already known: searching this position again
            // would only replace the line (often by a different or shallower one, with `played`
            // back at 0) while the user's premove is already on its way, which is what made the
            // arrows stop advancing. Show the next step at once and wait for the next move.
            postMultiSuggestion(remainingMate, mateLine?.flipped ?: !userIsWhite)
            previousFen = beforeFen
            previousAnalysis = beforeAnalysis
            currentAnalyses = null
            lastKnownGoodFen = newFen
            lastMove = move
            lastClassified = null
            postClearOpponentBadge()
            EngineStats.lastFen = newFen
            EngineStats.whiteAtBottom = whiteAtBottom
            return
        }
        val newAnalyses = recentlyShown(newFen) ?: run {
            val searchMs = searchTimeMs()
            postOverlayThinking(searchMs)
            analyzeInterruptibly(newFen, placement, searchMs)
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
        val live = liveAccessibilityPlacement()
        if (positionStale || (live != null && live != placement)) {
            // An interrupted search is too shallow to keep for classifying the next move.
            if (positionStale) currentAnalyses = null
            stablePlacement = null
            postOverlayClear()
            return
        }
        render()
        retryIfAnalysisFailed(placement, newAnalyses)

        // Only now, with the arrows on screen, classify the opponent's move for its badge.
        val (analysedBefore, classified) =
            classifyInterruptibly(placement, beforeFen, move, beforeAnalysis, olderAnalysis, multiPvForClassifier)
        if (!running.get()) return
        previousAnalysis = analysedBefore
        lastClassified = classified
        if (opponentJustMoved && classified != null) {
            postOpponentBadge(move.uci.substring(2, 4), classified.classification.displayName, !userIsWhite)
            // The coach's line was written before the opponent's move was classified. When that
            // move turns out to be remarkable, render again so the coach can react to it.
            if (classified.classification in COACH_REACTS_TO_OPPONENT) {
                val stillLive = liveAccessibilityPlacement()
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
        } catch (ex: RuntimeException) {
            // Any failure here only costs the classification; it must not reach the worker's
            // failure handling, which would blank the arrows and search the position again.
            Log.w(TAG, "Could not classify ${move.uci}", ex)
            null
        }
        return analysis to classified
    }

    /** Starts tracking from [placement] without a known previous move. */
    private fun resyncTo(placement: String, board: CharArray, whiteToMove: Boolean) {
        // Prefer the accessibility service's side-to-move when available; the caller's guess
        // (typically userIsWhite) is only a fallback.
        // The starting position is always white's move. The service only updates its
        // side-to-move while the move list has moves, so at a rematch it still holds the value
        // of the game before.
        var sideToMove = if (placement == START_PLACEMENT) true else AccessibilityData.whiteToMove ?: whiteToMove
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
            analyzeInterruptibly(fen, placement, searchMs)
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
        val live = liveAccessibilityPlacement()
        if (positionStale || (live != null && live != placement)) {
            // An interrupted search is too shallow to keep for classifying the next move.
            if (positionStale) currentAnalyses = null
            stablePlacement = null
            postOverlayClear()
            return
        }
        render()
        retryIfAnalysisFailed(placement, analyses)
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
        accessibilityMissedBoards = 0
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
        // Forced mate by the user: the best arrow is move 1 of the sequence (the bold teal
        // arrow, itself unnumbered). Any other line keeps step 0.
        val mateStep = if (moverMate != null && moverMate > 0) 1 else 0
        // Mate in one: the best arrow is itself the mating move, so it carries the mate target.
        suggestions[0] = best.copy(
            commentary = commentary,
            step = mateStep,
            isMateTarget = moverMate == 1,
            mateKingSquare = if (moverMate == 1) opponentKingSquare(fen) else null,
        )
        // Forced mate only: the user's later moves of the mating line, numbered 2, 3, ..., with
        // the opponent's reply before each of them. The line that delivers mate is the target.
        val premoves = premoveLines(bestAnalysis, best, sign, fen)
        suggestions += premoves
        mateLine = when {
            bestAnalysis == null -> null
            premoves.isNotEmpty() -> MateLine(bestAnalysis.pv, premoves, flipped)
            // Mate in one: no premoves, but the line is kept (with no arrows after the first)
            // so that playing the arrow is recognised as delivering the mate.
            moverMate == 1 -> MateLine(listOf(bestAnalysis.bestMove), emptyList(), flipped)
            else -> null
        }

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
     * be drawn with their number. The PV alternates user move, opponent reply, ..., so the
     * user's moves sit at the even indices.
     *
     * A forced mate shows every remaining move of the mate (up to [MAX_MATE_PREMOVES]).
     * Each line carries both squares of its UCI move: `from` is the piece to move (the overlay
     * numbers it with `step`), `to` is where it goes. `step` runs 2, 3, ...; step 1 is the best
     * arrow itself, set in [render]. Note that `from` is the square in the position after the
     * earlier moves: a piece that moves twice in the mate has its later `from` on the earlier
     * move's `to`, which may be empty on the current board.
     * Any other line shows no follow-up moves at all: numbered steps are reserved for forced
     * mates, so an ordinary suggestion is just its one arrow.
     *
     * The opponent's replies (the odd PV indices) are returned too, each right before the user
     * move that answers it, in PV order. A reply has [SuggestedLineData.isOpponentReply] set, no
     * mate distance, and the step of the user move it replies to: the reply to the best arrow is
     * step 1, the reply to premove 2 is step 2, and so on. A reply is only returned when the user
     * move after it is, so the sequence always ends on a user move.
     * The user move that delivers the mate (mate in 1 left) has
     * [SuggestedLineData.isMateTarget] set. It is absent when the sequence is cut short, by
     * [MAX_MATE_PREMOVES] or by a PV that ends early.
     * That line also carries [SuggestedLineData.mateKingSquare]: the opponent king's square at
     * the moment of mate, taken from [fen] and moved along with any king moves among the replies.
     * @param sign +1 if the user (side to move) is white, -1 if black
     * @param fen the position on the board now, with the user to move
     */
    private fun premoveLines(
        analysis: AnalysisResult?,
        best: SuggestedLineData,
        sign: Int,
        fen: String,
    ): List<SuggestedLineData> {
        analysis ?: return emptyList()
        val pv = analysis.pv
        // The PV must be the line of the arrow on screen, not a leftover of another move.
        if (pv.firstOrNull()?.startsWith(best.from + best.to) != true) return emptyList()

        // Only a mate the user delivers has premoves: not an ordinary line, not getting mated.
        val userMate = analysis.mate?.takeIf { it > 0 } ?: return emptyList()
        val count = minOf(userMate - 1, MAX_MATE_PREMOVES)

        val lines = ArrayList<SuggestedLineData>(2 * count.coerceAtLeast(0))
        // Where the opponent's king stands; follows the king through the replies below.
        var kingSquare = opponentKingSquare(fen)
        for (step in 2..count + 1) {
            val index = (step - 1) * 2
            val move = pv.getOrNull(index)
            if (move == null || !isUciMove(move) || !isUciMove(pv[index - 1])) {
                Log.w(TAG, "Mate in $userMate: PV ends before move $step (${pv.size} plies), " +
                    "showing ${lines.count { !it.isOpponentReply }} of $count premoves")
                break
            }
            // The opponent's reply to the user's previous move (step - 1), played before this one.
            val reply = pv[index - 1]
            // A reply that starts on the king's square is a king move (castling included: its
            // UCI form is the king's own two-square move).
            if (reply.substring(0, 2) == kingSquare) kingSquare = reply.substring(2, 4)
            lines += SuggestedLineData(
                from = reply.substring(0, 2),
                to = reply.substring(2, 4),
                evalCp = best.evalCp,
                mateIn = null,
                label = "",
                labelColor = MoveColors.NEUTRAL,
                rank = 1,
                step = step - 1,
                isOpponentReply = true,
            )
            // Mate distance left once the earlier moves are played, so the last arrow is M1.
            val left = userMate - (step - 1)
            lines += SuggestedLineData(
                from = move.substring(0, 2),
                to = move.substring(2, 4),
                evalCp = best.evalCp,
                mateIn = left * sign,
                label = mateLabel(left),
                // Same colors as render() gives the best arrow, so the move looks the same
                // when it becomes the move to play: gold only for the mating move itself.
                labelColor = if (left == 1) MoveColors.MATE else MoveColors.BEST,
                rank = 1,
                step = step,
                isMateTarget = left == 1,
                mateKingSquare = if (left == 1) kingSquare else null,
            )
        }
        return lines
    }

    /**
     * Square of the king of the side NOT to move in [fen] (the opponent when it is the user's
     * turn), e.g. "g8". Null if the FEN is malformed or has no such king.
     */
    private fun opponentKingSquare(fen: String): String? {
        val fields = fen.trim().split(' ')
        val king = when (fields.getOrNull(1)) {
            "w" -> 'k'
            "b" -> 'K'
            else -> return null
        }
        val ranks = fields[0].split('/')
        if (ranks.size != 8) return null
        for ((row, rankText) in ranks.withIndex()) {
            var file = 0
            for (c in rankText) {
                if (c.isDigit()) {
                    file += c - '0'
                } else {
                    if (c == king && file in 0..7) return "${'a' + file}${8 - row}"
                    file++
                }
            }
        }
        return null
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
                        isOpponentReply = s.isOpponentReply,
                        isMateTarget = s.isMateTarget,
                        mateKingSquare = s.mateKingSquare,
                    )
                }
                overlay.showMultiSuggestion(lines, flipped)
            }
        }
    }

    private fun postOverlayClear() {
        // Nothing of a forced mate is on screen any more, so there is nothing to keep.
        mateLine = null
        mainHandler.post {
            // The opponent's badge belongs to the position whose arrows are removed here.
            OverlayService.instance?.clearSuggestion()
            OverlayService.instance?.clearOpponentBadge()
        }
    }

    /**
     * Records [move] as played against the forced mate on screen ([mateLine]) and returns the
     * arrows of that mate still to be played, one step further on ([remainingMateLines]).
     * Empty, and the line forgotten, when no mate is on screen, [move] is not its next move
     * (the user left the line, or the opponent found another reply: fresh analysis decides), or
     * nothing is left of it. [mateDelivered] tells the last case apart: the mate was played out.
     */
    private fun advanceMateLine(move: InferredMove): List<SuggestedLineData> {
        mateDelivered = false
        val line = mateLine ?: return emptyList()
        val expected = line.pv.getOrNull(line.played)
        // Compared by squares only: a promotion piece may be written differently or be missing.
        val uci = move.uci
        if (expected == null || expected.length < 4 || !expected.regionMatches(0, uci, 0, 4, ignoreCase = true)) {
            Log.d(TAG, "MateLine left: move=$uci, expected=$expected, played=${line.played}, pv.size=${line.pv.size}")
            mateLine = null
            return emptyList()
        }
        line.played++
        val remaining = remainingMateLines(line)
        Log.d(TAG, "MateLine advance: played=${line.played}, pv.size=${line.pv.size}, " +
            "next=${line.pv.getOrNull(line.played)}, move=$uci, arrows left=${remaining.size}")
        if (remaining.isEmpty()) {
            // Only a line drawn to its end has the mating move as its last arrow; one cut short
            // (MAX_MATE_PREMOVES, incomplete PV) just runs out and is searched again.
            val wasLastUserMove = line.played % 2 == 1 && line.played - 1 >= line.lines.size
            // A mate in one has no arrows after the first: its only move is the mating move.
            mateDelivered = wasLastUserMove &&
                (line.lines.lastOrNull()?.isMateTarget == true || (line.lines.isEmpty() && line.played == 1))
            mateLine = null
        }
        return remaining
    }

    /**
     * What is left of [line] after its first [MateLine.played] plies, renumbered so that the
     * picture is the one [render] draws for the position reached:
     * - the user's next move is step 1, the bold main arrow with its own "M<n>" pill (and the
     *   king's ring when it is the mating move); the moves after it are steps 2, 3, ...;
     * - moves already played are gone, and so is the reply to a move the user has just played:
     *   the opponent is about to answer it, and the user is looking at the move to premove;
     * - the later replies keep the step of the user move they answer.
     * Empty when no user move is left.
     */
    private fun remainingMateLines(line: MateLine): List<SuggestedLineData> {
        // lines[i] is pv[i + 1], so with `played` plies done the first one left is lines[played - 1].
        var rest = line.lines.drop((line.played - 1).coerceAtLeast(0))
        // An odd number of plies played: the user moved last and rest starts with the reply to it.
        if (line.played % 2 == 1 && rest.firstOrNull()?.isOpponentReply == true) rest = rest.drop(1)
        val next = rest.firstOrNull { !it.isOpponentReply } ?: return emptyList()
        val shift = next.step - 1
        var first = true
        return rest.map { data ->
            when {
                data.isOpponentReply -> data.copy(step = (data.step - shift).coerceAtLeast(1))
                first -> {
                    first = false
                    // The overlay takes its coach text from the main arrow; no notation in it.
                    data.copy(
                        step = 1,
                        commentary = if (data.isMateTarget) MATE_LAST_MOVE_COMMENTARY else MATE_FOLLOW_COMMENTARY,
                    )
                }
                else -> data.copy(step = data.step - shift)
            }
        }
    }

    /**
     * Explains a board change that is not one move ([before] to [after]) as the next two or
     * three plies of the forced mate on screen: the opponent's reply and the premove that was
     * played right after it arrive as a single change, and a slow read can add the user's own
     * move before them.
     *
     * On a match every ply but the last is recorded here: [MateLine.played], castling rights,
     * move counters and [currentFen] move on as if each position had been seen, and the analyses
     * of the old position are dropped. The last ply is returned for the caller to handle like
     * any single move, with [currentFen] the position right before it. Null, and nothing
     * changed, when [after] is not on the line: the caller then starts over from the board.
     */
    private fun catchUpMateLine(before: CharArray, after: CharArray): InferredMove? {
        val line = mateLine ?: return null
        val match = matchMateLine(line, before, after)
        if (match == null) {
            Log.d(TAG, "MateLine catch-up: no match within $MAX_MATE_CATCH_UP_PLIES plies, " +
                "played=${line.played}, pv.size=${line.pv.size}, next=${line.pv.getOrNull(line.played)}, " +
                "from=${placementOf(before)}, to=${placementOf(after)}")
            return null
        }
        val (moves, boards) = match
        Log.d(TAG, "MateLine catch-up: ${moves.size} plies played at once " +
            "(${moves.joinToString(" ") { it.uci }}) from played=${line.played}, pv.size=${line.pv.size}")
        for (i in 0 until moves.size - 1) {
            val move = moves[i]
            val from = boards[i]
            val to = boards[i + 1]
            castlingRights = updatedCastlingRights(castlingRights, move, to)
            val capture = from[move.to] != EMPTY || move.enPassant
            halfMoveClock = if (from[move.from].lowercaseChar() == 'p' || capture) 0 else halfMoveClock + 1
            if (!move.white) fullMoveNumber++
            currentFen = composeFen(
                placementOf(to), !move.white, castlingRights, enPassantTarget(move, to), fullMoveNumber,
            )
            lastInferredMoverWasWhite = move.white
            line.played++
        }
        if (moves.size > 1) {
            // They describe a position that is no longer the one before the move.
            currentAnalyses = null
            previousAnalysis = null
        }
        return moves.last()
    }

    /**
     * The next plies of [line] (from [MateLine.played] on, at most [MAX_MATE_CATCH_UP_PLIES])
     * that turn [before] into [after], with the boards along the way (`boards[0]` is [before],
     * `boards[i + 1]` the board after `moves[i]`). Null when [after] is not on the line.
     * Changes nothing.
     */
    private fun matchMateLine(
        line: MateLine,
        before: CharArray,
        after: CharArray,
    ): Pair<List<InferredMove>, List<CharArray>>? {
        val moves = ArrayList<InferredMove>(MAX_MATE_CATCH_UP_PLIES)
        val boards = ArrayList<CharArray>(MAX_MATE_CATCH_UP_PLIES + 1)
        boards += before
        for (k in 0 until MAX_MATE_CATCH_UP_PLIES) {
            val uci = line.pv.getOrNull(line.played + k) ?: break
            val (move, next) = applyUci(boards.last(), uci) ?: break
            moves += move
            boards += next
            if (next.contentEquals(after)) return moves to boards
        }
        return null
    }

    /**
     * Plays the UCI move [uci] on [board] without checking legality (it comes from an engine
     * PV): returns the move and the board after it, with castling, en passant and promotion
     * carried out. Null when [uci] is malformed or its start square is empty.
     */
    private fun applyUci(board: CharArray, uci: String): Pair<InferredMove, CharArray>? {
        if (!isUciMove(uci)) return null
        val from = squareIndex(uci.substring(0, 2)) ?: return null
        val to = squareIndex(uci.substring(2, 4)) ?: return null
        val piece = board[from]
        if (piece == EMPTY || from == to) return null
        val white = piece.isUpperCase()
        val kind = piece.lowercaseChar()
        val next = board.copyOf()
        next[from] = EMPTY
        var landed = piece
        var promotion: Char? = null
        var enPassant = false
        if (kind == 'p') {
            if (from % 8 != to % 8 && board[to] == EMPTY) {
                enPassant = true
                next[(from / 8) * 8 + to % 8] = EMPTY
            }
            if (to / 8 == (if (white) 7 else 0)) {
                val p = (uci.getOrNull(4) ?: 'q').lowercaseChar()
                if (p !in "nbrq") return null
                landed = if (white) p.uppercaseChar() else p
                promotion = landed
            }
        } else if (kind == 'k' && abs(to % 8 - from % 8) == 2) {
            val base = (from / 8) * 8
            val rookFrom = if (to % 8 == 6) base + 7 else base
            val rookTo = if (to % 8 == 6) base + 5 else base + 3
            next[rookTo] = next[rookFrom]
            next[rookFrom] = EMPTY
        }
        next[to] = landed
        return InferredMove(from, to, promotion, white, enPassant) to next
    }

    /** FEN placement field of [board] (64 squares, a1 = 0): the inverse of [parsePlacement]. */
    private fun placementOf(board: CharArray): String = buildString {
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val c = board[rank * 8 + file]
                if (c == EMPTY) {
                    empty++
                } else {
                    if (empty > 0) append(empty)
                    empty = 0
                    append(c)
                }
            }
            if (empty > 0) append(empty)
            if (rank > 0) append('/')
        }
    }

    private fun postCoachMessage(text: String) {
        mainHandler.post { if (running.get()) OverlayService.instance?.showCoachMessage(text) }
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
     * @param keepArrows leave the arrows of a forced mate that is being followed on screen
     */
    private fun postOverlayThinking(searchMs: Int, keepArrows: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + searchMs
        mainHandler.post {
            // Whatever the main thread's delay ate is taken off the countdown.
            val remaining = deadline - SystemClock.elapsedRealtime()
            OverlayService.instance?.showThinking(
                if (searchMs >= 1000) remaining.coerceAtLeast(0L) else 0L,
                keepArrows,
            )
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
        /**
         * Place in a forced mate the user delivers: 1 = the move to play now, 2, 3, ... = the
         * user's later moves of that mate. 0 = not part of a mate sequence (ordinary arrow).
         * For every step >= 1, [from] is the piece to move (where the overlay puts the number)
         * and [to] is where it goes.
         */
        val step: Int = 0,
        /**
         * An opponent reply inside the mate sequence, not a move for the user to play. Its
         * [step] is the step of the user move it replies to; it has no mate distance or label.
         */
        val isOpponentReply: Boolean = false,
        /** The user move that delivers the checkmate: the last move of the mate sequence. */
        val isMateTarget: Boolean = false,
        /**
         * Only on the [isMateTarget] line: the square the opponent's king is mated on (where the
         * overlay draws its ring). Null if the king could not be located.
         */
        val mateKingSquare: String? = null,
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
