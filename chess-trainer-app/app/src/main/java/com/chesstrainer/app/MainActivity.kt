package com.chesstrainer.app

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chesstrainer.app.service.AccessibilityStatus
import com.chesstrainer.app.service.ScreenCaptureService
import com.chesstrainer.app.ui.OverlayService
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        /** Intent extras / actions understood by [ScreenCaptureService]. */
        const val EXTRA_RESULT_CODE = "com.chesstrainer.app.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.chesstrainer.app.extra.DATA"
        const val ACTION_CALIBRATE = "com.chesstrainer.app.action.CALIBRATE"

        private const val CALIBRATE_DELAY_MS = 5_000L
        private const val KEY_RUNNING = "running"
        private const val KEY_BOARD_FOUND = "board_found"

        /**
         * AnalysisCoordinator status messages that reveal whether a board is on screen. They
         * must match the coordinator's STATUS_* strings exactly.
         */
        private const val STATUS_BOARD_LOST = "Looking for chess board..."
        private val STATUS_BOARD_FOUND = setOf(
            "Board found — reading pieces...",
            "Board recognized",
            "Analyzing...",
        )
    }

    private lateinit var statusText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var calibrateButton: MaterialButton
    private lateinit var boardReaderStatusText: TextView
    private lateinit var enableBoardReaderButton: MaterialButton
    private lateinit var overlayStatusText: TextView
    private lateinit var captureStatusText: TextView
    private lateinit var engineStatusText: TextView
    private lateinit var boardStatusText: TextView

    private var running = false
    private var calibrationTimer: CountDownTimer? = null

    /** Whether the coordinator last reported a visible board (inferred from its status messages). */
    private var boardFound = false

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Notifications disabled; service status will be hidden", Toast.LENGTH_SHORT).show()
            }
            ensureOverlayPermission()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (OverlayService.canDrawOverlays(this)) {
                requestScreenCapture()
            } else {
                setStatus("Overlay permission denied. Enable \"Display over other apps\" to continue.")
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startServices(result.resultCode, data)
            } else {
                setStatus("Screen capture permission denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        running = savedInstanceState?.getBoolean(KEY_RUNNING) ?: false
        boardFound = savedInstanceState?.getBoolean(KEY_BOARD_FOUND) ?: false
        setContentView(buildContent())
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        // The overlay may have been stopped from its notification while we were away.
        running = isServiceRunning(OverlayService::class.java)
        // The coordinator outlives this activity; point its callbacks at the current instance
        // (e.g. after a rotation) so status and board detection keep updating.
        val coordinator = ChessTrainerApp.get(this).coordinator
        if (coordinator.isRunning()) attachCoordinatorListeners()
        refreshUi()
        updateAllStatuses()
    }

    /** Refreshes every row of the System Status section plus the Board Reader status. */
    private fun updateAllStatuses() {
        updateBoardReaderStatus()

        // Overlay permission
        val overlayOk = OverlayService.canDrawOverlays(this)
        overlayStatusText.text = if (overlayOk) "Overlay Permission: GRANTED" else "Overlay Permission: DENIED"
        overlayStatusText.setTextColor(
            ContextCompat.getColor(this, if (overlayOk) R.color.accent_green else R.color.accent_red)
        )

        // Screen capture
        val captureRunning = isServiceRunning(ScreenCaptureService::class.java)
        captureStatusText.text = if (captureRunning) "Screen Capture: ACTIVE" else "Screen Capture: OFF"
        captureStatusText.setTextColor(
            ContextCompat.getColor(this, if (captureRunning) R.color.accent_green else R.color.accent_red)
        )

        // Engine
        val coordinator = ChessTrainerApp.get(this).coordinator
        val engineRunning = coordinator.isRunning()
        engineStatusText.text = if (engineRunning) "Engine: RUNNING" else "Engine: STOPPED"
        engineStatusText.setTextColor(
            ContextCompat.getColor(this, if (engineRunning) R.color.accent_green else R.color.accent_red)
        )

        // Board detection: only meaningful while the coordinator is analysing frames.
        val (boardLabel, boardColor) = when {
            !engineRunning -> "Board Detection: OFF" to R.color.accent_red
            boardFound -> "Board Detection: FOUND" to R.color.accent_green
            else -> "Board Detection: SEARCHING" to R.color.accent_yellow
        }
        boardStatusText.text = boardLabel
        boardStatusText.setTextColor(ContextCompat.getColor(this, boardColor))
    }

    /** Reflects whether the board-reader accessibility service is enabled in system settings. */
    private fun updateBoardReaderStatus() {
        val enabled = AccessibilityStatus.isServiceEnabled(this)
        boardReaderStatusText.text = if (enabled) "Board Reader: ON" else "Board Reader: OFF"
        boardReaderStatusText.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.accent_green else R.color.accent_red)
        )
        enableBoardReaderButton.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** getRunningServices is deprecated but still reports this app's own services. */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_RUNNING, running)
        outState.putBoolean(KEY_BOARD_FOUND, boardFound)
    }

    override fun onDestroy() {
        calibrationTimer?.cancel()
        calibrationTimer = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(24f)
            setPadding(p, dp(48f), p, p)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Live move suggestions over your chess app"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        root.addView(subtitle, matchWrap().apply { topMargin = dp(4f) })

        statusText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            val p = dp(16f)
            setPadding(p, p, p, p)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
        }
        root.addView(statusText, matchWrap().apply { topMargin = dp(32f) })

        startButton = button(getString(R.string.start), R.color.accent_green) { onStartClicked() }
        root.addView(startButton, matchWrap().apply { topMargin = dp(32f) })

        stopButton = button(getString(R.string.stop), R.color.accent_red) { onStopClicked() }
        root.addView(stopButton, matchWrap().apply { topMargin = dp(12f) })

        // Secondary fallback: calibration normally happens automatically.
        calibrateButton = button("Re-calibrate", R.color.accent_blue) { onCalibrateClicked() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(40f)
            minimumHeight = dp(40f)
        }
        root.addView(
            calibrateButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(24f)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        boardReaderStatusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(boardReaderStatusText, matchWrap().apply { topMargin = dp(32f) })

        enableBoardReaderButton = button("Enable Board Reader", R.color.accent_blue) {
            AccessibilityStatus.openAccessibilitySettings(this)
        }
        root.addView(enableBoardReaderButton, matchWrap().apply { topMargin = dp(12f) })

        // Diagnostics: one colour-coded row per component (green = working, red = off/broken,
        // yellow = warning). Filled in by updateAllStatuses().
        val statusHeader = TextView(this).apply {
            text = "System Status"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(statusHeader, matchWrap().apply { topMargin = dp(32f) })

        overlayStatusText = statusRow("Overlay Permission")
        captureStatusText = statusRow("Screen Capture")
        engineStatusText = statusRow("Engine")
        boardStatusText = statusRow("Board Detection")
        val statusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(4f)
            setPadding(0, p, 0, p)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
            listOf(overlayStatusText, captureStatusText, engineStatusText, boardStatusText)
                .forEach { addView(it, matchWrap()) }
        }
        root.addView(statusPanel, matchWrap().apply { topMargin = dp(12f) })

        val hint = TextView(this).apply {
            text = "Start the service, then open your chess app. The board is detected " +
                "automatically when you show the starting position. " +
                "Use Re-calibrate if detection isn't working."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        root.addView(hint, matchWrap().apply { topMargin = dp(16f) })

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.background))
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** A System Status row; [label] is shown until [updateAllStatuses] fills in the state. */
    private fun statusRow(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START
        val p = dp(8f)
        setPadding(dp(16f), p, dp(16f), p)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun button(label: String, colorRes: Int, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Color.WHITE)
        backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, colorRes)
        cornerRadius = dp(10f)
        minHeight = dp(56f)
        setOnClickListener { onClick() }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun refreshUi() {
        if (calibrationTimer == null) {
            setStatus(
                if (running) "Running — open your chess app"
                else "Press Start, then open your chess app"
            )
        }
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        calibrateButton.isEnabled = running && calibrationTimer == null
    }

    // ---------------------------------------------------------------------------------------
    // Start flow: notifications -> overlay -> screen capture -> services
    // ---------------------------------------------------------------------------------------

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureOverlayPermission()
        }
    }

    private fun ensureOverlayPermission() {
        if (OverlayService.canDrawOverlays(this)) {
            requestScreenCapture()
            return
        }
        setStatus("Grant \"Display over other apps\" permission")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (_: Exception) {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun requestScreenCapture() {
        setStatus("Requesting screen capture permission…")
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startServices(resultCode: Int, data: Intent) {
        val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, captureIntent)
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))

        attachCoordinatorListeners()
        ChessTrainerApp.get(this).coordinator.start()

        running = true
        boardFound = false
        refreshUi()
        updateAllStatuses()
        // Set after refreshUi() so the generic "Running" text doesn't overwrite it;
        // the coordinator replaces this via statusListener once it finds the board.
        setStatus("Looking for chess board...")

        // Auto-open chess.com app
        try {
            val chessIntent = packageManager.getLaunchIntentForPackage("com.chess")
            if (chessIntent != null) {
                chessIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(chessIntent)
            }
        } catch (_: Exception) { }
    }

    /** Routes coordinator callbacks to this activity instance. */
    private fun attachCoordinatorListeners() {
        val coordinator = ChessTrainerApp.get(this).coordinator
        coordinator.statusListener = { msg -> runOnUiThread { onCoordinatorStatus(msg) } }
        coordinator.calibrationListener = { ok ->
            runOnUiThread {
                if (ok) setStatus("Calibration saved")
                else setStatus("Calibration failed — try again with the starting position")
            }
        }
    }

    private fun onCoordinatorStatus(message: String) {
        setStatus(message)
        when (message) {
            STATUS_BOARD_LOST -> boardFound = false
            in STATUS_BOARD_FOUND -> boardFound = true
        }
        updateAllStatuses()
    }

    // ---------------------------------------------------------------------------------------
    // Stop / calibrate
    // ---------------------------------------------------------------------------------------

    private fun onStopClicked() {
        calibrationTimer?.cancel()
        calibrationTimer = null
        ChessTrainerApp.get(this).coordinator.stop()
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        running = false
        boardFound = false
        refreshUi()
        updateAllStatuses()
    }

    private fun onCalibrateClicked() {
        if (!running) {
            Toast.makeText(this, "Press Start first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            "Open your chess app at the starting position. Capturing in ${CALIBRATE_DELAY_MS / 1000}s…",
            Toast.LENGTH_LONG,
        ).show()

        calibrationTimer = object : CountDownTimer(CALIBRATE_DELAY_MS, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished + 999) / 1000
                setStatus("Calibrating in ${seconds}s — show the starting position")
            }

            override fun onFinish() {
                calibrationTimer = null
                val intent = Intent(this@MainActivity, ScreenCaptureService::class.java)
                    .setAction(ACTION_CALIBRATE)
                try {
                    startService(intent)
                    setStatus("Calibration screenshot captured")
                } catch (e: IllegalStateException) {
                    setStatus("Calibration failed: ${e.message}")
                }
                startButton.isEnabled = !running
                stopButton.isEnabled = running
                calibrateButton.isEnabled = running
            }
        }.start()
        refreshUi()
        moveTaskToBack(true)
    }
}
