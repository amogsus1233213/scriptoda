package com.chesstrainer.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BoardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BoardA11y"
        private const val TARGET_PACKAGE = "com.chess"

        @Volatile
        var instance: BoardAccessibilityService? = null
            private set
    }

    private var lastProcessTime = 0L
    private val MIN_INTERVAL_MS = 500L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        AccessibilityData.targetPackageVisible = (pkg == TARGET_PACKAGE)

        if (pkg != TARGET_PACKAGE) return

        // Throttle processing
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < MIN_INTERVAL_MS) return
        lastProcessTime = now

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        try {
            val root = rootInActiveWindow ?: return
            val nodes = ViewTreeReader.traverse(root)
            root.recycle()

            if (nodes.isEmpty()) return

            // Build dump string for debugging
            val dump = buildString {
                for (n in nodes.take(100)) {
                    append("  ".repeat(n.depth))
                    append(n.className)
                    if (n.text != null) append(" text=\"${n.text}\"")
                    if (n.contentDescription != null) append(" desc=\"${n.contentDescription}\"")
                    if (n.viewId != null) append(" id=${n.viewId}")
                    append(" [${n.boundsLeft},${n.boundsTop}-${n.boundsRight},${n.boundsBottom}]")
                    appendLine()
                }
            }
            AccessibilityData.lastViewDump = dump

            // Parse for board positions
            val result = BoardPositionParser.parse(nodes)

            if (result.positions != null && result.positions.isNotEmpty()) {
                Log.i(TAG, "Found ${result.boardNodesFound} board nodes, ${result.positions.size} positions")
                AccessibilityData.update(result.positions)
            } else if (result.fen != null) {
                Log.i(TAG, "Found FEN: ${result.fen}")
                AccessibilityData.update(result.positions)
            } else {
                Log.d(TAG, "No board data in ${result.nodeCount} nodes")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading view tree", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        AccessibilityData.clear()
        super.onDestroy()
        Log.i(TAG, "Accessibility service destroyed")
    }
}
