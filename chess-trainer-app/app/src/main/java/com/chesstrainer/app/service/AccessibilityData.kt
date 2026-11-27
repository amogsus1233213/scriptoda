package com.chesstrainer.app.service

/**
 * Board state published by [BoardAccessibilityService] for [AnalysisCoordinator]. It is written
 * on the main thread and read from analysis threads.
 */
object AccessibilityData {
    @Volatile var lastViewDump: String = ""
    @Volatile var detectedPositions: Map<String, Char>? = null

    /**
     * Last time a read confirmed [detectedPositions], changed or not. Unchanged reads refresh it
     * too (see [confirm]), so a position that stays on screen does not go stale in [isRecent].
     */
    @Volatile var lastUpdateTime: Long = 0L
    @Volatile var targetPackageVisible: Boolean = false

    /** Diagnostics for the Stats tab: why the last read gave no board, and how big the tree was. */
    @Volatile var lastRejectReason: String? = null
    @Volatile var lastNodeCount: Int = 0
    @Volatile var serviceConnected: Boolean = false

    /**
     * Whether the user plays white (white at the bottom of the board), when the accessibility
     * service can tell; null when unknown. [AnalysisCoordinator] treats a non-null value as
     * authoritative over vision-based orientation detection, so it must be reset to null as soon
     * as it can no longer be vouched for (e.g. a new game starts or the chess app is left).
     *
     * [BoardAccessibilityService] sets it from the orientation read from the tree
     * ([BoardPositionParser.ParseResult.whiteAtBottom]) once that has been the same on two
     * consecutive reads. It assumes the user's side is drawn at the bottom, which is chess.com's
     * default unless the board is flipped by hand.
     */
    @Volatile var userPlaysWhite: Boolean? = null

    /**
     * On-screen orientation of the board [detectedPositions] was read from (true = white at the
     * bottom), derived from the piece nodes' bounds; null if the view tree could not tell. The
     * square names in [detectedPositions] are absolute, so this does not affect the FEN, only how
     * the overlay must map squares to the screen.
     */
    @Volatile var detectedWhiteAtBottom: Boolean? = null

    /**
     * Side to move as read from chess.com's move list (true = white to move); null when the
     * move list could not tell.
     */
    @Volatile var whiteToMove: Boolean? = null

    /**
     * Full FEN produced by the game tracker ([ChessGame.toFen]) when move tracking is active;
     * null when the position comes from the board parser instead. Unlike a FEN rebuilt from
     * [detectedPositions], this carries the correct side-to-move, castling rights and en passant
     * square, so [AnalysisCoordinator] should prefer it when available.
     */
    @Volatile var trackedFen: String? = null

    /**
     * Time left on the user's clock in whole seconds, as read from the view tree by
     * [ClockReader]; null when no clock is shown or it cannot be told which one is the user's.
     * [AnalysisCoordinator] shortens the search when this is low.
     */
    @Volatile var userTimeSeconds: Int? = null

    /**
     * Last positions that were read identically on at least two consecutive reads. Unlike
     * [detectedPositions], this skips the in-between states seen while a piece animates or is
     * dragged.
     */
    @Volatile var lastStablePositions: Map<String, Char>? = null
        private set

    /** How many times [detectedPositions] changed since the last [clear] (for debugging). */
    @Volatile var positionChangeCount: Int = 0
        private set

    /**
     * Publishes a read. [detectedPositions] is only replaced, and [positionChangeCount] only
     * bumped, when the positions differ. For reads known to be unchanged, use [confirm].
     */
    @Synchronized
    fun update(positions: Map<String, Char>?, whiteAtBottom: Boolean? = null) {
        if (positions != detectedPositions) {
            detectedPositions = positions
            positionChangeCount++
        }
        detectedWhiteAtBottom = whiteAtBottom
        lastUpdateTime = System.currentTimeMillis()
    }

    /**
     * Records a read that found [detectedPositions] unchanged. It keeps them fresh for
     * [isRecent] and refreshes the orientation, which changes without the position changing
     * when the board is flipped.
     */
    @Synchronized
    fun confirm(whiteAtBottom: Boolean?) {
        detectedWhiteAtBottom = whiteAtBottom
        lastUpdateTime = System.currentTimeMillis()
    }

    /** Records [positions] as read identically on consecutive reads. */
    fun markStable(positions: Map<String, Char>) {
        lastStablePositions = positions
    }

    /** The chess app is no longer in front, so the side the user plays can't be vouched for. */
    @Synchronized
    fun onTargetLeft() {
        targetPackageVisible = false
        userPlaysWhite = null
        detectedWhiteAtBottom = null
        whiteToMove = null
        trackedFen = null
        userTimeSeconds = null
        lastUpdateTime = 0L
    }

    fun isRecent(maxAgeMs: Long = 2000): Boolean {
        return detectedPositions != null &&
            System.currentTimeMillis() - lastUpdateTime < maxAgeMs
    }

    @Synchronized
    fun clear() {
        detectedPositions = null
        detectedWhiteAtBottom = null
        whiteToMove = null
        trackedFen = null
        userTimeSeconds = null
        lastStablePositions = null
        positionChangeCount = 0
        lastViewDump = ""
        lastUpdateTime = 0L
        targetPackageVisible = false
        userPlaysWhite = null
        lastRejectReason = null
        lastNodeCount = 0
    }
}
