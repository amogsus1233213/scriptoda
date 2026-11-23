package com.chesstrainer.app.service

object AccessibilityData {
    @Volatile var lastViewDump: String = ""
    @Volatile var detectedPositions: Map<String, Char>? = null
    @Volatile var lastUpdateTime: Long = 0L
    @Volatile var targetPackageVisible: Boolean = false

    fun update(positions: Map<String, Char>?) {
        detectedPositions = positions
        lastUpdateTime = System.currentTimeMillis()
    }

    fun isRecent(maxAgeMs: Long = 2000): Boolean {
        return detectedPositions != null &&
            System.currentTimeMillis() - lastUpdateTime < maxAgeMs
    }

    fun clear() {
        detectedPositions = null
        lastViewDump = ""
        lastUpdateTime = 0L
        targetPackageVisible = false
    }
}
