package com.chesstrainer.app.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.chesstrainer.app.engine.AnalysisResult
import com.chesstrainer.app.engine.ClassifiedMove
import com.chesstrainer.app.engine.MoveClassifier
import com.chesstrainer.app.engine.MoveCommentary
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
 *     is accepted (filters out piece animations and drags).
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

        const val DEFAULT_DEPTH = 21

        /** Number of engine lines (Multi-PV) shown as suggestions. */
        private const val NUM_LINES = 5

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
         * Orientation hysteresis. Every read frame votes: a frame whose [Orientation.score]
         * contradicts the locked orientation adds 2 votes when the evidence is strong
         * (|score| >= [STRONG_ORIENTATION_SCORE]) and 1 when weak; an agreeing frame resets the
         * count. The orientation switches at [ORIENTATION_SWITCH_VOTES], i.e. after 2 strong or 4
         * weak contrary frames in a row.
         */
        private const val ORIENTATION_SWITCH_VOTES = 4
        private const val STRONG_ORIENTATION_SCORE = 20f

        /** Frames without a visible board after which the overlay suggestion is cleared. */
        private const val MISSED_FRAMES_BEFORE_CLEAR = 6

        /** Minimum change (screen fraction) before new board bounds are pushed to the overlay. */
        private const val BOUNDS_EPSILON = 0.004f

        private const val WORKER_KEEP_ALIVE_SECONDS = 30L

        /** Accessibility positions older than this are ignored in favour of vision. */
        private const val ACCESSIBILITY_MAX_AGE_MS = 2000L

        /** Minimum number of accessibility-reported pieces for the data to be trusted. */
        private const val ACCESSIBILITY_MIN_PIECES = 3

        // User-facing status messages. Users should never need to know about "calibration".
        private const val STATUS_LOOKING = "Looking for chess board..."
        private const val STATUS_READING = "Board found — reading pieces..."
        private const val STATUS_RECOGNIZED = "Board recognized"
        private const val STATUS_ANALYZING = "Analyzing..."

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

    /** Locked board orientation; null until the first board was read. */
    private var whiteAtBottom: Boolean? = null
    private var orientationVotes = 0

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

    private var lastMove: InferredMove? = null
    private var lastClassified: ClassifiedMove? = null
    private var castlingRights = "KQkq"
    private var fullMoveNumber = 1

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
        return try {
            e.analyze(fen, depth)
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Analysis failed for $fen", ex)
            null
        }
    }

    /** Analyses [fen] with [NUM_LINES] principal variations, ordered best first. */
    private fun analyzeMulti(fen: String): List<AnalysisResult>? {
        val e = engine ?: return null
        if (!running.get()) return null
        return try {
            e.analyzeMultiPv(fen, depth, NUM_LINES)
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Multi-PV analysis failed for $fen", ex)
            null
        }
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
            // A failed copy only costs the bounds refresh; previously known bounds are reused.
            val frame = copyBitmap(bitmap)
            val accepted = submit {
                try {
                    if (frame != null) updateBoundsFromFrame(frame)
                    processAccessibilityPositions(accessibilityPositions)
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

        val accepted = when {
            // Normal path: two identical reads in a row
            candidateCount >= STABLE_FRAMES && placement != stablePlacement -> true
            // Majority vote: after MAX_FRAMES_BEFORE_MAJORITY frames, pick the most-read position
            candidateFramesSinceStable >= MAX_FRAMES_BEFORE_MAJORITY && candidateHistory.isNotEmpty() -> {
                val best = candidateHistory.maxByOrNull { it.value }
                if (best != null && best.key != stablePlacement && best.value >= 2) {
                    candidatePlacement = best.key
                    true
                } else false
            }
            else -> false
        }

        if (!accepted) return

        val finalPlacement = candidatePlacement ?: return
        stablePlacement = finalPlacement
        candidateHistory.clear()
        candidateFramesSinceStable = 0
        onPositionChanged(finalPlacement)
    }

    /**
     * Returns a snapshot of the accessibility-provided piece positions if they are recent
     * (< [ACCESSIBILITY_MAX_AGE_MS]) and contain at least [ACCESSIBILITY_MIN_PIECES] pieces,
     * otherwise null (the vision pipeline is used instead).
     */
    private fun freshAccessibilityPositions(): Map<String, Char>? {
        if (!AccessibilityData.isRecent(ACCESSIBILITY_MAX_AGE_MS)) return null
        val positions = AccessibilityData.detectedPositions ?: return null
        return if (positions.size >= ACCESSIBILITY_MIN_PIECES) positions else null
    }

    /**
     * Locates the board in [bitmap] only to refresh the overlay bounds (no piece reading). Used on
     * the accessibility path, where the position comes from accessibility data but the arrows still
     * need to know where the board is on screen. If the board cannot be located in this frame, the
     * last known bounds are kept.
     */
    private fun updateBoundsFromFrame(bitmap: Bitmap) {
        if (!running.get()) return
        val px = Pixels(bitmap)
        val grid = try {
            BoardLocator.locate(px)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Board location failed on accessibility frame", e)
            null
        } ?: return
        updateBoardBounds(grid, px, bitmap)
    }

    /**
     * Accessibility counterpart of [processFrame]: converts the reported positions to a FEN and
     * feeds its placement into the same move-tracking path ([onPositionChanged]) that sets
     * [currentFen] for vision reads. Accessibility data is exact, so no multi-frame stability
     * check is needed; an unchanged placement is simply ignored.
     */
    private fun processAccessibilityPositions(positions: Map<String, Char>) {
        if (!running.get()) return
        ensureTemplatesLoaded()

        // Side to move: keep the tracked side if known, otherwise default to white. The actual
        // mover is recovered by move inference in onPositionChanged once a move is diffed.
        val whiteToMove = currentFen?.let { sideToMoveIsWhite(it) } ?: true
        val fen = try {
            FenConverter.positionsToFen(positions, whiteToMove)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not convert accessibility positions", e)
            return
        }
        Log.d(TAG, "Using accessibility data: ${positions.size} pieces, FEN: $fen")

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

        if (placement == stablePlacement) return
        candidatePlacement = placement
        candidateCount = STABLE_FRAMES
        candidateHistory.clear()
        candidateFramesSinceStable = 0
        stablePlacement = placement
        onPositionChanged(placement)
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
        // start placement is read below. Placements tracked with the other orientation are void.
        if (whiteAtBottom != null && whiteAtBottom != bottomIsWhite) resetTracking()
        whiteAtBottom = bottomIsWhite
        orientationVotes = 0
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
     * When the orientation changes, all placements tracked so far were built with the wrong
     * orientation, so move tracking is reset and resyncs from the next stable read.
     */
    private fun resolveOrientation(letters: Array<CharArray>): Boolean {
        val score = Orientation.score(letters)
        val detected = score >= 0f
        val locked = whiteAtBottom
        if (locked == null) {
            whiteAtBottom = detected
            orientationVotes = 0
            return detected
        }
        if (detected == locked) {
            orientationVotes = 0
            return locked
        }
        orientationVotes += if (abs(score) >= STRONG_ORIENTATION_SCORE) 2 else 1
        val isStart = Fen.build(letters, detected).substringBefore(' ') == START_PLACEMENT
        if (isStart || orientationVotes >= ORIENTATION_SWITCH_VOTES) {
            Log.i(TAG, "Board orientation changed: white at bottom = $detected (score $score)")
            whiteAtBottom = detected
            orientationVotes = 0
            resetTracking()
            return detected
        }
        return locked
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
        lastMove = null
        lastClassified = null
        castlingRights = "KQkq"
        fullMoveNumber = 1
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

    private fun onPositionChanged(placement: String) {
        postOverlayClear()  // Clear arrows immediately on any position change
        val userIsWhite = whiteAtBottom ?: true
        val prevFen = currentFen
        val after = parsePlacement(placement) ?: return

        val move = if (prevFen != null && placement != START_PLACEMENT) {
            val before = parsePlacement(prevFen.substringBefore(' '))
            if (before != null) MoveInference.infer(before, after, epSquareOf(prevFen)) else null
        } else {
            null
        }

        if (move == null) {
            resyncTo(placement, after, if (placement == START_PLACEMENT) true else userIsWhite)
            return
        }

        // The side that actually moved is authoritative; fix the previous FEN if we guessed wrong.
        var beforeFen: String = prevFen ?: return
        // Only the best line of the previous position is needed for classification.
        var beforeAnalysis: AnalysisResult? = currentAnalyses?.firstOrNull()
        var olderAnalysis = previousAnalysis
        if (sideToMoveIsWhite(beforeFen) != move.white) {
            beforeFen = withSideToMove(beforeFen, move.white)
            beforeAnalysis = null
            olderAnalysis = null
        }
        if (move.enPassant && epSquareOf(beforeFen) != move.to) {
            beforeFen = withEnPassant(beforeFen, move.to)
            beforeAnalysis = null
        }
        if (beforeAnalysis == null) beforeAnalysis = analyze(beforeFen)
        if (!running.get()) return

        val e = engine
        val classified = if (e != null && beforeAnalysis != null) {
            try {
                MoveClassifier.classify(
                    engine = e,
                    fen = beforeFen,
                    playedMove = move.uci,
                    analysisBefore = beforeAnalysis,
                    depth = depth,
                    isBookMove = false,
                    // Eval before the opponent's previous move, from the current mover's POV.
                    evalBeforeOpponentMove = olderAnalysis?.let { -it.eval },
                )
            } catch (ex: IllegalStateException) {
                Log.w(TAG, "Could not classify ${move.uci}", ex)
                null
            }
        } else {
            null
        }
        if (!running.get()) return

        castlingRights = updatedCastlingRights(castlingRights, move, after)
        if (!move.white) fullMoveNumber++
        val newFen = composeFen(placement, !move.white, castlingRights, enPassantTarget(move, after), fullMoveNumber)
        val newAnalyses = analyzeMulti(newFen)
        if (!running.get()) return

        previousFen = beforeFen
        previousAnalysis = beforeAnalysis
        currentFen = newFen
        currentAnalyses = newAnalyses
        lastMove = move
        lastClassified = classified
        render()
    }

    /** Starts tracking from [placement] without a known previous move. */
    private fun resyncTo(placement: String, board: CharArray, whiteToMove: Boolean) {
        postOverlayClear()  // Clear arrows immediately
        castlingRights = castlingFromPlacement(board)
        fullMoveNumber = 1
        val fen = composeFen(placement, whiteToMove, castlingRights, null, fullMoveNumber)
        val analyses = analyzeMulti(fen)
        if (!running.get()) return
        previousFen = null
        previousAnalysis = null
        currentFen = fen
        currentAnalyses = analyses
        lastMove = null
        lastClassified = null
        render()
    }

    private fun resetGameState() {
        whiteAtBottom = null
        orientationVotes = 0
        missedFrames = 0
        boardVisible = false
        resetTracking()
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
        val userIsWhite = whiteAtBottom ?: true
        val flipped = !userIsWhite
        val usersTurn = whiteToMove == userIsWhite
        val classified = lastClassified

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

            val label: String
            val color: Int
            when {
                rank == 1 && classified != null && usersTurn -> {
                    // On user's turn, show classification of opponent's last move
                    label = classified.classification.displayName
                    color = MoveColors.forLabel(label)
                }
                rank == 1 && classified != null && !usersTurn -> {
                    // On opponent's turn, show classification of user's last move
                    label = classified.classification.displayName
                    color = MoveColors.forLabel(label)
                }
                rank == 1 -> {
                    label = if (usersTurn) "Best move" else "Best reply"
                    color = MoveColors.BEST
                }
                else -> {
                    // How much worse than the best line, from the side to move's POV.
                    val cpDiff = bestEval - result.eval
                    label = when {
                        cpDiff < 10 -> "Excellent"
                        cpDiff < 50 -> "Good"
                        cpDiff < 100 -> "Inaccuracy"
                        cpDiff < 200 -> "Mistake"
                        else -> "Blunder"
                    }
                    color = MoveColors.forLabel(label)
                }
            }
            SuggestedLineData(move.substring(0, 2), move.substring(2, 4), evalCp, mateIn, label, color, rank)
        }.toMutableList()

        if (suggestions.isEmpty()) {
            postOverlayClear()
            return
        }

        val best = suggestions[0]
        val commentary = try {
            MoveCommentary.generate(
                fromSquare = best.from,
                toSquare = best.to,
                label = best.label,
                evalCp = best.evalCp,
                // SuggestedLineData holds mate from white's POV; commentary wants side to move's.
                mateIn = best.mateIn?.let { it * sign },
                fen = fen,
                isUsersTurn = usersTurn,
                rank = 1,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Commentary failed for $fen", e)
            ""
        }
        suggestions[0] = best.copy(commentary = commentary)

        postMultiSuggestion(suggestions, flipped)
    }

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
                    )
                }
                overlay.showMultiSuggestion(lines, flipped)
            }
        }
    }

    private fun postOverlayClear() {
        mainHandler.post { OverlayService.instance?.clearSuggestion() }
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
        return "$placement $side ${castling.ifEmpty { "-" }} $ep 0 ${max(1, fullMove)}"
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
