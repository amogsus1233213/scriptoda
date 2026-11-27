package com.chesstrainer.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the chess.com board from the accessibility tree and publishes it in [AccessibilityData].
 *
 * Reads are throttled to one per [MIN_INTERVAL_MS]. An event that arrives sooner is not dropped:
 * it schedules a read for the end of the interval, so the final state after a burst of events (a
 * move and its animation) is always read. A read with a new position or orientation schedules one
 * more read to confirm it, even if chess.com sends no further events. The stable position
 * ([AccessibilityData.lastStablePositions]) and the user's side
 * ([AccessibilityData.userPlaysWhite]) wait for that confirmation.
 *
 * Everything runs on the main thread: accessibility events and the [handler] callbacks.
 */
class BoardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BoardA11y"
        private const val TARGET_PACKAGE = "com.chess"

        /** Shortest time between two view tree reads. */
        private const val MIN_INTERVAL_MS = 200L

        /**
         * While the chess app is in front the tree is read again at least this often, events or
         * not. A board that just stands there sends no events, and [AnalysisCoordinator] takes
         * a recent read as the proof that the board is still on screen.
         */
        private const val HEARTBEAT_MS = 1000L

        /** Identical consecutive reads after which a position or orientation counts as stable. */
        private const val STABLE_READS = 2

        /** Nodes included in [AccessibilityData.lastViewDump]. */
        private const val DUMP_NODES = 100

        /** Packages whose window-state events should NOT reset the game tracker. */
        private val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "android",
        )
        /** Package prefixes that are transient system UI (keyboards, etc.). */
        private val TRANSIENT_SYSTEM_PREFIXES = listOf(
            "com.google.android.inputmethod",
            "com.samsung.android.inputmethod",
            "com.swiftkey",
        )
        private const val TARGET_LEFT_DELAY_MS = 2000L

        @Volatile
        var instance: BoardAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scheduledRead = Runnable {
        readScheduled = false
        readBoard()
    }
    private var readScheduled = false

    /** [SystemClock.uptimeMillis] the scheduled read is due at; only valid while [readScheduled]. */
    private var scheduledReadAt = 0L

    /** [SystemClock.uptimeMillis] of the last read. */
    private var lastReadTime = 0L

    /** Last parsed tree and its result. An identical tree is not parsed again. */
    private var lastNodes: List<ViewTreeReader.NodeData>? = null
    private var lastResult: BoardPositionParser.ParseResult? = null

    /** Last known board state (null until a board was read), and how many reads in a row saw it. */
    private var lastPositions: Map<String, Char>? = null
    private var identicalReads = 0

    /** Orientation seen by the last read (true = white at the bottom), and how many reads in a row saw it. */
    private var lastOrientation: Boolean? = null
    private var identicalOrientations = 0

    /** Incremental game tracker: reconstructs the position from the accessible move list. */
    private val game = ChessGame()
    private var lastMoveCount = 0
    private var moveTrackingActive = false

    /** Deferred target-left reset: gives transient system UI time to dismiss before wiping the game. */
    private var targetLeftPending = false
    private val targetLeftRunnable = Runnable {
        if (targetLeftPending) {
            targetLeftPending = false
            // Another package's window event followed by two quiet seconds does not prove the
            // chess app is gone: launchers and system UI send such events while it stays in
            // front (gesture bar, taskbar, edge panels), and a board nobody touches is quiet.
            // Only reset when its window really is off the screen.
            if (AccessibilityData.targetPackageVisible && isTargetOnScreen()) {
                Log.d(TAG, "Chess app still on screen; not resetting")
                readBoard()
            } else {
                doTargetLeft()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityData.serviceConnected = true
        // Needed for [getWindows], which finds the chess app when another window is the active
        // one. Also declared in the XML config; set here too in case that was cached without it.
        try {
            val info = serviceInfo
            val flag = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            if (info != null && info.flags and flag == 0) {
                info.flags = info.flags or flag
                serviceInfo = info
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not enable window retrieval", e)
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // Our own overlay's events say nothing about the chess app.
        if (pkg == packageName) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (pkg != TARGET_PACKAGE) {
            // Another window came to the front. Content changes elsewhere (such as the status
            // bar clock) do not mean the chess app is gone.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Transient system UI (notification shade, keyboard, volume, dialogs) should
                // not wipe the game tracker.  Only schedule a deferred reset for real app switches.
                val isTransient = pkg in TRANSIENT_SYSTEM_PACKAGES ||
                    TRANSIENT_SYSTEM_PREFIXES.any { prefix -> pkg.startsWith(prefix) }
                if (!isTransient) {
                    // Defer the reset — if chess.com reappears within TARGET_LEFT_DELAY_MS the
                    // pending reset is cancelled and the game tracker survives.
                    if (!targetLeftPending) {
                        targetLeftPending = true
                        handler.postDelayed(targetLeftRunnable, TARGET_LEFT_DELAY_MS)
                    }
                }
            }
            return
        }

        // Chess.com is visible — cancel any pending target-left reset.
        if (targetLeftPending) {
            targetLeftPending = false
            handler.removeCallbacks(targetLeftRunnable)
        }

        AccessibilityData.targetPackageVisible = true
        val wait = lastReadTime + MIN_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) scheduleRead(wait) else readBoard()
    }

    private fun readBoard() {
        cancelScheduledRead()
        lastReadTime = SystemClock.uptimeMillis()
        try {
            readBoardNow()
        } catch (e: RuntimeException) {
            // This runs on the main thread, from an accessibility event or a handler callback.
            // Anything thrown by the parsers or the move tracker would otherwise kill the whole
            // process, and with a read every [HEARTBEAT_MS] it would do so again right after
            // the restart, until the system stops rebinding the service (enabled in settings,
            // but never connected).
            Log.e(TAG, "Board read failed", e)
            AccessibilityData.lastRejectReason = "Board read failed: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            // Heartbeat; a sooner read scheduled by the read above is kept (see scheduleRead).
            if (AccessibilityData.targetPackageVisible) scheduleRead(HEARTBEAT_MS)
        }
    }

    private fun readBoardNow() {
        val nodes = readTree() ?: return
        // The user's clock, for time pressure detection in AnalysisCoordinator. Null when the
        // tree shows no clock (untimed game, or not on a game screen).
        AccessibilityData.userTimeSeconds = ClockReader.userTimeSeconds(nodes)
        val result = parse(nodes)
        if (result.positions != null) {
            moveTrackingActive = false
            AccessibilityData.trackedFen = null
            publish(result)
            // Side to move from the move list — only when the board parser produced the position.
            // When move tracking is active, game.white is authoritative (set in publishFromMoveList).
            val moves = MoveListReader.extract(nodes)
            if (moves.isNotEmpty()) {
                AccessibilityData.whiteToMove = !moves.last().isWhite
            }
        } else {
            publishFromMoveList(nodes, result)
            // publishFromMoveList sets whiteToMove from game.white — do not overwrite it here.
        }
    }

    /**
     * Root node of the chess app's window, or null if it has none on screen. The active window
     * is tried first. It is not always the chess app's while that is in front: on some devices
     * the launcher (gesture bar, taskbar) or another system window is reported instead, which
     * used to reject every read. The other windows on screen are therefore searched as well,
     * taking the largest one of the chess app (the board's, not a popup's). The caller recycles
     * the returned node. Sets [AccessibilityData.lastRejectReason] when returning null.
     */
    private fun findTargetRoot(): AccessibilityNodeInfo? {
        var activePackage: String? = null
        try {
            val active = rootInActiveWindow
            if (active != null) {
                activePackage = active.packageName?.toString()
                if (activePackage == TARGET_PACKAGE) return active
                active.recycle()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read the active window", e)
        }

        var best: AccessibilityNodeInfo? = null
        var bestArea = -1L
        var windowCount = 0
        try {
            val bounds = Rect()
            for (window in windows.orEmpty()) {
                windowCount++
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                if (root.packageName?.toString() != TARGET_PACKAGE) {
                    root.recycle()
                    continue
                }
                window.getBoundsInScreen(bounds)
                val area = bounds.width().toLong() * bounds.height()
                if (area > bestArea) {
                    best?.recycle()
                    best = root
                    bestArea = area
                } else {
                    root.recycle()
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read the window list", e)
            best?.let {
                try { it.recycle() } catch (_: RuntimeException) {}
                best = null
            }
        }
        if (best == null) {
            AccessibilityData.lastRejectReason = if (activePackage == null && windowCount == 0) {
                "No active window"
            } else {
                "Active package: $activePackage, no $TARGET_PACKAGE window among $windowCount windows"
            }
        }
        return best
    }

    /** Whether the chess app has a window on screen right now. */
    private fun isTargetOnScreen(): Boolean {
        val root = findTargetRoot() ?: return false
        root.recycle()
        return true
    }

    /** The chess app's view tree, or null if it is not on screen or cannot be read. */
    private fun readTree(): List<ViewTreeReader.NodeData>? {
        val root = findTargetRoot() ?: return null
        return try {
            val nodes = ViewTreeReader.traverse(root)
            if (nodes.isEmpty()) {
                AccessibilityData.lastNodeCount = 0
                AccessibilityData.lastRejectReason = "Empty view tree"
                null
            } else nodes
        } catch (e: RuntimeException) {
            Log.w(TAG, "Error reading view tree", e)
            AccessibilityData.lastRejectReason = "Error reading view tree: ${e.javaClass.simpleName}"
            null
        } finally {
            root.recycle()
        }
    }

    /** Parses [nodes], reusing the last result if the tree has not changed since. */
    private fun parse(nodes: List<ViewTreeReader.NodeData>): BoardPositionParser.ParseResult {
        val cached = lastResult
        if (cached != null && nodes == lastNodes) return cached

        AccessibilityData.lastViewDump = dump(nodes)
        val result = BoardPositionParser.parse(nodes)
        lastNodes = nodes
        lastResult = result
        return result
    }

    private fun publish(result: BoardPositionParser.ParseResult) {
        AccessibilityData.lastNodeCount = result.nodeCount
        AccessibilityData.lastRejectReason = result.rejectReason
        trackOrientation(result.whiteAtBottom)

        val positions = result.positions
        if (positions == null) {
            Log.d(TAG, result.rejectReason?.let { "Board read rejected: $it" }
                ?: "No board data in ${result.nodeCount} nodes")
        } else if (positions == lastPositions) {
            // Same position: nothing to re-process, only keep it fresh for the coordinator.
            identicalReads++
            AccessibilityData.confirm(result.whiteAtBottom)
        } else {
            identicalReads = 1
            lastPositions = positions
            AccessibilityData.update(positions, result.whiteAtBottom)
            Log.i(
                TAG,
                "Position change #${AccessibilityData.positionChangeCount}: " +
                    "${positions.size} pieces from ${result.boardNodesFound} nodes, " +
                    "white at bottom = ${result.whiteAtBottom}, " +
                    BoardPositionParser.positionsToFen(positions),
            )
        }
        if (positions != null && identicalReads == STABLE_READS) {
            AccessibilityData.markStable(positions)
        }

        val positionUnconfirmed = positions != null && identicalReads < STABLE_READS
        val orientationUnconfirmed = lastOrientation != null && identicalOrientations < STABLE_READS
        if (positionUnconfirmed || orientationUnconfirmed) scheduleRead(MIN_INTERVAL_MS)
    }

    /**
     * Publishes the side the user plays ([AccessibilityData.userPlaysWhite]) once the tree has
     * shown the same orientation on [STABLE_READS] reads in a row. It assumes the user's side is
     * at the bottom. A read that cannot tell the orientation clears it right away, because
     * [AnalysisCoordinator] trusts a non-null value over its own detection.
     */
    private fun trackOrientation(whiteAtBottom: Boolean?) {
        identicalOrientations =
            if (whiteAtBottom != null && whiteAtBottom == lastOrientation) identicalOrientations + 1 else 1
        lastOrientation = whiteAtBottom

        val confirmed = whiteAtBottom?.takeIf { identicalOrientations >= STABLE_READS }
        if (AccessibilityData.userPlaysWhite != confirmed) {
            AccessibilityData.userPlaysWhite = confirmed
            Log.i(TAG, "User plays " + when (confirmed) {
                true -> "white"
                false -> "black"
                null -> "unknown colour (orientation not readable)"
            })
        }
    }

    /**
     * Fallback: when the board parser finds no piece nodes, extract moves from chess.com's
     * move list bar and replay them to reconstruct the position.
     */
    private fun publishFromMoveList(nodes: List<ViewTreeReader.NodeData>, parseResult: BoardPositionParser.ParseResult) {
        AccessibilityData.lastNodeCount = parseResult.nodeCount

        val extracted = MoveListReader.extract(nodes)
        if (extracted.isEmpty()) {
            AccessibilityData.lastRejectReason = "No board nodes and no moves in tree"
            publish(parseResult)
            return
        }

        // Detect new game: compare the entire overlapping prefix of extracted moves against
        // appliedSan.  A rematch with the same opening move must still be caught.
        val firstExtracted = extracted.first()
        if (firstExtracted.moveNumber == 1 && firstExtracted.isWhite && game.appliedSan.isNotEmpty()) {
            var needsReset = false

            // 1. If the extracted list is significantly shorter than what we tracked,
            //    the game went backwards — almost certainly a new game.
            if (game.appliedSan.size > extracted.size + 2) {
                needsReset = true
                Log.i(TAG, "New game detected: tracked ${game.appliedSan.size} moves but only ${extracted.size} extracted")
            }

            // 2. Compare every overlapping move; any mismatch means a new game.
            if (!needsReset) {
                val overlapSize = minOf(extracted.size, game.appliedSan.size)
                for (i in 0 until overlapSize) {
                    if (extracted[i].san.lowercase() != game.appliedSan[i].lowercase()) {
                        needsReset = true
                        Log.i(TAG, "New game detected: move ${i + 1} differs ('${extracted[i].san}' vs '${game.appliedSan[i]}')")
                        break
                    }
                }
            }

            if (needsReset) {
                game.reset()
                lastMoveCount = 0
            }
        }

        // If game hasn't started tracking yet and we don't see move 1,
        // we can't reconstruct. Wait for a new game.
        if (game.appliedSan.isEmpty() && !(firstExtracted.moveNumber == 1 && firstExtracted.isWhite)) {
            AccessibilityData.lastRejectReason = "Move tracking: waiting for new game (saw move ${firstExtracted.moveNumber})"
            publish(parseResult)
            return
        }

        // Apply any new moves we haven't seen yet
        var applied = false
        for (move in extracted) {
            val expectedMoveNum = (game.appliedSan.size / 2) + 1
            val expectedWhite = game.appliedSan.size % 2 == 0
            // Skip moves we've already applied
            if (move.moveNumber < expectedMoveNum) continue
            if (move.moveNumber == expectedMoveNum && move.isWhite == expectedWhite) {
                // This is the next move to apply
                if (game.applySan(move.san)) {
                    applied = true
                    Log.i(TAG, "Move tracked: ${move.moveNumber}${if (move.isWhite) "." else "..."} ${move.san}")
                } else {
                    Log.w(TAG, "Failed to apply move: ${move.san}, attempting full replay")
                    applied = attemptFullReplay(extracted, parseResult) || applied
                    break
                }
            } else if (move.moveNumber == expectedMoveNum && !move.isWhite && expectedWhite) {
                // We expected white but got black — might have missed white's move
                // This can happen if we started tracking mid-turn
                continue
            } else if (move.moveNumber > expectedMoveNum) {
                // We missed a move. Try a full replay from scratch.
                Log.w(TAG, "Missed move(s): expected $expectedMoveNum, saw ${move.moveNumber}. Attempting full replay")
                applied = attemptFullReplay(extracted, parseResult) || applied
                break
            }
        }

        // If we have a position from move tracking, publish it
        if (game.appliedSan.isNotEmpty()) {
            moveTrackingActive = true
            val positions = game.toPositions()
            val fen = game.toFen()
            AccessibilityData.lastRejectReason = null
            AccessibilityData.whiteToMove = game.white
            AccessibilityData.trackedFen = fen

            if (positions == lastPositions && !applied) {
                identicalReads++
                AccessibilityData.confirm(null)
            } else {
                identicalReads = 1
                lastPositions = positions
                AccessibilityData.update(positions, null)
                Log.i(TAG, "Move-tracked position: ${game.appliedSan.size} moves, $fen")
            }
            if (identicalReads == STABLE_READS) {
                AccessibilityData.markStable(positions)
            }

            // Infer user's color: in move 1, the player at the bottom made the first move.
            // We can't know for sure from moves alone, but chess.com defaults to user at bottom.
            // For now, leave orientation detection to the coordinator's vision fallback.

            // Schedule another read only when a new move was applied; otherwise use a slower poll.
            if (applied) {
                scheduleRead(MIN_INTERVAL_MS)
            } else {
                scheduleRead(1000L)
            }
        } else {
            publish(parseResult)
        }
    }

    /**
     * Resets the game tracker and replays ALL extracted moves from scratch.
     * Returns true if the replay produced a valid game state; false if it failed
     * (the tracker is left reset so the next read can try again).
     */
    private fun attemptFullReplay(
        extracted: List<MoveListReader.ExtractedMove>,
        parseResult: BoardPositionParser.ParseResult,
    ): Boolean {
        game.reset()
        lastMoveCount = 0
        for (m in extracted) {
            if (!game.applySan(m.san)) {
                Log.w(TAG, "Full replay failed at ${m.moveNumber}${if (m.isWhite) "." else "..."} ${m.san}")
                game.reset()
                lastMoveCount = 0
                AccessibilityData.lastRejectReason = "Move tracking: cannot replay game"
                publish(parseResult)
                return false
            }
        }
        Log.i(TAG, "Full replay succeeded with ${extracted.size} moves")
        return true
    }

    private fun onTargetLeft() {
        // Cancel any deferred target-left as well.
        targetLeftPending = false
        handler.removeCallbacks(targetLeftRunnable)
        doTargetLeft()
    }

    private fun doTargetLeft() {
        cancelScheduledRead()
        lastOrientation = null
        identicalOrientations = 0
        moveTrackingActive = false
        game.reset()
        lastMoveCount = 0
        if (AccessibilityData.userPlaysWhite != null) Log.i(TAG, "Chess app left, user's colour unknown")
        AccessibilityData.onTargetLeft()
    }

    /**
     * Schedules a read in [delayMs]. A read already scheduled for sooner is kept; a later one
     * (the heartbeat, the slow move-list poll) is brought forward, so an event's read never
     * waits behind it.
     */
    private fun scheduleRead(delayMs: Long) {
        val at = SystemClock.uptimeMillis() + delayMs
        if (readScheduled && scheduledReadAt <= at) return
        handler.removeCallbacks(scheduledRead)
        readScheduled = true
        scheduledReadAt = at
        handler.postAtTime(scheduledRead, at)
    }

    private fun cancelScheduledRead() {
        handler.removeCallbacks(scheduledRead)
        readScheduled = false
    }

    /** Indented node listing for debugging. */
    private fun dump(nodes: List<ViewTreeReader.NodeData>): String = buildString(
        minOf(nodes.size, DUMP_NODES) * 80
    ) {
        for (n in nodes.take(DUMP_NODES)) {
            append("  ".repeat(n.depth.coerceAtMost(20)))
            append(n.className)
            if (n.text != null) append(" text=\"${n.text.take(80)}\"")
            if (n.contentDescription != null) append(" desc=\"${n.contentDescription.toString().take(80)}\"")
            if (n.viewId != null) append(" id=${n.viewId}")
            append(" [${n.boundsLeft},${n.boundsTop}-${n.boundsRight},${n.boundsBottom}]")
            appendLine()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        readScheduled = false
        targetLeftPending = false
        instance = null
        AccessibilityData.serviceConnected = false
        AccessibilityData.clear()
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
    }
}
