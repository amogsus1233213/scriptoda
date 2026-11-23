package com.chesstrainer.app.service

/**
 * Live analysis statistics shown on the main screen's Stats tab. Written by the analysis
 * pipeline (e.g. [AnalysisCoordinator]) and read on the main thread.
 */
object EngineStats {
    @Volatile var movesAnalyzed: Int = 0
    @Volatile var currentDepth: Int = 0
    @Volatile var currentEvalCp: Int? = null
    @Volatile var currentMateIn: Int? = null
    @Volatile var lastFen: String? = null
    @Volatile var whiteAtBottom: Boolean? = null
    @Volatile var positionsSeen: Int = 0

    fun clear() {
        movesAnalyzed = 0
        currentDepth = 0
        currentEvalCp = null
        currentMateIn = null
        lastFen = null
        whiteAtBottom = null
        positionsSeen = 0
    }
}
