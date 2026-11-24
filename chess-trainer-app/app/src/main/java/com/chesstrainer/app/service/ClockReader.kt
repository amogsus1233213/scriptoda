package com.chesstrainer.app.service

/**
 * Reads the game clocks from chess.com's accessibility tree.
 *
 * The clocks are text nodes such as "5:00", "0:45", "0:08.3" or "1:00:00", one above the board
 * (opponent) and one below it (user). Like [BoardAccessibilityService], this assumes the user's
 * side is drawn at the bottom, whichever colour they play.
 */
object ClockReader {

    /** [H:]M:SS with optional fractional seconds. */
    private val CLOCK = Regex("^(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?:[.,]\\d+)?$")

    /** Total whole seconds shown by [text], or null if it is not a clock. */
    fun parseSeconds(text: String): Int? {
        val match = CLOCK.matchEntire(text.trim()) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toInt()
        val seconds = match.groupValues[3].toInt()
        if (seconds >= 60 || (match.groupValues[1].isNotEmpty() && minutes >= 60)) return null
        return hours * 3600 + minutes * 60 + seconds
    }

    /**
     * The user's remaining time in seconds, or null if it cannot be told.
     *
     * With two or more clocks in the tree the bottom-most one is the user's. A single clock only
     * counts if it lies in the lower half of the window (the first node is the window root).
     */
    fun userTimeSeconds(nodes: List<ViewTreeReader.NodeData>): Int? {
        var clocks = 0
        var bottomCenterY = Int.MIN_VALUE
        var bottomSeconds = 0
        for (n in nodes) {
            val text = n.text ?: n.contentDescription ?: continue
            if (text.length > 12) continue
            val seconds = parseSeconds(text) ?: continue
            clocks++
            val centerY = (n.boundsTop + n.boundsBottom) / 2
            if (centerY > bottomCenterY) {
                bottomCenterY = centerY
                bottomSeconds = seconds
            }
        }
        if (clocks == 0) return null
        if (clocks == 1) {
            val root = nodes.first()
            val middle = (root.boundsTop + root.boundsBottom) / 2
            if (bottomCenterY <= middle) return null
        }
        return bottomSeconds
    }
}
