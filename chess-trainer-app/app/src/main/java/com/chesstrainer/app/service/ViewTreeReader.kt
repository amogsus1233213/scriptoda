package com.chesstrainer.app.service

import android.view.accessibility.AccessibilityNodeInfo

object ViewTreeReader {

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
        if (depth > maxDepth) return

        node.getBoundsInScreen(bounds)

        results.add(NodeData(
            className = node.className?.toString() ?: "",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            depth = depth,
        ))

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseRecursive(child, depth + 1, maxDepth, results, bounds)
            } finally {
                child.recycle()
            }
        }
    }
}
