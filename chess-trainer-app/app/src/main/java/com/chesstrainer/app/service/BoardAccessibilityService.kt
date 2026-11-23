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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
        publish(parse(nodes))
    }

    /** The chess app's view tree, or null if it is not the active window or cannot be read. */
    private fun readTree(): List<ViewTreeReader.NodeData>? {
        val root = rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != TARGET_PACKAGE) null
            else ViewTreeReader.traverse(root).takeIf { it.isNotEmpty() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Error reading view tree", e)
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

    private fun onTargetLeft() {
        cancelScheduledRead()
        lastOrientation = null
        identicalOrientations = 0
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
        AccessibilityData.clear()
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
    }
}
