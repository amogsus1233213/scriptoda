package com.chesstrainer.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

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

        /** Identical consecutive reads after which a position or orientation counts as stable. */
        private const val STABLE_READS = 2

        /** Nodes included in [AccessibilityData.lastViewDump]. */
        private const val DUMP_NODES = 100

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityData.serviceConnected = true
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
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onTargetLeft()
            return
        }

        AccessibilityData.targetPackageVisible = true
        val wait = lastReadTime + MIN_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) scheduleRead(wait) else readBoard()
    }

    private fun readBoard() {
        cancelScheduledRead()
        lastReadTime = SystemClock.uptimeMillis()
        val nodes = readTree() ?: return
        val result = parse(nodes)
        if (result.positions != null) {
            moveTrackingActive = false
            publish(result)
        } else {
            publishFromMoveList(nodes, result)
        }

        // Side to move from the move list: the side that did not make the last listed move.
        val moves = MoveListReader.extract(nodes)
        if (moves.isNotEmpty()) {
            AccessibilityData.whiteToMove = !moves.last().isWhite
        }
    }

    /** The chess app's view tree, or null if it is not the active window or cannot be read. */
    private fun readTree(): List<ViewTreeReader.NodeData>? {
        val root = rootInActiveWindow
        if (root == null) {
            AccessibilityData.lastRejectReason = "No active window"
            return null
        }
        return try {
            if (root.packageName?.toString() != TARGET_PACKAGE) {
                AccessibilityData.lastRejectReason =
                    "Active package: ${root.packageName} (need $TARGET_PACKAGE)"
                null
            } else {
                val nodes = ViewTreeReader.traverse(root)
                if (nodes.isEmpty()) {
                    AccessibilityData.lastNodeCount = 0
                    AccessibilityData.lastRejectReason = "Empty view tree"
                    null
                } else nodes
            }
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

        // Detect new game: if we see move 1 white and our game has advanced past it,
        // or if the moves don't match what we have, reset.
        val firstExtracted = extracted.first()
        if (firstExtracted.moveNumber == 1 && firstExtracted.isWhite && game.appliedSan.isNotEmpty()) {
            // Check if this is a new game by seeing if the first move differs
            if (game.appliedSan.isNotEmpty() && extracted.firstOrNull()?.san?.lowercase() !=
                game.appliedSan.firstOrNull()?.lowercase()) {
                Log.i(TAG, "New game detected, resetting move tracker")
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
                    Log.w(TAG, "Failed to apply move: ${move.san}")
                    AccessibilityData.lastRejectReason = "Move tracking: failed to apply ${move.san}"
                    publish(parseResult)
                    return
                }
            } else if (move.moveNumber == expectedMoveNum && !move.isWhite && expectedWhite) {
                // We expected white but got black — might have missed white's move
                // This can happen if we started tracking mid-turn
                continue
            } else if (move.moveNumber > expectedMoveNum) {
                // We missed a move. Can't continue tracking.
                Log.w(TAG, "Missed move(s): expected $expectedMoveNum, saw ${move.moveNumber}")
                AccessibilityData.lastRejectReason = "Move tracking: missed move $expectedMoveNum"
                publish(parseResult)
                return
            }
        }

        // If we have a position from move tracking, publish it
        if (game.appliedSan.isNotEmpty()) {
            moveTrackingActive = true
            val positions = game.toPositions()
            val fen = game.toFen()
            AccessibilityData.lastRejectReason = null
            AccessibilityData.whiteToMove = game.white

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

            // Schedule another read to stay fresh
            scheduleRead(MIN_INTERVAL_MS)
        } else {
            publish(parseResult)
        }
    }

    private fun onTargetLeft() {
        cancelScheduledRead()
        lastOrientation = null
        identicalOrientations = 0
        moveTrackingActive = false
        game.reset()
        lastMoveCount = 0
        if (AccessibilityData.userPlaysWhite != null) Log.i(TAG, "Chess app left, user's colour unknown")
        AccessibilityData.onTargetLeft()
    }

    private fun scheduleRead(delayMs: Long) {
        if (readScheduled) return
        readScheduled = true
        handler.postDelayed(scheduledRead, delayMs)
    }

    private fun cancelScheduledRead() {
        handler.removeCallbacks(scheduledRead)
        readScheduled = false
    }

    /** Indented node listing for debugging. */
    private fun dump(nodes: List<ViewTreeReader.NodeData>): String = buildString {
        for (n in nodes.take(DUMP_NODES)) {
            append("  ".repeat(n.depth))
            append(n.className)
            if (n.text != null) append(" text=\"${n.text}\"")
            if (n.contentDescription != null) append(" desc=\"${n.contentDescription}\"")
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
        instance = null
        AccessibilityData.serviceConnected = false
        AccessibilityData.clear()
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
    }
}
