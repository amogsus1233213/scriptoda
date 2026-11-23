package com.chesstrainer.app.service

object AccessibilityData {
    @Volatile var lastViewDump: String = ""
    @Volatile var detectedPositions: Map<String, Char>? = null
    @Volatile var lastUpdateTime: Long = 0L
    @Volatile var targetPackageVisible: Boolean = false

    /**
     * Whether the user plays white (white at the bottom of the board), when the accessibility
     * service can tell; null when unknown. [AnalysisCoordinator] treats a non-null value as
     * authoritative over vision-based orientation detection, so it must be reset to null as soon
     * as it can no longer be vouched for (e.g. a new game starts or the chess app is left).
     */
    @Volatile var userPlaysWhite: Boolean? = null

    /**
     * On-screen orientation of the board [detectedPositions] was read from (true = white at the
     * bottom), derived from the piece nodes' bounds; null if the view tree could not tell. The
     * square names in [detectedPositions] are absolute, so this does not affect the FEN, only how
     * the overlay must map squares to the screen.
     */
    @Volatile var detectedWhiteAtBottom: Boolean? = null

    fun update(positions: Map<String, Char>?, whiteAtBottom: Boolean? = null) {
        detectedWhiteAtBottom = whiteAtBottom
        detectedPositions = positions
        lastUpdateTime = System.currentTimeMillis()
    }

    fun isRecent(maxAgeMs: Long = 2000): Boolean {
        return detectedPositions != null &&
            System.currentTimeMillis() - lastUpdateTime < maxAgeMs
    }

    fun clear() {
        detectedPositions = null
        detectedWhiteAtBottom = null
        lastViewDump = ""
        lastUpdateTime = 0L
        targetPackageVisible = false
        userPlaysWhite = null
    }
}
