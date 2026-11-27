package com.chesstrainer.app

import android.app.Application

class ChessTrainerApp : Application() {

    val coordinator by lazy {
        com.chesstrainer.app.service.AnalysisCoordinator(this)
    }

    companion object {
        fun get(context: android.content.Context): ChessTrainerApp =
            context.applicationContext as ChessTrainerApp
    }
}
