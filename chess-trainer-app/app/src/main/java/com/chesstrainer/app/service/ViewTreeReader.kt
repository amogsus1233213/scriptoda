package com.chesstrainer.app.service

import android.view.accessibility.AccessibilityNodeInfo

object ViewTreeReader {

    /**
     * Most nodes read from one tree. A chess.com board screen has a few hundred; the cap keeps a
     * pathological tree (a huge list, a broken app) from building an unbounded list, which the
     * service also caches between reads.
     */
    const val MAX_NODES = 3000

    /** Longest text or content description kept per node. Moves, clocks and squares are short. */
    const val MAX_TEXT_LENGTH = 500

    data class NodeData(
        val className: String,
        val text: String?,
        val contentDescription: String?,
        val viewId: String?,
        val boundsLeft: Int,
        val boundsTop: Int,
        val boundsRight: Int,
        val boundsBottom: Int,
        val depth: Int,
    )

    fun traverse(root: AccessibilityNodeInfo?, maxDepth: Int = 20): List<NodeData> {
        if (root == null) return emptyList()
        val results = mutableListOf<NodeData>()
        val bounds = android.graphics.Rect()
        traverseRecursive(root, 0, maxDepth, results, bounds)
        return results
    }

    private fun traverseRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        results: MutableList<NodeData>,
        bounds: android.graphics.Rect,
    ) {
        if (depth > maxDepth || results.size >= MAX_NODES) return

        node.getBoundsInScreen(bounds)

        results.add(NodeData(
            className = node.className?.toString() ?: "",
            text = node.text?.let { cap(it) },
            contentDescription = node.contentDescription?.let { cap(it) },
            viewId = node.viewIdResourceName,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            depth = depth,
        ))

        for (i in 0 until node.childCount) {
            if (results.size >= MAX_NODES) return
            val child = node.getChild(i) ?: continue
            try {
                traverseRecursive(child, depth + 1, maxDepth, results, bounds)
            } finally {
                child.recycle()
            }
        }
    }

    /** [text] as a String of at most [MAX_TEXT_LENGTH] chars, without copying a long original whole. */
    private fun cap(text: CharSequence): String =
        if (text.length <= MAX_TEXT_LENGTH) text.toString()
        else text.subSequence(0, MAX_TEXT_LENGTH).toString()
}
